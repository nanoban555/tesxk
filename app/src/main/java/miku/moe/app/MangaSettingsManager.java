package miku.moe.app;

import android.content.Context;
import java.util.ArrayList;

public final class MangaSettingsManager {
    private static final String PREFS = "miku_manga_settings";
    private static final String KEY_ENABLED = "manga_mode_enabled";
    private static final String KEY_GRID_COLUMNS = "manga_grid_columns";
    private static final String KEY_CHAPTER_LAYOUT = "manga_chapter_layout";
    private static final String KEY_READER_UI = "manga_reader_ui";
    private static final String KEY_DETAIL_UI = "manga_detail_ui";
    private static final String KEY_HOME_STYLE = "manga_home_style";
    private static final String KEY_READER_ZOOM = "manga_reader_zoom";
    private static final String KEY_READER_PHOTOVIEW_ZOOM = "manga_reader_photoview_zoom";
    private static final String KEY_READER_DOUBLE_TAP_ZOOM = "manga_reader_double_tap_zoom";
    private static final String KEY_READER_CROP_BORDER = "manga_reader_crop_border";
    private static final String KEY_READER_IMAGE_SCALE = "manga_reader_image_scale";
    private static final String KEY_READER_PAGE_TRANSITION = "manga_reader_page_transition";
    private static final String KEY_READER_INLINE_CHAPTER_PRELOAD = "manga_reader_inline_chapter_preload";
    private static final String KEY_READER_NEXT_CHAPTER_AUTO = "manga_reader_next_chapter_auto";
    private static final String KEY_READER_NEXT_CHAPTER_MANUAL = "manga_reader_next_chapter_manual";
    private static final String KEY_READER_NEXT_CHAPTER_THRESHOLD = "manga_reader_next_chapter_threshold";
    private static final String KEY_READER_NEXT_CHAPTER_DURATION = "manga_reader_next_chapter_duration";
    private static final String KEY_AUTO_SAVE_FAVORITE_HISTORY_IMAGES = "manga_auto_save_favorite_history_images";
    private static final String KEY_HIDE_LATEST_CHAPTER_LABEL = "manga_hide_latest_chapter_label";
    private static final String KEY_HIDE_TYPE_LABEL = "manga_hide_type_label";
    private static final String KEY_HIDE_ALL_LABELS = "manga_hide_all_labels";
    private static final String KEY_COMPACT_DATA_UI = "manga_compact_data_ui";
    private static final String KEY_BOLD_MANGA_TITLE = "manga_bold_manga_title";
    private static final String KEY_MANGA_SOURCE = "manga_source";
    private static final String KEY_HOME_V1_SOURCE = "manga_home_v1_source";
    private static final String KEY_MANGA_SOURCE_ENABLED_PREFIX = "manga_source_enabled_";
    private static final String KEY_MANGA_SOURCE_DOMAIN_PREFIX = "manga_source_domain_";
    private static final String KEY_DOH_PROVIDER = "network_doh_provider";
    private static android.content.Context appContext;

    public static final String READER_UI_DEFAULT = "default";
    public static final String READER_UI_V1 = "v1";
    public static final String READER_UI_V2 = "v2";
    public static final String DETAIL_UI_DEFAULT = "default";
    public static final String DETAIL_UI_V1 = "v1";
    public static final String DETAIL_UI_V2 = "v2";
    public static final String HOME_STYLE_DEFAULT = "default";
    public static final String HOME_STYLE_V1 = "v1";
    public static final String CHAPTER_LAYOUT_DEFAULT = "default";
    public static final String IMAGE_SCALE_FIT_WIDTH = "fit_width";
    public static final String IMAGE_SCALE_FIT_SCREEN = "fit_screen";
    public static final String IMAGE_SCALE_ORIGINAL = "original";
    public static final String CHAPTER_LAYOUT_GRID_2 = "grid_2";
    public static final String MANGA_SOURCE_KOMIKCAST = "komikcast";
    public static final String MANGA_SOURCE_SHINIGAMI = "shinigami";
    public static final String MANGA_SOURCE_DOUJINDESU = "doujindesu";
    public static final String MANGA_SOURCE_WESTMANGA = "westmanga";
    public static final String MANGA_SOURCE_BACAKOMIK = "bacakomik";
    public static final String MANGA_SOURCE_KOMIKINDO = "komikindo";
    public static final String MANGA_SOURCE_IKIRU = "ikiru";
    public static final String MANGA_SOURCE_KOMIKU = "komiku";
    public static final String MANGA_SOURCE_MANGASUSU = "mangasusu";
    public static final String MANGA_SOURCE_KOMIKU_ORG = "komiku_org";
    public static final String MANGA_SOURCE_COSMICSCANS = "cosmicscans";
    public static final String MANGA_SOURCE_KIRYUU_OFFICIAL = "kiryuu_official";
    public static final String MANGA_SOURCE_NATSU = "natsu";
    public static final String MANGA_SOURCE_AINZSCANSS = "ainzscanss";
    public static final String MANGA_SOURCE_APKOMIK = "apkomik";
    public static final String MANGA_SOURCE_COMICASO = "comicaso";
    public static final String MANGA_SOURCE_KUMOPOI = "kumopoi";
    public static final String MANGA_SOURCE_CROTPEDIA = "crotpedia";
    public static final String MANGA_SOURCE_NGOMIK = "ngomik";
    public static final String MANGA_SOURCE_MGKOMIK = "mgkomik";
    public static final String MANGA_SOURCE_KOMIKTAP = "komiktap";
    public static final String MANGA_SOURCE_MANHWAINDO = "manhwaindo";
    public static final String MANGA_SOURCE_SOULSCANS = "soulscans";
    public static final String MANGA_SOURCE_KUROMANGA = "kuromanga";
    public static final String MANGA_SOURCE_ISEKAIKOMIK = "isekaikomik";
    public static final String MANGA_SOURCE_LUVYAA = "luvyaa";
    public static final String MANGA_SOURCE_SEKTEDOUJIN = "sektedoujin";
    public static final String MANGA_SOURCE_Ainzscans = MANGA_SOURCE_AINZSCANSS;
    public static final int DOH_DISABLED = -1;
    public static final int DOH_CLOUDFLARE = 1;
    public static final int DOH_GOOGLE = 2;
    public static final int DOH_ADGUARD = 3;
    public static final int DOH_QUAD9 = 4;
    public static final int DOH_ALIDNS = 5;
    public static final int DOH_DNSPOD = 6;
    public static final int DOH_360 = 7;
    public static final int DOH_QUAD101 = 8;
    public static final int DOH_MULLVAD = 9;
    public static final int DOH_CONTROLD = 10;
    public static final int DOH_NJALLA = 11;
    public static final int DOH_SHECAN = 12;

    private MangaSettingsManager() {}

    public static void init(Context context) { if (context != null) appContext = context.getApplicationContext(); }

    public static boolean isMangaModeEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false); }
    public static void setMangaModeEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply(); }
    public static int getMangaGridColumns(Context context) { int columns = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GRID_COLUMNS, 3); return columns == 3 ? 3 : 2; }
    public static void setMangaGridColumns(Context context, int columns) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_GRID_COLUMNS, columns == 3 ? 3 : 2).apply(); }
    public static String getChapterLayout(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHAPTER_LAYOUT, CHAPTER_LAYOUT_DEFAULT); }
    public static void setChapterLayout(Context context, String layout) { String value = CHAPTER_LAYOUT_GRID_2.equals(layout) ? CHAPTER_LAYOUT_GRID_2 : CHAPTER_LAYOUT_DEFAULT; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHAPTER_LAYOUT, value).apply(); }
    public static boolean isChapterGrid2(Context context) { return CHAPTER_LAYOUT_GRID_2.equals(getChapterLayout(context)); }
    public static String getDetailUi(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DETAIL_UI, DETAIL_UI_V2);
        if (DETAIL_UI_DEFAULT.equals(value)) return DETAIL_UI_DEFAULT;
        if (DETAIL_UI_V1.equals(value)) return DETAIL_UI_V1;
        if (DETAIL_UI_V2.equals(value)) return DETAIL_UI_V2;
        return DETAIL_UI_V2;
    }
    public static void setDetailUi(Context context, String value) {
        String selected = DETAIL_UI_V2;
        if (DETAIL_UI_DEFAULT.equals(value)) selected = DETAIL_UI_DEFAULT;
        else if (DETAIL_UI_V1.equals(value)) selected = DETAIL_UI_V1;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DETAIL_UI, selected).commit();
    }
    public static boolean isDetailUiV1(Context context) { return DETAIL_UI_V1.equals(getDetailUi(context)); }
    public static boolean isDetailUiV2(Context context) { return DETAIL_UI_V2.equals(getDetailUi(context)); }
    public static String getDetailUiLabel(Context context) {
        String value = getDetailUi(context);
        if (DETAIL_UI_V2.equals(value)) return "Detail v2";
        if (DETAIL_UI_V1.equals(value)) return "Detail v1";
        return "Default";
    }

    public static String getHomeStyle(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOME_STYLE, HOME_STYLE_DEFAULT);
        return HOME_STYLE_V1.equals(value) ? HOME_STYLE_V1 : HOME_STYLE_DEFAULT;
    }

    public static void setHomeStyle(Context context, String value) {
        String selected = HOME_STYLE_V1.equals(value) ? HOME_STYLE_V1 : HOME_STYLE_DEFAULT;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOME_STYLE, selected).apply();
    }

    public static boolean isHomeStyleV1(Context context) { return HOME_STYLE_V1.equals(getHomeStyle(context)); }

    public static String getHomeStyleLabel(Context context) { return isHomeStyleV1(context) ? "Style Home v1" : "Style Home"; }

    public static String getHomeV1Source(Context context) {
        String fallback = getMangaSource(context);
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOME_V1_SOURCE, fallback);
        return isValidSource(value) ? value : fallback;
    }

    public static void setHomeV1Source(Context context, String source) {
        String value = isValidSource(source) ? source : MANGA_SOURCE_KOMIKCAST;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOME_V1_SOURCE, value).apply();
    }

    public static String getHomeV1SourceLabel(Context context) { return MangaSourceFactory.labelForSourceId(getHomeV1Source(context)); }

    public static String getMangaSource(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MANGA_SOURCE, MANGA_SOURCE_KOMIKCAST);
        if (!isValidSource(value) || !isMangaSourceEnabled(context, value)) value = getFirstEnabledMangaSource(context);
        return value;
    }

    public static void setMangaSource(Context context, String source) {
        String value = isValidSource(source) ? source : MANGA_SOURCE_KOMIKCAST;
        if (!isMangaSourceEnabled(context, value)) value = getFirstEnabledMangaSource(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MANGA_SOURCE, value).apply();
    }

    public static boolean isValidSource(String source) {
        if (MANGA_SOURCE_KOMIKCAST.equals(source)) return true;
        if (MANGA_SOURCE_SHINIGAMI.equals(source)) return true;
        if (MANGA_SOURCE_DOUJINDESU.equals(source)) return true;
        if (MANGA_SOURCE_WESTMANGA.equals(source)) return true;
        if (MANGA_SOURCE_BACAKOMIK.equals(source)) return true;
        if (MANGA_SOURCE_KOMIKINDO.equals(source)) return true;
        if (MANGA_SOURCE_IKIRU.equals(source)) return true;
        if (MANGA_SOURCE_KOMIKU.equals(source)) return true;
        if (MANGA_SOURCE_MANGASUSU.equals(source)) return true;
        if (MANGA_SOURCE_KOMIKU_ORG.equals(source)) return true;
        if (MANGA_SOURCE_COSMICSCANS.equals(source)) return true;
        if (MANGA_SOURCE_KIRYUU_OFFICIAL.equals(source)) return true;
        if (MANGA_SOURCE_NATSU.equals(source)) return true;
        if (MANGA_SOURCE_AINZSCANSS.equals(source)) return true;
        if (MANGA_SOURCE_APKOMIK.equals(source)) return true;
        if (MANGA_SOURCE_COMICASO.equals(source)) return true;
        if (MANGA_SOURCE_KUMOPOI.equals(source)) return true;
        if (MANGA_SOURCE_CROTPEDIA.equals(source)) return true;
        if (MANGA_SOURCE_NGOMIK.equals(source)) return true;
        if (MANGA_SOURCE_MGKOMIK.equals(source)) return true;
        if (MANGA_SOURCE_KOMIKTAP.equals(source)) return true;
        if (MANGA_SOURCE_MANHWAINDO.equals(source)) return true;
        if (MANGA_SOURCE_SOULSCANS.equals(source)) return true;
        if (MANGA_SOURCE_KUROMANGA.equals(source)) return true;
        if (MANGA_SOURCE_ISEKAIKOMIK.equals(source)) return true;
        if (MANGA_SOURCE_LUVYAA.equals(source)) return true;
        return MANGA_SOURCE_SEKTEDOUJIN.equals(source);
    }

    public static boolean isMangaSourceEnabled(Context context, String source) {
        if (!isValidSource(source)) return false;
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_MANGA_SOURCE_ENABLED_PREFIX + source, isDefaultEnabledSource(source));
    }

    public static void setMangaSourceEnabled(Context context, String source, boolean enabled) {
        if (!isValidSource(source)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MANGA_SOURCE_ENABLED_PREFIX + source, enabled).apply();
        if (!hasEnabledMangaSource(context)) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MANGA_SOURCE_ENABLED_PREFIX + MANGA_SOURCE_KOMIKCAST, true).putString(KEY_MANGA_SOURCE, MANGA_SOURCE_KOMIKCAST).apply();
        else if (!isMangaSourceEnabled(context, getMangaSourceRaw(context))) setMangaSource(context, getFirstEnabledMangaSource(context));
    }

    public static ArrayList<String> getEnabledMangaSources(Context context) {
        ArrayList<String> result = new ArrayList<>();
        for (String source : MangaSourceFactory.allSourceIds()) if (isMangaSourceEnabled(context, source)) result.add(source);
        if (result.isEmpty()) result.add(MANGA_SOURCE_KOMIKCAST);
        return result;
    }

    public static String getFirstEnabledMangaSource(Context context) {
        ArrayList<String> sources = getEnabledMangaSources(context);
        return sources.isEmpty() ? MANGA_SOURCE_KOMIKCAST : sources.get(0);
    }

    private static boolean hasEnabledMangaSource(Context context) {
        for (String source : MangaSourceFactory.allSourceIds()) if (isMangaSourceEnabled(context, source)) return true;
        return false;
    }

    private static String getMangaSourceRaw(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MANGA_SOURCE, MANGA_SOURCE_KOMIKCAST);
        return value;
    }

    public static String getMangaSourceLabel(Context context) { return MangaSourceFactory.labelForSourceId(getMangaSource(context)); }

    public static boolean isDefaultEnabledSource(String source) { return MANGA_SOURCE_KOMIKCAST.equals(source) || MANGA_SOURCE_SHINIGAMI.equals(source) || MANGA_SOURCE_DOUJINDESU.equals(source) || MANGA_SOURCE_MGKOMIK.equals(source); }

    public static String getDefaultDomain(String source) {
        if (MANGA_SOURCE_SHINIGAMI.equals(source)) return "https://11.shinigami.asia";
        if (MANGA_SOURCE_DOUJINDESU.equals(source)) return "https://doujin.desu.xxx";
        if (MANGA_SOURCE_WESTMANGA.equals(source)) return "https://v1.westmanga.my";
        if (MANGA_SOURCE_BACAKOMIK.equals(source)) return "https://bacakomik.my";
        if (MANGA_SOURCE_KOMIKINDO.equals(source)) return "https://komikindo.ch";
        if (MANGA_SOURCE_IKIRU.equals(source)) return "https://08.ikiru.wtf";
        if (MANGA_SOURCE_KOMIKU.equals(source)) return "https://01.komiku.asia";
        if (MANGA_SOURCE_MANGASUSU.equals(source)) return "https://mangasusuku.com";
        if (MANGA_SOURCE_KOMIKU_ORG.equals(source)) return "https://komiku.org";
        if (MANGA_SOURCE_COSMICSCANS.equals(source)) return "https://04.cosmicscans.to";
        if (MANGA_SOURCE_KIRYUU_OFFICIAL.equals(source)) return "https://v7.kiryuu.to";
        if (MANGA_SOURCE_NATSU.equals(source)) return "https://natsu.one";
        if (MANGA_SOURCE_AINZSCANSS.equals(source)) return "https://v3.ainzscans01.com";
        if (MANGA_SOURCE_APKOMIK.equals(source)) return "https://01.apkomik.com";
        if (MANGA_SOURCE_COMICASO.equals(source)) return "https://v3.comicaso.pro";
        if (MANGA_SOURCE_KUMOPOI.equals(source)) return "https://beta.kumopoi.com";
        if (MANGA_SOURCE_CROTPEDIA.equals(source)) return "https://crotpedia.net";
        if (MANGA_SOURCE_NGOMIK.equals(source)) return "https://02.ngomik.cc";
        if (MANGA_SOURCE_MGKOMIK.equals(source)) return "https://web1.mgkomik.cc";
        if (MANGA_SOURCE_KOMIKTAP.equals(source)) return "https://komiktap.info";
        if (MANGA_SOURCE_MANHWAINDO.equals(source)) return "https://www.manhwaindo.my";
        if (MANGA_SOURCE_SOULSCANS.equals(source)) return "https://v1.soulscans.org";
        if (MANGA_SOURCE_KUROMANGA.equals(source)) return "https://kuromanga.id";
        if (MANGA_SOURCE_ISEKAIKOMIK.equals(source)) return "https://ch1.isekaikomik.site";
        if (MANGA_SOURCE_LUVYAA.equals(source)) return "https://v5.luvyaa.co";
        if (MANGA_SOURCE_SEKTEDOUJIN.equals(source)) return "https://sektedoujin.cc";
        return "https://v5.voratoon.com";
    }

    public static String getSourceDomain(Context context, String source) {
        Context c = context != null ? context : appContext;
        String def = getDefaultDomain(source);
        if (c == null) return def;
        String value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MANGA_SOURCE_DOMAIN_PREFIX + source, def);
        if (MANGA_SOURCE_DOUJINDESU.equals(source)) return def;
        if (value == null || value.trim().isEmpty()) return def;
        value = value.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (isExpiredDefaultDomain(source, value)) return def;
        return value;
    }

    public static String getSourceDomain(String source) { return getSourceDomain(null, source); }

    private static boolean isExpiredDefaultDomain(String source, String value) {
        if (MANGA_SOURCE_IKIRU.equals(source) && ("https://05.ikiru.wtf".equals(value) || "http://05.ikiru.wtf".equals(value) || "https://06.ikiru.wtf".equals(value) || "http://06.ikiru.wtf".equals(value) || "https://07.ikiru.wtf".equals(value) || "http://07.ikiru.wtf".equals(value))) return true;
        if (MANGA_SOURCE_KOMIKCAST.equals(source) && (value.matches("(?i)https?://v[0-9]+\\.voratoon\\.com") && !value.toLowerCase(java.util.Locale.ROOT).endsWith("://v5.voratoon.com"))) return true;
        if (MANGA_SOURCE_KOMIKCAST.equals(source) && (value.contains("komikcast.fit") || value.contains("komikcast.cc") || value.contains("komikcast.com") || "https://v1.voratoon.com".equals(value) || "http://v1.voratoon.com".equals(value) || "https://v2.voratoon.com".equals(value) || "http://v2.voratoon.com".equals(value))) return true;
        if (MANGA_SOURCE_WESTMANGA.equals(source) && ("https://westmanga.co".equals(value) || "http://westmanga.co".equals(value))) return true;
        if (MANGA_SOURCE_KOMIKU.equals(source) && ("https://komiku.org".equals(value) || "http://komiku.org".equals(value))) return true;
        if (MANGA_SOURCE_NATSU.equals(source) && ("https://natsu.tv".equals(value) || "http://natsu.tv".equals(value))) return true;
        if (MANGA_SOURCE_KOMIKINDO.equals(source) && ("https://komikindo.ch".equals(value) || "http://komikindo.ch".equals(value))) return true;
        if (MANGA_SOURCE_SHINIGAMI.equals(source) && ("https://c.shinigami.asia".equals(value) || "http://c.shinigami.asia".equals(value) || "https://g.shinigami.asia".equals(value) || "http://g.shinigami.asia".equals(value))) return true;
        if (MANGA_SOURCE_COSMICSCANS.equals(source) && ("https://lc1.cosmicscans.to".equals(value) 
    || "http://lc1.cosmicscans.to".equals(value) 
    || "https://lc2.cosmicscans.to".equals(value) 
    || "http://lc2.cosmicscans.to".equals(value) 
    || "http://01.cosmicscans.to".equals(value) 
    || "https://01.cosmicscans.to".equals(value)
    || "https://02.cosmicscans.to".equals(value)
    || "https://03.cosmicscans.to".equals(value)
    || "http://03.cosmicscans.to".equals(value)
    || "https://03.cosmicscans.to/".equals(value))) return true;
        if (MANGA_SOURCE_KIRYUU_OFFICIAL.equals(source) && ("https://v5.kiryuu.to".equals(value) || "http://v5.kiryuu.to".equals(value) || "https://v6.kiryuu.to".equals(value) || "http://v6.kiryuu.to".equals(value))) return true;
        if (MANGA_SOURCE_AINZSCANSS.equals(source) && ("https://v1.ainzscans01.com".equals(value) || "http://v1.ainzscans01.com".equals(value) || "https://v2.ainzscans01.com".equals(value) || "http://v2.ainzscans01.com".equals(value))) return true;
        if (MANGA_SOURCE_KUMOPOI.equals(source) && ("https://kumopoi.org".equals(value) || "http://kumopoi.org".equals(value))) return true;
        return false;
    }

    public static void setSourceDomain(Context context, String source, String domain) {
        if (context == null || !isValidSource(source)) return;
        String value = domain == null ? "" : domain.trim();
        if (!value.isEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MANGA_SOURCE_DOMAIN_PREFIX + source, value).apply();
    }

    public static void resetSourceDomain(Context context, String source) { if (context != null && isValidSource(source)) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_MANGA_SOURCE_DOMAIN_PREFIX + source).apply(); }

    public static String getReaderUi(Context context) { String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_READER_UI, READER_UI_V1); if (READER_UI_V2.equals(value) || READER_UI_V1.equals(value) || READER_UI_DEFAULT.equals(value)) return value; return READER_UI_V1; }
    public static void setReaderUi(Context context, String ui) { String value = READER_UI_V2.equals(ui) ? READER_UI_V2 : READER_UI_V1.equals(ui) ? READER_UI_V1 : READER_UI_DEFAULT; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_READER_UI, value).apply(); }
    public static String getReaderUiLabel(Context context) { String value = getReaderUi(context); if (READER_UI_V2.equals(value)) return "UI Baca manga v2"; if (READER_UI_V1.equals(value)) return "UI Baca manga v1"; return "Default"; }
    public static boolean isReaderZoomEnabled(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getBoolean(KEY_READER_PHOTOVIEW_ZOOM, false) && prefs.getBoolean(KEY_READER_ZOOM, true);
    }
    public static void setReaderZoomEnabled(Context context, boolean enabled) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_ZOOM, enabled);
        if (enabled) editor.putBoolean(KEY_READER_PHOTOVIEW_ZOOM, false);
        editor.apply();
    }
    public static boolean isReaderPhotoViewZoomEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READER_PHOTOVIEW_ZOOM, false); }
    public static void setReaderPhotoViewZoomEnabled(Context context, boolean enabled) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_PHOTOVIEW_ZOOM, enabled);
        if (enabled) editor.putBoolean(KEY_READER_ZOOM, false);
        editor.apply();
    }
    public static boolean isReaderDoubleTapZoomEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READER_DOUBLE_TAP_ZOOM, true); }
    public static void setReaderDoubleTapZoomEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_DOUBLE_TAP_ZOOM, enabled).apply(); }
    public static boolean isReaderCropBorderEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READER_CROP_BORDER, false); }
    public static void setReaderCropBorderEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_CROP_BORDER, enabled).apply(); }
    public static boolean isReaderPageTransitionEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READER_PAGE_TRANSITION, true); }
    public static void setReaderPageTransitionEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_PAGE_TRANSITION, enabled).apply(); }
    public static String getReaderImageScale(Context context) { String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_READER_IMAGE_SCALE, IMAGE_SCALE_FIT_WIDTH); if (IMAGE_SCALE_FIT_SCREEN.equals(value) || IMAGE_SCALE_ORIGINAL.equals(value) || IMAGE_SCALE_FIT_WIDTH.equals(value)) return value; return IMAGE_SCALE_FIT_WIDTH; }
    public static void setReaderImageScale(Context context, String scale) { String value = IMAGE_SCALE_FIT_SCREEN.equals(scale) ? IMAGE_SCALE_FIT_SCREEN : IMAGE_SCALE_ORIGINAL.equals(scale) ? IMAGE_SCALE_ORIGINAL : IMAGE_SCALE_FIT_WIDTH; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_READER_IMAGE_SCALE, value).apply(); }
    public static String getReaderImageScaleLabel(Context context) { String value = getReaderImageScale(context); if (IMAGE_SCALE_FIT_SCREEN.equals(value)) return "Fit screen"; if (IMAGE_SCALE_ORIGINAL.equals(value)) return "Original"; return "Fit width"; }
    public static void normalizeReaderChapterPreloadModes(Context context) {
        if (context == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean inline = prefs.getBoolean(KEY_READER_INLINE_CHAPTER_PRELOAD, true);
        boolean auto = prefs.getBoolean(KEY_READER_NEXT_CHAPTER_AUTO, false);
        boolean manual = prefs.getBoolean(KEY_READER_NEXT_CHAPTER_MANUAL, false);
        if (inline && (auto || manual)) {
            prefs.edit().putBoolean(KEY_READER_NEXT_CHAPTER_AUTO, false).putBoolean(KEY_READER_NEXT_CHAPTER_MANUAL, false).apply();
            return;
        }
        if (auto && manual) prefs.edit().putBoolean(KEY_READER_NEXT_CHAPTER_MANUAL, false).apply();
    }

    public static boolean isReaderInlineChapterPreloadEnabled(Context context) { normalizeReaderChapterPreloadModes(context); return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READER_INLINE_CHAPTER_PRELOAD, true); }
    public static void setReaderInlineChapterPreloadEnabled(Context context, boolean enabled) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_INLINE_CHAPTER_PRELOAD, enabled);
        if (enabled) editor.putBoolean(KEY_READER_NEXT_CHAPTER_AUTO, false).putBoolean(KEY_READER_NEXT_CHAPTER_MANUAL, false);
        editor.apply();
    }
    public static boolean isReaderNextChapterAutoEnabled(Context context) { normalizeReaderChapterPreloadModes(context); return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READER_NEXT_CHAPTER_AUTO, false); }
    public static void setReaderNextChapterAutoEnabled(Context context, boolean enabled) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_NEXT_CHAPTER_AUTO, enabled);
        if (enabled) editor.putBoolean(KEY_READER_INLINE_CHAPTER_PRELOAD, false).putBoolean(KEY_READER_NEXT_CHAPTER_MANUAL, false);
        editor.apply();
    }
    public static boolean isReaderNextChapterManualEnabled(Context context) { normalizeReaderChapterPreloadModes(context); return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READER_NEXT_CHAPTER_MANUAL, false); }
    public static void setReaderNextChapterManualEnabled(Context context, boolean enabled) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READER_NEXT_CHAPTER_MANUAL, enabled);
        if (enabled) editor.putBoolean(KEY_READER_INLINE_CHAPTER_PRELOAD, false).putBoolean(KEY_READER_NEXT_CHAPTER_AUTO, false);
        editor.apply();
    }
    public static int getReaderNextChapterThreshold(Context context) { int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_READER_NEXT_CHAPTER_THRESHOLD, 2); return value < 1 ? 2 : Math.min(value, 10); }
    public static void setReaderNextChapterThreshold(Context context, int value) { int safe = value < 1 ? 2 : Math.min(value, 10); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_READER_NEXT_CHAPTER_THRESHOLD, safe).apply(); }
    public static String getReaderNextChapterThresholdText(Context context) { return String.valueOf(getReaderNextChapterThreshold(context)); }
    public static int getReaderNextChapterDurationSeconds(Context context) { int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_READER_NEXT_CHAPTER_DURATION, 3); return value < 1 ? 3 : Math.min(value, 30); }
    public static long getReaderNextChapterDurationMillis(Context context) { return getReaderNextChapterDurationSeconds(context) * 1000L; }
    public static void setReaderNextChapterDurationSeconds(Context context, int value) { int safe = value < 1 ? 3 : Math.min(value, 30); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_READER_NEXT_CHAPTER_DURATION, safe).apply(); }
    public static String getReaderNextChapterDurationText(Context context) { return String.valueOf(getReaderNextChapterDurationSeconds(context)); }
    public static boolean isAutoSaveFavoriteHistoryImagesEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_SAVE_FAVORITE_HISTORY_IMAGES, true); }
    public static void setAutoSaveFavoriteHistoryImagesEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_SAVE_FAVORITE_HISTORY_IMAGES, enabled).apply(); }
    public static boolean isHideLatestChapterLabelEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDE_LATEST_CHAPTER_LABEL, true); }
    public static void setHideLatestChapterLabelEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE_LATEST_CHAPTER_LABEL, enabled).apply(); }
    public static boolean isHideTypeLabelEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDE_TYPE_LABEL, true); }
    public static void setHideTypeLabelEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE_TYPE_LABEL, enabled).apply(); }
    public static boolean isHideAllMangaLabelsEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDE_ALL_LABELS, true); }
    public static void setHideAllMangaLabelsEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE_ALL_LABELS, enabled).apply(); }
    public static boolean isCompactMangaDataUiEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMPACT_DATA_UI, false); }
    public static void setCompactMangaDataUiEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPACT_DATA_UI, enabled).apply(); }
    public static boolean isBoldMangaTitleEnabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BOLD_MANGA_TITLE, true); }
    public static void setBoldMangaTitleEnabled(Context context, boolean enabled) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BOLD_MANGA_TITLE, enabled).apply(); }
    public static boolean shouldHideLatestChapterLabel(Context context) { Context c = context != null ? context : appContext; return c != null && (isHideAllMangaLabelsEnabled(c) || isHideLatestChapterLabelEnabled(c)); }
    public static boolean shouldHideTypeLabel(Context context) { Context c = context != null ? context : appContext; return c != null && (isHideAllMangaLabelsEnabled(c) || isHideTypeLabelEnabled(c)); }
    public static boolean shouldLoadLatestChapterLabel(Context context) { return !shouldHideLatestChapterLabel(context); }
    public static boolean shouldLoadTypeLabel(Context context) { return !shouldHideTypeLabel(context); }
    public static boolean shouldLoadLatestChapterLabel() { return shouldLoadLatestChapterLabel(null); }
    public static boolean shouldLoadTypeLabel() { return shouldLoadTypeLabel(null); }
    public static int getDohProvider(Context context) {
        if (context == null) return DOH_DISABLED;
        int provider = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DOH_PROVIDER, DOH_DISABLED);
        return isValidDohProvider(provider) ? provider : DOH_DISABLED;
    }

    public static void setDohProvider(Context context, int provider) {
        if (context == null) return;
        int value = isValidDohProvider(provider) ? provider : DOH_DISABLED;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_DOH_PROVIDER, value).apply();
        NetworkDohManager.refresh();
    }

    public static boolean isValidDohProvider(int provider) {
        return provider == DOH_DISABLED || provider == DOH_CLOUDFLARE || provider == DOH_GOOGLE || provider == DOH_ADGUARD || provider == DOH_QUAD9 || provider == DOH_ALIDNS || provider == DOH_DNSPOD || provider == DOH_360 || provider == DOH_QUAD101 || provider == DOH_MULLVAD || provider == DOH_CONTROLD || provider == DOH_NJALLA || provider == DOH_SHECAN;
    }

    public static String getDohProviderLabel(Context context) {
        int provider = getDohProvider(context);
        if (provider == DOH_CLOUDFLARE) return "Cloudflare";
        if (provider == DOH_GOOGLE) return "Google";
        if (provider == DOH_ADGUARD) return "AdGuard";
        if (provider == DOH_QUAD9) return "Quad9";
        if (provider == DOH_ALIDNS) return "AliDNS";
        if (provider == DOH_DNSPOD) return "DNSPod";
        if (provider == DOH_360) return "360";
        if (provider == DOH_QUAD101) return "Quad 101";
        if (provider == DOH_MULLVAD) return "Mullvad";
        if (provider == DOH_CONTROLD) return "Control D";
        if (provider == DOH_NJALLA) return "Njalla";
        if (provider == DOH_SHECAN) return "Shecan";
        return "Nonaktif";
    }
}

