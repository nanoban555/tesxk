package miku.moe.app;

import android.os.Build;
import android.content.Intent;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MikuDetail extends Fragment {
    private static final String TAG = "MikuDetail";
    private static final String ARG_CATEGORY_ID = "category_id";
    private static final String ARG_CHANNEL_ID = "channel_id";
    private static final String CATEGORY_URL = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_category_posts_secure/";
    private static final String DESCRIPTION_URL = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_post_description/";
    private static final int EPISODE_RENDER_BATCH_SIZE = 40;

    private ImageView imageView, backdropImageView;
    private TextView categoryNameTextView, genreTextView, ratingTextView, episodeCountTextView;
    private TextView synopsisTextView, expandSynopsisTextView, japaneseTextView, englishTextView, typeTextView, airedTextView;
    private TextView premieredTextView, studiosTextView, sourceTextView, durationTextView, yearTextView, viewsTextView;
    private View synopsisCard, infoCard;
    private MaterialButton favoriteButton, startButton, saveImageButton, orderEpisodeButton;
    private ListView episodesListView;
    private ProgressBar progressBar;
    private RequestQueue requestQueue;
    private AnimePost currentAnimePost;
    private final ArrayList<Episode> episodesList = new ArrayList<>();
    private final ArrayList<Episode> pendingEpisodes = new ArrayList<>();
    private final Handler episodeHandler = new Handler(Looper.getMainLooper());
    private EpisodeAdapter episodeAdapter;
    private boolean episodeNewestFirst = false;
    private String currentImageUrl = "";

    public static MikuDetail newInstance(int categoryId) { return newInstance(categoryId, -1); }

    public static MikuDetail newInstance(int categoryId, int channelId) {
        MikuDetail fragment = new MikuDetail();
        Bundle args = new Bundle();
        args.putInt(ARG_CATEGORY_ID, categoryId);
        args.putInt(ARG_CHANNEL_ID, channelId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_miku_detail, container, false);
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
        synopsisCard = view.findViewById(R.id.synopsisCard);
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
        infoCard = view.findViewById(R.id.infoCard);
        favoriteButton = view.findViewById(R.id.favoriteButton);
        startButton = view.findViewById(R.id.startButton);
        saveImageButton = view.findViewById(R.id.saveImageButton);
        orderEpisodeButton = view.findViewById(R.id.orderEpisodeButton);
        episodesListView = view.findViewById(R.id.episodesListView);
        progressBar = view.findViewById(R.id.progressBar);

        expandSynopsisTextView.setOnClickListener(v -> toggleSynopsis());
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        startButton.setOnClickListener(v -> { if (!episodesList.isEmpty()) openEpisodeDirectly(episodesList.get(0).channelId); });
        saveImageButton.setOnClickListener(v -> saveCurrentImageToGallery());
        orderEpisodeButton.setOnClickListener(v -> toggleEpisodeOrder());

        int categoryId = getArguments() == null ? -1 : getArguments().getInt(ARG_CATEGORY_ID, -1);
        int channelId = getArguments() == null ? -1 : getArguments().getInt(ARG_CHANNEL_ID, -1);
        if (categoryId == -1) {
            Toast.makeText(requireContext(), "Invalid category ID", Toast.LENGTH_SHORT).show();
            return;
        }
        fetchAnimeDetails(categoryId, channelId);
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
        episodeHandler.removeCallbacksAndMessages(null);
        if (requestQueue != null) requestQueue.cancelAll(TAG);
        if (episodesListView != null) {
            episodesListView.setOnItemClickListener(null);
            episodesListView.setAdapter(null);
        }
        episodeAdapter = null;
        super.onDestroyView();
    }

    private void fetchAnimeDetails(int categoryId, int firstKnownChannelId) {
        progressBar.setVisibility(View.VISIBLE);
        StringRequest request = new StringRequest(Request.Method.POST, CATEGORY_URL, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if ("ok".equals(json.optString("status"))) {
                    JSONObject category = json.getJSONObject("category");
                    String categoryImage = category.optString("img_url");
                    String title = category.optString("category_name");
                    bindImages(categoryImage);
                    categoryNameTextView.setText(title);
                    setTextOrHide(genreTextView, category.optString("genre"));
                    setTextOrHide(ratingTextView, isUseful(category.optString("rating")) ? "★ " + category.optString("rating") : "");

                    currentAnimePost = new AnimePost(categoryImage, title, categoryId, firstKnownChannelId);
                    currentAnimePost.genre = category.optString("genre", "");
                    currentAnimePost.rating = category.optString("rating", "");
                    updateFavoriteUi();

                    ArrayList<Episode> parsedEpisodes = new ArrayList<>();
                    int channelForDescription = firstKnownChannelId;
                    JSONArray posts = json.optJSONArray("posts");
                    if (posts != null) {
                        for (int i = 0; i < posts.length(); i++) {
                            JSONObject post = posts.getJSONObject(i);
                            int channelId = post.optInt("channel_id", -1);
                            String channelName = post.optString("channel_name");
                            if (channelId > 0) {
                                if (channelForDescription <= 0) channelForDescription = channelId;
                                parsedEpisodes.add(new Episode(channelId, channelName));
                            }
                        }
                    }
                    updateEpisodes(parsedEpisodes);
                    episodesListView.setOnItemClickListener((parent, v, position, id) -> openEpisodeDirectly(episodesList.get(position).channelId));
                    if (currentAnimePost != null) currentAnimePost.channelId = channelForDescription;
                    if (channelForDescription > 0) fetchPostDescription(channelForDescription); else progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "Gagal mengambil data", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                progressBar.setVisibility(View.GONE);
                Log.e(TAG, "Parse error", e);
                Toast.makeText(requireContext(), "Terjadi kesalahan dalam mem-parsing data", Toast.LENGTH_SHORT).show();
            }
        }, error -> {
            Log.e(TAG, "Network error", error);
            progressBar.setVisibility(View.GONE);
            Toast.makeText(requireContext(), "Kesalahan jaringan", Toast.LENGTH_SHORT).show();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("id", String.valueOf(categoryId));
                p.put("isAPKvalid", "true");
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        requestQueue.add(request);
    }

    private void openEpisodeDirectly(int channelId) {
        if (channelId <= 0) {
            Toast.makeText(requireContext(), "Episode tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        fetchEpisodeForPlayback(channelId);
    }

    private void fetchEpisodeForPlayback(int channelId) {
        progressBar.setVisibility(View.VISIBLE);
        StringRequest request = new StringRequest(Request.Method.POST, DESCRIPTION_URL, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(requireContext(), "Gagal mengambil data episode", Toast.LENGTH_SHORT).show();
                    return;
                }

                EpisodePlaybackData playbackData = EpisodePlaybackData.fromJson(json, channelId, currentAnimePost, currentImageUrl);
                ArrayList<QualityOption> availableQualities = getAvailableQualities(json);
                if (availableQualities.isEmpty()) {
                    Toast.makeText(requireContext(), "URL video tidak tersedia untuk episode ini", Toast.LENGTH_SHORT).show();
                    return;
                }

                String selectedQuality = PlaybackQualityManager.getQuality(requireContext());
                QualityOption selectedOption = findQualityOption(availableQualities, selectedQuality);
                if (selectedOption != null) {
                    openVideoPlayer(playbackData, selectedOption, false);
                } else {
                    showQualityFallbackDialog(playbackData, availableQualities, selectedQuality);
                }
            } catch (Exception e) {
                Log.e(TAG, "Episode playback parse error", e);
                Toast.makeText(requireContext(), "Terjadi kesalahan saat membuka video", Toast.LENGTH_SHORT).show();
            } finally {
                progressBar.setVisibility(View.GONE);
            }
        }, error -> {
            Log.e(TAG, "Episode playback network error", error);
            progressBar.setVisibility(View.GONE);
            Toast.makeText(requireContext(), "Kesalahan jaringan", Toast.LENGTH_SHORT).show();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("channel_id", String.valueOf(channelId));
                p.put("isAPKvalid", "true");
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        requestQueue.add(request);
    }

    private ArrayList<QualityOption> getAvailableQualities(JSONObject json) {
        ArrayList<QualityOption> options = new ArrayList<>();
        String secretKey = json.optString("secretKey", "");
        addQualityIfAvailable(options, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD),
                firstPlayableDecoded(json, secretKey, "channel_url", "channel_url_ori"));
        addQualityIfAvailable(options, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD),
                firstPlayableDecoded(json, secretKey, "channel_url_hd", "channel_url_hd_ori"));
        addQualityIfAvailable(options, PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD),
                firstPlayableDecoded(json, secretKey, "channel_url_fhd", "channel_url_fhd_ori"));
        return options;
    }

    private void addQualityIfAvailable(ArrayList<QualityOption> options, String quality, String label, String url) {
        if (isPlayableUrl(url)) options.add(new QualityOption(quality, label, url));
    }

    private QualityOption findQualityOption(ArrayList<QualityOption> options, String quality) {
        if (options == null || quality == null) return null;
        for (QualityOption option : options) {
            if (quality.equals(option.quality)) return option;
        }
        return null;
    }

    private void showQualityFallbackDialog(EpisodePlaybackData data, ArrayList<QualityOption> options, String missingQuality) {
        if (!isAdded() || getContext() == null) return;
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) labels[i] = options.get(i).label;
        String missingLabel = PlaybackQualityManager.getQualityLabel(missingQuality);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Resolusi tidak tersedia")
                .setMessage(missingLabel + " tidak tersedia untuk episode ini. Pilih resolusi yang tersedia:")
                .setItems(labels, (dialog, which) -> openVideoPlayer(data, options.get(which), true))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void openVideoPlayer(EpisodePlaybackData data, QualityOption option, boolean saveAsDefault) {
        if (data == null || option == null || !isPlayableUrl(option.url)) {
            Toast.makeText(requireContext(), "URL video tidak tersedia untuk episode ini", Toast.LENGTH_SHORT).show();
            return;
        }
        if (saveAsDefault) PlaybackQualityManager.setQuality(requireContext(), option.quality);
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, option.url);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, data.episodeTitle + " • " + option.label);
        intent.putExtra(VideoPlayerActivity.EXTRA_IMAGE_URL, data.imageUrl);
        intent.putExtra(VideoPlayerActivity.EXTRA_CHANNEL_ID, data.channelId);
        intent.putExtra(VideoPlayerActivity.EXTRA_CATEGORY_ID, data.categoryId);
        intent.putExtra(VideoPlayerActivity.EXTRA_CATEGORY_NAME, data.categoryName);
        intent.putExtra(VideoPlayerActivity.EXTRA_START_POSITION, HistoryManager.getPositionForChannel(requireContext(), data.channelId));
        intent.putExtra(VideoPlayerActivity.EXTRA_QUALITY, option.quality);
        startActivity(intent);
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

    private static final class EpisodePlaybackData {
        final int channelId;
        final int categoryId;
        final String episodeTitle;
        final String categoryName;
        final String imageUrl;

        EpisodePlaybackData(int channelId, int categoryId, String episodeTitle, String categoryName, String imageUrl) {
            this.channelId = channelId;
            this.categoryId = categoryId;
            this.episodeTitle = episodeTitle;
            this.categoryName = categoryName;
            this.imageUrl = imageUrl;
        }

        static EpisodePlaybackData fromJson(JSONObject json, int fallbackChannelId, AnimePost currentAnimePost, String fallbackImageUrl) {
            int resolvedChannelId = json.optInt("channel_id", fallbackChannelId);
            int resolvedCategoryId = json.optInt("category_id", currentAnimePost == null ? -1 : currentAnimePost.categoryId);
            String episodeTitle = json.optString("channel_name", "Episode");
            String categoryName = json.optString("category_name", currentAnimePost == null ? "" : currentAnimePost.categoryName);
            String imageUrl = json.optString("img_url", fallbackImageUrl);
            return new EpisodePlaybackData(resolvedChannelId, resolvedCategoryId, episodeTitle, categoryName, imageUrl);
        }
    }

    private String firstPlayable(String... urls) {
        if (urls == null) return "";
        for (String url : urls) if (isPlayableUrl(url)) return url;
        return "";
    }

    private String firstPlayableDecoded(JSONObject json, String secretKey, String... keys) {
        if (json == null || keys == null) return "";
        for (String key : keys) {
            String decoded = AnimeXnontonUrlDecoder.decryptUrl(json.optString(key, ""), secretKey);
            if (isPlayableUrl(decoded)) return decoded;
        }
        return "";
    }

    private boolean isPlayableUrl(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }
    private void fetchPostDescription(int channelId) {
        StringRequest request = new StringRequest(Request.Method.POST, DESCRIPTION_URL, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if ("ok".equalsIgnoreCase(json.optString("status"))) bindDescriptionData(json);
            } catch (Exception e) {
                Log.e(TAG, "Description parse error", e);
            } finally { progressBar.setVisibility(View.GONE); }
        }, error -> { Log.e(TAG, "Description network error", error); progressBar.setVisibility(View.GONE); }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("channel_id", String.valueOf(channelId));
                p.put("isAPKvalid", "true");
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        requestQueue.add(request);
    }

    private void bindDescriptionData(JSONObject json) {
        String imgUrl = json.optString("img_url", "");
        if (isUseful(imgUrl)) bindImages(imgUrl);
        String categoryName = json.optString("category_name", "");
        if (isUseful(categoryName)) categoryNameTextView.setText(categoryName);
        String rating = json.optString("rating", "");
        int year = json.optInt("years", 0);
        String views = json.optString("count_view", "");
        Map<String, String> meta = parseDescriptionHtml(json.optString("channel_description", ""));
        String metaGenre = firstUseful(json.optString("genre", ""), meta.get("Genres"));
        setTextOrHide(genreTextView, metaGenre);
        setTextOrHide(ratingTextView, isUseful(rating) ? "★ " + rating : "");
        setTextOrHide(yearTextView, year > 0 ? String.valueOf(year) : "");
        setTextOrHide(viewsTextView, "");
        String synopsis = meta.get("Synopsis");
        bindSynopsisText(synopsis);

        setTextOrHide(japaneseTextView, label("Japanese", meta.get("Japanese")));
        setTextOrHide(englishTextView, label("English", meta.get("English")));
        setTextOrHide(typeTextView, label("Type", meta.get("Type")));
        setTextOrHide(airedTextView, label("Aired", meta.get("Aired")));
        setTextOrHide(premieredTextView, label("Premiered", meta.get("Premiered")));
        setTextOrHide(studiosTextView, label("Studios", meta.get("Studios")));
        setTextOrHide(sourceTextView, label("Source", meta.get("Source")));
        setTextOrHide(durationTextView, label("Duration", meta.get("Duration")));

        if (currentAnimePost != null) {
            if (isUseful(imgUrl)) currentAnimePost.imgUrl = imgUrl;
            if (isUseful(categoryName)) currentAnimePost.categoryName = categoryName;
            currentAnimePost.genre = metaGenre == null ? "" : metaGenre;
            currentAnimePost.rating = rating == null ? "" : rating;
            currentAnimePost.year = year;
            updateFavoriteUi();
        }
        boolean hasInfo = japaneseTextView.getVisibility() == View.VISIBLE || englishTextView.getVisibility() == View.VISIBLE ||
                typeTextView.getVisibility() == View.VISIBLE || airedTextView.getVisibility() == View.VISIBLE ||
                premieredTextView.getVisibility() == View.VISIBLE || studiosTextView.getVisibility() == View.VISIBLE ||
                sourceTextView.getVisibility() == View.VISIBLE || durationTextView.getVisibility() == View.VISIBLE;
        infoCard.setVisibility(hasInfo ? View.VISIBLE : View.GONE);
    }

    private void bindImages(String url) {
        if (!isUseful(url)) return;
        currentImageUrl = url;
        Glide.with(this).load(url).centerCrop().into(imageView);
        Glide.with(this).load(url).centerCrop().into(backdropImageView);
    }

    private void toggleEpisodeOrder() {
        episodeNewestFirst = !episodeNewestFirst;
        applyEpisodeOrder(true);
    }

    private void updateEpisodes(ArrayList<Episode> data) {
        episodeHandler.removeCallbacksAndMessages(null);
        pendingEpisodes.clear();
        episodesList.clear();
        if (data != null) pendingEpisodes.addAll(data);
        if (episodeNewestFirst) Collections.reverse(pendingEpisodes);
        if (episodeAdapter == null) {
            episodeAdapter = new EpisodeAdapter(requireContext(), episodesList);
            episodesListView.setAdapter(episodeAdapter);
        } else {
            episodeAdapter.notifyDataSetChanged();
        }
        showEpisodeLoading("Menampilkan 0/" + pendingEpisodes.size() + " episode");
        renderNextEpisodeBatch();
    }

    private void renderNextEpisodeBatch() {
        if (!isAdded() || episodesListView == null) return;
        int total = episodesList.size() + pendingEpisodes.size();
        if (pendingEpisodes.isEmpty()) {
            hideEpisodeLoading();
            episodeCountTextView.setText(episodesList.size() + " episode");
            if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
            setListViewHeightBasedOnChildren(episodesListView);
            return;
        }
        int take = Math.min(EPISODE_RENDER_BATCH_SIZE, pendingEpisodes.size());
        ArrayList<Episode> batch = new ArrayList<>(pendingEpisodes.subList(0, take));
        pendingEpisodes.subList(0, take).clear();
        episodesList.addAll(batch);
        if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
        showEpisodeLoading("Menampilkan " + episodesList.size() + "/" + total + " episode");
        episodeHandler.postDelayed(this::renderNextEpisodeBatch, 35);
    }

    private void showEpisodeLoading(String text) {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (episodeCountTextView != null && text != null) episodeCountTextView.setText(text);
    }

    private void hideEpisodeLoading() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    private void applyEpisodeOrder(boolean notify) {
        if (notify && episodesList.size() > 1) Collections.reverse(episodesList);
        if (notify && pendingEpisodes.size() > 1) Collections.reverse(pendingEpisodes);
        if (orderEpisodeButton != null) orderEpisodeButton.setText(episodeNewestFirst ? "Urutan: Baru" : "Urutan: Lama");
        if (notify && episodesListView != null && episodeAdapter != null) {
            episodeAdapter.notifyDataSetChanged();
            setListViewHeightBasedOnChildren(episodesListView);
        }
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
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, "miku_anime_" + System.currentTimeMillis() + ".jpg");
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

    private Map<String, String> parseDescriptionHtml(String html) {
        Map<String, String> result = new HashMap<>();
        if (!isUseful(html)) return result;
        Matcher pMatcher = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (pMatcher.find()) {
            String rawParagraph = pMatcher.group(1);
            String cleanParagraph = cleanHtml(rawParagraph);
            if (!isUseful(cleanParagraph)) continue;
            Matcher strongMatcher = Pattern.compile("<strong[^>]*>(.*?)</strong>\\s*:?\\s*(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(rawParagraph);
            if (strongMatcher.find()) {
                String key = cleanHtml(strongMatcher.group(1)).replace(":", "").trim();
                String value = cleanHtml(strongMatcher.group(2)).replaceFirst("^:", "").trim();
                if (isUseful(key) && isUseful(value)) result.put(key, value);
            } else if (!result.containsKey("Synopsis")) {
                result.put("Synopsis", cleanParagraph.replaceFirst("^SINOPSIS\\s*", "").trim());
            }
        }
        return result;
    }

    private String cleanHtml(String value) {
        if (value == null) return "";
        String normalized = value.replace("<br />", "\n").replace("<br/>", "\n").replace("<br>", "\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY).toString().trim();
        return Html.fromHtml(normalized).toString().trim();
    }

    private void toggleSynopsis() {
        boolean expanded = synopsisTextView.getMaxLines() > 20;
        synopsisTextView.setMaxLines(expanded ? 6 : 100);
        expandSynopsisTextView.setText(expanded ? "Tampilkan lebih banyak" : "Tampilkan lebih sedikit");
    }

    private void toggleFavorite() {
        if (currentAnimePost == null || currentAnimePost.categoryId <= 0) return;
        FavoriteManager.toggle(requireContext(), currentAnimePost);
        updateFavoriteUi();
    }

    private void updateFavoriteUi() {
        if (!isAdded() || favoriteButton == null || currentAnimePost == null) return;
        boolean favorite = FavoriteManager.isFavorite(requireContext(), AnimeSettingsManager.SOURCE_DEFAULT, currentAnimePost.categoryId, currentAnimePost.slug);
        favoriteButton.setText(favorite ? "Di Favorite" : "Favorite");
    }

    private String label(String title, String value) { return isUseful(value) ? title + ": " + value.trim() : ""; }
    private String firstUseful(String primary, String fallback) { return isUseful(primary) ? primary : fallback; }
    private String formatViews(String views) {
        if (!isUseful(views)) return "";
        try { return String.format(Locale.US, "%,d", Long.parseLong(views)); } catch (Exception ignored) { return views; }
    }
    private boolean isUseful(String value) {
        if (value == null) return false;
        String v = value.trim();
        return !v.isEmpty() && !"null".equalsIgnoreCase(v) && !"#".equals(v) && !"-".equals(v);
    }
    private void bindSynopsisText(String synopsis) {
        if (synopsisCard != null) synopsisCard.setVisibility(View.VISIBLE);
        if (synopsisTextView != null) {
            synopsisTextView.setText(isUseful(synopsis) ? synopsis.trim() : "Belum ada deskripsi");
            synopsisTextView.setVisibility(View.VISIBLE);
        }
        if (expandSynopsisTextView != null) expandSynopsisTextView.setVisibility(isUseful(synopsis) ? View.VISIBLE : View.GONE);
    }

    private void setTextOrHide(TextView textView, String value) {
        if (textView == null) return;
        if (isUseful(value)) { textView.setText(value); textView.setVisibility(View.VISIBLE); }
        else { textView.setText(""); textView.setVisibility(View.GONE); }
    }

    private void setListViewHeightBasedOnChildren(ListView listView) {
        android.widget.ListAdapter listAdapter = listView.getAdapter();
        if (listAdapter == null) return;
        int totalHeight = listView.getPaddingTop() + listView.getPaddingBottom();
        int desiredWidth = View.MeasureSpec.makeMeasureSpec(listView.getWidth(), View.MeasureSpec.AT_MOST);
        for (int i = 0; i < listAdapter.getCount(); i++) {
            View item = listAdapter.getView(i, null, listView);
            item.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED);
            totalHeight += item.getMeasuredHeight();
        }
        totalHeight += listView.getDividerHeight() * Math.max(0, listAdapter.getCount() - 1);
        LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight;
        listView.setLayoutParams(params);
        listView.requestLayout();
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "AnimeXNonton 26.9.5/18");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("User-Agent", "okhttp/5.5.0");
        return h;
    }
}
