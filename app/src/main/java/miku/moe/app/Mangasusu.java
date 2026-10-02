package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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

public class Mangasusu extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_MANGASUSU); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, Boolean> LIST_HAS_NEXT_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        LIST_HAS_NEXT_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Mangasusu"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            HttpUrl url = buildListUrl(page, sort, query, genre);
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) {
                Boolean cachedHasNext = LIST_HAS_NEXT_CACHE.get(key);
                cb.onSuccess(new ArrayList<>(cached), cachedHasNext != null && cachedHasNext);
                return;
            }
            getDocument(url.toString(), new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            String requestType = extractTypeFilter(genre);
                            for (Element element : document.select(".listupd .bs")) {
                                MangaPost post = parseListPost(element, requestType);
                                String unique = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
                                if (!unique.isEmpty() && seen.add(unique)) out.add(post);
                            }
                            boolean next = !document.select("a.next.page-numbers, a.r[href*=page]").isEmpty();
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            LIST_HAS_NEXT_CACHE.put(key, next);
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Mangasusu gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(base() + "/komik/", new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(document);
                        appendTypeFilters(out);
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Genre Mangasusu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (needsListEnrichment(p)) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (!needsListEnrichment(p)) continue;
            boolean needsDetail = loadType && needsDetailEnrichment(p);
            boolean needsChapter = loadChapter && (p.latestChapter == null || p.latestChapter.trim().isEmpty());
            if (needsDetail) {
                detail(p.slug, new Result<MangaPost>() {
                    @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                        if (detail != null) {
                            if (loadType && detail.typeLabel != null && !detail.typeLabel.trim().isEmpty()) p.typeLabel = detail.getTypeLabel();
                            if (detail.genre != null && !detail.genre.trim().isEmpty()) p.genre = detail.genre;
                            if (detail.status != null && !detail.status.trim().isEmpty()) p.status = detail.status;
                            if (detail.author != null && !detail.author.trim().isEmpty()) p.author = detail.author;
                        }
                        if (needsChapter) enrichChapter(p, remaining, done); else finishEnrichment(remaining, done);
                    }
                    @Override public void onError(String message) { if (needsChapter) enrichChapter(p, remaining, done); else finishEnrichment(remaining, done); }
                });
            } else if (needsChapter) {
                enrichChapter(p, remaining, done);
            }
        }
    }

    private boolean needsListEnrichment(MangaPost p) {
        if (p == null || p.slug == null || p.slug.trim().isEmpty()) return false;
        boolean missingChapter = MangaSettingsManager.shouldLoadLatestChapterLabel() && (p.latestChapter == null || p.latestChapter.trim().isEmpty());
        boolean missingType = MangaSettingsManager.shouldLoadTypeLabel() && needsDetailEnrichment(p);
        return missingChapter || missingType;
    }

    private boolean needsDetailEnrichment(MangaPost p) {
        return p != null && ((p.typeLabel == null || p.typeLabel.trim().isEmpty()) || (p.genre == null || p.genre.trim().isEmpty()) || (p.status == null || p.status.trim().isEmpty()));
    }

    private void enrichChapter(MangaPost p, java.util.concurrent.atomic.AtomicInteger remaining, Runnable done) {
        chapters(p.slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (chapters != null && !chapters.isEmpty()) {
                    MangaChapter newest = chapters.get(0);
                    for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                    p.latestChapter = newest.title == null || newest.title.trim().isEmpty() ? "Chapter " + MangaChapter.formatIndex(newest.index) : newest.title;
                    p.latestChapterDate = newest.date == null ? "" : newest.date;
                }
                finishEnrichment(remaining, done);
            }
            @Override public void onError(String message) { finishEnrichment(remaining, done); }
        });
    }

    private void finishEnrichment(java.util.concurrent.atomic.AtomicInteger remaining, Runnable done) {
        if (remaining.decrementAndGet() <= 0 && done != null) done.run();
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getDocument(toAbsolute(slug), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(slug, document);
                        ArrayList<MangaChapter> chapters = parseChapters(document);
                        post.totalChapters = chapters.size();
                        DETAIL_CACHE.put(slug, post);
                        CHAPTER_CACHE.put(slug, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Mangasusu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(toAbsolute(slug), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = parseChapters(document);
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter Mangasusu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = slug + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = null;
                if (chapters != null) for (MangaChapter ch : chapters) if (Math.abs(ch.index - index) < 0.0001f) { chapter = ch; break; }
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter Mangasusu tidak ditemukan"); return; }
                getDocument(toAbsolute(chapter.slug), new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Mangasusu gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private HttpUrl buildListUrl(int page, String sort, String query, String genre) throws Exception {
        int safePage = Math.max(1, page);
        boolean searching = query != null && !query.trim().isEmpty();
        String genreId = extractGenreFilter(genre);
        String typeFilter = extractTypeFilter(genre);
        boolean filteringGenre = !genreId.isEmpty();
        boolean filteringType = !typeFilter.isEmpty();
        String path = searching && safePage > 1 ? base() + "/page/" + safePage + "/" : base() + "/komik/";
        HttpUrl.Builder builder = HttpUrl.parse(path).newBuilder();
        if (searching) {
            builder.addQueryParameter("s", query.trim());
            return builder.build();
        }
        if (safePage > 1) builder.addQueryParameter("page", String.valueOf(safePage));
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String order = "update";
        if ("popular".equals(s) || "popularity".equals(s)) order = "popular";
        else if ("added".equals(s) || "latest_added".equals(s) || "new".equals(s)) order = "latest";
        if (filteringGenre || filteringType) {
            if (filteringGenre) builder.addQueryParameter("genre[]", genreId);
            builder.addQueryParameter("status", "");
            builder.addQueryParameter("type", typeFilter);
            builder.addQueryParameter("order", filteringGenre ? "" : order);
            return builder.build();
        }
        builder.addQueryParameter("order", order);
        return builder.build();
    }

    private MangaPost parseListPost(Element element, String fallbackType) {
        Element link = element.selectFirst(".bsx > a, a");
        String slug = link == null ? "" : withoutDomain(link.attr("abs:href"));
        String title = text(element.selectFirst(".tt"));
        if (title.isEmpty() && link != null) title = link.attr("title").trim();
        String cover = image(element.selectFirst(".limit img"));
        String type = parseType(element.selectFirst(".limit .type"));
        if (type.isEmpty() && fallbackType != null && !fallbackType.trim().isEmpty()) type = fallbackType.trim();
        MangaPost post = new MangaPost(slug, title, cover, "", "", "", "", type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_MANGASUSU, "Mangasusu");
        if (type.isEmpty()) post.typeLabel = "";
        String latest = text(element.selectFirst(".epxs"));
        if (!latest.isEmpty()) post.latestChapter = latest;
        return post;
    }

    private MangaPost parseDetail(String slug, Document document) {
        String title = text(document.selectFirst(".seriestuhead h1.entry-title, h1.entry-title, h1"));
        String author = tableValue(document, "Author");
        String status = firstNonEmpty(tableValue(document, "Status"), MangaStatusParser.fromInfoRows(document));
        String type = tableValue(document, "Type");
        String genre = joinText(document.select(".seriestugenre a"));
        String synopsis = text(document.selectFirst(".seriestuhead .entry-content-single p, .entry-content-single p"));
        String marker = "bercerita tentang ";
        int pos = synopsis.toLowerCase(Locale.ROOT).indexOf(marker);
        if (pos >= 0) synopsis = synopsis.substring(pos + marker.length()).trim();
        String cover = image(document.selectFirst(".seriestucont .thumb img, .thumb img"));
        MangaPost post = new MangaPost(slug, title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_MANGASUSU, "Mangasusu");
        if (type.isEmpty()) post.typeLabel = "";
        Element latest = document.selectFirst(".epcurlast");
        if (latest != null) post.latestChapter = text(latest);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : document.select(".eplister li")) {
            Element urlElement = element.selectFirst(".eph-num a, a");
            if (urlElement == null) continue;
            String url = withoutDomain(urlElement.attr("abs:href"));
            String name = text(urlElement.selectFirst(".chapternum"));
            if (name.isEmpty()) name = text(urlElement);
            float index = parseChapterIndex(firstNonEmpty(element.attr("data-num"), name), out.size() + 1);
            String date = text(urlElement.selectFirst(".chapterdate"));
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, prettyRelativeDate(date)));
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String html = document.outerHtml();
        Matcher matcher = Pattern.compile("\\\"images\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(html);
        while (matcher.find()) {
            Matcher imageMatcher = Pattern.compile("\\\"(https?:\\\\/\\\\/[^\\\"]+)\\\"").matcher(matcher.group(1));
            while (imageMatcher.find()) addPage(out, seen, imageMatcher.group(1).replace("\\/", "/"));
        }
        if (out.isEmpty()) {
            Elements images = document.select("#readerarea img, .readerarea img, .chapterbody img, .entry-content-single img");
            for (Element img : images) addPage(out, seen, image(img));
        }
        return out;
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element input : document.select("input[name=genre[]]")) {
            String value = input.attr("value").trim();
            if (value.isEmpty() || !seen.add(value)) continue;
            String label = "";
            String id = input.attr("id");
            if (!id.isEmpty()) label = text(document.selectFirst("label[for=" + id + "]"));
            if (label.isEmpty()) label = text(input.parent());
            if (!label.isEmpty()) out.add(new GenreItem(label, value));
        }
        return out;
    }

    private void getDocument(String url, Result<Document> cb) {
        Request req = new Request.Builder().url(url).header("Referer", base() + "/").header("Origin", base()).header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8").header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                try { Document document = Jsoup.parse(body, url); MAIN.post(() -> cb.onSuccess(document, false)); }
                catch(Exception e) { MAIN.post(() -> cb.onError("Data Mangasusu gagal dibaca")); }
            }
        });
    }

    private static void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String url) {
        if (url == null) return;
        String value = url.trim();
        if (!value.startsWith("http")) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("readerarea.svg") || lower.contains("/iklan") || lower.contains("bacalightnovel") || lower.contains("/plus.") || lower.contains("/diamond.")) return;
        if (seen.add(value)) out.add(value);
    }

    private static String toAbsolute(String url) {
        if (url == null || url.trim().isEmpty()) return base();
        String value = url.trim();
        if (value.startsWith("http")) return value;
        if (!value.startsWith("/")) value = "/" + value;
        return base() + value;
    }

    private static String withoutDomain(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.isEmpty()) return "";
        try {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) {
                String path = parsed.encodedPath();
                String query = parsed.encodedQuery();
                return query == null || query.isEmpty() ? path : path + "?" + query;
            }
        } catch(Exception ignored) { }
        return value;
    }

    private static String image(Element element) {
        if (element == null) return "";
        String[] attrs = {"abs:src", "abs:data-src", "abs:data-lazy-src", "src", "data-src", "data-lazy-src"};
        for (String attr : attrs) {
            String v = element.attr(attr).trim();
            if (!v.isEmpty()) {
                if (v.startsWith("//")) return "https:" + v;
                if (v.startsWith("/")) return base() + v;
                return v;
            }
        }
        return "";
    }

    private static String text(Element element) { return element == null ? "" : element.text().trim(); }

    private static String joinText(Elements elements) {
        StringBuilder sb = new StringBuilder();
        for (Element e : elements) {
            String t = text(e);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(t);
        }
        return sb.toString();
    }

    private static String tableValue(Document document, String key) {
        for (Element row : document.select(".infotable tr")) {
            Elements cells = row.select("td");
            if (cells.size() >= 2 && text(cells.get(0)).equalsIgnoreCase(key)) return text(cells.get(1));
        }
        return "";
    }

    private static String parseType(Element element) {
        if (element == null) return "";
        String text = text(element);
        if (!text.isEmpty()) return text;
        for (String cls : element.classNames()) if (!"type".equalsIgnoreCase(cls)) return cls;
        return "";
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b == null ? "" : b.trim();
    }

    private static float parseChapterIndex(String name, int fallback) {
        if (name == null) return fallback;
        Matcher chapterMatcher = Pattern.compile("(?i)chapter\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(name);
        if (chapterMatcher.find()) {
            try { return Float.parseFloat(chapterMatcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
        }
        Matcher numberMatcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)").matcher(name);
        if (numberMatcher.find()) {
            try { return Float.parseFloat(numberMatcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
        }
        return fallback;
    }

    private static String prettyRelativeDate(String date) {
        if (date == null || date.trim().isEmpty()) return "";
        String value = date.trim();
        try {
            if (value.contains("yang lalu")) {
                int amount = Integer.parseInt(value.substring(0, value.indexOf(' ')).trim());
                Calendar calendar = Calendar.getInstance();
                String lower = value.toLowerCase(Locale.ROOT);
                if (lower.contains("detik")) calendar.add(Calendar.SECOND, -amount);
                else if (lower.contains("menit")) calendar.add(Calendar.MINUTE, -amount);
                else if (lower.contains("jam")) calendar.add(Calendar.HOUR_OF_DAY, -amount);
                else if (lower.contains("hari")) calendar.add(Calendar.DATE, -amount);
                else if (lower.contains("minggu")) calendar.add(Calendar.DATE, -amount * 7);
                else if (lower.contains("bulan")) calendar.add(Calendar.MONTH, -amount);
                else if (lower.contains("tahun")) calendar.add(Calendar.YEAR, -amount);
                return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(calendar.getTime());
            }
        } catch(Exception ignored) { }
        return value;
    }

    private static String extractGenreFilter(String genre) {
        if (genre == null) return "";
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith("type:")) continue;
            return normalizeGenreValue(value);
        }
        return "";
    }

    private static String extractTypeFilter(String genre) {
        if (genre == null) return "";
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.toLowerCase(Locale.ROOT).startsWith("type:")) continue;
            value = value.substring(value.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT);
            if ("manga".equals(value) || "manhwa".equals(value) || "manhua".equals(value)) return value;
        }
        return "";
    }

    private static void appendTypeFilters(ArrayList<GenreItem> out) {
        if (out == null) return;
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
    }

    private static String normalizeGenreValue(String genre) {
        if (genre == null) return "";
        String value = genre.trim();
        if (value.isEmpty()) return "";
        if (value.matches("[0-9]+")) return value;
        value = value.toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^0-9a-z]+", "-");
        value = value.replaceAll("^-+", "").replaceAll("-+$", "");
        return value;
    }
}
