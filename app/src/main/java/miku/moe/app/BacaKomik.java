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

public class BacaKomik extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://bacakomik.my";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_BACAKOMIK); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
    }

    private static final ArrayList<GenreItem> GENRES = new ArrayList<>();
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "BacaKomik"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            HttpUrl url = buildListUrl(page, sort, query, genre);
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 1); return; }
            getDocument(url.toString(), new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            for (Element element : document.select("div.animepost")) {
                                MangaPost post = parseListPost(element);
                                String unique = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
                                if (!unique.isEmpty() && seen.add(unique)) out.add(post);
                            }
                            boolean next = !document.select("a.next.page-numbers").isEmpty();
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar BacaKomik gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        synchronized (GENRES) {
            if (GENRES.isEmpty()) fillGenres();
            cb.onSuccess(new ArrayList<>(GENRES), false);
        }
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (p != null && (p.latestChapter == null || p.latestChapter.trim().isEmpty()) && p.slug != null && !p.slug.isEmpty()) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || (p.latestChapter != null && !p.latestChapter.trim().isEmpty()) || p.slug == null || p.slug.isEmpty()) continue;
            chapters(p.slug, new Result<ArrayList<MangaChapter>>() {
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
            });
        }
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
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail BacaKomik gagal dibaca")); }
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
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter BacaKomik gagal dibaca")); }
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
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter BacaKomik tidak ditemukan"); return; }
                getDocument(toAbsolute(chapter.slug), new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman BacaKomik gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private HttpUrl buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String path = Math.max(1, page) > 1 ? base() + "/daftar-komik/page/" + Math.max(1, page) + "/" : base() + "/daftar-komik/";
        HttpUrl.Builder builder = HttpUrl.parse(path).newBuilder();
        if (query != null && !query.trim().isEmpty()) builder.addQueryParameter("title", query.trim());
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s)) builder.addQueryParameter("order", "popular");
        else if ("latest".equals(s) || "update".equals(s)) builder.addQueryParameter("order", "update");
        addGenreParameters(builder, genre);
        return builder.build();
    }

    private void addGenreParameters(HttpUrl.Builder builder, String genre) {
        if (builder == null || genre == null) return;
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) value = value.substring(5).trim();
            String genreId = normalizeGenreValue(value);
            if (!genreId.isEmpty()) builder.addQueryParameter("genre[]", genreId);
        }
    }

    private MangaPost parseListPost(Element element) {
        Element link = element.selectFirst("div.animposx > a, a");
        String slug = link == null ? "" : withoutDomain(link.attr("abs:href"));
        String title = text(element.selectFirst(".animposx .tt h4, h4, .tt"));
        String cover = image(element.selectFirst("div.limit img, img"));
        String rawType = firstNonEmpty(text(element.selectFirst(".type, .limit .type, .typeflag, .colored")), inferTypeFromText(element.text()));
        MangaPost post = new MangaPost(slug, title, cover, "", "", "", rawType, rawType, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_BACAKOMIK, "BacaKomik");
        String latest = text(element.selectFirst(".lsch a, .lsch, .epxs, .chapter"));
        if (!latest.isEmpty()) post.latestChapter = latest;
        return post;
    }

    private MangaPost parseDetail(String slug, Document document) {
        Element info = document.selectFirst("div.infoanime");
        String title = text(document.selectFirst("#breadcrumbs li:last-child span"));
        if (title.isEmpty()) title = text(document.selectFirst("h1, .entry-title"));
        String author = cleanInfo(document.select(".infox .spe span:contains(Author)").text(), "Author");
        String status = cleanInfo(document.select(".infox .spe span:contains(Status)").text(), "Status");
        String type = cleanInfo(document.select(".infox .spe span:contains(Jenis Komik)").text(), "Jenis Komik");
        String genre = info == null ? "" : joinText(info.select(".infox > .genre-info > a, .infox .spe span:contains(Jenis Komik) a"));
        String synopsis = text(document.selectFirst("div.desc > .entry-content.entry-content-single p, div.desc p, .entry-content-single p"));
        String marker = "bercerita tentang ";
        int pos = synopsis.toLowerCase(Locale.ROOT).indexOf(marker);
        if (pos >= 0) synopsis = synopsis.substring(pos + marker.length()).trim();
        String cover = image(document.selectFirst(".thumb > img:nth-child(1), .thumb img"));
        return new MangaPost(slug, title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_BACAKOMIK, "BacaKomik");
    }

    private ArrayList<MangaChapter> parseChapters(Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : document.select("#chapter_list li")) {
            Element urlElement = element.selectFirst(".lchx a, a");
            if (urlElement == null) continue;
            String url = withoutDomain(urlElement.attr("abs:href"));
            String name = text(urlElement);
            float index = parseChapterIndex(name, out.size() + 1);
            String date = text(element.selectFirst(".dt a, .dt"));
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, prettyRelativeDate(date)));
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("div:has(>img[alt*=Chapter]) img, .entry-content img, .chapter-content img, article img");
        for (Element img : images) {
            if (img.parent() != null && "noscript".equalsIgnoreCase(img.parent().tagName())) continue;
            String url = image(img);
            String fallback = img.attr("onError");
            if (!fallback.isEmpty() && fallback.contains("src='")) url = fallback.substring(fallback.indexOf("src='") + 5).split("';", 2)[0];
            if (url.startsWith("http") && seen.add(url)) out.add(url);
        }
        return out;
    }

    private void getDocument(String url, Result<Document> cb) {
        Request req = new Request.Builder().url(url).header("Referer", base() + "/").header("Origin", base()).header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").header("Accept-Language", "id,en-US;q=0.9").header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                try { Document document = Jsoup.parse(body, url); MAIN.post(() -> cb.onSuccess(document, false)); }
                catch(Exception e) { MAIN.post(() -> cb.onError("Data BacaKomik gagal dibaca")); }
            }
        });
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
        return value.replace(base(), "");
    }

    private static String image(Element element) {
        if (element == null) return "";
        String[] attrs = {"abs:data-lazy-src", "abs:data-src", "abs:src", "data-lazy-src", "data-src", "src"};
        for (String attr : attrs) {
            String value = element.attr(attr);
            if (value != null && value.trim().startsWith("http")) return value.trim();
        }
        return "";
    }

    private static String text(Element element) { return element == null ? "" : element.text().trim().replaceAll("\\s+", " "); }

    private static String joinText(Elements elements) {
        ArrayList<String> values = new ArrayList<>();
        for (Element element : elements) {
            String value = text(element);
            if (!value.isEmpty()) values.add(value);
        }
        return android.text.TextUtils.join(", ", values);
    }

    private static String cleanInfo(String raw, String label) {
        if (raw == null) return "";
        return raw.replaceFirst("(?i)^\\s*" + Pattern.quote(label) + "\\s*:?\\s*", "").trim();
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

    private static String inferTypeFromText(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("manhwa")) return "Manhwa";
        if (lower.contains("manhua")) return "Manhua";
        if (lower.contains("doujinshi") || lower.contains("doujin")) return "Doujinshi";
        if (lower.contains("manga")) return "Manga";
        return "";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String normalizeGenreValue(String genre) {
        if (genre == null) return "";
        String value = genre.trim();
        if (value.isEmpty()) return "";
        value = value.toLowerCase(Locale.ROOT);
        value = value.replace(".", "");
        value = value.replaceAll("[^a-z0-9]+", "-");
        value = value.replaceAll("^-+", "").replaceAll("-+$", "");
        return value;
    }

    private static void fillGenres() {
        addGenre("4-Koma", "4-koma"); addGenre("4-Koma. Comedy", "4-koma-comedy"); addGenre("Action", "action"); addGenre("Action. Adventure", "action-adventure"); addGenre("Adult", "adult"); addGenre("Adventure", "adventure"); addGenre("Comedy", "comedy"); addGenre("Cooking", "cooking"); addGenre("Demons", "demons"); addGenre("Doujinshi", "doujinshi"); addGenre("Drama", "drama"); addGenre("Ecchi", "ecchi"); addGenre("Echi", "echi"); addGenre("Fantasy", "fantasy"); addGenre("Game", "game"); addGenre("Gender Bender", "gender-bender"); addGenre("Gore", "gore"); addGenre("Harem", "harem"); addGenre("Historical", "historical"); addGenre("Horror", "horror"); addGenre("Isekai", "isekai"); addGenre("Josei", "josei"); addGenre("Magic", "magic"); addGenre("Manga", "manga"); addGenre("Manhua", "manhua"); addGenre("Manhwa", "manhwa"); addGenre("Martial Arts", "martial-arts"); addGenre("Mature", "mature"); addGenre("Mecha", "mecha"); addGenre("Medical", "medical"); addGenre("Military", "military"); addGenre("Music", "music"); addGenre("Mystery", "mystery"); addGenre("One Shot", "one-shot"); addGenre("Oneshot", "oneshot"); addGenre("Parody", "parody"); addGenre("Police", "police"); addGenre("Psychological", "psychological"); addGenre("Romance", "romance"); addGenre("Samurai", "samurai"); addGenre("School", "school"); addGenre("School Life", "school-life"); addGenre("Sci-fi", "sci-fi"); addGenre("Seinen", "seinen"); addGenre("Shoujo", "shoujo"); addGenre("Shoujo Ai", "shoujo-ai"); addGenre("Shounen", "shounen"); addGenre("Shounen Ai", "shounen-ai"); addGenre("Slice of Life", "slice-of-life"); addGenre("Smut", "smut"); addGenre("Sports", "sports"); addGenre("Super Power", "super-power"); addGenre("Supernatural", "supernatural"); addGenre("Thriller", "thriller"); addGenre("Tragedy", "tragedy"); addGenre("Vampire", "vampire"); addGenre("Webtoon", "webtoon"); addGenre("Webtoons", "webtoons"); addGenre("Yuri", "yuri");
    }

    private static void addGenre(String title, String value) { GENRES.add(new GenreItem(title, value)); }
}
