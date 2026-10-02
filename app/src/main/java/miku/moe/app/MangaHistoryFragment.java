package miku.moe.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MangaHistoryFragment extends Fragment {
    private RecyclerView historyRecyclerView;
    private HistoryListAdapter historyAdapter;
    private TextView emptyTextView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private KomikcastClient historyApi;
    private final Handler coverHandler = new Handler(Looper.getMainLooper());
    private boolean historyRefreshRunning = false;
    private boolean historyCoverReloadPending = false;
    private boolean historyReloadAfterRefresh = false;
    private String lastHistorySignature = "";
    private int historyRefreshToken = 0;
    private final HashSet<String> selectedHistoryKeys = new HashSet<>();
    private int savedHistoryScrollPosition = RecyclerView.NO_POSITION;
    private int savedHistoryScrollOffset = 0;
    private int trackedHistoryScrollPosition = 0;
    private int trackedHistoryScrollOffset = 0;
    private int historyScrollRequestToken = 0;
    private long lastHistoryCoverReloadAt = 0L;
    private static final long HISTORY_COVER_RELOAD_INTERVAL_MS = 1500L;
    private boolean restoreHistoryScrollPending = false;
    private static final int HISTORY_ROW_HEADER = 1;
    private static final int HISTORY_ROW_ITEM = 2;
    private final MangaRoomEvents.Listener roomListener = this::requestHistoryLoad;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_history, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        historyApi = MangaSourceFactory.create(requireContext());
        historyRecyclerView = view.findViewById(R.id.historyRecyclerView);
        historyAdapter = new HistoryListAdapter();
        if (historyRecyclerView != null) {
            historyRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            historyAdapter.setHasStableIds(true);
            historyRecyclerView.setAdapter(historyAdapter);
            historyRecyclerView.setHasFixedSize(true);
            historyRecyclerView.setItemAnimator(null);
            historyRecyclerView.setItemViewCacheSize(8);
            historyRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    trackHistoryScroll();
                }
                @Override public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    trackHistoryScroll();
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) historyScrollRequestToken++;
                }
            });
        }
        emptyTextView = view.findViewById(R.id.emptyTextView);
        swipeRefreshLayout = view.findViewById(R.id.historySwipeRefreshLayout);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(this::refreshStoredHistoryData);
        loadHistory();
    }

    @Override public void onStart() {
        super.onStart();
        MangaRoomEvents.addListener(roomListener);
    }

    @Override public void onResume() { super.onResume(); requestHistoryLoad(); }

    @Override public void onPause() {
        saveHistoryScrollPosition();
        super.onPause();
    }

    @Override public void onStop() {
        MangaRoomEvents.removeListener(roomListener);
        super.onStop();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            saveHistoryScrollPosition();
            historyRefreshToken++;
            historyRefreshRunning = false;
            historyCoverReloadPending = false;
            historyReloadAfterRefresh = false;
            coverHandler.removeCallbacksAndMessages(null);
            setHistoryRefreshing(false);
     //       clearImages(historyRecyclerView);
            return;
        }
        if (historyRecyclerView != null && historyAdapter != null && historyRecyclerView.getAdapter() == null) historyRecyclerView.setAdapter(historyAdapter);
        requestHistoryLoad();
    }

    public void reload() { requestHistoryLoad(); }

    public void refreshHistoryFromHeader() {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
        refreshStoredHistoryData();
    }

    public void clearHistoryFromHeader() {
        if (selectedHistoryKeys.isEmpty()) showClearAllDialog(); else showDeleteSelectedDialog();
    }

    public void syncHeaderActions() {
        boolean empty = MangaHistoryManager.entries(requireContext()).isEmpty();
        updateClearButton(empty);
    }

    @Override public void onDestroyView() {
        saveHistoryScrollPosition();
        historyRefreshToken++;
        historyRefreshRunning = false;
        historyCoverReloadPending = false;
        historyReloadAfterRefresh = false;
        coverHandler.removeCallbacksAndMessages(null);
    //   clearImages(historyRecyclerView);
        if (historyRecyclerView != null) historyRecyclerView.setAdapter(null);
        historyRecyclerView = null;
        historyAdapter = null;
        emptyTextView = null;
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(null);
        swipeRefreshLayout = null;
        super.onDestroyView();
    }

    private void requestHistoryLoad() {
        if (!isReadyForVisibleReload()) return;
        if (historyRefreshRunning) {
            historyReloadAfterRefresh = true;
            return;
        }
        loadHistory();
    }

    private boolean isReadyForVisibleReload() {
        if (!isAdded() || getView() == null || isHidden() || !isResumed()) return false;
        Fragment parent = getParentFragment();
        while (parent != null) {
            if (!parent.isAdded() || parent.isHidden() || !parent.isResumed()) return false;
            parent = parent.getParentFragment();
        }
        return true;
    }

    private void loadHistory() {
        if (!isAdded() || historyRecyclerView == null || historyAdapter == null) return;
        ArrayList<MangaHistoryManager.Entry> rawItems = MangaHistoryManager.entries(requireContext());
        pruneSelectedHistory(rawItems);
        autoSaveHistoryImages(rawItems, false);
        Map<String, List<MangaHistoryManager.Entry>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (MangaHistoryManager.Entry item : rawItems) {
            String key = makeDayKey(item.time);
            if (!grouped.containsKey(key)) { grouped.put(key, new ArrayList<>()); labels.put(key, makeDayLabel(item.time)); }
            grouped.get(key).add(item);
        }
        ArrayList<HistoryRow> rows = new ArrayList<>();
        for (String dayKey : grouped.keySet()) {
            rows.add(HistoryRow.header(labels.get(dayKey)));
            List<MangaHistoryManager.Entry> dayItems = grouped.get(dayKey);
            for (MangaHistoryManager.Entry item : dayItems) rows.add(HistoryRow.item(item));
        }
        boolean rowsChanged = historyAdapter.submit(rows);
        if (rowsChanged) notifyHistoryAdapterKeepingScroll();
        else restoreHistoryScrollPosition();
        boolean empty = rawItems.isEmpty();
        if (emptyTextView != null) {
            emptyTextView.setText("Belum ada history baca\nBuka chapter manga, nanti progres bacamu muncul di sini.");
            emptyTextView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        updateClearButton(empty);
    }

    private void saveHistoryScrollPosition() {
        if (historyRecyclerView == null) return;
        RecyclerView.LayoutManager layoutManager = historyRecyclerView.getLayoutManager();
        if (!(layoutManager instanceof LinearLayoutManager)) return;
        LinearLayoutManager manager = (LinearLayoutManager) layoutManager;
        int position = manager.findFirstVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION) return;
        View first = manager.findViewByPosition(position);
        savedHistoryScrollPosition = position;
        savedHistoryScrollOffset = first == null ? 0 : first.getTop() - historyRecyclerView.getPaddingTop();
        restoreHistoryScrollPending = true;
    }

    private void trackHistoryScroll() {
        if (historyRecyclerView == null) return;
        RecyclerView.LayoutManager layoutManager = historyRecyclerView.getLayoutManager();
        if (!(layoutManager instanceof LinearLayoutManager)) return;
        LinearLayoutManager manager = (LinearLayoutManager) layoutManager;
        int position = manager.findFirstVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION) return;
        View first = manager.findViewByPosition(position);
        trackedHistoryScrollPosition = position;
        trackedHistoryScrollOffset = first == null ? 0 : first.getTop() - historyRecyclerView.getPaddingTop();
    }

    private void notifyHistoryAdapterKeepingScroll() {
        if (historyAdapter == null) return;
        if (!restoreHistoryScrollPending) {
            trackHistoryScroll();
            savedHistoryScrollPosition = trackedHistoryScrollPosition;
            savedHistoryScrollOffset = trackedHistoryScrollOffset;
            restoreHistoryScrollPending = true;
        }
        historyAdapter.notifyDataSetChanged();
        restoreHistoryScrollPosition();
    }

    private void restoreHistoryScrollPosition() {
        if (!restoreHistoryScrollPending || historyRecyclerView == null || historyAdapter == null) return;
        int itemCount = historyAdapter.getItemCount();
        if (itemCount <= 0 || savedHistoryScrollPosition == RecyclerView.NO_POSITION) return;
        int position = Math.max(0, Math.min(savedHistoryScrollPosition, itemCount - 1));
        if (position == 0 && savedHistoryScrollOffset == 0) {
            restoreHistoryScrollPending = false;
            return;
        }
        applyHistoryScroll(position, savedHistoryScrollOffset, ++historyScrollRequestToken, 0);
    }

    private void applyHistoryScroll(final int position, final int offset, final int token, final int attempt) {
        if (historyRecyclerView == null || token != historyScrollRequestToken) return;
        RecyclerView.LayoutManager layoutManager = historyRecyclerView.getLayoutManager();
        if (!(layoutManager instanceof LinearLayoutManager)) return;
        ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(position, offset);
        historyRecyclerView.post(() -> {
            if (token != historyScrollRequestToken || historyRecyclerView == null || historyAdapter == null) return;
            RecyclerView.LayoutManager currentManager = historyRecyclerView.getLayoutManager();
            if (!(currentManager instanceof LinearLayoutManager)) return;
            int current = ((LinearLayoutManager) currentManager).findFirstVisibleItemPosition();
            if (current == position) {
                restoreHistoryScrollPending = false;
                return;
            }
            if (attempt < 5) applyHistoryScroll(position, offset, token, attempt + 1);
            else restoreHistoryScrollPending = false;
        });
    }

    private void pruneSelectedHistory(ArrayList<MangaHistoryManager.Entry> items) {
        if (selectedHistoryKeys.isEmpty()) return;
        HashSet<String> visibleKeys = new HashSet<>();
        for (MangaHistoryManager.Entry item : items) visibleKeys.add(historySelectionKey(item));
        selectedHistoryKeys.retainAll(visibleKeys);
    }

    private void autoSaveHistoryImages(ArrayList<MangaHistoryManager.Entry> items, boolean forceNetwork) {
        if (!isReadyForVisibleReload() || items == null || !MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(requireContext())) return;
        Context app = requireContext().getApplicationContext();
        ArrayList<String> covers = new ArrayList<>();
        ArrayList<String> sources = new ArrayList<>();
        for (MangaHistoryManager.Entry item : items) {
            if (item == null || item.manga == null || item.manga.coverImage == null || item.manga.coverImage.trim().isEmpty()) continue;
            covers.add(item.manga.coverImage.trim());
            sources.add(item.manga.getSourceId());
        }
        if (covers.isEmpty()) return;
        MangaCoroutines.io(() -> {
            for (int i = 0; i < covers.size(); i++) {
                String cover = covers.get(i);
                if (MangaCoverCache.isSaved(cover)) continue;
                MangaCoverCache.saveAsync(app, cover, sources.get(i), saved -> { if (saved) scheduleHistoryCoverReload(); }, forceNetwork);
            }
        });
    }

    private void scheduleHistoryCoverReload() {
        if (!isReadyForVisibleReload()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHistoryCoverReloadAt < HISTORY_COVER_RELOAD_INTERVAL_MS) return;
        if (historyRefreshRunning) {
            historyReloadAfterRefresh = true;
            return;
        }
        if (historyCoverReloadPending) return;
        historyCoverReloadPending = true;
        coverHandler.postDelayed(() -> {
            historyCoverReloadPending = false;
            if (!isReadyForVisibleReload() || historyRecyclerView == null) return;
            lastHistoryCoverReloadAt = android.os.SystemClock.uptimeMillis();
            notifyHistoryAdapterKeepingScroll();
        }, 240L);
    }

    private void refreshStoredHistoryData() {
        Context context = getContext();
        if (context == null || !isReadyForVisibleReload() || !MangaSettingsManager.isMangaModeEnabled(context)) { setHistoryRefreshing(false); return; }
        if (historyRefreshRunning) { setHistoryRefreshing(true); return; }
        ArrayList<MangaHistoryManager.Entry> items = MangaHistoryManager.entries(context);
        if (items.isEmpty()) { setHistoryRefreshing(false); return; }
        historyRefreshRunning = true;
        historyReloadAfterRefresh = false;
        int token = ++historyRefreshToken;
        refreshStoredHistoryItem(items, 0, token);
    }

    private void setHistoryRefreshing(boolean refreshing) {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(refreshing);
    }

    private void finishHistoryRefresh(int token) {
        if (token != historyRefreshToken) return;
        historyRefreshRunning = false;
        historyReloadAfterRefresh = false;
        historyCoverReloadPending = false;
        coverHandler.removeCallbacksAndMessages(null);
        if (isReadyForVisibleReload() && historyRecyclerView != null && historyAdapter != null) loadHistory();
        setHistoryRefreshing(false);
    }

    private void refreshStoredHistoryItem(ArrayList<MangaHistoryManager.Entry> items, int index, int token) {
        Context context = getContext();
        if (context == null || !isReadyForVisibleReload() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(context)) { finishHistoryRefresh(token); return; }
        if (index >= items.size()) {
            finishHistoryRefresh(token);
            return;
        }
        MangaHistoryManager.Entry item = items.get(index);
        if (item == null || item.manga == null || item.manga.slug == null || item.manga.slug.trim().isEmpty()) {
            refreshStoredHistoryItem(items, index + 1, token);
            return;
        }
        KomikcastClient api = MangaSourceFactory.createFor(item.manga, context);
        final MangaPost[] freshPost = new MangaPost[1];
        api.detail(item.manga.slug, new KomikcastClient.Result<MangaPost>() {
            @Override public void onSuccess(MangaPost data, boolean hasNext) {
                Context callbackContext = getContext();
                if (callbackContext == null || !isReadyForVisibleReload() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { finishHistoryRefresh(token); return; }
                freshPost[0] = data == null ? item.manga : data.withSource(item.manga.getSourceId(), item.manga.getSourceLabel());
                MangaLabelUtils.applyHiddenLabels(callbackContext, freshPost[0]);
                if (!MangaSettingsManager.shouldLoadLatestChapterLabel(callbackContext)) {
                    MangaHistoryManager.updateEntry(callbackContext, item, freshPost[0], null, 0);
                    refreshStoredHistoryItem(items, index + 1, token);
                    return;
                }
                refreshStoredHistoryChapter(api, items, index, item, freshPost[0], token);
            }
            @Override public void onError(String message) {
                Context callbackContext = getContext();
                if (callbackContext == null || !isReadyForVisibleReload() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { finishHistoryRefresh(token); return; }
                refreshStoredHistoryChapter(api, items, index, item, item.manga, token);
            }
        });
    }

    private void refreshStoredHistoryChapter(KomikcastClient api, ArrayList<MangaHistoryManager.Entry> items, int index, MangaHistoryManager.Entry item, MangaPost freshPost, int token) {
        api.chapters(item.manga.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter matched = null;
                if (chapters != null) {
                    for (MangaChapter chapter : chapters) {
                        if (chapter != null && Math.abs(chapter.index - item.chapterIndex) < 0.001f) { matched = chapter; break; }
                    }
                }
                Context callbackContext = getContext();
                if (callbackContext == null || !isReadyForVisibleReload() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { finishHistoryRefresh(token); return; }
                MangaHistoryManager.updateEntry(callbackContext, item, freshPost, matched, chapters == null ? 0 : chapters.size());
                if (freshPost != null && freshPost.coverImage != null && !freshPost.coverImage.trim().isEmpty() && MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(callbackContext)) MangaCoverCache.saveAsync(callbackContext.getApplicationContext(), freshPost.coverImage, freshPost.getSourceId(), saved -> { if (saved) scheduleHistoryCoverReload(); }, false);
                refreshStoredHistoryItem(items, index + 1, token);
            }
            @Override public void onError(String message) {
                Context callbackContext = getContext();
                if (callbackContext == null || !isReadyForVisibleReload() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { finishHistoryRefresh(token); return; }
                MangaHistoryManager.updateEntry(callbackContext, item, freshPost, null, 0);
                if (freshPost != null && freshPost.coverImage != null && !freshPost.coverImage.trim().isEmpty() && MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(callbackContext)) MangaCoverCache.saveAsync(callbackContext.getApplicationContext(), freshPost.coverImage, freshPost.getSourceId(), saved -> { if (saved) scheduleHistoryCoverReload(); }, false);
                refreshStoredHistoryItem(items, index + 1, token);
            }
        });
    }

    private void bindHistoryCard(ItemHolder holder, MangaHistoryManager.Entry item) {
        if (holder == null || item == null || item.manga == null) return;
        View itemView = holder.itemView;
        ImageView image = holder.image;
        TextView title = holder.title;
        TextView meta = holder.meta;
        ImageView sourceBadge = holder.sourceBadge;
        TextView badge = holder.badge;
        ProgressBar bar = holder.progress;
        View detailClickArea = holder.detailClickArea;
        MaterialButton detailButton = holder.detailButton;
        MaterialButton continueButton = holder.continueButton;
        holder.entry = item;
        applySelectionStyle(itemView, holder, historySelectionKey(item));
        String coverUrl = item.manga.coverImage == null ? "" : item.manga.coverImage.trim();
        String coverSource = item.manga.getSourceId();
        if (image != null && !coverUrl.isEmpty() && !MangaImageLoader.isLoaded(image, coverUrl, coverSource)) {
            MangaImageLoader.loadForSource(image, coverUrl, coverSource, !MangaCoverCache.isSaved(itemView.getContext(), coverUrl), null);
        }
        if (title != null) {
            title.setText(item.manga.title == null || item.manga.title.trim().isEmpty() ? "Manga" : item.manga.title);
            MangaTitleStyle.apply(title, itemView.getContext());
        }
        if (sourceBadge != null) {
            sourceBadge.setVisibility(View.VISIBLE);
            sourceBadge.setContentDescription(item.manga.getSourceLabel());
            String iconUrl = MangaSourceFactory.iconForSourceId(coverSource);
            if (iconUrl != null && !iconUrl.trim().isEmpty() && !MangaImageLoader.isLoaded(sourceBadge, iconUrl, coverSource)) {
                MangaImageLoader.loadForSource(sourceBadge, iconUrl, coverSource, false, null);
            }
        }
        if (badge != null) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(formatChapterBadge(item.chapterTitle));
        }
        int percent = item.totalPages > 0 ? Math.max(0, Math.min(100, ((item.page + 1) * 100) / item.totalPages)) : 0;
        if (bar != null) bar.setProgress(percent);
        String total = item.manga.totalChapters > 0 ? " • Total " + item.manga.totalChapters + " chapter" : "";
        if (meta != null) meta.setText("Hal. " + (item.page + 1) + "/" + item.totalPages + " • " + percent + "% dibaca • " + formatRelativeReadTime(item.time) + total);
        View.OnClickListener openDetail = v -> {
            MangaHistoryManager.Entry bound = holder.entry == null ? item : holder.entry;
            if (!selectedHistoryKeys.isEmpty()) { toggleSelection(bound); return; }
            saveHistoryScrollPosition();
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).openMangaDetail(bound.manga);
        };
        View.OnClickListener continueRead = v -> {
            MangaHistoryManager.Entry bound = holder.entry == null ? item : holder.entry;
            if (!selectedHistoryKeys.isEmpty()) toggleSelection(bound);
            else {
                saveHistoryScrollPosition();
                openReader(bound);
            }
        };
        View.OnLongClickListener selectLong = v -> {
            MangaHistoryManager.Entry bound = holder.entry == null ? item : holder.entry;
            toggleSelection(bound);
            return true;
        };
        itemView.setOnLongClickListener(selectLong);
        itemView.setOnClickListener(openDetail);
        if (detailClickArea != null) {
            detailClickArea.setOnLongClickListener(selectLong);
            detailClickArea.setOnClickListener(openDetail);
        }
        if (detailButton != null) detailButton.setOnClickListener(openDetail);
        if (continueButton != null) continueButton.setOnClickListener(continueRead);
    }

    private void toggleSelection(MangaHistoryManager.Entry item) {
        String key = historySelectionKey(item);
        if (key.isEmpty()) return;
        if (selectedHistoryKeys.contains(key)) selectedHistoryKeys.remove(key); else selectedHistoryKeys.add(key);
        lastHistorySignature = "";
        loadHistory();
    }

    private String historySelectionKey(MangaHistoryManager.Entry item) {
        if (item == null || item.manga == null || item.manga.slug == null) return "";
        String slug = item.manga.slug.trim();
        if (slug.isEmpty()) return "";
        return item.manga.getSourceId() + ":" + slug;
    }

    private String historyItemKey(MangaHistoryManager.Entry item) {
        if (item == null || item.manga == null || item.manga.slug == null) return "";
        String itemKey = item.key == null ? "" : item.key.trim();
        if (!itemKey.isEmpty()) return itemKey;
        String selectionKey = historySelectionKey(item);
        if (selectionKey.isEmpty()) return "";
        return selectionKey + ":" + makeDayKey(item.time);
    }

    private void applySelectionStyle(View card, ItemHolder holder, String key) {
        if (!(card instanceof MaterialCardView)) return;
        boolean selected = selectedHistoryKeys.contains(key);
        if (holder != null && key != null && key.equals(holder.appliedSelectionKey) && holder.appliedSelectedState == selected) return;
        MaterialCardView materialCard = (MaterialCardView) card;
        materialCard.setStrokeWidth(dp(selected ? 3 : 1));
        materialCard.setStrokeColor(resolveThemeColor(selected ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorSurfaceContainerHighest));
        materialCard.setCardBackgroundColor(resolveThemeColor(selected ? com.google.android.material.R.attr.colorSecondaryContainer : com.google.android.material.R.attr.colorSurfaceContainer));
        if (holder != null) {
            holder.appliedSelectionKey = key;
            holder.appliedSelectedState = selected;
        }
    }

    private int resolveThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) return ContextCompat.getColor(requireContext(), typedValue.resourceId);
            return typedValue.data;
        }
        return 0;
    }

    private void updateClearButton(boolean empty) {
        Fragment parent = getParentFragment();
        if (parent instanceof MangaFavoriteTabsFragment) ((MangaFavoriteTabsFragment) parent).updateHistoryActionState(empty, selectedHistoryKeys.size());
    }

    private void showDeleteSelectedDialog() {
        if (!isAdded() || selectedHistoryKeys.isEmpty()) return;
        int count = selectedHistoryKeys.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.drawable.ic_delete)
                .setTitle("Hapus history terpilih?")
                .setMessage(count + " manga yang dipilih akan dihapus dari semua tanggal history.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> deleteSelectedHistory())
                .show();
    }

    private void deleteSelectedHistory() {
        if (!isAdded() || selectedHistoryKeys.isEmpty()) return;
        MangaHistoryManager.delete(requireContext(), new HashSet<>(selectedHistoryKeys));
        selectedHistoryKeys.clear();
        loadHistory();
    }

    private void showClearAllDialog() {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.drawable.ic_delete)
                .setTitle("Hapus semua history manga?")
                .setMessage("Semua history manga akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    MangaHistoryManager.clear(requireContext());
                    selectedHistoryKeys.clear();
                    loadHistory();
                })
                .show();
    }

    private String formatChapterBadge(String chapterTitle) {
        if (chapterTitle == null || chapterTitle.trim().isEmpty()) return "Chapter -";
        String clean = chapterTitle.trim();
        if (clean.toLowerCase(java.util.Locale.ROOT).startsWith("chapter")) return clean;
        return "Chapter " + clean;
    }

    private void openReader(MangaHistoryManager.Entry item) {
        if (!isAdded() || item == null || item.manga == null || item.manga.slug == null || item.manga.slug.isEmpty()) return;
        MangaSourceFactory.createFor(item.manga, requireContext()).chapters(item.manga.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (!isAdded()) return;
                if (chapters == null || chapters.isEmpty()) { Toast.makeText(requireContext(), "Daftar chapter tidak tersedia", Toast.LENGTH_SHORT).show(); return; }
                item.manga.totalChapters = chapters.size();
                int pos = 0;
                for (int i = 0; i < chapters.size(); i++) if (Math.abs(chapters.get(i).index - item.chapterIndex) < 0.001f) { pos = i; break; }
                ((MainActivity)requireActivity()).openMangaReader(item.manga, chapters, pos);
            }
            @Override public void onError(String message) { if (isAdded()) Toast.makeText(requireContext(), "History manga gagal dibuka", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void clearImages(View view) {
        if (view == null) return;
        if (view instanceof ImageView) MangaImageLoader.clear((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearImages(group.getChildAt(i));
        }
    }

    private final class HistoryListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ArrayList<HistoryRow> rows = new ArrayList<>();

        boolean submit(ArrayList<HistoryRow> data) {
            String signature = historyRowsSignature(data);
            if (signature.equals(lastHistorySignature)) return false;
            lastHistorySignature = signature;
            rows.clear();
            if (data != null) rows.addAll(data);
            return true;
        }

        @Override public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        @Override public long getItemId(int position) {
            HistoryRow row = rows.get(position);
            if (row == null) return position;
            if (row.type == HISTORY_ROW_HEADER) return ("header:" + (row.label == null ? "" : row.label)).hashCode();
            if (row.entry != null) return historyItemKey(row.entry).hashCode();
            return position;
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == HISTORY_ROW_HEADER) {
                View view = inflater.inflate(R.layout.history_day_header, parent, false);
                return new HeaderHolder(view, view.findViewById(R.id.dayHeaderTextView));
            }
            View view = inflater.inflate(R.layout.manga_history_item, parent, false);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(14));
            view.setLayoutParams(params);
            return new ItemHolder(view);
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            HistoryRow row = rows.get(position);
            if (holder instanceof HeaderHolder) ((HeaderHolder) holder).title.setText(row.label == null ? "" : row.label);
            else if (holder instanceof ItemHolder && row.entry != null) bindHistoryCard((ItemHolder) holder, row.entry);
        }

        @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof ItemHolder) {
                ItemHolder itemHolder = (ItemHolder) holder;
                itemHolder.entry = null;
            }
            clearImages(holder.itemView);
            super.onViewRecycled(holder);
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderHolder(View itemView, TextView title) {
            super(itemView);
            this.title = title;
        }
    }

    private static final class ItemHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView meta;
        final ImageView sourceBadge;
        final TextView badge;
        final ProgressBar progress;
        final View detailClickArea;
        final MaterialButton detailButton;
        final MaterialButton continueButton;
        String appliedSelectionKey;
        boolean appliedSelectedState;
        MangaHistoryManager.Entry entry;

        ItemHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imageView);
            title = itemView.findViewById(R.id.titleTextView);
            meta = itemView.findViewById(R.id.metaTextView);
            sourceBadge = itemView.findViewById(R.id.sourceBadgeImageView);
            badge = itemView.findViewById(R.id.historyBadgeTextView);
            progress = itemView.findViewById(R.id.watchProgress);
            detailClickArea = itemView.findViewById(R.id.detailClickArea);
            detailButton = itemView.findViewById(R.id.detailButton);
            continueButton = itemView.findViewById(R.id.continueButton);
        }
    }

    private static final class HistoryRow {
        final int type;
        final String label;
        final MangaHistoryManager.Entry entry;

        private HistoryRow(int type, String label, MangaHistoryManager.Entry entry) {
            this.type = type;
            this.label = label;
            this.entry = entry;
        }

        static HistoryRow header(String label) {
            return new HistoryRow(HISTORY_ROW_HEADER, label, null);
        }

        static HistoryRow item(MangaHistoryManager.Entry entry) {
            return new HistoryRow(HISTORY_ROW_ITEM, null, entry);
        }
    }

    private String historyRowsSignature(ArrayList<HistoryRow> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (HistoryRow row : rows) {
            if (row == null) continue;
            builder.append(row.type).append('|');
            if (row.type == HISTORY_ROW_HEADER) {
                builder.append(row.label == null ? "" : row.label);
            } else if (row.entry != null && row.entry.manga != null) {
                String key = historyItemKey(row.entry);
                String selectionKey = historySelectionKey(row.entry);
                builder.append(key).append('|')
                        .append(selectedHistoryKeys.contains(selectionKey) ? 1 : 0).append('|')
                        .append(row.entry.manga.title == null ? "" : row.entry.manga.title).append('|')
                        .append(row.entry.manga.coverImage == null ? "" : row.entry.manga.coverImage).append('|')
                        .append(row.entry.chapterTitle == null ? "" : row.entry.chapterTitle).append('|')
                        .append(row.entry.page).append('|')
                        .append(row.entry.totalPages).append('|')
                        .append(row.entry.time);
            }
            builder.append(';');
        }
        return builder.toString();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String makeDayKey(long timestamp) { Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(timestamp <= 0 ? System.currentTimeMillis() : timestamp); return cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.DAY_OF_YEAR); }

    private static String makeDayLabel(long timestamp) {
        if (timestamp <= 0) return "Dibaca hari ini";
        long diffDays = dayDiff(timestamp);
        if (diffDays <= 0) return "Dibaca hari ini";
        if (diffDays == 1) return "Dibaca kemarin";
        return "Dibaca " + diffDays + " hari yang lalu";
    }

    private static String formatRelativeReadTime(long timestamp) {
        if (timestamp <= 0) return "Baru dibaca";
        long now = System.currentTimeMillis();
        long diff = Math.max(0L, now - timestamp);
        long minute = 60L * 1000L;
        long hour = 60L * minute;
        long day = 24L * hour;
        if (diff < minute) return "Baru saja";
        if (diff < hour) {
            long minutes = Math.max(1L, diff / minute);
            return minutes + " menit yang lalu";
        }
        if (diff < day) {
            long hours = Math.max(1L, diff / hour);
            return hours + " jam yang lalu";
        }
        long diffDays = dayDiff(timestamp);
        if (diffDays <= 0) return "Hari ini";
        if (diffDays == 1) return "Kemarin";
        return diffDays + " hari yang lalu";
    }

    private static long dayDiff(long timestamp) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(timestamp);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        return (today.getTimeInMillis() - start.getTimeInMillis()) / (24L * 60L * 60L * 1000L);
    }
}
