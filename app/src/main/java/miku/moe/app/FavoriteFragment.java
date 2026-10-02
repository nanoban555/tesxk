package miku.moe.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class FavoriteFragment extends Fragment {
    private final ArrayList<AnimePost> favorites = new ArrayList<>();
    private AnimeStyleV1CardAdapter adapter;
    private RecyclerView favoriteRecyclerView;
    private TextView emptyTextView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;
    private final ExecutorService refreshExecutor = Executors.newFixedThreadPool(3);
    private final AtomicInteger refreshGeneration = new AtomicInteger();
    private boolean refreshingFavoriteData;

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) writeExport(result.getData().getData());
        });
        importLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) readImport(result.getData().getData());
        });
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_favorite, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        swipeRefreshLayout = view.findViewById(R.id.favoriteSwipeRefreshLayout);
        favoriteRecyclerView = view.findViewById(R.id.favoriteRecyclerView);
        emptyTextView = view.findViewById(R.id.emptyTextView);
        favoriteRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        favoriteRecyclerView.setItemAnimator(null);
        favoriteRecyclerView.setItemViewCacheSize(9);
        adapter = new AnimeStyleV1CardAdapter(requireContext(), favorites, AnimeStyleV1CardAdapter.MODE_FAVORITE, this::openFavorite);
        favoriteRecyclerView.setAdapter(adapter);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(this::refreshFavoriteData);
        View refreshFavoriteButton = view.findViewById(R.id.refreshFavoriteButton);
        if (refreshFavoriteButton != null) refreshFavoriteButton.setOnClickListener(v -> refreshFavoriteData());
        view.findViewById(R.id.exportFavoriteButton).setOnClickListener(v -> exportFavorites());
        view.findViewById(R.id.importFavoriteButton).setOnClickListener(v -> importFavorites());
        loadFavorites();
    }

    @Override public void onResume() {
        super.onResume();
        loadFavorites();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) loadFavorites();
    }

    @Override public void onDestroyView() {
        refreshGeneration.incrementAndGet();
        refreshingFavoriteData = false;
        if (favoriteRecyclerView != null) favoriteRecyclerView.setAdapter(null);
        favoriteRecyclerView = null;
        adapter = null;
        emptyTextView = null;
        swipeRefreshLayout = null;
        super.onDestroyView();
    }

    public void refreshFavorites() {
        loadFavorites();
    }

    @Override public void onDestroy() {
        refreshGeneration.incrementAndGet();
        refreshExecutor.shutdownNow();
        super.onDestroy();
    }

    private void exportFavorites() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/javascript");
        intent.putExtra(Intent.EXTRA_TITLE, "miku_favorite_backup.js");
        exportLauncher.launch(intent);
    }

    private void importFavorites() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        importLauncher.launch(intent);
    }

    private void writeExport(Uri uri) {
        try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException();
            out.write(FavoriteManager.exportEncrypted(requireContext()).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(requireContext(), "Favorite berhasil diekspor", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Gagal ekspor favorite", Toast.LENGTH_SHORT).show();
        }
    }

    private void readImport(Uri uri) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            FavoriteManager.importEncrypted(requireContext(), new String(bos.toByteArray(), StandardCharsets.UTF_8));
            loadFavorites();
            Toast.makeText(requireContext(), "Favorite berhasil diimport", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "File import tidak valid", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshFavoriteData() {
        if (!isAdded() || refreshingFavoriteData) return;
        loadFavorites();
        if (favorites.isEmpty()) {
            if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
            return;
        }
        refreshingFavoriteData = true;
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
        int run = refreshGeneration.incrementAndGet();
        ArrayList<AnimePost> snapshot = new ArrayList<>(favorites);
        AtomicInteger remaining = new AtomicInteger(snapshot.size());
        Map<String, String> resolvedLabels = new ConcurrentHashMap<>();
        android.content.Context appContext = requireContext().getApplicationContext();
        for (AnimePost post : snapshot) {
            refreshExecutor.execute(() -> {
                String label = AnimeFavoriteEpisodeResolver.resolve(post);
                if (!label.isEmpty()) resolvedLabels.put(FavoriteManager.keyOf(post), label);
                if (remaining.decrementAndGet() == 0) {
                    FavoriteManager.updateEpisodeLabels(appContext, resolvedLabels);
                    if (getActivity() != null) getActivity().runOnUiThread(() -> finishFavoriteRefresh(run, resolvedLabels));
                }
            });
        }
    }

    private void finishFavoriteRefresh(int run, Map<String, String> resolvedLabels) {
        if (!isAdded() || run != refreshGeneration.get()) return;
        refreshingFavoriteData = false;
        for (int i = 0; i < favorites.size(); i++) {
            AnimePost post = favorites.get(i);
            String label = resolvedLabels.get(FavoriteManager.keyOf(post));
            if (label == null || label.isEmpty()) continue;
            String normalized = AnimeEpisodeLabelUtils.normalize(label);
            if (normalized.isEmpty()) continue;
            post.episodeCount = normalized;
            post.channelName = normalized;
            if (adapter != null) adapter.notifyItemChanged(i);
        }
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
        Toast.makeText(requireContext(), resolvedLabels.isEmpty() ? "Episode favorite belum dapat diperbarui" : "Episode favorite diperbarui", Toast.LENGTH_SHORT).show();
    }

    private void openFavorite(AnimePost post) {
        if (post == null || !(requireActivity() instanceof MainActivity)) return;
        ((MainActivity) requireActivity()).openAnimeDetailV2(post);
    }

    private void loadFavorites() {
        if (!isAdded()) return;
        favorites.clear();
        HashSet<String> used = new HashSet<>();
        FavoriteManager.migrateLegacyAnimekuFavorites(requireContext());
        for (AnimePost post : FavoriteManager.getFavorites(requireContext())) addFavoritePost(post, used);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyTextView != null) emptyTextView.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addFavoritePost(AnimePost post, HashSet<String> used) {
        if (post == null) return;
        if (post.sourceId == null || post.sourceId.trim().isEmpty()) post.sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
        String slug = post.slug == null ? "" : post.slug.trim();
        String key = post.sourceId + ":" + (slug.isEmpty() ? String.valueOf(post.categoryId) : slug);
        if (used.add(key)) favorites.add(post);
    }
}
