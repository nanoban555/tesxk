package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SoulScans extends KomikcastClient {
    public static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_SOULSCANS;
    protected static String base() { return MangaSettingsManager.getSourceDomain(SOURCE_ID); }
    private static final String LABEL = "Soul Scans";
    private static final String API_ORIGIN = "https://img.soulscans.org";
    private static final String API_BASE = API_ORIGIN + "/api";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, CACHE_TTL);
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    @Override protected String sourceLabel() { return LABEL; }

    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            String url = buildListUrl(safePage, sort, query, genre);
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 24); return; }
            getDocument(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(document);
                            boolean next = hasNextPage(document, safePage, out.size());
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Soul Scans gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getJsonArray(API_BASE + "/genres", base() + "/allcomic", new Result<JSONArray>() {
            @Override public void onSuccess(JSONArray array, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenresFromApi(array);
                        if (out.isEmpty()) out = fallbackGenres();
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) {
                        MangaCoroutines.main(() -> loadGenresFromWeb(cb));
                    }
                });
            }
            @Override public void onError(String message) { loadGenresFromWeb(cb); }
        });
    }

    private void loadGenresFromWeb(Result<ArrayList<GenreItem>> cb) {
        getDocument(base() + "/allcomic", new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(document);
                        if (out.isEmpty()) out = fallbackGenres();
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) {
                        ArrayList<GenreItem> fallback = fallbackGenres();
                        GENRE_CACHE.put("genres", new ArrayList<>(fallback));
                        MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
                    }
                });
            }
            @Override public void onError(String message) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                GENRE_CACHE.put("genres", new ArrayList<>(fallback));
                cb.onSuccess(fallback, false);
            }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost post : list) if (needsEnrichment(post, loadChapter, loadType)) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost post : list) {
            if (!needsEnrichment(post, loadChapter, loadType)) continue;
            detail(post.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        if (loadChapter && (post.latestChapter == null || post.latestChapter.trim().isEmpty())) post.latestChapter = detail.latestChapter;
                        if (loadChapter && (post.latestChapterDate == null || post.latestChapterDate.trim().isEmpty())) post.latestChapterDate = detail.latestChapterDate;
                        if (loadType && (post.typeLabel == null || post.typeLabel.trim().isEmpty())) post.typeLabel = detail.typeLabel;
                        if (post.genre == null || post.genre.trim().isEmpty()) post.genre = detail.genre;
                        if (post.status == null || post.status.trim().isEmpty()) post.status = detail.status;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Soul Scans kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(apiSeriesUrl(clean), seriesUrl(clean), new Result<JSONObject>() {
            @Override public void onSuccess(JSONObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetailFromApi(clean, root);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> loadDetailFromWeb(clean, cb)); return; }
                        ArrayList<MangaChapter> chapters = parseChaptersFromApi(clean, root);
                        fillLatest(post, chapters);
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> loadDetailFromWeb(clean, cb)); }
                });
            }
            @Override public void onError(String message) { loadDetailFromWeb(clean, cb); }
        });
    }

    private void loadDetailFromWeb(String clean, Result<MangaPost> cb) {
        getDocument(seriesUrl(clean), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(clean, document);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail Soul Scans kosong")); return; }
                        ArrayList<MangaChapter> chapters = parseChapters(clean, document);
                        fillLatest(post, chapters);
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Soul Scans gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void fillLatest(MangaPost post, ArrayList<MangaChapter> chapters) {
        if (post == null || chapters == null) return;
        post.totalChapters = chapters.size();
        if (chapters.isEmpty()) return;
        MangaChapter newest = chapters.get(0);
        for (MangaChapter chapter : chapters) if (chapter != null && chapter.index > newest.index) newest = chapter;
        post.latestChapter = newest.title == null ? "" : newest.title;
        post.latestChapterDate = newest.date == null ? "" : newest.date;
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Soul Scans kosong"); return; }
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        detail(clean, new Result<MangaPost>() {
            @Override public void onSuccess(MangaPost data, boolean hasNext) {
                ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(clean);
                cb.onSuccess(chapters == null ? new ArrayList<>() : new ArrayList<>(chapters), false);
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String chapterUrl = cleanChapterUrl(slug);
        String clean = cleanSeriesSlug(slug);
        String key = (chapterUrl.isEmpty() ? clean : chapterUrl) + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        if (!chapterUrl.isEmpty()) { loadPages(chapterUrl, key, cb); return; }
        MangaChapter chapter = findCachedChapter(clean, index);
        if (chapter != null && chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()) {
            loadPages(chapter.chapterId.trim(), key, cb);
            return;
        }
        chapters(clean, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) {
                MangaChapter loaded = findChapter(data, index);
                if (loaded == null) loaded = findCachedChapter(clean, index);
                if (loaded != null && loaded.chapterId != null && !loaded.chapterId.trim().isEmpty()) loadPages(loaded.chapterId.trim(), key, cb);
                else loadPagesFromCandidates(chapterUrlCandidates(clean, index), 0, key, cb);
            }
            @Override public void onError(String message) { loadPagesFromCandidates(chapterUrlCandidates(clean, index), 0, key, cb); }
        });
    }

    private void loadPages(String chapterUrl, String key, Result<ArrayList<String>> cb) {
        String seriesSlug = cleanSeriesSlug(chapterUrl);
        String chapterSlug = cleanChapterSlug(chapterUrl);
        if (!seriesSlug.isEmpty() && !chapterSlug.isEmpty()) {
            String webUrl = chapterWebUrl(seriesSlug, chapterSlug);
            getJson(apiChapterUrl(seriesSlug, chapterSlug), webUrl, new Result<JSONObject>() {
                @Override public void onSuccess(JSONObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<String> pages = parsePagesFromApi(root, webUrl);
                            if (pages.isEmpty()) { MangaCoroutines.main(() -> loadPagesFromWeb(chapterUrl, key, cb)); return; }
                            PAGE_CACHE.put(key, new ArrayList<>(pages));
                            MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                        } catch(Exception e) { MangaCoroutines.main(() -> loadPagesFromWeb(chapterUrl, key, cb)); }
                    });
                }
                @Override public void onError(String message) { loadPagesFromWeb(chapterUrl, key, cb); }
            });
            return;
        }
        loadPagesFromWeb(chapterUrl, key, cb);
    }

    private void loadPagesFromWeb(String chapterUrl, String key, Result<ArrayList<String>> cb) {
        getDocument(chapterUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> pages = parsePages(document, chapterUrl);
                        if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman Soul Scans kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Soul Scans gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadPagesFromCandidates(ArrayList<String> urls, int pos, String key, Result<ArrayList<String>> cb) {
        if (urls == null || pos >= urls.size()) { cb.onError("Chapter Soul Scans tidak ditemukan"); return; }
        loadPages(urls.get(pos), key, new Result<ArrayList<String>>() {
            @Override public void onSuccess(ArrayList<String> data, boolean hasNext) { cb.onSuccess(data, hasNext); }
            @Override public void onError(String message) { loadPagesFromCandidates(urls, pos + 1, key, cb); }
        });
    }

    private String buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        FilterParts filter = parseFilters(genre);
        if (filter.type.isEmpty() && isTypeSort(s)) filter.type = s;
        if (filter.status.isEmpty() && ("completed".equals(s) || "complete".equals(s))) filter.status = "completed";
        if (filter.status.isEmpty() && ("ongoing".equals(s) || "on-going".equals(s))) filter.status = "ongoing";
        if ("project".equals(s) || "projects".equals(s)) {
            HttpUrl.Builder project = HttpUrl.parse(base() + "/projects").newBuilder();
            project.addQueryParameter("sort", "latest");
            project.addQueryParameter("order", "desc");
            project.addQueryParameter("page", String.valueOf(page));
            return project.build().toString();
        }
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/allcomic").newBuilder();
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) builder.addQueryParameter("search", q);
        if (!filter.genre.isEmpty()) builder.addQueryParameter("genre", filter.genre);
        if (!filter.status.isEmpty()) builder.addQueryParameter("status", filter.status);
        if (!filter.type.isEmpty()) builder.addQueryParameter("type", filter.type);
        builder.addQueryParameter("sort", sortParam(s));
        builder.addQueryParameter("order", orderParam(s));
        builder.addQueryParameter("page", String.valueOf(page));
        return builder.build().toString();
    }

    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements items = document.select("li, article, .comic-item, .grid > div, .flex > div, .card, .item, .series-item");
        for (Element item : items) {
            Element link = detailLink(item);
            if (link == null) continue;
            MangaPost post = postFromListItem(item, link);
            if (post.slug.isEmpty() || post.title.isEmpty() || !seen.add(post.slug)) continue;
            out.add(post);
        }
        if (out.isEmpty()) {
            for (Element link : document.select("a[href*='/comic/']")) {
                String href = link.absUrl("href");
                if (!isDetailUrl(href)) continue;
                String slug = cleanSeriesSlug(href);
                if (slug.isEmpty() || !seen.add(slug)) continue;
                Element root = nearestCard(link);
                MangaPost post = postFromListItem(root == null ? link : root, link);
                if (!post.title.isEmpty()) out.add(post);
            }
        }
        return out;
    }

    private MangaPost postFromListItem(Element item, Element link) {
        String href = link.absUrl("href");
        String slug = cleanSeriesSlug(href);
        Element img = firstImage(item, link);
        String title = firstNonEmpty(text(link, "strong"), attr(link, "title"), attr(img, "alt"), cleanListTitle(link.text()), titleFromSlug(slug));
        String cover = imageUrl(img);
        String itemText = item == null ? "" : item.text();
        String latest = cleanChapterText(firstNonEmpty(firstChapterText(item), itemText));
        if (latest.equals(title)) latest = "";
        String status = detectStatus(itemText);
        String type = detectType(itemText);
        return new MangaPost(slug, title, cover, "", status, "", "", type, latest, "").withSource(SOURCE_ID, LABEL);
    }

    private MangaPost parseDetailFromApi(String clean, JSONObject sourceRoot) {
        JSONObject root = seriesObject(sourceRoot);
        String apiSlug = firstNonEmpty(apiString(root, "slug"), clean);
        String title = firstNonEmpty(apiString(root, "title"), titleFromSlug(apiSlug));
        String cover = absolutizeApiAsset(firstNonEmpty(apiString(root, "poster_image_url"), apiString(root, "banner_image_url")));
        String synopsis = cleanSynopsis(firstNonEmpty(apiString(root, "synopsis"), apiString(root, "description")));
        String genre = jsonGenres(root.optJSONArray("genres"));
        String status = normalizeStatusLabel(firstNonEmpty(apiString(root, "comic_status"), apiString(root, "series_status"), apiString(root, "status")));
        String type = normalizeTypeLabel(firstNonEmpty(apiString(root, "comic_subtype"), apiString(root, "novel_subtype"), apiString(root, "anime_format"), apiString(root, "type")));
        MangaPost post = new MangaPost(apiSlug, title, cover, "", status, synopsis, genre, type).withSource(SOURCE_ID, LABEL);
        post.info = apiDetailInfo(root, genre);
        post.totalChapters = root.optJSONArray("units") == null ? 0 : root.optJSONArray("units").length();
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, seriesUrl(apiSlug));
        return post;
    }

    private MangaPost parseDetail(String clean, Document document) {
        for (JSONObject body : svelteBodies(document)) {
            MangaPost apiPost = parseDetailFromApi(clean, body);
            if (apiPost != null && apiPost.title != null && !apiPost.title.trim().isEmpty()) return apiPost;
        }
        String title = firstNonEmpty(text(document, "main h1"), text(document, "article h1"), text(document, "h1"), meta(document, "meta[property=og:title]"), document.title());
        title = title.replace("- Comic | Soul Scans ID", "").replace("| Soul Scans ID", "").trim();
        String cover = firstNonEmpty(meta(document, "meta[property=og:image]"), meta(document, "meta[name=twitter:image]"), imageUrl(document.selectFirst("main img[src*='series/covers'], main img[src*='thumbnail'], article img[src*='series/covers'], article img[src*='thumbnail'], img[src*='series/covers'], img[src*='thumbnail']")));
        String synopsis = cleanSynopsis(firstNonEmpty(text(document, ".synopsis"), text(document, ".description"), text(document, ".summary"), text(document, ".entry-content")));
        String genre = joinTexts(document.select("a[href*='genre'], a[href*='type'], .genres a, .genre a, .badge a, .tag a"));
        String body = document.text();
        String status = infoValue(document, "Status");
        String type = firstNonEmpty(infoValue(document, "Type"), detectType(genre + " " + body));
        MangaPost post = new MangaPost(clean, title, cover, "", status, synopsis, genre, type).withSource(SOURCE_ID, LABEL);
        post.info = detailInfo(document);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String seriesSlug, Document document) {
        ArrayList<MangaChapter> fromData = parseChaptersFromSvelte(seriesSlug, document);
        if (!fromData.isEmpty()) return fromData;
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href*='/comic/'][href*='/chapter/'], a[href*='/chapter/']")) {
            String href = link.absUrl("href");
            if (href.isEmpty() || !href.toLowerCase(Locale.ROOT).contains("/chapter/") || isChapterShortcut(link)) continue;
            String linkSeries = cleanSeriesSlug(href);
            if (!linkSeries.isEmpty() && !seriesSlug.isEmpty() && !seriesSlug.equals(linkSeries)) continue;
            String title = firstNonEmpty(cleanChapterText(link.text()), cleanChapterText(text(nearestCard(link))), cleanChapterText(href), "Chapter");
            float index = parseChapterIndex(firstNonEmpty(title, href));
            if (index < 0) index = out.size() + 1;
            String urlKey = href.replaceAll("/+$", "");
            if (!seen.add(urlKey)) continue;
            String date = extractDate(text(nearestCard(link)));
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, date);
            chapter.chapterId = href;
            out.add(chapter);
        }
        sortChapters(out);
        return out;
    }

    private ArrayList<MangaChapter> parseChaptersFromApi(String seriesSlug, JSONObject root) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JSONArray units = root == null ? null : root.optJSONArray("units");
        if (units == null && root != null) units = seriesObject(root).optJSONArray("units");
        if (units == null) return out;
        for (int i = 0; i < units.length(); i++) {
            JSONObject item = units.optJSONObject(i);
            if (item == null) continue;
            String chapterSlug = cleanChapterSlug(firstNonEmpty(item.optString("slug", ""), item.optString("chapter_slug", "")));
            if (chapterSlug.isEmpty()) continue;
            if (isLocked(item)) continue;
            float index = parseApiNumber(firstNonEmpty(item.optString("number", ""), item.optString("sort_number", ""), item.optString("title", ""), chapterSlug));
            if (index < 0) index = i + 1;
            String title = "Chapter " + MangaChapter.formatIndex(index);
            String date = MangaDateFormatter.format(firstNonEmpty(apiString(item, "created_at"), apiString(item, "updated_at")));
            String webUrl = chapterWebUrl(seriesSlug, chapterSlug);
            if (!seen.add(webUrl)) continue;
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, date);
            chapter.chapterId = webUrl;
            out.add(chapter);
        }
        sortChapters(out);
        return out;
    }

    private ArrayList<MangaChapter> parseChaptersFromSvelte(String seriesSlug, Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        for (JSONObject body : svelteBodies(document)) {
            out = parseChaptersFromApi(seriesSlug, body);
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document, String chapterUrl) {
        ArrayList<String> fromData = parsePagesFromSvelte(document, chapterUrl);
        if (!fromData.isEmpty()) return fromData;
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("main img, article img, .reading-content img, .chapter-content img, #chapter-content img, .readerarea img, #readerarea img, .page-break img, img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-pagespeed-lazy-src], picture source[srcset], source[srcset]");
        for (Element img : images) addPage(out, seen, imageUrl(img), chapterUrl);
        String html = document.outerHtml();
        Matcher quoted = Pattern.compile("https?:\\/\\/[^\"'<>\\s)]+(?:soulscans\\.org|dbm\\.my\\.id|wp\\.com|googleusercontent\\.com|ggpht\\.com|blogspot\\.com|blogger\\.googleusercontent\\.com)[^\"'<>\\s)]+", Pattern.CASE_INSENSITIVE).matcher(html);
        while (quoted.find()) addPage(out, seen, quoted.group().replace("\\/", "/"), chapterUrl);
        Matcher normal = Pattern.compile("https?://[^\"'<>\\s)]*(?:soulscans\\.org|dbm\\.my\\.id|wp\\.com|googleusercontent\\.com|ggpht\\.com|blogspot\\.com|blogger\\.googleusercontent\\.com)[^\"'<>\\s)]+", Pattern.CASE_INSENSITIVE).matcher(html);
        while (normal.find()) addPage(out, seen, normal.group().replace("\\/", "/"), chapterUrl);
        return out;
    }

    private ArrayList<String> parsePagesFromApi(JSONObject root, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JSONObject chapter = root == null ? null : root.optJSONObject("chapter");
        JSONArray pages = chapter == null ? null : chapter.optJSONArray("pages");
        if (pages == null && root != null) pages = root.optJSONArray("pages");
        if (pages == null) return out;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject item = pages.optJSONObject(i);
            if (item == null) continue;
            addPage(out, seen, firstNonEmpty(item.optString("image_url", ""), item.optString("imageUrl", ""), item.optString("url", ""), item.optString("src", ""), item.optString("image", ""), item.optString("page_url", ""), item.optString("file_url", "")), chapterUrl);
        }
        return out;
    }

    private ArrayList<String> parsePagesFromSvelte(Document document, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        for (JSONObject body : svelteBodies(document)) {
            out = parsePagesFromApi(body, chapterUrl);
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String raw, String chapterUrl) {
        String url = absolutize(raw);
        if (!url.startsWith("http")) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("loading") || lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("favicon") || lower.contains("placeholder") || lower.contains("profile") || lower.contains("/ads") || lower.contains("/iklan")) return;
        if (!lower.matches(".*\\.(jpg|jpeg|png|webp|avif)(?:\\?.*)?$") && !lower.contains("/manga-images/")) return;
        if (!lower.contains("/manga-images/") && !lower.contains("/chapter")) return;
        if (seen.add(url)) {
            MangaImageLoader.registerImageReferer(url, chapterUrl == null || chapterUrl.trim().isEmpty() ? base() + "/" : chapterUrl);
            out.add(url);
        }
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href*='genre='], a[href*='/genre/'], a[href*='genres']")) {
            String title = link.text().trim();
            String value = genreValue(link.absUrl("href"));
            if (title.isEmpty() || value.isEmpty() || isNoiseGenre(title) || !seen.add(value)) continue;
            out.add(new GenreItem(title, value));
        }
        return out;
    }

    private boolean hasNextPage(Document document, int page, int size) {
        if (document.selectFirst("a[rel=next], .pagination a.next, a.next, a[href*='page=" + (page + 1) + "']") != null) return true;
        for (Element link : document.select("a[href*='page=']")) {
            String href = link.absUrl("href");
            Matcher m = Pattern.compile("[?&]page=([0-9]+)").matcher(href);
            while (m.find()) {
                try { if (Integer.parseInt(m.group(1)) > page) return true; } catch(Exception ignored) { }
            }
        }
        return size >= 24;
    }

    private void getDocument(String url, Result<Document> cb) { getDocument(new Request.Builder().url(url).headers(headers()).build(), cb); }

    private void getJson(String url, Result<JSONObject> cb) { getJson(url, base() + "/", cb); }

    private void getJson(String url, String referer, Result<JSONObject> cb) { getJson(new Request.Builder().url(url).headers(apiHeaders(referer)).build(), cb); }

    private void getJsonArray(String url, String referer, Result<JSONArray> cb) { getJsonArray(new Request.Builder().url(url).headers(apiHeaders(referer)).build(), cb); }

    private void getJson(Request req, Result<JSONObject> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    JSONObject root = new JSONObject(body);
                    main.post(() -> cb.onSuccess(root, false));
                } catch(Exception e) { main.post(() -> cb.onError("JSON Soul Scans gagal dibaca")); }
            }
        });
    }

    private void getJsonArray(Request req, Result<JSONArray> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    JSONArray root = new JSONArray(body);
                    main.post(() -> cb.onSuccess(root, false));
                } catch(Exception e) { main.post(() -> cb.onError("JSON Soul Scans gagal dibaca")); }
            }
        });
    }

    private void getDocument(Request req, Result<Document> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    Document doc = Jsoup.parse(body, req.url().toString());
                    main.post(() -> cb.onSuccess(doc, false));
                } catch(Exception e) { main.post(() -> cb.onError("Data Soul Scans gagal dibaca")); }
            }
        });
    }

    private Headers apiHeaders(String referer) {
        String ref = referer == null || referer.trim().isEmpty() ? base() + "/" : referer.trim();
        return new Headers.Builder()
                .set("Referer", ref)
                .set("Origin", base())
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                .set("Accept", "application/json,text/plain,*/*")
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "no-cache")
                .set("Pragma", "no-cache")
                .set("Sec-Fetch-Site", "same-site")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Dest", "empty")
                .set("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                .set("sec-ch-ua-mobile", "?1")
                .set("sec-ch-ua-platform", "\"Android\"")
                .set("X-Requested-With", "XMLHttpRequest")
                .build();
    }

    private Headers headers() {
        String ref = base() + "/";
        return new Headers.Builder()
                .set("Referer", ref)
                .set("Origin", base())
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "max-age=0")
                .build();
    }

    private String apiSeriesUrl(String slug) { return API_BASE + "/series/comic/" + urlSegment(slug); }

    private String apiChapterUrl(String seriesSlug, String chapterSlug) { return API_BASE + "/series/comic/" + urlSegment(seriesSlug) + "/chapter/" + urlSegment(chapterSlug); }

    private String chapterWebUrl(String seriesSlug, String chapterSlug) { return base() + "/comic/" + urlSegment(seriesSlug) + "/chapter/" + urlSegment(chapterSlug); }

    private String absolutizeApiAsset(String raw) {
        String url = raw == null ? "" : raw.trim().replace("\\/", "/").replace("&amp;", "&");
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/api/")) return API_ORIGIN + url;
        if (url.startsWith("/uploads/")) return "http://ss.dbm.my.id" + url;
        if (url.startsWith("/")) return base() + url;
        return url;
    }

    private String apiDetailInfo(JSONObject root, String genre) {
        ArrayList<String> values = new ArrayList<>();
        addInfo(values, "Alternative", apiString(root, "alternative_titles"));
        addInfo(values, "Genre", genre);
        addInfo(values, "Artist", apiString(root, "artist_name"));
        addInfo(values, "Publisher", apiString(root, "publisher_name"));
        addInfo(values, "Released", firstNonEmpty(apiString(root, "release_year"), MangaDateFormatter.format(apiString(root, "first_release_date"))));
        addInfo(values, "Updated", MangaDateFormatter.format(apiString(root, "updated_at")));
        return TextUtils.join("\n", values);
    }

    private JSONObject seriesObject(JSONObject root) {
        if (root == null) return new JSONObject();
        JSONObject data = root.optJSONObject("data");
        if (data != null) root = data;
        JSONObject series = root.optJSONObject("series");
        return series == null ? root : series;
    }

    private String apiString(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return "";
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return "";
        String text = String.valueOf(value).replace("\\/", "/").replace("&amp;", "&").replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (text.equalsIgnoreCase("null") || text.equalsIgnoreCase("undefined")) return "";
        return text;
    }

    private String cleanSynopsis(String raw) {
        String value = raw == null ? "" : Jsoup.parseBodyFragment(raw).wholeText().replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (value.isEmpty() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("undefined")) return "Belum ada sinopsis.";
        return value;
    }

    private String normalizeStatusLabel(String raw) {
        String value = normalizeApiLabel(raw);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("ongoing") || lower.equals("on going")) return "Ongoing";
        if (lower.equals("completed") || lower.equals("complete")) return "Completed";
        if (lower.equals("hiatus")) return "Hiatus";
        if (lower.equals("dropped")) return "Dropped";
        return titleCase(value);
    }

    private String normalizeTypeLabel(String raw) {
        String value = normalizeApiLabel(raw);
        if (value.equalsIgnoreCase("comic")) return "";
        return value.toUpperCase(Locale.ROOT);
    }

    private String titleCase(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : value.split(" ")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.length() > 1 ? part.substring(1) : "");
        }
        return out.toString();
    }

    private ArrayList<GenreItem> parseGenresFromApi(JSONArray array) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String slug = firstNonEmpty(apiString(item, "slug"), apiString(item, "value"), apiString(item, "id"));
                String title = firstNonEmpty(apiString(item, "name"), apiString(item, "title"), titleFromSlug(slug));
                if (slug.isEmpty() || title.isEmpty() || isNoiseGenre(title) || !seen.add(slug.toLowerCase(Locale.ROOT))) continue;
                out.add(new GenreItem(title, slug));
            }
        }
        addGenreItem(out, seen, "Manga", "type:manga");
        addGenreItem(out, seen, "Manhwa", "type:manhwa");
        addGenreItem(out, seen, "Manhua", "type:manhua");
        addGenreItem(out, seen, "Ongoing", "status:ongoing");
        addGenreItem(out, seen, "Completed", "status:completed");
        return out;
    }

    private void addGenreItem(ArrayList<GenreItem> out, LinkedHashSet<String> seen, String title, String value) {
        if (out == null || seen == null || title == null || value == null || value.trim().isEmpty()) return;
        if (seen.add(value.toLowerCase(Locale.ROOT))) out.add(new GenreItem(title, value));
    }

    private String jsonGenres(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = "";
            Object item = array.opt(i);
            if (item instanceof JSONObject) {
                JSONObject object = (JSONObject) item;
                value = firstNonEmpty(apiString(object, "name"), apiString(object, "title"), apiString(object, "slug"));
            } else if (item != null) value = String.valueOf(item);
            value = value == null ? "" : value.trim();
            if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) values.add(value);
        }
        return TextUtils.join(", ", values);
    }

    private String formatRating(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        try { return String.format(Locale.ROOT, "%.1f", Double.parseDouble(value)); } catch(Exception ignored) { }
        return value;
    }

    private String formatCount(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        try {
            long number = Long.parseLong(value.replaceAll("[^0-9]", ""));
            return String.format(Locale.ROOT, "%,d", number).replace(',', '.');
        } catch(Exception ignored) {
            return value;
        }
    }

    private String primaryUploader(JSONArray array) {
        if (array == null) return "";
        String fallback = "";
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String username = apiString(item, "username");
            if (username.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = username;
            if (optBoolean(item, "is_primary")) return username;
        }
        return fallback;
    }

    private String normalizeApiLabel(String raw) {
        String value = raw == null ? "" : raw.trim().replace("_", " ");
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean isLocked(JSONObject item) {
        boolean locked = optBoolean(item, "is_locked");
        boolean premium = optBoolean(item, "is_premium");
        boolean purchased = optBoolean(item, "is_purchased");
        return locked && premium && !purchased;
    }

    private boolean optBoolean(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private float parseApiNumber(String raw) {
        String value = raw == null ? "" : raw.trim().replace(",", ".");
        if (!value.isEmpty()) {
            try { return Float.parseFloat(value); } catch(Exception ignored) { }
        }
        return parseChapterIndex(raw);
    }

    private void sortChapters(ArrayList<MangaChapter> chapters) {
        if (chapters == null) return;
        java.util.Collections.sort(chapters, (a, b) -> Float.compare(b == null ? -1f : b.index, a == null ? -1f : a.index));
    }

    private boolean isChapterShortcut(Element link) {
        String text = link == null ? "" : link.text().trim().toLowerCase(Locale.ROOT);
        if (text.equals("first chapter") || text.equals("read first chapter") || text.equals("latest chapter")) return true;
        if (text.contains("first chapter") && !text.matches(".*chapter\\s*[0-9]+.*")) return true;
        String href = link == null ? "" : link.absUrl("href");
        return cleanChapterSlug(href).isEmpty();
    }

    private ArrayList<JSONObject> svelteBodies(Document document) {
        ArrayList<JSONObject> out = new ArrayList<>();
        if (document == null) return out;
        String text = document.text() == null ? "" : document.text().trim();
        if (text.startsWith("{") && text.endsWith("}")) addJsonBody(out, text);
        for (Element script : document.select("script[type=application/json], script[data-sveltekit-fetched]")) {
            String raw = script.html();
            if (raw == null || raw.trim().isEmpty()) raw = script.data();
            addJsonBody(out, raw);
        }
        return out;
    }

    private void addJsonBody(ArrayList<JSONObject> out, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith("{")) return;
        try {
            JSONObject root = new JSONObject(value);
            String body = root.optString("body", "");
            if (body != null && body.trim().startsWith("{")) {
                out.add(new JSONObject(body));
            } else out.add(root);
        } catch(Exception ignored) { }
    }

    private boolean needsEnrichment(MangaPost post, boolean loadChapter, boolean loadType) {
        if (post == null || post.slug == null || post.slug.trim().isEmpty()) return false;
        boolean missingChapter = loadChapter && (post.latestChapter == null || post.latestChapter.trim().isEmpty() || post.latestChapter.equalsIgnoreCase("Belum ada chapter."));
        boolean missingType = loadType && (post.typeLabel == null || post.typeLabel.trim().isEmpty());
        return missingChapter || missingType;
    }

    private MangaChapter findCachedChapter(String clean, float index) {
        ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(clean);
        return findChapter(chapters, index);
    }

    private MangaChapter findChapter(ArrayList<MangaChapter> chapters, float index) {
        if (chapters == null) return null;
        MangaChapter nearest = null;
        for (MangaChapter chapter : chapters) {
            if (chapter == null) continue;
            if (Math.abs(chapter.index - index) < 0.001f) return chapter;
            if (nearest == null && MangaChapter.formatIndex(chapter.index).equals(MangaChapter.formatIndex(index))) nearest = chapter;
        }
        return nearest;
    }

    private ArrayList<String> chapterUrlCandidates(String slug, float index) {
        ArrayList<String> out = new ArrayList<>();
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) return out;
        String idx = MangaChapter.formatIndex(index);
        boolean integer = Math.abs(index - Math.round(index)) < 0.001f;
        String padded = integer && index > 0 && index < 10 ? String.format(Locale.ROOT, "%02d", Math.round(index)) : idx;
        addCandidate(out, clean, "chapter-" + idx);
        if (!padded.equals(idx)) addCandidate(out, clean, "chapter-" + padded);
        addCandidate(out, clean, idx);
        if (!padded.equals(idx)) addCandidate(out, clean, padded);
        return out;
    }

    private void addCandidate(ArrayList<String> out, String slug, String chapter) {
        String url = base() + "/comic/" + urlSegment(slug) + "/chapter/" + urlSegment(chapter);
        if (!out.contains(url)) out.add(url);
    }

    private FilterParts parseFilters(String raw) {
        FilterParts out = new FilterParts();
        if (raw == null) return out;
        for (String part : raw.split("[|,]")) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) { out.type = normalizeType(value.substring(5)); continue; }
            if (lower.startsWith("status:")) { out.status = normalizeStatus(value.substring(7)); continue; }
            if (lower.startsWith("genre:")) value = value.substring(6).trim();
            if (!value.isEmpty()) out.genre = value;
        }
        return out;
    }

    private boolean isTypeSort(String sort) { return "manga".equals(sort) || "manhwa".equals(sort) || "manhua".equals(sort); }

    private String sortParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s) || "views".equals(s)) return "popular";
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "title";
        if ("za".equals(s) || "z-a".equals(s) || "titlereverse".equals(s)) return "title";
        return "latest";
    }

    private String orderParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "asc";
        return "desc";
    }

    private String normalizeType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(value)) return "manga";
        if ("manhwa".equals(value)) return "manhwa";
        if ("manhua".equals(value)) return "manhua";
        return value;
    }

    private String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) || "complete".equals(value)) return "completed";
        if ("ongoing".equals(value) || "on-going".equals(value)) return "ongoing";
        return value;
    }

    private String detectStatus(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean done = java.util.regex.Pattern.compile("\\b(completed|complete|tamat)\\b").matcher(value).find();
        boolean going = java.util.regex.Pattern.compile("\\b(ongoing|on going|berjalan)\\b").matcher(value).find();
        if (done == going) return ""; // tidak ada / ambigu: jangan menebak
        return done ? "Completed" : "Ongoing";
    }

    private String detectType(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (value.contains("manhwa")) return "MANHWA";
        if (value.contains("manhua")) return "MANHUA";
        if (value.contains("manga")) return "MANGA";
        return "";
    }

    private String infoValue(Document document, String label) {
        String wanted = label == null ? "" : label.trim().replace(":", "").toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return "";
        for (Element row : document.select("tr, .info, .meta, .property, .properties > *, .detail-info > *, .comic-info > *")) {
            String full = row.text().trim();
            if (full.isEmpty()) continue;
            String lower = full.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(wanted)) continue;
            return full.replaceFirst("(?i)^" + Pattern.quote(label), "").replace(":", "").trim();
        }
        return "";
    }

    private String detailInfo(Document document) {
        ArrayList<String> values = new ArrayList<>();
        addInfo(values, "Artist", infoValue(document, "Artist"));
        addInfo(values, "Released", infoValue(document, "Released"));
        addInfo(values, "Updated", infoValue(document, "Updated"));
        return TextUtils.join("\n", values);
    }

    private void addInfo(ArrayList<String> out, String label, String value) {
        if (out == null || value == null || value.trim().isEmpty()) return;
        out.add(label + ": " + value.trim());
    }

    private Element detailLink(Element root) {
        if (root == null) return null;
        for (Element link : root.select("a[href*='/comic/']")) if (isDetailUrl(link.absUrl("href"))) return link;
        return null;
    }

    private boolean isDetailUrl(String href) {
        String lower = href == null ? "" : href.toLowerCase(Locale.ROOT);
        if (!lower.contains("/comic/")) return false;
        if (lower.contains("/chapter/")) return false;
        if (lower.contains("/login") || lower.contains("/profile") || lower.contains("/bookmark") || lower.contains("/history")) return false;
        return !cleanSeriesSlug(href).isEmpty();
    }

    private Element nearestCard(Element element) {
        Element cur = element;
        for (int i = 0; i < 5 && cur != null; i++) {
            String tag = cur.tagName().toLowerCase(Locale.ROOT);
            if ("li".equals(tag) || "article".equals(tag)) return cur;
            String cls = cur.className().toLowerCase(Locale.ROOT);
            if (cls.contains("card") || cls.contains("item") || cls.contains("comic") || cls.contains("series") || cls.contains("chapter")) return cur;
            cur = cur.parent();
        }
        return element == null ? null : element.parent();
    }

    private Element firstImage(Element item, Element link) {
        Element img = link == null ? null : link.selectFirst("img");
        if (img != null) return img;
        return item == null ? null : item.selectFirst("img[src], img[data-src], img[data-lazy-src], img[data-original]");
    }

    private String firstChapterText(Element item) {
        if (item == null) return "";
        for (Element link : item.select("a[href*='/chapter/']")) {
            String text = cleanChapterText(link.text());
            if (!text.isEmpty()) return text;
        }
        Matcher matcher = Pattern.compile("(?i)chapter\\s*[0-9]+(?:[.,-][0-9]+)?").matcher(item.text());
        if (matcher.find()) return matcher.group().trim();
        return "";
    }

    private String cleanListTitle(String text) {
        if (text == null) return "";
        String value = text.replaceAll("(?i)chapter\\s*[0-9]+(?:[.,-][0-9]+)?", "").replace("Belum ada chapter.", "").trim();
        return value.replaceAll("\\s+", " ").trim();
    }

    private String cleanChapterText(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile("(?i)chapter\\s*[#:-]?\\s*[0-9]+(?:[.,-][0-9]+)?").matcher(value);
        if (matcher.find()) return matcher.group().replace("-", ".").replaceAll("\\s+", " ").trim();
        Matcher path = Pattern.compile("(?i)(?:^|/)chapter[-_]?([0-9]+(?:[-_.][0-9]+)?)").matcher(value);
        if (path.find()) return "Chapter " + path.group(1).replace("-", ".");
        return value;
    }

    private String extractDate(String raw) {
        if (raw == null) return "";
        Matcher m = Pattern.compile("(?:\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4}|[A-Za-z]{3,9}\\s+\\d{1,2},\\s*\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{2,4})").matcher(raw);
        return m.find() ? m.group().trim() : "";
    }

    private float parseChapterIndex(String raw) {
        if (raw == null) return -1f;
        Matcher matcher = Pattern.compile("(?i)(?:chapter|chap|ch)?\\s*([0-9]+(?:[.,-][0-9]+)?)").matcher(raw);
        float last = -1f;
        while (matcher.find()) {
            try { last = Float.parseFloat(matcher.group(1).replace(",", ".").replace("-", ".")); } catch(Exception ignored) { }
        }
        return last;
    }

    private String cleanSeriesSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http")) {
            try {
                HttpUrl url = HttpUrl.parse(value);
                if (url != null) {
                    for (int i = 0; i < url.pathSize(); i++) {
                        if ("comic".equals(url.pathSegments().get(i)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                    }
                }
            } catch(Exception ignored) { }
        }
        value = value.replace(base(), "").trim();
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.startsWith("comic/")) value = value.substring(6);
        int chapter = value.indexOf("/chapter/");
        if (chapter >= 0) value = value.substring(0, chapter);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.trim();
    }

    private String cleanChapterUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http") && value.toLowerCase(Locale.ROOT).contains("/chapter/")) return value.replaceAll("/+$", "");
        return "";
    }

    private String cleanChapterSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("/+$", "");
        if (value.startsWith("http")) {
            try {
                HttpUrl url = HttpUrl.parse(value);
                if (url != null) {
                    for (int i = 0; i < url.pathSize(); i++) {
                        if ("chapter".equals(url.pathSegments().get(i)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                    }
                }
            } catch(Exception ignored) { }
        }
        int pos = value.toLowerCase(Locale.ROOT).indexOf("/chapter/");
        if (pos >= 0) value = value.substring(pos + 9);
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.trim();
    }

    private String seriesUrl(String slug) { return base() + "/comic/" + urlSegment(slug); }

    private String imageUrl(Element img) {
        if (img == null) return "";
        String url = firstNonEmpty(img.absUrl("data-src"), img.absUrl("data-lazy-src"), img.absUrl("data-original"), img.absUrl("data-pagespeed-lazy-src"), img.absUrl("src"), img.attr("data-src"), img.attr("data-lazy-src"), img.attr("data-original"), img.attr("data-pagespeed-lazy-src"), img.attr("src"), imageFromSrcset(img.attr("data-srcset")), imageFromSrcset(img.attr("srcset")));
        return absolutize(url);
    }

    private String absolutize(String raw) {
        String url = raw == null ? "" : raw.trim().replace("\\/", "/").replace("&amp;", "&");
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("/")) url = base() + url;
        if (url.startsWith("data:")) return "";
        return url.trim();
    }

    private String imageFromSrcset(String raw) {
        if (raw == null) return "";
        String best = "";
        int bestWidth = -1;
        for (String part : raw.split(",")) {
            String item = part == null ? "" : part.trim();
            if (item.isEmpty()) continue;
            String[] pieces = item.split("\\s+");
            String url = pieces.length > 0 ? pieces[0].trim() : "";
            if (url.isEmpty()) continue;
            int width = 0;
            if (pieces.length > 1) {
                try { width = Integer.parseInt(pieces[1].replaceAll("[^0-9]", "")); } catch(Exception ignored) { }
            }
            if (best.isEmpty() || width > bestWidth) {
                best = url;
                bestWidth = width;
            }
        }
        return best;
    }

    private String meta(Document document, String selector) {
        Element element = document == null ? null : document.selectFirst(selector);
        return element == null ? "" : element.attr("content").trim();
    }

    private String text(Document document, String selector) {
        Element element = document == null ? null : document.selectFirst(selector);
        return element == null ? "" : element.text().trim();
    }

    private String text(Element root, String selector) {
        Element element = root == null ? null : root.selectFirst(selector);
        return element == null ? "" : element.text().trim();
    }

    private String text(Element element) { return element == null ? "" : element.text().trim(); }

    private String attr(Element element, String name) {
        if (element == null || name == null) return "";
        return element.attr(name).trim();
    }

    private String joinTexts(Elements elements) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : elements) {
            String text = element.text().trim();
            if (!text.isEmpty() && seen.add(text)) values.add(text);
        }
        return TextUtils.join(", ", values);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private String urlSegment(String value) {
        if (value == null) return "";
        return value.trim().replace(" ", "%20");
    }

    private String titleFromSlug(String slug) {
        if (slug == null) return "";
        StringBuilder builder = new StringBuilder();
        for (String part : slug.split("-")) {
            if (part.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.length() > 1 ? part.substring(1) : "");
        }
        return builder.toString();
    }

    private String genreValue(String href) {
        String value = href == null ? "" : href.trim();
        try {
            HttpUrl url = HttpUrl.parse(value);
            if (url != null) {
                String q = url.queryParameter("genre");
                if (q != null && !q.trim().isEmpty()) return q.trim();
                for (int i = 0; i < url.pathSize(); i++) {
                    String segment = url.pathSegments().get(i);
                    if (("genre".equals(segment) || "genres".equals(segment)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                }
            }
        } catch(Exception ignored) { }
        return value.replace(base(), "").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private boolean isNoiseGenre(String title) {
        if (title == null) return true;
        String value = title.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("genres") || value.equals("genre") || value.equals("comic") || value.equals("soul scans") || value.equals("soulscans") || value.equals("soul scans id");
    }

    private ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] value : fallbackGenrePairs()) out.add(new GenreItem(value[0], value[1]));
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
        out.add(new GenreItem("Ongoing", "status:ongoing"));
        out.add(new GenreItem("Completed", "status:completed"));
        return out;
    }

    private String[][] fallbackGenrePairs() {
        return new String[][]{{"Action", "action"}, {"Adventure", "adventure"}, {"Comedy", "comedy"}, {"Drama", "drama"}, {"Fantasy", "fantasy"}, {"Harem", "harem"}, {"Historical", "historical"}, {"Horror", "horror"}, {"Isekai", "isekai"}, {"Magic", "magic"}, {"Manhua", "manhua"}, {"Manhwa", "manhwa"}, {"Martial Arts", "martial-arts"}, {"Mature", "mature"}, {"Mystery", "mystery"}, {"Psychological", "psychological"}, {"Reincarnation", "reincarnation"}, {"Romance", "romance"}, {"School Life", "school-life"}, {"Sci-Fi", "sci-fi"}, {"Seinen", "seinen"}, {"Shoujo", "shoujo"}, {"Shounen", "shounen"}, {"Slice of Life", "slice-of-life"}, {"Supernatural", "supernatural"}, {"Thriller", "thriller"}, {"Tragedy", "tragedy"}, {"Webtoon", "webtoon"}};
    }

    private static final class FilterParts {
        String genre = "";
        String type = "";
        String status = "";
    }
}
