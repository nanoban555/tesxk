package miku.moe.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.github.chrisbanes.photoview.PhotoView;

public class ZoomableMangaImageView extends PhotoView {
    private static final float MIN_SCALE = 1.0f;
    private static final float MEDIUM_SCALE = 2.0f;
    private static final float MAX_SCALE = 2.5f;
    private String readerScaleType = MangaSettingsManager.IMAGE_SCALE_FIT_WIDTH;
    private boolean doubleTapZoomEnabled = true;
    private int expectedImageWidth = -1;
    private int expectedImageHeight = -1;

    public ZoomableMangaImageView(Context context) {
        super(context);
        init();
    }

    public ZoomableMangaImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZoomableMangaImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setAdjustViewBounds(false);
        setScaleType(ScaleType.FIT_CENTER);
        setCropToPadding(false);
        setWillNotCacheDrawing(true);
        setZoomable(true);
        setMinimumScale(MIN_SCALE);
        setMediumScale(MEDIUM_SCALE);
        setMaximumScale(MAX_SCALE);
        setAllowParentInterceptOnEdge(true);
        setOnDoubleTapListener(new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!doubleTapZoomEnabled) return false;
                return performKotatsuDoubleTap(e);
            }
        });
    }

    public void setReaderScaleType(String type) {
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(type) || MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(type)) readerScaleType = type;
        else readerScaleType = MangaSettingsManager.IMAGE_SCALE_FIT_WIDTH;
        requestLayout();
    }

    public void setDoubleTapZoomEnabled(boolean enabled) {
        doubleTapZoomEnabled = enabled;
    }

    public void setExpectedImageSize(int width, int height) {
        int safeWidth = width > 0 ? width : -1;
        int safeHeight = height > 0 ? height : -1;
        if (expectedImageWidth == safeWidth && expectedImageHeight == safeHeight) return;
        expectedImageWidth = safeWidth;
        expectedImageHeight = safeHeight;
        requestLayout();
    }

    private boolean performKotatsuDoubleTap(MotionEvent event) {
        try {
            float target = getScale() > MIN_SCALE + 0.01f ? MIN_SCALE : MEDIUM_SCALE;
            setScale(target, event.getX(), event.getY(), true);
        } catch (Exception ignored) { }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        boolean handled = super.onTouchEvent(event);
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        return handled;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable drawable = getDrawable();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int imageWidth = drawable == null ? expectedImageWidth : drawable.getIntrinsicWidth();
        int imageHeight = drawable == null ? expectedImageHeight : drawable.getIntrinsicHeight();
        int height;
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) {
            height = Math.max(dp(240), getResources().getDisplayMetrics().heightPixels);
        } else if (MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(readerScaleType) && imageWidth > 0 && imageHeight > 0) {
            float density = getResources().getDisplayMetrics().density;
            int targetWidth = Math.min(width, Math.max(1, Math.round(imageWidth / Math.max(1f, density))));
            height = Math.round(targetWidth * (imageHeight / (float) imageWidth));
        } else if (imageWidth > 0 && imageHeight > 0) {
            height = Math.round(width * (imageHeight / (float) imageWidth));
        } else {
            height = dp(520);
        }
        if (height <= 0) height = dp(520);
        setMeasuredDimension(width, height);
    }

    @Override public void setImageDrawable(Drawable drawable) {
        int oldWidth = expectedImageWidth;
        int oldHeight = expectedImageHeight;
        int previousHeight = measuredHeightFor(oldWidth, oldHeight, Math.max(1, getWidth()));
        super.setImageDrawable(drawable);
        if (drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
            expectedImageWidth = drawable.getIntrinsicWidth();
            expectedImageHeight = drawable.getIntrinsicHeight();
        }
        if (oldWidth == expectedImageWidth && oldHeight == expectedImageHeight) {
            invalidate();
            return;
        }
        int nextHeight = measuredHeightFor(expectedImageWidth, expectedImageHeight, Math.max(1, getWidth()));
        if (previousHeight > 0 && nextHeight > 0 && Math.abs(previousHeight - nextHeight) <= 1) {
            invalidate();
            return;
        }
        requestLayout();
    }

    private int measuredHeightFor(int imageWidth, int imageHeight, int viewWidth) {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0) return -1;
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) return Math.max(dp(240), getResources().getDisplayMetrics().heightPixels);
        if (MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(readerScaleType)) {
            float density = getResources().getDisplayMetrics().density;
            int targetWidth = Math.min(viewWidth, Math.max(1, Math.round(imageWidth / Math.max(1f, density))));
            return Math.max(1, Math.round(targetWidth * (imageHeight / (float) imageWidth)));
        }
        return Math.max(1, Math.round(viewWidth * (imageHeight / (float) imageWidth)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
