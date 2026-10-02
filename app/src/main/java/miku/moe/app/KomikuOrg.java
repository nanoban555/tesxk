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

public class KomikuOrg extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG); }
    private static final String API_BASE = "https://api.komiku.org";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, String> PAGE_REFERER_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Komiku Org"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            String url = buildListUrl(page, sort, query, genre);
            String key = url;
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 1); return; }
            getDocument(url, isApiUrl(url), new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            String fallbackType = typeFromSort(sort);
                            for (Element element : document.select(".bge")) {
                                MangaPost post = parseListPost(element, fallbackType);
                                String unique = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
                                if (!unique.isEmpty() && seen.add(unique)) out.add(post);
                            }
                            if (out.isEmpty()) {
                                for (Element element : document.select(".ls2, article")) {
                                    MangaPost post = parseCompactPost(element, fallbackType);
                                    String unique = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
                                    if (!unique.isEmpty() && seen.add(unique)) out.add(post);
                                }
                            }
                            boolean next = hasNextPage(document);
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Komiku Org gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(base() + "/?post_type=manga&s=maou", false, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(document);
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Genre Komiku Org gagal dibaca")); }
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
        for (MangaPost p : list) if (p != null && p.slug != null && !p.slug.trim().isEmpty() && ((loadChapter && (p.latestChapter == null || p.latestChapter.trim().isEmpty())) || (loadType && (p.typeLabel == null || p.typeLabel.trim().isEmpty())))) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || p.slug == null || p.slug.trim().isEmpty() || !((loadChapter && (p.latestChapter == null || p.latestChapter.trim().isEmpty())) || (loadType && (p.typeLabel == null || p.typeLabel.trim().isEmpty())))) continue;
            detail(p.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        if (loadChapter && (p.latestChapter == null || p.latestChapter.trim().isEmpty())) p.latestChapter = detail.latestChapter == null ? "" : detail.latestChapter;
                        if (loadType && (p.typeLabel == null || p.typeLabel.trim().isEmpty())) p.typeLabel = detail.typeLabel == null ? "" : detail.getTypeLabel();
                        if (p.genre == null || p.genre.trim().isEmpty()) p.genre = detail.genre == null ? "" : detail.genre;
                        if (p.status == null || p.status.trim().isEmpty()) p.status = detail.status == null ? "" : detail.status;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getDocument(toAbsolute(slug), false, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(slug, document);
                        ArrayList<MangaChapter> chapters = parseChapters(document);
                        post.totalChapters = chapters.size();
                        if ((post.latestChapter == null || post.latestChapter.trim().isEmpty()) && !chapters.isEmpty()) post.latestChapter = chapters.get(0).title;
                        DETAIL_CACHE.put(slug, post);
                        CHAPTER_CACHE.put(slug, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Komiku Org gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(toAbsolute(slug), false, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = parseChapters(document);
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter Komiku Org gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = slug + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) {
            String cachedReferer = PAGE_REFERER_CACHE.get(pageKey);
            if (cachedReferer != null && !cachedReferer.isEmpty()) {
                for (String pageUrl : cached) MangaImageLoader.registerImageReferer(pageUrl, cachedReferer);
            }
            cb.onSuccess(new ArrayList<>(cached), false);
            return;
        }
        chapters(slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = null;
                if (chapters != null) for (MangaChapter ch : chapters) if (Math.abs(ch.index - index) < 0.0001f) { chapter = ch; break; }
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter Komiku Org tidak ditemukan"); return; }
                String chapterUrl = toAbsolute(chapter.slug);
                getDocument(chapterUrl, false, new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                for (String pageUrl : out) MangaImageLoader.registerImageReferer(pageUrl, chapterUrl);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                PAGE_REFERER_CACHE.put(pageKey, chapterUrl);
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Komiku Org gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String buildListUrl(int page, String sort, String query, String genre) {
        int safePage = Math.max(1, page);
        ParsedFilter filter = parseFilter(genre);
        String typeFilter = normalizeTypeParam(filter.type);
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(s) || "manhwa".equals(s) || "manhua".equals(s)) typeFilter = s;

        String path = API_BASE + "/manga" + (safePage > 1 ? "/page/" + safePage : "");
        HttpUrl parsed = HttpUrl.parse(path);
        if (parsed == null) return path;
        HttpUrl.Builder builder = parsed.newBuilder();
        if (query != null && !query.trim().isEmpty()) builder.addQueryParameter("s", query.trim());
        if (!typeFilter.isEmpty()) builder.addQueryParameter("tipe", typeFilter);
        if (!filter.genre.isEmpty()) builder.addQueryParameter("genre", filter.genre);

        String order = "modified";
        if ("title_latest".equals(s) || "judul_terbaru".equals(s) || "date".equals(s)) order = "date";
        else if ("popular".equals(s) || "popularity".equals(s) || "rating".equals(s)) order = "meta_value_num";
        else if ("random".equals(s) || "rand".equals(s)) order = "rand";
        builder.addQueryParameter("orderby", order);
        return builder.build().toString();
    }

    private static ParsedFilter parseFilter(String raw) {
        ParsedFilter out = new ParsedFilter();
        if (raw == null || raw.trim().isEmpty()) return out;
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            if (part == null) continue;
            String value = part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) out.type = lower.substring(5).trim();
            else if (out.genre.isEmpty()) out.genre = normalizeGenreValue(value);
        }
        return out;
    }

    private static String normalizeTypeParam(String type) {
        if (type == null) return "";
        String value = type.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("type:")) value = value.substring(5).trim();
        if ("manga".equals(value) || "manhwa".equals(value) || "manhua".equals(value)) return value;
        return "";
    }

    private static class ParsedFilter {
        String genre = "";
        String type = "";
    }

    private MangaPost parseListPost(Element element, String fallbackType) {
        Element link = element.selectFirst("a:has(h3), .bgei > a[href], .kan h3 a[href], a[href*=\"/manga/\"]");
        String slug = link == null ? "" : withoutDomain(link.attr("abs:href"));
        String title = text(element.selectFirst("h3, .kan h3"));
        if (title.isEmpty() && link != null) title = link.text().trim();
        String cover = image(element.selectFirst(".bgei img, img.sd, img"));
        String type = text(element.selectFirst(".tpe1_inf b"));
        String genre = textWithoutFirstBold(element.selectFirst(".tpe1_inf"));
        if (type.isEmpty() && fallbackType != null && !fallbackType.trim().isEmpty()) type = fallbackType.trim();
        MangaPost post = new MangaPost(slug, title, cover, "", "", "", genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG, "Komiku Org");
        if (type.isEmpty()) post.typeLabel = "";
        for (Element item : element.select(".new1")) {
            String label = item.text().toLowerCase(Locale.ROOT);
            if (!label.contains("terbaru")) continue;
            String latest = text(item.selectFirst("a span:last-child"));
            if (latest.isEmpty()) latest = item.text().replaceFirst("(?i)^terbaru:\\s*", "").trim();
            post.latestChapter = latest;
            break;
        }
        if (post.latestChapter == null || post.latestChapter.trim().isEmpty()) post.latestChapter = text(element.selectFirst(".up"));
        String info = text(element.selectFirst(".judul2"));
        if (!info.isEmpty()) post.latestChapterDate = info;
        return post;
    }

    private MangaPost parseCompactPost(Element element, String fallbackType) {
        Element link = element.selectFirst("h3 a[href], a[href*=\"/manga/\"]");
        String slug = link == null ? "" : withoutDomain(link.attr("abs:href"));
        String title = link == null ? "" : link.text().trim();
        String cover = image(element.selectFirst("img"));
        String latest = text(element.selectFirst(".ls2l"));
        String genre = text(element.selectFirst(".ls2t"));
        MangaPost post = new MangaPost(slug, title, cover, "", "", "", genre, fallbackType == null ? "" : fallbackType, latest, "").withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG, "Komiku Org");
        if (post.typeLabel == null || post.typeLabel.trim().isEmpty()) post.typeLabel = "";
        return post;
    }

    private MangaPost parseDetail(String slug, Document document) {
        String title = text(document.selectFirst("#Judul h1 [itemprop=name], #Judul h1 span[itemprop=name], h1[itemprop=name], h1.entry-title"));
        if (title.isEmpty()) title = tableValue(document, "Judul");
        if (title.isEmpty()) { Element metaTitle = document.selectFirst("meta[property=og:title]"); title = metaTitle == null ? "" : metaTitle.attr("content").replaceFirst("(?i)^komik\\s+", "").trim(); }
        String author = firstNonEmpty(tableValue(document, "Pengarang"), firstNonEmpty(tableValue(document, "Komikus"), tableValue(document, "Author")));
        String status = tableValue(document, "Status");
        String type = tableValue(document, "Tipe");
        String genre = joinText(document.select("ul.genre li.genre a span, #Informasi li.genre a, #Informasi .genre a"));
        String synopsis = text(document.selectFirst("#Sinopsis > p, #Informasi p.desc, p.desc"));
        String cover = image(document.selectFirst("div.ims > img, #Informasi .ims img, .ims img, meta[property=og:image]"));
        MangaPost post = new MangaPost(slug, title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG, "Komiku Org");
        if (type.isEmpty()) post.typeLabel = "";
        Element latest = document.selectFirst(".linkbutt .new1 a[title*=Terbaru] span:last-child");
        if (latest != null) post.latestChapter = text(latest);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element row : document.select("#Daftar_Chapter tr")) {
            Element link = row.selectFirst(".judulseries a[href]");
            if (link == null) continue;
            String url = withoutDomain(link.attr("abs:href"));
            String name = text(link.selectFirst("span[itemprop=name], b"));
            if (name.isEmpty()) name = text(link);
            float index = parseChapterIndex(name, out.size() + 1);
            String date = text(row.selectFirst(".tanggalseries"));
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, prettyRelativeDate(date)));
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element img : document.select("#Baca_Komik img.klazy.ww, #Baca_Komik img.ww, #Baca_Komik img[src], #Baca_Komik img[data-src], #Baca_Komik img[data-lazy-src], #Baca_Komik img[data-original]")) addPage(out, seen, image(img));
        if (out.isEmpty()) for (Element img : document.select("#Baca_Komik img, article img[src*=upload], main img[src*=upload]")) addPage(out, seen, image(img));
        return out;
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : document.select(".daftar ul.genre a[href*=\"/genre/\"], ul.genre a[href*=\"/genre/\"]")) {
            String title = text(link);
            String value = genreSlugFromUrl(link.attr("abs:href"));
            if (title.isEmpty() || value.isEmpty() || !seen.add(value)) continue;
            out.add(new GenreItem(title, value));
        }
        if (out.isEmpty()) addFallbackGenres(out, seen);
        return out;
    }

    private static void addFallbackGenres(ArrayList<GenreItem> out, LinkedHashSet<String> seen) {
        String[][] fallback = {
            {"Action", "action"}, {"Adventure", "adventure"}, {"Comedy", "comedy"}, {"Drama", "drama"},
            {"Fantasy", "fantasy"}, {"Harem", "harem"}, {"Historical", "historical"}, {"Horror", "horror"},
            {"Isekai", "isekai"}, {"Magic", "magic"}, {"Manhua", "manhua"}, {"Manhwa", "manhwa"},
            {"Martial Arts", "martial-arts"}, {"Murim", "murim"}, {"Mystery", "mystery"},
            {"Reincarnation", "reincarnation"}, {"Romance", "romance"}, {"School Life", "school-life"},
            {"Seinen", "seinen"}, {"Shounen", "shounen"}, {"Slice of Life", "slice-of-life"},
            {"Supernatural", "supernatural"}, {"System", "system"}, {"Webtoon", "webtoon"}
        };
        for (String[] item : fallback) {
            if (seen.add(item[1])) out.add(new GenreItem(item[0], item[1]));
        }
    }

    private void getDocument(String url, boolean api, Result<Document> cb) {
        Request.Builder builder = new Request.Builder().url(url).header("Accept", api ? "*/*" : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8").header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
        builder.header("Referer", base() + "/");
        if (api) builder.header("Origin", base());
        CloudflareHelper.enqueue(client, builder.build(), sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                try { Document document = Jsoup.parse(body, url); MAIN.post(() -> cb.onSuccess(document, false)); }
                catch(Exception e) { MAIN.post(() -> cb.onError("Data Komiku Org gagal dibaca")); }
            }
        });
    }

    private static boolean hasNextPage(Document document) {
        if (document == null) return false;
        if (!document.select("span[hx-get], a.next, a.next.page-numbers, .pagination a[href*=page], .page-numbers a[href*=page]").isEmpty()) return true;
        return !document.select(".hxloading").isEmpty();
    }

    private static boolean isApiUrl(String url) { return url != null && url.startsWith(API_BASE); }

    private static String typeFromSort(String sort) {
        if (sort == null) return "";
        String value = sort.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(value) || "manhwa".equals(value) || "manhua".equals(value)) return value;
        return "";
    }

    private static void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String url) {
        if (url == null) return;
        String value = url.trim();
        if (!value.startsWith("http")) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("/asset/img") || lower.contains("lazy.jpg") || lower.contains("iklan") || lower.contains("google") || lower.contains("gravatar")) return;
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
        String[] attrs = {"abs:data-src", "abs:data-lazy-src", "abs:data-original", "abs:data-ki-src", "abs:src", "content", "data-src", "data-lazy-src", "data-original", "data-ki-src", "src", "abs:srcset", "srcset"};
        for (String attr : attrs) {
            String v = element.attr(attr).trim();
            if (!v.isEmpty()) {
                if (attr.endsWith("srcset")) v = firstSrcSetUrl(v);
                if (v.startsWith("//")) return "https:" + v;
                if (v.startsWith("/")) return base() + v;
                return v;
            }
        }
        return "";
    }

    private static String firstSrcSetUrl(String value) {
        if (value == null) return "";
        String first = value.split(",")[0].trim();
        int space = first.indexOf(' ');
        return space >= 0 ? first.substring(0, space).trim() : first;
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
        if (document == null || key == null) return "";
        String cleanKey = key.trim().replace(":", "").toLowerCase(Locale.ROOT);
        for (Element row : document.select(".inftable tr")) {
            Elements cells = row.select("td");
            if (cells.size() < 2) continue;
            String label = text(cells.get(0)).replace(":", "").trim().toLowerCase(Locale.ROOT);
            if (label.equals(cleanKey)) return text(cells.get(1));
        }
        return "";
    }

    private static String textWithoutFirstBold(Element element) {
        if (element == null) return "";
        Element clone = element.clone();
        Element bold = clone.selectFirst("b");
        if (bold != null) bold.remove();
        return clone.text().trim();
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

    private static String genreSlugFromUrl(String url) {
        if (url == null) return "";
        String value = url.trim();
        try {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) value = parsed.encodedPath();
        } catch(Exception ignored) { }
        Matcher matcher = Pattern.compile("/genre/([^/]+)/?").matcher(value);
        if (matcher.find()) return matcher.group(1).trim();
        return normalizeGenreValue(value);
    }

    private static String normalizeGenreValue(String genre) {
        if (genre == null) return "";
        String value = genre.trim();
        if (value.isEmpty()) return "";
        value = value.toLowerCase(Locale.ROOT);
        if (value.startsWith("/genre/")) value = value.substring(7);
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        value = value.replaceAll("[^0-9a-z]+", "-");
        value = value.replaceAll("^-+", "").replaceAll("-+$", "");
        return value;
    }
}
