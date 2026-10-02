package miku.moe.app;

import android.content.Context;
import android.widget.TextView;

public final class MangaTitleStyle {
    private MangaTitleStyle() {}

    public static void apply(TextView view, Context context) {
        if (view == null || context == null) return;
        view.getPaint().setFakeBoldText(MangaSettingsManager.isBoldMangaTitleEnabled(context));
        view.invalidate();
    }
}
