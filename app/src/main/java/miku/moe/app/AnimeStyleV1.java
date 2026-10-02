package miku.moe.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnimeStyleV1 extends Fragment {
    private static final String ARG_SOURCE_ID = "source_id";
    private static final String ARG_SOURCE_LABEL = "source_label";
    private static final String ARG_KIND = "kind";
    private final ArrayList<AnimePost> posts = new ArrayList<>();
    private final HashSet<String> loadedKeys = new HashSet<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private ProgressBar bottomProgressBar;
    private TextView messageTextView;
    private AnimeStyleV1CardAdapter adapter;
    private String sourceId;
    private String sourceLabel;
    private String kind;
    private int page = 1;
    private int generation;
    private boolean loading;
    private boolean hasMore = true;

    public static AnimeStyleV1 newInstance(String sourceId, String sourceLabel, String kind) {
        AnimeStyleV1 fragment = new AnimeStyleV1();
        Bundle args = new Bundle();
        args.putString(ARG_SOURCE_ID, sourceId);
        args.putString(ARG_SOURCE_LABEL, sourceLabel);
        args.putString(ARG_KIND, kind);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_result_style_v1, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String fallbackSource = AnimeSettingsManager.getHomeV1Source(requireContext());
        sourceId = args == null ? fallbackSource : args.getString(ARG_SOURCE_ID, fallbackSource);
        if (!AnimeSettingsManager.isValidSource(sourceId)) sourceId = fallbackSource;
        sourceLabel = args == null ? AnimeSettingsManager.labelForSourceId(sourceId) : args.getString(ARG_SOURCE_LABEL, AnimeSettingsManager.labelForSourceId(sourceId));
        if (sourceLabel == null || sourceLabel.trim().isEmpty()) sourceLabel = AnimeSettingsManager.labelForSourceId(sourceId);
        kind = args == null ? "latest" : args.getString(ARG_KIND, "latest");
        if (!"popular".equals(kind)) kind = "latest";
        MaterialToolbar toolbar = view.findViewById(R.id.resultStyleV1Toolbar);
        toolbar.setTitle("popular".equals(kind) ? "Anime Populer" : "Anime Terbaru");
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationOnClickListener(v -> getParentFragmentManager().popBackStack());
        recyclerView = view.findViewById(R.id.resultStyleV1RecyclerView);
        progressBar = view.findViewById(R.id.resultStyleV1ProgressBar);
        bottomProgressBar = view.findViewById(R.id.resultStyleV1BottomProgressBar);
        messageTextView = view.findViewById(R.id.resultStyleV1MessageTextView);
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 3);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(null);
        recyclerView.setItemViewCacheSize(9);
        adapter = new AnimeStyleV1CardAdapter(requireContext(), posts, AnimeStyleV1CardAdapter.MODE_GRID, this::openAnime);
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loading || !hasMore) return;
                int last = layoutManager.findLastVisibleItemPosition();
                if (last >= Math.max(0, adapter.getItemCount() - 6)) loadPage(false);
            }
        });
        loadPage(true);
    }

    @Override public void onResume() {
        super.onResume();
        if (getActivity() != null) ThemeManager.applySystemBars(getActivity());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override public void onDestroyView() {
        generation++;
        if (recyclerView != null) recyclerView.setAdapter(null);
        recyclerView = null;
        progressBar = null;
        bottomProgressBar = null;
        messageTextView = null;
        adapter = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadPage(boolean reset) {
        if (!isAdded() || loading) return;
        if (reset) {
            page = 1;
            hasMore = true;
            posts.clear();
            loadedKeys.clear();
            if (adapter != null) adapter.notifyDataSetChanged();
        } else if (!hasMore) return;
        loading = true;
        int targetPage = reset ? 1 : page + 1;
        int run = ++generation;
        updateLoading(reset);
        android.content.Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            AnimeStyleV1DataLoader.Page result = null;
            Throwable error = null;
            try {
                result = AnimeStyleV1DataLoader.load(appContext, sourceId, kind, targetPage);
            } catch (Throwable throwable) {
                error = throwable;
            }
            AnimeStyleV1DataLoader.Page finalResult = result;
            Throwable finalError = error;
            mainHandler.post(() -> finishPage(run, targetPage, finalResult, finalError));
        });
    }

    private void finishPage(int run, int targetPage, AnimeStyleV1DataLoader.Page result, Throwable error) {
        if (!isAdded() || run != generation) return;
        loading = false;
        if (error != null || result == null) {
            updateError(safeMessage(error));
            return;
        }
        append(result.items);
        page = targetPage;
        hasMore = result.hasMore;
        updateLoaded();
    }

    private void append(ArrayList<AnimePost> data) {
        if (data == null) return;
        for (AnimePost post : data) {
            if (post == null) continue;
            post.sourceId = sourceId;
            String key = itemKey(post);
            if (key.isEmpty() || !loadedKeys.add(key)) continue;
            posts.add(post);
        }
    }

    private void openAnime(AnimePost post) {
        if (!isAdded() || post == null) return;
        if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openAnimeDetailV2(post);
    }

    private void updateLoading(boolean initial) {
        if (progressBar != null) progressBar.setVisibility(initial && posts.isEmpty() ? View.VISIBLE : View.GONE);
        if (bottomProgressBar != null) bottomProgressBar.setVisibility(!initial && !posts.isEmpty() ? View.VISIBLE : View.GONE);
        if (messageTextView != null) messageTextView.setVisibility(View.GONE);
    }

    private void updateLoaded() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (bottomProgressBar != null) bottomProgressBar.setVisibility(View.GONE);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (messageTextView != null) {
            messageTextView.setText(posts.isEmpty() ? "Data anime belum tersedia" : "");
            messageTextView.setVisibility(posts.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateError(String message) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (bottomProgressBar != null) bottomProgressBar.setVisibility(View.GONE);
        if (messageTextView != null && posts.isEmpty()) {
            messageTextView.setText(message);
            messageTextView.setVisibility(View.VISIBLE);
        }
    }

    private String itemKey(AnimePost post) {
        if (post == null) return "";
        String slug = post.slug == null ? "" : post.slug.trim();
        if (!slug.isEmpty()) return sourceId + ":slug:" + slug;
        if (post.categoryId > 0) return sourceId + ":category:" + post.categoryId + ":" + post.channelId;
        String title = post.categoryName == null ? "" : post.categoryName.trim();
        return title.isEmpty() ? "" : sourceId + ":title:" + title;
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "Gagal memuat anime";
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? "Gagal memuat anime" : message.trim();
    }
}
