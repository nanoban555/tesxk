package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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

/** Parser khusus SekteDoujin (sektedoujin.cc). WordPress theme ala Komikcast/Luvyaa.
 *  Reader pakai JSON images (cdn.uqni.net / cdn.komikindo.info). */
public class SekteDoujin extends KomikcastClient {
    public static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN;
    protected static String base() { return MangaSettingsManager.getSourceDomain(SOURCE_ID); }
    private static final String LABEL = "SekteDoujin";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ListCacheEntry> LIST_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);

    /** Cache listing: daftar post + flag hasNextPage yang benar (bukan heuristik size). */
    private static final class ListCacheEntry {
        final ArrayList<MangaPost> posts;
        final boolean hasNext;
        ListCacheEntry(ArrayList<MangaPost> posts, boolean hasNext) { this.posts = posts; this.hasNext = hasNext; }
    }
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
            ListCacheEntry cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached.posts), cached.hasNext); return; }
            getDocument(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(document);
                            boolean next = hasNextPage(document, Math.max(1, page), out.size());
                            LIST_CACHE.put(url, new ListCacheEntry(new ArrayList<>(out), next));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar SekteDoujin gagal dibaca")); }
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
        if (clean.isEmpty()) { cb.onError("Slug SekteDoujin kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getDocument(seriesUrl(clean), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(clean, document);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail SekteDoujin kosong")); return; }
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
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail SekteDoujin gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug SekteDoujin kosong"); return; }
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
                        if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman SekteDoujin kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman SekteDoujin gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadPagesFromCandidates(ArrayList<String> urls, int pos, String key, Result<ArrayList<String>> cb) {
        if (urls == null || pos >= urls.size()) { cb.onError("Chapter SekteDoujin tidak ditemukan"); return; }
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
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("project".equals(s)) {
            if (page <= 1) return base() + "/project/";
            return base() + "/project/page/" + page + "/";
        }
        FilterParts filter = parseFilters(genre);
        String genreSlug = firstSlugGenre(filter);
        if (!genreSlug.isEmpty()) {
            String path = "/genres/" + urlSegment(genreSlug) + "/";
            if (page > 1) path = "/genres/" + urlSegment(genreSlug) + "/page/" + page + "/";
            return base() + path;
        }
        String status = filter.status;
        if (status.isEmpty()) {
            if ("completed".equals(s) || "complete".equals(s)) status = "completed";
            else if ("ongoing".equals(s) || "on-going".equals(s)) status = "ongoing";
            else if ("hiatus".equals(s)) status = "hiatus";
        }
        HttpUrl parsed = HttpUrl.parse(base() + "/manga/");
        if (parsed == null) return base() + "/manga/?order=" + URLEncoder.encode(orderParam(sort), "UTF-8");
        HttpUrl.Builder builder = parsed.newBuilder();
        if (page > 1) builder.addQueryParameter("page", String.valueOf(page));
        for (String value : filter.genres) builder.addQueryParameter("genre[]", value);
        if (!status.isEmpty()) builder.addQueryParameter("status", status);
        if (!filter.type.isEmpty()) builder.addQueryParameter("type", filter.type);
        if (filter.genres.isEmpty()) builder.addQueryParameter("order", orderParam(sort));
        return builder.build().toString();
    }

    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements items = document.select(".listupd .bsx, .bsx");
        for (Element item : items) addListItem(out, seen, item);
        return out;
    }

    private void addListItem(ArrayList<MangaPost> out, LinkedHashSet<String> seen, Element item) {
        Element link = item.selectFirst("a[href]");
        if (link == null) return;
        String href = link.absUrl("href");
        String slug = cleanSeriesSlug(href);
        if (slug.isEmpty() || !seen.add(slug)) return;
        String title = firstNonEmpty(text(item, ".tt"), attr(link, "title"), attr(item.selectFirst("img"), "title"), attr(item.selectFirst("img"), "alt"), link.text(), titleFromSlug(slug));
        String cover = imageUrl(item.selectFirst(".limit img, img"));
        String type = parseType(item.selectFirst("span.type, .type"));
        String status = parseStatus(item.selectFirst("span.status, .status"));
        String latest = cleanChapterText(firstNonEmpty(text(item, ".epxs"), text(item, ".fivchap")));
        MangaPost post = new MangaPost(slug, title, cover, "", status, "", "", type, latest, "").withSource(SOURCE_ID, LABEL);
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, base() + "/");
        out.add(post);
    }

    private MangaPost parseDetail(String clean, Document document) {
        String title = firstNonEmpty(text(document, "h1.entry-title"), text(document, ".entry-title"), document.title() == null ? "" : document.title().replace("- Sektedoujin", "").replace("| Sektedoujin", "").trim());
        String cover = imageUrl(document.selectFirst(".thumb img, img.wp-post-image"));
        String synopsis = cleanSynopsis(firstNonEmpty(text(document, ".entry-content[itemprop=description]"), text(document, ".entry-content-single"), text(document, "#synopsis-wrapper")));
        String genre = joinTexts(document.select(".mgen a[rel=tag], .seriestugenre a[rel=tag]"));
        String type = "";
        String released = "";
        String author = "";
        String artist = "";
        String serialization = "";
        for (Element item : document.select(".tsinfo .imptdt, .imptdt")) {
            String own = item.ownText() == null ? "" : item.ownText().trim().toLowerCase(Locale.ROOT);
            String value = firstNonEmpty(text(item, "i"), text(item, "a"));
            if (value.isEmpty()) continue;
            if (own.contains("type")) type = MangaPost.normalizeType(value, "", "");
            else if (own.contains("released")) released = value;
            else if (own.contains("author")) author = value;
            else if (own.contains("artist")) artist = value;
            else if (own.contains("serialization") || own.contains("serial")) serialization = value;
        }
        if (genre.isEmpty()) {
            for (Element item : document.select(".mr-meta-row .meta-item")) {
                String label = text(item, ".meta-label").toLowerCase(Locale.ROOT);
                String value = joinTexts(item.select(".meta-pill"));
                if (value.isEmpty()) continue;
                if (label.contains("genre")) genre = value;
            }
        }
        String status = firstNonEmpty(MangaStatusParser.fromInfoRows(document), firstNonEmpty(parseStatus(document.selectFirst("span.status, .status")), text(document, "span.status")));
        MangaPost post = new MangaPost(clean, title, cover, author, status, synopsis, genre, type).withSource(SOURCE_ID, LABEL);
        ArrayList<String> infoValues = new ArrayList<>();
        addInfo(infoValues, "Status", status);
        addInfo(infoValues, "Type", type);
        addInfo(infoValues, "Released", released);
        addInfo(infoValues, "Author", author);
        addInfo(infoValues, "Artist", artist);
        addInfo(infoValues, "Serialization", serialization);
        post.info = TextUtils.join("\n", infoValues);
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, seriesUrl(clean));
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String seriesSlug, Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element item : document.select("#chapterlist li[data-num], #chapterlist li")) {
            Element link = item.selectFirst("a[href]");
            if (link == null) continue;
            String href = link.absUrl("href");
            if (href.isEmpty() || !href.toLowerCase(Locale.ROOT).contains("chapter")) continue;
            // Skip JS template placeholders ({{number}}) — hanya untuk history browser, bukan chapter asli
            if (href.contains("{{") || href.contains("}}")) continue;
            float index = parseChapterIndex(firstNonEmpty(attr(item, "data-num"), text(item, ".chapternum"), href));
            if (index < 0) continue;
            String key = chapterSeenKey(href, "", index);
            if (!seen.add(key)) continue;
            String date = firstNonEmpty(text(item, ".chapterdate"), attr(item.selectFirst("time"), "datetime"));
            String title = "Chapter " + MangaChapter.formatIndex(index);
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, date);
            chapter.chapterId = href;
            out.add(chapter);
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        // SekteDoujin: gambar chapter ada di JSON "sources":[{"source":"Server 1","images":[...]}]
        // (CDN: cdn.uqni.net untuk chapter baru, cdn.komikindo.info untuk chapter lama)
        try {
            String html = document.html().replace("\\/", "/");
            Matcher m = Pattern.compile("\"sources\"\\s*:\\s*\\[\\s*\\{[^}]*?\"images\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(html);
            if (m.find()) {
                Matcher im = Pattern.compile("\"(https?://[^\"\\\\]+\\.(?:jpg|jpeg|png|webp|avif)(?:\\?[^\"\\\\]*)?)\"", Pattern.CASE_INSENSITIVE).matcher(m.group(1));
                while (im.find()) addPage(out, seen, im.group(1).replace("\\/", "/"), chapterUrl);
            }
        } catch (Exception ignored) { }
        // Fallback: #readerarea img (kalau struktur berubah)
        if (out.isEmpty()) {
            Elements images = document.select("#readerarea img[src], #readerarea img[data-src]");
            for (Element img : images) addPage(out, seen, imageUrl(img), chapterUrl);
        }
        return out;
    }

    private void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String raw, String chapterUrl) {
        String url = raw == null ? "" : raw.trim().replace("\\/", "/");
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("/")) url = base() + url;
        if (!url.startsWith("http")) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("readerarea.svg") || lower.contains("loading") || lower.contains("logo") || lower.contains("banner") || lower.contains("premium") || lower.contains("ibb.co") || lower.contains("avatar") || lower.contains("gravatar") || lower.contains("histats") || lower.contains("/ads") || lower.contains("/iklan") || lower.contains("trakteer")) return;
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
        for (Element link : document.select("a[href*='/genres/']")) {
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
        if (document.selectFirst(".pagination a.next, .hpage a.next, .hpage a.r, a.next.page-numbers, a.nextpostslink, a[rel=next]") != null) return true;
        for (Element link : document.select(".pagination a[href], .hpage a[href]")) {
            String text = link.text() == null ? "" : link.text().trim();
            if (text.equalsIgnoreCase("Next") || text.equals("›") || text.equals("»")) return true;
            String href = link.absUrl("href");
            Matcher m = Pattern.compile("(?:/page/|[?&]page=)([0-9]+)").matcher(href);
            while (m.find()) {
                try { if (Integer.parseInt(m.group(1)) > page) return true; } catch(Exception ignored) { }
            }
        }
        return size >= 10 && document.selectFirst(".pagination a[href*='page='], .pagination a[href*='/page/'], .hpage a[href*='page='], .hpage a[href*='/page/']") != null;
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
                } catch(Exception e) { main.post(() -> cb.onError("Data SekteDoujin gagal dibaca")); }
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

    private String chapterSeenKey(String href, String title, float index) {
        String key = href == null ? "" : href.trim().toLowerCase(Locale.ROOT);
        if (!key.isEmpty()) return key;
        return MangaChapter.formatIndex(index) + ":" + (title == null ? "" : title.trim().toLowerCase(Locale.ROOT));
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
                if ("manga".equals(type) || "manhwa".equals(type) || "manhua".equals(type) || "doujinshi".equals(type)) out.type = type;
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
        if ("doujinshi".equals(value)) return "doujinshi";
        return "";
    }

    private String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) || "complete".equals(value)) return "completed";
        if ("ongoing".equals(value) || "on-going".equals(value)) return "ongoing";
        if ("hiatus".equals(value)) return "hiatus";
        return value;
    }

    private void addInfo(ArrayList<String> out, String label, String value) {
        if (out == null || value == null || value.trim().isEmpty()) return;
        out.add(label + ": " + value.trim());
    }

    private String cleanChapterText(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        return value.equalsIgnoreCase("Select Chapter") ? "" : value;
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
                if (url != null && url.pathSize() > 0) {
                    for (int i = url.pathSize() - 1; i >= 0; i--) {
                        String seg = url.pathSegments().get(i).trim();
                        if (!seg.isEmpty()) { value = seg; break; }
                    }
                }
            } catch(Exception ignored) { }
        }
        value = value.replace(base(), "").trim();
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("chapter")) return "";
        if (lower.equals("project") || lower.equals("genres") || lower.equals("manga") || lower.equals("page")) return "";
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

    private String seriesUrl(String slug) { return base() + "/" + urlSegment(slug) + "/"; }

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
        return value.isEmpty() || value.equals("genres") || value.equals("genre") || value.equals("manga") || value.equals("luvyaa");
    }

    private ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] value : fallbackGenrePairs()) out.add(new GenreItem(value[0], value[1]));
        return out;
    }

    private String[][] fallbackGenrePairs() {
        return new String[][]{{"Action", "228"}, {"Adult", "3"}, {"Adventure", "229"}, {"Age Progression", "2273"}, {"Age Regression", "3313"}, {"Ahegao", "591"}, {"Amputee", "3078"}, {"Anal", "362"}, {"Apron", "1591"}, {"Aunt", "735"}, {"Bald", "1392"}, {"BBig Breast", "1488"}, {"Bbw", "4064"}, {"BDSM", "165"}, {"Bestiality", "255"}, {"big aas", "3923"}, {"Big Areolae", "4067"}, {"Big Ass", "1361"}, {"Big Ass Handjob", "3462"}, {"Big Braest", "1627"}, {"Big Breast", "613"}, {"Big Breast Sole Female", "1640"}, {"Big Breast Sole Male", "3496"}, {"Big Breasts", "154"}, {"Big Penis", "675"}, {"BigAss", "614"}, {"Bike Shorts", "1642"}, {"Bikini", "1370"}, {"Birth", "3060"}, {"Bisexual", "1660"}, {"BisexualStocking", "2974"}, {"Blackmail", "596"}, {"Blakmail", "3751"}, {"Blindfold", "1500"}, {"Bloomers", "1676"}, {"blow job", "3924"}, {"Blowjob", "155"}, {"Body Swap", "2194"}, {"Bodysuit", "1592"}, {"Bondage", "1467"}, {"Business Suit", "1381"}, {"Busty", "3351"}, {"Cheating", "158"}, {"Codom", "2292"}, {"Collar", "1427"}, {"College", "1836"}, {"Comedi", "1151"}, {"Comedy", "6"}, {"Condom", "1371"}, {"Cousin", "1603"}, {"Creampie", "3352"}, {"Crossdressing", "1410"}, {"Cunniligus", "3410"}, {"Cunnilingus", "1348"}, {"Dark Skin", "1362"}, {"Daughter", "603"}, {"Defloration", "1349"}, {"Demon", "274"}, {"Demon Girl", "1424"}, {"Dick Growth", "1425"}, {"Dilf", "604"}, {"Double Penetration", "363"}, {"Double Pentration", "2462"}, {"Drama", "8"}, {"Drugs", "1372"}, {"Drunk", "611"}, {"Ecchi", "98"}, {"Elf", "318"}, {"Emotionless Sex", "1552"}, {"Exhibitionisim", "2506"}, {"Exhibitionism", "1373"}, {"Exhibtionism", "3118"}, {"Eyepatch", "3476"}, {"Family", "877"}, {"Family BDSM", "1877"}, {"Fantasy", "193"}, {"Female", "2343"}, {"Females Only", "1036"}, {"Femdom", "166"}, {"Fffm foursome", "4068"}, {"Filming", "597"}, {"Fingering", "1382"}, {"Footjob", "1384"}, {"Footjob Unusual Pupils", "1661"}, {"Forced", "729"}, {"Full Color", "15"}, {"Full Colour", "3921"}, {"Furry", "1561"}, {"Futanari", "606"}, {"Game", "453"}, {"Gangbang", "732"}, {"Garter Belt", "1385"}, {"Gende Bender", "3523"}, {"Gender Bender", "232"}, {"Ghost", "737"}, {"Glasses", "1350"}, {"Gore", "290"}, {"Group", "364"}, {"Guro", "3079"}, {"Gyaru", "313"}, {"hairy", "365"}, {"Handjob", "1374"}, {"Harem", "9"}, {"Hentai", "2648"}, {"High School", "1232"}, {"Hipnotis", "408"}, {"Historical", "250"}, {"Horns", "366"}, {"Horror", "189"}, {"HouseWife", "615"}, {"Huge boobs", "4069"}, {"Huge Breast", "1363"}, {"Huge Penis", "3451"}, {"Humiliation", "1469"}, {"Impregnate", "167"}, {"Impregnation", "1351"}, {"Incest", "156"}, {"Inflation", "1548"}, {"Inseki", "1428"}, {"Inverted Nipples", "1411"}, {"Invisible", "3330"}, {"Isekai", "1010"}, {"Josei", "346"}, {"Kemomimi", "1364"}, {"Kimono", "1492"}, {"Kuudere", "4070"}, {"Lactation", "1352"}, {"Leotard", "1365"}, {"Lingerie", "1353"}, {"Loli", "1386"}, {"Lolicon", "197"}, {"Lolipai", "1474"}, {"Magic", "3139"}, {"Maid", "587"}, {"Males Only", "1412"}, {"Manhwa", "740"}, {"Mantap", "758"}, {"Martial Arts", "251"}, {"Massage", "3665"}, {"Masturbation", "1378"}, {"Mating Press", "4071"}, {"Mature", "19"}, {"Miko", "2223"}, {"MILF", "159"}, {"Milf. Netorare", "600"}, {"Military", "4049"}, {"Mind Break", "1375"}, {"Mind Control", "1408"}, {"Monster", "367"}, {"Monster Girl", "190"}, {"Mother", "491"}, {"Multi-work Series", "1354"}, {"Muscle", "1376"}, {"Muscles", "2801"}, {"Mystery", "377"}, {"Nakadashi", "150"}, {"Necrophilia", "3080"}, {"Netoare", "3518"}, {"Netorare", "423"}, {"Netorre", "2803"}, {"Niece", "1730"}, {"Nipple Fuck", "2170"}, {"Nun", "368"}, {"Nurse", "1493"}, {"Office Girl", "736"}, {"Ojousama", "4072"}, {"Old Man", "1569"}, {"Old Woman", "1837"}, {"Original", "2051"}, {"Oyakodon", "1680"}, {"Paizuri", "593"}, {"Pantyhose", "1366"}, {"Parodi", "734"}, {"Parody", "200"}, {"Possession", "2554"}, {"Pregnant", "168"}, {"Project", "1838"}, {"Prostitusi", "733"}, {"Prostitution", "1355"}, {"Psychological", "238"}, {"Pubic hair", "3353"}, {"Rape", "195"}, {"Rimjob", "1460"}, {"Robot", "277"}, {"Romance", "10"}, {"Scat", "247"}, {"School Girl", "278"}, {"School Life", "11"}, {"School Uniform", "1379"}, {"Sci-fi", "99"}, {"Scrat", "1468"}, {"Seinen", "12"}, {"Senior Girl", "1839"}, {"Sex Toys", "1356"}, {"Shemale", "1161"}, {"Shota", "1395"}, {"Shotacon", "163"}, {"Shounen", "100"}, {"Sister", "1429"}, {"Sleeping", "1464"}, {"Slice of Life", "209"}, {"Small Breast", "1648"}, {"Small Penis", "3154"}, {"Smut", "343"}, {"Snuff", "2695"}, {"Sole Female", "151"}, {"Sole Female Sole Male", "3484"}, {"Sole Male", "314"}, {"Some male", "4065"}, {"Sports", "583"}, {"Stocking", "607"}, {"Story Arc", "728"}, {"Sumata", "1646"}, {"Supernatural", "13"}, {"Sweating", "1387"}, {"Swimsuit", "1367"}, {"SwimsuitX-Ray", "1509"}, {"Tanlines", "1368"}, {"Teacher", "169"}, {"Tentacles", "1745"}, {"Threesome", "160"}, {"Tomboy", "1430"}, {"Tomgirl", "1413"}, {"Torture", "1735"}, {"Toys", "731"}, {"Tragedy", "252"}, {"Tree", "2581"}, {"Twins", "2421"}, {"Twintails", "1357"}, {"Twintils", "2147"}, {"Uncen", "730"}, {"Uncensored", "618"}, {"Unsual Pupils", "3684"}, {"Unusual Pupil", "3052"}, {"Unusual Pupils", "1358"}, {"Vanilla", "440"}, {"Virgin", "152"}, {"Virginity", "1477"}, {"WAidow", "2100"}, {"Webtoon", "16"}, {"Widow", "1484"}, {"X-Ray", "1359"}, {"Yandere", "393"}, {"Yaoi", "1414"}, {"Yuri", "248"}};
    }

    private static final class FilterParts {
        final ArrayList<String> genres = new ArrayList<>();
        String type = "";
        String status = "";
    }
}
