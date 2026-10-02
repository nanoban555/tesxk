package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
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

public class Ngomik extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_NGOMIK); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(32, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, Boolean> LIST_HAS_NEXT_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, CACHE_TTL);
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    @Override protected String sourceLabel() { return "Ngomik"; }

    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        LIST_HAS_NEXT_CACHE.clear();
        GENRE_CACHE.clear();
    }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            String url = buildListUrl(Math.max(1, page), sort, query, genre);
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) {
                Boolean cachedHasNext = LIST_HAS_NEXT_CACHE.get(url);
                cb.onSuccess(new ArrayList<>(cached), cachedHasNext != null && cachedHasNext);
                return;
            }
            getDocument(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(document);
                            boolean next = hasNextPage(document, out.size());
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            LIST_HAS_NEXT_CACHE.put(url, next);
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Ngomik gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(base() + "/genres/", new Result<Document>() {
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
        if (clean.isEmpty()) { cb.onError("Slug Ngomik kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getDocument(seriesUrl(clean), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(clean, document);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail Ngomik kosong")); return; }
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
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Ngomik gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Ngomik kosong"); return; }
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
        if (chapter == null || chapter.chapterId == null || chapter.chapterId.trim().isEmpty()) {
            chapters(clean, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) {
                    MangaChapter loaded = findCachedChapter(clean, index);
                    if (loaded != null && loaded.chapterId != null && !loaded.chapterId.trim().isEmpty()) loadPages(loaded.chapterId.trim(), key, cb);
                    else cb.onError("Chapter Ngomik tidak ditemukan");
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        loadPages(chapter.chapterId.trim(), key, cb);
    }

    private void loadPages(String chapterUrl, String key, Result<ArrayList<String>> cb) {
        getDocument(chapterUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> pages = parsePages(document);
                        if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman Ngomik kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Ngomik gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            String encoded = URLEncoder.encode(q, "UTF-8");
            if (page <= 1) return base() + "/?s=" + encoded;
            return base() + "/page/" + page + "/?s=" + encoded;
        }
        String filter = genre == null ? "" : genre.trim();
        String genreSlug = extractGenre(filter);
        if (!genreSlug.isEmpty()) {
            String url = base() + "/genres/" + urlSegment(genreSlug) + "/";
            if (page > 1) url = base() + "/genres/" + urlSegment(genreSlug) + "/page/" + page + "/";
            return url;
        }
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/manga/").newBuilder();
        String order = orderParam(sort);
        if (!order.isEmpty()) builder.addQueryParameter("order", order);
        if (page > 1) builder.addQueryParameter("page", String.valueOf(page));
        return builder.build().toString();
    }

    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements items = document.select(".listupd .bsx");
        if (items.isEmpty()) items = document.select(".bsx");
        for (Element item : items) {
            Element link = item.selectFirst("a[href*='/manga/']");
            if (link == null) continue;
            String href = link.absUrl("href");
            String slug = cleanSeriesSlug(href);
            if (slug.isEmpty() || !seen.add(slug)) continue;
            String title = firstNonEmpty(text(item, ".tt"), attr(link, "title"), attr(item.selectFirst("img"), "title"), attr(item.selectFirst("img"), "alt"));
            String cover = imageUrl(item.selectFirst("img"));
            String type = parseType(item.selectFirst(".type"));
            String latest = cleanChapterText(text(item, ".epxs"));
            MangaPost post = new MangaPost(slug, title, cover, "", "", "", "", type, latest, "").withSource(MangaSettingsManager.MANGA_SOURCE_NGOMIK, "Ngomik");
            out.add(post);
        }
        return out;
    }

    private MangaPost parseDetail(String clean, Document document) {
        String title = firstNonEmpty(text(document, "h1.entry-title"), text(document, ".entry-title"), document.title() == null ? "" : document.title().replace("– Ngomik ID", "").trim());
        String cover = imageUrl(document.selectFirst(".thumb img, article img[itemprop=image], img.wp-post-image"));
        String synopsis = text(document, ".entry-content.entry-content-single");
        if (synopsis.isEmpty()) synopsis = text(document, ".entry-content");
        String genre = joinTexts(document.select(".mgen a"));
        String status = infoValue(document, "Status");
        String type = firstNonEmpty(infoValue(document, "Type"), parseType(document.selectFirst(".type")));
        String author = firstNonEmpty(infoValue(document, "Author"), infoValue(document, "Artist"));
        MangaPost post = new MangaPost(clean, title, cover, author, status, synopsis, genre, type).withSource(MangaSettingsManager.MANGA_SOURCE_NGOMIK, "Ngomik");
        post.info = firstNonEmpty(text(document, ".alternative"), "");
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String seriesSlug, Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element item : document.select("#chapterlist li, .eplister li, .bxcl li")) {
            Element link = item.selectFirst("a[href]");
            if (link == null) continue;
            String href = link.absUrl("href");
            String title = firstNonEmpty(text(item, ".chapternum"), link.text());
            float index = parseChapterIndex(firstNonEmpty(attr(item, "data-num"), title, href));
            if (index < 0) continue;
            String key = MangaChapter.formatIndex(index);
            if (!seen.add(key)) continue;
            String date = text(item, ".chapterdate");
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, date);
            chapter.chapterId = href;
            out.add(chapter);
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("#readerarea img, .entry-content img, .chapter-content img, .reading-content img");
        if (images.isEmpty()) images = document.select("img[src*='bid-cdn'], img[data-src*='bid-cdn'], img[src*='wp-content/uploads']");
        for (Element img : images) {
            String url = imageUrl(img);
            if (!url.startsWith("http")) continue;
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains("logo") || lower.contains("avatar") || lower.contains("cover.bid-cdn.cloud")) continue;
            if (seen.add(url)) out.add(url);
        }
        return out;
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select(".taxindex a[href*='/genres/']")) {
            String href = link.absUrl("href");
            String value = cleanGenreSlug(href);
            if (value.isEmpty() || !seen.add(value)) continue;
            Element span = link.selectFirst("span");
            String title = span == null ? link.ownText().trim() : span.text().trim();
            if (title.isEmpty()) title = titleFromSlug(value);
            out.add(new GenreItem(title, value));
        }
        appendTypeFilters(out);
        return out;
    }

    private boolean hasNextPage(Document document, int size) {
        if (document.selectFirst(".pagination a.next, a.next.page-numbers") != null) return true;
        return size >= 10 && document.selectFirst(".pagination a[href*='page='], .pagination a[href*='/page/']") != null;
    }

    private void getDocument(String url, Result<Document> cb) {
        getDocument(new Request.Builder().url(url).headers(headers(url)).build(), cb);
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
                } catch(Exception e) { main.post(() -> cb.onError("Data Ngomik gagal dibaca")); }
            }
        });
    }

    private Headers headers(String url) {
        String ref = base().endsWith("/") ? base() : base() + "/";
        return new Headers.Builder()
                .set("Referer", ref)
                .set("Origin", base())
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .set("Accept-Language", "id-ID,id;q=0.7,en-US;q=0.6,en;q=0.5")
                .set("Cache-Control", "max-age=0")
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
        if (chapters == null) return null;
        for (MangaChapter chapter : chapters) if (chapter != null && Math.abs(chapter.index - index) < 0.001f) return chapter;
        return null;
    }

    private String orderParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s)) return "popular";
        if ("added".equals(s) || "new".equals(s) || "latest_added".equals(s)) return "latest";
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "title";
        if ("za".equals(s) || "z-a".equals(s) || "titlereverse".equals(s)) return "titlereverse";
        return "update";
    }

    private String extractGenre(String raw) {
        if (raw == null) return "";
        for (String part : raw.split("[|,]")) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty() || value.startsWith("type:") || value.startsWith("status:")) continue;
            if (value.startsWith("genre:")) value = value.substring(6).trim();
            return value;
        }
        return "";
    }

    private String extractType(String raw) {
        if (raw == null) return "";
        for (String part : raw.split("[|,]")) {
            String value = part == null ? "" : part.trim();
            if (value.startsWith("type:")) return value.substring(5).trim();
        }
        return "";
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(type)) return "manga";
        if ("manhwa".equals(type)) return "manhwa";
        if ("manhua".equals(type)) return "manhua";
        if ("comic".equals(type)) return "comic";
        if ("novel".equals(type)) return "novel";
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String parseType(Element element) {
        if (element == null) return "";
        String text = element.text().trim();
        if (!text.isEmpty()) return text;
        for (String cls : element.classNames()) {
            if ("type".equalsIgnoreCase(cls)) continue;
            if (cls.equalsIgnoreCase("manga") || cls.equalsIgnoreCase("manhwa") || cls.equalsIgnoreCase("manhua") || cls.equalsIgnoreCase("comic") || cls.equalsIgnoreCase("novel")) return cls;
        }
        return "";
    }

    private String infoValue(Document document, String label) {
        String wanted = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        for (Element item : document.select(".imptdt")) {
            String full = item.text().trim();
            if (full.toLowerCase(Locale.ROOT).startsWith(wanted.toLowerCase(Locale.ROOT))) {
                Element value = item.selectFirst("i, a");
                if (value != null) return value.text().trim();
                return full.replaceFirst("(?i)^" + Pattern.quote(label), "").trim();
            }
        }
        return "";
    }

    private String cleanChapterText(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile("(?i)chapter\\s+[0-9]+(?:\\.[0-9]+)?").matcher(value);
        if (matcher.find()) return matcher.group().trim();
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

    private String seriesUrl(String slug) {
        return base() + "/manga/" + urlSegment(slug) + "/";
    }

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
        return android.text.TextUtils.join(", ", values);
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

    private ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        String[][] values = {{"Action","action"},{"Adult","adult"},{"Adventure","adventure"},{"Bloody","bloody"},{"Comedy","comedy"},{"Cooking","cooking"},{"Demons","demons"},{"Drama","drama"},{"Ecchi","ecchi"},{"Fantasy","fantasy"},{"Game","game"},{"Gender Bender","gender-bender"},{"Harem","harem"},{"Historical","historical"},{"Horror","horror"},{"Isekai","isekai"},{"Josei","josei"},{"Lolicon","lolicon"},{"Mafia","mafia"},{"Magic","magic"},{"Martial Arts","martial-arts"},{"Mature","mature"},{"Mecha","mecha"},{"Medical","medical"},{"Mystery","mystery"},{"Overpowered","overpowered"},{"Psychological","psychological"},{"Reincarnation","reincarnation"},{"Returner","returner"},{"Revenge","revenge"},{"Romance","romance"},{"School","school"},{"School Life","school-life"},{"Sci-fi","sci-fi"},{"Seinen","seinen"},{"Shotacon","shotacon"},{"Shoujo","shoujo"},{"Shoujo Ai","shoujo-ai"},{"Shounen","shounen"},{"Shounen Ai","shounen-ai"},{"Slice of Life","slice-of-life"},{"Smut","smut"},{"Sports","sports"},{"Superhero","superhero"},{"Supernatural","supernatural"},{"Thriller","thriller"},{"Tragedy","tragedy"},{"Yuri","yuri"}};
        for (String[] value : values) out.add(new GenreItem(value[0], value[1]));
        appendTypeFilters(out);
        return out;
    }

    private void appendTypeFilters(ArrayList<GenreItem> out) {
        if (out == null) return;
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
    }
}
