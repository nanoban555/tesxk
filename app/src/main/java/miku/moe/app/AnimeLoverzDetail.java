package miku.moe.app;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class AnimeLoverzDetail extends Fragment {
    private static final String TAG = "AnimeLoverzDetail";
    private static final String ARG_SLUG = "slug";
    private static final String ARG_TITLE = "title";
    private static final String ARG_IMAGE_URL = "image_url";
    private static final String ARG_GENRE = "genre";
    private static final String ARG_RATING = "rating";
    private static final String ARG_STATUS = "status";
    private static final String ARG_DESCRIPTION = "description";
    private static final String API_BASE = "https://apps.animekita.org/api/v1.2.5";

    private ImageView imageView, backdropImageView;
    private TextView categoryNameTextView, genreTextView, ratingTextView, episodeCountTextView;
    private TextView synopsisTextView, expandSynopsisTextView, japaneseTextView, englishTextView, typeTextView, airedTextView;
    private TextView premieredTextView, studiosTextView, sourceTextView, durationTextView, yearTextView, viewsTextView;
    private MaterialButton favoriteButton, startButton, saveImageButton, orderEpisodeButton;
    private ListView episodesListView;
    private ProgressBar progressBar;
    private RequestQueue requestQueue;
    private AnimekuEpisodeAdapter episodeAdapter;
    private final ArrayList<Episode> episodesList = new ArrayList<>();
    private final HashMap<Integer, EpisodeMeta> episodeMetaMap = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean episodeNewestFirst = false;
    private String currentSlug = "";
    private String currentTitle = "";
    private String currentImageUrl = "";
    private String currentGenre = "";
    private String currentRating = "";
    private String currentStatus = "";
    private String currentDescription = "";
    private int currentCategoryId = -1;

    public static AnimeLoverzDetail newInstance(String slug, String title, String imageUrl, String genre, String rating, String status, String description) {
        AnimeLoverzDetail fragment = new AnimeLoverzDetail();
        Bundle args = new Bundle();
        args.putString(ARG_SLUG, safe(slug));
        args.putString(ARG_TITLE, safe(title));
        args.putString(ARG_IMAGE_URL, safe(imageUrl));
        args.putString(ARG_GENRE, safe(genre));
        args.putString(ARG_RATING, safe(rating));
        args.putString(ARG_STATUS, safe(status));
        args.putString(ARG_DESCRIPTION, safe(description));
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_animeku_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        requestQueue = Volley.newRequestQueue(requireContext().getApplicationContext());
        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
        imageView = view.findViewById(R.id.imageView);
        backdropImageView = view.findViewById(R.id.backdropImageView);
        categoryNameTextView = view.findViewById(R.id.categoryNameTextView);
        genreTextView = view.findViewById(R.id.genreTextView);
        ratingTextView = view.findViewById(R.id.ratingTextView);
        episodeCountTextView = view.findViewById(R.id.episodeCountTextView);
        synopsisTextView = view.findViewById(R.id.synopsisTextView);
        expandSynopsisTextView = view.findViewById(R.id.expandSynopsisTextView);
        japaneseTextView = view.findViewById(R.id.japaneseTextView);
        englishTextView = view.findViewById(R.id.englishTextView);
        typeTextView = view.findViewById(R.id.typeTextView);
        airedTextView = view.findViewById(R.id.airedTextView);
        premieredTextView = view.findViewById(R.id.premieredTextView);
        studiosTextView = view.findViewById(R.id.studiosTextView);
        sourceTextView = view.findViewById(R.id.sourceTextView);
        durationTextView = view.findViewById(R.id.durationTextView);
        yearTextView = view.findViewById(R.id.yearTextView);
        viewsTextView = view.findViewById(R.id.viewsTextView);
        favoriteButton = view.findViewById(R.id.favoriteButton);
        startButton = view.findViewById(R.id.startButton);
        saveImageButton = view.findViewById(R.id.saveImageButton);
        orderEpisodeButton = view.findViewById(R.id.orderEpisodeButton);
        episodesListView = view.findViewById(R.id.episodesListView);
        progressBar = view.findViewById(R.id.progressBar);

        if (favoriteButton != null) {
            favoriteButton.setVisibility(View.VISIBLE);
            favoriteButton.setOnClickListener(v -> toggleFavorite());
        }
        if (saveImageButton != null) {
            saveImageButton.setVisibility(View.VISIBLE);
            saveImageButton.setOnClickListener(v -> saveCurrentImageToGallery());
        }
        expandSynopsisTextView.setOnClickListener(v -> toggleSynopsis());
        startButton.setOnClickListener(v -> openStartEpisode());
        orderEpisodeButton.setOnClickListener(v -> toggleEpisodeOrder());

        Bundle args = getArguments();
        currentSlug = args == null ? "" : args.getString(ARG_SLUG, "");
        currentTitle = args == null ? "" : args.getString(ARG_TITLE, "");
        currentImageUrl = args == null ? "" : args.getString(ARG_IMAGE_URL, "");
        currentGenre = formatGenreText(args == null ? "" : args.getString(ARG_GENRE, ""));
        currentRating = args == null ? "" : args.getString(ARG_RATING, "");
        currentStatus = args == null ? "" : args.getString(ARG_STATUS, "");
        currentDescription = args == null ? "" : args.getString(ARG_DESCRIPTION, "");
        currentCategoryId = positiveId(currentSlug);
        bindInitialData();
        if (currentSlug.trim().isEmpty()) Toast.makeText(requireContext(), "Data Animeloverz tidak valid", Toast.LENGTH_SHORT).show();
        else fetchAnimeDetail();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (episodesListView != null && episodeAdapter != null) {
            episodeAdapter.notifyDataSetChanged();
            setListViewHeightBasedOnChildren(episodesListView);
        }
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        if (requestQueue != null) requestQueue.cancelAll(TAG);
        if (episodesListView != null) {
            episodesListView.setOnItemClickListener(null);
            episodesListView.setAdapter(null);
        }
        episodeAdapter = null;
        super.onDestroyView();
    }

    private void bindInitialData() {
        if (isUseful(currentImageUrl)) bindImages(currentImageUrl);
        setTextOrHide(categoryNameTextView, currentTitle);
        setTextOrHide(genreTextView, formatGenreText(currentGenre));
        setTextOrHide(ratingTextView, isUseful(currentRating) ? "★ " + currentRating : "");
        setTextOrHide(typeTextView, label("Status", currentStatus));
        setTextOrHide(sourceTextView, "Source: Animeloverz");
        bindSynopsisText(currentDescription);
        setTextOrHide(japaneseTextView, "");
        setTextOrHide(englishTextView, "");
        setTextOrHide(airedTextView, "");
        setTextOrHide(premieredTextView, "");
        setTextOrHide(studiosTextView, "");
        setTextOrHide(durationTextView, "");
        setTextOrHide(yearTextView, "");
        setTextOrHide(viewsTextView, "");
        updateFavoriteButton();
    }

    private AnimePost currentPost() {
        AnimePost post = new AnimePost(currentImageUrl, currentTitle, currentCategoryId, -1);
        post.sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ;
        post.slug = currentSlug;
        post.genre = formatGenreText(currentGenre);
        post.rating = currentRating;
        post.statusVideo = currentStatus;
        post.description = currentDescription;
        return post;
    }

    private void updateFavoriteButton() {
        if (favoriteButton == null || !isAdded()) return;
        boolean favorite = FavoriteManager.isFavorite(requireContext(), AnimeSettingsManager.SOURCE_ANIMELOVERZ, currentCategoryId, currentSlug);
        favoriteButton.setText(favorite ? "Di Favorite" : "Favorite");
    }

    private void toggleFavorite() {
        if (!isAdded()) return;
        FavoriteManager.toggle(requireContext(), currentPost());
        updateFavoriteButton();
        Toast.makeText(requireContext(), FavoriteManager.isFavorite(requireContext(), AnimeSettingsManager.SOURCE_ANIMELOVERZ, currentCategoryId, currentSlug) ? "Ditambahkan ke favorite" : "Dihapus dari favorite", Toast.LENGTH_SHORT).show();
    }

    private void fetchAnimeDetail() {
        showLoading(true);
        ArrayList<String> variants = animeLoverzSlugVariants(currentSlug);
        fetchAnimeDetailVariant(variants, 0);
    }

    private void fetchAnimeDetailVariant(ArrayList<String> variants, int index) {
        if (variants == null || index >= variants.size()) {
            showLoading(false);
            showToast("Data Animeloverz tidak ditemukan");
            return;
        }
        String apiSlug = variants.get(index);
        String url = API_BASE + "/series.php?url=" + Uri.encode(apiSlug);
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONObject item = firstDataObject(json);
                if (item == null || !hasAnimeLoverzEpisodes(item)) {
                    fetchAnimeDetailVariant(variants, index + 1);
                    return;
                }
                currentSlug = apiSlug;
                bindDetail(item);
                showLoading(false);
            } catch (Exception e) {
                Log.e(TAG, "Detail parse error", e);
                fetchAnimeDetailVariant(variants, index + 1);
            }
        }, error -> {
            Log.e(TAG, "Detail network error", error);
            fetchAnimeDetailVariant(variants, index + 1);
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
            @Override public String getBodyContentType() { return "text/plain; charset=utf-8"; }
            @Override public byte[] getBody() {
                String body = "{\"get\":\"top\",\"post_type\":\"1\",\"post_id\":\"" + escapeJson(apiSlug) + "\",\"token\":\"\"}";
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void bindDetail(JSONObject item) {
        applyAnimeLoverzLegacyDetail(item);
        applyAnimeLoverzFallbackDetail(item);
        if (isUseful(currentImageUrl)) bindImages(currentImageUrl);
        setTextOrHide(categoryNameTextView, currentTitle);
        setTextOrHide(genreTextView, formatGenreText(currentGenre));
        setTextOrHide(ratingTextView, isUseful(currentRating) ? "★ " + currentRating : "");
        setTextOrHide(typeTextView, label("Type", firstUseful(item.optString("type", ""), item.optString("format", ""))));
        setTextOrHide(airedTextView, label("Rilis", firstUseful(item.optString("published", ""), item.optString("date", ""), item.optString("rilis", ""))));
        setTextOrHide(studiosTextView, label("Studio", firstUseful(item.optString("author", ""), item.optString("studio", ""), item.optString("studios", ""))));
        setTextOrHide(sourceTextView, "Source: Animeloverz");
        setTextOrHide(durationTextView, label("Status", currentStatus));
        bindSynopsisText(currentDescription);
        updateFavoriteButton();
        ArrayList<Episode> parsed = parseAnimeLoverzLegacyEpisodes(item);
        if (parsed.isEmpty()) parsed = parseAnimeLoverzFallbackEpisodes(item);
        updateEpisodes(parsed);
        episodesListView.setOnItemClickListener((parent, v, position, id) -> openEpisode(episodesList.get(position).channelId));
    }

    private void applyAnimeLoverzLegacyDetail(JSONObject item) {
        currentTitle = valueOrCurrent(item.optString("judul", ""), currentTitle);
        currentImageUrl = valueOrCurrent(item.optString("cover", ""), currentImageUrl);
        currentGenre = valueOrCurrent(genreField(item, "genre"), currentGenre);
        currentRating = valueOrCurrent(item.optString("rating", ""), currentRating);
        currentStatus = valueOrCurrent(item.optString("status", ""), currentStatus);
        currentDescription = valueOrCurrent(item.optString("sinopsis", ""), currentDescription);
        int id = item.optInt("id", currentCategoryId);
        if (id > 0) currentCategoryId = id;
    }

    private void applyAnimeLoverzFallbackDetail(JSONObject item) {
        currentTitle = valueOrCurrent(firstUseful(item.optString("title", ""), item.optString("name", ""), item.optString("nama", "")), currentTitle);
        currentImageUrl = valueOrCurrent(firstUseful(item.optString("thumb", ""), item.optString("thumbnail", ""), item.optString("image", ""), item.optString("poster", "")), currentImageUrl);
        currentGenre = valueOrCurrent(firstUseful(genreField(item, "genres"), genreField(item, "genre"), genreField(item, "genre_name")), currentGenre);
        currentRating = valueOrCurrent(firstUseful(item.optString("score", ""), item.optString("rate", "")), currentRating);
        currentStatus = valueOrCurrent(firstUseful(item.optString("lastup", ""), item.optString("release_status", ""), item.optString("anime_status", "")), currentStatus);
        currentDescription = valueOrCurrent(firstUseful(item.optString("description", ""), item.optString("desc", ""), item.optString("synopsis", "")), currentDescription);
    }

    private ArrayList<Episode> parseAnimeLoverzLegacyEpisodes(JSONObject item) {
        ArrayList<Episode> parsed = new ArrayList<>();
        episodeMetaMap.clear();
        JSONArray chapters = item.optJSONArray("chapter");
        if (chapters == null) return parsed;
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject chapter = chapters.optJSONObject(i);
            if (chapter == null) continue;
            int id = chapter.optInt("id", -1);
            String ch = chapter.optString("ch", "").trim();
            String episodeUrl = chapter.optString("url", "").trim();
            if (id <= 0 || episodeUrl.isEmpty()) continue;
            String label = ch.isEmpty() ? "Episode" : "Episode " + ch;
            parsed.add(new Episode(id, label));
            episodeMetaMap.put(id, new EpisodeMeta(id, episodeUrl, ch, label));
        }
        return parsed;
    }

    private ArrayList<Episode> parseAnimeLoverzFallbackEpisodes(JSONObject item) {
        ArrayList<Episode> parsed = new ArrayList<>();
        episodeMetaMap.clear();
        JSONArray chapters = firstArray(item, "chapter", "episodes", "episode", "episode_list", "daftar_episode", "list_episode");
        if (chapters == null) return parsed;
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject chapter = chapters.optJSONObject(i);
            if (chapter == null) continue;
            String episodeUrl = firstUseful(chapter.optString("url", ""), chapter.optString("link", ""), chapter.optString("slug", ""), chapter.optString("permalink", ""));
            if (!isUseful(episodeUrl)) continue;
            int id = chapter.optInt("id", 0);
            if (id <= 0) id = positiveId(episodeUrl);
            String ch = firstUseful(chapter.optString("ch", ""), chapter.optString("episode", ""), chapter.optString("eps", ""), chapter.optString("title", ""), chapter.optString("name", ""));
            String label = buildAnimeLoverzEpisodeLabel(ch);
            parsed.add(new Episode(id, label));
            episodeMetaMap.put(id, new EpisodeMeta(id, episodeUrl, ch, label));
        }
        return parsed;
    }

    private String buildAnimeLoverzEpisodeLabel(String ch) {
        if (!isUseful(ch)) return "Episode";
        String value = ch.trim();
        return value.toLowerCase(Locale.US).contains("episode") ? value : "Episode " + value;
    }

    private String valueOrCurrent(String value, String current) {
        return isUseful(value) ? value.trim() : current;
    }

    private void updateEpisodes(ArrayList<Episode> data) {
        episodesList.clear();
        if (data != null) episodesList.addAll(data);
        sortEpisodes();
        episodeAdapter = new AnimekuEpisodeAdapter(requireContext(), episodesList, AnimeSettingsManager.SOURCE_ANIMELOVERZ);
        episodesListView.setAdapter(episodeAdapter);
        setTextOrHide(episodeCountTextView, episodesList.isEmpty() ? "" : episodesList.size() + " episode");
        setListViewHeightBasedOnChildren(episodesListView);
    }

    private void sortEpisodes() {
        Collections.sort(episodesList, (a, b) -> {
            float ea = episodeIndex(a == null ? "" : a.channelName);
            float eb = episodeIndex(b == null ? "" : b.channelName);
            int result = Float.compare(ea, eb);
            return episodeNewestFirst ? -result : result;
        });
    }

    private void toggleEpisodeOrder() {
        episodeNewestFirst = !episodeNewestFirst;
        sortEpisodes();
        if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
        setListViewHeightBasedOnChildren(episodesListView);
        orderEpisodeButton.setText(episodeNewestFirst ? "Episode Terbaru" : "Episode Awal");
    }

    private void openStartEpisode() {
        if (episodesList.isEmpty()) {
            showToast("Episode belum tersedia");
            return;
        }
        openEpisode(episodesList.get(0).channelId);
    }

    private void openEpisode(int episodeId) {
        EpisodeMeta meta = episodeMetaMap.get(episodeId);
        if (meta == null) {
            showToast("Episode tidak valid");
            return;
        }
        fetchEpisodeForPlayback(meta);
    }

    private void fetchEpisodeForPlayback(EpisodeMeta meta) {
        showLoading(true);
        ArrayList<String[]> attempts = animeLoverzEpisodeRequestVariants(meta.url, currentSlug);
        fetchEpisodeForPlaybackVariant(meta, attempts, 0);
    }

    private void fetchEpisodeForPlaybackVariant(EpisodeMeta meta, ArrayList<String[]> attempts, int index) {
        if (attempts == null || index >= attempts.size()) {
            showLoading(false);
            showToast("URL video tidak tersedia untuk episode ini");
            return;
        }
        String episodeUrl = attempts.get(index)[0];
        String seriesSlug = attempts.get(index)[1];
        String url = API_BASE + "/series/episode/data.php?url=" + Uri.encode(episodeUrl);
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONObject item = firstDataObject(json);
                ArrayList<QualityOption> options = item == null ? new ArrayList<>() : parseQualities(item.optJSONObject("streams"));
                if (options.isEmpty()) {
                    fetchEpisodeForPlaybackVariant(meta, attempts, index + 1);
                    return;
                }
                String selected = PlaybackQualityManager.getQuality(requireContext());
                QualityOption option = findQualityOption(options, selected);
                showLoading(false);
                if (option != null) openVideoPlayer(meta, option, false);
                else showQualityFallbackDialog(meta, options, selected);
            } catch (Exception e) {
                Log.e(TAG, "Playback parse error", e);
                fetchEpisodeForPlaybackVariant(meta, attempts, index + 1);
            }
        }, error -> {
            Log.e(TAG, "Playback network error", error);
            fetchEpisodeForPlaybackVariant(meta, attempts, index + 1);
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
            @Override public String getBodyContentType() { return "text/plain; charset=utf-8"; }
            @Override public byte[] getBody() {
                String body = "{\"post_type\":\"2\",\"post_id\":\"" + escapeJson(episodeUrl) + "\",\"series_id\":\"" + escapeJson(seriesSlug) + "\",\"series_url\":\"" + escapeJson(seriesSlug) + "\",\"episode\":\"" + escapeJson(meta.episode) + "\",\"token\":\"\"}";
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private ArrayList<QualityOption> parseQualities(JSONObject streams) {
        ArrayList<QualityOption> options = new ArrayList<>();
        addQuality(options, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD), firstStream(streams, "480p", "360p"));
        addQuality(options, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD), firstStream(streams, "720p", "480p", "360p", "1080p"));
        addQuality(options, PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD), firstStream(streams, "1080p", "720p", "480p", "360p"));
        return options;
    }

    private void addQuality(ArrayList<QualityOption> options, String quality, String label, String url) {
        if (isPlayable(url)) options.add(new QualityOption(quality, label, url));
    }

    private String firstStream(JSONObject streams, String... keys) {
        if (streams == null || keys == null) return "";
        for (String key : keys) {
            JSONArray array = streams.optJSONArray(key);
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                String link = item == null ? "" : item.optString("link", "");
                if (isPlayable(link)) return link;
            }
        }
        return "";
    }

    private QualityOption findQualityOption(ArrayList<QualityOption> options, String quality) {
        if (quality == null) return null;
        for (QualityOption option : options) if (quality.equals(option.quality)) return option;
        return null;
    }

    private void showQualityFallbackDialog(EpisodeMeta meta, ArrayList<QualityOption> options, String missingQuality) {
        if (!isAdded() || getContext() == null) return;
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) labels[i] = options.get(i).label;
        String missingLabel = PlaybackQualityManager.getQualityLabel(missingQuality);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Resolusi tidak tersedia")
                .setMessage(missingLabel + " tidak tersedia untuk episode ini. Pilih resolusi yang tersedia:")
                .setItems(labels, (dialog, which) -> openVideoPlayer(meta, options.get(which), true))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void openVideoPlayer(EpisodeMeta meta, QualityOption option, boolean saveAsDefault) {
        if (meta == null || option == null || !isPlayable(option.url)) {
            showToast("URL video tidak tersedia untuk episode ini");
            return;
        }
        if (saveAsDefault) PlaybackQualityManager.setQuality(requireContext(), option.quality);
        Intent intent = new Intent(requireContext(), AnimekuVideoPlayerActivity.class);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_URL, option.url);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_TITLE, meta.label + " • " + option.label);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_IMAGE_URL, currentImageUrl);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CHANNEL_ID, meta.id);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_ID, currentCategoryId);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_NAME, currentTitle);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_START_POSITION, HistoryManager.getPositionForChannel(requireContext(), AnimeSettingsManager.SOURCE_ANIMELOVERZ, meta.id));
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_QUALITY, option.quality);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_DISABLE_PLAYLIST, false);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_HISTORY_SOURCE_ID, AnimeSettingsManager.SOURCE_ANIMELOVERZ);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_ANIMELOVERZ_SLUG, currentSlug);
        startActivity(intent);
    }

    private void bindImages(String url) {
        currentImageUrl = url == null ? "" : url.trim();
        if (!isUseful(currentImageUrl)) return;
        Glide.with(this).load(currentImageUrl).centerCrop().into(imageView);
        Glide.with(this).load(currentImageUrl).centerCrop().into(backdropImageView);
    }

    private void saveCurrentImageToGallery() {
        if (!isUseful(currentImageUrl)) {
            Toast.makeText(requireContext(), "Gambar belum tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        Glide.with(this).asBitmap().load(currentImageUrl).into(new CustomTarget<Bitmap>() {
            @Override public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, "animeloverz_" + System.currentTimeMillis() + ".jpg");
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MikuAnime");
                    Uri uri = requireContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new Exception("Uri kosong");
                    try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                        resource.compress(Bitmap.CompressFormat.JPEG, 95, out);
                    }
                    Toast.makeText(requireContext(), "Gambar tersimpan di galeri", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Gagal menyimpan gambar", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) { }
        });
    }

    private void toggleSynopsis() {
        if (synopsisTextView == null) return;
        int max = synopsisTextView.getMaxLines();
        boolean collapsed = max > 0 && max < 100;
        synopsisTextView.setMaxLines(collapsed ? Integer.MAX_VALUE : 4);
        expandSynopsisTextView.setText(collapsed ? "Tampilkan lebih sedikit" : "Baca selengkapnya");
    }

    private void setListViewHeightBasedOnChildren(ListView listView) {
        if (listView == null) return;
        ListAdapter adapter = listView.getAdapter();
        if (adapter == null) return;
        int totalHeight = 0;
        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(listView.getWidth() <= 0 ? getResources().getDisplayMetrics().widthPixels : listView.getWidth(), View.MeasureSpec.AT_MOST);
        for (int i = 0; i < adapter.getCount(); i++) {
            View item = adapter.getView(i, null, listView);
            item.measure(widthMeasureSpec, View.MeasureSpec.UNSPECIFIED);
            totalHeight += item.getMeasuredHeight();
        }
        LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight + listView.getDividerHeight() * Math.max(0, adapter.getCount() - 1);
        listView.setLayoutParams(params);
        listView.requestLayout();
    }

    private float episodeIndex(String text) {
        if (text == null) return Float.MAX_VALUE;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'));
        if (!matcher.find()) return Float.MAX_VALUE;
        try { return Float.parseFloat(matcher.group(1)); } catch (Exception ignored) { return Float.MAX_VALUE; }
    }

    private JSONObject firstDataObject(JSONObject json) {
        if (json == null) return null;
        Object data = json.opt("data");
        if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            return array.length() == 0 ? null : array.optJSONObject(0);
        }
        if (data instanceof JSONObject) return (JSONObject) data;
        if (json.has("judul") || json.has("chapter") || json.has("streams")) return json;
        return null;
    }

    private JSONArray firstArray(JSONObject json, String... names) {
        if (json == null || names == null) return null;
        for (String name : names) {
            JSONArray array = json.optJSONArray(name);
            if (array != null) return array;
        }
        return null;
    }

    private String firstUseful(String... values) {
        if (values == null) return "";
        for (String value : values) if (isUseful(value)) return value.trim();
        return "";
    }

    private String normalizeAnimeLoverzSlug(String value) {
        String slug = value == null ? "" : Uri.decode(value).trim();
        while (slug.startsWith("/")) slug = slug.substring(1);
        while (slug.endsWith("/")) slug = slug.substring(0, slug.length() - 1);
        return slug;
    }

    private String animeLoverzSlugWithSlash(String value) {
        String slug = normalizeAnimeLoverzSlug(value);
        return slug.isEmpty() ? "" : slug + "/";
    }

    private ArrayList<String> animeLoverzSlugVariants(String value) {
        ArrayList<String> variants = new ArrayList<>();
        String raw = value == null ? "" : Uri.decode(value).trim();
        while (raw.startsWith("/")) raw = raw.substring(1);
        String clean = normalizeAnimeLoverzSlug(raw);
        String slash = animeLoverzSlugWithSlash(raw);
        if (raw.endsWith("/")) {
            addVariant(variants, slash);
            addVariant(variants, clean);
        } else {
            addVariant(variants, clean);
            addVariant(variants, slash);
        }
        return variants;
    }

    private ArrayList<String[]> animeLoverzEpisodeRequestVariants(String episode, String series) {
        ArrayList<String[]> attempts = new ArrayList<>();
        ArrayList<String> episodeVariants = animeLoverzSlugVariants(episode);
        ArrayList<String> seriesVariants = animeLoverzSlugVariants(series);
        for (String episodeVariant : episodeVariants) {
            for (String seriesVariant : seriesVariants) addEpisodeVariant(attempts, episodeVariant, seriesVariant);
        }
        return attempts;
    }

    private void addVariant(ArrayList<String> variants, String value) {
        if (variants == null || !isUseful(value)) return;
        if (!variants.contains(value)) variants.add(value);
    }

    private void addEpisodeVariant(ArrayList<String[]> attempts, String episode, String series) {
        if (attempts == null || !isUseful(episode) || !isUseful(series)) return;
        for (String[] attempt : attempts) if (attempt[0].equals(episode) && attempt[1].equals(series)) return;
        attempts.add(new String[]{episode, series});
    }

    private boolean hasAnimeLoverzEpisodes(JSONObject item) {
        JSONArray chapters = firstArray(item, "chapter", "episodes", "episode", "episode_list", "daftar_episode", "list_episode");
        if (chapters == null || chapters.length() == 0) return false;
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject chapter = chapters.optJSONObject(i);
            if (chapter == null) continue;
            String episodeUrl = firstUseful(chapter.optString("url", ""), chapter.optString("link", ""), chapter.optString("slug", ""), chapter.optString("permalink", ""));
            if (isUseful(episodeUrl)) return true;
        }
        return false;
    }

    private String joinArray(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) values.add(value);
        }
        return android.text.TextUtils.join(", ", values);
    }

    private String genreField(JSONObject item, String key) {
        if (item == null || key == null) return "";
        JSONArray array = item.optJSONArray(key);
        if (array != null) return joinArray(array);
        Object value = item.opt(key);
        if (value instanceof JSONArray) return joinArray((JSONArray) value);
        return formatGenreText(value == null ? "" : String.valueOf(value));
    }

    private String formatGenreText(String value) {
        if (!isUseful(value)) return "";
        String text = value.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                return joinArray(new JSONArray(text));
            } catch (Exception ignored) { }
        }
        text = text.replace("\\", "").replace("[", "").replace("]", "").replace(String.valueOf('"'), "");
        text = text.replaceAll("\\/", "/").replaceAll("\\s*,\\s*", ", ").replaceAll("\\s+", " ").trim();
        return text;
    }

    private String label(String title, String value) {
        return isUseful(value) ? title + ": " + value.trim() : "";
    }

    private void bindSynopsisText(String synopsis) {
        if (synopsisTextView != null) {
            synopsisTextView.setText(isUseful(synopsis) ? synopsis.trim() : "Belum ada deskripsi");
            synopsisTextView.setVisibility(View.VISIBLE);
        }
        if (expandSynopsisTextView != null) expandSynopsisTextView.setVisibility(isUseful(synopsis) ? View.VISIBLE : View.GONE);
    }

    private void setTextOrHide(TextView textView, String value) {
        if (textView == null) return;
        if (isUseful(value)) {
            textView.setText(value);
            textView.setVisibility(View.VISIBLE);
        } else {
            textView.setText("");
            textView.setVisibility(View.GONE);
        }
    }

    private boolean isUseful(String value) {
        if (value == null) return false;
        String v = value.trim();
        return !v.isEmpty() && !"null".equalsIgnoreCase(v) && !"#".equals(v) && !"-".equals(v);
    }

    private boolean isPlayable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private void showLoading(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showToast(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private int positiveId(String value) {
        int id = value == null ? 1 : value.hashCode();
        return id == Integer.MIN_VALUE ? 1 : Math.abs(id);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", "Dart/3.9 (dart:io)");
        h.put("accept", "application/json");
        h.put("access-control-allow-origin", "*");
        h.put("content-type", "text/plain; charset=utf-8");
        return h;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class EpisodeMeta {
        final int id;
        final String url;
        final String episode;
        final String label;

        EpisodeMeta(int id, String url, String episode, String label) {
            this.id = id;
            this.url = url == null ? "" : url;
            this.episode = episode == null ? "" : episode;
            this.label = label == null ? "Episode" : label;
        }
    }

    private static final class QualityOption {
        final String quality;
        final String label;
        final String url;

        QualityOption(String quality, String label, String url) {
            this.quality = quality;
            this.label = label;
            this.url = url;
        }
    }
}
