package miku.moe.app;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnimekuDetail extends Fragment {
    private static final String TAG = "AnimekuDetail";
    private static final String ARG_CATEGORY_ID = "category_id";
    private static final String ARG_VIDEO_ID = "video_id";
    private static final String ARG_TITLE = "title";
    private static final String ARG_IMAGE_URL = "image_url";
    private static final String ARG_GENRE = "genre";
    private static final String ARG_RATING = "rating";
    private static final String ARG_YEAR = "year";
    private static final String ARG_VIEWS = "views";
    private static final String ARG_EPISODE_COUNT = "episode_count";
    private static final String ARG_DESCRIPTION = "description";
    private static final String API_BASE = "https://pencarinafkah.xyz/vA6//api/";
    private static final String API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO";
    private static final String IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/";
    private static final int PAGE_SIZE = 50;
    private static final int FIND_PAGE_LIMIT = 12;
    private static final int EPISODE_RENDER_BATCH_SIZE = 40;

    private ImageView imageView, backdropImageView;
    private TextView categoryNameTextView, genreTextView, ratingTextView, episodeCountTextView;
    private TextView synopsisTextView, expandSynopsisTextView, japaneseTextView, englishTextView, typeTextView, airedTextView;
    private TextView premieredTextView, studiosTextView, sourceTextView, durationTextView, yearTextView, viewsTextView;
    private View infoCard;
    private MaterialButton favoriteButton, startButton, saveImageButton, orderEpisodeButton;
    private ListView episodesListView;
    private ProgressBar progressBar;
    private RequestQueue requestQueue;
    private AnimePost currentAnimePost;
    private final ArrayList<Episode> episodesList = new ArrayList<>();
    private final ArrayList<Episode> pendingEpisodes = new ArrayList<>();
    private final Handler episodeHandler = new Handler(Looper.getMainLooper());
    private AnimekuEpisodeAdapter episodeAdapter;
    private boolean episodeNewestFirst = false;
    private String currentImageUrl = "";
    private int resolvedCategoryId = -1;
    private int resolvedVideoId = -1;

    public static AnimekuDetail newInstance(int categoryId, int videoId, String title, String imageUrl, String genre, String rating, int year, String views, String episodeCount, String description) {
        AnimekuDetail fragment = new AnimekuDetail();
        Bundle args = new Bundle();
        args.putInt(ARG_CATEGORY_ID, categoryId);
        args.putInt(ARG_VIDEO_ID, videoId);
        args.putString(ARG_TITLE, safe(title));
        args.putString(ARG_IMAGE_URL, safe(imageUrl));
        args.putString(ARG_GENRE, safe(genre));
        args.putString(ARG_RATING, safe(rating));
        args.putInt(ARG_YEAR, year);
        args.putString(ARG_VIEWS, safe(views));
        args.putString(ARG_EPISODE_COUNT, safe(episodeCount));
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
        infoCard = view.findViewById(R.id.infoCard);
        favoriteButton = view.findViewById(R.id.favoriteButton);
        startButton = view.findViewById(R.id.startButton);
        saveImageButton = view.findViewById(R.id.saveImageButton);
        orderEpisodeButton = view.findViewById(R.id.orderEpisodeButton);
        episodesListView = view.findViewById(R.id.episodesListView);
        progressBar = view.findViewById(R.id.progressBar);

        expandSynopsisTextView.setOnClickListener(v -> toggleSynopsis());
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        startButton.setOnClickListener(v -> openStartEpisode());
        saveImageButton.setOnClickListener(v -> saveCurrentImageToGallery());
        orderEpisodeButton.setOnClickListener(v -> toggleEpisodeOrder());

        Bundle args = getArguments();
        int categoryId = args == null ? -1 : args.getInt(ARG_CATEGORY_ID, -1);
        int videoId = args == null ? -1 : args.getInt(ARG_VIDEO_ID, -1);
        resolvedCategoryId = categoryId;
        resolvedVideoId = videoId;
        bindInitialData(args);
        if (categoryId > 0) fetchAnimeDetail(categoryId, true); else if (videoId > 0) fetchPostDetail(videoId, true); else Toast.makeText(requireContext(), "Data Animeku tidak valid", Toast.LENGTH_SHORT).show();
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

    private void bindInitialData(Bundle args) {
        if (args == null) return;
        int categoryId = args.getInt(ARG_CATEGORY_ID, -1);
        int videoId = args.getInt(ARG_VIDEO_ID, -1);
        String title = cleanAnimeTitle(args.getString(ARG_TITLE, ""));
        String imageUrl = imageUrl(args.getString(ARG_IMAGE_URL, ""));
        String genre = args.getString(ARG_GENRE, "");
        String rating = args.getString(ARG_RATING, "");
        int year = args.getInt(ARG_YEAR, 0);
        String views = args.getString(ARG_VIEWS, "");
        String episodeCount = args.getString(ARG_EPISODE_COUNT, "");
        String description = args.getString(ARG_DESCRIPTION, "");

        currentAnimePost = new AnimePost(imageUrl, title, categoryId, videoId);
        currentAnimePost.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
        currentAnimePost.genre = genre;
        currentAnimePost.rating = rating;
        currentAnimePost.year = year;
        currentAnimePost.countView = views;
        currentAnimePost.episodeCount = episodeCount;
        if (isUseful(imageUrl)) bindImages(imageUrl);
        setTextOrHide(categoryNameTextView, title);
        setTextOrHide(genreTextView, genre);
        setTextOrHide(ratingTextView, isUseful(rating) ? "★ " + rating : "");
        setTextOrHide(yearTextView, year > 0 ? String.valueOf(year) : "");
        setTextOrHide(viewsTextView, "");
        setTextOrHide(episodeCountTextView, isUseful(episodeCount) ? episodeCount + " episode" : "");
        bindDescription(description);
        updateFavoriteUi();
    }

    private void fetchPostDetail(int videoId, boolean showLoading) {
        if (showLoading) showLoading(true);
        String url = API_BASE + "get_post_detail?id=" + videoId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(requireContext(), "Gagal mengambil detail Animeku", Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONObject post = json.optJSONObject("post");
                if (post == null) {
                    Toast.makeText(requireContext(), "Detail Animeku kosong", Toast.LENGTH_SHORT).show();
                    return;
                }
                bindPostData(post);
                bindEpisodes(post, json.optJSONArray("suggested"));
            } catch (Exception e) {
                Log.e(TAG, "Detail parse error", e);
                Toast.makeText(requireContext(), "Terjadi kesalahan parsing detail Animeku", Toast.LENGTH_SHORT).show();
            } finally {
                showLoading(false);
            }
        }, error -> {
            Log.e(TAG, "Detail network error", error);
            showLoading(false);
            Toast.makeText(requireContext(), "Kesalahan jaringan Animeku", Toast.LENGTH_SHORT).show();
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void fetchAnimeDetail(int categoryId, boolean showLoading) {
        if (showLoading) showLoading(true);
        String url = API_BASE + "get_anime_detail?id=" + categoryId + "&api_key=" + API_KEY;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(requireContext(), "Gagal mengambil detail Animeku", Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONObject category = json.optJSONObject("category");
                JSONArray suggested = json.optJSONArray("suggested");
                if (category == null) {
                    Toast.makeText(requireContext(), "Detail Animeku kosong", Toast.LENGTH_SHORT).show();
                    return;
                }
                bindCategoryData(category);
                bindEpisodes(null, suggested);
                if (episodesList.isEmpty() && resolvedVideoId > 0) fetchPostDetail(resolvedVideoId, false);
                else if (episodesList.isEmpty()) Toast.makeText(requireContext(), "Episode Animeku belum ditemukan", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Anime detail parse error", e);
                Toast.makeText(requireContext(), "Terjadi kesalahan parsing detail Animeku", Toast.LENGTH_SHORT).show();
            } finally {
                showLoading(false);
            }
        }, error -> {
            Log.e(TAG, "Anime detail network error", error);
            showLoading(false);
            Toast.makeText(requireContext(), "Kesalahan jaringan Animeku", Toast.LENGTH_SHORT).show();
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void findFirstVideoForCategory(int categoryId, int page) {
        showLoading(true);
        String url = API_BASE + "get_videos?page=" + page + "&count=" + PAGE_SIZE + "&api_key=" + API_KEY;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONArray latest = json.optJSONArray("latest_anime");
                int foundVideoId = -1;
                if (latest != null) {
                    for (int i = 0; i < latest.length(); i++) {
                        JSONObject item = latest.optJSONObject(i);
                        if (item != null && item.optInt("cat_id", -1) == categoryId) {
                            foundVideoId = item.optInt("vid", -1);
                            break;
                        }
                    }
                }
                if (foundVideoId > 0) {
                    fetchPostDetail(foundVideoId, false);
                } else if (page < FIND_PAGE_LIMIT && latest != null && latest.length() > 0) {
                    findFirstVideoForCategory(categoryId, page + 1);
                } else {
                    showLoading(false);
                    Toast.makeText(requireContext(), "Episode Animeku belum ditemukan", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Find video parse error", e);
                showLoading(false);
                Toast.makeText(requireContext(), "Gagal mencari episode Animeku", Toast.LENGTH_SHORT).show();
            }
        }, error -> {
            Log.e(TAG, "Find video network error", error);
            showLoading(false);
            Toast.makeText(requireContext(), "Kesalahan jaringan Animeku", Toast.LENGTH_SHORT).show();
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void bindCategoryData(JSONObject category) {
        int categoryId = category.optInt("cid", resolvedCategoryId);
        resolvedCategoryId = categoryId;
        String image = imageUrl(category.optString("category_image", ""));
        String title = cleanAnimeTitle(category.optString("category_name", currentAnimePost == null ? "" : currentAnimePost.categoryName));
        String genre = category.optString("genre", "");
        String rating = category.optString("rating", "");
        int year = category.optInt("year", 0);
        String views = category.optString("total_views", "");
        String episodeCount = category.optString("video_count", "");
        String description = category.optString("desc_anime", "");

        currentAnimePost = new AnimePost(image, title, categoryId, resolvedVideoId);
        currentAnimePost.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
        currentAnimePost.channelName = title;
        currentAnimePost.genre = genre;
        currentAnimePost.rating = rating;
        currentAnimePost.year = year;
        currentAnimePost.countView = views;
        currentAnimePost.episodeCount = episodeCount;
        currentAnimePost.description = description;
        currentAnimePost.statusVideo = normalizeStatus(category.optString("status_video", ""));
        currentAnimePost.ongoing = isOngoing(currentAnimePost.statusVideo);

        if (isUseful(image)) bindImages(image);
        setTextOrHide(categoryNameTextView, title);
        setTextOrHide(genreTextView, genre);
        setTextOrHide(ratingTextView, isUseful(rating) ? "★ " + rating : "");
        setTextOrHide(yearTextView, year > 0 ? String.valueOf(year) : "");
        setTextOrHide(viewsTextView, "");
        setTextOrHide(episodeCountTextView, isUseful(episodeCount) ? episodeCount + " episode" : "");
        bindDescription(description);
        updateFavoriteUi();
    }

    private void bindPostData(JSONObject post) {
        int videoId = post.optInt("vid", resolvedVideoId);
        int categoryId = post.optInt("cat_id", resolvedCategoryId);
        resolvedVideoId = videoId;
        resolvedCategoryId = categoryId;
        String image = imageUrl(firstUseful(post.optString("category_image", ""), post.optString("video_thumbnail", "")));
        String title = cleanAnimeTitle(post.optString("category_name", currentAnimePost == null ? "" : currentAnimePost.categoryName));
        String episodeTitle = post.optString("video_title", title);
        String genre = post.optString("genre", "");
        String rating = post.optString("rating", "");
        int year = post.optInt("year", 0);
        String views = post.optString("total_views", "");
        String episodeCount = post.optString("video_count", "");

        currentAnimePost = new AnimePost(image, title, categoryId, videoId);
        currentAnimePost.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
        currentAnimePost.channelName = episodeTitle;
        currentAnimePost.genre = genre;
        currentAnimePost.rating = rating;
        currentAnimePost.year = year;
        currentAnimePost.countView = views;
        currentAnimePost.episodeCount = episodeCount;
        currentAnimePost.statusVideo = normalizeStatus(post.optString("status_video", ""));
        currentAnimePost.ongoing = isOngoing(currentAnimePost.statusVideo);
        currentAnimePost.hdAvailable = isPlayable(post.optString("video_url_hd", ""));
        currentAnimePost.fhdAvailable = isPlayable(post.optString("video_url_fullhd", ""));

        if (isUseful(image)) bindImages(image);
        setTextOrHide(categoryNameTextView, title);
        setTextOrHide(genreTextView, genre);
        setTextOrHide(ratingTextView, isUseful(rating) ? "★ " + rating : "");
        setTextOrHide(yearTextView, year > 0 ? String.valueOf(year) : "");
        setTextOrHide(viewsTextView, "");
        setTextOrHide(episodeCountTextView, isUseful(episodeCount) ? episodeCount + " episode" : "");
        bindDescription(firstUseful(post.optString("video_description", ""), post.optString("desc_anime", "")));
        updateFavoriteUi();
    }

    private void bindEpisodes(JSONObject post, JSONArray suggested) {
        LinkedHashMap<Integer, Episode> unique = new LinkedHashMap<>();
        addEpisode(unique, post);
        if (suggested != null) {
            for (int i = 0; i < suggested.length(); i++) addEpisode(unique, suggested.optJSONObject(i));
        }
        ArrayList<Episode> parsedEpisodes = new ArrayList<>(unique.values());
        Collections.sort(parsedEpisodes, (a, b) -> {
            int ea = extractEpisodeNumber(a.channelName);
            int eb = extractEpisodeNumber(b.channelName);
            if (ea != eb) return Integer.compare(ea, eb);
            return Integer.compare(a.channelId, b.channelId);
        });
        updateEpisodes(parsedEpisodes);
        episodesListView.setOnItemClickListener((parent, v, position, id) -> openEpisodeDirectly(episodesList.get(position).channelId));
        applyEpisodeOrder(false);
    }

    private void addEpisode(LinkedHashMap<Integer, Episode> unique, JSONObject item) {
        if (item == null) return;
        int videoId = item.optInt("vid", -1);
        if (videoId <= 0 || unique.containsKey(videoId)) return;
        String title = item.optString("video_title", "Episode");
        unique.put(videoId, new Episode(videoId, cleanEpisodeTitle(title)));
    }

    private void openStartEpisode() {
        if (!episodesList.isEmpty()) {
            openEpisodeDirectly(episodesList.get(0).channelId);
            return;
        }
        if (resolvedVideoId > 0) openEpisodeDirectly(resolvedVideoId); else Toast.makeText(requireContext(), "Episode belum tersedia", Toast.LENGTH_SHORT).show();
    }

    private void openEpisodeDirectly(int videoId) {
        if (videoId <= 0) {
            Toast.makeText(requireContext(), "Episode tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        fetchEpisodeForPlayback(videoId);
    }

    private void fetchEpisodeForPlayback(int videoId) {
        showLoading(true);
        String url = API_BASE + "get_post_detail?id=" + videoId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(requireContext(), "Gagal mengambil data episode Animeku", Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONObject post = json.optJSONObject("post");
                if (post == null) {
                    Toast.makeText(requireContext(), "Data episode Animeku kosong", Toast.LENGTH_SHORT).show();
                    return;
                }
                EpisodePlaybackData playbackData = EpisodePlaybackData.fromJson(post, videoId, currentAnimePost, currentImageUrl);
                ArrayList<QualityOption> availableQualities = getAvailableQualities(post);
                if (availableQualities.isEmpty()) {
                    Toast.makeText(requireContext(), "URL video tidak tersedia untuk episode ini", Toast.LENGTH_SHORT).show();
                    return;
                }
                String selectedQuality = PlaybackQualityManager.getQuality(requireContext());
                QualityOption selectedOption = findQualityOption(availableQualities, selectedQuality);
                if (selectedOption != null) openVideoPlayer(playbackData, selectedOption, false); else showQualityFallbackDialog(playbackData, availableQualities, selectedQuality);
            } catch (Exception e) {
                Log.e(TAG, "Episode playback parse error", e);
                Toast.makeText(requireContext(), "Terjadi kesalahan saat membuka video", Toast.LENGTH_SHORT).show();
            } finally {
                showLoading(false);
            }
        }, error -> {
            Log.e(TAG, "Episode playback network error", error);
            showLoading(false);
            Toast.makeText(requireContext(), "Kesalahan jaringan Animeku", Toast.LENGTH_SHORT).show();
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private ArrayList<QualityOption> getAvailableQualities(JSONObject json) {
        ArrayList<QualityOption> options = new ArrayList<>();
        addQualityIfAvailable(options, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD), json.optString("video_url", ""));
        String hd = firstPlayable(json.optString("video_url_hd", ""), json.optString("video_url_minihd", ""));
        String hdLabel = isPlayable(json.optString("video_url_hd", "")) ? PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD) : "Mini HD 480p";
        addQualityIfAvailable(options, PlaybackQualityManager.QUALITY_HD, hdLabel, hd);
        addQualityIfAvailable(options, PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD), json.optString("video_url_fullhd", ""));
        return options;
    }

    private void addQualityIfAvailable(ArrayList<QualityOption> options, String quality, String label, String url) {
        if (isPlayable(url)) options.add(new QualityOption(quality, label, url));
    }

    private QualityOption findQualityOption(ArrayList<QualityOption> options, String quality) {
        if (options == null || quality == null) return null;
        for (QualityOption option : options) if (quality.equals(option.quality)) return option;
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
        if (data == null || option == null || !isPlayable(option.url)) {
            Toast.makeText(requireContext(), "URL video tidak tersedia untuk episode ini", Toast.LENGTH_SHORT).show();
            return;
        }
        if (saveAsDefault) PlaybackQualityManager.setQuality(requireContext(), option.quality);
        Intent intent = new Intent(requireContext(), AnimekuVideoPlayerActivity.class);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_URL, option.url);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_VIDEO_TITLE, data.episodeTitle + " • " + option.label);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_IMAGE_URL, data.imageUrl);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CHANNEL_ID, data.videoId);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_ID, data.categoryId);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_CATEGORY_NAME, data.categoryName);
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_START_POSITION, AnimekuHistoryManager.getPositionForChannel(requireContext(), data.videoId));
        intent.putExtra(AnimekuVideoPlayerActivity.EXTRA_QUALITY, option.quality);
        startActivity(intent);
    }

    private void bindDescription(String html) {
        Map<String, String> meta = parseDescriptionHtml(html);
        String synopsis = meta.get("Synopsis");
        bindSynopsisText(synopsis);
        String genre = firstUseful(currentAnimePost == null ? "" : currentAnimePost.genre, firstUseful(meta.get("Genres"), meta.get("Genre")));
        setTextOrHide(genreTextView, genre);
        setTextOrHide(japaneseTextView, label("Japanese", meta.get("Japanese")));
        setTextOrHide(englishTextView, label("English", meta.get("English")));
        setTextOrHide(typeTextView, label("Type", meta.get("Type")));
        setTextOrHide(airedTextView, label("Aired", meta.get("Aired")));
        setTextOrHide(premieredTextView, label("Premiered", meta.get("Premiered")));
        setTextOrHide(studiosTextView, label("Studios", meta.get("Studios")));
        setTextOrHide(sourceTextView, label("Source", meta.get("Source")));
        setTextOrHide(durationTextView, label("Duration", meta.get("Duration")));
        boolean hasInfo = japaneseTextView.getVisibility() == View.VISIBLE || englishTextView.getVisibility() == View.VISIBLE || typeTextView.getVisibility() == View.VISIBLE || airedTextView.getVisibility() == View.VISIBLE || premieredTextView.getVisibility() == View.VISIBLE || studiosTextView.getVisibility() == View.VISIBLE || sourceTextView.getVisibility() == View.VISIBLE || durationTextView.getVisibility() == View.VISIBLE;
        if (infoCard != null) infoCard.setVisibility(hasInfo ? View.VISIBLE : View.GONE);
        if (currentAnimePost != null && isUseful(genre)) currentAnimePost.genre = genre;
    }

    private Map<String, String> parseDescriptionHtml(String html) {
        Map<String, String> result = new HashMap<>();
        if (!isUseful(html)) return result;
        String clean = cleanHtml(html).replace('\u00A0', ' ');
        String[] lines = clean.split("\\r?\\n+");
        boolean synopsisMode = false;
        StringBuilder synopsis = new StringBuilder();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (!isUseful(line)) continue;
            String lower = line.toLowerCase(Locale.US);
            if (lower.equals("synopsis")) {
                synopsisMode = true;
                continue;
            }
            if (lower.equals("alternative titles") || lower.equals("information")) {
                synopsisMode = false;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (isUseful(key) && isUseful(value)) result.put(key, value);
            }
            if (synopsisMode) {
                if (synopsis.length() > 0) synopsis.append('\n');
                synopsis.append(line);
            }
        }
        if (synopsis.length() > 0) result.put("Synopsis", synopsis.toString().trim());
        return result;
    }

    private String cleanHtml(String value) {
        if (value == null) return "";
        String normalized = value.replace("<br />", "\n").replace("<br/>", "\n").replace("<br>", "\n").replace("</p>", "\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY).toString().trim();
        return Html.fromHtml(normalized).toString().trim();
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
            episodeAdapter = new AnimekuEpisodeAdapter(requireContext(), episodesList);
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
            setTextOrHide(episodeCountTextView, episodesList.size() > 0 ? episodesList.size() + " episode" : "");
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
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, "animeku_" + System.currentTimeMillis() + ".jpg");
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
        boolean expanded = synopsisTextView.getMaxLines() > 20;
        synopsisTextView.setMaxLines(expanded ? 6 : 100);
        expandSynopsisTextView.setText(expanded ? "Tampilkan lebih banyak" : "Tampilkan lebih sedikit");
    }

    private void toggleFavorite() {
        if (currentAnimePost == null || currentAnimePost.categoryId <= 0) return;
        currentAnimePost.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
        boolean favorite = FavoriteManager.isFavorite(requireContext(), AnimeSettingsManager.SOURCE_ANIMEKU, currentAnimePost.categoryId, currentAnimePost.slug) || AnimekuFavoriteManager.isFavorite(requireContext(), currentAnimePost.categoryId);
        if (favorite) {
            FavoriteManager.remove(requireContext(), AnimeSettingsManager.SOURCE_ANIMEKU, currentAnimePost.categoryId, currentAnimePost.slug);
            AnimekuFavoriteManager.remove(requireContext(), currentAnimePost.categoryId);
        } else {
            FavoriteManager.add(requireContext(), currentAnimePost);
        }
        updateFavoriteUi();
    }

    private void updateFavoriteUi() {
        if (!isAdded() || favoriteButton == null || currentAnimePost == null) return;
        currentAnimePost.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
        boolean favorite = FavoriteManager.isFavorite(requireContext(), AnimeSettingsManager.SOURCE_ANIMEKU, currentAnimePost.categoryId, currentAnimePost.slug) || AnimekuFavoriteManager.isFavorite(requireContext(), currentAnimePost.categoryId);
        favoriteButton.setText(favorite ? "Di Favorite" : "Favorite");
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

    private String cleanAnimeTitle(String value) {
        if (value == null) return "";
        String cleaned = value.trim().replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("(?i)\\s+Eps?\\s*[-:]*\\s*\\d+.*$", "").trim();
        cleaned = cleaned.replaceAll("(?i)\\s+Episode\\s*[-:]*\\s*\\d+.*$", "").trim();
        return cleaned;
    }

    private String cleanEpisodeTitle(String value) {
        if (value == null) return "Episode";
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? "Episode" : cleaned;
    }

    private String normalizeStatus(String value) {
        if (value == null) return "";
        String raw = value.trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return "";
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("complete") || lower.contains("finished")) return "Completed";
        if (lower.contains("ongoing") || lower.contains("on going") || lower.contains("currently")) return "Ongoing";
        return raw;
    }

    private int extractEpisodeNumber(String title) {
        if (title == null) return Integer.MAX_VALUE;
        String text = title.toLowerCase(Locale.US);
        Matcher matcher = Pattern.compile("(?:episode|eps|ep|e)\\s*[-:]*\\s*(\\d+)").matcher(text);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); } catch (Exception ignored) { }
        }
        matcher = Pattern.compile("\\b(\\d{1,4})\\b").matcher(text);
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n != 360 && n != 480 && n != 720 && n != 1080) return n;
            } catch (Exception ignored) { }
        }
        return Integer.MAX_VALUE;
    }

    private String imageUrl(String image) {
        if (image == null) return "";
        String value = image.trim();
        if (!isUseful(value)) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        return IMAGE_BASE + value;
    }

    private String firstPlayable(String... urls) {
        if (urls == null) return "";
        for (String url : urls) if (isPlayable(url)) return url;
        return "";
    }

    private boolean isPlayable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private boolean isOngoing(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.US);
        return !v.isEmpty() && !v.contains("complete") && !v.contains("finished") && !v.equals("selesai");
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

    private void showLoading(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "Your Videos Channel");
        h.put("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)");
        h.put("Accept", "application/vnd.yourapi.v1.full+json");
        return h;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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
        final int videoId;
        final int categoryId;
        final String episodeTitle;
        final String categoryName;
        final String imageUrl;

        EpisodePlaybackData(int videoId, int categoryId, String episodeTitle, String categoryName, String imageUrl) {
            this.videoId = videoId;
            this.categoryId = categoryId;
            this.episodeTitle = episodeTitle;
            this.categoryName = categoryName;
            this.imageUrl = imageUrl;
        }

        static EpisodePlaybackData fromJson(JSONObject json, int fallbackVideoId, AnimePost currentAnimePost, String fallbackImageUrl) {
            int resolvedVideoId = json.optInt("vid", fallbackVideoId);
            int resolvedCategoryId = json.optInt("cat_id", currentAnimePost == null ? -1 : currentAnimePost.categoryId);
            String episodeTitle = json.optString("video_title", "Episode");
            String categoryName = json.optString("category_name", currentAnimePost == null ? "" : currentAnimePost.categoryName);
            String image = json.optString("category_image", "");
            if (image == null || image.trim().isEmpty() || "null".equalsIgnoreCase(image.trim())) image = json.optString("video_thumbnail", fallbackImageUrl);
            if (image != null && !image.trim().isEmpty() && !image.startsWith("http")) image = IMAGE_BASE + image;
            return new EpisodePlaybackData(resolvedVideoId, resolvedCategoryId, episodeTitle, categoryName, image == null ? "" : image);
        }
    }
}
