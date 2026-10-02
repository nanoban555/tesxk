package miku.moe.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import androidx.media3.exoplayer.source.hls.HlsMediaSource;
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
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnichinPlayerActivity extends AppCompatActivity {
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_VIDEO_TITLE = "video_title";
    public static final String EXTRA_IMAGE_URL = "image_url";
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_CATEGORY_NAME = "category_name";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_DISABLE_PLAYLIST = "disable_playlist";
    public static final String EXTRA_HISTORY_SOURCE_ID = "history_source_id";
    public static final String EXTRA_ANIMELOVERZ_SLUG = "animeloverz_slug";
    public static final String EXTRA_HISTORY_SLUG = "history_slug";
    public static final String EXTRA_ANICHIN_EPISODE_URL = "anichin_episode_url";

    private static final String TAG = "AnichinPlayer";
    private static final String API_BASE = "https://pencarinafkah.xyz/vA6//api/";
    private static final String IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/";
    private static final String ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5";

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
    private boolean disablePlaylist = false;
    private String historySourceId = AnimeSettingsManager.SOURCE_ANICHIN;
    private String animeLoverzSlug = "";
    private String historySlug = "";
    private String anichinEpisodeUrl = "";
    private boolean refreshingQuality = false;
    private ExecutorService anichinPlaylistExecutor;
    private final java.util.Map<Integer, String> anichinEpisodePageUrls = new java.util.HashMap<>();

    private static class EpisodeMedia {
        final int channelId;
        final int categoryId;
        final String categoryName;
        final String title;
        final String imageUrl;
        final String videoUrl;
        final int episodeNumber;
        final int sourceIndex;

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

    private static class AnimeLoverzEpisodeMeta {
        final int id;
        final String url;
        final String episode;
        final String label;
        final int index;

        AnimeLoverzEpisodeMeta(int id, String url, String episode, String label, int index) {
            this.id = id;
            this.url = url == null ? "" : url;
            this.episode = episode == null ? "" : episode;
            this.label = label == null || label.trim().isEmpty() ? "Episode" : label;
            this.index = index;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_animeku_video_player);
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
        disablePlaylist = getIntent().getBooleanExtra(EXTRA_DISABLE_PLAYLIST, false);
        historySourceId = getIntent().getStringExtra(EXTRA_HISTORY_SOURCE_ID);
        animeLoverzSlug = getIntent().getStringExtra(EXTRA_ANIMELOVERZ_SLUG);
        historySlug = getIntent().getStringExtra(EXTRA_HISTORY_SLUG);
        anichinEpisodeUrl = getIntent().getStringExtra(EXTRA_ANICHIN_EPISODE_URL);
        if (selectedQuality == null || selectedQuality.trim().isEmpty()) selectedQuality = PlaybackQualityManager.getQuality(this);
        if (historySourceId == null || historySourceId.trim().isEmpty() || !AnimeSettingsManager.isValidSource(historySourceId)) historySourceId = AnimeSettingsManager.SOURCE_ANICHIN;
        if (videoTitle == null) videoTitle = "";
        if (playerChrome != null) playerChrome.setTitle(videoTitle);
        if (imageUrl == null) imageUrl = "";
        if (categoryName == null) categoryName = "";
        if (animeLoverzSlug == null) animeLoverzSlug = "";
        if (historySlug == null) historySlug = "";
        if (anichinEpisodeUrl == null) anichinEpisodeUrl = "";
        if (historySlug.trim().isEmpty() && AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) historySlug = animeLoverzSlug;

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
            disablePlaylist = savedInstanceState.getBoolean("disable_playlist", disablePlaylist);
            historySourceId = savedInstanceState.getString("history_source_id", historySourceId);
            animeLoverzSlug = savedInstanceState.getString("animeloverz_slug", animeLoverzSlug);
            historySlug = savedInstanceState.getString("history_slug", historySlug);
            anichinEpisodeUrl = savedInstanceState.getString("anichin_episode_url", anichinEpisodeUrl);
            if (historySourceId == null || historySourceId.trim().isEmpty() || !AnimeSettingsManager.isValidSource(historySourceId)) historySourceId = AnimeSettingsManager.SOURCE_ANICHIN;
            if (historySlug == null) historySlug = "";
            if (anichinEpisodeUrl == null) anichinEpisodeUrl = "";
            if (historySlug.trim().isEmpty() && AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) historySlug = animeLoverzSlug;
        }

        hideSystemBars();
        initializePlayer();
        if (!disablePlaylist) {
            if (AnimeSettingsManager.SOURCE_ANICHIN.equals(historySourceId)) loadAnichinPlaylist();
            else if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) loadAnimeLoverzPlaylist(); else loadEpisodePlaylist();
        }
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
            Intent intent = createExternalPlayerIntent(Uri.parse(videoUrl));
            String packageName = getPlayerPackage(player);
            if (packageName != null) intent.setPackage(packageName);
            if (PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) startActivity(Intent.createChooser(intent, "Pilih player anime"));
            else startActivity(intent);
            finish();
            return true;
        } catch (ActivityNotFoundException e) {
            if (!PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) {
                try {
                    Intent fallback = createExternalPlayerIntent(Uri.parse(videoUrl));
                    startActivity(Intent.createChooser(fallback, "Pilih player anime"));
                    finish();
                    return true;
                } catch (Exception ignored) { }
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
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

        dataSourceFactory = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(videoHeaders());

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
                    if (AnimeSettingsManager.SOURCE_ANICHIN.equals(historySourceId)) {
                        String page = anichinEpisodePageUrls.get(episode.channelId);
                        if (page != null && !page.trim().isEmpty()) anichinEpisodeUrl = page;
                    }
                    showEpisodeChangedMessage(episode);
                    updateNavigationButtons();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (player != null) currentEpisodeDuration = Math.max(0L, player.getDuration());
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
        if (AnimeSettingsManager.SOURCE_ANICHIN.equals(historySourceId)) refreshAnichinQuality();
        else if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) refreshAnimeLoverzQuality(); else fetchAndReplaceCurrentQuality();
    }

    // ------------------------------------------------------------ Anichin

    /** Pilih URL terbaik dari hasil Anichin.playback sesuai resolusi yang dipilih user. */
    private String pickAnichinUrl(ArrayList<Anichin.QualityResult> options) {
        if (options == null || options.isEmpty()) return null;
        String[] preference;
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) preference = new String[]{PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_FHD};
        else if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) preference = new String[]{PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_SD};
        else preference = new String[]{PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.QUALITY_SD};
        for (String wanted : preference) {
            for (Anichin.QualityResult option : options) {
                if (wanted.equals(option.quality) && isAvailable(option.url)) return option.url.trim();
            }
        }
        for (Anichin.QualityResult option : options) if (isAvailable(option.url)) return option.url.trim();
        return null;
    }

    private String stripQualitySuffix(String title) {
        if (title == null) return "";
        int cut = title.indexOf(" • ");
        return (cut >= 0 ? title.substring(0, cut) : title).trim();
    }

    /** Bangun playlist episode Anichin: resolve tiap episode di background (tanpa webview). */
    private void loadAnichinPlaylist() {
        if (playlistLoaded || isFinishing()) return;
        if (historySlug == null || historySlug.trim().isEmpty()) return;
        playlistLoaded = true;
        if (anichinPlaylistExecutor == null || anichinPlaylistExecutor.isShutdown()) {
            anichinPlaylistExecutor = Executors.newFixedThreadPool(4);
        }
        final ExecutorService executor = anichinPlaylistExecutor;
        final String slug = historySlug.trim();
        executor.execute(() -> {
            try {
                Anichin.DetailResult detail = Anichin.detail(slug);
                ArrayList<Anichin.EpisodeResult> episodes = detail == null ? null : detail.episodes;
                if (episodes == null || episodes.size() <= 1) {
                    runOnUiThread(() -> refreshingQuality = false);
                    return;
                }
                final ArrayList<EpisodeMedia> result = new ArrayList<>();
                final java.util.Map<Integer, String> pageUrls = new java.util.HashMap<>();
                CountDownLatch latch = new CountDownLatch(episodes.size());
                for (Anichin.EpisodeResult ep : episodes) {
                    executor.execute(() -> {
                        try {
                            String best = pickAnichinUrl(Anichin.playback(ep.episodeId));
                            if (best != null) {
                                synchronized (result) {
                                    result.add(new EpisodeMedia(ep.id, -1, categoryName, ep.title + " • " + PlaybackQualityManager.getQualityLabel(selectedQuality), imageUrl, best, ep.episodeNumber, ep.episodeNumber));
                                    pageUrls.put(ep.id, ep.episodeId);
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                try {
                    latch.await(150, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                final ArrayList<EpisodeMedia> ordered = new ArrayList<>(result);
                runOnUiThread(() -> {
                    anichinEpisodePageUrls.putAll(pageUrls);
                    applyPlaylist(ordered);
                });
            } catch (Exception e) {
                Log.e(TAG, "Anichin playlist error", e);
                runOnUiThread(() -> refreshingQuality = false);
            }
        });
    }

    /** Ganti resolusi: resolve ulang URL episode yang sedang diputar via Anichin.playback. */
    private void refreshAnichinQuality() {
        String pageUrl = anichinEpisodeUrl;
        if ((pageUrl == null || pageUrl.trim().isEmpty()) && currentEpisodeMedia != null) {
            pageUrl = anichinEpisodePageUrls.get(currentEpisodeMedia.channelId);
        }
        if (pageUrl == null || pageUrl.trim().isEmpty()) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        final String target = pageUrl.trim();
        refreshingQuality = true;
        playlistLoaded = false;
        Toast.makeText(this, "Mengubah ke " + PlaybackQualityManager.getQualityLabel(selectedQuality), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String best = pickAnichinUrl(Anichin.playback(target));
                runOnUiThread(() -> {
                    if (!isAvailable(best)) {
                        Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                        refreshingQuality = false;
                        return;
                    }
                    EpisodeMedia current = currentEpisodeMedia;
                    int epId = current != null ? current.channelId : channelId;
                    int epNum = current != null ? current.episodeNumber : extractEpisodeNumber(videoTitle);
                    String baseTitle = current != null ? stripQualitySuffix(current.title) : stripQualitySuffix(videoTitle);
                    if (baseTitle.isEmpty()) baseTitle = "Episode";
                    EpisodeMedia replacement = new EpisodeMedia(epId, -1, categoryName, baseTitle + " • " + PlaybackQualityManager.getQualityLabel(selectedQuality), imageUrl, best, epNum, 0);
                    replaceCurrentMedia(replacement);
                    loadAnichinPlaylist();
                });
            } catch (Exception e) {
                Log.e(TAG, "Anichin quality refresh error", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show();
                    refreshingQuality = false;
                });
            }
        }).start();
    }

    private Map<String, String> videoHeadersFor(String url) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 12; SM-A165F Build/UP1A.231005.007)");
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("dailymotion.com")) h.put("Referer", "https://www.dailymotion.com/");
        else if (lower.contains("ok.ru")) h.put("Referer", "https://ok.ru/");
        return h;
    }

    private void refreshAnimeLoverzQuality() {
        if (animeLoverzSlug == null || animeLoverzSlug.trim().isEmpty()) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshingQuality = true;
        playlistLoaded = false;
        Toast.makeText(this, "Mengubah ke " + PlaybackQualityManager.getQualityLabel(selectedQuality), Toast.LENGTH_SHORT).show();
        loadAnimeLoverzPlaylist();
    }

    private void fetchAndReplaceCurrentQuality() {
        if (requestQueue == null || channelId <= 0 || categoryId <= 0) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = API_BASE + "get_post_detail_next_provious?id=" + channelId + "&cat_id=" + categoryId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONObject post = json.optJSONObject("post");
                if (post == null) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                EpisodeMedia episode = buildEpisodeFromJson(post, 0);
                if (episode == null || !isAvailable(episode.videoUrl)) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                replaceCurrentMedia(episode);
                playlistLoaded = false;
                loadEpisodePlaylist();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show();
            }
        }, error -> Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show()) {
            @Override public Map<String, String> getHeaders() { return apiHeaders(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private EpisodeMedia buildEpisodeFromJson(JSONObject json, int index) {
        if (json == null) return null;
        int resolvedChannelId = json.optInt("vid", channelId);
        int resolvedCategoryId = json.optInt("cat_id", categoryId);
        String resolvedUrl = resolveVideoUrl(json);
        if (!isAvailable(resolvedUrl)) return null;
        String rawTitle = json.optString("video_title", videoTitle);
        String label = qualityLabelForResolved(json, resolvedUrl);
        String title = rawTitle + " • " + label;
        String img = imageUrl(firstAvailableText(json.optString("category_image", ""), json.optString("video_thumbnail", ""), imageUrl));
        String name = json.optString("category_name", categoryName);
        return new EpisodeMedia(resolvedChannelId, resolvedCategoryId, name, title, img, resolvedUrl, extractEpisodeNumber(rawTitle), index);
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
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(videoHeadersFor(episode.videoUrl));
        String url = episode.videoUrl == null ? "" : episode.videoUrl.toLowerCase(java.util.Locale.ROOT);
        String noQuery = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (noQuery.endsWith(".m3u8")) {
            return new HlsMediaSource.Factory(factory).createMediaSource(mediaItem);
        }
        return new ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem);
    }

    private void loadAnimeLoverzPlaylist() {
        if (playlistLoaded || channelId <= 0 || requestQueue == null) return;
        if (animeLoverzSlug == null || animeLoverzSlug.trim().isEmpty()) return;
        playlistLoaded = true;
        ArrayList<String> variants = animeLoverzSlugVariants(animeLoverzSlug);
        loadAnimeLoverzPlaylistVariant(variants, 0);
    }

    private void loadAnimeLoverzPlaylistVariant(ArrayList<String> variants, int index) {
        if (variants == null || index >= variants.size()) return;
        String slug = variants.get(index);
        String url = ANIMELOVERZ_API_BASE + "/series.php?url=" + Uri.encode(slug);
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONObject item = firstAnimeLoverzDataObject(json);
                JSONArray chapters = item == null ? null : firstAnimeLoverzArray(item, "chapter", "episodes", "episode", "episode_list", "daftar_episode", "list_episode");
                ArrayList<AnimeLoverzEpisodeMeta> metas = new ArrayList<>();
                if (chapters != null) {
                    for (int i = 0; i < chapters.length(); i++) {
                        JSONObject chapter = chapters.optJSONObject(i);
                        if (chapter == null) continue;
                        String episodeUrl = firstAvailableText(chapter.optString("url", ""), chapter.optString("link", ""), chapter.optString("slug", ""), chapter.optString("permalink", ""));
                        if (episodeUrl.isEmpty()) continue;
                        int id = chapter.optInt("id", positiveId(episodeUrl));
                        String ch = firstAvailableText(chapter.optString("ch", ""), chapter.optString("episode", ""), chapter.optString("eps", ""), chapter.optString("title", ""), chapter.optString("name", ""));
                        String label = ch.isEmpty() ? "Episode" : ch.toLowerCase(java.util.Locale.US).contains("episode") ? ch : "Episode " + ch;
                        metas.add(new AnimeLoverzEpisodeMeta(id, episodeUrl, ch, label, i));
                    }
                }
                if (metas.isEmpty()) {
                    loadAnimeLoverzPlaylistVariant(variants, index + 1);
                    return;
                }
                animeLoverzSlug = slug;
                if (metas.size() <= 1) return;
                fetchAnimeLoverzPlaylistItems(metas);
            } catch (Exception e) {
                Log.e(TAG, "Animeloverz playlist parse error", e);
                loadAnimeLoverzPlaylistVariant(variants, index + 1);
            }
        }, error -> {
            Log.e(TAG, "Animeloverz playlist network error", error);
            loadAnimeLoverzPlaylistVariant(variants, index + 1);
        }) {
            @Override public Map<String, String> getHeaders() { return animeLoverzHeaders(); }
            @Override public String getBodyContentType() { return "text/plain; charset=utf-8"; }
            @Override public byte[] getBody() {
                String body = "{\"get\":\"top\",\"post_type\":\"1\",\"post_id\":\"" + escapeJson(slug) + "\",\"token\":\"\"}";
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void fetchAnimeLoverzPlaylistItems(ArrayList<AnimeLoverzEpisodeMeta> metas) {
        ArrayList<EpisodeMedia> result = new ArrayList<>();
        if (!refreshingQuality && currentEpisodeMedia != null && currentEpisodeMedia.channelId > 0 && isAvailable(currentEpisodeMedia.videoUrl)) result.add(currentEpisodeMedia);
        else if (!refreshingQuality && channelId > 0 && isAvailable(videoUrl)) result.add(new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, videoUrl, extractEpisodeNumber(videoTitle), 0));
        AtomicInteger remaining = new AtomicInteger(metas.size());
        for (AnimeLoverzEpisodeMeta meta : metas) {
            ArrayList<String[]> attempts = animeLoverzEpisodeRequestVariants(meta.url, animeLoverzSlug);
            fetchAnimeLoverzPlaylistItem(meta, attempts, 0, result, remaining);
        }
    }

    private void fetchAnimeLoverzPlaylistItem(AnimeLoverzEpisodeMeta meta, ArrayList<String[]> attempts, int index, ArrayList<EpisodeMedia> result, AtomicInteger remaining) {
        if (attempts == null || index >= attempts.size()) {
            if (remaining.decrementAndGet() == 0) applyPlaylist(result);
            return;
        }
        String episodeUrl = attempts.get(index)[0];
        String seriesSlug = attempts.get(index)[1];
        String url = ANIMELOVERZ_API_BASE + "/series/episode/data.php?url=" + Uri.encode(episodeUrl);
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONObject item = firstAnimeLoverzDataObject(json);
                String resolvedUrl = item == null ? "" : resolveAnimeLoverzVideoUrl(item.optJSONObject("streams"));
                if (isAvailable(resolvedUrl)) {
                    String label = meta.label + " • " + PlaybackQualityManager.getQualityLabel(selectedQuality);
                    result.add(new EpisodeMedia(meta.id, categoryId, categoryName, label, imageUrl, resolvedUrl, extractEpisodeNumber(meta.label), meta.index));
                    if (remaining.decrementAndGet() == 0) applyPlaylist(result);
                } else {
                    fetchAnimeLoverzPlaylistItem(meta, attempts, index + 1, result, remaining);
                }
            } catch (Exception e) {
                Log.e(TAG, "Animeloverz episode playlist parse error", e);
                fetchAnimeLoverzPlaylistItem(meta, attempts, index + 1, result, remaining);
            }
        }, error -> {
            Log.e(TAG, "Animeloverz episode playlist network error", error);
            fetchAnimeLoverzPlaylistItem(meta, attempts, index + 1, result, remaining);
        }) {
            @Override public Map<String, String> getHeaders() { return animeLoverzHeaders(); }
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

    private JSONObject firstAnimeLoverzDataObject(JSONObject json) {
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

    private JSONArray firstAnimeLoverzArray(JSONObject json, String... names) {
        if (json == null || names == null) return null;
        for (String name : names) {
            JSONArray array = json.optJSONArray(name);
            if (array != null) return array;
        }
        return null;
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
            addAnimeLoverzVariant(variants, slash);
            addAnimeLoverzVariant(variants, clean);
        } else {
            addAnimeLoverzVariant(variants, clean);
            addAnimeLoverzVariant(variants, slash);
        }
        return variants;
    }

    private ArrayList<String[]> animeLoverzEpisodeRequestVariants(String episode, String series) {
        ArrayList<String[]> attempts = new ArrayList<>();
        ArrayList<String> episodeVariants = animeLoverzSlugVariants(episode);
        ArrayList<String> seriesVariants = animeLoverzSlugVariants(series);
        for (String episodeVariant : episodeVariants) {
            for (String seriesVariant : seriesVariants) addAnimeLoverzEpisodeVariant(attempts, episodeVariant, seriesVariant);
        }
        return attempts;
    }

    private void addAnimeLoverzVariant(ArrayList<String> variants, String value) {
        if (variants == null || !hasText(value)) return;
        String clean = value.trim();
        if (!variants.contains(clean)) variants.add(clean);
    }

    private void addAnimeLoverzEpisodeVariant(ArrayList<String[]> attempts, String episode, String series) {
        if (attempts == null || !hasText(episode) || !hasText(series)) return;
        String cleanEpisode = episode.trim();
        String cleanSeries = series.trim();
        for (String[] attempt : attempts) if (attempt[0].equals(cleanEpisode) && attempt[1].equals(cleanSeries)) return;
        attempts.add(new String[]{cleanEpisode, cleanSeries});
    }

    private int positiveId(String value) {
        int id = value == null ? 1 : value.hashCode();
        return id == Integer.MIN_VALUE ? 1 : Math.abs(id);
    }

    private String resolveAnimeLoverzVideoUrl(JSONObject streams) {
        if (streams == null) return "";
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) return firstAnimeLoverzStream(streams, "480p", "360p", "720p", "1080p");
        if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) return firstAnimeLoverzStream(streams, "1080p", "720p", "480p", "360p");
        return firstAnimeLoverzStream(streams, "720p", "480p", "360p", "1080p");
    }

    private String firstAnimeLoverzStream(JSONObject streams, String... keys) {
        if (streams == null || keys == null) return "";
        for (String key : keys) {
            JSONArray array = streams.optJSONArray(key);
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                String link = item == null ? "" : item.optString("link", "");
                if (isAvailable(link)) return link.trim();
            }
        }
        return "";
    }

    private void loadEpisodePlaylist() {
        if (playlistLoaded || categoryId <= 0 || channelId <= 0 || requestQueue == null) return;
        playlistLoaded = true;
        String url = API_BASE + "get_post_detail_next_provious?id=" + channelId + "&cat_id=" + categoryId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) return;
                ArrayList<EpisodeMedia> episodes = new ArrayList<>();
                addEpisodeMedia(episodes, json.optJSONObject("post"), 0);
                JSONArray suggested = json.optJSONArray("suggested");
                if (suggested != null) for (int i = 0; i < suggested.length(); i++) addEpisodeMedia(episodes, suggested.optJSONObject(i), i + 1);
                applyPlaylist(episodes);
            } catch (Exception e) {
                Log.e(TAG, "Playlist parse error", e);
            }
        }, error -> Log.e(TAG, "Playlist network error", error)) {
            @Override public Map<String, String> getHeaders() { return apiHeaders(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void addEpisodeMedia(ArrayList<EpisodeMedia> result, JSONObject json, int index) {
        if (json == null) return;
        int resolvedChannelId = json.optInt("vid", -1);
        int resolvedCategoryId = json.optInt("cat_id", categoryId);
        if (resolvedChannelId <= 0 || (categoryId > 0 && resolvedCategoryId != categoryId)) return;
        String resolvedUrl = resolveVideoUrl(json);
        if (!isAvailable(resolvedUrl)) return;
        String rawTitle = json.optString("video_title", "Episode " + (index + 1));
        String label = qualityLabelForResolved(json, resolvedUrl);
        String title = rawTitle + " • " + label;
        String img = imageUrl(firstAvailableText(json.optString("category_image", ""), json.optString("video_thumbnail", ""), imageUrl));
        String name = json.optString("category_name", categoryName);
        result.add(new EpisodeMedia(resolvedChannelId, resolvedCategoryId, name, title, img, resolvedUrl, extractEpisodeNumber(rawTitle), index));
    }

    private void applyPlaylist(ArrayList<EpisodeMedia> episodes) {
        if (player == null || episodes == null || episodes.size() <= 1) {
            refreshingQuality = false;
            return;
        }
        LinkedHashMap<Integer, EpisodeMedia> unique = new LinkedHashMap<>();
        for (EpisodeMedia episode : episodes) if (episode != null && episode.channelId > 0 && !unique.containsKey(episode.channelId)) unique.put(episode.channelId, episode);
        ArrayList<EpisodeMedia> ordered = new ArrayList<>(unique.values());
        if (ordered.size() <= 1) {
            refreshingQuality = false;
            return;
        }
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
        if (currentIndex < 0) {
            refreshingQuality = false;
            return;
        }
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
            refreshingQuality = false;
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
        refreshingQuality = false;
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

    private String resolveVideoUrl(JSONObject json) {
        String sd = json.optString("video_url", "");
        String mini = json.optString("video_url_minihd", "");
        String hd = json.optString("video_url_hd", "");
        String fhd = json.optString("video_url_fullhd", "");
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) return firstAvailableText(sd, mini, hd, fhd);
        if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) return firstAvailableText(fhd, hd, mini, sd);
        return firstAvailableText(hd, mini, sd, fhd);
    }

    private String qualityLabelForResolved(JSONObject json, String resolvedUrl) {
        if (sameUrl(resolvedUrl, json.optString("video_url_fullhd", ""))) return PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD);
        if (sameUrl(resolvedUrl, json.optString("video_url_hd", ""))) return PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD);
        if (sameUrl(resolvedUrl, json.optString("video_url_minihd", ""))) return "Mini HD 480p";
        if (sameUrl(resolvedUrl, json.optString("video_url", ""))) return PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD);
        return PlaybackQualityManager.getQualityLabel(selectedQuality);
    }

    private boolean sameUrl(String a, String b) {
        return a != null && b != null && a.trim().equals(b.trim());
    }

    private String firstAvailableText(String... values) {
        if (values == null) return "";
        for (String value : values) if (isAvailable(value)) return value.trim();
        for (String value : values) if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) return value.trim();
        return "";
    }

    private String imageUrl(String image) {
        if (image == null) return "";
        String value = image.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        return IMAGE_BASE + value;
    }

    private boolean isAvailable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim());
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, String> animeLoverzHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", "Dart/3.9 (dart:io)");
        h.put("accept", "application/json");
        h.put("access-control-allow-origin", "*");
        h.put("content-type", "text/plain; charset=utf-8");
        return h;
    }

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "Your Videos Channel");
        h.put("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)");
        h.put("Accept", "application/vnd.yourapi.v1.full+json");
        return h;
    }

    private Map<String, String> videoHeaders() {
        return videoHeadersFor(videoUrl);
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
        HistoryItem item = new HistoryItem(episode.channelId, episode.categoryId, episode.categoryName, episode.title == null || episode.title.trim().isEmpty() ? "Animeku" : episode.title, episode.imageUrl, episode.videoUrl, safePosition, safeDuration, System.currentTimeMillis(), historySourceId);
        if (!AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) item.slug = historySlug == null ? "" : historySlug;
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) AnimekuHistoryManager.save(this, item);
        else HistoryManager.save(this, item);
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
        outState.putBoolean("disable_playlist", disablePlaylist);
        outState.putString("history_source_id", historySourceId);
        outState.putString("animeloverz_slug", animeLoverzSlug);
        outState.putString("history_slug", historySlug);
        outState.putString("anichin_episode_url", anichinEpisodeUrl);
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
        if (anichinPlaylistExecutor != null && !anichinPlaylistExecutor.isShutdown()) anichinPlaylistExecutor.shutdownNow();
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
