package miku.moe.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnimekuHistoryFragment extends Fragment {
    private LinearLayout historySectionContainer;
    private TextView emptyTextView;
    private MaterialButton clearButton;
    private SwipeRefreshLayout swipeRefreshLayout;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_animeku_history, container, false);
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

        clearButton.setOnClickListener(v -> showClearHistoryDialog());

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

    private void showClearHistoryDialog() {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hapus history anime?")
                .setMessage("Semua history anime akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    AnimekuHistoryManager.clear(requireContext());
                    loadHistory();
                })
                .show();
    }

    private void loadHistory() {
        if (!isAdded() || historySectionContainer == null) return;
        historySectionContainer.removeAllViews();

        ArrayList<HistoryItem> rawItems = buildDisplayHistory(AnimekuHistoryManager.getHistory(requireContext()));
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
        if (clearButton != null) clearButton.setVisibility(empty ? View.GONE : View.VISIBLE);
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
        if (item.categoryId > 0) return "category:" + item.categoryId;
        String name = item.categoryName == null ? "" : item.categoryName.trim().toLowerCase();
        if (!name.isEmpty()) return "name:" + name;
        String title = item.title == null ? "" : item.title.trim().toLowerCase();
        title = title.replaceAll("(?i)\\s*[•-]?\\s*(episode|eps|ep|e)\\s*[-:]*\\s*\\d+.*$", "").trim();
        if (!title.isEmpty()) return "title:" + title;
        return "channel:" + item.channelId;
    }

    private void addHistoryCard(LayoutInflater inflater, LinearLayout container, HistoryItem item) {
        View card = inflater.inflate(R.layout.animeku_history_item, container, false);
        HistoryAdapter.bindHistoryItem(requireContext(), card, item, this::openDetailFromHistory, this::continueWatching);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(14));
        container.addView(card, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void openDetailFromHistory(HistoryItem item) {
        if (!(requireActivity() instanceof MainActivity)) return;
        if (item.categoryId > 0 || item.channelId > 0) {
            ((MainActivity) requireActivity()).openAnimekuDetail(item.categoryId, item.channelId, item.categoryName, item.imageUrl, "", "", 0, "", "");
        } else {
            Toast.makeText(requireContext(), "Data detail history Animeku tidak lengkap", Toast.LENGTH_SHORT).show();
        }
    }

    private void continueWatching(HistoryItem item) {
        if (item.videoUrl == null || item.videoUrl.trim().isEmpty()) {
            Toast.makeText(requireContext(), "URL video tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(requireContext(), AnimekuVideoPlayerActivity.class);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_URL, item.videoUrl);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_TITLE, item.title);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_IMAGE_URL, item.imageUrl);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CHANNEL_ID, item.channelId);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_ID, item.categoryId);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_NAME, item.categoryName);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_START_POSITION, item.position);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_QUALITY, PlaybackQualityManager.getQuality(requireContext()));
        startActivity(intent);
    }
}
