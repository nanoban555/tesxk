package miku.moe.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoPlayerActivity extends AppCompatActivity {
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_VIDEO_TITLE = "video_title";
    public static final String EXTRA_IMAGE_URL = "image_url";
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_CATEGORY_NAME = "category_name";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_QUALITY = "quality";

    private static final String TAG = "VideoPlayerActivity";
    private static final String CATEGORY_URL = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_category_posts_secure/";
    private static final String DESCRIPTION_URL = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_post_description/";
    private static final String VIDEO_AUTH_USERNAME = "animexnonton";
    private static final String VIDEO_AUTH_PASSWORD = "jo3Z0XhdWpmjwaf6YUeLUBM5ltXNuUv8B";

    private PlayerView playerView;
    private AnimePlayerChrome playerChrome;
    private ExoPlayer player;
    private RequestQueue requestQueue;
    private DefaultHttpDataSource.Factory dataSourceFactory;
    private long playbackPosition = 0L;
    private boolean playWhenReady = true;
    private String videoUrl;
    private String videoTitle = "";
    private String imageUrl = "";
    private int channelId = -1;
    private int categoryId = -1;
    private String categoryName = "";
    private String selectedQuality = PlaybackQualityManager.QUALITY_HD;
    private boolean playlistLoaded = false;
    private boolean autoLandscapeActive = false;
    private EpisodeMedia currentEpisodeMedia;
    private long currentEpisodeDuration = 0L;

    private static class EpisodeMedia {
        final int channelId;
        final int categoryId;
        final String categoryName;
        final String title;
        final String imageUrl;
        final String videoUrl;
        final int episodeNumber;
        final int sourceIndex;

        EpisodeMedia(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl) {
            this(channelId, categoryId, categoryName, title, imageUrl, videoUrl, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        EpisodeMedia(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl, int episodeNumber, int sourceIndex) {
            this.channelId = channelId;
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.title = title;
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
            this.episodeNumber = episodeNumber;
            this.sourceIndex = sourceIndex;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        playerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyVideoScale(false);
        });
        playerChrome = new AnimePlayerChrome(findViewById(R.id.player_shell), this::finish, this::hideSystemBars);
        requestQueue = Volley.newRequestQueue(getApplicationContext());
        videoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        videoTitle = getIntent().getStringExtra(EXTRA_VIDEO_TITLE);
        imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        channelId = getIntent().getIntExtra(EXTRA_CHANNEL_ID, -1);
        categoryId = getIntent().getIntExtra(EXTRA_CATEGORY_ID, -1);
        categoryName = getIntent().getStringExtra(EXTRA_CATEGORY_NAME);
        playbackPosition = getIntent().getLongExtra(EXTRA_START_POSITION, 0L);
        selectedQuality = getIntent().getStringExtra(EXTRA_QUALITY);
        if (selectedQuality == null || selectedQuality.trim().isEmpty()) selectedQuality = PlaybackQualityManager.getQuality(this);
        if (videoTitle == null) videoTitle = "";
        if (playerChrome != null) playerChrome.setTitle(videoTitle);
        if (imageUrl == null) imageUrl = "";
        if (categoryName == null) categoryName = "";

        if (openExternalPlayerIfNeeded()) return;
        applyAutoLandscapeIfNeeded();

        if (savedInstanceState != null) {
            playbackPosition = savedInstanceState.getLong("player_position", 0L);
            playWhenReady = savedInstanceState.getBoolean("play_when_ready", true);
            String restoredUrl = savedInstanceState.getString("video_url");
            if (restoredUrl != null && !restoredUrl.isEmpty()) videoUrl = restoredUrl;
            videoTitle = savedInstanceState.getString("video_title", videoTitle);
            imageUrl = savedInstanceState.getString("image_url", imageUrl);
            channelId = savedInstanceState.getInt("channel_id", channelId);
            categoryId = savedInstanceState.getInt("category_id", categoryId);
            categoryName = savedInstanceState.getString("category_name", categoryName);
            selectedQuality = savedInstanceState.getString("selected_quality", selectedQuality);
        }

        hideSystemBars();
        initializePlayer();
        loadEpisodePlaylist();
    }

    private void applyAutoLandscapeIfNeeded() {
        autoLandscapeActive = PlaybackQualityManager.isAutoLandscape(this);
        if (autoLandscapeActive) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void resetAutoLandscapeIfNeeded() {
        if (autoLandscapeActive) {
            autoLandscapeActive = false;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private boolean openExternalPlayerIfNeeded() {
        String player = PlaybackQualityManager.getPlayer(this);
        if (PlaybackQualityManager.PLAYER_INTERNAL.equals(player)) return false;
        if (!isAvailable(videoUrl)) return false;
        try {
            Intent intent = createExternalPlayerIntent(buildAuthenticatedVideoUri(videoUrl));
            String packageName = getPlayerPackage(player);
            if (packageName != null) intent.setPackage(packageName);
            if (PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) startActivity(Intent.createChooser(intent, "Pilih player anime"));
            else startActivity(intent);
            finish();
            return true;
        } catch (ActivityNotFoundException e) {
            if (!PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) {
                try {
                    Intent fallback = createExternalPlayerIntent(buildAuthenticatedVideoUri(videoUrl));
                    startActivity(Intent.createChooser(fallback, "Pilih player anime"));
                    finish();
                    return true;
                } catch (Exception ignored) {}
            }
            Toast.makeText(this, PlaybackQualityManager.getPlayerLabel(player) + " tidak ditemukan", Toast.LENGTH_SHORT).show();
            PlaybackQualityManager.setPlayer(this, PlaybackQualityManager.PLAYER_INTERNAL);
            return false;
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka external player", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private Intent createExternalPlayerIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/*");
        intent.putExtra("title", videoTitle);
        intent.putExtra(Intent.EXTRA_TITLE, videoTitle);
        String basicAuth = buildBasicAuthHeader();
        HashMap<String, String> headerMap = new HashMap<>();
        headerMap.put("Authorization", basicAuth);
        Bundle headerBundle = new Bundle();
        headerBundle.putString("Authorization", basicAuth);
        intent.putExtra("headers", headerMap);
        intent.putExtra("android.media.intent.extra.HTTP_HEADERS", headerBundle);
        intent.putExtra("org.videolan.vlc.HTTP_HEADERS", headerBundle);
        intent.putExtra("org.videolan.vlc.headers", headerMap);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private Uri buildAuthenticatedVideoUri(String url) {
        Uri uri = Uri.parse(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return uri;
        if (uri.getUserInfo() != null && !uri.getUserInfo().trim().isEmpty()) return uri;
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) return uri;
        String authority = Uri.encode(VIDEO_AUTH_USERNAME) + ":" + Uri.encode(VIDEO_AUTH_PASSWORD) + "@" + host;
        if (uri.getPort() != -1) authority += ":" + uri.getPort();
        return uri.buildUpon().encodedAuthority(authority).build();
    }

    private String buildBasicAuthHeader() {
        String credential = VIDEO_AUTH_USERNAME + ":" + VIDEO_AUTH_PASSWORD;
        return "Basic " + Base64.encodeToString(credential.getBytes(), Base64.NO_WRAP);
    }

    private String getPlayerPackage(String player) {
        if (PlaybackQualityManager.PLAYER_VLC.equals(player)) return "org.videolan.vlc";
        if (PlaybackQualityManager.PLAYER_MPV.equals(player)) return "is.xyz.mpv";
        return null;
    }

    private void applyVideoScale(boolean showToast) {
        String scale = AnimeSettingsManager.getVideoScale(this);
        VideoScaleHelper.apply(playerView, player, scale);
        String label = AnimeSettingsManager.getVideoScaleLabel(scale);
        if (playerChrome != null) playerChrome.setScaleLabel(label);
        if (showToast) Toast.makeText(this, "Skala video: " + label, Toast.LENGTH_SHORT).show();
    }

    private void cycleVideoScale() {
        String next = AnimeSettingsManager.nextVideoScale(AnimeSettingsManager.getVideoScale(this));
        AnimeSettingsManager.setVideoScale(this, next);
        applyVideoScale(true);
    }

    private void initializePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        playerView.setUseController(false);
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                applyVideoScale(false);
            }
        });
        applyVideoScale(false);
        if (playerChrome != null) {
            playerChrome.attachPlayer(player);
            playerChrome.setTitle(videoTitle);
            playerChrome.setQualityLabel(PlaybackQualityManager.getQualityLabel(selectedQuality));
            playerChrome.setActionListener(new AnimePlayerChrome.ActionListener() {
                @Override
                public void onQualityClick() {
                    showQualityDialog();
                }

                @Override
                public void onSpeedClick() {
                    showSpeedDialog();
                }

                @Override
                public void onScaleClick() {
                    cycleVideoScale();
                }
            });
        }

        dataSourceFactory = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(Collections.singletonMap("Authorization", buildBasicAuthHeader()));

        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            finish();
            return;
        }

        EpisodeMedia current = new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, videoUrl, extractEpisodeNumber(videoTitle), 0);
        currentEpisodeMedia = current;
        player.setMediaSource(createMediaSource(current));
        player.setPlayWhenReady(playWhenReady);
        player.seekTo(playbackPosition);
        player.prepare();
        player.addListener(new Player.Listener() {
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    EpisodeMedia oldEpisode = currentEpisodeMedia;
                    try {
                        MediaItem oldItem = player.getMediaItemAt(oldPosition.mediaItemIndex);
                        if (oldItem.localConfiguration != null && oldItem.localConfiguration.tag instanceof EpisodeMedia) {
                            oldEpisode = (EpisodeMedia) oldItem.localConfiguration.tag;
                        }
                    } catch (Exception ignored) { }
                    long oldDuration = Math.max(0L, currentEpisodeDuration);
                    long oldWatched = Math.max(0L, oldPosition.positionMs);
                    if (oldDuration > 0 && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && oldWatched < oldDuration - 5000L) oldWatched = oldDuration;
                    saveHistory(oldEpisode, oldWatched, oldDuration);
                }
            }

            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                if (mediaItem != null && mediaItem.localConfiguration != null && mediaItem.localConfiguration.tag instanceof EpisodeMedia) {
                    EpisodeMedia episode = (EpisodeMedia) mediaItem.localConfiguration.tag;
                    currentEpisodeMedia = episode;
                    currentEpisodeDuration = 0L;
                    channelId = episode.channelId;
                    categoryId = episode.categoryId;
                    categoryName = episode.categoryName;
                    videoTitle = episode.title;
                    imageUrl = episode.imageUrl;
                    videoUrl = episode.videoUrl;
                    playbackPosition = 0L;
                    setTitle(videoTitle);
                    if (playerChrome != null) playerChrome.setTitle(videoTitle);
                    showEpisodeChangedMessage(episode);
                    updateNavigationButtons();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (player != null) currentEpisodeDuration = Math.max(currentEpisodeDuration, Math.max(0L, player.getDuration()));
                if (playbackState == Player.STATE_ENDED) saveHistory();
            }
        });
    }

    private void showSpeedDialog() {
        if (player == null || isFinishing()) return;
        float[] speeds = new float[]{0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        String[] labels = new String[speeds.length];
        float current = player.getPlaybackParameters().speed;
        int checked = 2;
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = String.format(java.util.Locale.US, "%.2fx", speeds[i]);
            if (Math.abs(current - speeds[i]) < 0.01f) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Kecepatan video")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    player.setPlaybackParameters(new PlaybackParameters(speeds[which]));
                    dialog.dismiss();
                    hideSystemBars();
                })
                .show();
    }

    private void showQualityDialog() {
        if (player == null || isFinishing()) return;
        String[] qualities = new String[]{PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_FHD};
        String[] labels = new String[qualities.length];
        int checked = 1;
        for (int i = 0; i < qualities.length; i++) {
            labels[i] = PlaybackQualityManager.getQualityLabel(qualities[i]);
            if (qualities[i].equals(selectedQuality)) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Resolusi video")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    changeQuality(qualities[which]);
                    hideSystemBars();
                })
                .show();
    }

    private void changeQuality(String quality) {
        if (quality == null || quality.equals(selectedQuality)) return;
        selectedQuality = quality;
        PlaybackQualityManager.setQuality(this, selectedQuality);
        if (playerChrome != null) playerChrome.setQualityLabel(PlaybackQualityManager.getQualityLabel(selectedQuality));
        fetchAndReplaceCurrentQuality();
    }

    private void fetchAndReplaceCurrentQuality() {
        if (requestQueue == null || channelId <= 0) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        StringRequest request = new StringRequest(Request.Method.POST, DESCRIPTION_URL, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                String resolvedUrl = resolveVideoUrl(json);
                if (!isAvailable(resolvedUrl)) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                String rawTitle = json.optString("channel_name", videoTitle);
                String title = rawTitle + " • " + PlaybackQualityManager.getQualityLabel(selectedQuality);
                EpisodeMedia episode = new EpisodeMedia(json.optInt("channel_id", channelId), json.optInt("category_id", categoryId), json.optString("category_name", categoryName), title, json.optString("img_url", imageUrl), resolvedUrl, extractEpisodeNumber(rawTitle), 0);
                replaceCurrentMedia(episode);
                playlistLoaded = false;
                loadEpisodePlaylist();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show();
            }
        }, error -> Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show()) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("channel_id", String.valueOf(channelId));
                p.put("isAPKvalid", "true");
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void replaceCurrentMedia(EpisodeMedia episode) {
        if (player == null || episode == null || !isAvailable(episode.videoUrl)) return;
        long pos = Math.max(0L, player.getCurrentPosition());
        boolean ready = player.getPlayWhenReady();
        currentEpisodeMedia = episode;
        channelId = episode.channelId;
        categoryId = episode.categoryId;
        categoryName = episode.categoryName;
        videoTitle = episode.title;
        imageUrl = episode.imageUrl;
        videoUrl = episode.videoUrl;
        if (playerChrome != null) {
            playerChrome.setTitle(videoTitle);
            playerChrome.setQualityLabel(PlaybackQualityManager.getQualityLabel(selectedQuality));
        }
        player.setMediaSource(createMediaSource(episode), pos);
        player.setPlayWhenReady(ready);
        player.prepare();
        Toast.makeText(this, PlaybackQualityManager.getQualityLabel(selectedQuality), Toast.LENGTH_SHORT).show();
    }

    private MediaSource createMediaSource(EpisodeMedia episode) {
        MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.parse(episode.videoUrl)).setTag(episode).build();
        return new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
    }

    private void loadEpisodePlaylist() {
        if (playlistLoaded || categoryId <= 0 || channelId <= 0 || requestQueue == null) return;
        playlistLoaded = true;
        StringRequest request = new StringRequest(Request.Method.POST, CATEGORY_URL, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) return;
                JSONArray posts = json.optJSONArray("posts");
                if (posts == null || posts.length() <= 1) return;
                ArrayList<Integer> ids = new ArrayList<>();
                for (int i = 0; i < posts.length(); i++) {
                    int id = posts.getJSONObject(i).optInt("channel_id", -1);
                    if (id > 0 && !ids.contains(id)) ids.add(id);
                }
                fetchEpisodeMediaList(ids, 0, new ArrayList<>());
            } catch (Exception e) {
                Log.e(TAG, "Playlist parse error", e);
            }
        }, error -> Log.e(TAG, "Playlist network error", error)) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("id", String.valueOf(categoryId));
                p.put("isAPKvalid", "true");
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void fetchEpisodeMediaList(List<Integer> ids, int index, ArrayList<EpisodeMedia> result) {
        if (ids == null || ids.isEmpty()) return;
        if (index >= ids.size()) { applyPlaylist(result); return; }
        int nextIndex = Math.min(ids.size(), index + 4);
        AtomicInteger remaining = new AtomicInteger(nextIndex - index);
        for (int requestIndex = index; requestIndex < nextIndex; requestIndex++) {
            final int episodeIndex = requestIndex;
            final int id = ids.get(episodeIndex);
            Runnable complete = () -> {
                if (remaining.decrementAndGet() == 0) {
                    if (nextIndex >= ids.size()) applyPlaylist(result);
                    else fetchEpisodeMediaList(ids, nextIndex, result);
                }
            };
            StringRequest request = new StringRequest(Request.Method.POST, DESCRIPTION_URL, response -> {
                try {
                    JSONObject json = new JSONObject(response);
                    if ("ok".equalsIgnoreCase(json.optString("status"))) {
                        int resolvedCategoryId = json.optInt("category_id", categoryId);
                        if (resolvedCategoryId == categoryId) {
                            String resolvedUrl = resolveVideoUrl(json);
                            if (isAvailable(resolvedUrl)) {
                                int resolvedChannelId = json.optInt("channel_id", id);
                                String resolvedCategoryName = json.optString("category_name", categoryName);
                                String rawTitle = json.optString("channel_name", "Episode " + (episodeIndex + 1));
                                int episodeNumber = extractEpisodeNumber(rawTitle);
                                String title = rawTitle + " • " + PlaybackQualityManager.getQualityLabel(selectedQuality);
                                String img = json.optString("img_url", imageUrl);
                                synchronized (result) {
                                    result.add(new EpisodeMedia(resolvedChannelId, resolvedCategoryId, resolvedCategoryName, title, img, resolvedUrl, episodeNumber, episodeIndex));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Episode media parse error", e);
                } finally {
                    complete.run();
                }
            }, error -> {
                Log.e(TAG, "Episode media network error", error);
                complete.run();
            }) {
                @Override protected Map<String, String> getParams() {
                    Map<String, String> p = new HashMap<>();
                    p.put("channel_id", String.valueOf(id));
                    p.put("isAPKvalid", "true");
                    return p;
                }
                @Override public Map<String, String> getHeaders() { return headers(); }
            };
            request.setShouldCache(false);
            request.setTag(TAG);
            requestQueue.add(request);
        }
    }

    private void applyPlaylist(ArrayList<EpisodeMedia> episodes) {
        if (player == null || episodes == null || episodes.size() <= 1) return;

        LinkedHashMap<Integer, EpisodeMedia> unique = new LinkedHashMap<>();
        for (EpisodeMedia episode : episodes) {
            if (episode != null && episode.channelId > 0 && !unique.containsKey(episode.channelId)) unique.put(episode.channelId, episode);
        }

        ArrayList<EpisodeMedia> ordered = new ArrayList<>(unique.values());
        if (ordered.size() <= 1) return;

        Collections.sort(ordered, (a, b) -> {
            int ea = a.episodeNumber;
            int eb = b.episodeNumber;
            if (ea != eb) return Integer.compare(ea, eb);
            if (a.sourceIndex != b.sourceIndex) return Integer.compare(a.sourceIndex, b.sourceIndex);
            return Integer.compare(a.channelId, b.channelId);
        });

        int currentIndex = -1;
        ArrayList<MediaSource> sources = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            EpisodeMedia episode = ordered.get(i);
            if (episode.channelId == channelId) currentIndex = i;
            sources.add(createMediaSource(episode));
        }
        if (currentIndex < 0) return;

        MediaItem playingItem = player.getCurrentMediaItem();
        EpisodeMedia playing = playingItem != null && playingItem.localConfiguration != null && playingItem.localConfiguration.tag instanceof EpisodeMedia ? (EpisodeMedia) playingItem.localConfiguration.tag : null;
        EpisodeMedia target = ordered.get(currentIndex);
        if (playing != null && playing.channelId == target.channelId && playing.videoUrl != null && playing.videoUrl.equals(target.videoUrl)) {
            int playerIndex = player.getCurrentMediaItemIndex();
            ArrayList<MediaSource> before = new ArrayList<>();
            for (int i = 0; i < currentIndex; i++) before.add(createMediaSource(ordered.get(i)));
            ArrayList<MediaSource> after = new ArrayList<>();
            for (int i = currentIndex + 1; i < ordered.size(); i++) after.add(createMediaSource(ordered.get(i)));
            if (!before.isEmpty()) player.addMediaSources(playerIndex, before);
            if (!after.isEmpty()) player.addMediaSources(playerIndex + before.size() + 1, after);
            updateNavigationButtons();
            return;
        }

        long pos = Math.max(0L, player.getCurrentPosition());
        boolean ready = player.getPlayWhenReady();
        player.stop();
        player.clearMediaItems();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        player.setMediaSources(sources, currentIndex, pos);
        player.setPlayWhenReady(ready);
        player.prepare();
        updateNavigationButtons();

    }

    private void showEpisodeChangedMessage(EpisodeMedia episode) {
        if (episode == null) return;
        String label = episode.title == null ? "" : episode.title.trim();
        int cut = label.indexOf(" • ");
        if (cut >= 0) label = label.substring(0, cut).trim();
        if (label.isEmpty()) {
            int episodeNumber = episode.episodeNumber;
            label = episodeNumber != Integer.MAX_VALUE ? "Episode " + episodeNumber : "episode ini";
        }
        Toast.makeText(this, "Kamu menonton " + label, Toast.LENGTH_SHORT).show();
    }

    private void updateNavigationButtons() {
        if (player == null) return;
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        if (playerChrome != null) playerChrome.updateNavigation(player.hasPreviousMediaItem(), player.hasNextMediaItem());
    }

    private int extractEpisodeNumber(String title) {
        if (title == null) return Integer.MAX_VALUE;
        String text = title.toLowerCase();
        Matcher matcher = Pattern.compile("(?:episode|eps|ep|e)\\s*[-:]*\\s*(\\d+)").matcher(text);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); } catch (Exception ignored) {}
        }
        matcher = Pattern.compile("\\b(\\d{1,4})\\b").matcher(text);
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n != 360 && n != 720 && n != 1080) return n;
            } catch (Exception ignored) {}
        }
        return Integer.MAX_VALUE;
    }

    private String resolveVideoUrl(JSONObject json) {
        String secretKey = json.optString("secretKey", "");
        String sd = firstAvailable(
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url"), secretKey),
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_ori"), secretKey)
        );
        String hd = firstAvailable(
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd"), secretKey),
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd_ori"), secretKey)
        );
        String fhd = firstAvailable(
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd"), secretKey),
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd_ori"), secretKey)
        );
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) return firstAvailable(sd, hd, fhd);
        if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) return firstAvailable(fhd, hd, sd);
        return firstAvailable(hd, sd, fhd);
    }

    private String firstAvailable(String... urls) {
        if (urls == null) return "";
        for (String url : urls) if (isAvailable(url)) return url;
        return "";
    }

    private boolean isAvailable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "AnimeXNonton 26.9.5/18");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("User-Agent", "okhttp/5.5.0");
        return h;
    }

    private void applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) return;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
    }

    private void hideSystemBars() {
        applyDisplayCutoutMode();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars(); }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            playbackPosition = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
            saveHistory();
            player.pause();
        }
    }

    private void saveHistory() {
        if (player == null) return;
        EpisodeMedia episode = currentEpisodeMedia;
        if (episode == null) episode = new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, videoUrl, extractEpisodeNumber(videoTitle), 0);
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = Math.max(0L, player.getDuration());
        saveHistory(episode, position, duration);
    }

    private void saveHistory(EpisodeMedia episode, long position, long duration) {
        if (episode == null || episode.videoUrl == null || episode.videoUrl.trim().isEmpty()) return;
        long safeDuration = Math.max(0L, duration);
        long safePosition = Math.max(0L, position);
        long completionThreshold = safeDuration <= 5000L ? Math.max(1L, safeDuration * 9L / 10L) : safeDuration - 5000L;
        if (safeDuration > 0L && safePosition > 0L && safePosition >= completionThreshold) safePosition = safeDuration;
        if (safePosition <= 0L && safeDuration <= 0L) return;
        HistoryManager.save(this, new HistoryItem(episode.channelId, episode.categoryId, episode.categoryName, episode.title == null || episode.title.trim().isEmpty() ? "Anime" : episode.title, episode.imageUrl, episode.videoUrl, safePosition, safeDuration, System.currentTimeMillis()));
    }

    @Override protected void onResume() { super.onResume(); hideSystemBars(); if (player != null) player.setPlayWhenReady(playWhenReady); }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            outState.putLong("player_position", player.getCurrentPosition());
            outState.putBoolean("play_when_ready", player.getPlayWhenReady());
        }
        outState.putString("video_url", videoUrl);
        outState.putString("video_title", videoTitle);
        outState.putString("image_url", imageUrl);
        outState.putInt("channel_id", channelId);
        outState.putInt("category_id", categoryId);
        outState.putString("category_name", categoryName);
        outState.putString("selected_quality", selectedQuality);
    }

    @Override
    public void finish() {
        resetAutoLandscapeIfNeeded();
        super.finish();
    }

    @Override
    protected void onStop() {
        if (isFinishing()) resetAutoLandscapeIfNeeded();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        resetAutoLandscapeIfNeeded();
        super.onDestroy();
        if (requestQueue != null) requestQueue.cancelAll(TAG);
        if (playerChrome != null) {
            playerChrome.release();
            playerChrome = null;
        }
        if (player != null) {
            saveHistory();
            player.release();
            player = null;
        }
        playerView = null;
    }
}
