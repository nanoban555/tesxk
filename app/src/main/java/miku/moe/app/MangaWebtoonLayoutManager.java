package miku.moe.app;

import android.content.Context;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MangaWebtoonLayoutManager extends LinearLayoutManager {
    private final int extraLayoutSpace;

    public MangaWebtoonLayoutManager(Context context, int extraLayoutSpace) {
        super(context, RecyclerView.VERTICAL, false);
        this.extraLayoutSpace = Math.max(0, extraLayoutSpace);
        setItemPrefetchEnabled(true);
    }

    @Override public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
        if (state != null && state.isPreLayout()) {
            extraLayoutSpace[0] = 0;
            extraLayoutSpace[1] = 0;
            return;
        }
        extraLayoutSpace[0] = this.extraLayoutSpace;
        extraLayoutSpace[1] = this.extraLayoutSpace;
    }
    public int findLastEndVisibleItemPosition() {
        if (getChildCount() <= 0) return RecyclerView.NO_POSITION;
        int parentStart = getPaddingTop();
        int parentEnd = getHeight() - getPaddingBottom();
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child == null) continue;
            int childStart = getDecoratedTop(child);
            int childEnd = getDecoratedBottom(child);
            if (childEnd <= parentEnd || childStart < parentStart) return getPosition(child);
        }
        return RecyclerView.NO_POSITION;
    }

}
