package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Ikiru extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://08.ikiru.wtf";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_IKIRU); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, Boolean> LIST_NEXT_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, 24L * 60L * 60L * 1000L);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private static final Map<String, Integer> ID_CACHE = new ConcurrentHashMap<>();
    private static String nonce;
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Ikiru"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        MangaCoroutines.io(() -> {
            try {
                int safePage = Math.max(1, page);
                String key = safePage + "|" + (sort == null ? "" : sort) + "|" + (query == null ? "" : query) + "|" + (genre == null ? "" : genre);
                ArrayList<MangaPost> cached = LIST_CACHE.get(key);
                if (cached != null) {
                    Boolean cachedHasNext = LIST_NEXT_CACHE.get(key);
                    MangaCoroutines.main(() -> cb.onSuccess(new ArrayList<>(cached), cachedHasNext != null && cachedHasNext));
                    return;
                }
                String html = executeSearch(safePage, sort, query, genre);
                Document document = Jsoup.parse(html, base());
                ArrayList<MangaPost> out = parseListing(document);
                if (out.isEmpty() && html.toLowerCase(Locale.ROOT).contains("/manga/")) {
                    throw new IOException("Parser Ikiru menghasilkan 0 item dari response berisi manga");
                }
                boolean hasNext = hasNext(document, safePage);
                LIST_CACHE.put(key, new ArrayList<>(out));
                LIST_NEXT_CACHE.put(key, hasNext);
                MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
        });
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                ArrayList<GenreItem> out = fetchGenres();
                GENRE_CACHE.put("genres", new ArrayList<>(out));
                MangaCoroutines.main(() -> cb.onSuccess(out, false));
            } catch(Exception e) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
            }
        });
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
                        p.latestChapter = newest.title == null || newest.title.isEmpty() ? "Chapter " + MangaChapter.formatIndex(newest.index) : newest.title;
                        p.latestChapterDate = newest.date == null ? "" : newest.date;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String cleanSlug = normalizeMangaSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        MangaCoroutines.io(() -> {
            try {
                MangaPost post = fetchPostBySlug(cleanSlug);
                if (post == null) { MangaCoroutines.main(() -> cb.onError("Detail Ikiru kosong")); return; }
                DETAIL_CACHE.put(cleanSlug, post);
                MangaCoroutines.main(() -> cb.onSuccess(post, false));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Ikiru gagal dibaca")); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String cleanSlug = normalizeMangaSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                Document document = fetchDetailDocument(cleanSlug);
                ArrayList<MangaChapter> out = parseChapters(document);
                if (out.isEmpty()) out = parseChapterSelects(cleanSlug, document);
                if (out.isEmpty()) throw new IOException("Parser chapter Ikiru menghasilkan 0 item");
                final ArrayList<MangaChapter> result = out;
                CHAPTER_CACHE.put(cleanSlug, new ArrayList<>(result));
                MangaCoroutines.main(() -> cb.onSuccess(result, false));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter Ikiru gagal dibaca")); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = normalizeMangaSlug(slug) + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = null;
                if (chapters != null) for (MangaChapter ch : chapters) if (Math.abs(ch.index - index) < 0.0001f) { chapter = ch; break; }
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter Ikiru tidak ditemukan"); return; }
                getDocument(toAbsolute(chapter.slug), new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                if (out.isEmpty()) throw new IOException("Parser reader Ikiru menghasilkan 0 gambar");
                                for (String pageUrl : out) MangaImageLoader.registerImageReferer(pageUrl, base() + "/");
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Ikiru gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String executeSearch(int page, String sort, String query, String genre) throws Exception {
        int safePage = Math.max(1, page);
        String cleanSort = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String cleanQuery = query == null ? "" : query.trim();
        String cleanGenre = genre == null ? "" : genre.trim();
        if (cleanQuery.isEmpty() && cleanGenre.isEmpty() && ("project".equals(cleanSort) || "projects".equals(cleanSort))) {
            return execute(pagedUrl("/project/", safePage), base() + "/project/");
        }
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
        body.addFormDataPart("nonce", getNonce());
        body.addFormDataPart("inclusion", "OR");
        body.addFormDataPart("exclusion", "OR");
        body.addFormDataPart("page", String.valueOf(safePage));
        body.addFormDataPart("genre", genreJson(cleanGenre));
        body.addFormDataPart("genre_exclude", "[]");
        body.addFormDataPart("author", "[]");
        body.addFormDataPart("artist", "[]");
        body.addFormDataPart("project", "0");
        body.addFormDataPart("type", typeJson(cleanGenre));
        body.addFormDataPart("status", statusJson(cleanGenre));
        body.addFormDataPart("order", orderDirection(cleanSort));
        body.addFormDataPart("orderby", orderBy(cleanSort));
        body.addFormDataPart("query", cleanQuery);
        return executePost(base() + "/wp-admin/admin-ajax.php?action=advanced_search", body.build(), advancedSearchUrl(safePage, cleanSort, cleanQuery, cleanGenre));
    }

    private String pagedUrl(String path, int page) {
        HttpUrl.Builder builder = HttpUrl.parse(base() + path).newBuilder();
        if (page > 1) builder.addQueryParameter("the_page", String.valueOf(page));
        return builder.build().toString();
    }

    private String advancedSearchUrl(int page, String sort, String query, String genre) {
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/advanced-search/").newBuilder();
        builder.addQueryParameter("the_page", String.valueOf(Math.max(1, page)));
        builder.addQueryParameter("the_genre", joinedFilterValues(genre, "genre"));
        builder.addQueryParameter("the_author", "");
        builder.addQueryParameter("the_artist", "");
        builder.addQueryParameter("the_exclude", "");
        builder.addQueryParameter("the_type", joinedFilterValues(genre, "type"));
        builder.addQueryParameter("the_status", joinedFilterValues(genre, "status"));
        builder.addQueryParameter("search_term", query == null ? "" : query);
        builder.addQueryParameter("project", "0");
        builder.addQueryParameter("order", orderDirection(sort));
        builder.addQueryParameter("orderby", orderBy(sort));
        return builder.build().toString();
    }

    private synchronized String getNonce() throws Exception {
        if (nonce != null && !nonce.trim().isEmpty()) return nonce;
        String html = execute(base() + "/wp-admin/admin-ajax.php?type=search_form&action=get_nonce", base() + "/advanced-search/");
        Document document = Jsoup.parseBodyFragment(html, base());
        Element input = document.selectFirst("input[name=search_nonce]");
        String value = input == null ? "" : input.attr("value");
        if (value.trim().isEmpty()) {
            Matcher matcher = Pattern.compile("name=['\"]search_nonce['\"][^>]*value=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(html);
            if (matcher.find()) value = matcher.group(1);
        }
        if (value.trim().isEmpty()) throw new Exception("Nonce kosong");
        nonce = value.trim();
        return nonce;
    }

    private ArrayList<GenreItem> fetchGenres() throws Exception {
        try {
            ArrayList<GenreItem> fromApi = fetchGenresFromApi();
            if (!fromApi.isEmpty()) return fromApi;
        } catch(Exception ignored) { }
        ArrayList<GenreItem> fromPage = fetchGenresFromAdvancedSearch();
        if (!fromPage.isEmpty()) return fromPage;
        return fallbackGenres();
    }

    private ArrayList<GenreItem> fetchGenresFromApi() throws Exception {
        Request req = request(base() + "/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc", base() + "/advanced-search/").cacheControl(CacheControl.FORCE_NETWORK).build();
        Response response = client.newCall(req).execute();
        String raw = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        JsonArray data = JsonParser.parseString(transformJson(raw)).getAsJsonArray();
        ArrayList<GenreItem> out = new ArrayList<>();
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String name = getString(object, "name");
            String slug = getString(object, "slug");
            if (!name.isEmpty() && !slug.isEmpty()) out.add(new GenreItem(name, "genre:" + slug));
        }
        appendStaticFilters(out);
        return out;
    }

    private ArrayList<GenreItem> fetchGenresFromAdvancedSearch() throws Exception {
        String html = execute(base() + "/advanced-search/", base() + "/advanced-search/");
        int start = html.indexOf("var searchTerms");
        if (start < 0) return new ArrayList<>();
        start = html.indexOf('{', start);
        if (start < 0) return new ArrayList<>();
        int end = html.indexOf(";</script>", start);
        if (end < 0) end = html.indexOf(";\n", start);
        if (end < 0) return new ArrayList<>();
        JsonObject root = JsonParser.parseString(html.substring(start, end)).getAsJsonObject();
        JsonObject genres = getObject(root, "genre");
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : genres.entrySet()) {
            JsonElement element = entry.getValue();
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String taxonomy = getString(object, "taxonomy");
            if (!"genre".equals(taxonomy) && !"genres".equals(taxonomy)) continue;
            String name = getString(object, "name");
            String slug = getString(object, "slug");
            if (!name.isEmpty() && !slug.isEmpty() && seen.add(slug)) out.add(new GenreItem(name, "genre:" + slug));
        }
        appendStaticFilters(out);
        return out;
    }

    private static void appendStaticFilters(ArrayList<GenreItem> out) {
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
        out.add(new GenreItem("Ongoing", "status:ongoing"));
        out.add(new GenreItem("Completed", "status:completed"));
        out.add(new GenreItem("Cancelled", "status:cancelled"));
        out.add(new GenreItem("On Hiatus", "status:on-hiatus"));
        out.add(new GenreItem("Unknown", "status:unknown"));
    }

    private ArrayList<MangaPost> parseListing(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seenSlug = new LinkedHashSet<>();
        for (Element anchor : document.select("a[href*=/manga/]")) {
            String href = anchor.attr("abs:href");
            if (href.contains("/chapter-")) continue;
            String slug = extractMangaSlug(href);
            if (slug.isEmpty() || !seenSlug.add(slug)) continue;
            Element card = closestMangaCard(anchor, slug);
            MangaPost post = parseListingPost(card, anchor, slug);
            if (post != null && !post.title.isEmpty() && !post.genre.toLowerCase(Locale.ROOT).contains("novel")) out.add(post);
        }
        return out;
    }

    private MangaPost parseListingPost(Element card, Element anchor, String slug) {
        if (card == null) card = anchor;
        String title = cleanTitle(firstNonEmpty(text(card.selectFirst("h1")), text(card.selectFirst("h2")), text(card.selectFirst("h3")), text(anchor)));
        if (title.isEmpty()) return null;
        String cover = image(card.selectFirst("a[href*=/manga/] img, img.wp-post-image, img"));
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, base() + "/");
        String synopsis = longestParagraph(card);
        String status = statusFromText(card.text());
        String type = typeFromCard(card);
        Element chapter = firstChapterElement(card);
        String latest = chapter == null ? "" : cleanChapterTitle(text(chapter));
        String date = "";
        if (chapter != null) {
            Element time = chapter.selectFirst("time");
            if (time == null && chapter.parent() != null) time = chapter.parent().selectFirst("time");
            date = time == null ? "" : firstNonEmpty(time.attr("datetime"), text(time));
        }
        return new MangaPost("/manga/" + slug + "/", title, cover, "", status, synopsis, "", type, latest, date).withSource(MangaSettingsManager.MANGA_SOURCE_IKIRU, "Ikiru");
    }

    private Element firstChapterElement(Element card) {
        if (card == null) return null;
        for (Element element : card.select("a[href*=/chapter-]")) {
            if (text(element).toLowerCase(Locale.ROOT).contains("chapter")) return element;
        }
        return card.selectFirst("a[href*=/chapter-]");
    }

    private Element closestMangaCard(Element anchor, String slug) {
        Element best = anchor;
        Element current = anchor;
        for (int i = 0; i < 10 && current != null; i++) {
            LinkedHashSet<String> slugs = mangaSlugsIn(current);
            if (slugs.size() == 1 && slugs.contains(slug)) {
                best = current;
                current = current.parent();
            } else break;
        }
        return best;
    }

    private LinkedHashSet<String> mangaSlugsIn(Element element) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (element == null) return out;
        if ("a".equalsIgnoreCase(element.tagName())) {
            String href = element.attr("abs:href");
            if (!href.contains("/chapter-")) {
                String slug = extractMangaSlug(href);
                if (!slug.isEmpty()) out.add(slug);
            }
        }
        for (Element anchor : element.select("a[href*=/manga/]")) {
            String href = anchor.attr("abs:href");
            if (href.contains("/chapter-")) continue;
            String slug = extractMangaSlug(href);
            if (!slug.isEmpty()) out.add(slug);
        }
        return out;
    }

    private MangaPost fetchPostBySlug(String slug) throws Exception {
        Document document = fetchDetailDocument(slug);
        MangaPost post = parseDetail(document, slug);
        if (post != null) return post;
        return fetchPostBySlugFromApi(slug);
    }

    private MangaPost fetchPostBySlugFromApi(String slug) throws Exception {
        HttpUrl url = HttpUrl.parse(base() + "/wp-json/wp/v2/manga").newBuilder().addQueryParameter("slug[]", slug).addQueryParameter("_embed", null).build();
        String raw = execute(url.toString(), base() + "/manga/" + slug + "/");
        JsonArray data = JsonParser.parseString(transformJson(raw)).getAsJsonArray();
        if (data.size() <= 0 || !data.get(0).isJsonObject()) return null;
        return parsePost(data.get(0).getAsJsonObject());
    }

    private Document fetchDetailDocument(String slug) throws Exception {
        String url = toAbsolute("/manga/" + normalizeMangaSlug(slug) + "/");
        return Jsoup.parse(execute(url, base() + "/"), url);
    }

    private MangaPost parseDetail(Document document, String slug) {
        if (document == null) return null;
        JsonObject structured = detailJson(document);
        String title = cleanTitle(firstNonEmpty(readJsonString(structured, "name"), readJsonString(structured, "headline"), text(document.selectFirst("h1[itemprop=name]")), text(document.selectFirst("h1"))));
        if (title.isEmpty()) return null;
        String synopsis = Jsoup.parseBodyFragment(readJsonString(structured, "description")).wholeText().trim();
        if (synopsis.isEmpty()) synopsis = firstNonEmpty(text(document.selectFirst("[itemprop=description]")), longestParagraph(document.selectFirst("article")), longestParagraph(document.selectFirst("main")));
        String cover = detailCover(document, structured);
        if (!cover.isEmpty()) MangaImageLoader.registerImageReferer(cover, base() + "/manga/" + normalizeMangaSlug(slug) + "/");
        String author = firstNonEmpty(readJsonAuthor(structured), extractField(document, "Author"), extractField(document, "Author(s)"));
        String artist = extractField(document, "Artist");
        if (!artist.isEmpty() && !author.toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT))) author = joinUnique(author, artist);
        String genre = firstNonEmpty(readJsonStringList(structured, "genre"), extractGenres(document));
        String type = extractTypeFromDetail(document, genre);
        String status = normalizeStatus(firstNonEmpty(readJsonString(structured, "creativeWorkStatus"), extractField(document, "Status")));
        ArrayList<MangaChapter> chapters = parseChapters(document);
        MangaChapter newest = newestChapter(chapters);
        int total = Math.max(chapters.size(), readJsonInt(structured, "numberOfPages", chapters.size()));
        String id = extractMangaId(document);
        if (!id.isEmpty()) {
            try { ID_CACHE.put(normalizeMangaSlug(slug), Integer.parseInt(id)); } catch(Exception ignored) { }
        }
        MangaPost post = new MangaPost("/manga/" + normalizeMangaSlug(slug) + "/", title, cover, author, status, synopsis, genre, type, newest == null ? "" : newest.title, newest == null ? "" : newest.date).withSource(MangaSettingsManager.MANGA_SOURCE_IKIRU, "Ikiru");
        post.info = readJsonString(structured, "alternateName");
        post.totalChapters = total;
        return post;
    }

    private String detailCover(Document document, JsonObject structured) {
        if (document == null) return normalizeImageUrl(readJsonImage(structured));
        
        String cover = image(document.selectFirst("main article [itemprop=image] img.wp-post-image, article [itemprop=image] img, article img.wp-post-image"));
        if (cover.isEmpty()) cover = imageFromMeta(document, "meta[property=og:image]");
        if (cover.isEmpty()) cover = imageFromMeta(document, "meta[name=twitter:image]");
        if (cover.isEmpty()) cover = normalizeImageUrl(readJsonImage(structured));
        if (cover.isEmpty()) cover = image(document.selectFirst("img.wp-post-image, main img[src*=/wp-content/uploads/]"));
        return normalizeImageUrl(cover);
    }

    private static String imageFromMeta(Document document, String selector) {
        if (document == null) return "";
        Element meta = document.selectFirst(selector);
        return meta == null ? "" : normalizeImageUrl(resolveCandidateImage(meta.attr("content")));
    }

    private MangaChapter newestChapter(ArrayList<MangaChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return null;
        MangaChapter newest = chapters.get(0);
        for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
        return newest;
    }

    private MangaPost parsePost(JsonObject object) {
        int id = getInt(object, "id", 0);
        String slugValue = getString(object, "slug");
        if (id > 0 && !slugValue.isEmpty()) ID_CACHE.put(slugValue, id);
        String title = Parser.unescapeEntities(getString(getObject(object, "title"), "rendered"), false);
        String synopsis = Jsoup.parseBodyFragment(getString(getObject(object, "content"), "rendered")).wholeText().trim();
        if (synopsis.isEmpty()) synopsis = Jsoup.parseBodyFragment(getString(getObject(object, "excerpt"), "rendered")).wholeText().trim();
        JsonObject embedded = getObject(object, "_embedded");
        String cover = parseCover(embedded);
        String author = joinUnique(joinTerms(embedded, "series-author"), joinTerms(embedded, "artist"));
        String genre = joinUnique(joinUnique(joinTerms(embedded, "genre"), joinTerms(embedded, "genres")), joinTerms(embedded, "type"));
        String type = firstType(joinTerms(embedded, "type"));
        String status = normalizeStatus(joinTerms(embedded, "status"));
        return new MangaPost("/manga/" + slugValue + "/", title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_IKIRU, "Ikiru");
    }

    private int getMangaId(String slug) throws Exception {
        String clean = normalizeMangaSlug(slug);
        Integer cached = ID_CACHE.get(clean);
        if (cached != null && cached > 0) return cached;
        Document document = fetchDetailDocument(clean);
        String id = extractMangaId(document);
        if (!id.isEmpty()) {
            int value = Integer.parseInt(id);
            ID_CACHE.put(clean, value);
            return value;
        }
        MangaPost post = fetchPostBySlugFromApi(clean);
        Integer loaded = ID_CACHE.get(clean);
        if (post != null && loaded != null && loaded > 0) return loaded;
        throw new Exception("ID kosong");
    }

    private ArrayList<MangaChapter> parseChapterSelects(String slug, Document detailDocument) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        try {
            String mangaId = extractMangaId(detailDocument);
            if (mangaId.isEmpty()) mangaId = String.valueOf(getMangaId(slug));
            MangaChapter newest = newestChapter(parseChapters(detailDocument));
            String chapterId = newest == null ? "" : chapterIdFromUrl(newest.slug);
            if (chapterId.isEmpty()) return out;
            HttpUrl url = HttpUrl.parse(base() + "/wp-admin/admin-ajax.php").newBuilder()
                    .addQueryParameter("manga_id", mangaId)
                    .addQueryParameter("chapter_id", chapterId)
                    .addQueryParameter("type", "manga")
                    .addQueryParameter("action", "chapter_selects")
                    .addQueryParameter("loc", "head").build();
            Document document = Jsoup.parseBodyFragment(execute(url.toString(), base() + "/manga/" + slug + "/"), base());
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (Element option : document.select("option[value*=chapter-], a[href*=chapter-]")) {
                String rawUrl = firstNonEmpty(option.attr("value"), option.attr("href"));
                String chapterUrl = withoutDomain(resolveUrl(rawUrl));
                String name = cleanChapterTitle(text(option));
                float index = parseChapterIndex(firstNonEmpty(name, chapterUrl), out.size() + 1);
                if (!chapterUrl.isEmpty() && seen.add(chapterUrl)) out.add(new MangaChapter(chapterUrl, index, name.isEmpty() ? "Chapter " + MangaChapter.formatIndex(index) : name, ""));
            }
        } catch(Exception ignored) { }
        return out;
    }

    private ArrayList<MangaChapter> parseChapters(Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : document.select("[data-chapter-number] a[href*=/chapter-], #chapter-list a[href*=/chapter-], a[href*=/chapter-]:has(time)")) {
            String url = withoutDomain(element.attr("abs:href"));
            String name = cleanChapterTitle(firstNonEmpty(text(element.selectFirst("span")), text(element)));
            Element box = closestWithAttribute(element, "data-chapter-number");
            String chapterNumber = box == null ? "" : box.attr("data-chapter-number").trim();
            if (name.isEmpty() && !chapterNumber.isEmpty()) name = "Chapter " + chapterNumber;
            Element time = element.selectFirst("time");
            if (time == null && element.parent() != null) time = element.parent().selectFirst("time");
            if (time == null && box != null) time = box.selectFirst("time");
            String date = time == null ? "" : firstNonEmpty(time.attr("datetime"), text(time));
            float index = !chapterNumber.isEmpty() ? parseChapterIndex(chapterNumber, out.size() + 1) : parseChapterIndex(name, out.size() + 1);
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, date));
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (document == null) return out;

        Element reader = document.selectFirst("main section[data-image-data], section[data-image-data]");
        if (reader != null) {

            for (Element img : reader.select("img")) {
                String url = image(img);
                addReaderImage(out, seen, url, true);
            }
        } else {
            Elements images = document.select("img[src*=kiru.kyut.dev], img[data-src*=kiru.kyut.dev], img[data-lazy-src*=kiru.kyut.dev], "
                    + "img[src*=cdn.uqni.net], img[data-src*=cdn.uqni.net], img[data-lazy-src*=cdn.uqni.net], "
                    + "img[src*=r2.uqni.net], img[data-src*=r2.uqni.net], img[data-lazy-src*=r2.uqni.net], "
                    + "img[src*=/wp-content/scr/], img[data-src*=/wp-content/scr/], img[data-lazy-src*=/wp-content/scr/]");
            for (Element img : images) {
                String url = image(img);
                addReaderImage(out, seen, url, false);
            }
        }

        Matcher matcher = Pattern.compile("https?://(?:kiru\\.kyut\\.dev/wp-content/scr|(?:cdn|r2)\\.uqni\\.net/images)/[^\"'<>\\s]+", Pattern.CASE_INSENSITIVE).matcher(document.outerHtml());
        while (matcher.find()) addReaderImage(out, seen, matcher.group(), false);

        return out;
    }

    private static void addReaderImage(ArrayList<String> out, LinkedHashSet<String> seen, String rawUrl, boolean trustedReaderContainer) {
        String url = normalizeImageUrl(rawUrl);
        if (!url.startsWith("http")) return;
        if ((trustedReaderContainer || isReaderImage(url)) && seen.add(url)) out.add(url);
    }

    private static boolean isReaderImage(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.contains("kiru.kyut.dev/wp-content/scr/")
                || lower.contains("/wp-content/scr/")
                || lower.contains("cdn.uqni.net/images/")
                || lower.contains("r2.uqni.net/images/")
                || lower.contains("/wp-content/uploads/images/");
    }

    private static Element closestWithAttribute(Element element, String attr) {
        Element current = element;
        while (current != null) {
            if (current.hasAttr(attr)) return current;
            current = current.parent();
        }
        return null;
    }

    private void getDocument(String url, Result<Document> cb) {
        Request req = request(url, base() + "/").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                Document document = Jsoup.parse(body, url);
                MAIN.post(() -> cb.onSuccess(document, false));
            }
        });
    }

    private String execute(String url) throws Exception { return execute(url, base() + "/"); }

    private String execute(String url, String referer) throws Exception {
        Response response = client.newCall(request(url, referer).build()).execute();
        String body = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        return body;
    }

    private String executePost(String url, RequestBody body, String referer) throws Exception {
        Response response = client.newCall(request(url, referer).header("Accept", "*/*").post(body).build()).execute();
        String text = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        return text;
    }

    private Request.Builder request(String url, String referer) {
        return new Request.Builder().url(url)
                .header("Referer", referer == null || referer.trim().isEmpty() ? base() + "/" : referer)
                .header("Origin", base())
                .header("Accept", "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.7,en-US;q=0.6,en;q=0.5")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36");
    }

    private static boolean hasNext(Document document, int page) {
        int current = Math.max(1, page);
        for (Element button : document.select("button[onclick*=addSingularFilter]")) {
            Matcher matcher = Pattern.compile("page[\'\\\"]?\\s*,\\s*[\'\\\"]?(\\d+)", Pattern.CASE_INSENSITIVE).matcher(button.attr("onclick"));
            if (matcher.find()) {
                try { if (Integer.parseInt(matcher.group(1)) > current) return true; } catch(Exception ignored) { }
            }
        }
        for (Element link : document.select("a[href*=the_page], a[href*=\"page=\"]")) {
            String href = link.attr("href");
            Matcher matcher = Pattern.compile("(?:the_page|page)=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(href);
            if (matcher.find()) {
                try { if (Integer.parseInt(matcher.group(1)) > current) return true; } catch(Exception ignored) { }
            }
        }
        return false;
    }

    private static String genreJson(String genre) { return jsonForKey(genre, "genre"); }
    private static String typeJson(String genre) { return jsonForKey(genre, "type"); }
    private static String statusJson(String genre) { return jsonForKey(genre, "status"); }

    private static String jsonForKey(String raw, String wanted) {
        ArrayList<String> values = splitFilterValues(raw, wanted);
        if (values.isEmpty()) return "[]";
        ArrayList<String> quoted = new ArrayList<>();
        for (String id : values) quoted.add("\"" + id.replace("\"", "") + "\"");
        return "[" + android.text.TextUtils.join(",", quoted) + "]";
    }

    private static String joinedFilterValues(String raw, String wanted) {
        return android.text.TextUtils.join(",", splitFilterValues(raw, wanted));
    }

    private static ArrayList<String> splitFilterValues(String raw, String wanted) {
        String value = raw == null ? "" : raw.trim();
        ArrayList<String> values = new ArrayList<>();
        if (value.isEmpty()) return values;
        String[] parts = value.split("\\|");
        for (String part : parts) {
            String item = part == null ? "" : part.trim();
            if (item.isEmpty()) continue;
            String key = "genre";
            String id = item;
            int split = item.indexOf(':');
            if (split > 0 && split < item.length() - 1) {
                key = item.substring(0, split).trim();
                id = item.substring(split + 1).trim();
            }
            if (!wanted.equals(key)) continue;
            id = normalizeSlug(id);
            if (!id.isEmpty()) values.add(id);
        }
        return values;
    }

    private static String orderBy(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s)) return "popular";
        if ("bookmark".equals(s) || "bookmarked".equals(s)) return "bookmarked";
        if ("rating".equals(s) || "rate".equals(s)) return "rating";
        if ("title".equals(s) || "az".equals(s) || "za".equals(s)) return "title";
        return "updated";
    }

    private static String orderDirection(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        return "az".equals(s) ? "asc" : "desc";
    }

    private static String transformJson(String raw) {
        if (raw == null) return "";
        int object = raw.indexOf('{');
        int array = raw.indexOf('[');
        int start;
        if (object < 0) start = array;
        else if (array < 0) start = object;
        else start = Math.min(object, array);
        return start >= 0 ? raw.substring(start) : raw;
    }

    private static String parseCover(JsonObject embedded) {
        JsonArray media = getArray(embedded, "wp:featuredmedia");
        if (media.size() > 0 && media.get(0).isJsonObject()) {
            JsonObject object = media.get(0).getAsJsonObject();
            String source = getString(object, "source_url");
            if (!source.isEmpty()) return source;
            JsonObject sizes = getObject(getObject(object, "media_details"), "sizes");
            for (String key : new String[]{"large", "medium_large", "medium", "thumbnail"}) {
                source = getString(getObject(sizes, key), "source_url");
                if (!source.isEmpty()) return source;
            }
        }
        return "";
    }

    private static String joinTerms(JsonObject embedded, String taxonomy) {
        JsonArray groups = getArray(embedded, "wp:term");
        ArrayList<String> values = new ArrayList<>();
        for (JsonElement groupElement : groups) {
            if (groupElement == null || !groupElement.isJsonArray()) continue;
            JsonArray group = groupElement.getAsJsonArray();
            if (group.size() <= 0 || !group.get(0).isJsonObject()) continue;
            if (!taxonomy.equals(getString(group.get(0).getAsJsonObject(), "taxonomy"))) continue;
            for (JsonElement termElement : group) {
                if (termElement != null && termElement.isJsonObject()) {
                    String name = getString(termElement.getAsJsonObject(), "name");
                    if (!name.isEmpty()) values.add(name);
                }
            }
        }
        return android.text.TextUtils.join(", ", values);
    }

    private static String joinUnique(String first, String second) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : (first + ", " + second).split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) values.add(value);
        }
        return android.text.TextUtils.join(", ", new ArrayList<>(values));
    }

    private static String firstType(String types) {
        String lower = types == null ? "" : types.toLowerCase(Locale.ROOT);
        if (lower.contains("manhwa")) return "Manhwa";
        if (lower.contains("manhua")) return "Manhua";
        if (lower.contains("manga")) return "Manga";
        return "Manga";
    }

    private static String normalizeStatus(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.contains("ongoing")) return "Ongoing";
        if (lower.contains("completed")) return "Completed";
        if (lower.contains("cancelled")) return "Cancelled";
        if (lower.contains("hiatus")) return "On Hiatus";
        return value == null ? "" : value.trim();
    }

    private static String toAbsolute(String url) { return resolveUrl(url); }

    private static String resolveUrl(String url) {
        if (url == null || url.trim().isEmpty()) return base();
        String value = url.trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        try {
            HttpUrl baseUrl = HttpUrl.parse(base().endsWith("/") ? base() : base() + "/");
            HttpUrl resolved = baseUrl == null ? null : baseUrl.resolve(value);
            if (resolved != null) return resolved.toString();
        } catch(Exception ignored) { }
        if (!value.startsWith("/")) value = "/" + value;
        return base() + value;
    }

    private static String withoutDomain(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("//")) value = "https:" + value;
        try {
            HttpUrl parsed = HttpUrl.parse(value.startsWith("http") ? value : resolveUrl(value));
            if (parsed != null) {
                String path = parsed.encodedPath();
                String query = parsed.encodedQuery();
                return query == null || query.isEmpty() ? path : path + "?" + query;
            }
        } catch(Exception ignored) { }
        return value.replace(base(), "");
    }

    private static String extractMangaSlug(String url) { return normalizeMangaSlug(url); }

    private static String normalizeMangaSlug(String value) {
        if (value == null) return "";
        String v = value.trim();
        try {
            HttpUrl parsed = HttpUrl.parse(v.startsWith("http") || v.startsWith("//") ? (v.startsWith("//") ? "https:" + v : v) : resolveUrl(v));
            if (parsed != null && parsed.pathSegments().size() >= 2 && "manga".equals(parsed.pathSegments().get(0))) return parsed.pathSegments().get(1);
        } catch(Exception ignored) { }
        v = v.replace(base(), "").replace("/manga/", "");
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        return v.trim();
    }

    private static String image(Element element) {
        if (element == null) return "";
        String[] attrs = {"data-lazy-src", "data-src", "data-original", "data-cfsrc", "src"};
        for (String attr : attrs) {
            String value = element.attr(attr);
            String resolved = resolveCandidateImage(value);
            if (!resolved.isEmpty()) return resolved;
        }
        String srcset = firstNonEmpty(element.attr("data-srcset"), element.attr("srcset"));
        if (!srcset.isEmpty()) {
            for (String candidate : srcset.split(",")) {
                String value = candidate.trim().split("\\s+", 2)[0];
                String resolved = resolveCandidateImage(value);
                if (!resolved.isEmpty()) return resolved;
            }
        }
        return "";
    }

    private static String resolveCandidateImage(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.isEmpty() || clean.startsWith("data:")) return "";
        String resolved = resolveUrl(clean);
        resolved = normalizeImageUrl(resolved);
        return resolved.startsWith("http") ? resolved : "";
    }

    private static String normalizeImageUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (value.startsWith("http://") && value.toLowerCase(Locale.ROOT).contains(".ikiru.wtf/")) {
            return "https://" + value.substring("http://".length());
        }
        return value;
    }

    private static String text(Element element) { return element == null ? "" : Parser.unescapeEntities(element.text(), false).trim().replaceAll("\\s+", " "); }

    private static String cleanTitle(String value) {
        String out = value == null ? "" : Parser.unescapeEntities(value, false).trim().replaceAll("\\s+", " ");
        out = out.replaceFirst("(?i)\\s+Bahasa Indonesia\\s*-\\s*Ikiru$", "");
        out = out.replaceFirst("(?i)\\s*-\\s*Ikiru$", "");
        return out.trim();
    }

    private static String cleanChapterTitle(String value) {
        String out = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile("(?i)(chapter\\s+[0-9]+(?:[.,][0-9]+)?)").matcher(out);
        if (matcher.find()) return matcher.group(1).replace(",", ".");
        return out;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String longestParagraph(Element root) {
        if (root == null) return "";
        String best = "";
        for (Element p : root.select("p")) {
            String txt = text(p);
            String lower = txt.toLowerCase(Locale.ROOT);
            if (txt.length() > best.length() && txt.length() > 24 && !lower.startsWith("chapter") && !lower.equals("ongoing") && !lower.equals("completed")) best = txt;
        }
        return best;
    }

    private static String statusFromText(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        boolean ongoing = java.util.regex.Pattern.compile("\\bongoing\\b").matcher(lower).find();
        boolean completed = java.util.regex.Pattern.compile("\\bcompleted\\b").matcher(lower).find();
        boolean hiatus = java.util.regex.Pattern.compile("\\bhiatus\\b").matcher(lower).find();
        boolean cancelled = java.util.regex.Pattern.compile("\\bcancelled\\b").matcher(lower).find();
        int hits = (ongoing ? 1 : 0) + (completed ? 1 : 0) + (hiatus ? 1 : 0) + (cancelled ? 1 : 0);
        if (hits != 1) return "";
        return normalizeStatus(ongoing ? "ongoing" : completed ? "completed" : hiatus ? "hiatus" : "cancelled");
    }

    private static String typeFromCard(Element card) {
        if (card == null) return "Manga";
        String html = card.outerHtml().toLowerCase(Locale.ROOT);
        if (html.contains("manhwa")) return "Manhwa";
        if (html.contains("manhua")) return "Manhua";
        if (html.contains("manga")) return "Manga";
        return "Manga";
    }

    private static String extractTypeFromDetail(Document document, String genre) {
        String type = firstType(joinUnique(genre, extractField(document, "Type")));
        if (!"Manga".equals(type)) return type;
        String html = document == null ? "" : document.outerHtml().toLowerCase(Locale.ROOT);
        if (html.contains("manhwa")) return "Manhwa";
        if (html.contains("manhua")) return "Manhua";
        return type;
    }

    private static String extractField(Document document, String label) {
        if (document == null || label == null || label.isEmpty()) return "";
        for (Element element : document.select("h4, h5, span, dt, div")) {
            if (!label.equalsIgnoreCase(text(element))) continue;
            Element current = element;
            for (int depth = 0; depth < 3 && current != null; depth++) {
                Element sibling = current.nextElementSibling();
                if (sibling != null) {
                    String siblingText = text(sibling);
                    if (looksLikeFieldValue(siblingText)) return siblingText;
                }
                Element row = current.parent();
                if (row != null) {
                    String value = text(row).replaceFirst("(?i)^" + Pattern.quote(label) + "\\s*:?\\s*", "").trim();
                    if (looksLikeFieldValue(value)) return value;
                }
                current = row;
            }
        }
        return "";
    }

    private static boolean looksLikeFieldValue(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 120) return false;
        return !v.equals("-") && !v.equalsIgnoreCase("Type") && !v.equalsIgnoreCase("Status") && !v.equalsIgnoreCase("Author") && !v.equalsIgnoreCase("Artist");
    }

    private static String extractGenres(Document document) {
        if (document == null) return "";
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Element a : document.select("a[href*=/genre/], a[rel=tag]")) {
            String value = text(a);
            if (!value.isEmpty()) values.add(value);
        }
        return android.text.TextUtils.join(", ", new ArrayList<>(values));
    }

    private static JsonObject detailJson(Document document) {
        if (document == null) return new JsonObject();
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                JsonElement root = JsonParser.parseString(script.html());
                JsonObject object = findComicObject(root);
                if (object != null) return object;
            } catch(Exception ignored) { }
        }
        return new JsonObject();
    }

    private static JsonObject findComicObject(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (looksLikeMangaObject(object)) return object;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonObject found = findComicObject(entry.getValue());
                if (found != null) return found;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                JsonObject found = findComicObject(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean looksLikeMangaObject(JsonObject object) {
        String type = readJsonString(object, "@type").toLowerCase(Locale.ROOT);
        if (type.contains("comic") || type.contains("book")) return true;
        if (object.has("headline") && object.has("chapter") && object.has("image")) return true;
        if (object.has("numberOfPages") && object.has("creativeWorkStatus")) return true;
        return false;
    }

    private static String readJsonString(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        JsonElement element = object.get(name);
        try {
            if (element.isJsonArray()) {
                ArrayList<String> values = new ArrayList<>();
                for (JsonElement child : element.getAsJsonArray()) if (child != null && !child.isJsonNull()) values.add(child.getAsString());
                return android.text.TextUtils.join(", ", values).trim();
            }
            return element.getAsString().trim();
        } catch(Exception ignored) { return ""; }
    }

    private static int readJsonInt(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
        try { return object.get(name).getAsInt(); } catch(Exception ignored) { return fallback; }
    }

    private static String readJsonStringList(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        JsonElement element = object.get(name);
        ArrayList<String> values = new ArrayList<>();
        try {
            if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) if (child != null && !child.isJsonNull()) values.add(child.getAsString().trim());
            else values.add(element.getAsString().trim());
        } catch(Exception ignored) { }
        return android.text.TextUtils.join(", ", values);
    }

    private static String readJsonImage(JsonObject object) {
        if (object == null || !object.has("image")) return "";
        JsonElement element = object.get("image");
        try {
            if (element.isJsonObject()) return firstNonEmpty(getString(element.getAsJsonObject(), "url"), getString(element.getAsJsonObject(), "contentUrl"));
            if (element.isJsonArray() && element.getAsJsonArray().size() > 0) {
                JsonElement first = element.getAsJsonArray().get(0);
                if (first.isJsonObject()) return firstNonEmpty(getString(first.getAsJsonObject(), "url"), getString(first.getAsJsonObject(), "contentUrl"));
                return first.getAsString().trim();
            }
            return element.getAsString().trim();
        } catch(Exception ignored) { return ""; }
    }

    private static String readJsonAuthor(JsonObject object) {
        if (object == null || !object.has("author")) return "";
        JsonElement element = object.get("author");
        ArrayList<String> values = new ArrayList<>();
        try {
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    if (child == null || child.isJsonNull()) continue;
                    if (child.isJsonObject()) values.add(getString(child.getAsJsonObject(), "name"));
                    else values.add(child.getAsString());
                }
            } else if (element.isJsonObject()) values.add(getString(element.getAsJsonObject(), "name"));
            else values.add(element.getAsString());
        } catch(Exception ignored) { }
        return android.text.TextUtils.join(", ", values).trim();
    }

    private static String extractMangaId(Document document) {
        if (document == null) return "";
        Matcher matcher = Pattern.compile("manga_id[=:'\\\"]+([0-9]+)", Pattern.CASE_INSENSITIVE).matcher(document.outerHtml());
        if (matcher.find()) return matcher.group(1);
        matcher = Pattern.compile("postid-([0-9]+)", Pattern.CASE_INSENSITIVE).matcher(document.outerHtml());
        if (matcher.find()) return matcher.group(1);
        return "";
    }

    private static String chapterIdFromUrl(String url) {
        if (url == null) return "";
        Matcher matcher = Pattern.compile("chapter-[^./]+\\.([0-9]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : "";
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

    private static String normalizeSlug(String value) {
        if (value == null) return "";
        String out = value.trim().toLowerCase(Locale.ROOT);
        out = out.replace(".", "");
        out = out.replaceAll("[^a-z0-9]+", "-");
        out = out.replaceAll("^-+", "").replaceAll("-+$", "");
        return out;
    }

    private static ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        addGenre(out, "Action", "action"); addGenre(out, "Adventure", "adventure"); addGenre(out, "Comedy", "comedy"); addGenre(out, "Drama", "drama"); addGenre(out, "Fantasy", "fantasy"); addGenre(out, "Horror", "horror"); addGenre(out, "Isekai", "isekai"); addGenre(out, "Romance", "romance"); addGenre(out, "School Life", "school-life"); addGenre(out, "Slice of Life", "slice-of-life"); addGenre(out, "Supernatural", "supernatural"); addGenre(out, "Manga", "type:manga"); addGenre(out, "Manhwa", "type:manhwa"); addGenre(out, "Manhua", "type:manhua"); addGenre(out, "Ongoing", "status:ongoing"); addGenre(out, "Completed", "status:completed");
        return out;
    }

    private static void addGenre(ArrayList<GenreItem> out, String title, String value) { out.add(new GenreItem(title, value.contains(":") ? value : "genre:" + value)); }

    private static JsonObject getObject(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) return new JsonObject();
        return object.getAsJsonObject(name);
    }

    private static JsonArray getArray(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(name);
    }

    private static String getString(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try { return object.get(name).getAsString().trim(); } catch(Exception e) { return ""; }
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
        try { return object.get(name).getAsInt(); } catch(Exception e) { return fallback; }
    }
}
