package miku.moe.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

public class CollapsibleHeaderBehavior extends CoordinatorLayout.Behavior<View> {
    private int offset;
    private int maxOffset;

    public CollapsibleHeaderBehavior() { }

    public CollapsibleHeaderBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        parent.onLayoutChild(child, layoutDirection);
        calculateMaxOffset(child);
        applyOffset(parent, child);
        return true;
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                       @NonNull View child,
                                       @NonNull View directTargetChild,
                                       @NonNull View target,
                                       int axes,
                                       int type) {
        return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedPreScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                  @NonNull View child,
                                  @NonNull View target,
                                  int dx,
                                  int dy,
                                  @NonNull int[] consumed,
                                  int type) {
        if (dy <= 0) return;
        int oldOffset = offset;
        offset = clamp(offset + dy, 0, maxOffset);
        int delta = offset - oldOffset;
        if (delta != 0) {
            consumed[1] = delta;
            applyOffset(coordinatorLayout, child);
        }
    }

    @Override
    public void onNestedScroll(@NonNull CoordinatorLayout coordinatorLayout,
                               @NonNull View child,
                               @NonNull View target,
                               int dxConsumed,
                               int dyConsumed,
                               int dxUnconsumed,
                               int dyUnconsumed,
                               int type,
                               @NonNull int[] consumed) {
        if (dyUnconsumed >= 0) return;
        int oldOffset = offset;
        offset = clamp(offset + dyUnconsumed, 0, maxOffset);
        int delta = offset - oldOffset;
        if (delta != 0) {
            consumed[1] += delta;
            applyOffset(coordinatorLayout, child);
        }
    }

    private void calculateMaxOffset(View child) {
        
        maxOffset = Math.max(0, child.getBottom());
        offset = clamp(offset, 0, maxOffset);
    }

    private void applyOffset(CoordinatorLayout parent, View child) {
        if (maxOffset <= 0) calculateMaxOffset(child);
        float progress = maxOffset == 0 ? 0f : (float) offset / (float) maxOffset;
        child.setTranslationY(-offset);
        child.setAlpha(1f - progress);
        child.setScaleX(1f - (progress * 0.04f));
        child.setScaleY(1f - (progress * 0.04f));
        child.setVisibility(progress >= 0.995f ? View.INVISIBLE : View.VISIBLE);
        parent.dispatchDependentViewsChanged(child);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
