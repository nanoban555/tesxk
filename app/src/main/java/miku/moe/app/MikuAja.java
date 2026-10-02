package miku.moe.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.content.res.Configuration;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MikuAja extends Fragment {
    private static final String TAG = "MikuAja";
    private static final String ARG_CHANNEL_ID = "channel_id";

    private ImageView imgUrlImageView;
    private PlayerView playerView;
    private FrameLayout playerHolder, fullscreenContainer;
    private View pageRoot;
    private MaterialToolbar toolbar;
    private MaterialButton fullscreenButton;
    private TextView channelNameTextView, channelUrlTextView, channelUrlHdTextView, channelUrlFhdTextView,
            channelUrlOriTextView, channelUrlHdOriTextView, channelUrlFhdOriTextView, gdriveUrlTextView,
            playerHintTextView;
    private ProgressBar progressBar;
    private ExoPlayer player;
    private boolean isFullscreen = false;
    private long playbackPosition = 0L;
    private boolean playWhenReady = false;
    private String currentVideoUrl = null;
    private int currentChannelId = -1;
    private int currentCategoryId = -1;
    private String currentTitle = "";
    private String currentImageUrl = "";
    private String currentCategoryName = "";
    private String channelUrl, channelUrlHd, channelUrlFhd, channelUrlOri, channelUrlHdOri, channelUrlFhdOri, gdriveUrl;

    public static MikuAja newInstance(int channelId) {
        MikuAja fragment = new MikuAja();
        Bundle args = new Bundle();
        args.putInt(ARG_CHANNEL_ID, channelId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_miku_aja, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (isFullscreen) exitFullscreen();
            else requireActivity().onBackPressed();
        });

        pageRoot = view.findViewById(R.id.pageRoot);
        playerHolder = view.findViewById(R.id.playerHolder);
        fullscreenContainer = view.findViewById(R.id.fullscreenContainer);
        imgUrlImageView = view.findViewById(R.id.imgUrlImageView);
        playerView = view.findViewById(R.id.playerView);
        fullscreenButton = view.findViewById(R.id.fullscreenButton);
        channelNameTextView = view.findViewById(R.id.channelNameTextView);
        playerHintTextView = view.findViewById(R.id.playerHintTextView);
        channelUrlTextView = view.findViewById(R.id.channelUrlTextView);
        channelUrlHdTextView = view.findViewById(R.id.channelUrlHdTextView);
        channelUrlFhdTextView = view.findViewById(R.id.channelUrlFhdTextView);
        channelUrlOriTextView = view.findViewById(R.id.channelUrlOriTextView);
        channelUrlHdOriTextView = view.findViewById(R.id.channelUrlHdOriTextView);
        channelUrlFhdOriTextView = view.findViewById(R.id.channelUrlFhdOriTextView);
        gdriveUrlTextView = view.findViewById(R.id.gdriveUrlTextView);
        progressBar = view.findViewById(R.id.progressBar);

        setupPlayer();
        if (savedInstanceState != null) {
            playbackPosition = savedInstanceState.getLong("player_position", 0L);
            playWhenReady = savedInstanceState.getBoolean("player_play_when_ready", false);
            currentVideoUrl = savedInstanceState.getString("current_video_url");
            isFullscreen = savedInstanceState.getBoolean("is_fullscreen", false);
        }
        fullscreenButton.setOnClickListener(v -> {
            if (isFullscreen) exitFullscreen(); else enterFullscreen();
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (isFullscreen) {
                    exitFullscreen();
                } else {
                    setEnabled(false);
                    requireActivity().onBackPressed();
                }
            }
        });

        int channelId = getArguments() == null ? -1 : getArguments().getInt(ARG_CHANNEL_ID, -1);
        if (channelId != -1) fetchPostDescription(channelId);
        else Toast.makeText(requireContext(), "Invalid channel ID", Toast.LENGTH_SHORT).show();
    }

    private void setupPlayer() {

    }

    private void fetchPostDescription(int channelId) {
        progressBar.setVisibility(View.VISIBLE);
        String url = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_post_description/";
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if ("ok".equals(json.optString("status"))) {
                    currentChannelId = json.optInt("channel_id", channelId);
                    currentCategoryId = json.optInt("category_id", -1);
                    currentTitle = json.optString("channel_name", "");
                    currentCategoryName = json.optString("category_name", "");
                    currentImageUrl = json.optString("img_url", "");
                    Glide.with(this).load(currentImageUrl).centerCrop().into(imgUrlImageView);
                    channelNameTextView.setText(currentTitle);
                    String secretKey = json.optString("secretKey", "");
                    channelUrl = AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url"), secretKey);
                    channelUrlHd = AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd"), secretKey);
                    channelUrlFhd = AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd"), secretKey);
                    channelUrlOri = AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_ori"), secretKey);
                    channelUrlHdOri = AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd_ori"), secretKey);
                    channelUrlFhdOri = AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd_ori"), secretKey);
                    gdriveUrl = AnimeXnontonUrlDecoder.decryptUrl(json.optString("gdrive_url"), secretKey);
                    setupClickListeners();
                    updateAvailability();
                } else Toast.makeText(requireContext(), "Gagal mengambil data", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Parse error", e);
                Toast.makeText(requireContext(), "Terjadi kesalahan dalam mem-parsing data", Toast.LENGTH_SHORT).show();
            } finally { progressBar.setVisibility(View.GONE); }
        }, error -> {
            Log.e(TAG, "Network error", error);
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
        Volley.newRequestQueue(requireContext()).add(request);
    }

    private void setupClickListeners() {
        channelUrlTextView.setOnClickListener(v -> openVideoPlayer(channelUrl, "Biasa"));
        channelUrlHdTextView.setOnClickListener(v -> openVideoPlayer(channelUrlHd, "HD"));
        channelUrlFhdTextView.setOnClickListener(v -> openVideoPlayer(channelUrlFhd, "Full HD"));
        channelUrlOriTextView.setOnClickListener(v -> openVideoPlayer(channelUrlOri, "Original"));
        channelUrlHdOriTextView.setOnClickListener(v -> openVideoPlayer(channelUrlHdOri, "HD Original"));
        channelUrlFhdOriTextView.setOnClickListener(v -> openVideoPlayer(channelUrlFhdOri, "FHD Original"));
        gdriveUrlTextView.setOnClickListener(v -> openVideoPlayer(gdriveUrl, "Google Drive"));
    }

    private void updateAvailability() {
        updateButton(channelUrlTextView, channelUrl);
        updateButton(channelUrlHdTextView, channelUrlHd);
        updateButton(channelUrlFhdTextView, channelUrlFhd);
        updateButton(channelUrlOriTextView, channelUrlOri);
        updateButton(channelUrlHdOriTextView, channelUrlHdOri);
        updateButton(channelUrlFhdOriTextView, channelUrlFhdOri);
        updateButton(gdriveUrlTextView, gdriveUrl);
    }

    private void updateButton(TextView button, String url) {
        button.setVisibility(isAvailable(url) ? View.VISIBLE : View.GONE);
    }

    private boolean isAvailable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private void openVideoPlayer(String videoUrl, String label) {
        if (!isAvailable(videoUrl)) {
            Toast.makeText(requireContext(), "URL tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, videoUrl);
        String title = currentTitle == null || currentTitle.trim().isEmpty() ? label : currentTitle + " • " + label;
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, title);
        intent.putExtra(VideoPlayerActivity.EXTRA_IMAGE_URL, currentImageUrl);
        intent.putExtra(VideoPlayerActivity.EXTRA_CHANNEL_ID, currentChannelId);
        intent.putExtra(VideoPlayerActivity.EXTRA_CATEGORY_ID, currentCategoryId);
        intent.putExtra(VideoPlayerActivity.EXTRA_CATEGORY_NAME, currentCategoryName);
        intent.putExtra(VideoPlayerActivity.EXTRA_QUALITY, qualityFromLabel(label));
        startActivity(intent);
    }

    private String qualityFromLabel(String label) {
        if (label != null && label.toLowerCase().contains("full")) return PlaybackQualityManager.QUALITY_FHD;
        if (label != null && label.toLowerCase().contains("fhd")) return PlaybackQualityManager.QUALITY_FHD;
        if (label != null && label.toLowerCase().contains("hd")) return PlaybackQualityManager.QUALITY_HD;
        return PlaybackQualityManager.QUALITY_SD;
    }

    private void playInline(String videoUrl, String label) {
        currentVideoUrl = videoUrl;
        if (!isAvailable(videoUrl)) {
            Toast.makeText(requireContext(), "URL tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            imgUrlImageView.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            fullscreenButton.setVisibility(View.VISIBLE);
            playerHintTextView.setText("Memutar kualitas " + label + ". Tekan tombol layar penuh untuk menonton tanpa gangguan.");

            String credential = "animexnonton:jo3Z0XhdWpmjwaf6YUeLUBM5ltXNuUv8B";
            String basicAuth = "Basic " + Base64.encodeToString(credential.getBytes(), Base64.NO_WRAP);
            DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(Collections.singletonMap("Authorization", basicAuth));
            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoUrl));
            MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
            player.setMediaSource(mediaSource);
            player.prepare();
            if (playbackPosition > 0) player.seekTo(playbackPosition);
            if (playWhenReady || playbackPosition == 0) player.play();
            playWhenReady = false;
            playbackPosition = 0L;
            if (isFullscreen) applyFullscreenLayout(); else applyNormalPlayerLayout();
        } catch (Exception e) {
            Log.e(TAG, "Play error", e);
            Toast.makeText(requireContext(), "Video tidak bisa diputar", Toast.LENGTH_SHORT).show();
        }
    }

    private void enterFullscreen() {
        if (isFullscreen) return;
        isFullscreen = true;
        moveToContainer(playerView, fullscreenContainer);
        moveToContainer(fullscreenButton, fullscreenContainer);
        fullscreenContainer.setVisibility(View.VISIBLE);
        pageRoot.setVisibility(View.GONE);
        fullscreenButton.setText("✕");
        applyFullscreenLayout();
        hideSystemBars();
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;
        isFullscreen = false;
        moveToContainer(playerView, playerHolder);
        moveToContainer(fullscreenButton, playerHolder);
        fullscreenContainer.setVisibility(View.GONE);
        pageRoot.setVisibility(View.VISIBLE);
        fullscreenButton.setText("⛶");
        applyNormalPlayerLayout();
        showSystemBars();
    }

    private void moveToContainer(View child, ViewGroup target) {
        ViewGroup parent = (ViewGroup) child.getParent();
        if (parent != null) parent.removeView(child);
        FrameLayout.LayoutParams params = child == fullscreenButton
                ? new FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.END | android.view.Gravity.BOTTOM)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (child == fullscreenButton) params.setMargins(0, 0, dp(14), dp(14));
        target.addView(child, params);
    }

    private void applyFullscreenLayout() {
        if (fullscreenContainer != null) {
            fullscreenContainer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (playerView != null) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            playerView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            playerView.requestLayout();
        }
    }

    private void applyNormalPlayerLayout() {
        if (playerView != null) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            playerView.requestLayout();
        }
    }

    @Override public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isFullscreen) {
            applyFullscreenLayout();
            hideSystemBars();
        } else {
            applyNormalPlayerLayout();
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            outState.putLong("player_position", player.getCurrentPosition());
            outState.putBoolean("player_play_when_ready", player.getPlayWhenReady());
        }
        outState.putBoolean("is_fullscreen", isFullscreen);
        outState.putString("current_video_url", currentVideoUrl);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void hideSystemBars() {
        Window window = requireActivity().getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showSystemBars() {
        requireActivity().getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).restoreSystemBars();
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "AnimeXNonton 26.9.5/18");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("User-Agent", "okhttp/5.5.0");
        return h;
    }

    @Override public void onPause() {
        super.onPause();
        if (player != null) {
            playbackPosition = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
            player.pause();
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (player != null) {
            player.release();
            player = null;
        }
        playerView = null;
    }
}
