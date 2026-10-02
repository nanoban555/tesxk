package miku.moe.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public final class Dramora {
    private static final String API_BASE = "https://api.dramora.my.id";
    private static final int LIMIT = 20;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final Map<String, String> GENRE_ID_CACHE = new LinkedHashMap<>();

    private Dramora() {}

    public static PageResult listing(String listing, int page) throws IOException {
        String category;
        if ("china".equals(listing)) category = "c-drama";
        else if ("thailand".equals(listing)) category = "t-drama";
        else category = "k-drama";
        HttpUrl url = HttpUrl.parse(API_BASE + "/api/v2/movie").newBuilder()
                .addQueryParameter("offset", String.valueOf(offset(page)))
                .addQueryParameter("limit", String.valueOf(LIMIT))
                .addQueryParameter("category", category)
                .build();
        return moviePage(url);
    }

    public static PageResult search(String query, int page) throws IOException {
        HttpUrl.Builder builder = HttpUrl.parse(API_BASE + "/api/v2/movie").newBuilder()
                .addQueryParameter("offset", String.valueOf(offset(page)))
                .addQueryParameter("limit", String.valueOf(LIMIT));
        if (useful(query)) builder.addQueryParameter("q", query.trim());
        return moviePage(builder.build());
    }

    public static PageResult genre(String genre, int page) throws IOException {
        String id = genreIdFor(genre);
        if (!useful(id)) return new PageResult(new ArrayList<>(), false, 0);
        HttpUrl url = HttpUrl.parse(API_BASE + "/api/v2/movie").newBuilder()
                .addQueryParameter("offset", String.valueOf(offset(page)))
                .addQueryParameter("limit", String.valueOf(LIMIT))
                .addQueryParameter("genreId", id)
                .build();
        return moviePage(url);
    }

    public static ArrayList<String> genres() throws IOException {
        ensureGenres();
        return new ArrayList<>(GENRE_ID_CACHE.keySet());
    }

    public static DetailResult detail(AnimePost initial) throws IOException {
        String id = firstUseful(initial.slug, initial.categoryId > 0 ? String.valueOf(initial.categoryId) : "");
        if (!useful(id)) return new DetailResult(initial, "", new ArrayList<>(), new LinkedHashMap<>(), new ArrayList<>());
        HttpUrl url = HttpUrl.parse(API_BASE + "/api/v1/movie/detail-movie").newBuilder()
                .addQueryParameter("id", id)
                .build();
        JSONObject json = parseJson(get(url.toString()));
        JSONObject data = json.optJSONObject("data");
        if (data == null) return new DetailResult(initial, "", new ArrayList<>(), new LinkedHashMap<>(), new ArrayList<>());
        AnimePost post = moviePost(data);
        if (post == null) post = new AnimePost(initial.imgUrl, initial.categoryName, initial.categoryId, initial.channelId);
        post.sourceId = AnimeSettingsManager.SOURCE_DRAMORA;
        if (!useful(post.slug)) post.slug = firstUseful(initial.slug, id);
        if (!useful(post.imgUrl)) post.imgUrl = initial.imgUrl;
        if (!useful(post.categoryName)) post.categoryName = initial.categoryName;
        String description = cleanHtml(data.optString("description", initial.description));
        post.description = description;
        ArrayList<String> genres = genreNames(data.optJSONArray("genre"));
        if (genres.isEmpty()) genres = genreNames(data.optJSONArray("genres"));
        post.genre = joinStrings(genres);
        post.statusVideo = normalizeStatus(data.optString("status", post.statusVideo));
        post.ongoing = isOngoing(post.statusVideo);
        post.episodeCount = episodeCountText(data);
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        put(rows, "Kategori", data.optString("category", ""));
        put(rows, "Rating Umur", data.optString("ratingFilm", ""));
        put(rows, "Season", numberText(data, "seasonsCount"));
        put(rows, "Total Episode", numberText(data, "totalEpisode"));
        put(rows, "Episode Tersedia", numberText(data, "episodesCount"));
        put(rows, "Tanggal Rilis", formatDate(data.optString("releaseDate", "")));
        put(rows, "Aktor", actorNames(data.optJSONArray("actors")));
        ArrayList<EpisodeResult> episodes = episodes(data.optJSONArray("episodes"));
        return new DetailResult(post, description, genres, rows, episodes);
    }

    public static ArrayList<QualityResult> playback(String episodeId) throws IOException {
        ArrayList<QualityResult> result = new ArrayList<>();
        if (!useful(episodeId)) return result;
        HttpUrl url = HttpUrl.parse(API_BASE + "/api/v1/movie/detail-video").newBuilder()
                .addQueryParameter("episodeId", episodeId.trim())
                .build();
        JSONObject json = parseJson(get(url.toString()));
        JSONObject data = json.optJSONObject("data");
        JSONObject videos = data == null ? null : data.optJSONObject("videos");
        if (videos == null) return result;
        addQuality(result, PlaybackQualityManager.QUALITY_SD, "360p", videos);
        addQuality(result, PlaybackQualityManager.QUALITY_HD, "720p", videos);
        addQuality(result, PlaybackQualityManager.QUALITY_HD, "480p", videos);
        addQuality(result, PlaybackQualityManager.QUALITY_FHD, "1080p", videos);
        JSONArray names = videos.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if (!useful(key)) continue;
                String normalized = key.replace("p", "");
                if (containsQuality(result, key) || containsQuality(result, normalized)) continue;
                String link = videos.optString(key, "");
                if (playable(link)) result.add(new QualityResult(qualityKey(key), key.endsWith("p") ? key : key + "p", link));
            }
        }
        return result;
    }

    public static String sourceLabel() {
        return "Dramora";
    }

    private static PageResult moviePage(HttpUrl url) throws IOException {
        JSONObject json = parseJson(get(url.toString()));
        JSONArray array = movieArray(json);
        ArrayList<AnimePost> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            AnimePost post = moviePost(array.optJSONObject(i));
            if (post != null) items.add(post);
        }
        return new PageResult(items, array.length() >= LIMIT, array.length());
    }

    private static AnimePost moviePost(JSONObject item) {
        if (item == null) return null;
        String id = item.optString("id", "").trim();
        String title = item.optString("title", "").trim();
        if (!useful(id) || !useful(title)) return null;
        AnimePost post = new AnimePost(item.optString("banner", ""), title, positiveId(id), -1);
        post.sourceId = AnimeSettingsManager.SOURCE_DRAMORA;
        post.slug = id;
        post.genre = joinStrings(genreNames(firstArray(item, "genre", "genres")));
        post.rating = trimNumber(item.opt("rating"));
        post.year = item.optInt("year", 0);
        post.countView = numberText(item, "viewers");
        post.episodeCount = episodeCountText(item);
        post.statusVideo = normalizeStatus(item.optString("status", ""));
        post.ongoing = isOngoing(post.statusVideo);
        post.description = cleanHtml(item.optString("description", ""));
        post.channelName = firstUseful(item.optString("category", ""), post.episodeCount);
        return post;
    }

    private static ArrayList<EpisodeResult> episodes(JSONArray array) {
        ArrayList<EpisodeResult> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            if (!"publish".equalsIgnoreCase(item.optString("videoStatus", "publish"))) continue;
            String id = item.optString("id", "").trim();
            if (!useful(id)) continue;
            String title = firstUseful(item.optString("subTitle", ""), "Episode " + item.optInt("episodes", i + 1));
            String subtitle = firstUseful(formatDate(item.optString("updated_at", "")), formatDate(item.optString("created_at", "")));
            result.add(new EpisodeResult(positiveId(id), title, subtitle, id, item.optString("thumbnail", ""), item.optInt("episodes", i + 1)));
        }
        result.sort((a, b) -> Integer.compare(a.episodeNumber, b.episodeNumber));
        return result;
    }

    private static void ensureGenres() throws IOException {
        if (!GENRE_ID_CACHE.isEmpty()) return;
        JSONObject json = parseJson(get(API_BASE + "/api/v2/genre"));
        JSONArray array = json.optJSONArray("data");
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("slug", "").trim();
            String id = item.optString("id", "").trim();
            if (useful(name) && useful(id) && !GENRE_ID_CACHE.containsKey(name)) GENRE_ID_CACHE.put(name, id);
        }
    }

    private static String genreIdFor(String genre) throws IOException {
        ensureGenres();
        String target = normalize(genre);
        for (Map.Entry<String, String> entry : GENRE_ID_CACHE.entrySet()) {
            if (normalize(entry.getKey()).equals(target)) return entry.getValue();
        }
        return "";
    }

    private static JSONArray movieArray(JSONObject json) {
        Object data = json.opt("data");
        if (data instanceof JSONArray) return (JSONArray) data;
        if (data instanceof JSONObject) {
            JSONObject object = (JSONObject) data;
            JSONArray array = firstArray(object, "data", "items", "movies", "results", "rows");
            if (array != null) return array;
        }
        JSONArray array = firstArray(json, "items", "movies", "results", "rows");
        return array == null ? new JSONArray() : array;
    }

    private static JSONArray firstArray(JSONObject json, String... names) {
        if (json == null) return null;
        for (String name : names) {
            JSONArray array = json.optJSONArray(name);
            if (array != null) return array;
        }
        return null;
    }

    private static ArrayList<String> genreNames(JSONArray array) {
        ArrayList<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            String name = "";
            if (raw instanceof JSONObject) name = ((JSONObject) raw).optString("slug", ((JSONObject) raw).optString("name", ""));
            else if (raw != null) name = String.valueOf(raw);
            if (useful(name) && !containsIgnoreCase(result, name)) result.add(name.trim());
        }
        return result;
    }

    private static String actorNames(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String name = array.optJSONObject(i) == null ? "" : array.optJSONObject(i).optString("name", "");
            if (useful(name) && !containsIgnoreCase(result, name)) result.add(name.trim());
            if (result.size() >= 12) break;
        }
        return joinStrings(result);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        String target = normalize(value);
        for (String item : list) if (normalize(item).equals(target)) return true;
        return false;
    }

    private static String joinStrings(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (!useful(value)) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private static String episodeCountText(JSONObject item) {
        int episodes = item.optInt("episodesCount", 0);
        int total = item.optInt("totalEpisode", 0);
        if (episodes > 0 && total > 0 && episodes != total) return episodes + "/" + total + " Episode";
        int value = Math.max(episodes, total);
        return value > 0 ? value + " Episode" : "";
    }

    private static String numberText(JSONObject item, String key) {
        if (item == null || !item.has(key) || item.isNull(key)) return "";
        Object value = item.opt(key);
        if (value instanceof Number) return trimNumber(value);
        return String.valueOf(value).trim();
    }

    private static String trimNumber(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        if (text.endsWith(".0")) return text.substring(0, text.length() - 2);
        return text;
    }

    private static void put(LinkedHashMap<String, String> rows, String key, String value) {
        if (useful(key) && useful(value)) rows.put(key, value.trim());
    }

    private static String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).headers(headers()).build();
        try (okhttp3.Response response = CLIENT.newCall(request).execute()) {
            return response.body() == null ? "" : response.body().string();
        }
    }

    private static JSONObject parseJson(String value) throws IOException {
        try {
            return new JSONObject(value == null ? "" : value);
        } catch (Exception e) {
            throw new IOException("Invalid Dramora response", e);
        }
    }

    private static Headers headers() {
        return new Headers.Builder()
                .add("user-agent", "Dart/3.10 (dart:io)")
                .add("content-type", "application/json")
                .add("accept", "application/json")
                .build();
    }

    private static int offset(int page) {
        return Math.max(page - 1, 0) * LIMIT;
    }

    private static int positiveId(String value) {
        int hash = value == null ? 1 : value.hashCode();
        return hash == Integer.MIN_VALUE ? 1 : Math.abs(hash);
    }

    private static String cleanHtml(String value) {
        String raw = value == null ? "" : value.trim();
        if (!useful(raw)) return "";
        return Jsoup.parse(raw).text().trim();
    }

    private static String firstUseful(String first, String second) {
        if (useful(first)) return first.trim();
        return useful(second) ? second.trim() : "";
    }

    private static boolean useful(String value) {
        String raw = value == null ? "" : value.trim();
        return !raw.isEmpty() && !"null".equalsIgnoreCase(raw) && !"-".equals(raw);
    }

    private static boolean playable(String value) {
        String raw = value == null ? "" : value.trim();
        return raw.startsWith("http://") || raw.startsWith("https://");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
    }

    private static String normalizeStatus(String value) {
        String raw = value == null ? "" : value.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.equals("finish") || lower.contains("complete") || lower.contains("finished")) return "Completed";
        if (lower.equals("on-going") || lower.equals("ongoing") || lower.contains("on going")) return "Ongoing";
        return raw;
    }

    private static boolean isOngoing(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("ongoing") || lower.contains("on-going") || lower.contains("on going");
    }

    private static String formatDate(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.length() >= 10 && raw.charAt(4) == '-' && raw.charAt(7) == '-') return raw.substring(0, 10);
        return raw;
    }

    private static void addQuality(ArrayList<QualityResult> list, String quality, String key, JSONObject videos) {
        String link = videos.optString(key, "");
        if (!playable(link)) link = videos.optString(key.replace("p", ""), "");
        if (playable(link) && !containsQuality(list, key)) list.add(new QualityResult(quality, key, link));
    }

    private static boolean containsQuality(List<QualityResult> list, String label) {
        String target = label == null ? "" : label.replace("p", "").trim();
        for (QualityResult item : list) {
            if (item.label.replace("p", "").trim().equals(target)) return true;
        }
        return false;
    }

    private static String qualityKey(String key) {
        String clean = key == null ? "" : key.replace("p", "").trim();
        int value = 0;
        try {
            value = Integer.parseInt(clean);
        } catch (Exception ignored) {
        }
        if (value >= 1080) return PlaybackQualityManager.QUALITY_FHD;
        if (value >= 480) return PlaybackQualityManager.QUALITY_HD;
        return PlaybackQualityManager.QUALITY_SD;
    }

    public static final class PageResult {
        public final ArrayList<AnimePost> items;
        public final boolean hasMore;
        public final int rawCount;

        PageResult(ArrayList<AnimePost> items, boolean hasMore, int rawCount) {
            this.items = items;
            this.hasMore = hasMore;
            this.rawCount = rawCount;
        }
    }

    public static final class DetailResult {
        public final AnimePost post;
        public final String description;
        public final ArrayList<String> genres;
        public final LinkedHashMap<String, String> rows;
        public final ArrayList<EpisodeResult> episodes;

        DetailResult(AnimePost post, String description, ArrayList<String> genres, LinkedHashMap<String, String> rows, ArrayList<EpisodeResult> episodes) {
            this.post = post;
            this.description = description;
            this.genres = genres;
            this.rows = rows;
            this.episodes = episodes;
        }
    }

    public static final class EpisodeResult {
        public final int id;
        public final String title;
        public final String subtitle;
        public final String episodeId;
        public final String thumbnail;
        public final int episodeNumber;

        EpisodeResult(int id, String title, String subtitle, String episodeId, String thumbnail, int episodeNumber) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.episodeId = episodeId;
            this.thumbnail = thumbnail;
            this.episodeNumber = episodeNumber;
        }
    }

    public static final class QualityResult {
        public final String quality;
        public final String label;
        public final String url;

        QualityResult(String quality, String label, String url) {
            this.quality = quality;
            this.label = label;
            this.url = url;
        }
    }
}
