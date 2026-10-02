package miku.moe.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class MangaFavoriteFragment extends Fragment {
    private final ArrayList<MangaPost> favorites = new ArrayList<>();
    private MangaGridAdapter adapter;
    private TextView emptyTextView;
    private GridView gridView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ActivityResultLauncher<Intent> exportLauncher, importLauncher;
    private SharedPreferences favoritePreferences;
    private static final long MIN_FAVORITE_REFRESH_MS = 900L;
    private static final String DETAIL_CACHE_PREFS = "miku_manga_detail_cache";
    private static final long AUTO_FAVORITE_REFRESH_DELAY_MS = 250L;
    private static final long FAVORITE_COVER_RELOAD_INTERVAL_MS = 1200L;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean refreshingFavoriteData = false;
    private boolean pendingForcedFavoriteRefresh = false;
    private long favoriteRefreshStartedAt = 0L;
    private boolean suppressFavoriteReload = false;
    private boolean favoriteCoverReloadPending = false;
    private long lastFavoriteCoverReloadAt = 0L;
    private boolean pendingAutoFavoriteRefresh = false;
    private boolean pendingAutoFavoriteForceNetwork = false;
    private String lastFavoriteSignature = "";
    private int savedFavoriteScrollPosition = 0;
    private int savedFavoriteScrollOffset = 0;
    private int trackedFavoriteScrollPosition = 0;
    private int trackedFavoriteScrollOffset = 0;
    private int favoriteScrollRequestToken = 0;
    private boolean restoreFavoriteScrollPending = false;
    private final Map<String, ChapterIncrease> favoriteChapterIncreases = new HashMap<>();
    private final LinkedHashMap<String, FavoriteChapterUpdate> favoriteChapterUpdates = new LinkedHashMap<>();
    private final ArrayList<MangaPost> checkedFavoriteUpdatePosts = new ArrayList<>();
    private interface FavoriteTypeCallback { void done(MangaPost post); }
    private interface FavoriteTypeResultCallback { void done(String type); }
    private final SharedPreferences.OnSharedPreferenceChangeListener favoriteChangeListener = (prefs, key) -> {
        if ("items".equals(key) && !suppressFavoriteReload && isReadyForVisibleReload()) loadFavorites(false);
    };
    private final MangaRoomEvents.Listener roomListener = () -> {
        if (!suppressFavoriteReload && isReadyForVisibleReload()) loadFavorites(false);
    };

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> { if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) writeExport(result.getData().getData()); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> { if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) readImport(result.getData().getData()); });
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_favorite, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        swipeRefreshLayout = view.findViewById(R.id.favoriteSwipeRefreshLayout);
        gridView = view.findViewById(R.id.gridView);
        gridView.setNumColumns(3);
        gridView.setSmoothScrollbarEnabled(true);
        gridView.setScrollingCacheEnabled(false);
        gridView.setAnimationCacheEnabled(false);
        gridView.setCacheColorHint(0x00000000);
        gridView.setRecyclerListener(this::clearImages);
        gridView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                trackFavoriteScroll();
            }
            @Override public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                trackFavoriteScroll();
                if (scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL
                        || scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
                    favoriteScrollRequestToken++;
                }
            }
        });
        emptyTextView = view.findViewById(R.id.emptyTextView);
        adapter = new MangaGridAdapter(requireContext(), favorites, post -> openFavoriteDetail(post), true, post -> openLatestFavoriteChapter(post), false);
        adapter.setIkiruStyle(true);
        adapter.setStripChapterPrefix(true);
        adapter.setChapterInsideCover(true);
        adapter.setCachedCoverOnly(false);
        gridView.setAdapter(adapter);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(this::refreshFavoriteLocalFromSwipe);
        loadFavorites(false);
    }

    @Override public void onStart() {
        super.onStart();
        favoritePreferences = requireContext().getApplicationContext().getSharedPreferences("miku_manga_favorites", Context.MODE_PRIVATE);
        favoritePreferences.registerOnSharedPreferenceChangeListener(favoriteChangeListener);
        MangaRoomEvents.addListener(roomListener);
        loadFavorites(false);
    }

    @Override public void onResume() { super.onResume(); if (gridView != null) gridView.setNumColumns(3); if (isReadyForVisibleReload()) loadFavorites(false); }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            saveFavoriteScrollPosition();
            refreshHandler.removeCallbacksAndMessages(null);
            refreshingFavoriteData = false;
            pendingForcedFavoriteRefresh = false;
            pendingAutoFavoriteRefresh = false;
            pendingAutoFavoriteForceNetwork = false;
            favoriteCoverReloadPending = false;
            setFavoriteRefreshing(false);
       //     clearImages(gridView);
            return;
        }
        if (isReadyForVisibleReload()) {
            if (gridView != null) {
                if (adapter != null && gridView.getAdapter() == null) gridView.setAdapter(adapter);
                gridView.setNumColumns(3);
            }
            loadFavorites(false);
        }
    }

    private void clearImages(View view) {
        if (view == null) return;
        if (view instanceof ImageView) MangaImageLoader.clear((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearImages(group.getChildAt(i));
        }
    }

    @Override public void onPause() {
        saveFavoriteScrollPosition();
        super.onPause();
    }

    @Override public void onStop() {
        if (favoritePreferences != null) favoritePreferences.unregisterOnSharedPreferenceChangeListener(favoriteChangeListener);
        MangaRoomEvents.removeListener(roomListener);
        super.onStop();
    }

    public void refreshFavorites() { if (isReadyForVisibleReload()) loadFavorites(false); }

    private void refreshFavoriteLocalFromSwipe() {
        loadFavorites(false);
        setFavoriteRefreshing(false);
    }

    public void refreshFavoriteFromHeader() {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
        refreshFavoriteData(true);
    }

    public void exportFavoriteFromHeader() { exportFavorites(); }

    public void importFavoriteFromHeader() { importFavorites(); }

    public void openUpdateFromHeader() {
        Intent intent = new Intent(requireContext(), MikuUpdate.class);
        intent.putExtra(MikuUpdate.EXTRA_AUTO_CHECK, MikuUpdate.shouldAutoCheckOnOpen(requireContext()));
        startActivity(intent);
    }

    @Override public void onDestroyView() {
        saveFavoriteScrollPosition();
        if (gridView != null) {
            gridView.setOnScrollListener(null);
            gridView.setRecyclerListener(null);
            gridView.setAdapter(null);
        }
        gridView = null;
        adapter = null;
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(null);
        swipeRefreshLayout = null;
        refreshHandler.removeCallbacksAndMessages(null);
        favoriteCoverReloadPending = false;
        pendingAutoFavoriteRefresh = false;
        pendingAutoFavoriteForceNetwork = false;
        super.onDestroyView();
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

    private void exportFavorites() { Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/javascript"); intent.putExtra(Intent.EXTRA_TITLE, "miku_manga_favorite_backup.js"); exportLauncher.launch(intent); }
    private void importFavorites() { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); importLauncher.launch(intent); }
    private void writeExport(Uri uri) { try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) { out.write(MangaFavoriteManager.exportEncrypted(requireContext()).getBytes(StandardCharsets.UTF_8)); Toast.makeText(requireContext(), "Favorite manga diekspor", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(requireContext(), "Favorite manga gagal diekspor", Toast.LENGTH_SHORT).show(); } }
    private void readImport(Uri uri) { try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) { ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) != -1) bos.write(buf, 0, n); MangaFavoriteManager.importEncrypted(requireContext(), new String(bos.toByteArray(), StandardCharsets.UTF_8)); loadFavorites(false); Toast.makeText(requireContext(), "Favorite manga berhasil diimport", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(requireContext(), "File import manga tidak valid", Toast.LENGTH_SHORT).show(); } }
    private void loadFavorites() { loadFavorites(false); }

    private void loadFavorites(boolean syncNetwork) {
        if (!isAdded()) return;
        favorites.clear();
        favorites.addAll(MangaFavoriteManager.getFavorites(requireContext()));
        boolean localChanged = hydrateFavoriteLabelsFromLocalCache();
        applyStoredFavoriteChapterIncreases();
        autoSaveFavoriteImages();
        String currentSignature = favoriteListSignature();
        if (adapter != null && !currentSignature.equals(lastFavoriteSignature)) notifyFavoriteAdapterKeepingScroll();
        lastFavoriteSignature = currentSignature;
        if (emptyTextView != null) emptyTextView.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
        if (localChanged) saveFavoritesSilently();
        restoreFavoriteScrollPosition();
        if (syncNetwork) scheduleFavoriteDataRefresh(true);
    }

    private void trackFavoriteScroll() {
        if (gridView == null) return;
        int first = gridView.getFirstVisiblePosition();
        if (first < 0) return;
        View child = gridView.getChildAt(0);
        trackedFavoriteScrollPosition = first;
        trackedFavoriteScrollOffset = child == null ? 0 : child.getTop() - gridView.getPaddingTop();
    }

    private void saveFavoriteScrollPosition() {
        trackFavoriteScroll();
        if (gridView == null) return;
        savedFavoriteScrollPosition = Math.max(0, trackedFavoriteScrollPosition);
        savedFavoriteScrollOffset = trackedFavoriteScrollOffset;
        restoreFavoriteScrollPending = true;
    }

    private void notifyFavoriteAdapterKeepingScroll() {
        if (adapter == null) return;
        if (!restoreFavoriteScrollPending) {
            trackFavoriteScroll();
            savedFavoriteScrollPosition = Math.max(0, trackedFavoriteScrollPosition);
            savedFavoriteScrollOffset = trackedFavoriteScrollOffset;
            restoreFavoriteScrollPending = true;
        }
        adapter.notifyDataSetChanged();
        restoreFavoriteScrollPosition();
    }

    private void restoreFavoriteScrollPosition() {
        if (gridView == null || adapter == null || adapter.getCount() <= 0) return;
        if (!restoreFavoriteScrollPending) return;
        int position = Math.max(0, Math.min(savedFavoriteScrollPosition, adapter.getCount() - 1));
        int offset = savedFavoriteScrollOffset;
        if (position == 0 && offset == 0) {
            restoreFavoriteScrollPending = false;
            return;
        }
        applyFavoriteScroll(position, offset, ++favoriteScrollRequestToken, 0);
    }

    private void applyFavoriteScroll(final int position, final int offset, final int token, final int attempt) {
        if (gridView == null || adapter == null || adapter.getCount() <= 0) return;
        if (token != favoriteScrollRequestToken) return;
        int safePosition = Math.max(0, Math.min(position, adapter.getCount() - 1));
        gridView.setSelectionFromTop(safePosition, offset);
        gridView.post(() -> {
            if (token != favoriteScrollRequestToken || gridView == null || adapter == null) return;
            int current = gridView.getFirstVisiblePosition();
            if (current == safePosition) {
                restoreFavoriteScrollPending = false;
                return;
            }
            if (attempt < 5) applyFavoriteScroll(safePosition, offset, token, attempt + 1);
            else restoreFavoriteScrollPending = false;
        });
    }

    private String favoriteListSignature() {
        StringBuilder builder = new StringBuilder();
        for (MangaPost post : favorites) {
            if (post == null) continue;
            builder.append(post.getSourceId()).append('|')
                    .append(post.slug == null ? "" : post.slug).append('|')
                    .append(post.title == null ? "" : post.title).append('|')
                    .append(post.coverImage == null ? "" : post.coverImage).append('|')
                    .append(post.latestChapter == null ? "" : post.latestChapter).append('|')
                    .append(post.totalChapters).append(';');
        }
        return builder.toString();
    }

    private boolean hydrateFavoriteLabelsFromLocalCache() {
        if (!isAdded() || favorites.isEmpty()) return false;
        SharedPreferences prefs = requireContext().getApplicationContext().getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE);
        boolean changed = false;
        for (MangaPost post : favorites) {
            if (post == null || empty(post.slug)) continue;
            String raw = prefs.getString(post.getSourceId() + "_" + post.slug, "");
            if (empty(raw)) continue;
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject manga = root.optJSONObject("manga");
                if (manga != null) {
                    changed |= fillIfEmpty(post, "title", manga.optString("title", ""));
                    changed |= fillIfEmpty(post, "cover", manga.optString("coverImage", ""));
                    changed |= fillIfEmpty(post, "author", manga.optString("author", ""));
                    changed |= fillIfEmpty(post, "status", manga.optString("status", ""));
                    changed |= fillIfEmpty(post, "synopsis", manga.optString("synopsis", ""));
                    changed |= fillIfEmpty(post, "genre", manga.optString("genre", ""));
                    changed |= fillIfEmpty(post, "info", manga.optString("info", ""));
                    changed |= applyCachedTypeLabel(post, manga.optString("typeLabel", ""), manga.optString("genre", "") + " " + manga.optString("status", "") + " " + manga.optString("synopsis", "") + " " + manga.optString("info", ""));
                    changed |= fillIfEmpty(post, "latest", manga.optString("latestChapter", ""));
                    changed |= fillIfEmpty(post, "date", manga.optString("latestChapterDate", ""));
                    int cachedTotal = manga.optInt("totalChapters", 0);
                    if (cachedTotal > post.totalChapters) {
                        post.totalChapters = cachedTotal;
                        changed = true;
                    }
                }
                JSONArray chapters = root.optJSONArray("chapters");
                if (chapters != null && chapters.length() > 0) changed |= applyCachedLatestChapter(post, chapters);
            } catch (Exception ignored) {}
        }
        return changed;
    }

    private boolean applyCachedLatestChapter(MangaPost post, JSONArray chapters) {
        float newestIndex = -1f;
        String newestDate = "";
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject item = chapters.optJSONObject(i);
            if (item == null) continue;
            float index = (float) item.optDouble("index", -1d);
            if (index > newestIndex) {
                newestIndex = index;
                newestDate = item.optString("date", "");
            }
        }
        boolean changed = false;
        if (chapters.length() > post.totalChapters) {
            post.totalChapters = chapters.length();
            changed = true;
        }
        if (newestIndex >= 0f) {
            float currentIndex = parseChapterIndex(post.latestChapter);
            if (empty(post.latestChapter) || currentIndex < newestIndex) {
                post.latestChapter = "Chapter " + MangaChapter.formatIndex(newestIndex);
                changed = true;
            }
            if (!empty(newestDate) && empty(post.latestChapterDate)) {
                post.latestChapterDate = newestDate;
                changed = true;
            }
        }
        return changed;
    }

    private boolean applyCachedTypeLabel(MangaPost post, String value, String support) {
        if (post == null) return false;
        String cachedType = MangaLabelUtils.normalizeStoredType(value, support);
        if (cachedType.isEmpty()) return false;
        String currentType = MangaLabelUtils.normalizeStoredType(post.typeLabel, (post.genre == null ? "" : post.genre) + " " + (post.status == null ? "" : post.status) + " " + (post.info == null ? "" : post.info));
        if (currentType.isEmpty() || "MANGA".equals(currentType) || MangaLabelUtils.isSpecificCountryType(cachedType)) {
            if (!cachedType.equals(currentType) || post.typeLabel == null || !cachedType.equals(post.typeLabel.trim())) {
                post.typeLabel = cachedType;
                return true;
            }
        }
        return false;
    }

    private boolean fillIfEmpty(MangaPost post, String field, String value) {
        if (empty(value)) return false;
        if ("title".equals(field) && empty(post.title)) { post.title = value; return true; }
        if ("cover".equals(field) && empty(post.coverImage)) { post.coverImage = value; return true; }
        if ("author".equals(field) && empty(post.author)) { post.author = value; return true; }
        if ("status".equals(field) && empty(post.status)) { post.status = value; return true; }
        if ("synopsis".equals(field) && empty(post.synopsis)) { post.synopsis = value; return true; }
        if ("genre".equals(field) && empty(post.genre)) { post.genre = value; return true; }
        if ("info".equals(field) && empty(post.info)) { post.info = value; return true; }
        if ("type".equals(field) && empty(post.typeLabel)) { post.typeLabel = value; return true; }
        if ("latest".equals(field) && empty(post.latestChapter)) { post.latestChapter = value; return true; }
        if ("date".equals(field) && empty(post.latestChapterDate)) { post.latestChapterDate = value; return true; }
        return false;
    }

    private void saveFavoritesSilently() {
        if (!isAdded()) return;
        suppressFavoriteReload = true;
        try {
            MangaFavoriteManager.saveFavorites(requireContext(), favorites);
        } finally {
            suppressFavoriteReload = false;
        }
    }

    private void scheduleFavoriteDataRefresh(boolean forceNetwork) {
        if (!isReadyForVisibleReload() || favorites.isEmpty()) return;
        if (forceNetwork) pendingAutoFavoriteForceNetwork = true;
        if (refreshingFavoriteData) {
            if (forceNetwork) pendingForcedFavoriteRefresh = true;
            return;
        }
        if (pendingAutoFavoriteRefresh) return;
        pendingAutoFavoriteRefresh = true;
        refreshHandler.postDelayed(() -> {
            pendingAutoFavoriteRefresh = false;
            boolean shouldForce = pendingAutoFavoriteForceNetwork;
            pendingAutoFavoriteForceNetwork = false;
            if (isReadyForVisibleReload()) refreshFavoriteData(shouldForce);
        }, AUTO_FAVORITE_REFRESH_DELAY_MS);
    }

    private void autoSaveFavoriteImages() {
        autoSaveFavoriteImages(false);
    }

    private void autoSaveFavoriteImages(boolean forceNetwork) {
        if (!isReadyForVisibleReload() || !MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(requireContext())) return;
        Context app = requireContext().getApplicationContext();
        ArrayList<String> covers = new ArrayList<>();
        ArrayList<String> sources = new ArrayList<>();
        for (MangaPost post : favorites) {
            if (post == null || post.coverImage == null || post.coverImage.trim().isEmpty()) continue;
            covers.add(post.coverImage.trim());
            sources.add(post.getSourceId());
        }
        if (covers.isEmpty()) return;
        MangaCoroutines.io(() -> {
            for (int i = 0; i < covers.size(); i++) {
                String cover = covers.get(i);
                if (MangaCoverCache.isSaved(cover)) continue;
                MangaCoverCache.saveAsync(app, cover, sources.get(i), saved -> { if (saved) scheduleFavoriteCoverReload(); }, forceNetwork);
            }
        });
    }

    private void scheduleFavoriteCoverReload() {
        if (!isReadyForVisibleReload() || favoriteCoverReloadPending) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastFavoriteCoverReloadAt < FAVORITE_COVER_RELOAD_INTERVAL_MS) return;
        if (!hasMissingFavoriteCover()) return;
        favoriteCoverReloadPending = true;
        refreshHandler.postDelayed(() -> {
            favoriteCoverReloadPending = false;
            lastFavoriteCoverReloadAt = SystemClock.uptimeMillis();
            if (!isReadyForVisibleReload() || adapter == null) return;
            if (!hasMissingFavoriteCover()) return;
            notifyFavoriteAdapterKeepingScroll();
        }, 160L);
    }

    private boolean hasMissingFavoriteCover() {
        if (gridView == null) return false;
        int first = gridView.getFirstVisiblePosition();
        int last = gridView.getLastVisiblePosition();
        for (int i = first; i <= last; i++) {
            View child = gridView.getChildAt(i - first);
            if (child == null) continue;
            ImageView image = child.findViewById(R.id.imageView);
            if (image != null && image.getDrawable() == null) return true;
        }
        return false;
    }

    private void refreshFavoriteData(boolean forceNetwork){
        if(!isReadyForVisibleReload()) { setFavoriteRefreshing(false); return; }
        if(favorites.isEmpty()) favorites.addAll(MangaFavoriteManager.getFavorites(requireContext()));
        if(refreshingFavoriteData) {
            if(forceNetwork) pendingForcedFavoriteRefresh = true;
            setFavoriteRefreshing(true);
            return;
        }
        if(favorites.isEmpty()) { setFavoriteRefreshing(false); return; }
        refreshingFavoriteData = true;
        favoriteRefreshStartedAt = System.currentTimeMillis();
        setFavoriteRefreshing(true);
        favoriteChapterUpdates.clear();
        checkedFavoriteUpdatePosts.clear();
        if(forceNetwork) {
            MangaMemoryCache.clearRegistered();
            autoSaveFavoriteImages(false);
        }
        ArrayList<MangaPost> snapshot = favoriteSnapshotCopy(favorites);
        refreshFavoriteAt(snapshot, 0, false);
    }

    private ArrayList<MangaPost> favoriteSnapshotCopy(ArrayList<MangaPost> source) {
        ArrayList<MangaPost> out = new ArrayList<>();
        if (source == null) return out;
        for (MangaPost post : source) out.add(copyFavoritePost(post));
        return out;
    }

    private MangaPost copyFavoritePost(MangaPost post) {
        if (post == null) return null;
        MangaPost copy = new MangaPost(post.slug, post.title, post.coverImage, post.author, post.status, post.synopsis, post.genre, post.typeLabel, post.latestChapter, post.latestChapterDate).withSource(post.getSourceId(), post.getSourceLabel());
        copy.info = post.info;
        copy.totalChapters = post.totalChapters;
        copy.favoriteChapterBase = post.favoriteChapterBase;
        copy.favoriteChapterAdded = post.favoriteChapterAdded;
        copy.historyChapterIndex = post.historyChapterIndex;
        copy.historyLastRead = post.historyLastRead;
        copy.historyPage = post.historyPage;
        copy.historyTotalPages = post.historyTotalPages;
        return copy;
    }

    private void setFavoriteRefreshing(boolean refreshing) {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(refreshing);
    }

    private void finishFavoriteRefresh(){
        refreshingFavoriteData = false;
        if(pendingForcedFavoriteRefresh){
            pendingForcedFavoriteRefresh = false;
            refreshHandler.postDelayed(() -> {
                if(isReadyForVisibleReload()) refreshFavoriteData(true);
            }, 120);
            return;
        }
        long elapsed = System.currentTimeMillis() - favoriteRefreshStartedAt;
        long delay = Math.max(0L, MIN_FAVORITE_REFRESH_MS - elapsed);
        refreshHandler.postDelayed(() -> {
            if(isReadyForVisibleReload()) setFavoriteRefreshing(false);
        }, delay);
    }

    private void refreshFavoriteAt(ArrayList<MangaPost> snapshot, int index, boolean changed){
        if(!isReadyForVisibleReload()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
        if(index >= snapshot.size()){
            suppressFavoriteReload = true;
            MangaFavoriteManager.saveFavorites(requireContext(), favorites);
            MikuUpdate.recordExternalCheck(requireContext().getApplicationContext(), favoriteSnapshotCopy(checkedFavoriteUpdatePosts), externalUpdateItems());
            checkedFavoriteUpdatePosts.clear();
            refreshHandler.postDelayed(() -> {
                suppressFavoriteReload = false;
                if(isReadyForVisibleReload()) loadFavorites(false);
            }, 500);
            notifyFavoriteAdapterKeepingScroll();
            finishFavoriteRefresh();
            showFavoriteUpdateDialogIfNeeded();
            return;
        }
        MangaPost oldPost = snapshot.get(index);
        if(oldPost == null || oldPost.slug == null || oldPost.slug.trim().isEmpty()){
            refreshFavoriteAt(snapshot, index + 1, changed);
            return;
        }
        KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(oldPost.getSourceId());
        sourceApi.detail(oldPost.slug, new KomikcastClient.Result<MangaPost>(){
            @Override public void onSuccess(MangaPost fresh, boolean next){
                if(!isReadyForVisibleReload()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                MangaPost merged = mergeFavorite(oldPost, fresh);
                resolveFavoriteType(oldPost, merged, sourceApi, resolved -> {
                    if(!isReadyForVisibleReload()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                    applyFavoriteHiddenLabels(resolved);
                    finishFavoriteResolvedCheck(snapshot, index, changed, oldPost, resolved);
                });
            }
            @Override public void onError(String message){
                if(!isReadyForVisibleReload()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                resolveFavoriteType(oldPost, oldPost, sourceApi, resolved -> {
                    if(!isReadyForVisibleReload()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                    finishFavoriteResolvedCheck(snapshot, index, changed, oldPost, resolved);
                });
            }
        });
    }

    private void finishFavoriteResolvedCheck(ArrayList<MangaPost> snapshot, int index, boolean changed, MangaPost oldPost, MangaPost resolved){
        MangaRepository.INSTANCE.chaptersReliableFuture(oldPost.getSourceId(), oldPost.slug).whenComplete((chapters, chapterError) -> {
            if(!isReadyForVisibleReload()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
            if(chapterError == null && chapters != null){
                MikuUpdate.UpdateBaselineSnapshot baseline = MikuUpdate.storedBaseline(requireContext(), oldPost);
                MangaUpdateCheckLogic.applyLatestChapter(resolved, chapters);
                applyFavoriteChapterIncrease(resolved, oldPost, baseline, chapters);
                checkedFavoriteUpdatePosts.add(copyFavoritePost(resolved));
            }
            boolean itemChanged = replaceFavorite(oldPost, resolved);
            refreshHandler.postDelayed(() -> refreshFavoriteAt(snapshot, index + 1, changed || itemChanged), 120);
        });
    }

    private ArrayList<MikuUpdate.ExternalCheckItem> externalUpdateItems(){
        ArrayList<MikuUpdate.ExternalCheckItem> items = new ArrayList<>();
        for(FavoriteChapterUpdate update : favoriteChapterUpdates.values()){
            if(update == null || update.post == null) continue;
            items.add(new MikuUpdate.ExternalCheckItem(update.post, update.added, update.post.latestChapter));
        }
        return items;
    }

    private void resolveFavoriteType(MangaPost oldPost, MangaPost target, KomikcastClient sourceApi, FavoriteTypeCallback callback){
        if(callback == null) return;
        if(target == null){
            callback.done(oldPost);
            return;
        }
        if(!isReadyForVisibleReload()){
            callback.done(target);
            return;
        }
        String currentType = MangaLabelUtils.typeForFlag(target);
        if(MangaLabelUtils.isSpecificCountryType(currentType) || "DOUJINSHI".equals(currentType) || "DOUJIN".equals(currentType) || "ONESHOT".equals(currentType)){
            target.typeLabel = currentType;
            callback.done(target);
            return;
        }
        String cacheType = typeFromDetailCache(target);
        if(cacheType.isEmpty()) cacheType = typeFromDetailCache(oldPost);
        if(!cacheType.isEmpty()){
            target.typeLabel = cacheType;
            callback.done(target);
            return;
        }
        String query = firstNonEmpty(target.title, oldPost == null ? "" : oldPost.title, target.slug);
        if(query.isEmpty() || sourceApi == null){
            applyLocalTypeFallback(target);
            callback.done(target);
            return;
        }
        sourceApi.list(1, "latest", query, "", new KomikcastClient.Result<ArrayList<MangaPost>>(){
            @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext){
                if(!isReadyForVisibleReload()){
                    callback.done(target);
                    return;
                }
                MangaPost matched = matchingSearchPost(target, data);
                String foundType = typeFromSearchPost(matched);
                if(!foundType.isEmpty()){
                    mergeResolvedFavoriteIdentity(target, matched, target.getSourceId());
                    target.typeLabel = foundType;
                    callback.done(target);
                    return;
                }
                resolveFavoriteTypeBySourceFilters(target, oldPost, sourceApi, resolvedType -> {
                    if(!resolvedType.isEmpty()){
                        target.typeLabel = resolvedType;
                        callback.done(target);
                    } else {
                        resolveFavoriteTypeAcrossSources(target, oldPost, callback);
                    }
                });
            }
            @Override public void onError(String message){
                resolveFavoriteTypeBySourceFilters(target, oldPost, sourceApi, resolvedType -> {
                    if(!resolvedType.isEmpty()){
                        target.typeLabel = resolvedType;
                        callback.done(target);
                    } else {
                        resolveFavoriteTypeAcrossSources(target, oldPost, callback);
                    }
                });
            }
        });
    }

    private void resolveFavoriteTypeBySourceFilters(MangaPost target, MangaPost oldPost, KomikcastClient sourceApi, FavoriteTypeResultCallback callback){
        if(callback == null) return;
        if(!isReadyForVisibleReload() || sourceApi == null || target == null){
            callback.done("");
            return;
        }
        String query = firstNonEmpty(target.title, oldPost == null ? "" : oldPost.title, target.slug);
        if(query.isEmpty()){
            callback.done("");
            return;
        }
        sourceApi.genres(new KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>>(){
            @Override public void onSuccess(ArrayList<KomikcastClient.GenreItem> genres, boolean hasNext){
                ArrayList<TypeFilterCandidate> candidates = typeFilterCandidates(genres);
                if(candidates.isEmpty()){
                    callback.done("");
                    return;
                }
                resolveFavoriteTypeByCandidate(target, sourceApi, query, candidates, 0, callback);
            }
            @Override public void onError(String message){
                callback.done("");
            }
        });
    }

    private void resolveFavoriteTypeByCandidate(MangaPost target, KomikcastClient sourceApi, String query, ArrayList<TypeFilterCandidate> candidates, int index, FavoriteTypeResultCallback callback){
        if(callback == null) return;
        if(!isReadyForVisibleReload() || target == null || sourceApi == null || index >= candidates.size()){
            callback.done("");
            return;
        }
        TypeFilterCandidate candidate = candidates.get(index);
        sourceApi.list(1, "latest", query, candidate.value, new KomikcastClient.Result<ArrayList<MangaPost>>(){
            @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext){
                if(!isReadyForVisibleReload()){
                    callback.done("");
                    return;
                }
                MangaPost matched = matchingSearchPost(target, data);
                if(matched != null){
                    String found = MangaLabelUtils.normalizeStoredType(matched.typeLabel, firstNonEmpty(matched.genre, "") + " " + firstNonEmpty(matched.status, "") + " " + firstNonEmpty(matched.info, ""));
                    callback.done(found.isEmpty() ? candidate.type : found);
                    return;
                }
                resolveFavoriteTypeByCandidate(target, sourceApi, query, candidates, index + 1, callback);
            }
            @Override public void onError(String message){
                resolveFavoriteTypeByCandidate(target, sourceApi, query, candidates, index + 1, callback);
            }
        });
    }

    private ArrayList<TypeFilterCandidate> typeFilterCandidates(ArrayList<KomikcastClient.GenreItem> genres){
        ArrayList<TypeFilterCandidate> out = new ArrayList<>();
        if(genres == null) return out;
        addTypeFilterCandidate(out, genres, "MANHWA");
        addTypeFilterCandidate(out, genres, "MANHUA");
        addTypeFilterCandidate(out, genres, "MANGA");
        return out;
    }

    private void addTypeFilterCandidate(ArrayList<TypeFilterCandidate> out, ArrayList<KomikcastClient.GenreItem> genres, String type){
        for(KomikcastClient.GenreItem item : genres){
            if(item == null) continue;
            String label = firstNonEmpty(item.title, item.value);
            String normalized = MangaLabelUtils.normalizeStoredType(label, item.value);
            if(type.equals(normalized)) out.add(new TypeFilterCandidate(type, item.value));
        }
    }

    private MangaPost matchingSearchPost(MangaPost target, ArrayList<MangaPost> data){
        if(target == null || data == null || data.isEmpty()) return null;
        String targetSlug = safeLower(target.slug);
        String targetTitle = safeLower(target.title);
        String targetSlugKey = compactKey(target.slug);
        String targetTitleKey = compactKey(target.title);
        for(MangaPost item : data){
            if(item == null) continue;
            if(!targetSlug.isEmpty() && targetSlug.equals(safeLower(item.slug))) return item;
        }
        for(MangaPost item : data){
            if(item == null) continue;
            if(!targetTitle.isEmpty() && targetTitle.equals(safeLower(item.title))) return item;
        }
        for(MangaPost item : data){
            if(item == null) continue;
            String itemSlugKey = compactKey(item.slug);
            String itemTitleKey = compactKey(item.title);
            if(!targetSlugKey.isEmpty() && targetSlugKey.equals(itemSlugKey)) return item;
            if(!targetTitleKey.isEmpty() && targetTitleKey.equals(itemTitleKey)) return item;
        }
        for(MangaPost item : data){
            if(item == null) continue;
            String itemTitleKey = compactKey(item.title);
            if(!targetTitleKey.isEmpty() && !itemTitleKey.isEmpty() && (itemTitleKey.contains(targetTitleKey) || targetTitleKey.contains(itemTitleKey))) return item;
        }
        return null;
    }

    private String typeFromSearchResult(MangaPost target, ArrayList<MangaPost> data){
        if(target == null || data == null || data.isEmpty()) return "";
        MangaPost best = matchingSearchPost(target, data);
        if(best == null) return "";
        return MangaLabelUtils.normalizeStoredType(best.typeLabel, firstNonEmpty(best.genre, "") + " " + firstNonEmpty(best.status, "") + " " + firstNonEmpty(best.info, ""));
    }

    private String typeFromSearchPost(MangaPost post){
        if(post == null) return "";
        return MangaLabelUtils.normalizeStoredType(post.typeLabel, firstNonEmpty(post.genre, "") + " " + firstNonEmpty(post.status, "") + " " + firstNonEmpty(post.synopsis, "") + " " + firstNonEmpty(post.info, ""));
    }

    private void resolveFavoriteTypeAcrossSources(MangaPost target, MangaPost oldPost, FavoriteTypeCallback callback){
        if(callback == null) return;
        if(!isReadyForVisibleReload() || target == null){
            callback.done(target);
            return;
        }
        ArrayList<String> sourceIds = favoriteSourceScanOrder(target, oldPost);
        String query = firstNonEmpty(target.title, oldPost == null ? "" : oldPost.title, target.slug, oldPost == null ? "" : oldPost.slug);
        if(sourceIds.isEmpty() || query.isEmpty()){
            applyLocalTypeFallback(target);
            callback.done(target);
            return;
        }
        resolveFavoriteTypeAcrossSourceAt(target, query, sourceIds, 0, callback);
    }

    private ArrayList<String> favoriteSourceScanOrder(MangaPost target, MangaPost oldPost){
        ArrayList<String> out = new ArrayList<>();
        addSourceScanId(out, target == null ? "" : target.getSourceId());
        addSourceScanId(out, oldPost == null ? "" : oldPost.getSourceId());
        String[] all = MangaSourceFactory.allSourceIds();
        if(all != null) for(String sourceId : all) addSourceScanId(out, sourceId);
        return out;
    }

    private void addSourceScanId(ArrayList<String> out, String sourceId){
        if(out == null || sourceId == null || sourceId.trim().isEmpty()) return;
        String value = sourceId.trim();
        for(String item : out) if(value.equals(item)) return;
        out.add(value);
    }

    private void resolveFavoriteTypeAcrossSourceAt(MangaPost target, String query, ArrayList<String> sourceIds, int index, FavoriteTypeCallback callback){
        if(callback == null) return;
        if(!isReadyForVisibleReload() || target == null){
            callback.done(target);
            return;
        }
        if(sourceIds == null || index >= sourceIds.size()){
            applyLocalTypeFallback(target);
            callback.done(target);
            return;
        }
        String sourceId = sourceIds.get(index);
        KomikcastClient api = MangaSourceFactory.createBySourceId(sourceId);
        api.list(1, "latest", query, "", new KomikcastClient.Result<ArrayList<MangaPost>>(){
            @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext){
                if(!isReadyForVisibleReload()){
                    callback.done(target);
                    return;
                }
                MangaPost matched = matchingSearchPost(target, data);
                if(matched == null){
                    resolveFavoriteTypeAcrossSourceAt(target, query, sourceIds, index + 1, callback);
                    return;
                }
                String listedType = typeFromSearchPost(matched);
                mergeResolvedFavoriteIdentity(target, matched, sourceId);
                if(!listedType.isEmpty()){
                    target.typeLabel = listedType;
                    callback.done(target);
                    return;
                }
                api.detail(matched.slug, new KomikcastClient.Result<MangaPost>(){
                    @Override public void onSuccess(MangaPost detail, boolean next){
                        if(!isReadyForVisibleReload()){
                            callback.done(target);
                            return;
                        }
                        if(detail != null){
                            mergeResolvedFavoriteIdentity(target, detail, sourceId);
                            String detailType = MangaLabelUtils.normalizeStoredType(detail.typeLabel, firstNonEmpty(detail.genre, "") + " " + firstNonEmpty(detail.status, "") + " " + firstNonEmpty(detail.synopsis, "") + " " + firstNonEmpty(detail.info, ""));
                            if(!detailType.isEmpty()){
                                target.typeLabel = detailType;
                                callback.done(target);
                                return;
                            }
                        }
                        resolveFavoriteTypeAcrossSourceAt(target, query, sourceIds, index + 1, callback);
                    }
                    @Override public void onError(String message){
                        resolveFavoriteTypeAcrossSourceAt(target, query, sourceIds, index + 1, callback);
                    }
                });
            }
            @Override public void onError(String message){
                resolveFavoriteTypeAcrossSourceAt(target, query, sourceIds, index + 1, callback);
            }
        });
    }

    private void mergeResolvedFavoriteIdentity(MangaPost target, MangaPost source, String sourceId){
        if(target == null || source == null) return;
        if(!empty(source.slug)) target.slug = source.slug;
        if(!empty(source.title)) target.title = source.title;
        if(!empty(source.coverImage)) target.coverImage = source.coverImage;
        if(!empty(source.author)) target.author = source.author;
        if(!empty(source.status)) target.status = source.status;
        if(!empty(source.synopsis)) target.synopsis = source.synopsis;
        if(!empty(source.genre)) target.genre = source.genre;
        if(!empty(source.info)) target.info = source.info;
        if(!empty(source.latestChapter)) target.latestChapter = source.latestChapter;
        if(!empty(source.latestChapterDate)) target.latestChapterDate = source.latestChapterDate;
        target.totalChapters = Math.max(target.totalChapters, source.totalChapters);
        String safeSourceId = firstNonEmpty(sourceId, source.getSourceId(), target.getSourceId());
        target.withSource(safeSourceId, MangaSourceFactory.labelForSourceId(safeSourceId));
    }

    private String typeFromDetailCache(MangaPost post){
        if(!isAdded() || post == null || empty(post.slug)) return "";
        try{
            SharedPreferences prefs = requireContext().getApplicationContext().getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(post.getSourceId() + "_" + post.slug, "");
            if(empty(raw)) return "";
            JSONObject root = new JSONObject(raw);
            JSONObject manga = root.optJSONObject("manga");
            if(manga == null) return "";
            return MangaLabelUtils.normalizeStoredType(manga.optString("typeLabel", ""), manga.optString("genre", "") + " " + manga.optString("status", "") + " " + manga.optString("synopsis", "") + " " + manga.optString("info", ""));
        }catch(Exception ignored){
            return "";
        }
    }

    private void applyLocalTypeFallback(MangaPost post){
        if(post == null) return;
        String resolved = MangaLabelUtils.normalizeStoredType(post.typeLabel, firstNonEmpty(post.genre, "") + " " + firstNonEmpty(post.status, "") + " " + firstNonEmpty(post.synopsis, "") + " " + firstNonEmpty(post.info, "") + " " + firstNonEmpty(post.title, "") + " " + firstNonEmpty(post.slug, ""));
        if(!resolved.isEmpty()) post.typeLabel = resolved;
    }

    private String firstNonEmpty(String... values){
        if(values == null) return "";
        for(String value : values) if(value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private String safeLower(String value){
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String compactKey(String value){
        if(value == null) return "";
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "").trim();
    }

    private void applyStoredFavoriteChapterIncreases(){
        Map<String, MangaFavoriteManager.ChapterBadge> stored = MangaFavoriteManager.getChapterIncreaseBadges(requireContext());
        if(!stored.isEmpty()){
            for(MangaPost post : favorites){
                if(post == null) continue;
                MangaFavoriteManager.ChapterBadge badge = stored.get(favoriteKey(post));
                if(badge != null){
                    post.favoriteChapterBase = badge.base;
                    post.favoriteChapterAdded = badge.added;
                }
            }
        }
        for(Map.Entry<String, ChapterIncrease> entry : favoriteChapterIncreases.entrySet()){
            ChapterIncrease increase = entry.getValue();
            if(increase == null) continue;
            for(MangaPost post : favorites){
                if(post != null && favoriteKey(post).equals(entry.getKey())){
                    post.favoriteChapterBase = increase.base;
                    post.favoriteChapterAdded = increase.added;
                }
            }
        }
    }

    private MangaPost mergeFavorite(MangaPost oldPost, MangaPost fresh){
        MangaPost result = fresh == null ? oldPost : fresh;
        if(result.title == null || result.title.trim().isEmpty()) result.title = oldPost.title;
        if(result.coverImage == null || result.coverImage.trim().isEmpty()) result.coverImage = oldPost.coverImage;
        if(result.author == null || result.author.trim().isEmpty()) result.author = oldPost.author;
        if(result.status == null || result.status.trim().isEmpty()) result.status = oldPost.status;
        if(result.synopsis == null || result.synopsis.trim().isEmpty()) result.synopsis = oldPost.synopsis;
        if(result.genre == null || result.genre.trim().isEmpty()) result.genre = oldPost.genre;
        if(result.info == null || result.info.trim().isEmpty()) result.info = oldPost.info;
        String oldType = MangaLabelUtils.typeLabel(oldPost);
        String resultType = MangaLabelUtils.typeLabel(result);
        if (MangaLabelUtils.isSpecificCountryType(resultType)) result.typeLabel = resultType;
        else if (MangaLabelUtils.isSpecificCountryType(oldType) && (resultType.isEmpty() || "MANGA".equals(resultType))) result.typeLabel = oldType;
        else if (!resultType.isEmpty()) result.typeLabel = resultType;
        else if (!oldType.isEmpty()) result.typeLabel = oldType;
        if(result.latestChapter == null || result.latestChapter.trim().isEmpty()) result.latestChapter = oldPost.latestChapter;
        if(result.latestChapterDate == null || result.latestChapterDate.trim().isEmpty()) result.latestChapterDate = oldPost.latestChapterDate;
        result.withSource(oldPost.getSourceId(), oldPost.getSourceLabel());
        result.totalChapters = Math.max(result.totalChapters, oldPost.totalChapters);
        result.favoriteChapterBase = 0;
        result.favoriteChapterAdded = 0;
        String finalType = MangaLabelUtils.normalizeStoredType(result.typeLabel, firstNonEmpty(result.genre, "") + " " + firstNonEmpty(result.status, "") + " " + firstNonEmpty(result.synopsis, "") + " " + firstNonEmpty(result.info, ""));
        if(!finalType.isEmpty()) result.typeLabel = finalType;
        applyFavoriteHiddenLabels(result);
        return result;
    }

    private void applyFavoriteHiddenLabels(MangaPost post){
    }

    private void applyLatestChapter(MangaPost post, ArrayList<MangaChapter> chapters){
        MangaUpdateCheckLogic.applyLatestChapter(post, chapters);
    }

    private int favoriteChapterTotal(MangaPost post){
        return MangaUpdateCheckLogic.favoriteChapterTotal(post);
    }

    private void applyFavoriteChapterIncrease(MangaPost post, MangaPost oldPost, MikuUpdate.UpdateBaselineSnapshot baseline, ArrayList<MangaChapter> chapters){
        if(post == null) return;
        float oldChapterIndex = MangaUpdateCheckLogic.parseChapterIndex(oldPost == null ? "" : oldPost.latestChapter);
        int oldChapterTotal = MangaUpdateCheckLogic.favoriteChapterTotal(oldPost);
        float baseIndex = baseline != null && baseline.chapterIndex > 0f ? baseline.chapterIndex : oldChapterIndex;
        int baseTotal = baseline != null && baseline.totalChapters > 0 ? baseline.totalChapters : oldChapterTotal;
        boolean hasBaseline = (baseline != null && baseline.hasUsableBaseline()) || baseIndex > 0f || baseTotal > 0;
        int computed = hasBaseline ? MangaUpdateCheckLogic.calculateAddedFromBaseline(baseIndex, baseTotal, chapters) : 0;
        MangaFavoriteManager.ChapterBadge storedBadge = MangaFavoriteManager.getChapterIncreaseBadge(requireContext(), favoriteKey(post));
        int added;
        int base;
        if (storedBadge != null && storedBadge.added > 0) {
            added = storedBadge.added + Math.max(0, computed);
            base = storedBadge.base;
        } else {
            added = computed;
            base = MangaUpdateCheckLogic.displayBase(oldPost, baseIndex, baseTotal);
        }
        if(added > 0){
            post.favoriteChapterBase = base;
            post.favoriteChapterAdded = added;
            MangaFavoriteManager.setChapterIncreaseBadge(requireContext(), favoriteKey(post), base, added);
            favoriteChapterIncreases.put(favoriteKey(post), new ChapterIncrease(base, added));
            if (computed > 0) {
                float titleThreshold = baseIndex > 0f ? baseIndex : baseTotal;
                favoriteChapterUpdates.put(favoriteKey(post), new FavoriteChapterUpdate(post, added, MangaUpdateCheckLogic.collectNewChapterTitles(chapters, titleThreshold, 4)));
            }
        }
    }

    private int countNewChapters(ArrayList<MangaChapter> chapters, float threshold){
        return MangaUpdateCheckLogic.countNewChapters(chapters, threshold);
    }

    private ArrayList<String> collectNewChapterTitles(ArrayList<MangaChapter> chapters, float threshold){
        return MangaUpdateCheckLogic.collectNewChapterTitles(chapters, threshold, 4);
    }

    private void showFavoriteUpdateDialogIfNeeded(){
        if(!isReadyForVisibleReload() || favoriteChapterUpdates.isEmpty()) return;
        Context context = requireContext();
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        GradientDrawable dialogBg = new GradientDrawable();
        dialogBg.setColor(0xF2181820);
        dialogBg.setCornerRadius(dp(24));
        scrollView.setBackground(dialogBg);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        TextView titleView = new TextView(context);
        titleView.setText("Update Favorite");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView descView = new TextView(context);
        descView.setText("Chapter baru ditemukan di favorite manga kamu.");
        descView.setTextColor(0xCCFFFFFF);
        descView.setTextSize(13);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descParams.setMargins(0, dp(4), 0, dp(12));
        container.addView(descView, descParams);
        for(FavoriteChapterUpdate update : favoriteChapterUpdates.values()) addFavoriteUpdateRow(context, container, update);
        TextView okView = new TextView(context);
        okView.setText("Oke");
        okView.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE));
        okView.setTextSize(15);
        okView.setTypeface(Typeface.DEFAULT_BOLD);
        okView.setGravity(Gravity.CENTER);
        int okVertical = dp(10);
        int okHorizontal = dp(24);
        okView.setPadding(okHorizontal, okVertical, okHorizontal, okVertical);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(themeColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF7C4DFF));
        okBg.setCornerRadius(dp(18));
        okView.setBackground(okBg);
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okParams.gravity = Gravity.END;
        okParams.setMargins(0, dp(8), 0, 0);
        container.addView(okView, okParams);
        AlertDialog dialog = new AlertDialog.Builder(context).setView(scrollView).create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        okView.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(d -> {
            if(dialog.getWindow() != null){
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        dialog.show();
        favoriteChapterUpdates.clear();
    }

    private void addFavoriteUpdateRow(Context context, LinearLayout container, FavoriteChapterUpdate update){
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int cardPadding = dp(10);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1B22);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x22FFFFFF);
        card.setBackground(bg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(10));
        container.addView(card, cardParams);
        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable imageBg = new GradientDrawable();
        imageBg.setColor(0x33333333);
        imageBg.setCornerRadius(dp(12));
        imageView.setBackground(imageBg);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(64), dp(86));
        card.addView(imageView, imageParams);
        if(update.post != null) MangaImageLoader.loadForSource(imageView, update.post.coverImage, update.post.getSourceId(), true, null);
        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(dp(12), 0, 0, 0);
        card.addView(textBox, textParams);
        TextView mangaTitle = new TextView(context);
        mangaTitle.setText(update.post == null || update.post.title == null ? "" : update.post.title);
        mangaTitle.setTextColor(Color.WHITE);
        mangaTitle.setTextSize(15);
        mangaTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mangaTitle.setMaxLines(2);
        textBox.addView(mangaTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView countView = new TextView(context);
        countView.setText("Ditemukan " + update.added + " chapter baru");
        countView.setTextColor(0xFFE5D8FF);
        countView.setTextSize(13);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countParams.setMargins(0, dp(5), 0, 0);
        textBox.addView(countView, countParams);
        if(update.chapters != null && !update.chapters.isEmpty()){
            TextView chapterView = new TextView(context);
            chapterView.setText(joinChapterTitles(update.chapters));
            chapterView.setTextColor(0xB3FFFFFF);
            chapterView.setTextSize(12);
            chapterView.setMaxLines(4);
            LinearLayout.LayoutParams chapterParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chapterParams.setMargins(0, dp(5), 0, 0);
            textBox.addView(chapterView, chapterParams);
        }
    }

    private String joinChapterTitles(ArrayList<String> titles){
        StringBuilder builder = new StringBuilder();
        for(int i=0;i<titles.size();i++){
            if(i > 0) builder.append("\n");
            builder.append(titles.get(i));
        }
        return builder.toString();
    }

    private int themeColor(Context context, int attr, int fallback){
        android.util.TypedValue value = new android.util.TypedValue();
        if(context != null && context.getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }

    private int dp(int value){
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean replaceFavorite(MangaPost oldPost, MangaPost fresh){
        for(int i=0;i<favorites.size();i++){
            MangaPost current = favorites.get(i);
            if(current != null && current.slug.equals(oldPost.slug) && current.getSourceId().equals(oldPost.getSourceId())){
                String before = signature(current);
                favorites.set(i, fresh);
                if (fresh != null && fresh.coverImage != null && !fresh.coverImage.trim().isEmpty() && MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(requireContext())) MangaCoverCache.saveAsync(requireContext().getApplicationContext(), fresh.coverImage, fresh.getSourceId(), saved -> { if (saved) scheduleFavoriteCoverReload(); }, false);
                boolean changed = !before.equals(signature(fresh));
                if(changed) notifyFavoriteAdapterKeepingScroll();
                return changed;
            }
        }
        return false;
    }

    private void openFavoriteDetail(MangaPost post){
        clearFavoriteChapterIncreaseForPost(post);
        saveFavoriteScrollPosition();
        if(isAdded()) ((MainActivity) requireActivity()).openMangaDetail(post);
    }

    private void clearFavoriteChapterIncreaseForPost(MangaPost post){
        if(post == null) return;
        String key = favoriteKey(post);
        boolean changed = favoriteChapterIncreases.remove(key) != null;
        favoriteChapterUpdates.remove(key);
        MangaFavoriteManager.removeChapterIncreaseBadge(requireContext(), key);
        for(MangaPost item : favorites){
            if(item != null && favoriteKey(item).equals(key) && (item.favoriteChapterBase > 0 || item.favoriteChapterAdded > 0)){
                item.favoriteChapterBase = 0;
                item.favoriteChapterAdded = 0;
                changed = true;
            }
        }
        if(post.favoriteChapterBase > 0 || post.favoriteChapterAdded > 0){
            post.favoriteChapterBase = 0;
            post.favoriteChapterAdded = 0;
            changed = true;
        }
        if(changed) notifyFavoriteAdapterKeepingScroll();
    }

    private String favoriteKey(MangaPost post){
        if(post == null) return "";
        return post.getSourceId() + "|" + (post.slug == null ? "" : post.slug);
    }

    private String signature(MangaPost post){
        if(post == null) return "";
        return post.title + "|" + post.coverImage + "|" + post.author + "|" + post.status + "|" + post.synopsis + "|" + post.genre + "|" + post.info + "|" + MangaLabelUtils.typeLabel(post) + "|" + post.latestChapter + "|" + post.latestChapterDate + "|" + post.totalChapters + "|" + post.favoriteChapterBase + "|" + post.favoriteChapterAdded;
    }

    private void openLatestFavoriteChapter(MangaPost post){
        if(!isAdded() || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(post.getSourceId());
        sourceApi.chapters(post.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>(){
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext){
                if(!isAdded()) return;
                if(chapters == null || chapters.isEmpty()){
                    Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                int pos = findChapterPosition(chapters, post.latestChapter);
                clearFavoriteChapterIncreaseForPost(post);
                saveFavoriteScrollPosition();
                ((MainActivity) requireActivity()).openMangaReader(post, new ArrayList<>(chapters), pos);
            }
            @Override public void onError(String message){
                if(!isAdded()) return;
                Toast.makeText(requireContext(), message == null || message.trim().isEmpty() ? "Gagal membuka chapter" : message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int findChapterPosition(ArrayList<MangaChapter> chapters, String latestChapter){
        float target = parseChapterIndex(latestChapter);
        if(target >= 0f){
            for(int i=0;i<chapters.size();i++) if(Math.abs(chapters.get(i).index - target) < 0.001f) return i;
        }
        int newest = 0;
        for(int i=1;i<chapters.size();i++) if(chapters.get(i).index > chapters.get(newest).index) newest = i;
        return newest;
    }

    private float parseChapterIndex(String text){
        return MangaUpdateCheckLogic.parseChapterIndex(text);
    }

    private boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    private static class TypeFilterCandidate {
        final String type;
        final String value;
        TypeFilterCandidate(String type, String value){ this.type = type == null ? "" : type; this.value = value == null ? "" : value; }
    }

    private static class FavoriteChapterUpdate {
        final MangaPost post;
        final int added;
        final ArrayList<String> chapters;

        FavoriteChapterUpdate(MangaPost post, int added, ArrayList<String> chapters){
            this.post = post;
            this.added = added;
            this.chapters = chapters;
        }
    }

    private static class ChapterIncrease {
        final int base;
        final int added;

        ChapterIncrease(int base, int added){
            this.base = base;
            this.added = added;
        }
    }
}
