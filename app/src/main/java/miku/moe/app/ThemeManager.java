package miku.moe.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsetsController;

import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.MaterialColors;

public final class ThemeManager {
    public static final String THEME_DEFAULT = "default";
    public static final String THEME_MIKU = "miku";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_GREEN = "green";
    public static final String THEME_PURPLE = "purple";
    public static final String THEME_RED = "red";
    public static final String THEME_AMBER = "amber";
    public static final String THEME_CYAN = "cyan";
    public static final String THEME_TEAL = "teal";
    public static final String THEME_INDIGO = "indigo";
    public static final String THEME_ORANGE = "orange";
    public static final String THEME_CORAL = "coral";
    public static final String THEME_ROSE = "rose";
    public static final String THEME_MAGENTA = "magenta";
    public static final String THEME_VIOLET = "violet";
    public static final String THEME_GRAPE = "grape";
    public static final String THEME_LIME = "lime";
    public static final String THEME_EMERALD = "emerald";
    public static final String THEME_MINT = "mint";
    public static final String THEME_SKY = "sky";
    public static final String THEME_NAVY = "navy";
    public static final String THEME_BROWN = "brown";
    public static final String THEME_GRAPHITE = "graphite";
    public static final String THEME_GOLD = "gold";
    public static final String THEME_SAKURA = "sakura";
    public static final String THEME_AQUA = "aqua";
    private static final String PREFS_NAME = "miku_moe_ui_prefs";
    private static final String KEY_APP_THEME = "app_theme";

    private ThemeManager() {}

    public static void applyNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    public static void applyTheme(Activity activity) {
        applyNightMode(activity);
        activity.setTheme(getThemeStyle(activity));
        int surfaceColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, 0);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(surfaceColor));
    }

    public static void applySystemBars(Activity activity) {
        Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(lp);
        }
        int surfaceColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, 0);
        int navigationColor = surfaceColor;
        window.setBackgroundDrawable(new ColorDrawable(surfaceColor));
        window.setStatusBarColor(surfaceColor);
        window.setNavigationBarColor(navigationColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.setNavigationBarDividerColor(navigationColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        boolean isNightMode = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!isNightMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    public static Context createForcedNightContext(Context context) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context nightContext = context.createConfigurationContext(configuration);
        nightContext.setTheme(getThemeStyle(context));
        return nightContext;
    }

    public static int getForcedNightColor(Context context, int attr, int fallback) {
        return MaterialColors.getColor(createForcedNightContext(context), attr, fallback);
    }

    public static void applyWebViewSystemBars(Activity activity) {
        Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(lp);
        }
        int surfaceColor = getForcedNightColor(activity, com.google.android.material.R.attr.colorSurface, 0xFF07131F);
        window.setBackgroundDrawable(new ColorDrawable(surfaceColor));
        window.setStatusBarColor(surfaceColor);
        window.setNavigationBarColor(surfaceColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.setNavigationBarDividerColor(surfaceColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && window.getInsetsController() != null) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            window.getInsetsController().setSystemBarsAppearance(0, appearance);
        }
    }

    public static String[] getThemeValues() {
        return new String[]{THEME_DEFAULT, THEME_MIKU, THEME_BLUE, THEME_GREEN, THEME_PURPLE, THEME_RED, THEME_AMBER, THEME_CYAN, THEME_TEAL, THEME_INDIGO, THEME_ORANGE, THEME_CORAL, THEME_ROSE, THEME_MAGENTA, THEME_VIOLET, THEME_GRAPE, THEME_LIME, THEME_EMERALD, THEME_MINT, THEME_SKY, THEME_NAVY, THEME_BROWN, THEME_GRAPHITE, THEME_GOLD, THEME_SAKURA, THEME_AQUA};
    }

    public static String[] getThemeLabels() {
        return new String[]{"Default", "Miku Pink", "Ocean Blue", "Green Apple", "Lavender", "Strawberry", "Amber Glow", "Crystal Cyan", "Deep Teal", "Midnight Indigo", "Sunset Orange", "Soft Coral", "Rose Quartz", "Neon Magenta", "Electric Violet", "Sweet Grape", "Lime Fresh", "Emerald Forest", "Mint Breeze", "Sky Breeze", "Royal Navy", "Coffee Brown", "Graphite Dark", "Golden Honey", "Sakura Bloom", "Aqua Marine"};
    }

    public static String getTheme(Context context) {
        String theme = prefs(context).getString(KEY_APP_THEME, THEME_BLUE);
        for (String value : getThemeValues()) {
            if (value.equals(theme)) return value;
        }
        return THEME_BLUE;
    }

    public static void setTheme(Context context, String theme) {
        String selected = THEME_BLUE;
        for (String value : getThemeValues()) {
            if (value.equals(theme)) {
                selected = value;
                break;
            }
        }
        prefs(context).edit().putString(KEY_APP_THEME, selected).apply();
    }

    public static int getThemeStyle(Context context) {
        String theme = getTheme(context);
        if (THEME_MIKU.equals(theme)) return R.style.AppTheme_Miku;
        if (THEME_BLUE.equals(theme)) return R.style.AppTheme_Blue;
        if (THEME_GREEN.equals(theme)) return R.style.AppTheme_Green;
        if (THEME_PURPLE.equals(theme)) return R.style.AppTheme_Purple;
        if (THEME_RED.equals(theme)) return R.style.AppTheme_Red;
        if (THEME_AMBER.equals(theme)) return R.style.AppTheme_Amber;
        if (THEME_CYAN.equals(theme)) return R.style.AppTheme_Cyan;
        if (THEME_TEAL.equals(theme)) return R.style.AppTheme_Teal;
        if (THEME_INDIGO.equals(theme)) return R.style.AppTheme_Indigo;
        if (THEME_ORANGE.equals(theme)) return R.style.AppTheme_Orange;
        if (THEME_CORAL.equals(theme)) return R.style.AppTheme_Coral;
        if (THEME_ROSE.equals(theme)) return R.style.AppTheme_Rose;
        if (THEME_MAGENTA.equals(theme)) return R.style.AppTheme_Magenta;
        if (THEME_VIOLET.equals(theme)) return R.style.AppTheme_Violet;
        if (THEME_GRAPE.equals(theme)) return R.style.AppTheme_Grape;
        if (THEME_LIME.equals(theme)) return R.style.AppTheme_Lime;
        if (THEME_EMERALD.equals(theme)) return R.style.AppTheme_Emerald;
        if (THEME_MINT.equals(theme)) return R.style.AppTheme_Mint;
        if (THEME_SKY.equals(theme)) return R.style.AppTheme_Sky;
        if (THEME_NAVY.equals(theme)) return R.style.AppTheme_Navy;
        if (THEME_BROWN.equals(theme)) return R.style.AppTheme_Brown;
        if (THEME_GRAPHITE.equals(theme)) return R.style.AppTheme_Graphite;
        if (THEME_GOLD.equals(theme)) return R.style.AppTheme_Gold;
        if (THEME_SAKURA.equals(theme)) return R.style.AppTheme_Sakura;
        if (THEME_AQUA.equals(theme)) return R.style.AppTheme_Aqua;
        return R.style.AppTheme;
    }

    public static String getThemeLabel(Context context) {
        String theme = getTheme(context);
        String[] values = getThemeValues();
        String[] labels = getThemeLabels();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(theme)) return labels[i];
        }
        return labels[0];
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
