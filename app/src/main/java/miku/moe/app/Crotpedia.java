package miku.moe.app;

import android.text.TextUtils;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class Crotpedia extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_CROTPEDIA); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, CACHE_TTL);
    private static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_CROTPEDIA;
    private static final String SOURCE_LABEL = "Crotpedia";
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return SOURCE_LABEL; }

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
            final int safePage = Math.max(1, page);
            final String requestQuery = query == null ? "" : query.trim();
            String url = buildListUrl(safePage, sort, requestQuery, genre);
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= expectedPageSize(requestQuery, genre)); return; }
            getText(url, new Result<String>() {
                @Override public void onSuccess(String body, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            Document doc = Jsoup.parse(body, base());
                            if (isLoginPage(doc, body)) { MangaCoroutines.main(() -> cb.onError("Login Crotpedia dulu lewat icon WebView")); return; }
                            ArrayList<MangaPost> out = parseList(doc);
                            boolean next = hasNext(doc);
                            if (out.isEmpty() && safePage <= 1 && !requestQuery.isEmpty()) {
                                MangaCoroutines.main(() -> searchAjax(requestQuery, cb));
                                return;
                            }
                            String requestFilter = genre == null ? "" : genre.trim();
                            if (out.isEmpty() && safePage <= 1 && requestQuery.isEmpty() && requestFilter.isEmpty()) {
                                MangaCoroutines.main(() -> cb.onError("Daftar Crotpedia kosong dari endpoint advanced-search"));
                                return;
                            }
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Crotpedia gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) {
                    if (safePage <= 1 && !requestQuery.isEmpty()) searchAjax(requestQuery, cb);
                    else cb.onError(message);
                }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    private void searchAjax(String query, Result<ArrayList<MangaPost>> cb) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) { cb.onSuccess(new ArrayList<>(), false); return; }
        String key = "ajax:" + q.toLowerCase(Locale.ROOT);
        ArrayList<MangaPost> cached = LIST_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        FormBody body = new FormBody.Builder()
                .add("action", "data_fetch")
                .add("keyword", q)
                .build();
        Request request = new Request.Builder()
                .url(base() + "/wp-admin/admin-ajax.php")
                .headers(ajaxHeaders())
                .post(body)
                .build();
        CloudflareHelper.enqueue(client, request, SOURCE_LABEL, new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code())); return; }
                MangaCoroutines.io(() -> {
                    try {
                        Document doc = Jsoup.parse("<div class=\"crotpedia-ajax-root\">" + responseBody + "</div>", base());
                        ArrayList<MangaPost> out = parseList(doc);
                        LIST_CACHE.put(key, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Search AJAX Crotpedia gagal dibaca")); }
                });
            }
        });
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getText(base() + "/advanced-search/", new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        Document doc = Jsoup.parse(body, base());
                        if (isLoginPage(doc, body)) { MangaCoroutines.main(() -> cb.onError("Login Crotpedia dulu lewat icon WebView")); return; }
                        ArrayList<GenreItem> out = parseGenres(doc);
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

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSeriesSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getText(seriesUrl(clean), new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        Document doc = Jsoup.parse(body, base());
                        if (isLoginPage(doc, body)) { MangaCoroutines.main(() -> cb.onError("Login Crotpedia dulu lewat icon WebView")); return; }
                        MangaPost post = parseDetail(clean, doc);
                        if (post.title.trim().isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail Crotpedia kosong")); return; }
                        ArrayList<MangaChapter> chapters = parseChapters(clean, doc);
                        post.totalChapters = chapters.size();
                        if (!chapters.isEmpty()) {
                            MangaChapter newest = chapters.get(0);
                            for (MangaChapter chapter : chapters) if (chapter.index > newest.index) newest = chapter;
                            post.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
                            post.latestChapterDate = newest.date == null ? "" : newest.date;
                        }
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Crotpedia gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSeriesSlug(slug);
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
        String clean = cleanSeriesSlug(slug);
        String key = clean + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaChapter chapter = findCachedChapter(clean, index);
        if (chapter == null || chapter.chapterId == null || chapter.chapterId.trim().isEmpty()) {
            chapters(clean, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) { loadPages(clean, index, cb); }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        loadPages(clean, index, cb);
    }

    private void loadPages(String clean, float index, Result<ArrayList<String>> cb) {
        String key = clean + ":" + MangaChapter.formatIndex(index);
        MangaChapter chapter = findCachedChapter(clean, index);
        if (chapter == null || chapter.chapterId == null || chapter.chapterId.trim().isEmpty()) { cb.onError("Chapter Crotpedia tidak ditemukan"); return; }
        getText(chapter.chapterId.trim(), new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        Document doc = Jsoup.parse(body, base());
                        if (isLoginPage(doc, body)) { MangaCoroutines.main(() -> cb.onError("Login Crotpedia dulu lewat icon WebView")); return; }
                        ArrayList<String> pages = parsePages(doc);
                        if (pages.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman Crotpedia kosong")); return; }
                        PAGE_CACHE.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Crotpedia gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost post : list) if (post != null && post.slug != null && !post.slug.trim().isEmpty() && (post.latestChapter == null || post.latestChapter.trim().isEmpty() || post.typeLabel == null || post.typeLabel.trim().isEmpty())) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost post : list) {
            if (post == null || post.slug == null || post.slug.trim().isEmpty()) continue;
            if (post.latestChapter != null && !post.latestChapter.trim().isEmpty() && post.typeLabel != null && !post.typeLabel.trim().isEmpty()) continue;
            detail(post.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        if (post.latestChapter == null || post.latestChapter.trim().isEmpty()) post.latestChapter = detail.latestChapter;
                        if (post.latestChapterDate == null || post.latestChapterDate.trim().isEmpty()) post.latestChapterDate = detail.latestChapterDate;
                        if (post.typeLabel == null || post.typeLabel.trim().isEmpty()) post.typeLabel = detail.typeLabel;
                        if (post.genre == null || post.genre.trim().isEmpty()) post.genre = detail.genre;
                        if (post.status == null || post.status.trim().isEmpty()) post.status = detail.status;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    private String buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String q = query == null ? "" : query.trim();
        String filter = genre == null ? "" : genre.trim();
        if (!q.isEmpty()) {
            if (page <= 1) return base() + "/?s=" + URLEncoder.encode(q, "UTF-8");
            return base() + "/page/" + page + "/?s=" + URLEncoder.encode(q, "UTF-8");
        }
        String sortValue = normalizeSort(sort);
        String typeValue = firstNonEmpty(extractType(filter), typeForSort(sort));
        String statusValue = extractStatus(filter);
        ArrayList<String> genres = extractGenres(filter);
        return advancedSearchUrl(Math.max(1, page), sortValue, typeValue, statusValue, genres);
    }

    private String advancedSearchUrl(int page, String order, String type, String status, ArrayList<String> genres) {
        String path = page <= 1 ? base() + "/advanced-search/" : base() + "/advanced-search/page/" + page + "/";
        HttpUrl parsed = HttpUrl.parse(path);
        HttpUrl.Builder builder = parsed == null ? new HttpUrl.Builder().scheme("https").host("crotpedia.net").addPathSegment("advanced-search") : parsed.newBuilder();
        builder.addQueryParameter("title", "");
        builder.addQueryParameter("author", "");
        builder.addQueryParameter("artist", "");
        builder.addQueryParameter("yearx", "");
        builder.addQueryParameter("status", status == null ? "" : status.trim());
        builder.addQueryParameter("type", type == null ? "" : type.trim());
        builder.addQueryParameter("order", order == null || order.trim().isEmpty() ? "update" : order.trim());
        if (genres != null) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String raw : genres) {
                String value = raw == null ? "" : raw.trim();
                if (!value.isEmpty() && seen.add(value)) builder.addQueryParameter("genre[]", value);
            }
        }
        return builder.build().toString();
    }

    private ArrayList<MangaPost> parseList(Document doc) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element item : doc.select(".flexbox2-item, .searchbox")) {
            Element link = item.selectFirst(".flexbox2-content > a[href], .searchbox > a[href], a[href*=/baca/series/]");
            if (link == null) continue;
            String href = link.absUrl("href");
            String slug = cleanSeriesSlug(href);
            if (slug.isEmpty() || !seen.add(slug)) continue;
            String title = firstNonEmpty(attr(link, "title"), text(item.selectFirst(".flexbox2-title span:first-child")), text(item.selectFirst(".searchbox-title")), text(link));
            title = cleanTitle(title);
            String cover = imageFrom(item.selectFirst(".flexbox2-thumb img, .searchbox-thumb img, img"));
            String author = text(item.selectFirst(".studio"));
            String type = text(item.selectFirst(".type"));
            String status = normalizeStatus(text(item.selectFirst(".status")));
            String latest = normalizeLatestChapter(text(item.selectFirst(".season")));
            String synopsis = text(item.selectFirst(".synops"));
            ArrayList<String> genres = new ArrayList<>();
            LinkedHashSet<String> genreSeen = new LinkedHashSet<>();
            for (Element genre : item.select(".genres a, a[href*=/baca/genre/]")) {
                String value = text(genre);
                if (!value.isEmpty() && genreSeen.add(value.toLowerCase(Locale.ROOT))) genres.add(value);
            }
            MangaPost post = new MangaPost(slug, title, cover, author, status, synopsis, TextUtils.join(", ", genres), type, latest, "").withSource(SOURCE_ID, SOURCE_LABEL);
            post.info = text(item.selectFirst(".score"));
            out.add(post);
        }
        return out;
    }

    private MangaPost parseDetail(String slug, Document doc) {
        String title = text(doc.selectFirst(".series-title h2, h1.entry-title, .post-title h1"));
        if (title.isEmpty()) title = cleanTitle(meta(doc, "og:title"));
        String cover = imageFrom(doc.selectFirst(".series-thumb img, .thumb img, .post-thumb img"));
        if (cover.isEmpty()) cover = meta(doc, "og:image");
        String synopsis = text(doc.selectFirst(".series-synops, .entry-content .sinopsis, .synopsis"));
        if (synopsis.isEmpty()) synopsis = meta(doc, "description");
        String type = text(doc.selectFirst(".series-infoz.block .type, .series-infoz .type, span.type"));
        if (type.isEmpty()) type = infoValue(doc, "Type");
        String status = text(doc.selectFirst(".series-infoz.block .status, .series-infoz .status"));
        if (status.isEmpty()) status = infoValue(doc, "Status");
        if (status.isEmpty()) status = MangaStatusParser.fromInfoRows(doc);
        if (status.isEmpty()) status = text(doc.selectFirst("span.status"));
        String author = infoValue(doc, "Author");
        if (author.isEmpty()) author = infoValue(doc, "Artist");
        ArrayList<String> genres = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select(".series-genres a, a[href*=/baca/genre/]")) {
            String value = text(link);
            if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) genres.add(value);
        }
        String alt = firstNonEmpty(text(doc.selectFirst(".series-titlex span")), text(doc.selectFirst(".series-title span")));
        String info = collectInfo(doc, title, alt);
        MangaPost post = new MangaPost(slug, cleanTitle(title), cover, author, normalizeStatus(status), cleanSynopsis(synopsis), TextUtils.join(", ", genres), type, "", "").withSource(SOURCE_ID, SOURCE_LABEL);
        post.info = info;
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String mangaSlug, Document doc) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element item : doc.select(".series-chapterlist .flexch-infoz a[href], .series-chapterlist a[href]")) {
            String href = item.absUrl("href");
            if (href.isEmpty() || href.contains("/go/") || !href.contains("/baca/")) continue;
            String title = text(item.selectFirst("span:first-child"));
            if (title.isEmpty()) title = attr(item, "title");
            if (title.isEmpty()) title = text(item);
            float index = parseChapterIndex(title.isEmpty() ? href : title);
            if (index < 0) index = parseChapterIndex(href);
            if (index < 0) index = out.size() + 1;
            String key = MangaChapter.formatIndex(index) + "|" + href;
            if (!seen.add(key)) continue;
            String date = text(item.selectFirst(".date, time"));
            MangaChapter chapter = new MangaChapter(mangaSlug, index, title, date);
            chapter.chapterId = href;
            out.add(chapter);
        }
        return out;
    }

    private ArrayList<String> parsePages(Document doc) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element image : doc.select(".reader-area img, .reading-content img, .entry-content img, article img")) {
            String url = imageFrom(image);
            if (isReaderImage(url) && seen.add(url)) out.add(url);
        }
        if (out.isEmpty() && doc != null) {
            Matcher matcher = Pattern.compile("https?://reader\\.eromanga\\.cfd/images/[^\"'<>\\s]+", Pattern.CASE_INSENSITIVE).matcher(doc.html());
            while (matcher.find()) {
                String url = matcher.group();
                if (isReaderImage(url) && seen.add(url)) out.add(url);
            }
        }
        return out;
    }

    private ArrayList<GenreItem> parseGenres(Document doc) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element input : doc.select("form[action*=advanced-search] input[name='genre[]']")) {
            String slug = attr(input, "value");
            if (slug.isEmpty() || !seen.add(slug)) continue;
            String id = attr(input, "id");
            Element label = null;
            if (!id.isEmpty()) {
                for (Element candidate : doc.select("label[for]")) {
                    if (id.equals(attr(candidate, "for"))) { label = candidate; break; }
                }
            }
            if (label == null) label = input.parent() == null ? null : input.parent().selectFirst("label");
            String title = cleanGenreTitle(text(label));
            if (title.isEmpty()) title = slugToTitle(slug);
            out.add(new GenreItem(title, slug));
        }
        for (Element link : doc.select("a[href*=/baca/genre/]")) {
            String href = link.absUrl("href");
            String slug = genreSlug(href);
            if (slug.isEmpty() || !seen.add(slug)) continue;
            String title = cleanGenreTitle(text(link));
            if (title.isEmpty()) title = slugToTitle(slug);
            out.add(new GenreItem(title, slug));
        }
        return out;
    }

    private ArrayList<GenreItem> fallbackGenres() {
        String[][] values = new String[][]{{"2 Penetration","2-penetration"},{"3 Penetration","3-penetration"},{"Ahegao","ahegao"},{"Anal","anal"},{"Apron","apron"},{"Bdsm","bdsm"},{"Big Breast","big-breast"},{"Big Penis","big-penis"},{"Blackmail","blackmail"},{"Bloomers","bloomers"},{"Blowjob","blowjob"},{"Body Swap","body-swap"},{"Bondage","bondage"},{"Bukkake","bukkake"},{"Bunny Girl","bunny-girl"},{"Censored","censored"},{"Cervix Penetration","cervix-penetration"},{"Cheating","cheating"},{"Chinese Dress","chinese-dress"},{"Colored","colored"},{"Condom","condom"},{"Cosplay","cosplay"},{"Crossdressing","crossdressing"},{"Dark Skin","dark-skin"},{"Deepthroat","deepthroat"},{"Double Penetration","double-penetration"},{"Drama","drama"},{"Elf","elf"},{"Emotionless","emotionless"},{"exhibitionism","exhibitionism"},{"Fanbox","fanbox"},{"Female Only","female-only"},{"Femboy","femboy"},{"Femdom","femdom"},{"FFM Threesome","ffm-threesome"},{"Filming","filming"},{"Fingering","fingering"},{"Footjob","footjob"},{"Force","force"},{"Fox Girl","fox-girl"},{"Futanari","futanari"},{"Gender Bender","gender-bender"},{"Glasses","glasses"},{"Group","group"},{"Hair Buns","hair-buns"},{"Handjob","handjob"},{"Harem","harem"},{"Impregnation","impregnation"},{"Incest","incest"},{"Inseki","inseki"},{"Kemonomimi","kemonomimi"},{"Kimono","kimono"},{"Kissing","kissing"},{"Kogal","kogal"},{"Kuudere","kuudere"},{"Lactation","lactation"},{"Lingerie","lingerie"},{"Loli","loli"},{"Maid","maid"},{"Manhwa","manhwa"},{"Masturbation","masturbation"},{"Milf","milf"},{"Mind Break","mind-break"},{"Mind Control","mind-control"},{"MMF Threesome","mmf-threesome"},{"Monster","monster"},{"Nakadashi","nakadashi"},{"Netorare","netorare"},{"Netorase","netorase"},{"Netori","netori"},{"No Penetration","no-penetration"},{"Nun","nun"},{"Nurse","nurse"},{"Office Lady","office-lady"},{"Old Man","old-man"},{"Osananajimi","osananajimi"},{"Oyakodon","oyakodon"},{"Paizuri","paizuri"},{"Pantyhose","pantyhose"},{"Parodi: Ao no Hako","parodi-ao-no-hako"},{"Parodi: Arknights","parodi-arknights"},{"Parodi: Azur Lane","parodi-azur-lane"},{"Parodi: Blue Archive","parodi-blue-archive"},{"Parodi: Bocchi the Rock!","parodi-bocchi-the-rock"},{"Parodi: Boku no Hero Academia","parodi-boku-no-hero-academia"},{"Parodi: Boku no Kokoro no Yabai Yatsu","parodi-boku-no-kokoro-no-yabai-yatsu"},{"Parodi: Bokutachi wa Benkyou ga Dekinai","parodi-bokutachi-wa-benkyou-ga-dekinai"},{"Parodi: Fate Grand Order","parodi-fate-grand-order"},{"Parodi: Genshin Impact","parodi-genshin-impact"},{"Parodi: Girls Frontline","parodi-girls-frontline"},{"Parodi: Gotoubun no Hanayome","parodi-gotoubun-no-hanayome"},{"Parodi: Hololive","parodi-hololive"},{"Parodi: Honkai Impact","parodi-honkai-impact"},{"Parodi: Honkai Star Rail","parodi-honkai-star-rail"},{"Parodi: Kantai Collection","parodi-kantai-collection"},{"Parodi: Kyoukai no Kanata","parodi-kyoukai-no-kanata"},{"Parodi: Love Live","parodi-love-live"},{"Parodi: Make Heroine ga Oosugiru","parodi-make-heroine-ga-oosugiru"},{"Parodi: Nanabun no Nijyuuni","parodi-nanabun-no-nijyuuni"},{"Parodi: Nijisanji","parodi-nijisanji"},{"Parodi: Nikke Goddes of Factory","parodi-nikke-goddes-of-factory"},{"Parodi: Princess Connect!","parodi-princess-connect"},{"Parodi: Seishun Buta Yarou wa Bunny Girl Senpai no Yume o Minai","parodi-seishun-buta-yarou-wa-bunny-girl-senpai-no-yume-o-minai"},{"Parodi: Sono Bisque Doll wa Koi o Suru","parodi-sono-bisque-doll-wa-koi-o-suru"},{"Parodi: Sousou no Frieren","parodi-sousou-no-frieren"},{"Parodi: The iDOLM@STER","parodi-the-idolmster"},{"Parodi: To Love-Ru","parodi-to-love-ru"},{"Parodi: Tokidoki Bosotto Russia-go de Dereru Tonari no Alya-san","parodi-tokidoki-bosotto-russia-go-de-dereru-tonari-no-alya-san"},{"Parodi: Touhou","parodi-touhou"},{"Parodi: Xenoblade Chronicles","parodi-xenoblade-chronicles"},{"Parodi: Zenless Zone Zero","parodi-zenless-zone-zero"},{"Pegging","pegging"},{"Pixiv","pixiv"},{"Ponytail","ponytail"},{"Possession","possession"},{"Pregnant","pregnant"},{"Prostitution","prostitution"},{"Rape","rape"},{"Reincarnation","reincarnation"},{"Rimjob","rimjob"},{"Romance","romance"},{"Shimaidon","shimaidon"},{"Shotacon","shotacon"},{"Sister","sister"},{"Sleeping","sleeping"},{"Sole Female","sole-female"},{"Sole Male","sole-male"},{"Squirting","squirting"},{"Stockings","stockings"},{"Stomach Deformation","stomach-deformation"},{"Succubus","succubus"},{"Sweating","sweating"},{"Swimsuit","swimsuit"},{"Tankoubon","tankoubon"},{"Teacher","teacher"},{"Tomboy","tomboy"},{"Toys","toys"},{"Tsundere","tsundere"},{"Twins","twins"},{"Twintails","twintails"},{"Uncensored","uncensored"},{"Uniform","uniform"},{"Vanilla","vanilla"},{"Virginity","virginity"},{"Webtoon","webtoon"},{"X-Ray","x-ray"},{"Yandere","yandere"},{"Yuri","yuri"}};
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] value : values) out.add(new GenreItem(value[0], value[1]));
        return out;
    }

    private MangaChapter findCachedChapter(String slug, float index) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached == null) return null;
        for (MangaChapter chapter : cached) if (chapter != null && Math.abs(chapter.index - index) < 0.001f) return chapter;
        return null;
    }

    private boolean hasNext(Document doc) {
        if (doc == null) return false;
        return doc.selectFirst(".pagination a.next, a.next.page-numbers, a[rel=next]") != null;
    }

    private int expectedPageSize(String query, String genre) {
        if (query != null && !query.trim().isEmpty()) return 10;
        return 16;
    }

    private String normalizeSort(String sort) {
        String value = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (value.equals("popular") || value.equals("popularity")) return "popular";
        if (value.equals("rating") || value.equals("rate")) return "rating";
        if (value.equals("added") || value.equals("latest_added") || value.equals("new")) return "latest";
        if (value.equals("az") || value.equals("title") || value.equals("a-z")) return "title";
        if (value.equals("za") || value.equals("titlereverse") || value.equals("z-a")) return "titlereverse";
        if (value.equals("latest") || value.equals("update") || value.equals("updated") || value.equals("latest_update")) return "update";
        return "update";
    }

    private String typeForSort(String sort) {
        return normalizeTypeValue(sort);
    }

    private ArrayList<String> extractGenres(String raw) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (raw == null) return out;
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty() || value.startsWith("type:") || value.startsWith("status:")) continue;
            if (value.startsWith("genre:")) value = value.substring(6).trim();
            if (!value.isEmpty() && seen.add(value)) out.add(value);
        }
        return out;
    }

    private String extractStatus(String raw) {
        if (raw == null) return "";
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.startsWith("status:")) continue;
            value = value.substring(7).trim().toLowerCase(Locale.ROOT);
            if (value.equals("ongoing") || value.equals("on-going")) return "ongoing";
            if (value.equals("completed") || value.equals("complete")) return "completed";
            return value;
        }
        return "";
    }

    private String extractType(String raw) {
        if (raw == null) return "";
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.startsWith("type:")) continue;
            return normalizeTypeValue(value.substring(5));
        }
        return "";
    }

    private String normalizeTypeValue(String value) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("image-set") || lower.equals("imageset") || lower.equals("image_set") || lower.equals("image set") || lower.equals("image")) return "Image-set";
        if (lower.equals("one-shot") || lower.equals("oneshot") || lower.equals("one_shot") || lower.equals("one shot")) return "One-shot";
        if (lower.equals("doujinshi") || lower.equals("doujin")) return "Doujinshi";
        if (lower.equals("manhwa")) return "Manhwa";
        if (lower.equals("manga")) return "Manga";
        return "";
    }

    private String cleanSeriesSlug(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                HttpUrl parsed = HttpUrl.parse(value);
                if (parsed != null) value = parsed.encodedPath();
            } catch(Exception ignored) {}
        }
        value = value.replaceFirst("^/", "");
        value = value.replaceFirst("^baca/series/", "");
        value = value.replaceFirst("^series/", "");
        value = value.replaceAll("/+$", "");
        return value.trim();
    }

    private String seriesUrl(String slug) {
        return base() + "/baca/series/" + urlSegment(slug) + "/";
    }

    private String urlSegment(String value) {
        try { return URLEncoder.encode(value == null ? "" : value.trim(), "UTF-8").replace("+", "%20").replace("%2F", "/"); }
        catch(Exception e) { return value == null ? "" : value.trim(); }
    }

    private String genreSlug(String href) {
        String value = href == null ? "" : href.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) value = parsed.encodedPath();
        }
        Matcher matcher = Pattern.compile("/baca/genre/([^/]+)/?").matcher(value);
        if (matcher.find()) return matcher.group(1).trim();
        return "";
    }

    private float parseChapterIndex(String text) {
        if (text == null) return -1f;
        Matcher matcher = Pattern.compile("(?i)chapter[^0-9]*(\\d+(?:[.,]\\d+)?)").matcher(text);
        if (matcher.find()) return parseChapterNumber(matcher.group(1));
        matcher = Pattern.compile("(\\d+(?:[.,]\\d+)?)").matcher(text);
        if (matcher.find()) return parseChapterNumber(matcher.group(1));
        return -1f;
    }

    private float parseChapterNumber(String raw) {
        try { return Float.parseFloat((raw == null ? "" : raw).replace(',', '.')); }
        catch(Exception e) { return -1f; }
    }

    private boolean isLoginPage(Document doc, String body) {
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        String title = doc == null || doc.title() == null ? "" : doc.title().toLowerCase(Locale.ROOT);
        if (title.contains("login") || text.contains("/login/")) {
            return doc != null && (doc.selectFirst("form[action*=login], input[name=log], input[name=pwd]") != null || doc.selectFirst(".reader-area img[src], .flexbox2-item, .searchbox, .series-title h2") == null);
        }
        return false;
    }

    private String infoValue(Document doc, String key) {
        if (doc == null || key == null) return "";
        String target = normalizeInfoLabel(key);
        for (Element item : doc.select(".series-infolist li, .series-infoz li, .series-info li")) {
            Element labelElement = item.selectFirst("b, strong, .label");
            String label = labelElement == null ? "" : normalizeInfoLabel(text(labelElement));
            if (label.isEmpty()) {
                String raw = text(item);
                if (raw.toLowerCase(Locale.ROOT).startsWith(target.toLowerCase(Locale.ROOT))) return raw.substring(Math.min(key.length(), raw.length())).replaceFirst("^\\s*:?\\s*", "").trim();
                continue;
            }
            if (label.equalsIgnoreCase(target)) return infoItemValue(item, labelElement);
        }
        return "";
    }

    private String collectInfo(Document doc, String title, String alt) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> used = new LinkedHashSet<>();
        String alternative = mergeInfoValues(title, alt, infoValue(doc, "Alternative"));
        addInfoRow(values, used, "Alternative", alternative);
        for (Element item : doc.select(".series-infolist li")) {
            Element labelElement = item.selectFirst("b, strong, .label");
            String label = labelElement == null ? "" : normalizeInfoLabel(text(labelElement));
            if (label.isEmpty()) continue;
            String value = infoItemValue(item, labelElement);
            if (label.equalsIgnoreCase("Alternative")) continue;
            addInfoRow(values, used, label, value);
        }
        addInfoRow(values, used, "Rating", text(doc.selectFirst(".series-infoz.score span")));
        addInfoRow(values, used, "Bookmark", text(doc.selectFirst(".favcount span[data-favorites-post-count-id]")));
        return TextUtils.join("||", values);
    }

    private void addInfoRow(ArrayList<String> values, LinkedHashSet<String> used, String label, String value) {
        String cleanLabel = normalizeInfoLabel(label);
        String cleanValue = value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (cleanLabel.isEmpty() || cleanValue.isEmpty()) return;
        String key = cleanLabel.toLowerCase(Locale.ROOT);
        if (!used.add(key)) return;
        values.add(cleanLabel + ": " + cleanValue);
    }

    private String infoItemValue(Element item, Element labelElement) {
        if (item == null) return "";
        String label = labelElement == null ? "" : text(labelElement);
        Element span = item.selectFirst("span");
        if (span != null && span != labelElement) return text(span);
        Element link = item.selectFirst("a");
        if (link != null && link != labelElement) return text(link);
        String raw = text(item);
        if (!label.isEmpty()) raw = raw.replaceFirst("^" + Pattern.quote(label) + "\\s*:?\\s*", "");
        return raw.trim();
    }

    private String normalizeInfoLabel(String value) {
        if (value == null) return "";
        String text = value.replace('\u00A0', ' ').replaceAll("\\s+", " ").replace(":", "").trim();
        if (text.equalsIgnoreCase("Published")) return "Published";
        if (text.equalsIgnoreCase("Author")) return "Author";
        if (text.equalsIgnoreCase("Artist")) return "Artist";
        if (text.equalsIgnoreCase("Alternative")) return "Alternative";
        if (text.equalsIgnoreCase("Total Chapter") || text.equalsIgnoreCase("Total Chapters")) return "Total Chapter";
        if (text.equalsIgnoreCase("Project")) return "Project";
        if (text.equalsIgnoreCase("Rating") || text.equalsIgnoreCase("Score")) return "Rating";
        if (text.equalsIgnoreCase("Bookmark") || text.equalsIgnoreCase("Bookmarks")) return "Bookmark";
        return text;
    }

    private String mergeInfoValues(String title, String... parts) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String cleanTitle = title == null ? "" : title.trim();
        for (String part : parts) {
            String value = part == null ? "" : part.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
            if (value.isEmpty()) continue;
            if (!cleanTitle.isEmpty() && value.equalsIgnoreCase(cleanTitle)) continue;
            if (seen.add(value.toLowerCase(Locale.ROOT))) out.add(value);
        }
        return TextUtils.join(" / ", out);
    }

    private String normalizeLatestChapter(String value) {
        String text = value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        Matcher reverse = Pattern.compile("(?i)^(\\d+(?:[.,]\\d+)?)\\s*chapter(?:s)?$").matcher(text);
        if (reverse.find()) return "Chapter " + reverse.group(1).replace(',', '.');
        Matcher direct = Pattern.compile("(?i)^chapter\\s*(\\d+(?:[.,]\\d+)?)$").matcher(text);
        if (direct.find()) return "Chapter " + direct.group(1).replace(',', '.');
        if (text.toLowerCase(Locale.ROOT).contains("chapter")) return text;
        Matcher anyNumber = Pattern.compile("(\\d+(?:[.,]\\d+)?)").matcher(text);
        if (anyNumber.find()) return "Chapter " + anyNumber.group(1).replace(',', '.');
        return text;
    }

    private String normalizeStatus(String value) {
        String text = value == null ? "" : value.trim();
        if (text.equalsIgnoreCase("complete")) return "Completed";
        return text;
    }

    private String cleanSynopsis(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)^sinopsis\\s*:?\\s*", "").trim();
    }

    private String cleanTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("(?i)\\s*-\\s*CrotPedia\\s*$", "").replaceAll("\\s+18\\+$", "").trim();
    }

    private String cleanGenreTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("\\s+\\d+$", "").trim();
    }

    private String slugToTitle(String slug) {
        String[] parts = slug.replace('-', ' ').split("\\s+");
        ArrayList<String> out = new ArrayList<>();
        for (String part : parts) if (!part.isEmpty()) out.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        return TextUtils.join(" ", out);
    }

    private String imageFrom(Element element) {
        if (element == null) return "";
        String url = firstNonEmpty(
                absoluteAttr(element, "data-src"),
                absoluteAttr(element, "data-lazy-src"),
                absoluteAttr(element, "data-original"),
                absoluteAttr(element, "data-cfsrc"),
                firstSrcsetUrl(element),
                absoluteAttr(element, "src"));
        if (url.startsWith("//")) url = "https:" + url;
        return url.trim();
    }

    private String absoluteAttr(Element element, String attrName) {
        if (element == null || attrName == null) return "";
        String abs = element.absUrl(attrName);
        if (!abs.isEmpty()) return abs;
        return element.attr(attrName).trim();
    }

    private String firstSrcsetUrl(Element element) {
        if (element == null) return "";
        String srcset = element.attr("srcset");
        if (srcset == null || srcset.trim().isEmpty()) return "";
        String first = srcset.split(",")[0].trim();
        if (first.isEmpty()) return "";
        String raw = first.split("\\s+")[0].trim();
        if (raw.startsWith("//")) return "https:" + raw;
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;
        return element.absUrl("srcset");
    }

    private boolean isReaderImage(String url) {
        if (url == null) return false;
        String clean = url.trim();
        if (!clean.startsWith("http")) return false;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("reader.eromanga.cfd/images/")) return true;
        return lower.matches(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$") && !lower.contains("cover.eromanga.cfd");
    }

    private String meta(Document doc, String property) {
        if (doc == null || property == null) return "";
        Element el = doc.selectFirst("meta[property=\"" + property + "\"], meta[name=\"" + property + "\"]");
        return attr(el, "content");
    }

    private String text(Element element) { return element == null ? "" : element.text().replace('\u00A0', ' ').replaceAll("\\s+", " ").trim(); }
    private String attr(Element element, String key) { return element == null || key == null ? "" : element.attr(key).trim(); }
    private String firstNonEmpty(String... values) { for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim(); return ""; }

    private static final int MAX_ORIGIN_ERROR_RETRY = 2;

    private void getText(String url, Result<String> cb) { getText(url, cb, 0); }

    private void getText(String url, Result<String> cb, int attempt) {
        Request request = new Request.Builder().url(url).headers(headers()).build();
        CloudflareHelper.enqueue(client, request, SOURCE_LABEL, new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                if (!response.isSuccessful()) {
                    response.close();

                    if (isTransientOriginError(code) && attempt < MAX_ORIGIN_ERROR_RETRY) {
                        long delayMs = 700L * (attempt + 1);
                        MangaCoroutines.io(() -> {
                            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
                            getText(url, cb, attempt + 1);
                        });
                        return;
                    }
                    String message = isTransientOriginError(code)
                            ? "Server " + SOURCE_LABEL + " sedang gangguan (HTTP " + code + "), coba lagi nanti"
                            : "HTTP " + code;
                    MangaCoroutines.main(() -> cb.onError(message));
                    return;
                }
                String body = response.body() == null ? "" : response.body().string();
                MangaCoroutines.main(() -> cb.onSuccess(body, false));
            }
        });
    }

    private static boolean isTransientOriginError(int code) {
        return code >= 520 && code <= 530;
    }

    private okhttp3.Headers headers() {
        String b = base();
        return new okhttp3.Headers.Builder()
                .set("Referer", b.endsWith("/") ? b : b + "/")
                .set("Origin", b)
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .set("Accept-Language", "id,en-US;q=0.9")
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                .build();
    }

    private okhttp3.Headers ajaxHeaders() {
        String b = base();
        return new okhttp3.Headers.Builder()
                .set("Referer", b.endsWith("/") ? b : b + "/")
                .set("Origin", b)
                .set("Accept", "text/html,*/*;q=0.9")
                .set("Accept-Language", "id,en-US;q=0.9")
                .set("X-Requested-With", "XMLHttpRequest")
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                .build();
    }
}
