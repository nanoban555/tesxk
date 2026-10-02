package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Ainzscanss extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_AINZSCANSS); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    @Override protected String sourceLabel() { return "Ainzscanss"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            int limit = query != null && !query.trim().isEmpty() ? 20 : 20;
            String safeQuery = query == null ? "" : query.trim();
            FilterSpec filter = parseFilter(genre);
            HttpUrl.Builder builder = HttpUrl.parse(apiBase() + "/search").newBuilder();
            builder.addQueryParameter("limit", String.valueOf(limit));
            builder.addQueryParameter("page", String.valueOf(safePage));
            if (safeQuery.isEmpty()) builder.addQueryParameter("type", "COMIC");
            if (!safeQuery.isEmpty()) builder.addQueryParameter("q", safeQuery);
            if (!filter.genre.isEmpty()) builder.addQueryParameter("genre", filter.genre);
            boolean projectOnly = safeQuery.isEmpty() && filter.genre.isEmpty() && filter.type.isEmpty();
            if (projectOnly) builder.addQueryParameter("project_only", "1");
            String mappedSort = normalizeSort(sort, !safeQuery.isEmpty());
            builder.addQueryParameter("sort", mappedSort);
            if (!"popular".equals(mappedSort)) builder.addQueryParameter("order", "desc");
            String url = builder.build().toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= limit); return; }
            getJson(url, new Result<JsonElement>() {
                @Override public void onSuccess(JsonElement root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                            JsonArray data = getArray(obj, "data");
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            for (JsonElement el : data) {
                                if (el == null || !el.isJsonObject()) continue;
                                MangaPost post = parsePost(el.getAsJsonObject());
                                String key = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
                                if (key != null && !key.trim().isEmpty() && seen.add(key.trim())) out.add(post);
                            }
                            int current = getInt(obj, "page", safePage);
                            int totalPages = getInt(obj, "total_pages", current);
                            boolean hasNext = current < totalPages;
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Ainzscanss gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getJson(apiBase() + "/genres", new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonArray arr = root != null && root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
                        ArrayList<GenreItem> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        for (JsonElement el : arr) {
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject item = el.getAsJsonObject();
                            String title = getString(item, "name");
                            String value = getString(item, "slug");
                            if (!title.isEmpty() && !value.isEmpty() && seen.add(value)) out.add(new GenreItem(title, value));
                        }
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Genre Ainzscanss gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(apiBase() + "/series/comic/" + clean, new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        if (obj == null) { MangaCoroutines.main(() -> cb.onError("Detail Ainzscanss kosong")); return; }
                        MangaPost post = parsePost(obj);
                        ArrayList<MangaChapter> chapters = parseChapters(clean, obj);
                        post.totalChapters = chapters.size();
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Ainzscanss gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getJson(apiBase() + "/series/comic/" + clean, new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        ArrayList<MangaChapter> out = parseChapters(clean, obj);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter Ainzscanss gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String clean = cleanSlug(slug);
        String key = clean + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(clean, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter target = null;
                if (chapters != null) for (MangaChapter ch : chapters) if (Math.abs(ch.index - index) < 0.0001f) { target = ch; break; }
                if (target == null || target.slug == null || target.slug.trim().isEmpty()) { cb.onError("Chapter Ainzscanss tidak ditemukan"); return; }
                String chapterSlug = cleanSlug(target.slug);
                getJson(apiBase() + "/series/comic/" + clean + "/chapter/" + chapterSlug, new Result<JsonElement>() {
                    @Override public void onSuccess(JsonElement root, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                                JsonObject chapter = getObject(obj, "chapter");
                                if (chapter == null) { MangaCoroutines.main(() -> cb.onError("Chapter Ainzscanss kosong")); return; }
                                JsonArray pages = getArray(chapter, "pages");
                                ArrayList<PageItem> pageItems = new ArrayList<>();
                                LinkedHashSet<String> seen = new LinkedHashSet<>();
                                for (JsonElement el : pages) {
                                    if (el == null || !el.isJsonObject()) continue;
                                    JsonObject page = el.getAsJsonObject();
                                    String url = absolutize(getString(page, "image_url"));
                                    if (!url.startsWith("http") || !seen.add(url)) continue;
                                    pageItems.add(new PageItem(getInt(page, "page_number", pageItems.size() + 1), url));
                                }
                                Collections.sort(pageItems, Comparator.comparingInt(a -> a.number));
                                ArrayList<String> out = new ArrayList<>();
                                for (PageItem item : pageItems) out.add(item.url);
                                JsonArray units = getArray(obj, "units");
                                if (units.size() > 0) CHAPTER_CACHE.put(clean, parseUnits(clean, units));
                                PAGE_CACHE.put(key, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Ainzscanss gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        boolean needs = false;
        for (MangaPost post : list) if (post != null && post.slug != null && !post.slug.trim().isEmpty() && ((post.latestChapter == null || post.latestChapter.trim().isEmpty()) || (post.typeLabel == null || post.typeLabel.trim().isEmpty()))) { needs = true; break; }
        if (!needs) { if (done != null) MangaCoroutines.main(done); return; }
        super.enrichLatest(list, done);
    }

    private ArrayList<MangaChapter> parseChapters(String seriesSlug, JsonObject obj) {
        if (obj == null) return new ArrayList<>();
        return parseUnits(seriesSlug, getArray(obj, "units"));
    }

    private ArrayList<MangaChapter> parseUnits(String seriesSlug, JsonArray units) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement el : units) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject unit = el.getAsJsonObject();
            String chapterSlug = getString(unit, "slug");
            float number = parseChapterNumber(firstNonEmpty(getString(unit, "number"), getString(unit, "sort_number"), getString(unit, "title")));
            if (number < 0f || chapterSlug.isEmpty()) continue;
            String key = MangaChapter.formatIndex(number);
            if (!seen.add(key)) continue;
            MangaChapter chapter = new MangaChapter(chapterSlug, number, "", prettyDate(getString(unit, "created_at")));
            chapter.chapterId = getString(unit, "id");
            out.add(chapter);
        }
        return out;
    }

    private MangaPost parsePost(JsonObject item) {
        String slug = getString(item, "slug");
        ArrayList<String> genreList = new ArrayList<>();
        JsonArray genres = getArray(item, "genres");
        for (JsonElement el : genres) {
            if (el == null || !el.isJsonObject()) continue;
            String name = getString(el.getAsJsonObject(), "name");
            if (!name.isEmpty()) genreList.add(name);
        }
        String primaryGenre = getString(item, "primary_genre");
        if (genreList.isEmpty() && !primaryGenre.isEmpty()) genreList.add(primaryGenre);
        String genre = TextUtils.join(", ", genreList);
        String type = firstNonEmpty(getString(item, "comic_subtype"), getString(item, "novel_subtype"), getString(item, "type"));
        String latestNumber = firstNonEmpty(getString(item, "comic_latest_chapter_number"), getString(item, "series_latest_chapter_number"), getString(item, "novel_latest_chapter_number"));
        String latestChapter = latestNumber.isEmpty() ? "" : "Chapter " + formatNumber(latestNumber);
        JsonArray units = getArray(item, "units");
        if (latestChapter.isEmpty() && units.size() > 0 && units.get(0).isJsonObject()) {
            JsonObject unit = units.get(0).getAsJsonObject();
            float number = parseChapterNumber(firstNonEmpty(getString(unit, "number"), getString(unit, "sort_number"), getString(unit, "title")));
            if (number >= 0f) latestChapter = "Chapter " + MangaChapter.formatIndex(number);
        }
        String latestDate = firstNonEmpty(getString(item, "latest_activity_at"), getString(item, "updated_at"), getString(item, "created_at"));
        String status = firstNonEmpty(getString(item, "comic_status"), getString(item, "series_status"), getString(item, "novel_status"));
        MangaPost post = new MangaPost(slug, getString(item, "title"), absolutize(getString(item, "poster_image_url")), firstNonEmpty(getString(item, "author_name"), getString(item, "artist_name"), getString(item, "publisher_name")), status, getString(item, "synopsis"), genre, type, latestChapter, prettyDate(latestDate)).withSource(MangaSettingsManager.MANGA_SOURCE_AINZSCANSS, "Ainzscanss");
        post.totalChapters = units.size();
        return post;
    }

    private void getJson(String url, Result<JsonElement> cb) {
        Request req = new Request.Builder().url(url).header("Referer", base() + "/").header("Origin", base()).header("Accept", "application/json").header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8").header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try { JsonElement parsed = JsonParser.parseString(body); main.post(() -> cb.onSuccess(parsed, false)); }
                catch(Exception e) { main.post(() -> cb.onError("Data Ainzscanss gagal dibaca")); }
            }
        });
    }

    private static String apiBase() {
        return "https://api.ainzscans01.com/api";
    }

    private static String normalizeSort(String sort, boolean searching) {
        String value = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("views".equals(value) || "view".equals(value) || "top_views".equals(value) || "popularity".equals(value)) return searching ? "popular" : "views";
        if ("bookmark".equals(value) || "favorite".equals(value) || "favorites".equals(value) || "top_favorite".equals(value)) return "bookmark";
        if ("rate".equals(value) || "rating".equals(value) || "top_rate".equals(value)) return "rate";
        if ("popular".equals(value)) return searching ? "popular" : "views";
        return searching ? "popular" : "latest";
    }

    private static FilterSpec parseFilter(String raw) {
        FilterSpec spec = new FilterSpec();
        if (raw == null || raw.trim().isEmpty()) return spec;
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            if (value.startsWith("type:")) spec.type = normalizeType(value.substring(5));
            else spec.genre = value;
        }
        return spec;
    }

    private static String normalizeType(String raw) {
        String type = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("MANHWA".equals(type)) return "MANHWA";
        if ("MANHUA".equals(type)) return "MANHUA";
        if ("WEBTOON".equals(type)) return "WEBTOON";
        if ("MANGA".equals(type)) return "MANGA";
        return type;
    }

    private static String cleanSlug(String slug) {
        if (slug == null) return "";
        String value = slug.trim();
        int q = value.indexOf('?');
        if (q >= 0) value = value.substring(0, q);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int idx = value.lastIndexOf('/');
        if (idx >= 0) value = value.substring(idx + 1);
        return value;
    }

    private static String cleanChapterTitle(String title, float number, String seriesSlug) {
        String value = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) return "";
        String idx = MangaChapter.formatIndex(number);
        value = value.replaceFirst("(?i)^ch(?:apter)?\\s*" + java.util.regex.Pattern.quote(idx) + "(?:\\.0+)?\\s*[:\\-]?\\s*", "").trim();
        String seriesTitle = seriesSlug == null ? "" : seriesSlug.replace('-', ' ').trim();
        if (!seriesTitle.isEmpty() && value.equalsIgnoreCase(seriesTitle)) return "";
        return value;
    }

    private static float parseChapterNumber(String raw) {
        if (raw == null) return -1f;
        String value = raw.trim().replace(',', '.');
        try { return Float.parseFloat(value); } catch(Exception ignored) { }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(value);
        if (matcher.find()) {
            try { return Float.parseFloat(matcher.group(1)); } catch(Exception ignored) { }
        }
        return -1f;
    }

    private static String formatNumber(String raw) {
        float number = parseChapterNumber(raw);
        if (number >= 0f) return MangaChapter.formatIndex(number);
        return raw == null ? "" : raw.trim();
    }

    public static String prettyDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        try {
            String r = raw.replace("Z", "+00:00");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(in.parse(r));
        } catch(Exception ignored) { }
        return raw.length() > 10 ? raw.substring(0, 10) : raw;
    }

    private static String absolutize(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        String value = url.trim();
        if (value.startsWith("http")) return value;
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/api/")) return "https://api.ainzscans01.com" + value;
        if (value.startsWith("/")) return base() + value;
        return value;
    }

    private static JsonObject getObject(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; } catch(Exception e){ return null; } }
    private static JsonArray getArray(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); } catch(Exception e){ return new JsonArray(); } }
    private static String getString(JsonObject o, String k) { try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : ""; } catch(Exception e){ return ""; } }
    private static int getInt(JsonObject o, String k, int def) { try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def; } catch(Exception e){ return def; } }
    private static String firstNonEmpty(String... values) { for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim(); return ""; }

    private static final class FilterSpec {
        String genre = "";
        String type = "";
    }

    private static final class PageItem {
        final int number;
        final String url;
        PageItem(int number, String url) {
            this.number = number;
            this.url = url;
        }
    }
}
