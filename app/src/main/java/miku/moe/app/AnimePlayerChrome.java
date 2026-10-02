package miku.moe.app;

import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.Player;

import java.util.Locale;

public class AnimePlayerChrome {
    public interface ActionListener {
        void onQualityClick();
        void onSpeedClick();
        void onScaleClick();
    }

    private final View root;
    private final View topBar;
    private final View centerScrim;
    private final View centerControls;
    private final View bottomBar;
    private final TextView lockedHint;
    private final TextView titleView;
    private final TextView qualityView;
    private final TextView positionView;
    private final TextView durationView;
    private final TextView speedView;
    private final TextView scaleView;
    private final ImageButton closeButton;
    private final ImageButton playButton;
    private final ImageButton rewindButton;
    private final ImageButton forwardButton;
    private final ImageButton previousButton;
    private final ImageButton nextButton;
    private final ImageButton lockButton;
    private final SeekBar seekBar;
    private final ProgressBar bufferingView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable closeAction;
    private final Runnable immersiveAction;
    private final GestureDetector gestureDetector;
    private ActionListener actionListener;
    private Player player;
    private boolean controlsVisible = true;
    private boolean locked = false;
    private boolean userSeeking = false;
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500L);
        }
    };
    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hideControls();
        }
    };
    private final Runnable hideLockRunnable = new Runnable() {
        @Override
        public void run() {
            if (locked) {
                lockButton.setVisibility(View.GONE);
                lockedHint.setVisibility(View.GONE);
            }
        }
    };
    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            updateState();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateState();
        }
    };

    public AnimePlayerChrome(View root, Runnable closeAction, Runnable immersiveAction) {
        this.root = root;
        this.closeAction = closeAction;
        this.immersiveAction = immersiveAction;
        this.topBar = root.findViewById(R.id.player_top_bar);
        this.centerScrim = root.findViewById(R.id.player_center_scrim);
        this.centerControls = root.findViewById(R.id.player_center_controls);
        this.bottomBar = root.findViewById(R.id.player_bottom_bar);
        this.lockedHint = root.findViewById(R.id.player_locked_hint);
        this.titleView = root.findViewById(R.id.player_title);
        this.qualityView = root.findViewById(R.id.player_quality);
        this.positionView = root.findViewById(R.id.player_position);
        this.durationView = root.findViewById(R.id.player_duration);
        this.speedView = root.findViewById(R.id.player_speed);
        this.scaleView = root.findViewById(R.id.player_scale);
        this.closeButton = root.findViewById(R.id.player_close);
        this.playButton = root.findViewById(R.id.player_play_pause);
        this.rewindButton = root.findViewById(R.id.player_rewind);
        this.forwardButton = root.findViewById(R.id.player_forward);
        this.previousButton = root.findViewById(R.id.player_previous);
        this.nextButton = root.findViewById(R.id.player_next);
        this.lockButton = root.findViewById(R.id.player_lock);
        this.seekBar = root.findViewById(R.id.player_seek);
        this.bufferingView = root.findViewById(R.id.player_buffering);
        this.gestureDetector = new GestureDetector(root.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                handleDoubleTapSeek(e.getX());
                return true;
            }
        });
        setupActions();
        updateLockPlacement();
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateLockPlacement());
        showControls();
    }

    public void attachPlayer(Player player) {
        if (this.player != null) this.player.removeListener(listener);
        this.player = player;
        if (this.player != null) this.player.addListener(listener);
        handler.removeCallbacks(progressRunnable);
        handler.post(progressRunnable);
        updateState();
        updateNavigation(false, false);
        showControls();
    }

    public void setActionListener(ActionListener actionListener) {
        this.actionListener = actionListener;
    }

    public void release() {
        handler.removeCallbacks(progressRunnable);
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(hideLockRunnable);
        if (player != null) player.removeListener(listener);
        player = null;
    }

    public void setTitle(String title) {
        titleView.setText(title == null || title.trim().isEmpty() ? "Anime" : title.trim());
    }

    public void setQualityLabel(String label) {
        qualityView.setText(label == null || label.trim().isEmpty() ? "AUTO" : label.trim());
    }

    public void setScaleLabel(String label) {
        scaleView.setText(label == null || label.trim().isEmpty() ? "Normal" : label.trim());
    }

    public void updateNavigation(boolean hasPrevious, boolean hasNext) {
        previousButton.setEnabled(hasPrevious);
        nextButton.setEnabled(hasNext);
        previousButton.setAlpha(hasPrevious ? 1f : 0.35f);
        nextButton.setAlpha(hasNext ? 1f : 0.35f);
    }

    private void setupActions() {
        root.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
        closeButton.setOnClickListener(v -> {
            if (closeAction != null) closeAction.run();
        });
        qualityView.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onQualityClick();
            scheduleHide();
        });
        speedView.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onSpeedClick();
            scheduleHide();
        });
        scaleView.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onScaleClick();
            scheduleHide();
        });
        playButton.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
            updateState();
            scheduleHide();
        });
        rewindButton.setOnClickListener(v -> seekBy(-10000L, true));
        forwardButton.setOnClickListener(v -> seekBy(10000L, true));
        previousButton.setOnClickListener(v -> {
            if (player != null && player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
            scheduleHide();
        });
        nextButton.setOnClickListener(v -> {
            if (player != null && player.hasNextMediaItem()) player.seekToNextMediaItem();
            scheduleHide();
        });
        lockButton.setOnClickListener(v -> {
            locked = !locked;
            lockButton.setImageResource(locked ? R.drawable.ic_player_lock : R.drawable.ic_player_unlock);
            if (locked) {
                hideControls();
                showLockedHint("Player terkunci");
                handler.removeCallbacks(hideLockRunnable);
                handler.postDelayed(hideLockRunnable, 900L);
            } else {
                handler.removeCallbacks(hideLockRunnable);
                showControls();
            }
        });
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || player == null) return;
                long duration = Math.max(0L, player.getDuration());
                if (duration > 0L) positionView.setText(formatTime(duration * progress / 1000L));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
                handler.removeCallbacks(hideRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null) {
                    long duration = Math.max(0L, player.getDuration());
                    if (duration > 0L) player.seekTo(duration * seekBar.getProgress() / 1000L);
                }
                userSeeking = false;
                scheduleHide();
            }
        });
    }

    private void seekBy(long delta, boolean keepChromeMode) {
        if (player == null) return;
        long duration = Math.max(0L, player.getDuration());
        long target = Math.max(0L, player.getCurrentPosition() + delta);
        if (duration > 0L) target = Math.min(duration, target);
        player.seekTo(target);
        updateProgress();
        if (keepChromeMode) scheduleHide();
    }

    private void handleDoubleTapSeek(float x) {
        if (player == null || locked) return;
        float width = Math.max(1f, root.getWidth());
        if (x < width * 0.42f) {
            seekBy(-10000L, false);
        } else if (x > width * 0.58f) {
            seekBy(10000L, false);
        }
    }

    private void toggleControls() {
        if (locked) {
            updateLockPlacement();
            lockButton.setVisibility(View.VISIBLE);
            showLockedHint("Ketuk ikon untuk membuka");
            handler.removeCallbacks(hideRunnable);
            handler.removeCallbacks(hideLockRunnable);
            handler.postDelayed(hideLockRunnable, 1800L);
            return;
        }
        if (controlsVisible) hideControls(); else showControls();
    }

    private void showControls() {
        controlsVisible = true;
        updateLockPlacement();
        topBar.setVisibility(View.VISIBLE);
        centerScrim.setVisibility(View.VISIBLE);
        centerControls.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        lockButton.setVisibility(View.VISIBLE);
        lockedHint.setVisibility(View.GONE);
        scheduleHide();
    }

    private void hideControls() {
        controlsVisible = false;
        topBar.setVisibility(View.GONE);
        centerScrim.setVisibility(View.GONE);
        centerControls.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        lockButton.setVisibility(View.GONE);
        lockedHint.setVisibility(View.GONE);
        if (immersiveAction != null) immersiveAction.run();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        if (!locked) handler.postDelayed(hideRunnable, 3600L);
    }

    private void showLockedHint(String message) {
        lockedHint.setText(message);
        lockedHint.setVisibility(View.VISIBLE);
    }

    private void updateState() {
        if (player == null) return;
        playButton.setImageResource(player.isPlaying() ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        bufferingView.setVisibility(player.getPlaybackState() == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        speedView.setText(String.format(Locale.US, "%.2fx", player.getPlaybackParameters().speed));
        updateProgress();
    }

    private void updateProgress() {
        if (player == null) return;
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = Math.max(0L, player.getDuration());
        if (!userSeeking) {
            positionView.setText(formatTime(position));
            durationView.setText(duration > 0L ? formatTime(duration) : "--:--");
            seekBar.setProgress(duration > 0L ? (int) Math.min(1000L, position * 1000L / duration) : 0);
            seekBar.setSecondaryProgress(duration > 0L ? (int) Math.min(1000L, player.getBufferedPosition() * 1000L / duration) : 0);
        }
        updateNavigation(player.hasPreviousMediaItem(), player.hasNextMediaItem());
    }

    private void updateLockPlacement() {
        if (!(lockButton.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lockButton.getLayoutParams();
        int width = root.getWidth();
        int height = root.getHeight();
        boolean portrait = height > width || root.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        params.gravity = portrait ? Gravity.BOTTOM | Gravity.END : Gravity.CENTER_VERTICAL | Gravity.END;
        params.setMarginEnd(dp(portrait ? 30 : 18));
        params.bottomMargin = portrait ? dp(178) : 0;
        lockButton.setLayoutParams(params);
    }

    private int dp(int value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }

    private String formatTime(long value) {
        long totalSeconds = Math.max(0L, value / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
