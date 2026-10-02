package miku.moe.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AnimeFavoriteEpisodeResolver {
    private static final String DEFAULT_CATEGORY_URL = "https://wincamp.web.id/animexnonton/api/phalcon/api/get_category_posts_secure/";
    private static final String ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api";
    private static final String ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO";
    private static final String ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5";
    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build();

    private AnimeFavoriteEpisodeResolver() {}

    public static String resolve(AnimePost post) {
        if (post == null) return "";
        try {
            String source = AnimeSettingsManager.isValidSource(post.sourceId) ? post.sourceId : AnimeSettingsManager.SOURCE_DEFAULT;
            if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(source)) return resolveAnimeku(post);
            if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(source)) return resolveAnimeLoverz(post);
            if (AnimeSettingsManager.SOURCE_DRAMORA.equals(source)) return resolveDramora(post);
            if (AnimeSettingsManager.SOURCE_ANICHIN.equals(source)) return resolveAnichin(post);
            return resolveDefault(post);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String resolveDefault(AnimePost post) throws Exception {
        if (post.categoryId <= 0) return AnimeEpisodeLabelUtils.latestLabel(post);
        FormBody body = new FormBody.Builder()
                .add("id", String.valueOf(post.categoryId))
                .add("isAPKvalid", "true")
                .build();
        String raw = execute(new Request.Builder().url(DEFAULT_CATEGORY_URL).headers(defaultHeaders()).post(body).build());
        JSONObject json = new JSONObject(raw);
        JSONArray posts = json.optJSONArray("posts");
        String best = bestEpisodeFromArray(posts);
        if (!best.isEmpty()) return best;
        return posts != null ? AnimeEpisodeLabelUtils.fromCount(posts.length()) : AnimeEpisodeLabelUtils.latestLabel(post);
    }

    private static String resolveAnimeku(AnimePost post) throws Exception {
        if (post.categoryId <= 0) return AnimeEpisodeLabelUtils.latestLabel(post);
        String url = ANIMEKU_API_BASE + "/get_anime_detail?id=" + post.categoryId + "&api_key=" + ANIMEKU_API_KEY;
        JSONObject json = new JSONObject(execute(new Request.Builder().url(url).headers(animekuHeaders()).get().build()));
        JSONObject category = json.optJSONObject("category");
        JSONArray suggested = json.optJSONArray("suggested");
        String categoryCount = category == null ? "" : AnimeEpisodeLabelUtils.normalize(category.optString("video_count", ""));
        String arrayBest = bestEpisodeFromArray(suggested);
        String best = larger(categoryCount, arrayBest);
        if (!best.isEmpty()) return best;
        return suggested != null ? AnimeEpisodeLabelUtils.fromCount(suggested.length()) : AnimeEpisodeLabelUtils.latestLabel(post);
    }

    private static String resolveAnimeLoverz(AnimePost post) throws Exception {
        String slug = post.slug == null ? "" : post.slug.trim().replaceAll("^/+|/+$", "");
        if (slug.isEmpty()) return AnimeEpisodeLabelUtils.latestLabel(post);
        List<String> variants = new ArrayList<>();
        variants.add(slug);
        String decoded = Uri.decode(slug);
        if (!decoded.equals(slug)) variants.add(decoded);
        for (String variant : variants) {
            JSONObject payload = new JSONObject()
                    .put("get", "top")
                    .put("post_type", "1")
                    .put("post_id", variant)
                    .put("token", "");
            RequestBody body = RequestBody.create(payload.toString(), TEXT);
            String url = ANIMELOVERZ_API_BASE + "/series.php?url=" + Uri.encode(variant);
            String raw = execute(new Request.Builder().url(url).headers(loverzHeaders()).post(body).build());
            if (raw.trim().isEmpty()) continue;
            JSONObject json = new JSONObject(raw);
            String best = scanEpisodeFields(json);
            if (!best.isEmpty()) return best;
        }
        return AnimeEpisodeLabelUtils.latestLabel(post);
    }

    private static String resolveDramora(AnimePost post) throws Exception {
        Dramora.DetailResult detail = Dramora.detail(post);
        String best = "";
        if (detail != null && detail.episodes != null) {
            for (Dramora.EpisodeResult episode : detail.episodes) {
                if (episode == null) continue;
                best = larger(best, AnimeEpisodeLabelUtils.normalize(episode.title));
            }
            if (best.isEmpty()) best = AnimeEpisodeLabelUtils.fromCount(detail.episodes.size());
        }
        if (!best.isEmpty()) return best;
        return detail == null || detail.post == null ? AnimeEpisodeLabelUtils.latestLabel(post) : AnimeEpisodeLabelUtils.latestLabel(detail.post);
    }

    private static String resolveAnichin(AnimePost post) throws Exception {
        String slug = post.slug == null ? "" : post.slug.trim().replaceAll("^/+|/+$", "");
        if (slug.isEmpty()) return AnimeEpisodeLabelUtils.latestLabel(post);
        Anichin.DetailResult detail = Anichin.detail(slug);
        String best = "";
        if (detail != null && detail.episodes != null) {
            for (Anichin.EpisodeResult episode : detail.episodes) {
                if (episode == null) continue;
                best = larger(best, AnimeEpisodeLabelUtils.normalize(episode.title));
            }
            if (best.isEmpty()) best = AnimeEpisodeLabelUtils.fromCount(detail.episodes.size());
        }
        if (!best.isEmpty()) return best;
        return detail == null || detail.post == null ? AnimeEpisodeLabelUtils.latestLabel(post) : AnimeEpisodeLabelUtils.latestLabel(detail.post);
    }
        if (array == null) return "";
        String best = "";
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (raw instanceof JSONObject) best = larger(best, scanEpisodeFields((JSONObject) raw));
            else best = larger(best, AnimeEpisodeLabelUtils.normalize(String.valueOf(raw)));
        }
        return best;
    }

    private static String scanEpisodeFields(JSONObject object) {
        if (object == null) return "";
        String best = "";
        JSONArray names = object.names();
        if (names == null) return best;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            Object value = object.opt(key);
            String normalizedKey = key.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
            boolean episodeKey = normalizedKey.contains("episode") || normalizedKey.contains("chapter") || normalizedKey.equals("ch") || normalizedKey.contains("lastch") || normalizedKey.contains("video count") || normalizedKey.contains("total episode") || normalizedKey.contains("video title") || normalizedKey.contains("channel name");
            if (value instanceof JSONObject) {
                best = larger(best, scanEpisodeFields((JSONObject) value));
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                if (episodeKey) best = larger(best, AnimeEpisodeLabelUtils.fromCount(array.length()));
                best = larger(best, bestEpisodeFromArray(array));
            } else if (episodeKey && value != null) {
                best = larger(best, AnimeEpisodeLabelUtils.normalize(String.valueOf(value)));
            }
        }
        return best;
    }

    private static String larger(String first, String second) {
        double a = AnimeEpisodeLabelUtils.numericValue(first);
        double b = AnimeEpisodeLabelUtils.numericValue(second);
        if (b > a) return second == null ? "" : second;
        return first == null ? "" : first;
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
                .add("access-control-allow-origin", "*")
                .add("content-type", "text/plain; charset=utf-8")
                .build();
    }
}
