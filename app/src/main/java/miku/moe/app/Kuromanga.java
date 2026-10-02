package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Kuromanga extends KomikcastClient {
    public static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_KUROMANGA;
    protected static String base() { return MangaSettingsManager.getSourceDomain(SOURCE_ID); }
    private static final String LABEL = "Kuromanga";
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
            String url = buildListUrl(Math.max(1, page), sort, query, genre);
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 10); return; }
            getDocument(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(document);
                            boolean next = hasNextPage(document, Math.max(1, page), out.size());
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Kuromanga gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(base() + "/manga/?order=update", new Result<Document>() {
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
        if (clean.isEmpty()) { cb.onError("Slug Kuromanga kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getDocument(seriesUrl(clean), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(clean, document);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail Kuromanga kosong")); return; }
                        ArrayList<MangaChapter> chapters = parseChapters(clean, document);
                        post.totalChapters = chapters.size();
                        if (!chapters.isEmpty()) {
                            MangaChapter newest = chapters.get(0);
                            for (MangaChapter chapter : chapters) if (chapter.index > newest.index) newest = chapter;
                            post.latestChapter = newest.title == null ? "" : newest.title;
                            post.latestChapterDate = newest.date == null ? "" : newest.date;
                        }
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Kuromanga gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Kuromanga kosong"); return; }
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
        getDocument(chapterUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> pages = parsePages(document, chapterUrl);
                        if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman Kuromanga kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Kuromanga gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadPagesFromCandidates(ArrayList<String> urls, int pos, String key, Result<ArrayList<String>> cb) {
        if (urls == null || pos >= urls.size()) { cb.onError("Chapter Kuromanga tidak ditemukan"); return; }
        loadPages(urls.get(pos), key, new Result<ArrayList<String>>() {
            @Override public void onSuccess(ArrayList<String> data, boolean hasNext) { cb.onSuccess(data, hasNext); }
            @Override public void onError(String message) { loadPagesFromCandidates(urls, pos + 1, key, cb); }
        });
    }

    private String buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            String encoded = URLEncoder.encode(q, "UTF-8");
            if (page <= 1) return base() + "/?s=" + encoded;
            return base() + "/page/" + page + "/?s=" + encoded;
        }
        FilterParts filter = parseFilters(genre);
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (filter.status.isEmpty() && ("completed".equals(s) || "complete".equals(s))) filter.status = "completed";
        String order = orderParam(sort);
        if (!filter.requiresAdvancedQuery()) {
            String genreSlug = firstSlugGenre(filter);
            if (!genreSlug.isEmpty()) {
                String path = "/genres/" + urlSegment(genreSlug) + "/";
                if (page > 1) path = "/genres/" + urlSegment(genreSlug) + "/page/" + page + "/";
                return base() + path;
            }
        }
        HttpUrl parsed = HttpUrl.parse(base() + "/manga/");
        if (parsed == null) return base() + "/manga/?order=" + URLEncoder.encode(order, "UTF-8");
        HttpUrl.Builder builder = parsed.newBuilder();
        if (page > 1) builder.addQueryParameter("page", String.valueOf(page));
        for (String value : filter.genres) builder.addQueryParameter("genre[]", value);
        if (!filter.status.isEmpty()) builder.addQueryParameter("status", filter.status);
        if (!filter.type.isEmpty()) builder.addQueryParameter("type", filter.type);
        builder.addQueryParameter("order", order);
        return builder.build().toString();
    }

    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements items = document.select(".seriesearch .listupd .bsx, .postbody .listupd .bsx, .listupd .bsx, .manga-list .manga-item, a.manga-item, .manga-item");
        if (items.isEmpty()) items = document.select(".bsx");
        for (Element item : items) addListItem(out, seen, item);
        if (out.isEmpty()) {
            for (Element link : document.select("main a[href*='/manga/'], .postbody a[href*='/manga/'], .bixbox a[href*='/manga/'], article a[href*='/manga/']")) addListItem(out, seen, link);
        }
        return out;
    }

    private void addListItem(ArrayList<MangaPost> out, LinkedHashSet<String> seen, Element item) {
        Element link = item.tagName().equalsIgnoreCase("a") ? item : item.selectFirst("a[href*='/manga/']");
        if (link == null) return;
        String href = link.absUrl("href");
        if (href.contains("/manga/list-mode") || href.contains("/manga/?")) return;
        String slug = cleanSeriesSlug(href);
        if (slug.isEmpty() || !seen.add(slug)) return;
        String title = firstNonEmpty(text(item, ".tt"), text(item, ".manga-title"), attr(link, "title"), attr(item.selectFirst("img"), "title"), attr(item.selectFirst("img"), "alt"), link.text(), titleFromSlug(slug));
        String cover = imageUrl(item.selectFirst(".limit img, img"));
        String type = parseType(item.selectFirst(".limit .type, .type, span.type, .typename"));
        String status = parseStatus(item.selectFirst(".limit .status, .status"));
        String latest = cleanChapterText(text(item, ".adds .epxs, .epxs, .chapter, .chap"));
        MangaPost post = new MangaPost(slug, title, cover, "", status, "", "", type, latest, "").withSource(SOURCE_ID, LABEL);
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, base() + "/");
        out.add(post);
    }

    private MangaPost parseDetail(String clean, Document document) {
        String title = firstNonEmpty(text(document, ".seriestuheader h1.entry-title"), text(document, "h1.entry-title"), text(document, ".entry-title"), document.title() == null ? "" : document.title().replace("- KuroManga", "").replace("| KuroManga", "").replace("Baca Manga", "").trim());
        String cover = imageUrl(document.selectFirst(".thumb img, .info-left .thumb img, .main-info .thumb img, article img[itemprop=image], img.wp-post-image"));
        String synopsis = cleanSynopsis(firstNonEmpty(text(document, ".seriestuhead .entry-content.entry-content-single"), text(document, ".info-desc .entry-content.entry-content-single"), text(document, ".entry-content.entry-content-single[itemprop=description]"), text(document, ".entry-content.entry-content-single p"), text(document, ".entry-content-single")));
        String genre = joinTexts(document.select(".seriestugenre a[href*='/genres/'], .seriestugenre a[rel=tag], .mgen a, span.mgen a"));
        String status = firstNonEmpty(infoValue(document, "Status"), MangaStatusParser.fromInfoRows(document), parseStatus(document.selectFirst(".status")));
        String type = firstNonEmpty(infoValue(document, "Type"), parseType(document.selectFirst(".type")));
        String author = infoValue(document, "Author");
        MangaPost post = new MangaPost(clean, title, cover, author, status, synopsis, genre, type).withSource(SOURCE_ID, LABEL);
        post.info = detailInfo(document, genre);
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, seriesUrl(clean));
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String seriesSlug, Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element item : document.select("#chapterlist li, .eplister#chapterlist li")) {
            Element link = item.selectFirst(".eph-num a[href], a[href]");
            if (link == null) continue;
            String href = link.absUrl("href");
            if (href.isEmpty() || !href.toLowerCase(Locale.ROOT).contains("chapter")) continue;
            String title = chapterTitleFromItem(item, link);
            float index = parseChapterIndex(firstNonEmpty(attr(item, "data-num"), title, href));
            if (index < 0) continue;
            String key = chapterSeenKey(href, title, index);
            if (!seen.add(key)) continue;
            String date = firstNonEmpty(text(item, ".chapterdate"), attr(item.selectFirst("time"), "datetime"));
            MangaChapter chapter = new MangaChapter(seriesSlug, index, cleanChapterTitle(title, index), date);
            chapter.chapterId = href;
            out.add(chapter);
        }
        out.addAll(parseChapterOptions(seriesSlug, document, seen));
        return out;
    }

    private ArrayList<MangaChapter> parseChapterOptions(String seriesSlug, Document document, LinkedHashSet<String> seen) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> localSeen = seen == null ? new LinkedHashSet<>() : seen;
        for (Element option : document.select("select[name=chapter] option[value], select#chapter option[value]")) {
            String value = option.attr("value").trim();
            if (value.isEmpty() || value.equals("#") || value.startsWith("?")) continue;
            String href = option.absUrl("value");
            if (href.isEmpty() || !href.toLowerCase(Locale.ROOT).contains("chapter")) continue;
            String title = text(option);
            if (title.isEmpty() || title.equalsIgnoreCase("Select Chapter")) continue;
            float index = parseChapterIndex(firstNonEmpty(attr(option, "data-num"), title, href));
            if (index < 0) continue;
            String key = chapterSeenKey(href, title, index);
            if (!localSeen.add(key)) continue;
            MangaChapter chapter = new MangaChapter(seriesSlug, index, cleanChapterTitle(title, index), "");
            chapter.chapterId = href;
            out.add(chapter);
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("#readerarea img.ts-main-image[src], #readerarea img.ts-main-image[data-src], #readerarea img[data-index][src], #readerarea img[data-index][data-src], #readerarea img[data-server][src], #readerarea picture img");
        for (Element img : images) addPage(out, seen, imageUrl(img), chapterUrl);
        if (!out.isEmpty()) return out;
        String html = document.outerHtml();
        try {
            Matcher tsReader = Pattern.compile("ts_reader\\.run\\((\\{.*?\\})\\);", Pattern.DOTALL).matcher(html);
            while (tsReader.find()) {
                JsonObject root = JsonParser.parseString(tsReader.group(1)).getAsJsonObject();
                JsonArray sources = jsonArray(root, "sources");
                for (JsonElement sourceElement : sources) {
                    if (sourceElement == null || !sourceElement.isJsonObject()) continue;
                    JsonArray sourceImages = jsonArray(sourceElement.getAsJsonObject(), "images");
                    for (JsonElement imageElement : sourceImages) if (imageElement != null && !imageElement.isJsonNull()) addPage(out, seen, imageElement.getAsString(), chapterUrl);
                }
            }
        } catch(Exception ignored) { }
        return out;
    }

    private void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String raw, String chapterUrl) {
        String url = raw == null ? "" : raw.trim().replace("\\/", "/");
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("/")) url = base() + url;
        if (!url.startsWith("http")) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("readerarea.svg") || lower.contains("loading") || lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("gravatar") || lower.contains("histats") || lower.contains("/ads") || lower.contains("/iklan") || lower.contains("trakteer")) return;
        if (!lower.matches(".*\\.(jpg|jpeg|png|webp|avif)(?:\\?.*)?$")) return;
        if (seen.add(url)) {
            MangaImageLoader.registerImageReferer(url, chapterUrl == null || chapterUrl.trim().isEmpty() ? base() + "/" : chapterUrl.trim());
            out.add(url);
        }
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element input : document.select("input.genre-item[name='genre[]'][value], input[name='genre[]'][value]")) {
            String value = input.attr("value").trim();
            if (value.isEmpty() || !seen.add(value)) continue;
            String label = "";
            String id = input.attr("id").trim();
            if (!id.isEmpty()) label = text(document.selectFirst("label[for='" + id + "']"));
            if (label.isEmpty()) label = text(input.parent());
            if (label.isEmpty() || isNoiseGenre(label)) continue;
            out.add(new GenreItem(label, value));
        }
        if (out.isEmpty()) out.addAll(parseGenreLinks(document));
        return out;
    }

    private ArrayList<GenreItem> parseGenreLinks(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select(".taxindex a[href*='/genres/'], a[href*='/genres/']")) {
            String value = cleanGenreSlug(link.absUrl("href"));
            if (value.isEmpty()) continue;
            String knownId = knownGenreId(value);
            if (!knownId.isEmpty()) value = knownId;
            if (!seen.add(value)) continue;
            Element span = link.selectFirst("span");
            String title = firstNonEmpty(span == null ? "" : span.text(), link.ownText(), link.text(), titleFromSlug(value));
            if (title.isEmpty() || isNoiseGenre(title)) continue;
            out.add(new GenreItem(title, value));
        }
        return out;
    }

    private boolean hasNextPage(Document document, int page, int size) {
        if (document.selectFirst(".pagination a.next, a.next.page-numbers, a.nextpostslink, a[rel=next], .hpage a.r") != null) return true;
        for (Element link : document.select(".pagination a[href], .hpage a[href], .nav-links a[href]")) {
            String text = link.text() == null ? "" : link.text().trim();
            if (text.equalsIgnoreCase("Next") || text.equals("›") || text.equals("»")) return true;
            String href = link.absUrl("href");
            Matcher m = Pattern.compile("(?:/page/|[?&]page=)([0-9]+)").matcher(href);
            while (m.find()) {
                try { if (Integer.parseInt(m.group(1)) > page) return true; } catch(Exception ignored) { }
            }
        }
        return size >= 10 && document.selectFirst(".pagination a[href*='page='], .pagination a[href*='/page/'], .hpage a[href]") != null;
    }

    private void getDocument(String url, Result<Document> cb) { getDocument(new Request.Builder().url(url).headers(headers()).build(), cb); }

    private void getDocument(Request req, Result<Document> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    Document doc = Jsoup.parse(body, req.url().toString());
                    main.post(() -> cb.onSuccess(doc, false));
                } catch(Exception e) { main.post(() -> cb.onError("Data Kuromanga gagal dibaca")); }
            }
        });
    }

    private Headers headers() {
        String ref = base() + "/";
        return new Headers.Builder()
                .set("Referer", ref)
                .set("Origin", base())
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "no-cache")
                .set("Pragma", "no-cache")
                .set("Upgrade-Insecure-Requests", "1")
                .set("Sec-Fetch-Site", "same-origin")
                .set("Sec-Fetch-Mode", "navigate")
                .set("Sec-Fetch-Dest", "document")
                .set("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                .set("sec-ch-ua-mobile", "?1")
                .set("sec-ch-ua-platform", "\"Android\"")
                .build();
    }

    private boolean needsEnrichment(MangaPost post, boolean loadChapter, boolean loadType) {
        if (post == null || post.slug == null || post.slug.trim().isEmpty()) return false;
        boolean missingChapter = loadChapter && (post.latestChapter == null || post.latestChapter.trim().isEmpty());
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
        addCandidate(out, clean, idx);
        return out;
    }

    private void addCandidate(ArrayList<String> out, String slug, String chapter) {
        String url = base() + "/" + urlSegment(slug) + "-chapter-" + urlSegment(chapter) + "/";
        if (!out.contains(url)) out.add(url);
    }

    private String chapterTitleFromItem(Element item, Element link) {
        String title = firstNonEmpty(text(item, ".chapternum"), text(link, ".chapternum"));
        if (title.isEmpty()) {
            String dataNum = attr(item, "data-num");
            if (!dataNum.isEmpty()) title = "Chapter " + dataNum;
        }
        if (title.isEmpty() && link != null) {
            Element clone = link.clone();
            clone.select(".chapterdate, time").remove();
            title = clone.text().trim();
        }
        return cleanChapterText(title);
    }

    private String chapterSeenKey(String href, String title, float index) {
        String key = href == null ? "" : href.trim().toLowerCase(Locale.ROOT);
        if (!key.isEmpty()) return key;
        return MangaChapter.formatIndex(index) + ":" + (title == null ? "" : title.trim().toLowerCase(Locale.ROOT));
    }

    private String stripChapterDate(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        String months = "January|February|March|April|May|June|July|August|September|October|November|December|Januari|Februari|Maret|April|Mei|Juni|Juli|Agustus|September|Oktober|November|Desember";
        value = value.replaceFirst("(?i)\\s+(" + months + ")\\s+\\d{1,2},?\\s+\\d{4}$", "").trim();
        value = value.replaceFirst("(?i)\\s+\\d{1,2}\\s+(" + months + ")\\s+\\d{4}$", "").trim();
        return value;
    }

    private FilterParts parseFilters(String raw) {
        FilterParts out = new FilterParts();
        if (raw == null) return out;
        for (String part : raw.split("[|,]")) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) {
                String type = normalizeType(value.substring(5));
                if ("manga".equals(type) || "manhwa".equals(type) || "manhua".equals(type)) out.type = type;
                continue;
            }
            if (lower.startsWith("status:")) { out.status = normalizeStatus(value.substring(7)); continue; }
            if (lower.startsWith("genre:")) value = value.substring(6).trim();
            String knownId = knownGenreId(value);
            if (!knownId.isEmpty()) value = knownId;
            if (!value.isEmpty() && !out.genres.contains(value)) out.genres.add(value);
        }
        return out;
    }

    private String firstSlugGenre(FilterParts filter) {
        if (filter == null) return "";
        if (filter.genres.size() != 1) return "";
        String value = filter.genres.get(0);
        return value.matches("^[0-9]+$") ? "" : cleanGenreSlug(value);
    }

    private String knownGenreId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace("_", "-");
        if (value.matches("^[0-9]+$")) return value;
        String[][] values = fallbackGenrePairs();
        for (String[] item : values) {
            String title = item[0].toLowerCase(Locale.ROOT).replace(" ", "-").replace("'", "").replace("(", "").replace(")", "");
            String clean = value.replace("'", "").replace("(", "").replace(")", "");
            if (title.equals(clean) || item[0].toLowerCase(Locale.ROOT).equals(value)) return item[1];
        }
        return "";
    }

    private String orderParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s)) return "popular";
        if ("added".equals(s) || "new".equals(s) || "latest_added".equals(s)) return "latest";
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "title";
        if ("za".equals(s) || "z-a".equals(s) || "titlereverse".equals(s)) return "titlereverse";
        return "update";
    }

    private String parseType(Element element) {
        if (element == null) return "";
        String text = element.text().trim();
        if (!text.isEmpty()) return MangaPost.normalizeType(text, "", "");
        for (String cls : element.classNames()) {
            if ("type".equalsIgnoreCase(cls)) continue;
            if (cls.equalsIgnoreCase("manga") || cls.equalsIgnoreCase("manhwa") || cls.equalsIgnoreCase("manhua")) return cls;
        }
        return "";
    }

    private String parseStatus(Element element) {
        if (element == null) return "";
        String text = element.text().trim();
        if (!text.isEmpty()) return text;
        for (String cls : element.classNames()) {
            if ("status".equalsIgnoreCase(cls)) continue;
            if (cls.equalsIgnoreCase("completed") || cls.equalsIgnoreCase("ongoing")) return cls;
        }
        return "";
    }

    private String normalizeType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(value)) return "manga";
        if ("manhwa".equals(value)) return "manhwa";
        if ("manhua".equals(value)) return "manhua";
        return "";
    }

    private String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) || "complete".equals(value)) return "completed";
        if ("ongoing".equals(value) || "on-going".equals(value)) return "ongoing";
        if ("hiatus".equals(value)) return "hiatus";
        return value;
    }

    private String infoValue(Document document, String label) {
        String wanted = label == null ? "" : label.trim().replace(":", "").toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return "";
        for (Element row : document.select("table.infotable tr, .infotable tr")) {
            Elements cells = row.select("td, th");
            if (cells.size() < 2) continue;
            String key = cells.get(0).text().replace(":", "").trim().toLowerCase(Locale.ROOT);
            if (!key.equals(wanted)) continue;
            return cells.get(1).text().trim();
        }
        for (Element item : document.select(".tsinfo .imptdt, .imptdt")) {
            String full = item.text().trim();
            if (!full.toLowerCase(Locale.ROOT).startsWith(wanted)) continue;
            Element value = item.selectFirst("i, a, span");
            if (value != null) return value.text().trim();
            return full.replaceFirst("(?i)^" + Pattern.quote(label), "").replace(":", "").trim();
        }
        return "";
    }

    private String detailInfo(Document document, String genre) {
        ArrayList<String> values = new ArrayList<>();
        addInfo(values, "Alternative", firstNonEmpty(text(document, ".seriestualt"), text(document, ".alternative"), infoValue(document, "Alternative"), infoValue(document, "Alternative Name")));
        addInfo(values, "Status", infoValue(document, "Status"));
        addInfo(values, "Type", infoValue(document, "Type"));
        addInfo(values, "Released", infoValue(document, "Released"));
        addInfo(values, "Author", infoValue(document, "Author"));
        addInfo(values, "Artist", infoValue(document, "Artist"));
        return TextUtils.join("\n", values);
    }

    private void addInfo(ArrayList<String> out, String label, String value) {
        if (out == null || value == null || value.trim().isEmpty()) return;
        out.add(label + ": " + value.trim());
    }

    private JsonArray jsonArray(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name) || !object.get(name).isJsonArray()) return new JsonArray();
        return object.get(name).getAsJsonArray();
    }

    private String cleanChapterText(String raw) {
        if (raw == null) return "";
        String value = stripChapterDate(raw.trim().replaceAll("\\s+", " "));
        return value.equalsIgnoreCase("Select Chapter") ? "" : value;
    }

    private String cleanChapterTitle(String raw, float index) {
        String idx = MangaChapter.formatIndex(index);
        String value = cleanChapterText(raw);
        if (idx.isEmpty()) return value;
        if (value.isEmpty() || value.equalsIgnoreCase("Chapter")) return "Chapter " + idx;
        Matcher matcher = Pattern.compile("(?i)^chapter\\s+" + Pattern.quote(idx) + "(?:\\s*[:\\-–—])?\\s*(.*)$").matcher(value);
        if (matcher.find()) {
            String suffix = matcher.group(1) == null ? "" : matcher.group(1).trim();
            return suffix.isEmpty() ? "Chapter " + idx : suffix;
        }
        matcher = Pattern.compile("(?i)^" + Pattern.quote(idx) + "(?:\\s*[:\\-–—])?\\s*(.*)$").matcher(value);
        if (matcher.find()) {
            String suffix = matcher.group(1) == null ? "" : matcher.group(1).trim();
            return suffix.isEmpty() ? "Chapter " + idx : suffix;
        }
        return value;
    }

    private float parseChapterIndex(String raw) {
        if (raw == null) return -1f;
        Matcher matcher = Pattern.compile("(?i)(?:chapter|chap|ch)?\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(raw);
        float last = -1f;
        while (matcher.find()) {
            try { last = Float.parseFloat(matcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
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
                        if ("manga".equals(url.pathSegments().get(i)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                    }
                }
            } catch(Exception ignored) { }
        }
        value = value.replace(base(), "").trim();
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.startsWith("manga/")) value = value.substring(6);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        if (value.toLowerCase(Locale.ROOT).contains("chapter")) return "";
        return value.trim();
    }

    private String cleanGenreSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http")) {
            try {
                HttpUrl url = HttpUrl.parse(value);
                if (url != null) {
                    for (int i = 0; i < url.pathSize(); i++) {
                        if ("genres".equals(url.pathSegments().get(i)) && i + 1 < url.pathSize()) return url.pathSegments().get(i + 1).trim();
                    }
                }
            } catch(Exception ignored) { }
        }
        value = value.replace(base(), "").replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.startsWith("genres/")) value = value.substring(7);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.trim();
    }

    private String cleanChapterUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http") && value.toLowerCase(Locale.ROOT).contains("chapter")) return value;
        return "";
    }

    private String seriesUrl(String slug) { return base() + "/manga/" + urlSegment(slug) + "/"; }

    private String imageUrl(Element img) {
        if (img == null) return "";
        String url = firstNonEmpty(img.absUrl("data-src"), img.absUrl("data-lazy-src"), img.absUrl("data-original"), img.absUrl("data-pagespeed-lazy-src"), img.absUrl("src"), img.attr("data-src"), img.attr("data-lazy-src"), img.attr("data-original"), img.attr("data-pagespeed-lazy-src"), img.attr("src"), imageFromSrcset(img.attr("data-srcset")), imageFromSrcset(img.attr("srcset")));
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

    private String cleanSynopsis(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (value.equalsIgnoreCase("Sinopsis")) return "";
        value = value.replaceFirst("(?i)^sinopsis\\s*", "").trim();
        return value;
    }

    private boolean isNoiseGenre(String title) {
        if (title == null) return true;
        String value = title.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("genres") || value.equals("genre") || value.equals("manga") || value.equals("kuromanga");
    }

    private ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] value : fallbackGenrePairs()) out.add(new GenreItem(value[0], value[1]));
        return out;
    }

    private String[][] fallbackGenrePairs() {
        return new String[][]{{"Action", "action"}, {"Adventure", "adventure"}, {"Comedy", "comedy"}, {"Drama", "drama"}, {"Ecchi", "ecchi"}, {"Fantasy", "fantasy"}, {"Harem", "harem"}, {"Historical", "historical"}, {"Horror", "horror"}, {"Isekai", "isekai"}, {"Magic", "magic"}, {"Manhua", "manhua"}, {"Manhwa", "manhwa"}, {"Martial Arts", "martial-arts"}, {"Mature", "mature"}, {"Mystery", "mystery"}, {"Psychological", "psychological"}, {"Reincarnation", "reincarnation"}, {"Romance", "romance"}, {"School Life", "school-life"}, {"Sci-Fi", "sci-fi"}, {"Seinen", "seinen"}, {"Shoujo", "shoujo"}, {"Shounen", "shounen"}, {"Slice of Life", "slice-of-life"}, {"Supernatural", "supernatural"}, {"Thriller", "thriller"}, {"Tragedy", "tragedy"}, {"Webtoon", "webtoon"}};
    }

    private static final class FilterParts {
        final ArrayList<String> genres = new ArrayList<>();
        String type = "";
        String status = "";
        boolean requiresAdvancedQuery() {
            if (!type.isEmpty() || !status.isEmpty()) return true;
            for (String genre : genres) if (genre != null && genre.trim().matches("^[0-9]+$")) return true;
            return genres.size() > 1;
        }
    }
}
