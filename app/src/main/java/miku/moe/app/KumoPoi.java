package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
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
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class KumoPoi extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KUMOPOI); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final String SITE = "https://beta.kumopoi.com";
    private static final String API = "https://api.kumopoi.com/api/v1";
    private static final String CDN = "https://kumo.gorae.my.id/";
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SecureRandom SIGNATURE_RANDOM = new SecureRandom();
    private static final String SIGNATURE_SECRET = "vtm6RLiSyKmWd1YpZtkt5ue4oPdYdmzW0ZgxDgyBNEM=";
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(32, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, Boolean> NEXT_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "KumoPoi"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            String apiUrl = buildApiListUrl(page, sort, query, genre);
            String key = apiUrl;
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { Boolean cachedNext = NEXT_CACHE.get(key); cb.onSuccess(new ArrayList<>(cached), cachedNext != null && cachedNext); return; }
            getJson(apiUrl, new Result<String>() {
                @Override public void onSuccess(String json, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseApiList(json);
                            boolean hasNext = hasApiNext(json);
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            NEXT_CACHE.put(key, hasNext);
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch(Exception e) {
                            listFallback(page, sort, query, genre, cb);
                        }
                    });
                }
                @Override public void onError(String message) { listFallback(page, sort, query, genre, cb); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getJson(API + "/genres", new Result<String>() {
            @Override public void onSuccess(String json, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseApiGenres(json);
                        if (out.isEmpty()) {
                            MangaCoroutines.main(() -> loadSiteGenres(cb));
                            return;
                        }
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> loadSiteGenres(cb)); }
                });
            }
            @Override public void onError(String message) { loadSiteGenres(cb); }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost post : list) if (needsEnrichment(post, loadChapter, loadType)) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost post : list) {
            if (!needsEnrichment(post, loadChapter, loadType)) continue;
            boolean needType = loadType && empty(post.typeLabel);
            boolean needChapter = loadChapter && empty(post.latestChapter);
            if (needType) {
                detail(post.slug, new Result<MangaPost>() {
                    @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                        if (detail != null) {
                            if (!empty(detail.typeLabel)) post.typeLabel = detail.typeLabel;
                            if (!empty(detail.genre)) post.genre = detail.genre;
                            if (!empty(detail.status)) post.status = detail.status;
                        }
                        if (needChapter) enrichChapter(post, remaining, done); else finishEnrichment(remaining, done);
                    }
                    @Override public void onError(String message) { if (needChapter) enrichChapter(post, remaining, done); else finishEnrichment(remaining, done); }
                });
            } else if (needChapter) {
                enrichChapter(post, remaining, done);
            }
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        String series = seriesSlug(clean);
        if (empty(series)) { cb.onError("Detail KumoPoi tidak ditemukan"); return; }
        getSiteText(siteComicUrl(series), new Result<String>() {
            @Override public void onSuccess(String html, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseSiteDetail(clean, extractNextFlightText(html));
                        if (post != null) {
                            DETAIL_CACHE.put(clean, post);
                            MangaCoroutines.main(() -> cb.onSuccess(post, false));
                            return;
                        }
                        MangaCoroutines.main(() -> detailRsc(clean, series, html, cb));
                    } catch(Exception e) { MangaCoroutines.main(() -> detailRsc(clean, series, html, cb)); }
                });
            }
            @Override public void onError(String message) { detailRsc(clean, series, "", cb); }
        });
    }

    private void detailRsc(String clean, String series, String html, Result<MangaPost> cb) {
        Request request = new Request.Builder().url(siteComicRscUrl(series)).headers(rscHeaders(series)).build();
        getText(request, new Result<String>() {
            @Override public void onSuccess(String text, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseSiteDetail(clean, text);
                        if (post != null) {
                            DETAIL_CACHE.put(clean, post);
                            MangaCoroutines.main(() -> cb.onSuccess(post, false));
                            return;
                        }
                        MangaCoroutines.main(() -> detailMetadataFallback(clean, html, cb));
                    } catch(Exception e) { MangaCoroutines.main(() -> detailMetadataFallback(clean, html, cb)); }
                });
            }
            @Override public void onError(String message) { detailMetadataFallback(clean, html, cb); }
        });
    }

    private void detailMetadataFallback(String clean, String html, Result<MangaPost> cb) {
        if (empty(html)) { detailFallback(clean, cb); return; }
        MangaCoroutines.io(() -> {
            try {
                MangaPost post = parseSiteHtmlDetail(clean, html);
                if (post != null) {
                    DETAIL_CACHE.put(clean, post);
                    MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    return;
                }
                MangaCoroutines.main(() -> detailFallback(clean, cb));
            } catch(Exception e) { MangaCoroutines.main(() -> detailFallback(clean, cb)); }
        });
    }

    private void detailFallback(String clean, Result<MangaPost> cb) {
        String detailUrl = toAbsolute(clean);
        getDocument(detailUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(clean, document);
                        ArrayList<MangaChapter> chapters = parseChapters(document);
                        post.totalChapters = chapters.size();
                        DETAIL_CACHE.put(clean, post);
                        if (!chapters.isEmpty()) CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail KumoPoi gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String series = seriesSlug(clean);
        getJson(API + "/comics/" + series + "/chapters?limit=1300", new Result<String>() {
            @Override public void onSuccess(String json, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = parseApiChapters(json);
                        if (!out.isEmpty()) {
                            CHAPTER_CACHE.put(clean, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            return;
                        }
                        chaptersFallback(clean, cb);
                    } catch(Exception e) { chaptersFallback(clean, cb); }
                });
            }
            @Override public void onError(String message) { chaptersFallback(clean, cb); }
        });
    }

    private void chaptersFallback(String clean, Result<ArrayList<MangaChapter>> cb) {
        String detailUrl = toAbsolute(clean);
        getDocument(detailUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = parseChapters(document);
                        if (!out.isEmpty()) {
                            CHAPTER_CACHE.put(clean, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            return;
                        }
                        String seriesId = seriesId(document);
                        MangaCoroutines.main(() -> loadAjaxChapters(clean, detailUrl, seriesId, cb));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter KumoPoi gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String clean = cleanSlug(slug);
        String key = clean + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(clean, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = nearestChapter(chapters, index);
                if (chapter == null || empty(chapter.slug)) { cb.onError("Chapter KumoPoi tidak ditemukan"); return; }
                getJson(API + "/chapters/" + chapter.slug.replace("/api-chapter/", "") + "/pages", new Result<String>() {
                    @Override public void onSuccess(String json, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parseApiPages(json);
                                if (out.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman KumoPoi kosong")); return; }
                                String chapterUrl = siteComicUrl(seriesSlug(clean)) + "/chapter/" + chapter.slug.replace("/api-chapter/", "");
                                for (String pageUrl : out) MangaImageLoader.registerImageReferer(pageUrl, chapterUrl);
                                PAGE_CACHE.put(key, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman KumoPoi gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String buildApiListUrl(int page, String sort, String query, String genre) {
        HttpUrl.Builder builder = HttpUrl.parse(API + "/comics").newBuilder()
                .addQueryParameter("sort", apiSort(sort))
                .addQueryParameter("page", String.valueOf(Math.max(1, page)))
                .addQueryParameter("limit", "24")
                .addQueryParameter("excludeAdult", "true");
        if (!empty(query)) builder.addQueryParameter("search", query.trim());
        String g = filterValue(genre, "genre");
        if (!empty(g)) builder.addQueryParameter("genre", g);
        String type = filterValue(genre, "type");
        if (!empty(type)) builder.addQueryParameter("type", type.toUpperCase(Locale.ROOT));
        String status = filterValue(genre, "status");
        if (!empty(status)) builder.addQueryParameter("status", apiStatus(status));
        if ("project".equalsIgnoreCase(sort)) builder.addQueryParameter("flag", "PROJECT");
        return builder.build().toString();
    }

    private String apiSort(String sort) {
        String value = sort == null ? "latest" : sort.toLowerCase(Locale.ROOT);
        if (value.contains("popular") || value.contains("trend")) return "popular";
        if (value.equals("az") || value.equals("a-z")) return "az";
        if (value.equals("za") || value.equals("z-a")) return "za";
        if (value.equals("oldest") || value.equals("old")) return "oldest";
        return "latest";
    }

    private String apiStatus(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(value) || "COMPLETE".equals(value)) return "END";
        if ("HIATUS".equals(value)) return "END_SEASON";
        return value;
    }

    private ArrayList<MangaPost> parseApiList(String json) throws Exception {
        ArrayList<MangaPost> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONObject data = root.optJSONObject("data");
        JSONArray array = data != null ? data.optJSONArray("data") : root.optJSONArray("data");
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            out.add(apiPost(item));
        }
        return out;
    }

    private MangaPost parseApiDetail(String slug, String json) throws Exception {
        ArrayList<MangaPost> items = parseApiList(json);
        for (MangaPost item : items) if (cleanSlug(item.slug).equals(cleanSlug(slug))) return item;
        return items.isEmpty() ? null : items.get(0);
    }

    private MangaPost apiPost(JSONObject item) throws Exception {
        String slug = item.optString("slug");
        String title = item.optString("title");
        String cover = cdnUrl(item.optString("cover"));
        String type = formatType(item.optString("type"));
        String status = item.optString("status");
        ArrayList<String> genres = new ArrayList<>();
        JSONArray gs = item.optJSONArray("genres");
        if (gs != null) for (int i = 0; i < gs.length(); i++) genres.add(gs.getJSONObject(i).optString("name"));
        String latest = "";
        String latestDate = "";
        JSONArray latestChapters = item.optJSONArray("latestChapters");
        if (latestChapters != null && latestChapters.length() > 0) {
            JSONObject chapter = latestChapters.optJSONObject(0);
            if (chapter != null) {
                String number = chapter.optString("number");
                if (!empty(number)) latest = "Chapter " + number;
                latestDate = chapter.optString("publishedAt");
            }
        }
        return new MangaPost("/comic/" + slug, title, cover, "", status, "", String.join(", ", genres), type, latest, latestDate).withSource(MangaSettingsManager.MANGA_SOURCE_KUMOPOI, "KumoPoi");
    }

    private ArrayList<MangaChapter> parseApiChapters(String json) throws Exception {
        JSONArray array = new JSONObject(json).getJSONObject("data").getJSONArray("chapters");
        return parseChapterArray(array);
    }

    private ArrayList<String> parseApiPages(String json) throws Exception {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JSONObject data = new JSONObject(json).getJSONObject("data");
        if (data.optBoolean("locked", false)) return out;
        JSONArray pages = data.getJSONArray("pages");
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            String token = page.optString("token");
            if (empty(token)) continue;
            String mode = page.optString("mode");
            String value = "r2".equalsIgnoreCase(mode) ? chapterDeliveryUrl(token) : mediaSignedUrl(token);
            addPage(out, seen, value);
        }
        return out;
    }

    private boolean hasApiNext(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            JSONObject p = data == null ? null : data.optJSONObject("meta");
            if (p == null) p = root.optJSONObject("pagination");
            if (p == null) return false;
            return p.optInt("page") < p.optInt("totalPages");
        } catch(Exception e) { return false; }
    }

    private void getJson(String url, Result<String> cb) {
        Request request = new Request.Builder().url(url).headers(apiHeaders(url)).build();
        getText(request, cb);
    }

    private void getSiteText(String url, Result<String> cb) {
        Request request = new Request.Builder().url(url).headers(siteHeaders()).build();
        getText(request, cb);
    }

    private void getText(Request request, Result<String> cb) {
        CloudflareHelper.enqueue(client, request, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code() + " KumoPoi")); return; }
                MAIN.post(() -> cb.onSuccess(body, false));
            }
        });
    }

    private Headers apiHeaders(String url) {
        Headers.Builder builder = new Headers.Builder()
                .add("Accept", "application/json, text/plain, */*")
                .add("Origin", SITE)
                .add("Referer", SITE + "/")
                .add("Accept-Language", "id-ID,id;q=0.8")
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36")
                .add("Sec-Fetch-Site", "same-site")
                .add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Dest", "empty");
        if (url != null && url.contains("/pages") && !url.contains("/admin/")) addPageSignature(builder, url);
        return builder.build();
    }

    private void addPageSignature(Headers.Builder builder, String url) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
            String nonce = randomBase36(8) + randomBase36(8);
            HttpUrl parsed = HttpUrl.parse(url);
            String path = parsed == null ? url : parsed.encodedPath();
            String payload = "GET:" + path + ":" + timestamp + ":" + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNATURE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
                StringBuilder signature = new StringBuilder(digest.length * 2);
                for (byte value : digest) signature.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            builder.add("x-app-timestamp", timestamp)
                    .add("x-app-nonce", nonce)
                    .add("x-app-signature", signature.toString());
        } catch (Exception ignored) { }
    }

    private String randomBase36(int length) {
        StringBuilder out = new StringBuilder(length);
        while (out.length() < length) out.append(Long.toString(SIGNATURE_RANDOM.nextLong() & Long.MAX_VALUE, 36));
        return out.substring(0, length);
    }

    private Headers siteHeaders() {
        return new Headers.Builder()
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Referer", SITE + "/comics")
                .add("Accept-Language", "id-ID,id;q=0.8")
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Dest", "document")
                .build();
    }

    private Headers rscHeaders(String series) {
        return new Headers.Builder()
                .add("RSC", "1")
                .add("Accept", "*/*")
                .add("Referer", SITE + "/")
                .add("Next-Router-State-Tree", encodedRscState(series))
                .add("Accept-Language", "id-ID,id;q=0.8")
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Dest", "empty")
                .build();
    }

    private String encodedRscState(String series) {
        String state = "[\"\",{\"children\":[\"(main)\",{\"children\":[\"comic\",{\"children\":[[\"slug\"," + JSONObject.quote(series) + ",\"d\"],{\"children\":[\"__PAGE__\",{},null,null]},null,null]},null,\"refetch\"]},null,null]},null,null]";
        HttpUrl url = HttpUrl.parse(SITE + "/").newBuilder().addQueryParameter("state", state).build();
        String query = url.encodedQuery();
        return query == null || !query.startsWith("state=") ? state : query.substring(6);
    }

    private String siteComicUrl(String series) {
        return HttpUrl.parse(SITE + "/").newBuilder().addPathSegment("comic").addPathSegment(series).build().toString();
    }

    private String siteComicRscUrl(String series) {
        return HttpUrl.parse(SITE + "/").newBuilder().addPathSegment("comic").addPathSegment(series).addQueryParameter("_rsc", "1").build().toString();
    }

    private String cdnUrl(String path) {
        if (empty(path)) return "";
        String value = path.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            HttpUrl parsed = HttpUrl.parse(value);
            return parsed == null ? value : parsed.toString();
        }
        while (value.startsWith("/")) value = value.substring(1);
        return HttpUrl.parse(CDN).newBuilder().addPathSegments(value).build().toString();
    }

    private String chapterDeliveryUrl(String token) {
        return HttpUrl.parse(API + "/media/chapter/deliver").newBuilder().addQueryParameter("token", token).build().toString();
    }

    private String mediaSignedUrl(String token) {
        return HttpUrl.parse(API + "/media/signed").newBuilder().addQueryParameter("token", token).build().toString();
    }

    private MangaPost parseSiteDetail(String clean, String text) throws Exception {
        JSONObject comic = extractComicObject(text, seriesSlug(clean));
        if (comic == null) return null;
        String title = comic.optString("title");
        if (empty(title)) return null;
        String cover = cdnUrl(comic.optString("cover"));
        String author = comic.optString("author");
        String artist = comic.optString("artist");
        String status = detailStatus(comic.optString("status"), comic.optString("endSeasonLabel"));
        String synopsis = comic.optString("description");
        String type = formatType(comic.optString("type"));
        String genres = genreNames(comic.optJSONArray("genres"));
        MangaPost post = new MangaPost(clean, title, cover, author, status, synopsis, genres, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KUMOPOI, "KumoPoi");
        ArrayList<String> info = new ArrayList<>();
        appendInfo(info, "Tipe", type);
        appendInfo(info, "Author", author);
        appendInfo(info, "Artist", artist);
        appendInfo(info, "Status", status);
        int year = comic.optInt("year", 0);
        if (year > 0) appendInfo(info, "Tahun", String.valueOf(year));
        appendInfo(info, "Alternative", joinJsonStrings(comic.optJSONArray("altTitles")));
        appendInfo(info, "Views", comic.optString("viewCount"));
        if (comic.has("bookmarkCount")) appendInfo(info, "Bookmark", String.valueOf(comic.optInt("bookmarkCount", 0)));
        post.info = joinInfoRows(info);
        JSONArray chapterArray = comic.optJSONArray("chapters");
        if (chapterArray != null) {
            ArrayList<MangaChapter> chapters = parseChapterArray(chapterArray);
            post.totalChapters = chapters.size();
            if (!chapters.isEmpty()) {
                CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                MangaChapter newest = newestChapter(chapters);
                if (newest != null) {
                    post.latestChapter = empty(newest.title) ? "Chapter " + MangaChapter.formatIndex(newest.index) : newest.title;
                    post.latestChapterDate = newest.date == null ? "" : newest.date;
                }
            }
        }
        return post;
    }

    private MangaPost parseSiteHtmlDetail(String clean, String html) throws Exception {
        String flight = extractNextFlightText(html);
        MangaPost post = parseSiteDetail(clean, flight);
        if (post != null) return post;
        post = parseJsonLdDetail(clean, html);
        if (post != null) return post;
        return parseMetaDetail(clean, html);
    }

    private MangaPost parseJsonLdDetail(String clean, String html) {
        if (empty(html)) return null;
        try {
            Document document = Jsoup.parse(html, siteComicUrl(seriesSlug(clean)));
            for (Element script : document.select("script[type=application/ld+json]")) {
                String raw = firstNonEmpty(script.data(), script.html());
                JSONObject data = jsonLdBook(raw);
                if (data == null) continue;
                String title = data.optString("name");
                String cover = jsonLdImage(data.opt("image"));
                String synopsis = data.optString("description");
                if (empty(title) || (empty(cover) && empty(synopsis))) continue;
                String author = jsonLdPerson(data.opt("author"));
                String genres = jsonLdValues(data.opt("genre"));
                String alternative = jsonLdValues(data.opt("alternateName"));
                String language = data.optString("inLanguage");
                String type = typeFromLanguage(language);
                String status = statusFromHtml(document);
                MangaPost post = new MangaPost(clean, title, cover, author, status, synopsis, genres, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KUMOPOI, "KumoPoi");
                int total = data.optInt("numberOfPages", 0);
                if (total > 0) post.totalChapters = total;
                ArrayList<String> info = new ArrayList<>();
                appendInfo(info, "Tipe", type);
                appendInfo(info, "Author", author);
                appendInfo(info, "Status", status);
                appendInfo(info, "Alternative", alternative);
                if (total > 0) appendInfo(info, "Chapter", String.valueOf(total));
                post.info = joinInfoRows(info);
                return post;
            }
        } catch(Exception ignored) { }
        return null;
    }

    private MangaPost parseMetaDetail(String clean, String html) {
        if (empty(html)) return null;
        try {
            Document document = Jsoup.parse(html, siteComicUrl(seriesSlug(clean)));
            String title = firstNonEmpty(metaContent(document, "meta[property=\"og:title\"]"), text(document.selectFirst("h1")));
            String cover = firstNonEmpty(metaContent(document, "meta[property=\"og:image\"]"), image(document.selectFirst("aside img, img[alt]")));
            String synopsis = metaContent(document, "meta[property=\"og:description\"]");
            if (empty(title) || (empty(cover) && empty(synopsis))) return null;
            String status = statusFromHtml(document);
            MangaPost post = new MangaPost(clean, title, cover, "", status, synopsis, "", "", "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KUMOPOI, "KumoPoi");
            ArrayList<String> info = new ArrayList<>();
            appendInfo(info, "Status", status);
            post.info = joinInfoRows(info);
            return post;
        } catch(Exception ignored) { return null; }
    }

    private JSONObject jsonLdBook(String raw) {
        if (empty(raw)) return null;
        String value = raw.trim();
        try {
            if (value.startsWith("[")) {
                JSONArray array = new JSONArray(value);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    JSONObject book = jsonLdBookObject(item);
                    if (book != null) return book;
                }
                return null;
            }
            return jsonLdBookObject(new JSONObject(value));
        } catch(Exception e) { return null; }
    }

    private JSONObject jsonLdBookObject(JSONObject object) {
        if (object == null) return null;
        if (jsonLdTypeMatches(object.opt("@type"), "Book")) return object;
        JSONArray graph = object.optJSONArray("@graph");
        if (graph != null) {
            for (int i = 0; i < graph.length(); i++) {
                JSONObject item = graph.optJSONObject(i);
                if (item != null && jsonLdTypeMatches(item.opt("@type"), "Book")) return item;
            }
        }
        return null;
    }

    private boolean jsonLdTypeMatches(Object value, String expected) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (expected.equalsIgnoreCase(array.optString(i))) return true;
            return false;
        }
        return value != null && expected.equalsIgnoreCase(String.valueOf(value));
    }

    private String jsonLdImage(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            return firstNonEmpty(object.optString("url"), object.optString("contentUrl"));
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String image = jsonLdImage(array.opt(i));
                if (!empty(image)) return image;
            }
            return "";
        }
        return value == null || JSONObject.NULL.equals(value) ? "" : String.valueOf(value);
    }

    private String jsonLdPerson(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            return firstNonEmpty(object.optString("name"), object.optString("alternateName"));
        }
        return jsonLdValues(value);
    }

    private String jsonLdValues(Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                String parsed = item instanceof JSONObject ? firstNonEmpty(((JSONObject) item).optString("name"), ((JSONObject) item).optString("value")) : String.valueOf(item);
                if (!empty(parsed) && !"null".equalsIgnoreCase(parsed)) values.add(parsed);
            }
            return String.join(", ", values);
        }
        if (value instanceof JSONObject) return firstNonEmpty(((JSONObject) value).optString("name"), ((JSONObject) value).optString("value"));
        return value == null || JSONObject.NULL.equals(value) ? "" : String.valueOf(value);
    }

    private String typeFromLanguage(String language) {
        String value = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("ko") || value.startsWith("kr")) return "Manhwa";
        if (value.startsWith("ja") || value.startsWith("jp")) return "Manga";
        if (value.startsWith("zh") || value.startsWith("cn")) return "Manhua";
        return "";
    }

    private String statusFromHtml(Document document) {
        if (document == null) return "";
        for (Element element : document.select("span")) {
            String value = text(element);
            String lower = value.toLowerCase(Locale.ROOT);
            if ("berlanjut".equals(lower) || "ongoing".equals(lower)) return "Berlanjut";
            if ("tamat".equals(lower) || "completed".equals(lower) || "complete".equals(lower) || "end".equals(lower)) return "Tamat";
            if ("akhir season".equals(lower) || "end season".equals(lower) || "end_season".equals(lower)) return "Akhir Season";
            if ("drop".equals(lower) || "dropped".equals(lower)) return "Drop";
        }
        return "";
    }

    private String metaContent(Document document, String selector) {
        Element element = document == null ? null : document.selectFirst(selector);
        return element == null ? "" : clean(element.attr("content"));
    }

    private JSONObject extractComicObject(String text, String expectedSlug) {
        if (empty(text)) return null;
        String marker = "\"comic\":";
        int from = 0;
        while (true) {
            int index = text.indexOf(marker, from);
            if (index < 0) return null;
            int start = text.indexOf('{', index + marker.length());
            if (start < 0) return null;
            String value = balancedJsonFrom(text, start, '{', '}');
            if (!empty(value)) {
                try {
                    JSONObject comic = new JSONObject(value);
                    String slug = comic.optString("slug");
                    String title = comic.optString("title");
                    if (!empty(title) && !empty(slug) && (empty(expectedSlug) || expectedSlug.equalsIgnoreCase(slug)) && comic.has("cover") && comic.has("description")) return comic;
                } catch(Exception ignored) { }
            }
            from = index + marker.length();
        }
    }

    private String detailStatus(String raw, String endSeasonLabel) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("ONGOING".equals(value)) return "Berlanjut";
        if ("END_SEASON".equals(value)) return empty(endSeasonLabel) ? "Akhir Season" : endSeasonLabel.trim();
        if ("END".equals(value)) return "Tamat";
        if ("DROP".equals(value)) return "Drop";
        return raw == null ? "" : raw.trim();
    }

    private ArrayList<MangaChapter> parseChapterArray(JSONArray array) throws Exception {
        ArrayList<MangaChapter> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            String rawNumber = item.optString("number");
            if (empty(id) || empty(rawNumber)) continue;
            float number = parseChapterIndex(rawNumber, i + 1);
            String chapterTitle = item.optString("title");
            String display = "Chapter " + rawNumber;
            if (!empty(chapterTitle) && !"null".equalsIgnoreCase(chapterTitle)) display += " - " + chapterTitle;
            out.add(new MangaChapter("/api-chapter/" + id, number, display, item.optString("publishedAt")));
        }
        return out;
    }

    private MangaChapter newestChapter(ArrayList<MangaChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return null;
        MangaChapter newest = chapters.get(0);
        for (MangaChapter chapter : chapters) if (chapter.index > newest.index) newest = chapter;
        return newest;
    }

    private String genreNames(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            String name = value instanceof JSONObject ? ((JSONObject) value).optString("name") : String.valueOf(value);
            if (!empty(name) && !"null".equalsIgnoreCase(name)) values.add(name);
        }
        return String.join(", ", values);
    }

    private String joinJsonStrings(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i);
            if (!empty(value)) values.add(value);
        }
        return String.join(", ", values);
    }

    private String extractNextFlightText(String html) {
        if (empty(html)) return "";
        String marker = "self.__next_f.push([1,";
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int index = html.indexOf(marker, from);
            if (index < 0) break;
            int quote = html.indexOf('"', index + marker.length());
            if (quote < 0) break;
            int end = jsonStringEnd(html, quote);
            if (end < 0) break;
            try {
                JSONArray value = new JSONArray("[" + html.substring(quote, end + 1) + "]");
                out.append(value.optString(0));
            } catch(Exception ignored) { }
            from = end + 1;
        }
        return out.toString();
    }

    private int jsonStringEnd(String text, int startQuote) {
        boolean escaped = false;
        for (int i = startQuote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') return i;
        }
        return -1;
    }

    private JSONObject extractJsonObject(String text, String marker) {
        String value = balancedJson(text, marker, '{', '}');
        if (empty(value)) return null;
        try { return new JSONObject(value); } catch(Exception e) { return null; }
    }

    private JSONArray extractJsonArray(String text, String marker) {
        String value = balancedJson(text, marker, '[', ']');
        if (empty(value)) return null;
        try { return new JSONArray(value); } catch(Exception e) { return null; }
    }

    private String balancedJson(String text, String marker, char open, char close) {
        if (empty(text) || empty(marker)) return "";
        int markerIndex = text.indexOf(marker);
        if (markerIndex < 0) return "";
        int start = text.indexOf(open, markerIndex + marker.length());
        if (start < 0) return "";
        return balancedJsonFrom(text, start, open, close);
    }

    private String balancedJsonFrom(String text, int start, char open, char close) {
        if (empty(text) || start < 0 || start >= text.length() || text.charAt(start) != open) return "";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return "";
    }

    private void loadSiteGenres(Result<ArrayList<GenreItem>> cb) {
        getSiteText(SITE + "/comics", new Result<String>() {
            @Override public void onSuccess(String html, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        String flight = extractNextFlightText(html);
                        JSONArray array = extractJsonArray(firstNonEmpty(flight, html), "\"genres\":");
                        ArrayList<GenreItem> out = parseGenreArray(array);
                        if (out.isEmpty()) {
                            MangaCoroutines.main(() -> cb.onError("Genre KumoPoi gagal dibaca"));
                            return;
                        }
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Genre KumoPoi gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private ArrayList<GenreItem> parseGenreArray(JSONArray array) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) addGenreItem(out, seen, array.optJSONObject(i));
        return out;
    }

    private void addGenreItem(ArrayList<GenreItem> out, LinkedHashSet<String> seen, JSONObject genre) {
        if (genre == null) return;
        String slug = genre.optString("slug");
        String name = genre.optString("name");
        if (!empty(slug) && !empty(name) && seen.add(slug)) out.add(new GenreItem(name, slug));
    }

    private Request buildListRequest(int page, String sort, String query, String genre) {
        int safePage = Math.max(1, page);
        String q = query == null ? "" : query.trim();
        String mode = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            String path = safePage > 1 ? base() + "/page/" + safePage + "/" : base() + "/";
            HttpUrl url = HttpUrl.parse(path).newBuilder().addQueryParameter("s", q).build();
            return new Request.Builder().url(url).headers(headers(false, base() + "/")).build();
        }
        if ("project".equals(mode) || "projects".equals(mode)) {
            String url = safePage > 1 ? base() + "/project/page/" + safePage + "/" : base() + "/project/";
            return new Request.Builder().url(url).headers(headers(false, base() + "/project/")).build();
        }
        String genreValue = filterValue(genre, "genre");
        String typeValue = filterValue(genre, "type");
        String statusValue = filterValue(genre, "status");
        if (statusValue.isEmpty() && ("ongoing".equals(mode) || "completed".equals(mode) || "hiatus".equals(mode))) statusValue = mode;
        boolean filtered = !genreValue.isEmpty() || !typeValue.isEmpty() || !statusValue.isEmpty();
        String order = filtered && ("latest".equals(mode) || "ongoing".equals(mode) || "completed".equals(mode) || "hiatus".equals(mode)) ? "" : orderForSort(mode);
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/manga/").newBuilder();
        if (safePage > 1) builder.addQueryParameter("page", String.valueOf(safePage));
        if (!genreValue.isEmpty()) builder.addQueryParameter("genre[]", genreValue);
        if (filtered) {
            builder.addQueryParameter("status", statusValue);
            builder.addQueryParameter("type", typeValue);
            builder.addQueryParameter("order", order);
        } else if (!order.isEmpty()) {
            builder.addQueryParameter("order", order);
        }
        HttpUrl url = builder.build();
        return new Request.Builder().url(url).headers(headers(false, base() + "/manga/")).build();
    }

    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements entries = document.select(".listupd .bs, .bs");
        for (Element entry : entries) {
            Element link = entry.selectFirst(".bsx > a[href], a[href*=/manga/]");
            if (link == null) continue;
            String href = link.attr("abs:href");
            if (!href.contains("/manga/")) continue;
            String slug = withoutDomain(href);
            String title = firstNonEmpty(link.attr("title"), text(entry.selectFirst(".tt")), link.attr("aria-label"));
            String cover = image(entry.selectFirst("img"));
            String latest = text(entry.selectFirst(".epxs"));
            String date = text(entry.selectFirst(".epxdate"));
            String type = classValue(entry.selectFirst(".type"), "type");
            String status = classValue(entry.selectFirst(".status"), "status");
            if (title.isEmpty() || slug.isEmpty() || !seen.add(slug)) continue;
            out.add(new MangaPost(slug, title, cover, "", status, "", "", formatType(type), latest, date).withSource(MangaSettingsManager.MANGA_SOURCE_KUMOPOI, "KumoPoi"));
        }
        return out;
    }

    private MangaPost parseDetail(String slug, Document document) {
        String title = cleanTitle(text(document.selectFirst("h1.entry-title, h1")));
        String cover = image(document.selectFirst(".info-left .thumb img, .thumb img"));
        String status = infoValue(document, "Status");
        String type = formatType(infoValue(document, "Type"));
        String released = infoValue(document, "Released");
        String author = infoValue(document, "Author");
        String artist = infoValue(document, "Artist");
        String alternative = text(document.selectFirst(".alternative"));
        String genre = joinText(document.select(".mgen a"));
        String synopsis = synopsis(document);
        MangaPost post = new MangaPost(slug, title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KUMOPOI, "KumoPoi");
        ArrayList<String> info = new ArrayList<>();
        appendInfo(info, "Tipe", type);
        appendInfo(info, "Author", author);
        appendInfo(info, "Artist", artist);
        appendInfo(info, "Status", status);
        appendInfo(info, "Released", released);
        appendInfo(info, "Alternative", alternative);
        post.info = joinInfoRows(info);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element item : document.select(".eplister li, #chapterlist li")) {
            Element link = item.selectFirst("a[href]");
            if (link == null) continue;
            String url = withoutDomain(stripQuery(link.attr("abs:href")));
            String name = firstNonEmpty(text(item.selectFirst(".chapternum")), text(link));
            String date = text(item.selectFirst(".chapterdate"));
            float index = parseChapterIndex(firstNonEmpty(item.attr("data-num"), name, url), out.size() + 1);
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, date));
        }
        return out;
    }

    private ArrayList<MangaChapter> parseAjaxChapters(String html, String baseUrl) {
        Document document = Jsoup.parse(html == null ? "" : html, baseUrl);
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element option : document.select("option[value]")) {
            String url = withoutDomain(stripQuery(normalizeUrl(option.attr("value"))));
            String name = text(option);
            float index = parseChapterIndex(firstNonEmpty(name, url), out.size() + 1);
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, ""));
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("#readerarea img");
        if (images.isEmpty()) images = document.select(".chapterbody .entry-content img, .entry-content-single img");
        for (Element img : images) addPage(out, seen, image(img));
        if (out.isEmpty()) {
            Matcher matcher = Pattern.compile("https?://[^\\\"'<>\\s]+\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\\\"'<>\\s]*)?", Pattern.CASE_INSENSITIVE).matcher(document.outerHtml());
            while (matcher.find()) addPage(out, seen, matcher.group());
        }
        return out;
    }

    private ArrayList<GenreItem> parseApiGenres(String json) throws Exception {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("data");
        if (array == null) {
            JSONObject data = root.optJSONObject("data");
            if (data != null) array = data.optJSONArray("data");
        }
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            if (item.has("slug") && item.has("name")) addGenreItem(out, seen, item);
            JSONArray genres = item.optJSONArray("genres");
            if (genres == null) continue;
            for (int j = 0; j < genres.length(); j++) addGenreItem(out, seen, genres.optJSONObject(j));
        }
        return out;
    }
    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element input : document.select("input[name=\"genre[]\"][value]")) {
            String value = input.attr("value").trim();
            Element parent = input.parent();
            Element label = parent == null ? null : parent.selectFirst("label");
            String title = firstNonEmpty(text(label), parent == null ? "" : text(parent));
            if (value.isEmpty() || title.isEmpty() || !seen.add(value)) continue;
            out.add(new GenreItem(title, value));
        }
        return out;
    }

    private void loadAjaxChapters(String slug, String detailUrl, String seriesId, Result<ArrayList<MangaChapter>> cb) {
        if (empty(seriesId)) { cb.onSuccess(new ArrayList<>(), false); return; }
        FormBody body = new FormBody.Builder().add("action", "get_chapters").add("id", seriesId).build();
        Request request = new Request.Builder().url(base() + "/wp-admin/admin-ajax.php").post(body).headers(headers(true, detailUrl)).build();
        CloudflareHelper.enqueue(client, request, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code() + " KumoPoi")); return; }
                try {
                    ArrayList<MangaChapter> out = parseAjaxChapters(responseBody, detailUrl);
                    CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                    MAIN.post(() -> cb.onSuccess(out, false));
                } catch(Exception e) { MAIN.post(() -> cb.onError("Chapter KumoPoi gagal dibaca")); }
            }
        });
    }

    private void enrichChapter(MangaPost post, java.util.concurrent.atomic.AtomicInteger remaining, Runnable done) {
        chapters(post.slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (chapters != null && !chapters.isEmpty()) {
                    MangaChapter newest = chapters.get(0);
                    for (MangaChapter chapter : chapters) if (chapter.index > newest.index) newest = chapter;
                    post.latestChapter = empty(newest.title) ? "Chapter " + MangaChapter.formatIndex(newest.index) : newest.title;
                    post.latestChapterDate = newest.date == null ? "" : newest.date;
                }
                finishEnrichment(remaining, done);
            }
            @Override public void onError(String message) { finishEnrichment(remaining, done); }
        });
    }

    private void finishEnrichment(java.util.concurrent.atomic.AtomicInteger remaining, Runnable done) {
        if (remaining.decrementAndGet() <= 0 && done != null) done.run();
    }

    private boolean needsEnrichment(MangaPost post, boolean loadChapter, boolean loadType) {
        if (post == null || empty(post.slug)) return false;
        return loadChapter && empty(post.latestChapter) || loadType && empty(post.typeLabel);
    }

    private MangaChapter nearestChapter(ArrayList<MangaChapter> chapters, float index) {
        if (chapters == null || chapters.isEmpty()) return null;
        MangaChapter best = chapters.get(0);
        float distance = Math.abs(best.index - index);
        for (MangaChapter chapter : chapters) {
            float current = Math.abs(chapter.index - index);
            if (current < 0.0001f) return chapter;
            if (current < distance) { best = chapter; distance = current; }
        }
        return best;
    }

    private void listFallback(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        getDocument(buildListRequest(page, sort, query, genre), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    ArrayList<MangaPost> out = parseList(document);
                    MangaCoroutines.main(() -> cb.onSuccess(out, hasNextPage(document)));
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void getDocument(String url, Result<Document> cb) {
        String absolute = toAbsolute(url);
        getDocument(new Request.Builder().url(absolute).headers(headers(false, absolute)).build(), cb);
    }

    private void getDocument(Request request, Result<Document> cb) {
        CloudflareHelper.enqueue(client, request, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code() + " KumoPoi")); return; }
                try {
                    Document document = Jsoup.parse(body, request.url().toString());
                    MAIN.post(() -> cb.onSuccess(document, false));
                } catch(Exception e) { MAIN.post(() -> cb.onError("Data KumoPoi gagal dibaca")); }
            }
        });
    }

    private Headers headers(boolean ajax, String referer) {
        Headers.Builder builder = new Headers.Builder()
                .add("Referer", empty(referer) ? base() + "/" : referer)
                .add("Origin", base())
                .add("Accept-Language", "id-ID,id;q=0.8,en-US;q=0.7,en;q=0.6")
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36");
        if (ajax) {
            builder.add("Accept", "*/*");
            builder.add("X-Requested-With", "XMLHttpRequest");
            builder.add("Sec-Fetch-Site", "same-origin");
            builder.add("Sec-Fetch-Mode", "cors");
            builder.add("Sec-Fetch-Dest", "empty");
        } else {
            builder.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            builder.add("Upgrade-Insecure-Requests", "1");
        }
        return builder.build();
    }

    private static boolean hasNextPage(Document document) {
        return document != null && !document.select("a.next.page-numbers, a[rel=next], a.r[href*=\"page=\"]").isEmpty();
    }

    private static String synopsis(Document document) {
        Elements paragraphs = document.select(".info-desc .entry-content p");
        if (!paragraphs.isEmpty()) return joinText(paragraphs);
        return text(document.selectFirst(".info-desc .entry-content, .entry-content-single"));
    }

    private static String infoValue(Document document, String label) {
        for (Element row : document.select(".tsinfo .imptdt")) {
            String own = clean(row.ownText());
            if (!own.toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT))) continue;
            Element value = row.selectFirst("i, a, span");
            String result = text(value);
            if (!result.isEmpty()) return result;
            String all = clean(row.text());
            if (all.length() > own.length()) return clean(all.substring(own.length()));
        }
        return "";
    }

    private static String seriesId(Document document) {
        Element article = document.selectFirst("article[id^=post-]");
        if (article != null) {
            Matcher matcher = Pattern.compile("post-(\\d+)").matcher(article.id());
            if (matcher.find()) return matcher.group(1);
        }
        Matcher matcher = Pattern.compile("(?i)(?:postid|post_id|series_id|manga_id)[^0-9]{0,20}(\\d+)").matcher(document.outerHtml());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String orderForSort(String mode) {
        if ("popular".equals(mode) || "popularity".equals(mode) || "trending".equals(mode) || "views".equals(mode)) return "popular";
        if ("added".equals(mode) || "new".equals(mode) || "latest_added".equals(mode)) return "latest";
        if ("az".equals(mode) || "a-z".equals(mode) || "title".equals(mode)) return "title";
        if ("za".equals(mode) || "z-a".equals(mode) || "titlereverse".equals(mode)) return "titlereverse";
        return "update";
    }

    private static String filterValue(String raw, String key) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String prefix = key + ":";
        for (String part : raw.split("\\|")) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if ("genre".equals(key) && !lower.startsWith("type:") && !lower.startsWith("status:") && !lower.equals("project")) return value;
            if (lower.startsWith(prefix)) return value.substring(prefix.length()).trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static String classValue(Element element, String ignoredClass) {
        if (element == null) return "";
        for (String value : element.classNames()) {
            if (value.equalsIgnoreCase(ignoredClass)) continue;
            return value.replace('-', ' ').trim();
        }
        return text(element);
    }

    private static String cleanTitle(String value) {
        String title = clean(value);
        return title.replaceFirst("(?i)\\s+Bahasa Indonesia\\s*$", "").trim();
    }

    private static String formatType(String value) {
        String clean = clean(value);
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("manhwa")) return "Manhwa";
        if (lower.contains("manhua")) return "Manhua";
        if (lower.contains("manga")) return "Manga";
        if (lower.contains("comic")) return "Comic";
        if (lower.contains("novel")) return "Novel";
        return clean;
    }

    private static void appendInfo(ArrayList<String> rows, String key, String value) {
        String clean = clean(value);
        if (rows == null || clean.isEmpty() || "-".equals(clean)) return;
        rows.add(key + ": " + clean);
    }

    private static String joinInfoRows(ArrayList<String> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (String row : rows) {
            if (empty(row)) continue;
            if (builder.length() > 0) builder.append("||");
            builder.append(row.trim());
        }
        return builder.toString();
    }

    private static void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String url) {
        if (url == null) return;
        String value = normalizeUrl(url);
        if (!value.startsWith("http")) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("logo") || lower.contains("avatar") || lower.contains("favicon") || lower.contains("loading") || lower.contains("placeholder")) return;
        if (seen.add(value)) out.add(value);
    }

    private static String image(Element element) {
        if (element == null) return "";
        String[] attrs = {"abs:data-src", "abs:data-lazy-src", "abs:data-cfsrc", "abs:src", "data-src", "data-lazy-src", "data-cfsrc", "srcset", "data-srcset", "src"};
        for (String attr : attrs) {
            String value = element.attr(attr).trim();
            if (value.isEmpty()) continue;
            if (attr.contains("srcset")) value = firstSrcset(value);
            value = normalizeUrl(value);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String firstSrcset(String value) {
        if (value == null) return "";
        String first = value.split(",")[0].trim();
        int space = first.indexOf(' ');
        return space > 0 ? first.substring(0, space).trim() : first;
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/")) return base() + value;
        return value;
    }

    private static String toAbsolute(String raw) {
        if (raw == null || raw.trim().isEmpty()) return base() + "/";
        String value = raw.trim();
        if (value.startsWith("http")) return value;
        if (!value.startsWith("/")) value = "/" + value;
        return base() + value;
    }

    private static String withoutDomain(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        try {
            HttpUrl url = HttpUrl.parse(value);
            if (url != null) return url.encodedPath();
        } catch(Exception ignored) { }
        return stripQuery(value);
    }

    private static String stripQuery(String value) {
        if (value == null) return "";
        int index = value.indexOf('?');
        return index >= 0 ? value.substring(0, index) : value;
    }

    private static String cleanSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http")) value = withoutDomain(value);
        if (value.isEmpty()) return "";
        if (!value.startsWith("/")) value = "/" + value;
        return stripQuery(value);
    }

    private static String seriesSlug(String raw) {
        String value = cleanSlug(raw);
        if (value.startsWith("/comic/")) value = value.substring(7);
        else if (value.startsWith("/manga/")) value = value.substring(7);
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int slash = value.indexOf('/');
        return slash >= 0 ? value.substring(0, slash) : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String text(Element element) { return element == null ? "" : clean(element.text()); }

    private static String joinText(Elements elements) {
        StringBuilder builder = new StringBuilder();
        if (elements == null) return "";
        for (Element element : elements) {
            String value = text(element);
            if (value.isEmpty()) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (!empty(value)) return value.trim();
        return "";
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    private static float parseChapterIndex(String raw, int fallback) {
        if (raw == null) return fallback;
        Matcher chapterMatcher = Pattern.compile("(?i)(?:chapter|ch|episode|ep)\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(raw);
        if (chapterMatcher.find()) {
            try { return Float.parseFloat(chapterMatcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
        }
        Matcher numberMatcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)").matcher(raw);
        float result = fallback;
        while (numberMatcher.find()) {
            try { result = Float.parseFloat(numberMatcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
        }
        return result;
    }
}
