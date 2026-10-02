package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class HistoryManager {
    private static final String PREF = "anime_watch_history";
    private static final String KEY = "items";

    public static void save(Context context, HistoryItem item) {
        if (context == null || item == null || item.videoUrl == null || item.videoUrl.trim().isEmpty()) return;

        ArrayList<HistoryItem> list = getHistory(context);
        fillMissingMetadata(item, list);
        String newKey = keyOf(item);

        for (int i = list.size() - 1; i >= 0; i--) {
            if (keyOf(list.get(i)).equals(newKey)) {
                list.remove(i);
            }
        }

        item.lastWatched = System.currentTimeMillis();
        if (item.duration < 0) item.duration = 0;
        if (item.position < 0) item.position = 0;
        if (item.duration > 0 && item.position > item.duration) item.position = item.duration;

        list.add(0, item);
        saveAll(context, list);
    }

    public static HistoryItem getByChannelId(Context context, int channelId) {
        return getByChannelId(context, AnimeSettingsManager.SOURCE_DEFAULT, channelId);
    }

    public static HistoryItem getByChannelId(Context context, String sourceId, int channelId) {
        if (context == null || channelId <= 0) return null;
        String requestedSource = sourceId == null || sourceId.trim().isEmpty() || !AnimeSettingsManager.isValidSource(sourceId)
                ? AnimeSettingsManager.SOURCE_DEFAULT
                : sourceId.trim();
        ArrayList<HistoryItem> list = getHistory(context);
        for (HistoryItem item : list) {
            if (item == null || item.channelId != channelId) continue;
            if (requestedSource.equals(item.sourceId)) return item;
        }
        return null;
    }

    public static HistoryItem getLatestForAnime(Context context, String sourceId, int categoryId, String slug, String categoryName) {
        if (context == null) return null;
        String source = normalizeSource(sourceId);
        HistoryItem latest = null;
        for (HistoryItem item : getHistory(context)) {
            if (!belongsToAnime(item, source, categoryId, slug, categoryName)) continue;
            if (latest == null || item.lastWatched > latest.lastWatched) latest = item;
        }
        return latest;
    }

    public static void updateAnimeMetadata(Context context, AnimePost post) {
        if (context == null || post == null) return;
        ArrayList<HistoryItem> list = getHistory(context);
        boolean changed = false;
        for (HistoryItem item : list) {
            if (!belongsToAnime(item, post.sourceId, post.categoryId, post.slug, post.categoryName)) continue;
            if (isUseful(post.imgUrl) && !post.imgUrl.trim().equals(item.imageUrl)) {
                item.imageUrl = post.imgUrl.trim();
                changed = true;
            }
            if (isUseful(post.categoryName) && !post.categoryName.trim().equals(item.categoryName)) {
                item.categoryName = post.categoryName.trim();
                changed = true;
            }
            if (post.categoryId > 0 && post.categoryId != item.categoryId) {
                item.categoryId = post.categoryId;
                changed = true;
            }
            if (isUseful(post.slug) && !post.slug.trim().equals(item.slug)) {
                item.slug = post.slug.trim();
                changed = true;
            }
        }
        if (changed) saveAll(context, list);
    }

    public static long getPositionForChannel(Context context, int channelId) {
        return getPositionForChannel(context, AnimeSettingsManager.SOURCE_DEFAULT, channelId);
    }

    public static long getPositionForChannel(Context context, String sourceId, int channelId) {
        HistoryItem item = getByChannelId(context, sourceId, channelId);
        if (item == null) return 0L;
        long position = Math.max(0L, item.position);
        long duration = Math.max(0L, item.duration);
        if (duration > 0 && position >= duration - 5000L) return duration;
        return position;
    }

    public static ArrayList<HistoryItem> getHistory(Context context) {
        ArrayList<HistoryItem> result = new ArrayList<>();
        if (context == null) return result;
        try {
            String raw = prefs(context).getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HistoryItem item = new HistoryItem(
                        o.optInt("channelId", -1),
                        o.optInt("categoryId", -1),
                        o.optString("categoryName", ""),
                        o.optString("title", ""),
                        o.optString("imageUrl", ""),
                        o.optString("videoUrl", ""),
                        o.optLong("position", 0L),
                        o.optLong("duration", 0L),
                        o.optLong("lastWatched", 0L),
                        o.optString("sourceId", AnimeSettingsManager.SOURCE_DEFAULT)
                );
                item.slug = o.optString("slug", "");
                if (!item.videoUrl.trim().isEmpty()) result.add(item);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static void remove(Context context, HistoryItem item) {
        if (context == null || item == null) return;
        ArrayList<HistoryItem> list = getHistory(context);
        String removeKey = keyOf(item);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (keyOf(list.get(i)).equals(removeKey)) {
                list.remove(i);
            }
        }
        saveAll(context, list);
    }

    public static void clear(Context context) {
        if (context != null) prefs(context).edit().putString(KEY, "[]").apply();
    }

    private static void saveAll(Context context, ArrayList<HistoryItem> list) {
        JSONArray arr = new JSONArray();
        try {
            for (HistoryItem item : list) {
                JSONObject o = new JSONObject();
                o.put("channelId", item.channelId);
                o.put("categoryId", item.categoryId);
                o.put("categoryName", item.categoryName);
                o.put("title", item.title);
                o.put("imageUrl", item.imageUrl);
                o.put("videoUrl", item.videoUrl);
                o.put("position", item.position);
                o.put("duration", item.duration);
                o.put("lastWatched", item.lastWatched);
                o.put("sourceId", item.sourceId == null ? AnimeSettingsManager.SOURCE_DEFAULT : item.sourceId);
                o.put("slug", item.slug == null ? "" : item.slug);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs(context).edit().putString(KEY, arr.toString()).apply();
    }

    private static void fillMissingMetadata(HistoryItem item, ArrayList<HistoryItem> list) {
        if (item == null || list == null || list.isEmpty()) return;
        for (HistoryItem existing : list) {
            if (!belongsToAnime(existing, normalizeSource(item.sourceId), item.categoryId, item.slug, item.categoryName)) continue;
            if (!isUseful(item.imageUrl) && isUseful(existing.imageUrl)) item.imageUrl = existing.imageUrl;
            if (!isUseful(item.categoryName) && isUseful(existing.categoryName)) item.categoryName = existing.categoryName;
            if (!isUseful(item.slug) && isUseful(existing.slug)) item.slug = existing.slug;
            if (item.categoryId <= 0 && existing.categoryId > 0) item.categoryId = existing.categoryId;
            if (isUseful(item.imageUrl) && isUseful(item.categoryName) && (isUseful(item.slug) || !AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(normalizeSource(item.sourceId)))) return;
        }
    }

    private static boolean belongsToAnime(HistoryItem item, String sourceId, int categoryId, String slug, String categoryName) {
        if (item == null || !normalizeSource(item.sourceId).equals(normalizeSource(sourceId))) return false;
        String requestedSlug = normalizeSlug(slug);
        String itemSlug = normalizeSlug(item.slug);
        if (!requestedSlug.isEmpty() && !itemSlug.isEmpty()) return requestedSlug.equals(itemSlug);
        if (categoryId > 0 && item.categoryId > 0) return categoryId == item.categoryId;
        String requestedName = normalizeText(categoryName);
        String itemName = normalizeText(item.categoryName);
        return !requestedName.isEmpty() && requestedName.equals(itemName);
    }

    private static String keyOf(HistoryItem item) {
        if (item == null) return "empty";
        String source = normalizeSource(item.sourceId);
        if (item.channelId > 0) return source + ":episode:" + item.channelId;
        return source + ":url:" + (item.videoUrl == null ? "" : item.videoUrl.trim());
    }

    private static String normalizeSource(String sourceId) {
        return AnimeSettingsManager.isValidSource(sourceId) ? sourceId : AnimeSettingsManager.SOURCE_DEFAULT;
    }

    private static String normalizeSlug(String value) {
        String slug = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        while (slug.startsWith("/")) slug = slug.substring(1);
        while (slug.endsWith("/")) slug = slug.substring(0, slug.length() - 1);
        return slug;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isUseful(String value) {
        return value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
