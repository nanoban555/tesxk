package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Shinigami extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://11.shinigami.asia";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI); }
    private static final String API = "https://api.shngm.io";
    private static final String FALLBACK_CDN = "https://storage.shngm.id";

    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, String> CHAPTER_ID_CACHE = new MangaMemoryCache<>(400, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    @Override protected String sourceLabel() { return "Shinigami"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            String safeQuery = query == null ? "" : query.trim();
            java.util.Map<String,String> filters = parseFilters(genre);
            String safeSort = firstNonEmpty(filters.get("sort"), sort, "latest").toLowerCase(Locale.ROOT);
            if (safeSort.equals("popular") || safeSort.equals("populer") || safeSort.equals("views") || safeSort.equals("trending")) safeSort = "popularity";
            if (!safeSort.equals("popularity") && !safeSort.equals("rating") && !safeSort.equals("latest")) safeSort = "latest";
            String sortOrder = firstNonEmpty(filters.get("sort_order"), "desc").toLowerCase(Locale.ROOT);
            if (!sortOrder.equals("asc") && !sortOrder.equals("desc")) sortOrder = "desc";

            StringBuilder url = new StringBuilder(API + "/v1/manga/list?page=" + safePage + "&page_size=30");
            if (!safeQuery.isEmpty()) url.append("&q=").append(URLEncoder.encode(safeQuery, "UTF-8"));
            url.append("&sort=").append(URLEncoder.encode(safeSort, "UTF-8"));
            url.append("&sort_order=").append(URLEncoder.encode(sortOrder, "UTF-8"));
            appendQuery(url, "status", filters.get("status"));
            appendQuery(url, "format", filters.get("format"));
            appendQuery(url, "type", filters.get("type"));
            appendQuery(url, "genre_include", filters.get("genre_include"));
            appendQuery(url, "genre_exclude", filters.get("genre_exclude"));
            if (!empty(filters.get("genre_include"))) url.append("&genre_include_mode=and");
            if (!empty(filters.get("genre_exclude"))) url.append("&genre_exclude_mode=and");

            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 30); return; }
            get(key, new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            JsonArray data = getArray(root, "data");
                            for (JsonElement el : data) if (el != null && el.isJsonObject()) {
                                MangaPost p = parsePost(el.getAsJsonObject());
                                String k = !p.slug.isEmpty() ? p.slug : p.title;
                                if (!k.isEmpty() && seen.add(k)) out.add(p);
                            }
                            JsonObject meta = getObject(root, "meta");
                            int current = meta == null ? safePage : getInt(meta, "page", safePage);
                            int total = meta == null ? -1 : firstPositive(getInt(meta, "total_page", -1), getInt(meta, "totalPage", -1));
                            boolean hasNext = total > 0 ? current < total : out.size() >= 30;
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Shinigami gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch (Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> out = new ArrayList<>();
        String[][] values = {
            {"Action","action"},{"Adaptation","adaptation"},{"Adult","adult"},{"Adventure","adventure"},{"Comedy","comedy"},
            {"Cooking","cooking"},{"Crime","crime"},{"Demon","demon"},{"Demons","demons"},{"Dra","dra-genre"},
            {"Drama","drama"},{"Ecchi","ecchi"},{"Fantasy","fantasy"},{"Fight","fight"},{"Game","game"},{"Gender Bender","gender-bender"},
            {"Harem","harem"},{"Historical","historical"},{"Horror","horror"},{"Isekai","isekai"},{"Josei","josei-genre"},
            {"Love","love"},{"Magic","magic"},{"Martial Arts","martial-arts"},{"Mature","mature"},{"Mecha","mecha"},
            {"Medical","medical"},{"Murim","murim"},{"Mystery","mystery"},{"Philosophical","philosophical"},{"Psychological","psychological"},
            {"Regression","regression"},{"Revenge","revenge"},{"Romance","romance"},{"School Life","school-life"},{"Sci-fi","sci-fi"},
            {"Seinen","seinen"},{"Shoujo","shoujo"},{"Shounen","shounen"},{"Slice of Life","slice-of-life"},{"Smut","smut"},
            {"Sports","sports"},{"Supernatural","supernatural"},{"Supranatural","supranatural"},{"Thriller","thriller"},{"Tragedy","tragedy"},
            {"Violence","violence"},{"Wuxia","wuxia"}
        };
        for (String[] v : values) out.add(new GenreItem(v[0], v[1]));
        cb.onSuccess(out, false);
    }

    private static java.util.Map<String,String> parseFilters(String raw) {
        java.util.Map<String,String> out = new java.util.HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split("\\|")) {
            String p = part == null ? "" : part.trim(); if (p.isEmpty()) continue;
            int i = p.indexOf(':');
            if (i < 0) { out.put("genre_include", appendCsv(out.get("genre_include"), slug(p))); continue; }
            String key = p.substring(0,i).trim().toLowerCase(Locale.ROOT);
            String val = p.substring(i+1).trim(); if (val.isEmpty()) continue;
            switch (key) {
                case "genre": out.put("genre_include", appendCsv(out.get("genre_include"), slug(val))); break;
                case "genre_exclude": out.put("genre_exclude", appendCsv(out.get("genre_exclude"), slug(val))); break;
                case "format": out.put("format", appendCsv(out.get("format"), slug(val))); break;
                case "type": out.put("type", appendCsv(out.get("type"), slug(val))); break;
                case "status": case "sort": case "sort_order": out.put(key, slug(val)); break;
                default: out.put("genre_include", appendCsv(out.get("genre_include"), slug(val))); break;
            }
        }
        return out;
    }
    private static String appendCsv(String a, String b) { return empty(a) ? b : a + "," + b; }
    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private static String slug(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", ""); }
    private static void appendQuery(StringBuilder b, String k, String v) { if (!empty(v)) { try { b.append("&").append(k).append("=").append(URLEncoder.encode(v, "UTF-8")); } catch(Exception ignored) {} } }

    private String normalizeFilterValue(String raw) {
        if (raw == null) return "";
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            if (value.toLowerCase(Locale.ROOT).startsWith("type:")) value = value.substring(5).trim();
            value = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+", "").replaceAll("-+$", "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (p != null && p.slug != null && !p.slug.isEmpty()) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || p.slug == null || p.slug.isEmpty()) continue;
            if (!loadType) {
                chapters(p.slug, chapterCallback(p, remaining, done));
                continue;
            }
            detail(p.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost d, boolean ignored) {
                    if (d != null) {
                        if (p.genre == null || p.genre.trim().isEmpty()) p.genre = d.genre;
                        if (d.typeLabel != null && !d.typeLabel.trim().isEmpty()) p.typeLabel = d.typeLabel;
                        if (p.author == null || p.author.trim().isEmpty()) p.author = d.author;
                        if (p.status == null || p.status.trim().isEmpty()) p.status = d.status;
                    }
                    if (loadChapter) chapters(p.slug, chapterCallback(p, remaining, done)); else if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (loadChapter) chapters(p.slug, chapterCallback(p, remaining, done)); else if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }
    private Result<ArrayList<MangaChapter>> chapterCallback(MangaPost p, java.util.concurrent.atomic.AtomicInteger remaining, Runnable done) {
        return new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (chapters != null && !chapters.isEmpty()) {
                    MangaChapter newest = chapters.get(0);
                    for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                    p.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
                    p.latestChapterDate = newest.date == null ? "" : newest.date;
                }
                if (remaining.decrementAndGet() <= 0 && done != null) done.run();
            }
            @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
        };
    }
    @Override public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        get(API + "/v1/manga/detail/" + slug, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        if (data == null) { MangaCoroutines.main(() -> cb.onError("Detail manga Shinigami kosong")); return; }
                        MangaPost parsed = parseDetail(slug, data);
                        DETAIL_CACHE.put(slug, parsed);
                        MangaCoroutines.main(() -> cb.onSuccess(parsed, false));
                    } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Shinigami gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        get(API + "/v1/chapter/" + slug + "/list?page_size=3000", new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonArray data = getArray(root, "data");
                        for (JsonElement el : data) {
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject item = el.getAsJsonObject();
                            float idx = getFloat(item, "chapter_number", -1f);
                            if (idx < 0) continue;
                            String key = MangaChapter.formatIndex(idx);
                            if (!seen.add(key)) continue;
                            String chapterId = firstNonEmpty(getString(item, "chapter_id"), getString(item, "chapterId"), getString(item, "id"), getString(item, "chapter_uuid"), getString(item, "uuid"));
                            if (!chapterId.isEmpty()) {
                                CHAPTER_ID_CACHE.put(slug + ":" + key, chapterId);
                                CHAPTER_ID_CACHE.put(slug + ":" + idx, chapterId);
                            }
                            MangaChapter chapter = new MangaChapter(slug, idx, getString(item, "chapter_title"), KomikcastClient.prettyDate(getString(item, "release_date")));
                            chapter.chapterId = chapterId;
                            out.add(chapter);
                        }
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter Shinigami gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String chapterKey = slug + ":" + MangaChapter.formatIndex(index);
        String pageKey = "shinigami:" + chapterKey;
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String chapterId = CHAPTER_ID_CACHE.get(chapterKey);
        if (chapterId == null || chapterId.trim().isEmpty()) chapterId = CHAPTER_ID_CACHE.get(slug + ":" + index);
        if (chapterId == null || chapterId.trim().isEmpty()) chapterId = findChapterIdFromCachedList(slug, index);
        if (chapterId == null || chapterId.trim().isEmpty()) {
            chapters(slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) { pages(slug, index, cb); }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        get(API + "/v1/chapter/detail/" + chapterId, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonObject data = getObject(root, "data");
                        JsonObject chapter = getObject(data, "chapter");
                        if (chapter == null) chapter = data;
                        String cdnBase = firstNonEmpty(getString(data, "base_url"), getString(data, "baseUrl"), getString(root, "base_url"), FALLBACK_CDN);
                        String path = firstNonEmpty(getString(chapter, "path"), getString(chapter, "image_path"), getString(chapter, "imagePath"), getString(chapter, "directory"));
                        JsonArray pages = getArray(chapter, "data");
                        if (pages.size() == 0) pages = getArray(chapter, "pages");
                        if (pages.size() == 0) pages = getArray(chapter, "images");
                        for (JsonElement image : pages) if (image != null && !image.isJsonNull()) {
                            String name = image.isJsonObject() ? firstNonEmpty(getString(image.getAsJsonObject(), "url"), getString(image.getAsJsonObject(), "src"), getString(image.getAsJsonObject(), "image"), getString(image.getAsJsonObject(), "filename"), getString(image.getAsJsonObject(), "name")) : image.getAsString();
                            String url = buildImageUrl(cdnBase, path, name);
                            if (url.startsWith("http") && seen.add(url)) out.add(url);
                        }
                        if (!out.isEmpty()) PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Shinigami gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String findChapterIdFromCachedList(String slug, float index) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached == null || cached.isEmpty()) return "";
        for (MangaChapter ch : cached) {
            if (ch == null) continue;
            if (Math.abs(ch.index - index) < 0.001f && ch.chapterId != null && !ch.chapterId.trim().isEmpty()) return ch.chapterId.trim();
        }
        return "";
    }

    private String buildImageUrl(String cdnBase, String path, String name) {
        String safeBase = normalizeBaseUrl(firstNonEmpty(cdnBase, FALLBACK_CDN));
        String safeName = name == null ? "" : name.trim();
        if (safeName.startsWith("http://") || safeName.startsWith("https://")) {
            return replaceLegacyCdn(safeName, safeBase);
        }
        String safePath = path == null ? "" : path.trim();
        if (safePath.startsWith("http://") || safePath.startsWith("https://")) {
            if (!safePath.endsWith("/")) safePath = safePath + "/";
            while (safeName.startsWith("/")) safeName = safeName.substring(1);
            return replaceLegacyCdn(safePath, safeBase) + safeName;
        }
        while (safePath.startsWith("/")) safePath = safePath.substring(1);
        while (safePath.endsWith("/")) safePath = safePath.substring(0, safePath.length() - 1);
        while (safeName.startsWith("/")) safeName = safeName.substring(1);
        if (safePath.isEmpty()) return safeBase + "/" + safeName;
        return safeBase + "/" + safePath + "/" + safeName;
    }

    private String normalizeBaseUrl(String value) {
        String base = value == null ? "" : value.trim();
        if (base.isEmpty()) base = FALLBACK_CDN;
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://" + base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private String replaceLegacyCdn(String url, String dynamicBase) {
        String value = url == null ? "" : url.trim();
        String[] legacyBases = {
                "https://storage.shngm.id", "http://storage.shngm.id",
                "https://storage.shngm.io", "http://storage.shngm.io",
                "https://storage.shinigami.id", "http://storage.shinigami.id"
        };
        for (String legacy : legacyBases) {
            if (value.startsWith(legacy)) return dynamicBase + value.substring(legacy.length());
        }
        return value;
    }

    private void get(String url, Result<JsonObject> cb) {
        Request req = new Request.Builder().url(url)
                .header("Referer", base() + "/")
                .header("Origin", base())
                .header("Accept", "application/json")
                .header("DNT", "1")
                .header("Sec-GPC", "1")
                .header("Accept-Language", "id,en-US;q=0.9")
                .header("User-Agent", "Mozilla/5.0")
                .build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code())); return; }
                try { JsonObject obj = JsonParser.parseString(body).getAsJsonObject(); MangaCoroutines.main(() -> cb.onSuccess(obj, false)); }
                catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Data Shinigami gagal dibaca")); }
            }
        });
    }

    private MangaPost parsePost(JsonObject item) {
        String slug = firstNonEmpty(getString(item, "manga_id"), getString(item, "mangaId"), getString(item, "id"), getString(item, "slug"));
        String title = getString(item, "title");
        String cover = firstNonEmpty(getString(item, "cover_image_url"), getString(item, "coverImageUrl"), getString(item, "thumbnail"), getString(item, "cover"));
        String format = firstNonEmpty(getString(item, "format"), getString(item, "type"), getString(item, "comic_type"), taxonomyText(item, "Format"));
        String genre = taxonomyText(item, "Genre");
        return new MangaPost(slug, title, cover, "", "", "", genre, firstNonEmpty(format, inferTypeFromText(title + " " + genre)), "", "").withSource(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI, "Shinigami");
    }

    private MangaPost parseDetail(String slug, JsonObject data) {
        String description = getString(data, "description");
        String status = statusLabel(getInt(data, "status", 0));
        JsonObject taxonomy = getObject(data, "taxonomy");
        String author = joinTaxonomy(taxonomy, "Author");
        String artist = joinTaxonomy(taxonomy, "Artist");
        String genres = joinTaxonomy(taxonomy, "Genre");
        String format = joinTaxonomy(taxonomy, "Format");
        String creator = firstNonEmpty(author, artist);
        return new MangaPost(slug, getString(data, "title"), firstNonEmpty(getString(data, "cover_image_url"), getString(data, "coverImageUrl"), getString(data, "thumbnail")), creator, status, description, genres, firstNonEmpty(format, inferTypeFromText(genres)), "", "").withSource(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI, "Shinigami");
    }

    private String taxonomyText(JsonObject item, String key) {
        JsonObject taxonomy = getObject(item, "taxonomy");
        String joined = joinTaxonomy(taxonomy, key);
        if (!joined.isEmpty()) return joined;
        JsonArray arr = getArray(item, key.toLowerCase(Locale.ROOT));
        ArrayList<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonObject()) {
                String name = getString(el.getAsJsonObject(), "name");
                if (!name.isEmpty()) out.add(name);
            } else {
                String name = el.getAsString();
                if (name != null && !name.trim().isEmpty()) out.add(name.trim());
            }
        }
        return android.text.TextUtils.join(", ", out);
    }

    private String joinTaxonomy(JsonObject taxonomy, String key) {
        JsonArray arr = getArray(taxonomy, key);
        ArrayList<String> out = new ArrayList<>();
        for (JsonElement el : arr) if (el != null && el.isJsonObject()) {
            String name = getString(el.getAsJsonObject(), "name");
            if (!name.isEmpty()) out.add(name);
        }
        return android.text.TextUtils.join(", ", out);
    }

    private String inferTypeFromText(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (t.contains("manhwa")) return "manhwa";
        if (t.contains("manhua")) return "manhua";
        if (t.contains("webtoon")) return "webtoon";
        return "manga";
    }

    private String statusLabel(int status) {
        if (status == 1) return "Ongoing";
        if (status == 2) return "Completed";
        return "Unknown";
    }

    private static JsonObject getObject(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; } catch(Exception e){return null;} }
    private static JsonArray getArray(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); } catch(Exception e){return new JsonArray();} }
    private static String getString(JsonObject o, String k) { try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; } catch(Exception e){return "";} }
    private static String firstNonEmpty(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return ""; }
    private static int getInt(JsonObject o, String k, int def) { try { return o != null && o.has(k) ? o.get(k).getAsInt() : def; } catch(Exception e){return def;} }
    private static int firstPositive(int... values) { for (int v : values) if (v > 0) return v; return 0; }
    private static float getFloat(JsonObject o, String k, float def) { try { return o != null && o.has(k) ? o.get(k).getAsFloat() : def; } catch(Exception e){return def;} }
}
