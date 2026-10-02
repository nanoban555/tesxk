package miku.moe.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Comicaso extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_COMICASO); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final int LIMIT = 60;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, String> CURSOR_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, CACHE_TTL);
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Comicaso"; }

    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        CURSOR_CACHE.clear();
    }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            Listing listing = listingForSort(sort);
            String q = query == null ? "" : query.trim();
            String genreValue = extractGenre(genre);
            int targetPage = Math.max(1, page);
            String cursor = targetPage > 1 ? CURSOR_CACHE.get(cursorKey(targetPage, listing, q, genreValue)) : "";
            HttpUrl url = buildListUrl(targetPage, listing, q, genreValue, cursor);
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= limitForGenre(genreValue)); return; }
            getJson(key, new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            JsonArray data = getArray(root, "data");
                            for (JsonElement element : data) {
                                if (element == null || !element.isJsonObject()) continue;
                                MangaPost post = parseListPost(element.getAsJsonObject());
                                String unique = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
                                if (!unique.isEmpty() && seen.add(unique)) out.add(post);
                            }
                            boolean hasNext = getBoolean(root, "has_more", out.size() >= limitForGenre(genreValue));
                            String nextCursor = getString(root, "next_cursor");
                            if (!nextCursor.trim().isEmpty()) CURSOR_CACHE.put(cursorKey(targetPage + 1, listing, q, genreValue), nextCursor.trim());
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Comicaso gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("normal");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] item : NORMAL_GENRES) out.add(new GenreItem(item[0], item[1]));
        GENRE_CACHE.put("normal", new ArrayList<>(out));
        cb.onSuccess(out, false);
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        SourceSlug target = decodeSlug(slug);
        String key = target.key();
        MangaPost cached = DETAIL_CACHE.get(key);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        HttpUrl url = HttpUrl.parse(base() + "/api/manga.php").newBuilder()
                .addQueryParameter("source", target.source)
                .addQueryParameter("slug", target.slug)
                .addQueryParameter("platform", "web")
                .build();
        getJson(url.toString(), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        if (data == null) { MangaCoroutines.main(() -> cb.onError("Detail Comicaso kosong")); return; }
                        MangaPost post = parseDetailPost(target.source, data);
                        ArrayList<MangaChapter> chapters = parseChapters(target.source, target.slug, getArray(data, "chapters"));
                        post.totalChapters = chapters.size();
                        DETAIL_CACHE.put(key, post);
                        CHAPTER_CACHE.put(key, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Comicaso gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        SourceSlug target = decodeSlug(slug);
        String key = target.key();
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        detail(key, new Result<MangaPost>() {
            @Override public void onSuccess(MangaPost data, boolean hasNext) {
                ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(key);
                cb.onSuccess(cached == null ? new ArrayList<>() : new ArrayList<>(cached), false);
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        SourceSlug target = decodeSlug(slug);
        String pageKey = target.key() + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(target.key(), new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = findChapter(chapters, index);
                if (chapter == null || chapter.slug == null || chapter.slug.trim().isEmpty()) { cb.onError("Chapter Comicaso tidak ditemukan"); return; }
                HttpUrl.Builder builder = HttpUrl.parse(base() + "/api/chapter.php").newBuilder()
                        .addQueryParameter("source", target.source)
                        .addQueryParameter("manga", target.slug)
                        .addQueryParameter("chapter", chapter.slug.trim())
                        .addQueryParameter("platform", "web");
                if (chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()) builder.addQueryParameter("token", chapter.chapterId.trim());
                getJson(builder.build().toString(), new Result<JsonObject>() {
                    @Override public void onSuccess(JsonObject root, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                JsonObject data = getObject(root, "data");
                                if (data == null) { MangaCoroutines.main(() -> cb.onError("Chapter Comicaso kosong")); return; }
                                ArrayList<String> out = parsePages(data);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Comicaso gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (done != null) MangaCoroutines.main(done);
    }

    private HttpUrl buildListUrl(int page, Listing listing, String query, String genre, String cursor) {
        int limit = limitForGenre(genre);
        int offset = Math.max(0, page - 1) * limit;
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/api/home.php").newBuilder()
                .addQueryParameter("source", listing.source)
                .addQueryParameter("q", query == null ? "" : query)
                .addQueryParameter("mode", listing.mode)
                .addQueryParameter("type", listing.type)
                .addQueryParameter("limit", String.valueOf(limit));
        if (cursor != null && !cursor.trim().isEmpty()) builder.addQueryParameter("cursor", cursor.trim());
        else builder.addQueryParameter("offset", String.valueOf(offset));
        if (genre != null && !genre.trim().isEmpty()) builder.addQueryParameter("genre", genre.trim());
        return builder.build();
    }

    private int limitForGenre(String genre) {
        return genre == null || genre.trim().isEmpty() ? LIMIT : 30;
    }

    private String cursorKey(int page, Listing listing, String query, String genre) {
        return page + "|" + listing.source + "|" + listing.mode + "|" + listing.type + "|" + (query == null ? "" : query.trim()) + "|" + (genre == null ? "" : genre.trim());
    }

    private MangaPost parseListPost(JsonObject item) {
        String apiSource = cleanApiSource(getString(item, "source"));
        String slug = getString(item, "slug");
        String genre = joinGenres(getArray(item, "genres"));
        String synopsis = cleanHtml(getString(item, "synopsis"));
        String latest = firstNonEmpty(getString(item, "latest_chapter"), getString(item, "latest_chapter_title"), getString(item, "last_chapter"));
        MangaPost post = new MangaPost(encodeSlug(apiSource, slug), getString(item, "title"), getString(item, "thumbnail"), getString(item, "author"), normalizeStatus(getString(item, "status")), synopsis, genre, getString(item, "type"), latest, epochDate(firstNonEmpty(getString(item, "updated_at"), getString(item, "manga_date")))).withSource(MangaSettingsManager.MANGA_SOURCE_COMICASO, "Comicaso");
        post.info = getString(item, "alternative");
        return post;
    }

    private MangaPost parseDetailPost(String apiSource, JsonObject data) {
        String slug = getString(data, "slug");
        String genre = joinGenres(getArray(data, "genres"));
        String latest = firstNonEmpty(getString(data, "latest_chapter"), getString(data, "latest_chapter_title"), getString(data, "last_chapter"));
        MangaPost post = new MangaPost(encodeSlug(apiSource, slug), getString(data, "title"), getString(data, "thumbnail"), getString(data, "author"), normalizeStatus(getString(data, "status")), cleanHtml(getString(data, "synopsis")), genre, getString(data, "type"), latest, epochDate(firstNonEmpty(getString(data, "updated_at"), getString(data, "manga_date")))).withSource(MangaSettingsManager.MANGA_SOURCE_COMICASO, "Comicaso");
        post.info = getString(data, "alternative");
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String source, String mangaSlug, JsonArray array) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String chapterSlug = getString(item, "slug");
            if (chapterSlug.isEmpty()) continue;
            float index = parseChapterIndex(firstNonEmpty(getString(item, "title"), chapterSlug), -1f);
            if (index < 0f) continue;
            String key = MangaChapter.formatIndex(index);
            if (!seen.add(key)) continue;
            MangaChapter chapter = new MangaChapter(chapterSlug, index, getString(item, "title"), epochDate(getString(item, "date")));
            chapter.chapterId = getString(item, "chapter_token");
            out.add(chapter);
        }
        Collections.sort(out, (a, b) -> Float.compare(b.index, a.index));
        return out;
    }

    private ArrayList<String> parsePages(JsonObject data) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray images = getArray(data, "images");
        for (JsonElement element : images) {
            if (element == null || element.isJsonNull()) continue;
            String url = element.getAsString();
            if (url != null && url.startsWith("http") && seen.add(url)) out.add(url);
        }
        JsonArray pages = getArray(data, "pages");
        for (JsonElement element : pages) {
            if (element == null || !element.isJsonObject()) continue;
            String url = getString(element.getAsJsonObject(), "src");
            if (url.startsWith("http") && seen.add(url)) out.add(url);
        }
        return out;
    }

    private MangaChapter findChapter(ArrayList<MangaChapter> chapters, float index) {
        if (chapters == null) return null;
        for (MangaChapter chapter : chapters) if (chapter != null && Math.abs(chapter.index - index) < 0.0001f) return chapter;
        return chapters.isEmpty() ? null : chapters.get(0);
    }

    private void getJson(String url, Result<JsonObject> cb) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.6")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Referer", base() + "/sw.js")
                .header("Origin", base())
                .header("X-Comicaso-Platform", "web")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                .build();
        CloudflareHelper.enqueue(client, request, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    MangaCoroutines.main(() -> cb.onSuccess(obj, false));
                } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Data Comicaso gagal dibaca")); }
            }
        });
    }

    private Listing listingForSort(String sort) {
        String value = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("new".equals(value)) return new Listing("all", "new", "all");
        if ("completed".equals(value) || "complete".equals(value)) return new Listing("all", "completed", "all");
        if ("manga".equals(value)) return new Listing("all", "update", "manga");
        if ("manhwa".equals(value)) return new Listing("all", "update", "manhwa");
        if ("manhua".equals(value)) return new Listing("all", "update", "manhua");
        if ("adult_update".equals(value)) return new Listing("medusa", "update", "all");
        if ("adult_new".equals(value)) return new Listing("medusa", "new", "all");
        if ("adult_completed".equals(value)) return new Listing("medusa", "completed", "all");
        if ("adult_manga".equals(value)) return new Listing("medusa", "update", "manga");
        if ("adult_manhwa".equals(value)) return new Listing("medusa", "update", "manhwa");
        if ("adult_manhua".equals(value)) return new Listing("medusa", "update", "manhua");
        return new Listing("all", "update", "all");
    }

    private String extractGenre(String value) {
        if (value == null) return "";
        String[] parts = value.split("\\|");
        for (String part : parts) {
            String item = part == null ? "" : part.trim();
            if (item.isEmpty() || item.startsWith("type:") || item.startsWith("status:")) continue;
            return item;
        }
        return "";
    }

    private static String encodeSlug(String source, String slug) { return cleanApiSource(source) + "::" + (slug == null ? "" : slug.trim()); }

    private static SourceSlug decodeSlug(String raw) {
        String value = raw == null ? "" : raw.trim();
        int index = value.indexOf("::");
        if (index > 0) return new SourceSlug(cleanApiSource(value.substring(0, index)), value.substring(index + 2).trim());
        return new SourceSlug("comicazen", value);
    }

    private static String cleanApiSource(String source) {
        String value = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if ("medusa".equals(value)) return "medusa";
        return "comicazen";
    }

    private static String joinGenres(JsonArray array) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) continue;
            String value = element.getAsString().trim();
            if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) values.add(value);
        }
        return android.text.TextUtils.join(", ", values);
    }

    private static String cleanHtml(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        return Jsoup.parse(raw).text().trim();
    }

    private static String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("end") || lower.equals("completed") || lower.equals("complete")) return "Completed";
        if (lower.equals("on-going") || lower.equals("ongoing")) return "Ongoing";
        return value;
    }

    private static float parseChapterIndex(String text, float fallback) {
        String value = text == null ? "" : text.replace(',', '.');
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(value);
        if (!matcher.find()) return fallback;
        try { return Float.parseFloat(matcher.group(1)); } catch(Exception ignored) { return fallback; }
    }

    private static String epochDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String value = raw.trim();
        try {
            long number = Long.parseLong(value);
            if (number <= 0L) return "";
            long millis = value.length() > 10 ? number : number * 1000L;
            return new SimpleDateFormat("d MMMM yyyy", new Locale("id", "ID")).format(new Date(millis));
        } catch(Exception ignored) {
            return MangaDateFormatter.format(value);
        }
    }

    private static JsonObject getObject(JsonObject object, String key) {
        try { return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null; } catch(Exception e) { return null; }
    }

    private static JsonArray getArray(JsonObject object, String key) {
        try { return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); } catch(Exception e) { return new JsonArray(); }
    }

    private static String getString(JsonObject object, String key) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; } catch(Exception e) { return ""; }
    }

    private static boolean getBoolean(JsonObject object, String key, boolean def) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : def; } catch(Exception e) { return def; }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static final class Listing {
        final String source;
        final String mode;
        final String type;
        Listing(String source, String mode, String type) {
            this.source = source;
            this.mode = mode;
            this.type = type;
        }
    }

    private static final class SourceSlug {
        final String source;
        final String slug;
        SourceSlug(String source, String slug) {
            this.source = cleanApiSource(source);
            this.slug = slug == null ? "" : slug.trim();
        }
        String key() { return encodeSlug(source, slug); }
    }

    private static final String[][] NORMAL_GENRES = new String[][]{
            {"Action","action"},{"Adaptation","adaptation"},{"Adult","adult"},{"Adventure","adventure"},{"College Life","college-life"},{"Comedy","comedy"},{"Cooking","cooking"},{"Crime","crime"},{"Drama","drama"},{"Ecchi","ecchi"},{"Fantasy","fantasy"},{"Full Color","full-color"},{"Harem","harem"},{"Historical","historical"},{"Horror","horror"},{"Isekai","isekai"},{"Josei(W)","josei-w"},{"Magic","magic"},{"Martial Arts","martial-arts"},{"Mature","mature"},{"Mystery","mystery"},{"Office Workers","office-workers"},{"Omegaverse","omegaverse"},{"Psychological","psychological"},{"Reincarnation","reincarnation"},{"Romance","romance"},{"School Life","school-life"},{"Seinen(M)","seinen-m"},{"Shoujo(G)","shoujo-g"},{"Shounen ai","shounen-ai"},{"Shounen(B)","shounen-b"},{"Showbiz","showbiz"},{"Slice of Life","slice-of-life"},{"Smut","smut"},{"Sports","sports"},{"Supernatural","supernatural"},{"Tragedy","tragedy"},{"Yakuzas","yakuzas"},{"Yaoi(BL)","yaoi-bl"}
    };
}
