package miku.moe.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import java.util.List;

public class ContentBelowHeaderBehavior extends CoordinatorLayout.Behavior<View> {
    public ContentBelowHeaderBehavior() { }

    public ContentBelowHeaderBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) dependency.getLayoutParams();
        return params.getBehavior() instanceof CollapsibleHeaderBehavior;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        positionBelow(parent, child, dependency);
        return true;
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        parent.onLayoutChild(child, layoutDirection);
        List<View> dependencies = parent.getDependencies(child);
        for (View dependency : dependencies) {
            if (layoutDependsOn(parent, child, dependency)) {
                positionBelow(parent, child, dependency);
                return true;
            }
        }
        return true;
    }

    private void positionBelow(CoordinatorLayout parent, View child, View dependency) {
        float visibleBottom = dependency.getBottom() + dependency.getTranslationY();
        float targetY = Math.max(0f, visibleBottom);
        child.setY(targetY);
        child.getLayoutParams().height = Math.max(0, parent.getHeight() - Math.round(targetY));
        child.requestLayout();
    }
}
