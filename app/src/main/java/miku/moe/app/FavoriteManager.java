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
import java.util.HashSet;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class FavoriteManager {
    private static final String PREF = "anime_favorites";
    private static final String KEY = "items";
    private static final String AES_SECRET = "Miku01v-Favorite-Backup-Key";

    public static boolean isFavorite(Context context, int categoryId) {
        return isFavorite(context, "", categoryId, "");
    }
    public static boolean isFavorite(Context context, String sourceId, int categoryId, String slug) {
        for (AnimePost post : getFavorites(context)) if (sameAnime(post, sourceId, categoryId, slug)) return true;
        return false;
    }

    public static void toggle(Context context, AnimePost post) {
        if (post == null) return;
        if (isFavorite(context, post.sourceId, post.categoryId, post.slug)) remove(context, post.sourceId, post.categoryId, post.slug); else add(context, post);
    }

    public static void add(Context context, AnimePost post) {
        if (context == null || post == null) return;
        ArrayList<AnimePost> list = getFavorites(context);
        for (AnimePost current : list) {
            if (!sameAnime(current, post.sourceId, post.categoryId, post.slug)) continue;
            mergeMetadata(current, post);
            save(context, list);
            return;
        }
        list.add(0, post);
        save(context, list);
    }

    public static void update(Context context, AnimePost post) {
        if (context == null || post == null) return;
        ArrayList<AnimePost> list = getFavorites(context);
        for (AnimePost current : list) {
            if (!sameAnime(current, post.sourceId, post.categoryId, post.slug)) continue;
            mergeMetadata(current, post);
            save(context, list);
            return;
        }
    }

    public static void remove(Context context, int categoryId) {
        remove(context, "", categoryId, "");
    }

    public static void remove(Context context, String sourceId, int categoryId, String slug) {
        ArrayList<AnimePost> list = getFavorites(context);
        for (int i = list.size() - 1; i >= 0; i--) if (sameAnime(list.get(i), sourceId, categoryId, slug)) list.remove(i);
        save(context, list);
    }

    public static ArrayList<AnimePost> getFavorites(Context context) {
        return parseFavorites(prefs(context).getString(KEY, "[]"));
    }

    public static void updateEpisodeLabels(Context context, Map<String, String> labels) {
        if (context == null || labels == null || labels.isEmpty()) return;
        ArrayList<AnimePost> list = getFavorites(context);
        boolean changed = false;
        for (AnimePost post : list) {
            String label = labels.get(keyOf(post));
            if (label == null || label.trim().isEmpty()) continue;
            String normalized = AnimeEpisodeLabelUtils.normalize(label);
            if (normalized.isEmpty()) continue;
            if (!normalized.equals(post.episodeCount) || !normalized.equals(post.channelName)) {
                post.episodeCount = normalized;
                post.channelName = normalized;
                changed = true;
            }
        }
        if (changed) save(context, list);
    }

    public static String keyOf(AnimePost post) {
        if (post == null) return "";
        String source = post.sourceId == null || post.sourceId.trim().isEmpty() ? AnimeSettingsManager.SOURCE_DEFAULT : post.sourceId.trim();
        String slug = post.slug == null ? "" : post.slug.trim();
        return source + ":" + (slug.isEmpty() ? String.valueOf(post.categoryId) : slug);
    }

    public static String exportEncrypted(Context context) throws Exception {
        String json = prefs(context).getString(KEY, "[]");
        return "const MIKU_FAVORITES_AES = \"" + encrypt(json) + "\";";
    }

    public static void importEncrypted(Context context, String fileContent) throws Exception {
        String data = fileContent.trim();
        int q1 = data.indexOf('"');
        int q2 = data.lastIndexOf('"');
        if (q1 >= 0 && q2 > q1) data = data.substring(q1 + 1, q2);
        String json = decrypt(data);
        ArrayList<AnimePost> imported = parseFavorites(json);
        if (imported.isEmpty()) return;
        ArrayList<AnimePost> existing = getFavorites(context);
        HashSet<String> existingKeys = new HashSet<>();
        for (AnimePost current : existing) existingKeys.add(keyOf(current));
        for (AnimePost incoming : imported) {
            if (existingKeys.add(keyOf(incoming))) existing.add(0, incoming);
        }
        save(context, existing);
    }

    public static void migrateLegacyAnimekuFavorites(Context context) {
        if (context == null) return;
        try {
            SharedPreferences legacyPrefs = context.getApplicationContext().getSharedPreferences("animeku_favorites", Context.MODE_PRIVATE);
            String raw = legacyPrefs.getString(KEY, "[]");
            if (raw == null || raw.isEmpty() || "[]".equals(raw.trim())) return;
            ArrayList<AnimePost> legacy = parseLegacyAnimekuJson(raw);
            if (legacy.isEmpty()) return;
            ArrayList<AnimePost> existing = getFavorites(context);
            HashSet<String> existingKeys = new HashSet<>();
            for (AnimePost current : existing) existingKeys.add(keyOf(current));
            boolean changed = false;
            for (AnimePost post : legacy) {
                if (post == null) continue;
                post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
                if (existingKeys.add(keyOf(post))) {
                    existing.add(post);
                    changed = true;
                }
            }
            if (changed) save(context, existing);
            legacyPrefs.edit().putBoolean("migrated_to_unified", true).apply();
        } catch (Exception ignored) { }
    }

    private static ArrayList<AnimePost> parseLegacyAnimekuJson(String raw) {
        ArrayList<AnimePost> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AnimePost post = new AnimePost(o.optString("imgUrl", ""), o.optString("categoryName", ""), o.optInt("categoryId", -1), o.optInt("channelId", -1));
                post.slug = o.optString("slug", "");
                post.genre = o.optString("genre", "");
                post.rating = o.optString("rating", "");
                post.year = o.optInt("year", 0);
                post.countView = o.optString("countView", "");
                post.episodeCount = o.optString("episodeCount", "");
                post.channelName = o.optString("channelName", "");
                post.description = o.optString("description", "");
                post.statusVideo = o.optString("statusVideo", "");
                post.ongoing = o.optBoolean("ongoing", false);
                if (post.categoryId > 0 || (post.slug != null && !post.slug.trim().isEmpty())) result.add(post);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static void mergeMetadata(AnimePost target, AnimePost source) {
        if (target == null || source == null) return;
        if (isUseful(source.sourceId)) target.sourceId = source.sourceId.trim();
        if (isUseful(source.imgUrl)) target.imgUrl = source.imgUrl.trim();
        if (isUseful(source.categoryName)) target.categoryName = source.categoryName.trim();
        if (source.categoryId > 0) target.categoryId = source.categoryId;
        if (source.channelId > 0) target.channelId = source.channelId;
        if (isUseful(source.slug)) target.slug = source.slug.trim();
        if (isUseful(source.genre)) target.genre = source.genre.trim();
        if (isUseful(source.rating)) target.rating = source.rating.trim();
        if (source.year > 0) target.year = source.year;
        if (isUseful(source.countView)) target.countView = source.countView.trim();
        if (isUseful(source.episodeCount)) target.episodeCount = source.episodeCount.trim();
        if (isUseful(source.channelName)) target.channelName = source.channelName.trim();
        if (isUseful(source.description)) target.description = source.description.trim();
        if (isUseful(source.statusVideo)) {
            target.statusVideo = source.statusVideo.trim();
            target.ongoing = source.ongoing;
        }
    }

    private static boolean isUseful(String value) {
        return value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim());
    }

    private static boolean sameAnime(AnimePost post, String sourceId, int categoryId, String slug) {
        if (post == null) return false;
        String requestedSource = sourceId == null ? "" : sourceId.trim();
        String postSource = post.sourceId == null ? "" : post.sourceId.trim();
        if (requestedSource.isEmpty()) requestedSource = AnimeSettingsManager.SOURCE_DEFAULT;
        if (postSource.isEmpty()) postSource = AnimeSettingsManager.SOURCE_DEFAULT;
        if (!requestedSource.equals(postSource)) return false;
        String requestedSlug = slug == null ? "" : slug.trim();
        String postSlug = post.slug == null ? "" : post.slug.trim();
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(requestedSource) && !requestedSlug.isEmpty()) return requestedSlug.equals(postSlug);
        return post.categoryId == categoryId;
    }

    private static ArrayList<AnimePost> parseFavorites(String raw) {
        ArrayList<AnimePost> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AnimePost post = new AnimePost(o.optString("imgUrl", ""), o.optString("categoryName", ""), o.optInt("categoryId", -1), o.optInt("channelId", -1));
                post.sourceId = o.optString("sourceId", "");
                if (post.sourceId == null || post.sourceId.trim().isEmpty()) post.sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
                post.slug = o.optString("slug", "");
                post.genre = o.optString("genre", "");
                post.rating = o.optString("rating", "");
                post.year = o.optInt("year", 0);
                post.countView = o.optString("countView", "");
                post.episodeCount = o.optString("episodeCount", "");
                post.channelName = o.optString("channelName", post.episodeCount);
                post.description = o.optString("description", "");
                post.statusVideo = o.optString("statusVideo", "");
                post.ongoing = o.optBoolean("ongoing", false);
                if (post.categoryId > 0 || (post.slug != null && !post.slug.trim().isEmpty())) result.add(post);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static void save(Context context, ArrayList<AnimePost> list) {
        JSONArray arr = new JSONArray();
        try {
            for (AnimePost p : list) {
                JSONObject o = new JSONObject();
                o.put("imgUrl", p.imgUrl);
                o.put("categoryName", p.categoryName);
                o.put("categoryId", p.categoryId);
                o.put("channelId", p.channelId);
                o.put("sourceId", p.sourceId == null ? "" : p.sourceId);
                o.put("slug", p.slug == null ? "" : p.slug);
                o.put("genre", p.genre == null ? "" : p.genre);
                o.put("rating", p.rating == null ? "" : p.rating);
                o.put("year", p.year);
                o.put("countView", p.countView == null ? "" : p.countView);
                o.put("episodeCount", p.episodeCount == null ? "" : p.episodeCount);
                o.put("channelName", p.channelName == null ? "" : p.channelName);
                o.put("description", p.description == null ? "" : p.description);
                o.put("statusVideo", p.statusVideo == null ? "" : p.statusVideo);
                o.put("ongoing", p.ongoing);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs(context).edit().putString(KEY, arr.toString()).apply();
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

    private static SharedPreferences prefs(Context context) { return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE); }
}
