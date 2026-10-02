package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

public class AnimeSettingsManager {
    private static final String PREF = "anime_source_settings";
    private static final String KEY_SOURCE = "anime_source";
    private static final String KEY_SOURCE_ENABLED_PREFIX = "anime_source_enabled_";
    private static final String KEY_HOME_STYLE = "anime_home_style";
    private static final String KEY_HOME_V1_SOURCE = "anime_home_v1_source";
    private static final String KEY_HIDE_LATEST_EPISODE_LABEL = "anime_hide_latest_episode_label";
    private static final String KEY_VIDEO_SCALE = "anime_video_scale";
    public static final String SOURCE_DEFAULT = "default";
    public static final String SOURCE_ANIMEKU = "animeku";
    public static final String SOURCE_ANIMELOVERZ = "animeloverz";
    public static final String SOURCE_DRAMORA = "dramora";
    public static final String SOURCE_ANICHIN = "anichin";
    public static final String HOME_STYLE_DEFAULT = "default";
    public static final String HOME_STYLE_V1 = "v1";
    public static final String VIDEO_SCALE_NORMAL = "normal";
    public static final String VIDEO_SCALE_FIT = "fit";
    public static final String VIDEO_SCALE_STRETCH = "stretch";

    public static String getAnimeSource(Context context) {
        if (context == null) return SOURCE_DEFAULT;
        String source = prefs(context).getString(KEY_SOURCE, SOURCE_DEFAULT);
        if (!isValidSource(source) || !isAnimeSourceEnabled(context, source)) source = getFirstEnabledAnimeSource(context);
        return source;
    }

    public static void setAnimeSource(Context context, String source) {
        if (context == null) return;
        String value = isValidSource(source) ? source : SOURCE_DEFAULT;
        if (!isAnimeSourceEnabled(context, value)) value = getFirstEnabledAnimeSource(context);
        prefs(context).edit().putString(KEY_SOURCE, value).apply();
    }

    public static boolean isAnimekuSource(Context context) {
        return SOURCE_ANIMEKU.equals(getAnimeSource(context));
    }

    public static String getAnimeSourceLabel(Context context) {
        return labelForSourceId(getAnimeSource(context));
    }

    public static String getHomeStyle(Context context) {
        if (context == null) return HOME_STYLE_DEFAULT;
        String value = prefs(context).getString(KEY_HOME_STYLE, HOME_STYLE_DEFAULT);
        return HOME_STYLE_V1.equals(value) ? HOME_STYLE_V1 : HOME_STYLE_DEFAULT;
    }

    public static void setHomeStyle(Context context, String style) {
        if (context == null) return;
        String value = HOME_STYLE_V1.equals(style) ? HOME_STYLE_V1 : HOME_STYLE_DEFAULT;
        prefs(context).edit().putString(KEY_HOME_STYLE, value).apply();
    }

    public static boolean isHomeStyleV1(Context context) {
        return HOME_STYLE_V1.equals(getHomeStyle(context));
    }

    public static String getHomeStyleLabel(Context context) {
        return isHomeStyleV1(context) ? "Home Anime v1" : "Home default";
    }

    public static String getHomeV1Source(Context context) {
        if (context == null) return SOURCE_DEFAULT;
        String fallback = getAnimeSource(context);
        String value = prefs(context).getString(KEY_HOME_V1_SOURCE, fallback);
        return isValidSource(value) ? value : fallback;
    }

    public static void setHomeV1Source(Context context, String source) {
        if (context == null) return;
        String value = isValidSource(source) ? source : SOURCE_DEFAULT;
        prefs(context).edit().putString(KEY_HOME_V1_SOURCE, value).apply();
    }

    public static String getHomeV1SourceLabel(Context context) {
        return labelForSourceId(getHomeV1Source(context));
    }

    public static boolean isHideLatestEpisodeLabelEnabled(Context context) {
        return context != null && prefs(context).getBoolean(KEY_HIDE_LATEST_EPISODE_LABEL, false);
    }

    public static void setHideLatestEpisodeLabelEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_HIDE_LATEST_EPISODE_LABEL, enabled).apply();
    }

    public static boolean shouldShowLatestEpisodeLabel(Context context) {
        return !isHideLatestEpisodeLabelEnabled(context);
    }

    public static String labelForSourceId(String source) {
        if (SOURCE_ANIMEKU.equals(source)) return "Animeku";
        if (SOURCE_ANIMELOVERZ.equals(source)) return "Animeloverz";
        if (SOURCE_DRAMORA.equals(source)) return "Dramora";
        if (SOURCE_ANICHIN.equals(source)) return "Anichin";
        return "Anime X Nonton";
    }

    public static boolean isValidSource(String source) {
        return SOURCE_DEFAULT.equals(source) || SOURCE_ANIMEKU.equals(source) || SOURCE_ANIMELOVERZ.equals(source) || SOURCE_DRAMORA.equals(source) || SOURCE_ANICHIN.equals(source);
    }

    public static String[] allSourceIds() {
        return new String[]{SOURCE_DEFAULT, SOURCE_ANIMEKU, SOURCE_ANIMELOVERZ, SOURCE_DRAMORA, SOURCE_ANICHIN};
    }

    public static boolean isAnimeSourceEnabled(Context context, String source) {
        if (context == null || !isValidSource(source)) return false;
        return prefs(context).getBoolean(KEY_SOURCE_ENABLED_PREFIX + source, true);
    }

    public static void setAnimeSourceEnabled(Context context, String source, boolean enabled) {
        if (context == null || !isValidSource(source)) return;
        prefs(context).edit().putBoolean(KEY_SOURCE_ENABLED_PREFIX + source, enabled).apply();
        if (!hasEnabledAnimeSource(context)) {
            prefs(context).edit().putBoolean(KEY_SOURCE_ENABLED_PREFIX + SOURCE_DEFAULT, true).putString(KEY_SOURCE, SOURCE_DEFAULT).apply();
        } else if (!isAnimeSourceEnabled(context, prefs(context).getString(KEY_SOURCE, SOURCE_DEFAULT))) {
            setAnimeSource(context, getFirstEnabledAnimeSource(context));
        }
    }

    public static ArrayList<String> getEnabledAnimeSources(Context context) {
        ArrayList<String> result = new ArrayList<>();
        if (isAnimeSourceEnabled(context, SOURCE_DEFAULT)) result.add(SOURCE_DEFAULT);
        if (isAnimeSourceEnabled(context, SOURCE_ANIMEKU)) result.add(SOURCE_ANIMEKU);
        if (isAnimeSourceEnabled(context, SOURCE_ANIMELOVERZ)) result.add(SOURCE_ANIMELOVERZ);
        if (isAnimeSourceEnabled(context, SOURCE_DRAMORA)) result.add(SOURCE_DRAMORA);
        if (isAnimeSourceEnabled(context, SOURCE_ANICHIN)) result.add(SOURCE_ANICHIN);
        if (result.isEmpty()) result.add(SOURCE_DEFAULT);
        return result;
    }

    public static String getFirstEnabledAnimeSource(Context context) {
        ArrayList<String> sources = getEnabledAnimeSources(context);
        return sources.isEmpty() ? SOURCE_DEFAULT : sources.get(0);
    }

    private static boolean hasEnabledAnimeSource(Context context) {
        return isAnimeSourceEnabled(context, SOURCE_DEFAULT) || isAnimeSourceEnabled(context, SOURCE_ANIMEKU) || isAnimeSourceEnabled(context, SOURCE_ANIMELOVERZ) || isAnimeSourceEnabled(context, SOURCE_DRAMORA) || isAnimeSourceEnabled(context, SOURCE_ANICHIN);
    }

    public static String getVideoScale(Context context) {
        if (context == null) return VIDEO_SCALE_NORMAL;
        String scale = prefs(context).getString(KEY_VIDEO_SCALE, VIDEO_SCALE_NORMAL);
        if (VIDEO_SCALE_FIT.equals(scale) || VIDEO_SCALE_STRETCH.equals(scale)) return scale;
        return VIDEO_SCALE_NORMAL;
    }

    public static void setVideoScale(Context context, String scale) {
        if (context == null) return;
        String value = VIDEO_SCALE_NORMAL;
        if (VIDEO_SCALE_FIT.equals(scale) || VIDEO_SCALE_STRETCH.equals(scale)) value = scale;
        prefs(context).edit().putString(KEY_VIDEO_SCALE, value).apply();
    }

    public static String getVideoScaleLabel(String scale) {
        if (VIDEO_SCALE_FIT.equals(scale)) return "Fit";
        if (VIDEO_SCALE_STRETCH.equals(scale)) return "Stretch";
        return "Normal";
    }

    public static String nextVideoScale(String scale) {
        if (VIDEO_SCALE_NORMAL.equals(scale)) return VIDEO_SCALE_FIT;
        if (VIDEO_SCALE_FIT.equals(scale)) return VIDEO_SCALE_STRETCH;
        return VIDEO_SCALE_NORMAL;
    }

    public static int getVideoResizeMode(String scale) {
        if (VIDEO_SCALE_STRETCH.equals(scale)) return androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL;
        return androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
