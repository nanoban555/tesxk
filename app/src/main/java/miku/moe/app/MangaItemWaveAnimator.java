package miku.moe.app;

import android.view.View;

public final class MangaItemWaveAnimator {
    private MangaItemWaveAnimator() {}

    public static void apply(View view, int position) {
        reset(view);
    }

    public static void reset(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }
}
