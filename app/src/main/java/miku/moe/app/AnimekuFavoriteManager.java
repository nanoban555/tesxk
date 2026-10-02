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

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class AnimekuFavoriteManager {
    private static final String PREF = "animeku_favorites";
    private static final String KEY = "items";
    private static final String AES_SECRET = "Miku01v-Animeku-Favorite-Backup";

    public static boolean isFavorite(Context context, int categoryId) {
        for (AnimePost post : getFavorites(context)) if (post.categoryId == categoryId) return true;
        return false;
    }

    public static void toggle(Context context, AnimePost post) {
        if (post == null) return;
        if (isFavorite(context, post.categoryId)) remove(context, post.categoryId); else add(context, post);
    }

    public static void add(Context context, AnimePost post) {
        if (context == null || post == null || post.categoryId <= 0) return;
        ArrayList<AnimePost> list = getFavorites(context);
        for (AnimePost p : list) if (p.categoryId == post.categoryId) return;
        list.add(0, post);
        save(context, list);
    }

    public static void remove(Context context, int categoryId) {
        ArrayList<AnimePost> list = getFavorites(context);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).categoryId == categoryId) list.remove(i);
        save(context, list);
    }

    public static ArrayList<AnimePost> getFavorites(Context context) {
        if (context == null) return new ArrayList<>();
        return parseFavorites(prefs(context).getString(KEY, "[]"));
    }

    public static String exportEncrypted(Context context) throws Exception {
        String json = prefs(context).getString(KEY, "[]");
        return "const MIKU_ANIMEKU_FAVORITES_AES = \"" + encrypt(json) + "\";";
    }

    public static void importEncrypted(Context context, String fileContent) throws Exception {
        String data = fileContent.trim();
        int q1 = data.indexOf('"');
        int q2 = data.lastIndexOf('"');
        if (q1 >= 0 && q2 > q1) data = data.substring(q1 + 1, q2);
        String json = decrypt(data);
        ArrayList<AnimePost> list = parseFavorites(json);
        save(context, list);
    }

    private static ArrayList<AnimePost> parseFavorites(String raw) {
        ArrayList<AnimePost> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AnimePost post = new AnimePost(o.optString("imgUrl", ""), o.optString("categoryName", ""), o.optInt("categoryId", -1), o.optInt("channelId", -1));
                post.genre = o.optString("genre", "");
                post.rating = o.optString("rating", "");
                post.year = o.optInt("year", 0);
                post.countView = o.optString("countView", "");
                post.episodeCount = o.optString("episodeCount", "");
                post.ongoing = o.optBoolean("ongoing", false);
                post.description = o.optString("description", "");
                if (post.categoryId > 0) result.add(post);
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
                o.put("genre", p.genre == null ? "" : p.genre);
                o.put("rating", p.rating == null ? "" : p.rating);
                o.put("year", p.year);
                o.put("countView", p.countView == null ? "" : p.countView);
                o.put("episodeCount", p.episodeCount == null ? "" : p.episodeCount);
                o.put("ongoing", p.ongoing);
                o.put("description", p.description == null ? "" : p.description);
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

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
