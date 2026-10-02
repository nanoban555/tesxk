package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class MangaFavoriteManager {
    private static final String PREFS = "miku_manga_favorites";
    private static final String KEY_ITEMS = "items";
    private static final String MIGRATION_PREFS = "miku_manga_room_migration";
    private static final String DETAIL_CACHE_PREFS = "miku_manga_detail_cache";
    private static final String KEY_MIGRATED = "favorites_migrated";
    private static final String REMOVED_POSITION_PREFIX = "removed_position_";
    private static final String BADGE_PREFS = "miku_manga_favorite_badges";
    private static final String KEY_BADGES = "chapter_increase_badges";
    private static final String AES_SECRET = "Miku01v-Manga-Favorite-Backup-Key";
    private MangaFavoriteManager() {}

    public static final class ChapterBadge {
        public final int base;
        public final int added;
        public ChapterBadge(int base, int added) { this.base = base; this.added = added; }
    }

    public static Map<String, ChapterBadge> getChapterIncreaseBadges(Context c) {
        Map<String, ChapterBadge> out = new HashMap<>();
        if (c == null) return out;
        try {
            JSONObject root = new JSONObject(badgePrefs(c).getString(KEY_BADGES, "{}"));
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                ChapterBadge badge = parseBadge(root.optJSONObject(key));
                if (badge != null) out.put(key, badge);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static ChapterBadge getChapterIncreaseBadge(Context c, String key) {
        if (c == null || key == null || key.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(badgePrefs(c).getString(KEY_BADGES, "{}"));
            return parseBadge(root.optJSONObject(key.trim()));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void setChapterIncreaseBadge(Context c, String key, int base, int added) {
        if (c == null || key == null || key.trim().isEmpty() || added <= 0) return;
        try {
            JSONObject root = new JSONObject(badgePrefs(c).getString(KEY_BADGES, "{}"));
            JSONObject o = new JSONObject();
            o.put("base", Math.max(0, base));
            o.put("added", Math.max(1, added));
            root.put(key.trim(), o);
            badgePrefs(c).edit().putString(KEY_BADGES, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void removeChapterIncreaseBadge(Context c, String key) {
        if (c == null || key == null || key.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(badgePrefs(c).getString(KEY_BADGES, "{}"));
            if (root.has(key.trim())) {
                root.remove(key.trim());
                badgePrefs(c).edit().putString(KEY_BADGES, root.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    public static void clearChapterIncreaseBadges(Context c) {
        if (c == null) return;
        try { badgePrefs(c).edit().remove(KEY_BADGES).apply(); } catch (Exception ignored) {}
    }

    private static ChapterBadge parseBadge(JSONObject o) {
        if (o == null) return null;
        int added = o.optInt("added", 0);
        if (added <= 0) return null;
        return new ChapterBadge(o.optInt("base", 0), added);
    }

    private static SharedPreferences badgePrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isFavorite(Context c, String slug) {
        if (c == null || slug == null) return false;
        String safeSlug = slug.trim();
        if (safeSlug.isEmpty()) return false;
        String sourceId = MangaSettingsManager.getMangaSource(c);
        ensureMigrated(c);
        return dao(c).getFavorite(key(sourceId, safeSlug)) != null;
    }

    public static boolean isFavorite(Context c, MangaPost post) {
        if (c == null || post == null || post.slug == null) return false;
        String safeSlug = post.slug.trim();
        if (safeSlug.isEmpty()) return false;
        ensureMigrated(c);
        return dao(c).getFavorite(key(post.getSourceId(), safeSlug)) != null;
    }

    public static void toggle(Context c, MangaPost post) {
        if (isFavorite(c, post)) remove(c, post); else add(c, post);
    }

    public static void remove(Context c, MangaPost post) {
        if (c == null || post == null || post.slug == null) return;
        String safeSlug = post.slug.trim();
        if (safeSlug.isEmpty()) return;
        ensureMigrated(c);
        rememberRemovedPosition(c, post.getSourceId(), safeSlug);
        dao(c).deleteFavorite(key(post.getSourceId(), safeSlug));
        p(c).edit().putString(KEY_ITEMS, toJson(getFavorites(c)).toString()).apply();
        MangaRoomEvents.notifyChanged();
    }

    public static void add(Context c, MangaPost post) {
        try {
            if (c == null || post == null || post.slug == null) return;
            String safeSlug = post.slug.trim();
            if (safeSlug.isEmpty()) return;
            post.slug = safeSlug;
            ensureMigrated(c);
            ArrayList<MangaPost> all = getFavorites(c);
            int existingIndex = -1;
            for (int i = 0; i < all.size(); i++) {
                MangaPost current = all.get(i);
                if (current != null && current.slug.equals(post.slug) && current.getSourceId().equals(post.getSourceId())) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex >= 0) {
                all.set(existingIndex, post);
            } else {
                int restoreIndex = removedPosition(c, post.getSourceId(), safeSlug);
                if (restoreIndex >= 0) {
                    all.add(Math.min(restoreIndex, all.size()), post);
                    clearRemovedPosition(c, post.getSourceId(), safeSlug);
                } else {
                    all.add(0, post);
                }
            }
            persist(c, all);
            saveCoverIfEnabled(c, post);
            MangaRoomEvents.notifyChanged();
        } catch (Exception ignored) {}
    }

    public static void remove(Context c, String slug) {
        if (c == null || slug == null) return;
        String safeSlug = slug.trim();
        if (safeSlug.isEmpty()) return;
        String sourceId = MangaSettingsManager.getMangaSource(c);
        ensureMigrated(c);
        rememberRemovedPosition(c, sourceId, safeSlug);
        dao(c).deleteFavorite(key(sourceId, safeSlug));
        p(c).edit().putString(KEY_ITEMS, toJson(getFavorites(c)).toString()).apply();
        MangaRoomEvents.notifyChanged();
    }

    public static ArrayList<MangaPost> getFavorites(Context c) {
        ArrayList<MangaPost> out = new ArrayList<>();
        if (c == null) return out;
        ensureMigrated(c);
        ArrayList<MangaPost> prefItems = parse(p(c).getString(KEY_ITEMS, "[]"));
        try {
            List<MangaFavoriteEntity> rows = dao(c).getFavorites();
            for (MangaFavoriteEntity row : rows) {
                MangaPost post = toPost(row);
                if (post != null && post.slug != null && !post.slug.isEmpty()) {
                    hydrateFavoritePost(c, post, prefItems);
                    out.add(post);
                }
            }
        } catch (Exception ignored) {}
        if (out.isEmpty() && !prefItems.isEmpty() && !migrationPrefs(c).getBoolean(KEY_MIGRATED, false)) {
            out.addAll(prefItems);
            try { persist(c, out); } catch (Exception ignored) {}
        }
        return out;
    }

    public static String exportEncrypted(Context c) throws Exception {
        if (c == null) return "const MIKU_MANGA_FAVORITES_AES = \"\";";
        return "const MIKU_MANGA_FAVORITES_AES = \"" + encrypt(toJson(getFavorites(c)).toString()) + "\";";
    }

    public static void importEncrypted(Context c, String fileContent) throws Exception {
        if (c == null) return;
        String data = fileContent == null ? "" : fileContent.trim();
        int q1 = data.indexOf('"');
        int q2 = data.lastIndexOf('"');
        if (q1 >= 0 && q2 > q1) data = data.substring(q1 + 1, q2);
        ArrayList<MangaPost> list = parseStrict(decrypt(data));
        if (!persist(c, list)) throw new IllegalStateException("Gagal menyimpan favorit");
        migrationPrefs(c).edit().putBoolean(KEY_MIGRATED, true).apply();
        MangaRoomEvents.notifyChanged();
    }

    public static void saveFavorites(Context c, ArrayList<MangaPost> list) {
        boolean saved = persist(c, list);
        if (saved) migrationPrefs(c).edit().putBoolean(KEY_MIGRATED, true).apply();
        MangaRoomEvents.notifyChanged();
    }

    private static void ensureMigrated(Context c) {
        if (c == null) return;
        SharedPreferences prefs = migrationPrefs(c);
        if (prefs.getBoolean(KEY_MIGRATED, false)) return;
        synchronized (MangaFavoriteManager.class) {
            if (prefs.getBoolean(KEY_MIGRATED, false)) return;
            try {
                ArrayList<MangaPost> old = parse(p(c).getString(KEY_ITEMS, "[]"));
                if (!old.isEmpty() && !persist(c, old)) return;
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply();
            } catch (Exception ignored) {}
        }
    }

    private static ArrayList<MangaPost> parse(String raw) {
        ArrayList<MangaPost> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                MangaPost p = new MangaPost(o.optString("slug"), o.optString("title"), o.optString("cover"), o.optString("author"), o.optString("status"), o.optString("synopsis"), o.optString("genre"), o.optString("typeLabel"), o.optString("latestChapter"), o.optString("latestChapterDate")).withSource(sourceId, o.optString("sourceLabel", MangaSourceFactory.labelForSourceId(sourceId)));
                p.info = o.optString("info", "");
                p.totalChapters = o.optInt("totalChapters", 0);
                if (!p.slug.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static ArrayList<MangaPost> parseStrict(String raw) throws Exception {
        JSONArray array = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || item.optString("slug").trim().isEmpty()) throw new IllegalArgumentException("Favorit backup tidak valid");
        }
        return parse(array.toString());
    }

    private static boolean persist(Context c, ArrayList<MangaPost> list) {
        if (c == null || list == null) return false;
        try {
            ArrayList<MangaFavoriteEntity> rows = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                MangaPost m = list.get(i);
                if (m == null || m.slug == null || m.slug.trim().isEmpty()) continue;
                MangaFavoriteEntity row = toEntity(m, i);
                rows.add(row);
            }
            MangaDao mangaDao = dao(c);
            mangaDao.replaceFavorites(rows);
            p(c).edit().putString(KEY_ITEMS, toJson(list).toString()).apply();
            pruneChapterBadges(c, rows);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void pruneChapterBadges(Context c, ArrayList<MangaFavoriteEntity> rows) {
        try {
            JSONObject root = new JSONObject(badgePrefs(c).getString(KEY_BADGES, "{}"));
            if (root.length() == 0) return;
            HashSet<String> validKeys = new HashSet<>();
            for (MangaFavoriteEntity row : rows) validKeys.add(safe(row.sourceId).trim() + "|" + safe(row.slug).trim());
            ArrayList<String> stale = new ArrayList<>();
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!validKeys.contains(key)) stale.add(key);
            }
            if (stale.isEmpty()) return;
            for (String key : stale) root.remove(key);
            badgePrefs(c).edit().putString(KEY_BADGES, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static void rememberRemovedPosition(Context c, String sourceId, String slug) {
        if (c == null || slug == null || slug.trim().isEmpty()) return;
        try {
            ArrayList<MangaPost> all = getFavorites(c);
            for (int i = 0; i < all.size(); i++) {
                MangaPost current = all.get(i);
                if (current != null && current.slug.equals(slug) && current.getSourceId().equals(sourceId)) {
                    p(c).edit().putInt(REMOVED_POSITION_PREFIX + key(sourceId, slug), i).apply();
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private static int removedPosition(Context c, String sourceId, String slug) {
        if (c == null || slug == null || slug.trim().isEmpty()) return -1;
        return p(c).getInt(REMOVED_POSITION_PREFIX + key(sourceId, slug), -1);
    }

    private static void clearRemovedPosition(Context c, String sourceId, String slug) {
        if (c == null || slug == null || slug.trim().isEmpty()) return;
        p(c).edit().remove(REMOVED_POSITION_PREFIX + key(sourceId, slug)).apply();
    }

    private static MangaFavoriteEntity toEntity(MangaPost m, int position) {
        MangaFavoriteEntity row = new MangaFavoriteEntity();
        row.slug = safe(m.slug).trim();
        row.sourceId = m.getSourceId();
        row.sourceLabel = m.getSourceLabel();
        row.key = key(row.sourceId, row.slug);
        row.title = safe(m.title);
        row.cover = safe(m.coverImage);
        row.author = safe(m.author);
        row.status = safe(m.status);
        row.synopsis = safe(m.synopsis);
        row.genre = safe(m.genre);
        row.typeLabel = safe(MangaLabelUtils.typeLabel(m));
        row.info = safe(m.info);
        row.latestChapter = safe(m.latestChapter);
        row.latestChapterDate = safe(m.latestChapterDate);
        row.totalChapters = Math.max(0, m.totalChapters);
        row.savedAt = System.currentTimeMillis() - position;
        row.position = position;
        return row;
    }

    public static void updateExistingFavorite(Context c, MangaPost post) {
        if (c == null || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        ensureMigrated(c);
        ArrayList<MangaPost> all = getFavorites(c);
        for (int i = 0; i < all.size(); i++) {
            MangaPost current = all.get(i);
            if (current != null && current.slug.equals(post.slug) && current.getSourceId().equals(post.getSourceId())) {
                all.set(i, post);
                persist(c, all);
                MangaRoomEvents.notifyChanged();
                return;
            }
        }
    }

    private static MangaPost toPost(MangaFavoriteEntity row) {
        if (row == null) return null;
        String sourceId = safe(row.sourceId).isEmpty() ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : row.sourceId;
        MangaPost post = new MangaPost(safe(row.slug), safe(row.title), safe(row.cover), safe(row.author), safe(row.status), safe(row.synopsis), safe(row.genre), safe(row.typeLabel), safe(row.latestChapter), safe(row.latestChapterDate)).withSource(sourceId, safe(row.sourceLabel).isEmpty() ? MangaSourceFactory.labelForSourceId(sourceId) : row.sourceLabel);
        post.info = safe(row.info);
        post.totalChapters = Math.max(0, row.totalChapters);
        return post;
    }

    private static void hydrateFavoritePost(Context c, MangaPost post, ArrayList<MangaPost> prefItems) {
        if (post == null) return;
        MangaPost pref = findFavorite(prefItems, post.getSourceId(), post.slug);
        if (pref != null) mergeMissingFavoriteData(post, pref);
        hydrateFavoriteFromDetailCache(c, post);
        String type = MangaLabelUtils.normalizeStoredType(post.typeLabel, safe(post.genre) + " " + safe(post.status) + " " + safe(post.synopsis) + " " + safe(post.info));
        if (!type.isEmpty()) post.typeLabel = type;
    }

    private static MangaPost findFavorite(ArrayList<MangaPost> list, String sourceId, String slug) {
        if (list == null || slug == null) return null;
        for (MangaPost item : list) {
            if (item != null && safe(item.slug).equals(slug) && item.getSourceId().equals(sourceId)) return item;
        }
        return null;
    }

    private static void mergeMissingFavoriteData(MangaPost target, MangaPost source) {
        if (target == null || source == null) return;
        if (safe(target.title).isEmpty()) target.title = safe(source.title);
        if (safe(target.coverImage).isEmpty()) target.coverImage = safe(source.coverImage);
        if (safe(target.author).isEmpty()) target.author = safe(source.author);
        if (safe(target.status).isEmpty()) target.status = safe(source.status);
        if (safe(target.synopsis).isEmpty()) target.synopsis = safe(source.synopsis);
        if (safe(target.genre).isEmpty()) target.genre = safe(source.genre);
        if (safe(target.info).isEmpty()) target.info = safe(source.info);
        String sourceType = MangaLabelUtils.normalizeStoredType(source.typeLabel, safe(source.genre) + " " + safe(source.status) + " " + safe(source.synopsis) + " " + safe(source.info));
        String targetType = MangaLabelUtils.normalizeStoredType(target.typeLabel, safe(target.genre) + " " + safe(target.status) + " " + safe(target.synopsis) + " " + safe(target.info));
        if (!sourceType.isEmpty() && (targetType.isEmpty() || "MANGA".equals(targetType) || MangaLabelUtils.isSpecificCountryType(sourceType))) target.typeLabel = sourceType;
        if (safe(target.latestChapter).isEmpty()) target.latestChapter = safe(source.latestChapter);
        if (safe(target.latestChapterDate).isEmpty()) target.latestChapterDate = safe(source.latestChapterDate);
        if (target.totalChapters <= 0) target.totalChapters = source.totalChapters;
    }

    private static void hydrateFavoriteFromDetailCache(Context c, MangaPost post) {
        if (c == null || post == null || safe(post.slug).isEmpty()) return;
        try {
            String raw = c.getApplicationContext().getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE).getString(post.getSourceId() + "_" + post.slug, "");
            if (raw == null || raw.trim().isEmpty()) return;
            JSONObject root = new JSONObject(raw);
            JSONObject manga = root.optJSONObject("manga");
            if (manga == null) return;
            if (safe(post.title).isEmpty()) post.title = manga.optString("title", post.title);
            if (safe(post.coverImage).isEmpty()) post.coverImage = manga.optString("coverImage", post.coverImage);
            if (safe(post.author).isEmpty()) post.author = manga.optString("author", post.author);
            if (safe(post.status).isEmpty()) post.status = manga.optString("status", post.status);
            if (safe(post.synopsis).isEmpty()) post.synopsis = manga.optString("synopsis", post.synopsis);
            if (safe(post.genre).isEmpty()) post.genre = manga.optString("genre", post.genre);
            if (safe(post.info).isEmpty()) post.info = manga.optString("info", post.info);
            String cacheType = MangaLabelUtils.normalizeStoredType(manga.optString("typeLabel", ""), manga.optString("genre", "") + " " + manga.optString("status", "") + " " + manga.optString("synopsis", "") + " " + manga.optString("info", ""));
            String currentType = MangaLabelUtils.normalizeStoredType(post.typeLabel, safe(post.genre) + " " + safe(post.status) + " " + safe(post.synopsis) + " " + safe(post.info));
            if (!cacheType.isEmpty() && (currentType.isEmpty() || "MANGA".equals(currentType) || MangaLabelUtils.isSpecificCountryType(cacheType))) post.typeLabel = cacheType;
        } catch (Exception ignored) {}
    }

    private static JSONArray toJson(ArrayList<MangaPost> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (MangaPost m : list) {
            if (m == null || m.slug == null || m.slug.trim().isEmpty()) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("slug", m.slug.trim());
                o.put("sourceId", m.getSourceId());
                o.put("sourceLabel", m.getSourceLabel());
                o.put("title", m.title);
                o.put("cover", m.coverImage);
                o.put("author", m.author);
                o.put("status", m.status);
                o.put("synopsis", m.synopsis);
                o.put("genre", m.genre);
                o.put("info", m.info);
                o.put("typeLabel", MangaLabelUtils.typeLabel(m));
                o.put("latestChapter", m.latestChapter);
                o.put("latestChapterDate", m.latestChapterDate);
                o.put("totalChapters", Math.max(0, m.totalChapters));
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private static void saveCoverIfEnabled(Context c, MangaPost post) {
        if (c == null || post == null || post.coverImage == null || post.coverImage.trim().isEmpty()) return;
        if (!MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(c)) return;
        MangaCoverCache.saveAsync(c.getApplicationContext(), post.coverImage, post.getSourceId());
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String decrypt(String enc) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key());
        return new String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(AES_SECRET.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
    }

    private static String key(String sourceId, String slug) {
        return safe(sourceId).trim() + ":" + safe(slug).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static MangaDao dao(Context c) {
        return MangaDatabase.get(c).mangaDao();
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences migrationPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE);
    }
}
