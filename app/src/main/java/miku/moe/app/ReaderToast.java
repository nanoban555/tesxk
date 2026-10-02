package miku.moe.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

public final class ReaderToast {
    private ReaderToast() {}

    public static void show(Context context, CharSequence message) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        TextView text = new TextView(appContext);
        text.setText(message == null ? "" : message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(false);
        text.setMaxLines(2);
        int horizontal = dp(appContext, 14);
        int vertical = dp(appContext, 8);
        text.setPadding(horizontal, vertical, horizontal, vertical);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#CC000000"));
        background.setCornerRadius(dp(appContext, 18));
        text.setBackground(background);
        text.setMaxWidth(dp(appContext, 280));
        text.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Toast toast = new Toast(appContext);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(appContext, 92));
        toast.setView(text);
        toast.show();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
