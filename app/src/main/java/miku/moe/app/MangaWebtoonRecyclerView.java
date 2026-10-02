package miku.moe.app;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MangaWebtoonRecyclerView extends RecyclerView {
    private static final long ZOOM_DURATION = 180L;
    private static final long DOUBLE_TAP_DURATION = 220L;
    private static final long SINGLE_TAP_MAX_DURATION = 400L;
    private static final float DEFAULT_SCALE = 1f;
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.5f;
    private static final float DOUBLE_TAP_SCALE = 2f;
    private static final int FLING_RANGE = 20000;
    private SingleTapListener singleTapListener;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller overScroller;
    private boolean zoomEnabled;
    private boolean doubleTapZoomEnabled = true;
    private boolean isZooming;
    private boolean isZoomDragging;
    private boolean isDoubleTapping;
    private boolean isQuickScaling;
    private boolean tapDuringManualScroll;
    private boolean isManuallyScrolling;
    private boolean validSingleTapCandidate;
    private float tapDownX;
    private float tapDownY;
    private long tapDownTime;
    private long lastTapDuration;
    private long suppressSingleTapUntil;
    private float pendingSingleTapX;
    private float pendingSingleTapY;
    private long pendingSingleTapDownTime;
    private int tapMoveSlop;
    private int scrollPointerId;
    private int downX;
    private int downY;
    private int halfWidth;
    private int halfHeight;
    private int touchSlop;
    private float currentScale = DEFAULT_SCALE;
    private int firstVisibleItemPosition;
    private int lastVisibleItemPosition;
    private boolean externalGestureFrameEnabled;
    private int flingLastX;
    private int flingLastY;

    public MangaWebtoonRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public MangaWebtoonRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MangaWebtoonRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipToPadding(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        tapMoveSlop = Math.max(touchSlop, Math.round(12f * getResources().getDisplayMetrics().density));
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new TapListener());
        overScroller = new OverScroller(context, new AccelerateDecelerateInterpolator());
        scaleDetector.setQuickScaleEnabled(true);
    }

    public interface SingleTapListener {
        void onSingleTap(float x, float y, int width, int height);
    }

    public void setSingleTapListener(SingleTapListener listener) {
        singleTapListener = listener;
    }

    void setExternalGestureFrameEnabled(boolean enabled) {
        externalGestureFrameEnabled = enabled;
        scaleDetector.setQuickScaleEnabled(!enabled && zoomEnabled && doubleTapZoomEnabled);
    }

    boolean isReaderZoomEnabled() {
        return zoomEnabled;
    }

    public void setZoomEnabled(boolean enabled) {
        zoomEnabled = enabled;
        scaleDetector.setQuickScaleEnabled(!externalGestureFrameEnabled && enabled && doubleTapZoomEnabled);
        if (getParent() instanceof MangaWebtoonFrame) ((MangaWebtoonFrame) getParent()).setDoubleTapZoomEnabled(enabled && doubleTapZoomEnabled);
        if (!enabled) resetZoom();
    }

    public void setDoubleTapZoomEnabled(boolean enabled) {
        doubleTapZoomEnabled = enabled;
        scaleDetector.setQuickScaleEnabled(!externalGestureFrameEnabled && zoomEnabled && enabled);
        if (getParent() instanceof MangaWebtoonFrame) ((MangaWebtoonFrame) getParent()).setDoubleTapZoomEnabled(zoomEnabled && enabled);
        if (!enabled) {
            isDoubleTapping = false;
            isQuickScaling = false;
        }
    }

    public void resetZoom() {
        cancelZoomAnimation();
        currentScale = DEFAULT_SCALE;
        isZooming = false;
        isZoomDragging = false;
        isDoubleTapping = false;
        isQuickScaling = false;
        setScaleRate(DEFAULT_SCALE);
        setTranslationX(0f);
        setTranslationY(0f);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        halfWidth = MeasureSpec.getSize(widthSpec) / 2;
        halfHeight = MeasureSpec.getSize(heightSpec) / 2;
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        updateSingleTapCandidate(ev);
        if (zoomEnabled && !externalGestureFrameEnabled) scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) tapDuringManualScroll = isManuallyScrolling;
        if (zoomEnabled && handleZoomTouch(e)) return true;
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateSingleTapCandidate(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            validSingleTapCandidate = SystemClock.uptimeMillis() >= suppressSingleTapUntil;
            tapDownX = ev.getX();
            tapDownY = ev.getY();
            tapDownTime = ev.getEventTime();
            lastTapDuration = 0L;
            return;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            validSingleTapCandidate = false;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE && validSingleTapCandidate) {
            float dx = ev.getX() - tapDownX;
            float dy = ev.getY() - tapDownY;
            if ((dx * dx) + (dy * dy) > tapMoveSlop * tapMoveSlop) validSingleTapCandidate = false;
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            lastTapDuration = ev.getEventTime() - tapDownTime;
            pendingSingleTapX = ev.getX();
            pendingSingleTapY = ev.getY();
            pendingSingleTapDownTime = tapDownTime;
            if (validSingleTapCandidate && lastTapDuration <= SINGLE_TAP_MAX_DURATION) postDelayed(singleTapFallbackRunnable, ViewConfiguration.getDoubleTapTimeout() + 40L);
        }
    }

    @Override public void onScrolled(int dx, int dy) {
        super.onScrolled(dx, dy);
        LayoutManager manager = getLayoutManager();
        if (manager instanceof LinearLayoutManager) {
            LinearLayoutManager linear = (LinearLayoutManager) manager;
            firstVisibleItemPosition = linear.findFirstVisibleItemPosition();
            lastVisibleItemPosition = linear.findLastVisibleItemPosition();
        }
    }

    @Override public void onScrollStateChanged(int state) {
        super.onScrollStateChanged(state);
        if (state == SCROLL_STATE_IDLE) isManuallyScrolling = false;
        else if (state == SCROLL_STATE_DRAGGING) {
            isManuallyScrolling = true;
            validSingleTapCandidate = false;
        }
    }

    private boolean handleZoomTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        int actionIndex = ev.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!overScroller.isFinished()) overScroller.forceFinished(true);
            scrollPointerId = ev.getPointerId(0);
            downX = Math.round(ev.getX());
            downY = Math.round(ev.getY());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            scrollPointerId = ev.getPointerId(actionIndex);
            downX = Math.round(ev.getX(actionIndex));
            downY = Math.round(ev.getY(actionIndex));
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (ev.getPointerId(actionIndex) == scrollPointerId) {
                int newIndex = actionIndex == 0 ? 1 : 0;
                if (newIndex < ev.getPointerCount()) {
                    scrollPointerId = ev.getPointerId(newIndex);
                    downX = Math.round(ev.getX(newIndex));
                    downY = Math.round(ev.getY(newIndex));
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (isDoubleTapping && isQuickScaling) return true;
            if (ev.getPointerCount() > 1) return true;
            if (currentScale <= DEFAULT_SCALE) return false;
            int index = ev.findPointerIndex(scrollPointerId);
            if (index < 0) return false;
            int x = Math.round(ev.getX(index));
            int y = Math.round(ev.getY(index));
            int dx = x - downX;
            int dy = y - downY;
            if (!isZoomDragging) {
                boolean startScroll = false;
                if (Math.abs(dx) > touchSlop) {
                    dx += dx < 0 ? touchSlop : -touchSlop;
                    startScroll = true;
                }
                if (Math.abs(dy) > touchSlop) {
                    dy += dy < 0 ? touchSlop : -touchSlop;
                    startScroll = true;
                }
                if (startScroll) isZoomDragging = true;
            }
            if (isZoomDragging) {
                boolean consumed = zoomScrollBy(dx, dy);
                downX = x;
                downY = y;
                if (consumed && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return consumed;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (doubleTapZoomEnabled && isDoubleTapping && !isQuickScaling) doubleTapZoom(ev);
            isZoomDragging = false;
            isDoubleTapping = false;
            isQuickScaling = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            isZoomDragging = false;
            isDoubleTapping = false;
            isQuickScaling = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return false;
    }

    private float getPositionX(float positionX) {
        if (currentScale <= DEFAULT_SCALE) return 0f;
        float max = halfWidth * (currentScale - DEFAULT_SCALE);
        return Math.max(-max, Math.min(max, positionX));
    }

    private float getPositionY(float positionY) {
        if (currentScale <= DEFAULT_SCALE) return 0f;
        float max = halfHeight * (currentScale - DEFAULT_SCALE);
        return Math.max(-max, Math.min(max, positionY));
    }

    private boolean canConsumeX(float dx) {
        if (currentScale <= DEFAULT_SCALE || dx == 0f) return false;
        float x = getTranslationX();
        return dx > 0f ? x < getPositionX(Float.MAX_VALUE) : x > getPositionX(-Float.MAX_VALUE);
    }

    private boolean canConsumeY(float dy) {
        if (currentScale <= DEFAULT_SCALE || dy == 0f) return false;
        float y = getTranslationY();
        return dy > 0f ? y < getPositionY(Float.MAX_VALUE) : y > getPositionY(-Float.MAX_VALUE);
    }

    private void setScaleRate(float rate) {
        setScaleX(rate);
        setScaleY(rate);
    }

    private boolean zoomScrollBy(int dx, int dy) {
        boolean consumed = false;
        if (dx != 0 && canConsumeX(dx)) {
            setTranslationX(getPositionX(getTranslationX() + dx));
            consumed = true;
        }
        if (dy != 0 && canConsumeY(dy)) {
            setTranslationY(getPositionY(getTranslationY() + dy));
            consumed = true;
        }
        if (consumed) invalidate();
        return consumed;
    }

    boolean onReaderScaleBegin() {
        if (!zoomEnabled) return false;
        cancelZoomAnimation();
        if (!overScroller.isFinished()) overScroller.forceFinished(true);
        if (isDoubleTapping) isQuickScaling = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    boolean onReaderScale(float scaleFactor) {
        return onReaderScale(scaleFactor, halfWidth, halfHeight);
    }

    boolean onReaderScale(float scaleFactor, float focusX, float focusY) {
        if (!zoomEnabled) return false;
        if (Math.abs(scaleFactor - DEFAULT_SCALE) < 0.003f) return true;
        float oldScale = currentScale;
        float newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, currentScale * scaleFactor));
        if (oldScale == 0f || Float.isNaN(oldScale) || Float.isNaN(newScale)) return false;
        float factor = newScale / oldScale;
        float nextX = focusX - halfWidth - (focusX - halfWidth - getTranslationX()) * factor;
        float nextY = focusY - halfHeight - (focusY - halfHeight - getTranslationY()) * factor;
        currentScale = newScale;
        setScaleRate(currentScale);
        if (currentScale > DEFAULT_SCALE) {
            setTranslationX(getPositionX(nextX));
            setTranslationY(getPositionY(nextY));
        } else {
            setTranslationX(0f);
            setTranslationY(0f);
        }
        invalidate();
        return true;
    }

    void onReaderScaleEnd() {
        if (currentScale <= DEFAULT_SCALE) {
            setTranslationX(0f);
            setTranslationY(0f);
        }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    void onManualReaderScroll() {
        isManuallyScrolling = true;
    }

    boolean zoomFling(int velocityX, int velocityY) {
        validSingleTapCandidate = false;
        isManuallyScrolling = true;
        if (!zoomEnabled || currentScale <= DEFAULT_SCALE) return false;
        overScroller.forceFinished(true);
        flingLastX = Math.round(getTranslationX());
        flingLastY = Math.round(getTranslationY());
        overScroller.fling(
                flingLastX,
                flingLastY,
                velocityX,
                velocityY,
                Math.round(getPositionX(-Float.MAX_VALUE)),
                Math.round(getPositionX(Float.MAX_VALUE)),
                Math.round(getPositionY(-Float.MAX_VALUE)) - FLING_RANGE,
                Math.round(getPositionY(Float.MAX_VALUE)) + FLING_RANGE
        );
        postOnAnimation(flingRunnable);
        return true;
    }

    private final Runnable flingRunnable = new Runnable() {
        @Override public void run() {
            if (overScroller.computeScrollOffset()) {
                int currX = overScroller.getCurrX();
                int currY = overScroller.getCurrY();
                float targetX = getPositionX(getTranslationX() + currX - flingLastX);
                float targetY = getPositionY(getTranslationY() + currY - flingLastY);
                flingLastX = currX;
                flingLastY = currY;
                setTranslationX(targetX);
                setTranslationY(targetY);
                postOnAnimation(this);
            }
        }
    };

    private void animateZoom(float fromScale, float toScale, float fromX, float toX, float fromY, float toY, long duration) {
        cancelZoomAnimation();
        isZooming = true;
        AnimatorSet set = new AnimatorSet();
        ValueAnimator scaleAnimator = ValueAnimator.ofFloat(fromScale, toScale);
        ValueAnimator xAnimator = ValueAnimator.ofFloat(fromX, toX);
        ValueAnimator yAnimator = ValueAnimator.ofFloat(fromY, toY);
        scaleAnimator.addUpdateListener(animation -> {
            currentScale = (float) animation.getAnimatedValue();
            setScaleRate(currentScale);
        });
        xAnimator.addUpdateListener(animation -> setTranslationX(getPositionX((float) animation.getAnimatedValue())));
        yAnimator.addUpdateListener(animation -> setTranslationY(getPositionY((float) animation.getAnimatedValue())));
        set.playTogether(scaleAnimator, xAnimator, yAnimator);
        set.setDuration(duration);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                currentScale = toScale;
                setScaleRate(toScale);
                setTranslationX(toScale <= DEFAULT_SCALE ? 0f : getPositionX(toX));
                setTranslationY(toScale <= DEFAULT_SCALE ? 0f : getPositionY(toY));
                isZooming = false;
            }

            @Override public void onAnimationCancel(android.animation.Animator animation) {
                isZooming = false;
            }
        });
        activeAnimator = set;
        set.start();
    }

    private AnimatorSet activeAnimator;

    private void cancelZoomAnimation() {
        if (activeAnimator != null) {
            activeAnimator.cancel();
            activeAnimator = null;
        }
    }

    private void doubleTapZoom(MotionEvent ev) {
        if (isZooming || !zoomEnabled) return;
        if (currentScale > DEFAULT_SCALE + 0.01f) {
            animateZoom(currentScale, DEFAULT_SCALE, getTranslationX(), 0f, getTranslationY(), 0f, DOUBLE_TAP_DURATION);
        } else {
            float target = Math.min(MAX_SCALE, DOUBLE_TAP_SCALE);
            float factor = target / DEFAULT_SCALE;
            float toX = ev.getX() - halfWidth - (ev.getX() - halfWidth) * factor;
            float toY = ev.getY() - halfHeight - (ev.getY() - halfHeight) * factor;
            currentScale = target;
            toX = getPositionX(toX);
            toY = getPositionY(toY);
            currentScale = DEFAULT_SCALE;
            animateZoom(DEFAULT_SCALE, target, 0f, toX, 0f, toY, DOUBLE_TAP_DURATION);
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
            return MangaWebtoonRecyclerView.this.onReaderScaleBegin();
        }

        @Override public boolean onScale(ScaleGestureDetector detector) {
            if (!zoomEnabled) return false;
            return MangaWebtoonRecyclerView.this.onReaderScale(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
        }

        @Override public void onScaleEnd(ScaleGestureDetector detector) {
            MangaWebtoonRecyclerView.this.onReaderScaleEnd();
        }
    }

    private final Runnable singleTapFallbackRunnable = new Runnable() {
        @Override public void run() {
            if (validSingleTapCandidate && pendingSingleTapDownTime == tapDownTime) dispatchReaderSingleTap(pendingSingleTapX, pendingSingleTapY);
        }
    };

    private void dispatchReaderSingleTap(float x, float y) {
        if (SystemClock.uptimeMillis() < suppressSingleTapUntil) {
            validSingleTapCandidate = false;
            return;
        }
        boolean scrollIdle = getScrollState() == SCROLL_STATE_IDLE;
        if (validSingleTapCandidate && lastTapDuration <= SINGLE_TAP_MAX_DURATION && (!tapDuringManualScroll || scrollIdle) && !isZoomDragging && (!isManuallyScrolling || scrollIdle)) {
            if (singleTapListener != null) singleTapListener.onSingleTap(x, y, getWidth(), getHeight());
            else performClick();
        }
        validSingleTapCandidate = false;
        removeCallbacks(singleTapFallbackRunnable);
    }

    private class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            dispatchReaderSingleTap(e.getX(), e.getY());
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            suppressSingleTapUntil = SystemClock.uptimeMillis() + 700L;
            if (!zoomEnabled || !doubleTapZoomEnabled) return false;
            isDoubleTapping = true;
            return true;
        }

        @Override public boolean onDoubleTapEvent(MotionEvent e) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            suppressSingleTapUntil = SystemClock.uptimeMillis() + 700L;
            return false;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            return false;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (externalGestureFrameEnabled) {
                validSingleTapCandidate = false;
                isManuallyScrolling = true;
                return false;
            }
            return zoomFling(Math.round(velocityX), Math.round(velocityY));
        }

        @Override public void onLongPress(MotionEvent e) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }
}
