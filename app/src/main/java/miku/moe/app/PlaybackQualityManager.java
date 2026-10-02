package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class PlaybackQualityManager {
    public static final String QUALITY_SD = "SD";
    public static final String QUALITY_HD = "HD";
    public static final String QUALITY_FHD = "FHD";
    public static final String PLAYER_INTERNAL = "internal";
    public static final String PLAYER_CHOOSER = "chooser";
    public static final String PLAYER_VLC = "vlc";
    public static final String PLAYER_MPV = "mpv";

    private static final String PREF_NAME = "playback_quality_pref";
    private static final String KEY_QUALITY = "selected_quality";
    private static final String KEY_PLAYER = "selected_player";
    private static final String KEY_AUTO_LANDSCAPE = "auto_landscape";

    private PlaybackQualityManager() {}

    public static String getQuality(Context context) {
        if (context == null) return QUALITY_HD;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String quality = prefs.getString(KEY_QUALITY, QUALITY_HD);
        if (QUALITY_SD.equals(quality) || QUALITY_HD.equals(quality) || QUALITY_FHD.equals(quality)) return quality;
        return QUALITY_HD;
    }

    public static void setQuality(Context context, String quality) {
        if (context == null) return;
        if (!QUALITY_SD.equals(quality) && !QUALITY_HD.equals(quality) && !QUALITY_FHD.equals(quality)) quality = QUALITY_HD;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUALITY, quality)
                .apply();
    }

    public static String getQualityLabel(String quality) {
        if (QUALITY_SD.equals(quality)) return "SD 360p";
        if (QUALITY_FHD.equals(quality)) return "FHD 1080p";
        return "HD 720p";
    }

    public static String getPlayer(Context context) {
        if (context == null) return PLAYER_INTERNAL;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String player = prefs.getString(KEY_PLAYER, PLAYER_INTERNAL);
        if (PLAYER_INTERNAL.equals(player) || PLAYER_CHOOSER.equals(player) || PLAYER_VLC.equals(player) || PLAYER_MPV.equals(player)) return player;
        return PLAYER_INTERNAL;
    }

    public static void setPlayer(Context context, String player) {
        if (context == null) return;
        if (!PLAYER_INTERNAL.equals(player) && !PLAYER_CHOOSER.equals(player) && !PLAYER_VLC.equals(player) && !PLAYER_MPV.equals(player)) player = PLAYER_INTERNAL;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAYER, player)
                .apply();
    }

    public static String getPlayerLabel(String player) {
        if (PLAYER_CHOOSER.equals(player)) return "Pilih aplikasi";
        if (PLAYER_VLC.equals(player)) return "VLC";
        if (PLAYER_MPV.equals(player)) return "MPV";
        return "Internal Player";
    }

    public static boolean isAutoLandscape(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_LANDSCAPE, false);
    }

    public static void setAutoLandscape(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_LANDSCAPE, enabled)
                .apply();
    }
}
