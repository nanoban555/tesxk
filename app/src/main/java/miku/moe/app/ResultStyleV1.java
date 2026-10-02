package miku.moe.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.HashSet;

public class ResultStyleV1 extends Fragment {
    private static final String ARG_SOURCE_ID = "source_id";
    private static final String ARG_SOURCE_LABEL = "source_label";
    private static final String ARG_KIND = "kind";
    private final ArrayList<MangaPost> posts = new ArrayList<>();
    private final HashSet<String> loadedKeys = new HashSet<>();
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private ProgressBar bottomProgressBar;
    private TextView messageTextView;
    private MangaStyleV1CardAdapter adapter;
    private String sourceId;
    private String sourceLabel;
    private String kind;
    private int page = 1;
    private int generation = 0;
    private boolean loading;
    private boolean openingChapter;
    private boolean hasMore = true;

    public static ResultStyleV1 newInstance(String sourceId, String sourceLabel, String kind) {
        ResultStyleV1 fragment = new ResultStyleV1();
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
        String fallbackSource = MangaSettingsManager.getHomeV1Source(requireContext());
        sourceId = args == null ? fallbackSource : args.getString(ARG_SOURCE_ID, fallbackSource);
        if (!MangaSettingsManager.isValidSource(sourceId)) sourceId = fallbackSource;
        sourceLabel = MangaSourceFactory.labelForSourceId(sourceId);
        kind = args == null ? "popular" : args.getString(ARG_KIND, "popular");
        MaterialToolbar toolbar = view.findViewById(R.id.resultStyleV1Toolbar);
        toolbar.setTitle("popular".equals(kind) ? "Manga Populer" : "Manga Terbaru");
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationOnClickListener(v -> getParentFragmentManager().popBackStack());
        recyclerView = view.findViewById(R.id.resultStyleV1RecyclerView);
        progressBar = view.findViewById(R.id.resultStyleV1ProgressBar);
        bottomProgressBar = view.findViewById(R.id.resultStyleV1BottomProgressBar);
        messageTextView = view.findViewById(R.id.resultStyleV1MessageTextView);
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 3);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(null);
        adapter = new MangaStyleV1CardAdapter(requireContext(), posts, MangaStyleV1CardAdapter.MODE_RESULT, post -> {
            if (isAdded() && requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaDetail(post);
        }, this::loadLatestChapter, this::openLatestChapter);
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

    private void loadPage(boolean reset) {
        if (!isAdded() || loading) return;
        if (reset) {
            page = 1;
            hasMore = true;
            openingChapter = false;
            posts.clear();
            loadedKeys.clear();
            // Reset eksplisit: bersihkan cache parser supaya data benar-benar dari network.
            MangaSourceFactory.invalidateSourceCaches(sourceId);
            if (adapter != null) adapter.notifyDataSetChanged();
        } else if (!hasMore) return;
        loading = true;
        int targetPage = reset ? 1 : page + 1;
        int run = ++generation;
        updateLoading(reset);
        String sort = "popular".equals(kind) ? "popular" : "latest";
        MangaRepository.INSTANCE.listFuture(sourceId, targetPage, sort, "", "").whenComplete((result, error) -> {
            if (!isAdded() || run != generation) return;
            loading = false;
            if (error != null || result == null) {
                updateError(error == null ? "Gagal memuat manga" : safeMessage(error));
                return;
            }
            append(result.getData());
            page = targetPage;
            hasMore = result.getHasNext();
            updateLoaded();
        });
    }

    private void loadLatestChapter(MangaPost post) {
        if (!isAdded() || post == null || !MangaSettingsManager.shouldLoadLatestChapterLabel(requireContext())) return;
        int run = generation;
        MangaLatestChapterResolver.resolve(post, (resolved, changed) -> {
            if (!isAdded() || run != generation || adapter == null || resolved == null || !changed) return;
            int index = posts.indexOf(resolved);
            if (index >= 0) adapter.notifyItemChanged(index);
        });
    }

    private void openLatestChapter(MangaPost post) {
        if (!isAdded() || openingChapter || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        openingChapter = true;
        int run = generation;
        refreshBottomProgress();
        MangaRepository.INSTANCE.chaptersReliableFuture(post.getSourceId(), post.slug).whenComplete((chapters, error) -> {
            if (!isAdded() || run != generation) return;
            openingChapter = false;
            refreshBottomProgress();
            if (error != null) {
                Toast.makeText(requireContext(), safeMessage(error), Toast.LENGTH_SHORT).show();
                return;
            }
            if (chapters == null || chapters.isEmpty()) {
                Toast.makeText(requireContext(), "Chapter terbaru gagal dimuat. Coba lagi.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).openMangaReader(post, new ArrayList<>(chapters), findChapterPosition(chapters, post.latestChapter));
            }
        });
    }

    private int findChapterPosition(ArrayList<MangaChapter> chapters, String latestChapter) {
        float target = parseChapterIndex(latestChapter);
        if (target >= 0f) {
            for (int i = 0; i < chapters.size(); i++) if (Math.abs(chapters.get(i).index - target) < 0.001f) return i;
        }
        int newest = 0;
        for (int i = 1; i < chapters.size(); i++) if (chapters.get(i).index > chapters.get(newest).index) newest = i;
        return newest;
    }

    private float parseChapterIndex(String text) {
        if (text == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'));
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch (Exception ignored) { return -1f; }
    }

    private void append(ArrayList<MangaPost> data) {
        if (data == null) return;
        for (MangaPost post : data) {
            if (post == null) continue;
            post.withSource(sourceId, sourceLabel);
            MangaLabelUtils.applyHiddenLabels(getContext(), post);
            String value = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
            String key = sourceId + "|" + (value == null ? "" : value.trim());
            if (key.endsWith("|") || !loadedKeys.add(key)) continue;
            posts.add(post);
        }
    }

    private void updateLoading(boolean initial) {
        if (progressBar != null) progressBar.setVisibility(initial && posts.isEmpty() ? View.VISIBLE : View.GONE);
        refreshBottomProgress();
        if (messageTextView != null) messageTextView.setVisibility(View.GONE);
    }

    private void updateLoaded() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        refreshBottomProgress();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (messageTextView != null) {
            messageTextView.setText(posts.isEmpty() ? "Data manga belum tersedia" : "");
            messageTextView.setVisibility(posts.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateError(String message) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        refreshBottomProgress();
        if (messageTextView != null && posts.isEmpty()) {
            messageTextView.setText(message);
            messageTextView.setVisibility(View.VISIBLE);
        }
    }

    private void refreshBottomProgress() {
        if (bottomProgressBar == null) return;
        bottomProgressBar.setVisibility(openingChapter || loading && !posts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "Gagal memuat manga";
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? "Gagal memuat manga" : message.trim();
    }
}
