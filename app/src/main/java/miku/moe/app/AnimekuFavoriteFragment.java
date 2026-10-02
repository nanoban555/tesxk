package miku.moe.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class AnimekuFavoriteFragment extends Fragment {
    private final ArrayList<AnimePost> favorites = new ArrayList<>();
    private AnimekuGridAdapter adapter;
    private TextView emptyTextView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ActivityResultLauncher<Intent> exportLauncher, importLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) writeExport(result.getData().getData());
        });
        importLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) readImport(result.getData().getData());
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_animeku_favorite, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        swipeRefreshLayout = view.findViewById(R.id.favoriteSwipeRefreshLayout);
        GridView gridView = view.findViewById(R.id.gridView);
        emptyTextView = view.findViewById(R.id.emptyTextView);
        adapter = new AnimekuGridAdapter(requireContext(), favorites, post -> ((MainActivity) requireActivity()).openAnimekuDetail(post.categoryId, post.channelId, post.categoryName, post.imgUrl, post.genre, post.rating, post.year, post.countView, post.episodeCount, post.description));
        gridView.setAdapter(adapter);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(this::refreshFavoriteData);
        View refreshFavoriteButton = view.findViewById(R.id.refreshFavoriteButton);
        if (refreshFavoriteButton != null) refreshFavoriteButton.setOnClickListener(v -> refreshFavoriteData());
        view.findViewById(R.id.exportFavoriteButton).setOnClickListener(v -> exportFavorites());
        view.findViewById(R.id.importFavoriteButton).setOnClickListener(v -> importFavorites());
        loadFavorites();
    }

    @Override public void onResume() { super.onResume(); loadFavorites(); }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) loadFavorites();
    }

    public void refreshFavorites() { loadFavorites(); }

    private void exportFavorites() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/javascript");
        intent.putExtra(Intent.EXTRA_TITLE, "animeku_favorite_backup.js");
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
            out.write(AnimekuFavoriteManager.exportEncrypted(requireContext()).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(requireContext(), "Favorite Animeku berhasil diekspor", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Gagal ekspor favorite Animeku", Toast.LENGTH_SHORT).show();
        }
    }

    private void readImport(Uri uri) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            AnimekuFavoriteManager.importEncrypted(requireContext(), new String(bos.toByteArray(), StandardCharsets.UTF_8));
            loadFavorites();
            Toast.makeText(requireContext(), "Favorite Animeku berhasil diimport", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "File import Animeku tidak valid", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshFavoriteData() {
        loadFavorites();
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
    }

    private void loadFavorites() {
        if (!isAdded()) return;
        favorites.clear();
        favorites.addAll(AnimekuFavoriteManager.getFavorites(requireContext()));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyTextView != null) emptyTextView.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
