package miku.moe.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HistoryFragment extends Fragment {
    private LinearLayout historySectionContainer;
    private TextView emptyTextView;
    private MaterialButton clearButton;
    private SwipeRefreshLayout swipeRefreshLayout;
    private final HashSet<String> selectedHistoryKeys = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        historySectionContainer = view.findViewById(R.id.historySectionContainer);
        emptyTextView = view.findViewById(R.id.emptyTextView);
        clearButton = view.findViewById(R.id.clearHistoryButton);
        swipeRefreshLayout = view.findViewById(R.id.historySwipeRefreshLayout);

        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(this::refreshHistoryData);
        View refreshButton = view.findViewById(R.id.refreshHistoryButton);
        if (refreshButton != null) refreshButton.setOnClickListener(v -> refreshHistoryData());

        clearButton.setOnClickListener(v -> {
            if (selectedHistoryKeys.isEmpty()) showClearAllHistoryDialog(); else showDeleteSelectedHistoryDialog();
        });

        loadHistory();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistory();
    }

    public void refreshHistory() {
        loadHistory();
    }

    private void refreshHistoryData() {
        loadHistory();
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
    }

    private void showClearAllHistoryDialog() {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.drawable.ic_delete)
                .setTitle("Hapus semua history anime?")
                .setMessage("Semua history anime dari semua source akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    selectedHistoryKeys.clear();
                    HistoryManager.clear(requireContext());
                    AnimekuHistoryManager.clear(requireContext());
                    loadHistory();
                })
                .show();
    }

    private void showDeleteSelectedHistoryDialog() {
        if (!isAdded() || selectedHistoryKeys.isEmpty()) return;
        int count = selectedHistoryKeys.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.drawable.ic_delete)
                .setTitle("Hapus history terpilih?")
                .setMessage(count + " history anime yang dipilih akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> deleteSelectedHistory())
                .show();
    }

    private void deleteSelectedHistory() {
        if (!isAdded() || selectedHistoryKeys.isEmpty()) return;
        HashSet<String> keys = new HashSet<>(selectedHistoryKeys);
        ArrayList<HistoryItem> defaultItems = HistoryManager.getHistory(requireContext());
        for (HistoryItem item : defaultItems) {
            if (item != null && keys.contains(animeHistoryKey(item))) HistoryManager.remove(requireContext(), item);
        }
        ArrayList<HistoryItem> animekuItems = AnimekuHistoryManager.getHistory(requireContext());
        for (HistoryItem item : animekuItems) {
            if (item != null) item.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
            if (item != null && keys.contains(animeHistoryKey(item))) AnimekuHistoryManager.remove(requireContext(), item);
        }
        selectedHistoryKeys.clear();
        loadHistory();
    }

    private void loadHistory() {
        if (!isAdded() || historySectionContainer == null) return;
        historySectionContainer.removeAllViews();

        ArrayList<HistoryItem> rawItems = buildDisplayHistory(loadAllAnimeHistory());
        pruneSelectedHistory(rawItems);
        Map<String, List<HistoryItem>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();

        for (HistoryItem item : rawItems) {
            String dayKey = HistoryAdapter.makeDayKey(item.lastWatched);
            if (!grouped.containsKey(dayKey)) {
                grouped.put(dayKey, new ArrayList<>());
                labels.put(dayKey, HistoryAdapter.makeDayLabel(item.lastWatched));
            }
            grouped.get(dayKey).add(item);
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (String dayKey : grouped.keySet()) {
            View header = inflater.inflate(R.layout.history_day_header, historySectionContainer, false);
            TextView dayHeaderTextView = header.findViewById(R.id.dayHeaderTextView);
            dayHeaderTextView.setText(labels.get(dayKey));
            historySectionContainer.addView(header);

            List<HistoryItem> dayItems = grouped.get(dayKey);
            for (HistoryItem dayItem : dayItems) {
                addHistoryCard(inflater, historySectionContainer, dayItem);
            }
        }

        boolean empty = rawItems.isEmpty();
        if (emptyTextView != null) emptyTextView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (clearButton != null) {
            boolean hasSelection = !selectedHistoryKeys.isEmpty();
            clearButton.setVisibility(empty ? View.GONE : View.VISIBLE);
            clearButton.setIconResource(R.drawable.ic_delete);
            clearButton.setIconTint(ColorStateList.valueOf(hasSelection ? 0xFFD32F2F : resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)));
            clearButton.setContentDescription(hasSelection ? "Hapus " + selectedHistoryKeys.size() + " history anime" : "Hapus semua history anime");
        }
    }

    private void pruneSelectedHistory(ArrayList<HistoryItem> items) {
        if (selectedHistoryKeys.isEmpty()) return;
        HashSet<String> visibleKeys = new HashSet<>();
        for (HistoryItem item : items) visibleKeys.add(animeHistoryKey(item));
        selectedHistoryKeys.retainAll(visibleKeys);
    }

    private ArrayList<HistoryItem> loadAllAnimeHistory() {
        ArrayList<HistoryItem> all = new ArrayList<>();
        ArrayList<HistoryItem> defaultItems = HistoryManager.getHistory(requireContext());
        for (HistoryItem item : defaultItems) {
            if (item != null) {
                if (!AnimeSettingsManager.isValidSource(item.sourceId)) item.sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
                all.add(item);
            }
        }
        ArrayList<HistoryItem> animekuItems = AnimekuHistoryManager.getHistory(requireContext());
        for (HistoryItem item : animekuItems) {
            if (item != null) {
                item.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
                all.add(item);
            }
        }
        Collections.sort(all, (a, b) -> Long.compare(b == null ? 0L : b.lastWatched, a == null ? 0L : a.lastWatched));
        return all;
    }

    private ArrayList<HistoryItem> buildDisplayHistory(ArrayList<HistoryItem> items) {
        ArrayList<HistoryItem> result = new ArrayList<>();
        Map<String, Boolean> used = new LinkedHashMap<>();
        for (HistoryItem item : items) {
            String key = animeHistoryKey(item);
            if (!used.containsKey(key)) {
                used.put(key, true);
                result.add(item);
            }
        }
        return result;
    }

    private String animeHistoryKey(HistoryItem item) {
        if (item == null) return "empty";
        String source = AnimeSettingsManager.isValidSource(item.sourceId) ? item.sourceId : AnimeSettingsManager.SOURCE_DEFAULT;
        if (item.categoryId > 0) return source + ":category:" + item.categoryId;
        String name = item.categoryName == null ? "" : item.categoryName.trim().toLowerCase();
        if (!name.isEmpty()) return source + ":name:" + name;
        String title = item.title == null ? "" : item.title.trim().toLowerCase();
        title = title.replaceAll("(?i)\\s*[•-]?\\s*(episode|eps|ep|e)\\s*[-:]*\\s*\\d+.*$", "").trim();
        if (!title.isEmpty()) return source + ":title:" + title;
        return source + ":channel:" + item.channelId;
    }

    private void addHistoryCard(LayoutInflater inflater, LinearLayout container, HistoryItem item) {
        View card = inflater.inflate(R.layout.anime_history_item, container, false);
        HistoryAdapter.bindHistoryItem(requireContext(), card, item, this::openDetailFromHistory, this::continueWatching);
        String key = animeHistoryKey(item);
        bindSelection(card, key, item);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(14));
        container.addView(card, params);
    }

    private void bindSelection(View card, String key, HistoryItem item) {
        applySelectionStyle(card, key);
        View detailClickArea = card.findViewById(R.id.detailClickArea);
        View.OnClickListener clickListener = v -> {
            if (selectedHistoryKeys.isEmpty()) openDetailFromHistory(item); else toggleHistorySelection(key);
        };
        View.OnLongClickListener longClickListener = v -> {
            toggleHistorySelection(key);
            return true;
        };
        card.setOnClickListener(clickListener);
        card.setOnLongClickListener(longClickListener);
        if (detailClickArea != null) {
            detailClickArea.setOnClickListener(clickListener);
            detailClickArea.setOnLongClickListener(longClickListener);
        }
    }

    private void toggleHistorySelection(String key) {
        if (selectedHistoryKeys.contains(key)) selectedHistoryKeys.remove(key); else selectedHistoryKeys.add(key);
        loadHistory();
    }

    private void applySelectionStyle(View card, String key) {
        if (!(card instanceof MaterialCardView)) return;
        MaterialCardView materialCard = (MaterialCardView) card;
        boolean selected = selectedHistoryKeys.contains(key);
        materialCard.setStrokeWidth(dp(selected ? 3 : 1));
        materialCard.setStrokeColor(resolveThemeColor(selected ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorSurfaceContainerHighest));
        materialCard.setCardBackgroundColor(resolveThemeColor(selected ? com.google.android.material.R.attr.colorSecondaryContainer : com.google.android.material.R.attr.colorSurfaceContainer));
    }

    private int resolveThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) return ContextCompat.getColor(requireContext(), typedValue.resourceId);
            return typedValue.data;
        }
        return 0;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void openDetailFromHistory(HistoryItem item) {
        if (!(requireActivity() instanceof MainActivity)) return;
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(item.sourceId)) {
            if (item.categoryId > 0 || item.channelId > 0) {
                ((MainActivity) requireActivity()).openAnimekuDetail(item.categoryId, item.channelId, item.categoryName, item.imageUrl, "", "", 0, "", "");
            } else {
                Toast.makeText(requireContext(), "Data detail history Animeku tidak lengkap", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(item.sourceId)) {
            if (item.slug != null && !item.slug.trim().isEmpty()) {
                ((MainActivity) requireActivity()).openAnimeLoverzDetail(item.slug, item.categoryName, item.imageUrl, "", "", "", "");
            } else {
                Toast.makeText(requireContext(), "Data detail history Animeloverz tidak lengkap", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (AnimeSettingsManager.SOURCE_DRAMORA.equals(item.sourceId)) {
            AnimePost post = new AnimePost(item.imageUrl, item.categoryName, item.categoryId, item.channelId);
            post.sourceId = AnimeSettingsManager.SOURCE_DRAMORA;
            post.slug = item.slug == null ? "" : item.slug;
            ((MainActivity) requireActivity()).openAnimeDetailV2(post);
            return;
        }
        if (AnimeSettingsManager.SOURCE_ANICHIN.equals(item.sourceId)) {
            AnimePost post = new AnimePost(item.imageUrl, item.categoryName, item.categoryId, item.channelId);
            post.sourceId = AnimeSettingsManager.SOURCE_ANICHIN;
            post.slug = item.slug == null ? "" : item.slug;
            ((MainActivity) requireActivity()).openAnimeDetailV2(post);
            return;
        }
        if (item.categoryId > 0) {
            ((MainActivity) requireActivity()).openDetail(item.categoryId, item.channelId);
        } else if (item.channelId > 0) {
            ((MainActivity) requireActivity()).openEpisode(item.channelId);
            Toast.makeText(requireContext(), "Data detail lama belum lengkap, membuka episode", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(), "Data detail history tidak lengkap", Toast.LENGTH_SHORT).show();
        }
    }

    private void continueWatching(HistoryItem item) {
        if (item.videoUrl == null || item.videoUrl.trim().isEmpty()) {
            Toast.makeText(requireContext(), "URL video tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(item.sourceId) || AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(item.sourceId) || AnimeSettingsManager.SOURCE_DRAMORA.equals(item.sourceId)) {
            Intent intent = new Intent(requireContext(), AnimekuVideoPlayerActivity.class);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_URL, item.videoUrl);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_TITLE, item.title);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_IMAGE_URL, item.imageUrl);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CHANNEL_ID, item.channelId);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_ID, item.categoryId);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_NAME, item.categoryName);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_START_POSITION, item.position);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_QUALITY, PlaybackQualityManager.getQuality(requireContext()));
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_DISABLE_PLAYLIST, AnimeSettingsManager.SOURCE_DRAMORA.equals(item.sourceId));
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_HISTORY_SOURCE_ID, item.sourceId);
            intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_HISTORY_SLUG, item.slug == null ? "" : item.slug);
            if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(item.sourceId)) intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_ANIMELOVERZ_SLUG, item.slug == null ? "" : item.slug);
            startActivity(intent);
            return;
        }
        if (AnimeSettingsManager.SOURCE_ANICHIN.equals(item.sourceId)) {
            Intent intent = new Intent(requireContext(), AnichinPlayerActivity.class);
            intent.putExtra(AnichinPlayerActivity.EXTRA_VIDEO_URL, item.videoUrl);
            intent.putExtra(AnichinPlayerActivity.EXTRA_VIDEO_TITLE, item.title);
            intent.putExtra(AnichinPlayerActivity.EXTRA_IMAGE_URL, item.imageUrl);
            intent.putExtra(AnichinPlayerActivity.EXTRA_CHANNEL_ID, item.channelId);
            intent.putExtra(AnichinPlayerActivity.EXTRA_CATEGORY_ID, item.categoryId);
            intent.putExtra(AnichinPlayerActivity.EXTRA_CATEGORY_NAME, item.categoryName);
            intent.putExtra(AnichinPlayerActivity.EXTRA_START_POSITION, item.position);
            intent.putExtra(AnichinPlayerActivity.EXTRA_QUALITY, PlaybackQualityManager.getQuality(requireContext()));
            intent.putExtra(AnichinPlayerActivity.EXTRA_DISABLE_PLAYLIST, false);
            intent.putExtra(AnichinPlayerActivity.EXTRA_HISTORY_SOURCE_ID, AnimeSettingsManager.SOURCE_ANICHIN);
            intent.putExtra(AnichinPlayerActivity.EXTRA_HISTORY_SLUG, item.slug == null ? "" : item.slug);
            startActivity(intent);
            return;
        }
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, item.videoUrl);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, item.title);
        intent.putExtra(VideoPlayerActivity.EXTRA_IMAGE_URL, item.imageUrl);
        intent.putExtra(VideoPlayerActivity.EXTRA_CHANNEL_ID, item.channelId);
        intent.putExtra(VideoPlayerActivity.EXTRA_CATEGORY_ID, item.categoryId);
        intent.putExtra(VideoPlayerActivity.EXTRA_CATEGORY_NAME, item.categoryName);
        intent.putExtra(VideoPlayerActivity.EXTRA_START_POSITION, item.position);
        startActivity(intent);
    }
}
