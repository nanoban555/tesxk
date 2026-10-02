package miku.moe.app;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class AnimeStyleV1DataLoader {
    private static final int PAGE_SIZE = 20;
    private static final int BLOCKED_CHANNEL_ID = 45784;
    private static final String DEFAULT_BASE = "https://wincamp.web.id/animexnonton/api/phalcon/api/";
    private static final String ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api";
    private static final String ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO";
    private static final String ANIMEKU_IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/";
    private static final String ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private AnimeStyleV1DataLoader() {}

    public static Page load(Context context, String sourceId, String kind, int page) throws Exception {
        String source = AnimeSettingsManager.isValidSource(sourceId) ? sourceId : AnimeSettingsManager.SOURCE_DEFAULT;
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(source)) return loadAnimeku(kind, page);
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(source)) return loadAnimeLoverz(kind, page);
        if (AnimeSettingsManager.SOURCE_DRAMORA.equals(source)) return loadDramora(kind, page);
        if (AnimeSettingsManager.SOURCE_ANICHIN.equals(source)) return loadAnichin(kind, page);
        return loadDefault(context, kind, page);
    }

    private static Page loadDefault(Context context, String kind, int page) throws Exception {
        boolean popular = "popular".equals(kind);
        String endpoint = popular ? "get_category_not_ongoing/" : "get_posts/";
        FormBody.Builder form = new FormBody.Builder()
                .add("isAPKvalid", "true")
                .add("page", String.valueOf(page))
                .add("count", String.valueOf(PAGE_SIZE));
        if (popular) {
            form.add("lang", "ID");
        } else {
            String deviceId = context == null ? "" : Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null) deviceId = "";
            form.add("device_id", deviceId).add("device_token", deviceId);
        }
        Request request = new Request.Builder().url(DEFAULT_BASE + endpoint).headers(defaultHeaders()).post(form.build()).build();
        JSONObject json = new JSONObject(execute(request));
        JSONArray array = popular ? json.optJSONArray("categories") : json.optJSONArray("posts");
        ArrayList<AnimePost> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                int categoryId = item.optInt("category_id", item.optInt("cid", -1));
                int channelId = item.optInt("channel_id", -1);
                String title = cleanTitle(item.optString("category_name", ""));
                if (categoryId <= 0 || title.isEmpty() || channelId == BLOCKED_CHANNEL_ID) continue;
                AnimePost post = new AnimePost(first(item.optString("img_url", ""), item.optString("category_image", "")), title, categoryId, channelId);
                post.sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
                post.channelName = item.optString("channel_name", "");
                post.episodeCount = first(item.optString("count_anime", ""), post.channelName);
                post.created = item.optString("created", "");
                post.countView = first(item.optString("count_view", ""), item.optString("total_views", ""));
                post.ongoing = item.optInt("ongoing", 0) == 1;
                post.hdAvailable = item.optBoolean("is_hd_available", false);
                post.fhdAvailable = item.optBoolean("is_fhd_available", false);
                post.rating = item.optString("rating", "");
                post.year = parseInt(item.optString("years", ""));
                post.scheduleDay = item.optInt("days", -1);
                result.add(post);
            }
        }
        int total = json.optInt("count_total", -1);
        int rawCount = array == null ? 0 : array.length();
        boolean hasMore = total > 0 ? page * PAGE_SIZE < total : rawCount >= PAGE_SIZE;
        return new Page(result, hasMore);
    }

    private static Page loadAnimeku(String kind, int page) throws Exception {
        boolean popular = "popular".equals(kind);
        String endpoint = popular ? "get_category_popular" : "get_videos";
        String url = ANIMEKU_API_BASE + "/" + endpoint + "?page=" + page + "&count=" + PAGE_SIZE + "&api_key=" + ANIMEKU_API_KEY;
        JSONObject json = new JSONObject(execute(new Request.Builder().url(url).headers(animekuHeaders()).get().build()));
        if (!"ok".equalsIgnoreCase(json.optString("status", ""))) return new Page(new ArrayList<>(), false);
        JSONArray array = popular ? json.optJSONArray("new_anime") : json.optJSONArray("latest_anime");
        ArrayList<AnimePost> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                int categoryId = item.optInt(popular ? "cid" : "cat_id", item.optInt("cat_id", -1));
                int videoId = item.optInt("vid", -1);
                String title = cleanTitle(item.optString("category_name", ""));
                if (categoryId <= 0 || title.isEmpty() || !popular && videoId <= 0) continue;
                AnimePost post = new AnimePost(imageUrl(first(item.optString("category_image", ""), item.optString("video_thumbnail", ""))), title, categoryId, videoId);
                post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
                String episode = popular ? item.optString("video_count", "") : item.optString("video_title", "");
                post.channelName = episode;
                post.episodeCount = episode;
                post.genre = item.optString("genre", "");
                post.rating = item.optString("rating", "");
                post.statusVideo = item.optString("status_video", "");
                post.ongoing = isOngoing(post.statusVideo);
                result.add(post);
            }
        }
        int total = json.optInt("count_total", -1);
        int rawCount = array == null ? 0 : array.length();
        boolean hasMore = total > 0 ? page * PAGE_SIZE < total : rawCount >= PAGE_SIZE;
        return new Page(result, hasMore);
    }

    private static Page loadAnimeLoverz(String kind, int page) throws Exception {
        int targetPage = "latest".equals(kind) && page > 1 ? page - 1 : page;
        String url = "popular".equals(kind) || page > 1
                ? ANIMELOVERZ_API_BASE + "/baruupload.php?page=" + targetPage
                : ANIMELOVERZ_API_BASE + "/home/ongoing.php?page=" + page + "&type=all";
        JSONArray array = new JSONArray(execute(new Request.Builder().url(url).headers(loverzHeaders()).get().build()));
        ArrayList<AnimePost> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String title = first(item.optString("judul", ""), first(item.optString("anime_name", ""), first(item.optString("title", ""), item.optString("name", ""))));
            String slug = first(item.optString("url", ""), first(item.optString("link", ""), first(item.optString("slug", ""), item.optString("permalink", "")))).trim().replaceAll("^/+|/+$", "");
            if (title.trim().isEmpty() || slug.isEmpty()) continue;
            int id = parseInt(item.optString("id", ""));
            if (id <= 0) id = positiveId(slug);
            AnimePost post = new AnimePost(first(item.optString("cover", ""), first(item.optString("thumb", ""), first(item.optString("thumbnail", ""), item.optString("image", "")))), cleanTitle(title), id, -1);
            post.sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ;
            post.slug = slug;
            post.channelName = first(item.optString("lastch", ""), first(item.optString("episode", ""), item.optString("ch", "")));
            post.episodeCount = first(item.optString("total_episode", ""), item.optString("episode_count", ""));
            post.rating = first(item.optString("score", ""), first(item.optString("rating", ""), item.optString("rate", "")));
            post.statusVideo = first(item.optString("status", ""), first(item.optString("release_status", ""), item.optString("anime_status", "")));
            post.description = first(item.optString("sinopsis", ""), first(item.optString("synopsis", ""), first(item.optString("description", ""), item.optString("desc", ""))));
            post.ongoing = isOngoing(post.statusVideo);
            result.add(post);
        }
        return new Page(result, array.length() > 0);
    }

    private static Page loadDramora(String kind, int page) throws Exception {
        Dramora.PageResult result = Dramora.listing("popular".equals(kind) ? "china" : "korea", page);
        return new Page(result.items, result.hasMore);
    }

    private static Page loadAnichin(String kind, int page) throws Exception {
        Anichin.PageResult result = Anichin.listing("popular".equals(kind) ? "popular" : "update", page);
        return new Page(result.items, result.hasMore);
    }

    private static String execute(Request request) throws Exception {
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
            return response.body() == null ? "" : response.body().string();
        }
    }

    private static Headers defaultHeaders() {
        return new Headers.Builder()
                .add("Cache-Control", "max-age=0")
                .add("Data-Agent", "AnimeXNonton 26.9.5/18")
                .add("Content-Type", "application/x-www-form-urlencoded")
                .add("User-Agent", "okhttp/5.5.0")
                .build();
    }

    private static Headers animekuHeaders() {
        return new Headers.Builder()
                .add("Cache-Control", "max-age=0")
                .add("Data-Agent", "Your Videos Channel")
                .add("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)")
                .add("Accept", "application/vnd.yourapi.v1.full+json")
                .build();
    }

    private static Headers loverzHeaders() {
        return new Headers.Builder()
                .add("user-agent", "Dart/3.9 (dart:io)")
                .add("accept", "application/json")
                .build();
    }

    private static String imageUrl(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean)) return "";
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean;
        return ANIMEKU_IMAGE_BASE + clean;
    }

    private static String cleanTitle(String value) {
        String result = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        result = result.replaceAll("(?i)\\bsub\\s*indo\\b", "");
        result = result.replaceAll("(?i)\\bsubtitle\\s*indonesia\\b", "");
        return result.trim().replaceAll("\\s+", " ");
    }

    private static String first(String first, String second) {
        String a = first == null ? "" : first.trim();
        if (!a.isEmpty() && !"null".equalsIgnoreCase(a)) return a;
        String b = second == null ? "" : second.trim();
        return "null".equalsIgnoreCase(b) ? "" : b;
    }

    private static boolean isOngoing(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return !clean.contains("complete") && !clean.contains("finished") && !clean.contains("selesai");
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int positiveId(String value) {
        int hash = value == null ? 1 : value.hashCode();
        return hash == Integer.MIN_VALUE ? 1 : Math.abs(hash);
    }

    public static final class Page {
        public final ArrayList<AnimePost> items;
        public final boolean hasMore;

        Page(ArrayList<AnimePost> items, boolean hasMore) {
            this.items = items;
            this.hasMore = hasMore;
        }
    }
}
