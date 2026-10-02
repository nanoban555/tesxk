package miku.moe.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ReaderNextChapterToast {
    private ReaderNextChapterToast() {}

    public static Handle showAuto(View root, CharSequence message, Runnable onCancel) {
        return show(root, message, true, onCancel, null);
    }

    public static Handle showManual(View root, CharSequence message, Runnable onCancel, Runnable onNext) {
        return show(root, message, false, onCancel, onNext);
    }

    private static Handle show(View root, CharSequence message, boolean autoMode, Runnable onCancel, Runnable onNext) {
        if (root == null) return Handle.EMPTY;
        ViewGroup parent = resolveParent(root);
        if (parent == null) return Handle.EMPTY;
        Context context = root.getContext();
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        int horizontal = dp(context, 14);
        int vertical = dp(context, 8);
        container.setPadding(horizontal, vertical, horizontal, vertical);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#CC000000"));
        background.setCornerRadius(dp(context, 18));
        container.setBackground(background);
        TextView text = new TextView(context);
        text.setText(message == null ? "" : message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(false);
        text.setMaxLines(2);
        text.setMaxWidth(dp(context, 280));
        container.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (autoMode || onNext != null) {
            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.CENTER);
            buttons.setPadding(0, dp(context, 6), 0, 0);
            TextView noButton = actionText(context, "Tidak");
            noButton.setOnClickListener(v -> { if (onCancel != null) onCancel.run(); });
            buttons.addView(noButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (!autoMode) {
                TextView yesButton = actionText(context, "Ya");
                yesButton.setOnClickListener(v -> { if (onNext != null) onNext.run(); });
                LinearLayout.LayoutParams yesParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                yesParams.setMargins(dp(context, 12), 0, 0, 0);
                buttons.addView(yesButton, yesParams);
            }
            container.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.setMargins(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 92));
            parent.addView(container, params);
        } else {
            parent.addView(container, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return new Handle(parent, container);
    }

    private static TextView actionText(Context context, String label) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#22FFFFFF"));
        background.setCornerRadius(dp(context, 12));
        text.setBackground(background);
        return text;
    }

    private static ViewGroup resolveParent(View root) {
        View view = root.getRootView();
        if (view instanceof ViewGroup) return (ViewGroup) view;
        if (root instanceof ViewGroup) return (ViewGroup) root;
        return null;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static final class Handle {
        static final Handle EMPTY = new Handle(null, null);
        private final ViewGroup parent;
        private final View view;
        private boolean dismissed;

        private Handle(ViewGroup parent, View view) {
            this.parent = parent;
            this.view = view;
        }

        public void dismiss() {
            if (dismissed) return;
            dismissed = true;
            if (parent == null || view == null) return;
            try { parent.removeView(view); } catch (Exception ignored) { }
        }
    }
}
