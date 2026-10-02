package miku.moe.app;

import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;

public final class VideoScaleHelper {
    private static final float FIT_MAX_ZOOM = 1.12f;
    private static final float FIT_PIVOT_Y = 0.85f;

    private VideoScaleHelper() {
    }

    public static void apply(PlayerView view, Player player, String scale) {
        if (view == null) return;
        view.setResizeMode(AnimeSettingsManager.getVideoResizeMode(scale));
        int viewWidth = view.getWidth();
        int viewHeight = view.getHeight();
        float zoom = 1f;
        float pivotY = viewHeight / 2f;
        if (AnimeSettingsManager.VIDEO_SCALE_FIT.equals(scale) && player != null && viewWidth > 0 && viewHeight > 0) {
            VideoSize size = player.getVideoSize();
            if (size.width > 0 && size.height > 0) {
                float videoRatio = size.width * size.pixelWidthHeightRatio / size.height;
                float viewRatio = (float) viewWidth / viewHeight;
                float full = Math.max(viewRatio / videoRatio, videoRatio / viewRatio);
                zoom = Math.min(full, FIT_MAX_ZOOM);
                if (viewRatio >= videoRatio) pivotY = viewHeight * FIT_PIVOT_Y;
            }
        }
        view.setPivotX(viewWidth / 2f);
        view.setPivotY(pivotY);
        view.setScaleX(zoom);
        view.setScaleY(zoom);
    }
}
