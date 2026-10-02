package miku.moe.app;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class MangaWebtoonFrame extends FrameLayout {
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector flingDetector;

    public MangaWebtoonFrame(@NonNull Context context) {
        this(context, null);
    }

    public MangaWebtoonFrame(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MangaWebtoonFrame(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        flingDetector = new GestureDetector(context, new FlingListener());
    }

    @Override public void onViewAdded(android.view.View child) {
        super.onViewAdded(child);
        if (child instanceof MangaWebtoonRecyclerView) ((MangaWebtoonRecyclerView) child).setExternalGestureFrameEnabled(true);
    }

    @Override public void onViewRemoved(android.view.View child) {
        if (child instanceof MangaWebtoonRecyclerView) ((MangaWebtoonRecyclerView) child).setExternalGestureFrameEnabled(false);
        super.onViewRemoved(child);
    }

    void setDoubleTapZoomEnabled(boolean enabled) {
        scaleDetector.setQuickScaleEnabled(enabled);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        MangaWebtoonRecyclerView recycler = getRecyclerView();
        if (recycler != null && recycler.isReaderZoomEnabled()) {
            scaleDetector.onTouchEvent(ev);
            flingDetector.onTouchEvent(ev);
            Rect rect = new Rect();
            recycler.getHitRect(rect);
            rect.inset(1, 1);
            if (rect.right >= rect.left && rect.bottom >= rect.top) {
                float x = Math.max(rect.left, Math.min(rect.right, ev.getX()));
                float y = Math.max(rect.top, Math.min(rect.bottom, ev.getY()));
                ev.setLocation(x, y);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private MangaWebtoonRecyclerView getRecyclerView() {
        for (int i = 0; i < getChildCount(); i++) {
            android.view.View child = getChildAt(i);
            if (child instanceof MangaWebtoonRecyclerView) return (MangaWebtoonRecyclerView) child;
        }
        return null;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
            MangaWebtoonRecyclerView recycler = getRecyclerView();
            if (recycler == null) return false;
            return recycler.onReaderScaleBegin();
        }

        @Override public boolean onScale(ScaleGestureDetector detector) {
            MangaWebtoonRecyclerView recycler = getRecyclerView();
            if (recycler == null) return false;
            return recycler.onReaderScale(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
        }

        @Override public void onScaleEnd(ScaleGestureDetector detector) {
            MangaWebtoonRecyclerView recycler = getRecyclerView();
            if (recycler != null) recycler.onReaderScaleEnd();
        }
    }

    private class FlingListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            MangaWebtoonRecyclerView recycler = getRecyclerView();
            if (recycler == null) return false;
            recycler.onManualReaderScroll();
            return recycler.zoomFling(Math.round(velocityX), Math.round(velocityY));
        }
    }
}
