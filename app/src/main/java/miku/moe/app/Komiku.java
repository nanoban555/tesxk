package miku.moe.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Komiku extends KomikcastClient {
    private static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_KOMIKU;
    private static final String SOURCE_LABEL = "Komiku Asia";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final int PER_PAGE = 20;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(32, CACHE_TTL);
    private static final MangaMemoryCache<String, String> PAGE_REFERER_CACHE = new MangaMemoryCache<>(32, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    private static final MangaMemoryCache<String, Long> ID_CACHE = new MangaMemoryCache<>(128, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;

    protected static String base() { return MangaSettingsManager.getSourceDomain(SOURCE_ID); }
    private static String api() { return base() + "/api/v2"; }
    @Override protected String sourceLabel() { return SOURCE_LABEL; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            String url = buildListUrl(page, sort, query, genre);
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= PER_PAGE); return; }
            getJson(url, browseReferer(sort, query, genre), new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            JsonArray items = firstArray(root, "items", "data", "comics", "results", "list");
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            String requestedGenre = extractGenreFilter(genre);
                            String requestedType = requestedTypeFilter(sort, genre);
                            String requestedStatus = requestedStatusFilter(sort, genre);
                            for (JsonElement e : items) {
                                if (e == null || !e.isJsonObject()) continue;
                                JsonObject item = e.getAsJsonObject();
                                if (!requestedGenre.isEmpty() && !postHasGenre(item, requestedGenre)) continue;
                                if (!requestedType.isEmpty() && !str(item, "type").equalsIgnoreCase(requestedType)) continue;
                                if (!requestedStatus.isEmpty() && !str(item, "status").equalsIgnoreCase(requestedStatus)) continue;
                                MangaPost p = parseApiPost(item);
                                String key = p.slug == null || p.slug.isEmpty() ? p.title : p.slug;
                                if (!key.isEmpty() && seen.add(key)) out.add(p);
                            }
                            if (items.size() > 0 && out.isEmpty()) {
                                MangaCoroutines.main(() -> cb.onError("Daftar Komiku Asia gagal dibaca: response berisi data tetapi parser/filter tidak menghasilkan item"));
                                return;
                            }
                            int current = intValue(root, "page", Math.max(1, page));
                            int totalPages = intValue(root, "totalPages", current);
                            boolean hasNext = current < totalPages;
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Komiku Asia gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch (Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String[] genres = {"4-Koma","Action","Adaptation","Adventure","apocalypse","Comedy","Cooking","Crime","Demon","Drama","Ecchi","Fantasy","Game","Gender bender","Harem","Historical","Horror","Isekai","Josei","Magic","Martial Arts","Mature","Mecha","Medical","Military","Monsters","murim","Mystery","One-Shot","Police","Psychological","Regression","Reincarnation","Revenge","Romance","School","School Life","Sci-Fi","Seinen","Shoujo","Shoujo Ai","Shounen","Shounen Ai","Slice of Life","Sports","Super Power","Superhero","Supernatural","Survival","System","Thriller","Tragedy","Vampire","Webtoons","Wuxia"};
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String g : genres) out.add(new GenreItem(g, g));
        GENRE_CACHE.put("genres", new ArrayList<>(out));
        cb.onSuccess(out, false);
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty() || !MangaSettingsManager.shouldLoadLatestChapterLabel()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(list.size());
        for (MangaPost p : list) {
            if (p == null || p.slug == null || p.slug.isEmpty()) { if (remaining.decrementAndGet() == 0 && done != null) done.run(); continue; }
            if (p.latestChapter != null && !p.latestChapter.trim().isEmpty()) { if (remaining.decrementAndGet() == 0 && done != null) done.run(); continue; }
            detail(p.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost d, boolean ignored) {
                    if (d != null) { p.latestChapter = d.latestChapter; p.latestChapterDate = d.latestChapterDate; p.totalChapters = d.totalChapters; }
                    if (remaining.decrementAndGet() == 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() == 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String cleanSlug = slugFromUrl(slug);
        MangaPost cached = DETAIL_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(api() + "/comics/" + encodePath(cleanSlug), base() + "/manga/" + encodePath(cleanSlug), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                long comicId = longValue(root, "id", 0L);
                if (comicId > 0) ID_CACHE.put(cleanSlug, comicId);
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseApiPost(root);
                        post.totalChapters = intValue(root, "chapterCount", post.totalChapters);
                        chapters(cleanSlug, new Result<ArrayList<MangaChapter>>() {
                            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean ignored2) {
                                if (chapters != null && !chapters.isEmpty()) {
                                    post.totalChapters = chapters.size();
                                    MangaChapter newest = chapters.get(0);
                                    for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                                    post.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
                                    post.latestChapterDate = newest.date == null ? "" : newest.date;
                                }
                                DETAIL_CACHE.put(cleanSlug, post);
                                MangaCoroutines.main(() -> cb.onSuccess(post, false));
                            }
                            @Override public void onError(String message) {
                                DETAIL_CACHE.put(cleanSlug, post);
                                MangaCoroutines.main(() -> cb.onSuccess(post, false));
                            }
                        });
                    } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Komiku Asia gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String cleanSlug = slugFromUrl(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String detailUrl = base() + "/manga/" + encodePath(cleanSlug);
        getText(rscUrl(detailUrl), base() + "/browse", true, "/browse", new Result<String>() {
            @Override public void onSuccess(String raw, boolean ignored) {
                MangaCoroutines.io(() -> {
                    ArrayList<MangaChapter> out = parseChaptersFromRaw(raw, cleanSlug);
                    if (!out.isEmpty()) {
                        CHAPTER_CACHE.put(cleanSlug, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } else {
                        fetchChaptersFromHtml(detailUrl, cleanSlug, cb);
                    }
                });
            }
            @Override public void onError(String message) { fetchChaptersFromHtml(detailUrl, cleanSlug, cb); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String cleanSlug = slugFromUrl(slug);
        String key = cleanSlug + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) {
            String cachedReferer = PAGE_REFERER_CACHE.get(key);
            if (cachedReferer != null && !cachedReferer.isEmpty()) {
                for (String pageUrl : cached) MangaImageLoader.registerImageReferer(pageUrl, cachedReferer);
            }
            cb.onSuccess(new ArrayList<>(cached), false);
            return;
        }
        chapters(cleanSlug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean ignored) {
                MangaChapter target = null;
                for (MangaChapter c : chapters) if (Math.abs(c.index - index) < 0.001f) { target = c; break; }
                if (target == null) { cb.onError("Chapter Komiku Asia tidak ditemukan"); return; }
                String chapterUrl = toAbsolute(target.slug);
                String referer = base() + "/manga/" + encodePath(cleanSlug);
                getText(rscUrl(chapterUrl), referer, true, "/manga/" + encodePath(cleanSlug), new Result<String>() {
                    @Override public void onSuccess(String raw, boolean ignored2) {
                        MangaCoroutines.io(() -> {
                            ArrayList<String> pages = parsePagesFromRaw(raw, chapterUrl);
                            if (!pages.isEmpty()) {
                                cacheAndReturnPages(key, pages, chapterUrl, cb);
                            } else {
                                fetchPagesFromHtml(chapterUrl, referer, key, cb);
                            }
                        });
                    }
                    @Override public void onError(String message) { fetchPagesFromHtml(chapterUrl, referer, key, cb); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void fetchChaptersFromHtml(String detailUrl, String slug, Result<ArrayList<MangaChapter>> cb) {
        getText(detailUrl, base() + "/browse", false, "/browse", new Result<String>() {
            @Override public void onSuccess(String raw, boolean ignored) {
                MangaCoroutines.io(() -> {
                    ArrayList<MangaChapter> out = parseChaptersFromRaw(raw, slug);
                    if (out.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Chapter Komiku Asia tidak ditemukan pada detail page")); return; }
                    CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                    MangaCoroutines.main(() -> cb.onSuccess(out, false));
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void fetchPagesFromHtml(String chapterUrl, String referer, String key, Result<ArrayList<String>> cb) {
        getText(chapterUrl, referer, false, "/manga/" + slugFromReaderUrl(chapterUrl), new Result<String>() {
            @Override public void onSuccess(String raw, boolean ignored) {
                MangaCoroutines.io(() -> {
                    ArrayList<String> pages = parsePagesFromRaw(raw, chapterUrl);
                    if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Gambar chapter Komiku Asia tidak ditemukan")); return; }
                    cacheAndReturnPages(key, pages, chapterUrl, cb);
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void cacheAndReturnPages(String key, ArrayList<String> pages, String chapterUrl, Result<ArrayList<String>> cb) {
        for (String pageUrl : pages) MangaImageLoader.registerImageReferer(pageUrl, chapterUrl);
        PAGE_CACHE.put(key, new ArrayList<>(pages));
        PAGE_REFERER_CACHE.put(key, chapterUrl);
        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
    }

    private String buildListUrl(int page, String sort, String query, String genre) throws Exception {
        int p = Math.max(1, page);
        String s = sort == null ? "latest" : sort.trim().toLowerCase(Locale.ROOT);
        String apiSort = "update";
        String order = "desc";
        String status = "Semua";
        String type = "Semua";
        if ("popular".equals(s) || "popularity".equals(s) || "views".equals(s)) apiSort = "popular";
        else if ("rating".equals(s) || "rate".equals(s)) apiSort = "rating";
        else if ("newest".equals(s) || "new".equals(s) || "added".equals(s) || "latest_added".equals(s)) apiSort = "newest";
        else if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) apiSort = "az";
        else if ("ongoing".equals(s)) status = "Ongoing";
        else if ("completed".equals(s) || "complete".equals(s)) status = "Completed";
        else if ("hiatus".equals(s)) status = "Hiatus";
        else if ("manga".equals(s) || "manhwa".equals(s) || "manhua".equals(s)) type = apiType(s);

        HttpUrl parsed = HttpUrl.parse(api() + "/comics");
        if (parsed == null) throw new IllegalStateException("Base URL Komiku Asia tidak valid");
        HttpUrl.Builder b = parsed.newBuilder();
        if (query != null && !query.trim().isEmpty()) b.addQueryParameter("search", query.trim());
        String cleanGenre = genre == null ? "" : genre.trim();
        FilterState filters = new FilterState(status, type);
        if (cleanGenre.contains("|")) {
            for (String token : cleanGenre.split("\\|")) applyFilterToken(b, token, filters);
        } else {
            applyFilterToken(b, cleanGenre, filters);
        }
        status = filters.status;
        type = filters.type;
        b.addQueryParameter("status", status)
                .addQueryParameter("type", type)
                .addQueryParameter("author", "Semua")
                .addQueryParameter("sort", apiSort)
                .addQueryParameter("order", order)
                .addQueryParameter("page", String.valueOf(p))
                .addQueryParameter("perPage", String.valueOf(PER_PAGE));
        return b.build().toString();
    }

    private static class FilterState {
        String status;
        String type;
        FilterState(String status, String type) {
            this.status = status == null || status.trim().isEmpty() ? "Semua" : status;
            this.type = type == null || type.trim().isEmpty() ? "Semua" : type;
        }
    }

    private static void applyFilterToken(HttpUrl.Builder b, String token, FilterState filters) {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty()) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("type:")) {
            filters.type = apiType(value.substring(5));
        } else if (lower.startsWith("status:")) {
            filters.status = apiStatus(value.substring(7));
        } else if (lower.startsWith("genre:")) {
            String genre = value.substring(6).trim();
            if (!genre.isEmpty()) b.addQueryParameter("genres", genre);
        } else {
            b.addQueryParameter("genres", value);
        }
    }

    private String browseReferer(String sort, String query, String genre) {
        String q = query == null ? "" : query.trim();
        String g = extractGenreFilter(genre);
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        try {
            HttpUrl.Builder b = HttpUrl.parse(base() + "/browse").newBuilder();
            if (!q.isEmpty()) b.addQueryParameter("q", q);
            if (!g.isEmpty()) b.addQueryParameter("genre", g);
            if ("popular".equals(s) || "popularity".equals(s)) b.addQueryParameter("sort", "popular");
            else if ("rating".equals(s) || "rate".equals(s)) b.addQueryParameter("sort", "rating");
            else if ("newest".equals(s) || "new".equals(s) || "added".equals(s) || "latest_added".equals(s)) b.addQueryParameter("sort", "newest");
            else if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) b.addQueryParameter("sort", "az");
            return b.build().toString();
        } catch (Exception e) {
            return base() + "/browse";
        }
    }

    private static String extractGenreFilter(String value) {
        if (value == null) return "";
        for (String token : value.split("\\|")) {
            String v = token.trim();
            if (v.isEmpty() || v.toLowerCase(Locale.ROOT).startsWith("type:") || v.toLowerCase(Locale.ROOT).startsWith("status:")) continue;
            if (v.toLowerCase(Locale.ROOT).startsWith("genre:")) return v.substring(6).trim();
            return v;
        }
        return "";
    }

    private static String requestedTypeFilter(String sort, String genre) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(s) || "manhwa".equals(s) || "manhua".equals(s)) return apiType(s);
        if (genre != null) for (String token : genre.split("\\|")) {
            String v = token.trim();
            if (v.toLowerCase(Locale.ROOT).startsWith("type:")) return apiType(v.substring(5));
        }
        return "";
    }

    private static String requestedStatusFilter(String sort, String genre) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("ongoing".equals(s)) return "Ongoing";
        if ("completed".equals(s) || "complete".equals(s)) return "Completed";
        if ("hiatus".equals(s)) return "Hiatus";
        if (genre != null) for (String token : genre.split("\\|")) {
            String v = token.trim();
            if (v.toLowerCase(Locale.ROOT).startsWith("status:")) return apiStatus(v.substring(7));
        }
        return "";
    }

    private static boolean postHasGenre(JsonObject o, String requested) {
        JsonArray arr = arrayValue(o, "genres");
        String target = requested.trim();
        for (JsonElement e : arr) {
            if (e != null && e.isJsonPrimitive() && e.getAsString().trim().equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private static String apiType(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("manga")) return "Manga";
        if (v.equals("manhwa")) return "Manhwa";
        if (v.equals("manhua")) return "Manhua";
        return "Semua";
    }

    private static String apiStatus(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("ongoing")) return "Ongoing";
        if (v.equals("completed") || v.equals("complete")) return "Completed";
        if (v.equals("hiatus")) return "Hiatus";
        return "Semua";
    }

    private MangaPost parseApiPost(JsonObject o) {
        String slug = slugFromUrl(str(o, "slug"));
        String title = str(o, "title");
        String cover = str(o, "coverUrl");
        String author = str(o, "author");
        String artist = str(o, "artist");
        String status = str(o, "status");
        String type = str(o, "type");
        String synopsis = str(o, "synopsis");
        String genre = join(arrayValue(o, "genres"));
        float latest = numberValue(o, "latestChapter", 0);
        long latestAt = longValue(o, "latestChapterAt", 0L);
        String latestText = latest > 0 ? "Chapter " + MangaChapter.formatIndex(latest) : "";
        String date = latestAt > 0 ? formatDate(latestAt) : "";
        MangaPost p = new MangaPost(slug, title, cover, author, status, synopsis, genre, type, latestText, date).withSource(SOURCE_ID, SOURCE_LABEL);
        p.totalChapters = intValue(o, "chapterCount", 0);
        String alt = str(o, "alt");
        String rating = str(o, "rating");
        if (!alt.isEmpty() || !artist.isEmpty() || !rating.isEmpty()) {
            StringBuilder info = new StringBuilder();
            if (!alt.isEmpty()) info.append("Alt: ").append(alt);
            if (!artist.isEmpty()) { if (info.length() > 0) info.append("\n"); info.append("Artist: ").append(artist); }
            if (!rating.isEmpty()) { if (info.length() > 0) info.append("\n"); info.append("Rating: ").append(rating); }
            p.info = info.toString();
        }
        return p;
    }

    private ArrayList<MangaChapter> parseChaptersFromRaw(String raw, String slug) {
        JsonArray arr = extractJsonArray(raw, "chapters");
        ArrayList<MangaChapter> out = parseChapters(arr, slug);
        if (!out.isEmpty()) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\{\\s*\\\"n\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*,\\s*\\\"title\\\"\\s*:\\s*\\\"([^\\\"]*)\\\".*?\\\"releasedLabel\\\"\\s*:\\s*\\\"([^\\\"]*)\\\".*?\\\"id\\\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(raw == null ? "" : raw);
        while (m.find()) {
            try {
                float n = Float.parseFloat(m.group(1));
                String title = unescape(m.group(2));
                String date = unescape(m.group(3));
                String chapterId = m.group(4);
                String chapterSlug = "/read/id/" + slug + "/ch" + MangaChapter.formatIndex(n) + "-" + chapterId;
                if (seen.add(chapterSlug)) out.add(new MangaChapter(chapterSlug, n, title, prettyRelativeDate(date)));
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Float.compare(b.index, a.index));
        return out;
    }

    private ArrayList<MangaChapter> parseChapters(JsonArray arr, String slug) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (arr != null) {
            for (JsonElement e : arr) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String title = str(o, "title");
                float n = numberValue(o, "n", parseChapterIndex(title, 0));
                if (n <= 0) n = parseChapterIndex(title, 0);
                if (n <= 0) continue;
                if (title.isEmpty()) title = "Chapter " + MangaChapter.formatIndex(n);
                String date = str(o, "releasedLabel");
                long releasedAt = longValue(o, "releasedAt", 0L);
                if (date.isEmpty() && releasedAt > 0) date = formatDate(releasedAt);
                long chapterId = longValue(o, "id", 0L);
                if (chapterId <= 0) continue;
                String chapterSlug = "/read/id/" + slug + "/ch" + MangaChapter.formatIndex(n) + "-" + chapterId;
                if (seen.add(chapterSlug)) out.add(new MangaChapter(chapterSlug, n, title, prettyRelativeDate(date)));
            }
        }
        out.sort((a, b) -> Float.compare(b.index, a.index));
        return out;
    }

    private ArrayList<String> parsePagesFromRaw(String raw, String baseUri) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray pages = extractJsonArray(raw, "pages");
        if (pages != null) {
            for (JsonElement e : pages) {
                if (e == null || !e.isJsonObject()) continue;
                addPage(out, seen, str(e.getAsJsonObject(), "url"));
            }
        }
        if (!out.isEmpty()) return out;
        Document doc = Jsoup.parse(raw == null ? "" : raw, baseUri == null ? base() : baseUri);
        Elements imgs = doc.select("img.rd-page-image");
        for (Element img : imgs) addPage(out, seen, image(img));
        if (out.isEmpty()) {
            Elements legacy = doc.select("div.reading-content img, div.page-break img, #Baca_Komik img, #readerarea img, .rd-page img");
            for (Element img : legacy) addPage(out, seen, image(img));
        }
        if (out.isEmpty()) {
            Matcher urlField = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"(https?:\\\\?/\\\\?/[^\\\"'<>\\s)]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\\"'<>\\s)]*)?)\\\"", Pattern.CASE_INSENSITIVE).matcher(raw == null ? "" : raw);
            while (urlField.find()) addPage(out, seen, unescapeUrl(urlField.group(1)));
        }
        if (out.isEmpty()) {
            Matcher anyImage = Pattern.compile("https?:\\\\?/\\\\?/[^\\\"'<>\\s)]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\\"'<>\\s)]*)?", Pattern.CASE_INSENSITIVE).matcher(raw == null ? "" : raw);
            while (anyImage.find()) addPage(out, seen, unescapeUrl(anyImage.group()));
        }
        return out;
    }

    private void fetchComicId(String slug, Result<Long> cb) {
        String cleanSlug = slugFromUrl(slug);
        Long cached = ID_CACHE.get(cleanSlug);
        if (cached != null && cached > 0) { cb.onSuccess(cached, false); return; }
        getJson(api() + "/comics/" + encodePath(cleanSlug), base() + "/manga/" + encodePath(cleanSlug), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                long id = longValue(root, "id", 0L);
                if (id <= 0) { cb.onError("ID komik Komiku Asia tidak valid"); return; }
                ID_CACHE.put(cleanSlug, id);
                cb.onSuccess(id, false);
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void getJson(String url, String referer, Result<JsonObject> cb) {
        String solvedBody = CloudflareHelper.cachedSolvedBody(url);
        if (solvedBody != null && !solvedBody.trim().isEmpty()) {
            try {
                postSuccess(cb, JsonParser.parseString(solvedBody).getAsJsonObject(), false);
                return;
            } catch (Exception ignored) { }
        }
        Request req = new Request.Builder().url(url)
                .header("Referer", referer == null || referer.trim().isEmpty() ? base() + "/browse" : referer)
                .header("Accept", "application/json")
                .header("Accept-Language", "id-ID,id;q=0.7")
                .header("User-Agent", userAgent())
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build();
        CloudflareHelper.enqueue(client, req, SOURCE_LABEL, new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { postError(cb, CloudflareHelper.errorMessage(e)); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { postError(cb, "HTTP " + response.code()); return; }
                try { postSuccess(cb, JsonParser.parseString(body).getAsJsonObject(), false); }
                catch (Exception e) { postError(cb, "JSON Komiku Asia gagal dibaca"); }
            }
        });
    }

    private void getText(String url, String referer, boolean rsc, String nextUrl, Result<String> cb) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("Referer", referer == null || referer.trim().isEmpty() ? base() + "/" : referer)
                .header("Accept", rsc ? "*/*" : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.7")
                .header("User-Agent", userAgent())
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", rsc ? "cors" : "navigate")
                .header("Sec-Fetch-Dest", "empty");
        if (rsc) {
            builder.header("RSC", "1");
            if (nextUrl != null && !nextUrl.trim().isEmpty()) builder.header("Next-Url", nextUrl.trim());
        }
        CloudflareHelper.enqueue(client, builder.build(), SOURCE_LABEL, new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { postError(cb, CloudflareHelper.errorMessage(e)); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { postError(cb, "HTTP " + response.code()); return; }
                if (body.trim().isEmpty()) { postError(cb, "Respons Komiku Asia kosong"); return; }
                postSuccess(cb, body, false);
            }
        });
    }

    private static <T> void postSuccess(Result<T> cb, T data, boolean hasNext) {
        MangaCoroutines.main(() -> cb.onSuccess(data, hasNext));
    }

    private static void postError(Result<?> cb, String message) {
        MangaCoroutines.main(() -> cb.onError(message));
    }

    private static JsonArray extractJsonArray(String raw, String name) {
        if (raw == null || name == null || name.isEmpty()) return null;
        String key = "\"" + name + "\"";
        int keyIndex = raw.indexOf(key);
        while (keyIndex >= 0) {
            int colon = raw.indexOf(':', keyIndex + key.length());
            if (colon < 0) return null;
            int start = colon + 1;
            while (start < raw.length() && Character.isWhitespace(raw.charAt(start))) start++;
            if (start >= raw.length() || raw.charAt(start) != '[') {
                keyIndex = raw.indexOf(key, keyIndex + key.length());
                continue;
            }
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            for (int i = start; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (inString) {
                    if (escape) escape = false;
                    else if (c == '\\') escape = true;
                    else if (c == '"') inString = false;
                } else {
                    if (c == '"') inString = true;
                    else if (c == '[') depth++;
                    else if (c == ']') {
                        depth--;
                        if (depth == 0) {
                            try { return JsonParser.parseString(raw.substring(start, i + 1)).getAsJsonArray(); }
                            catch (Exception ignored) { break; }
                        }
                    }
                }
            }
            keyIndex = raw.indexOf(key, keyIndex + key.length());
        }
        return null;
    }

    private static float numberValue(JsonObject o, String k, float d) { try { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? d : e.getAsFloat(); } catch (Exception e) { return d; } }
    private static int intValue(JsonObject o, String k, int d) { try { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? d : e.getAsInt(); } catch (Exception e) { return d; } }
    private static long longValue(JsonObject o, String k, long d) { try { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? d : e.getAsLong(); } catch (Exception e) { return d; } }
    private static JsonArray firstArray(JsonObject o,String... names){for(String n:names){JsonArray arr=arrayValue(o,n);if(arr.size()>0)return arr;}return new JsonArray();}
    private static JsonArray arrayValue(JsonObject o, String k){try{JsonElement e=o.get(k);return e!=null&&e.isJsonArray()?e.getAsJsonArray():new JsonArray();}catch(Exception e){return new JsonArray();}}
    private static String str(JsonObject o,String k){try{JsonElement e=o.get(k);return e==null||e.isJsonNull()?"":e.getAsString().trim();}catch(Exception e){return "";}}
    private static String join(JsonArray a) { StringBuilder s=new StringBuilder(); if(a==null)return ""; for(JsonElement e:a){if(e==null||!e.isJsonPrimitive())continue; String v=e.getAsString().trim(); if(v.isEmpty())continue; if(s.length()>0)s.append(", "); s.append(v);} return s.toString(); }
    private static String text(Element e) { return e == null ? "" : e.text().trim(); }
    private static String userAgent() { return CloudflareHelper.browserUserAgent(); }
    private static String encodePath(String value) { try { return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20"); } catch(Exception e) { return value == null ? "" : value; } }

    private static String rscUrl(String url) {
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null) return url;
            return parsed.newBuilder().addQueryParameter("_rsc", Long.toString(System.currentTimeMillis(), 36)).build().toString();
        } catch (Exception e) { return url; }
    }

    private static String toAbsolute(String value) {
        if (value == null || value.trim().isEmpty()) return base();
        String v = value.trim();
        if (v.startsWith("//")) return "https:" + v;
        if (v.startsWith("http://") || v.startsWith("https://")) return v;
        try {
            HttpUrl root = HttpUrl.parse(base() + "/");
            HttpUrl resolved = root == null ? null : root.resolve(v);
            if (resolved != null) return resolved.toString();
        } catch (Exception ignored) {}
        return base() + (v.startsWith("/") ? v : "/" + v);
    }

    private static String image(Element e) {
        if (e == null) return "";
        for (String a : new String[]{"abs:data-src","abs:data-lazy-src","abs:data-original","abs:src","data-src","data-lazy-src","data-original","src"}) {
            String v=e.attr(a).trim();
            if(!v.isEmpty()) return normalizeImageAttr(v);
        }
        String srcset = e.attr("abs:srcset").trim();
        if (srcset.isEmpty()) srcset = e.attr("srcset").trim();
        if (!srcset.isEmpty()) return normalizeImageAttr(srcset.split(",")[0].trim().split("\\s+")[0]);
        return "";
    }

    private static String normalizeImageAttr(String v) {
        String url = unescapeUrl(v == null ? "" : v.trim());
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }

    private static void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String url) {
        if(url==null)return;
        String v=unescapeUrl(url.trim());
        if(v.startsWith("//")) v = "https:" + v;
        if(!v.startsWith("http://") && !v.startsWith("https://"))return;
        String l=v.toLowerCase(Locale.ROOT);
        if(l.contains("readerarea.svg")||l.contains("/iklan")||l.contains("bacalightnovel")||l.contains("cropped-ic_komiku")||l.contains("/thumbnail/"))return;
        if(!l.contains("cdnkomiku.xyz") && !l.contains("/chapter-"))return;
        if(seen.add(v))out.add(v);
    }

    private static float parseChapterIndex(String s,int fallback){String raw=s==null?"":s;Matcher m=Pattern.compile("(?i)chapter\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(raw);if(m.find())try{return Float.parseFloat(m.group(1).replace(',','.'));}catch(Exception ignored){}m=Pattern.compile("([0-9]+(?:[.,][0-9]+)?)").matcher(raw);if(m.find())try{return Float.parseFloat(m.group(1).replace(',','.'));}catch(Exception ignored){}return fallback;}
    private static String prettyRelativeDate(String value){if(value==null||value.trim().isEmpty())return "";return value.trim();}
    private static String formatDate(long millis){try{return new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new java.util.Date(millis));}catch(Exception e){return "";}}

    private static String slugFromUrl(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty()) return "";
        try {
            HttpUrl parsed = HttpUrl.parse(v.startsWith("http") ? v : base() + (v.startsWith("/") ? v : "/" + v));
            if (parsed != null) {
                int manga = parsed.pathSegments().indexOf("manga");
                if (manga >= 0 && manga + 1 < parsed.pathSegments().size()) return parsed.pathSegments().get(manga + 1);
                int id = parsed.pathSegments().indexOf("id");
                if (id >= 0 && id + 1 < parsed.pathSegments().size()) return parsed.pathSegments().get(id + 1);
            }
        } catch (Exception ignored) {}
        while (v.startsWith("/")) v = v.substring(1);
        if (v.startsWith("manga/")) v = v.substring(6);
        int slash = v.indexOf('/');
        return slash >= 0 ? v.substring(0, slash) : v;
    }

    private static String slugFromReaderUrl(String value) {
        String slug = slugFromUrl(value);
        return slug == null || slug.isEmpty() ? "" : slug;
    }

    private static String unescapeUrl(String value) {
        return unescape(value).replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
    }

    private static String unescape(String value) {
        if (value == null) return "";
        return value.replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\n", " ")
                .replace("\\u0026", "&")
                .replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .trim();
    }
}
