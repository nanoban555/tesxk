package miku.moe.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import com.google.android.material.snackbar.Snackbar;

public final class AppSnackbar {
    private AppSnackbar() {}

    public static void show(Context context, CharSequence message) {
        show(context, message, Snackbar.LENGTH_SHORT);
    }

    public static void showLong(Context context, CharSequence message) {
        show(context, message, Snackbar.LENGTH_LONG);
    }

    public static void show(View view, CharSequence message) {
        if (view == null) return;
        runOnMain(() -> {
            Snackbar snackbar = make(view, safeText(message), Snackbar.LENGTH_SHORT);
            if (snackbar != null) snackbar.show();
        });
    }

    public static void showLong(View view, CharSequence message) {
        if (view == null) return;
        runOnMain(() -> {
            Snackbar snackbar = make(view, safeText(message), Snackbar.LENGTH_LONG);
            if (snackbar != null) snackbar.show();
        });
    }

    public static Snackbar build(View view, CharSequence message, int duration) {
        if (view == null) return null;
        return make(view, safeText(message), duration);
    }

    public static void show(Context context, CharSequence message, int duration) {
        if (context == null) return;
        Activity activity = findActivity(context);
        if (activity == null) return;
        runOnMain(() -> {
            View view = activity.findViewById(android.R.id.content);
            Snackbar snackbar = make(view, safeText(message), duration);
            if (snackbar != null) snackbar.show();
        });
    }

    private static Snackbar make(View view, CharSequence message, int duration) {
        if (view == null) return null;
        Snackbar snackbar = Snackbar.make(view, message, duration);
        // Widget.Miku.Snackbar hanya mengatur backgroundTint (gelap). Karena aplikasi
        // memaksa mode malam, warna teks bawaan Material3 untuk Snackbar (colorOnSurfaceInverse)
        // ikut gelap, sehingga teks nyaris tidak terlihat di atas latar gelap. Set manual
        // pakai colorOnSurface tema aktif (terang di mode malam) agar selalu kontras.
        snackbar.setTextColor(resolveColorOnSurface(view));
        View anchor = findSnackbarAnchor(view);
        if (anchor != null) snackbar.setAnchorView(anchor);
        applySafeMargins(snackbar, view, anchor == null);
        return snackbar;
    }

    private static int resolveColorOnSurface(View view) {
        Context context = view.getContext();
        TypedValue typedValue = new TypedValue();
        // colorOnSurface adalah attr standar Material3; di mode malam aplikasi ini nilainya terang.
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                return context.getResources().getColor(typedValue.resourceId, context.getTheme());
            }
            return typedValue.data;
        }
        return 0xFFECE1D5; // fallback: warna terang default mode malam (md_theme_onSurface)
    }

    private static View findSnackbarAnchor(View root) {
        View anchor = findUsableAnchor(root, R.id.readerFloatingControls);
        if (anchor != null) return anchor;
        anchor = findUsableAnchor(root, R.id.bottomNavigation);
        if (anchor != null) return anchor;
        return null;
    }

    private static View findUsableAnchor(View root, int id) {
        if (root == null) return null;
        View view = root.findViewById(id);
        if (view == null) return null;
        if (view.getVisibility() != View.VISIBLE) return null;
        return view;
    }

    private static void applySafeMargins(Snackbar snackbar, View root, boolean addBottomSafeArea) {
        if (snackbar == null) return;
        View snackbarView = snackbar.getView();
        if (snackbarView == null) return;
        ViewGroup.LayoutParams params = snackbarView.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        int horizontal = dp(root, 12);
        margins.leftMargin = Math.max(margins.leftMargin, horizontal);
        margins.rightMargin = Math.max(margins.rightMargin, horizontal);
        if (addBottomSafeArea) {
            int bottom = dp(root, 24) + getNavigationInset(root);
            margins.bottomMargin = Math.max(margins.bottomMargin, bottom);
        }
        snackbarView.setLayoutParams(margins);
    }

    private static int getNavigationInset(View root) {
        if (root == null || Build.VERSION.SDK_INT < 23) return 0;
        WindowInsets insets = root.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= 29) return insets.getSystemWindowInsetBottom();
        return insets.getStableInsetBottom();
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static CharSequence safeText(CharSequence text) {
        return text == null || text.toString().trim().isEmpty() ? "Info" : text;
    }

    private static int dp(View view, int value) {
        Context context = view == null ? null : view.getContext();
        if (context == null) return value;
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }
}
