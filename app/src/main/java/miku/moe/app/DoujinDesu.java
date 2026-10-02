package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DoujinDesu extends KomikcastClient {
    private static final String API_SECRET = "dfdf72051dbfdc7d76889ebd31324e74";
    private static final String DECRYPT_SALT = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2";
    private static final String UA_ANDROID_CHROME_152 = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36";
    private static final String DEVICE_ID = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "_" + Long.toString(System.currentTimeMillis(), 36);
    private static final String DEVICE_NAME = "Chrome on Linux";
    private static final int LIMIT = 24;
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().cache(null).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(96, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, String> CHAPTER_ID_CACHE = new MangaMemoryCache<>(600, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); }
    }

    private static final ArrayList<GenreItem> GENRE_CACHE = new ArrayList<>();
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU); }

    private static String apiBase() { return base() + "/api"; }

    @Override protected String sourceLabel() { return "DoujinDesu"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            DoujinFilterSpec spec = parseFilterSpec(genre);
            String safeQuery = query == null ? "" : query.trim();
            String mode = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim().toLowerCase(Locale.ROOT);
            String apiUrl = mangaUrl(safePage, mode, safeQuery, spec.genre, spec.status, spec.type);
            String url = !safeQuery.isEmpty() ? exploreSearchUrl(safeQuery, safePage, spec.genre, spec.status, spec.type) : apiUrl;
            String cacheKey = url;
            ArrayList<MangaPost> cached = LIST_CACHE.get(cacheKey);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= LIMIT); return; }
            Result<JsonElement> parser = new Result<JsonElement>() {
                @Override public void onSuccess(JsonElement root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            JsonArray items = mangaArray(root);
                            for (JsonElement element : items) {
                                if (element == null || !element.isJsonObject()) continue;
                                MangaPost post = parsePost(element.getAsJsonObject());
                                if (post == null || post.slug == null || post.slug.trim().isEmpty()) continue;
                                if (hasTypeFilter(spec.type) && !matchesType(post, spec.type) && !isMainTypeTab(mode)) continue;
                                if (seen.add(post.slug)) out.add(post);
                            }
                            boolean hasNext = hasNext(root, out.size(), safePage);
                            LIST_CACHE.put(cacheKey, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar DoujinDesu gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            };
            if (!safeQuery.isEmpty()) getExploreJson(url, apiUrl, parser);
            else getJson(url, parser);
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        synchronized (GENRE_CACHE) {
            if (!GENRE_CACHE.isEmpty()) { cb.onSuccess(new ArrayList<>(GENRE_CACHE), false); return; }
        }
        HttpUrl url = HttpUrl.parse(apiBase() + "/taxonomy/genres").newBuilder().addQueryParameter("page", "1").addQueryParameter("search", "").addQueryParameter("limit", "60").build();
        getJson(url.toString(), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenreItems(root);
                        if (out.isEmpty()) { MangaCoroutines.main(() -> getGenresFromLegacyApi(cb)); return; }
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(out); }
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) {
                        ArrayList<GenreItem> fallback = fallbackGenres();
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(fallback); }
                        MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
                    }
                });
            }
            @Override public void onError(String message) { getGenresFromLegacyApi(cb); }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        boolean needs = false;
        for (MangaPost post : list) {
            if (post == null) continue;
            if (post.latestChapter == null || post.latestChapter.trim().isEmpty() || post.typeLabel == null || post.typeLabel.trim().isEmpty()) { needs = true; break; }
        }
        if (!needs) { if (done != null) MangaCoroutines.main(done); return; }
        super.enrichLatest(list, done);
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(apiBase() + "/manga/" + urlSegment(clean), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        if (obj == null) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu kosong")); return; }
                        MangaPost post = parsePost(obj);
                        if (post == null || post.slug.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu kosong")); return; }
                        DETAIL_CACHE.put(clean, post);
                        postViewSilently(apiBase() + "/manga/" + urlSegment(post.slug) + "/view");
                        ArrayList<MangaChapter> chapters = parseChapters(post.slug, obj);
                        if (!chapters.isEmpty()) CHAPTER_CACHE.put(post.slug, chapters);
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { loadDetailFromSearch(clean, cb); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getJson(apiBase() + "/manga/" + urlSegment(clean), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        if (obj == null) { MangaCoroutines.main(() -> cb.onError("Daftar chapter DoujinDesu kosong")); return; }
                        MangaPost post = parsePost(obj);
                        if (post != null && !post.slug.isEmpty()) DETAIL_CACHE.put(clean, post);
                        ArrayList<MangaChapter> out = parseChapters(clean, obj);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { loadChaptersFromSearch(clean, cb); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String clean = cleanSlug(slug);
        String key = clean + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String chapterId = findChapterId(clean, index);
        if (chapterId.isEmpty()) {
            chapters(clean, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) { loadPages(clean, index, cb); }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        loadPages(clean, index, cb);
    }

    private void loadPages(String slug, float index, Result<ArrayList<String>> cb) {
        String chapterId = findChapterId(slug, index);
        if (chapterId.isEmpty()) { cb.onError("ID chapter DoujinDesu tidak ditemukan"); return; }
        String key = slug + ":" + MangaChapter.formatIndex(index);
        getJson(apiBase() + "/chapters/" + urlSegment(chapterId), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
                        if (obj == null) { MangaCoroutines.main(() -> cb.onError("Chapter DoujinDesu kosong")); return; }
                        JsonObject data = getObject(obj, "data");
                        if (data != null) obj = data;
                        ArrayList<String> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonArray urls = chapterContentUrls(obj);
                        for (JsonElement element : urls) {
                            String url = normalizeChapterImageUrl(chapterImageValue(element));
                            if (url.startsWith("http") && seen.add(url)) out.add(url);
                        }
                        if (out.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman DoujinDesu kosong")); return; }
                        postViewSilently(apiBase() + "/chapters/" + urlSegment(chapterId) + "/view");
                        PAGE_CACHE.put(key, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadDetailFromSearch(String slug, Result<MangaPost> cb) {
        String url = mangaSearchUrl(slug, 1);
        getJson(url, new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost found = null;
                        JsonArray items = mangaArray(root);
                        for (JsonElement element : items) {
                            if (element == null || !element.isJsonObject()) continue;
                            MangaPost post = parsePost(element.getAsJsonObject());
                            if (post != null && slug.equalsIgnoreCase(post.slug)) { found = post; break; }
                            if (found == null) found = post;
                        }
                        if (found == null) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu kosong")); return; }
                        DETAIL_CACHE.put(slug, found);
                        MangaPost result = found;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void loadChaptersFromSearch(String slug, Result<ArrayList<MangaChapter>> cb) {
        getJson(mangaSearchUrl(slug, 1), new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = new ArrayList<>();
                        JsonArray items = mangaArray(root);
                        for (JsonElement element : items) {
                            if (element == null || !element.isJsonObject()) continue;
                            JsonObject obj = element.getAsJsonObject();
                            String itemSlug = getString(obj, "slug");
                            if (!slug.equalsIgnoreCase(itemSlug)) continue;
                            out = parseChapters(slug, obj);
                            MangaPost post = parsePost(obj);
                            if (post != null) DETAIL_CACHE.put(slug, post);
                            break;
                        }
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        ArrayList<MangaChapter> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String mangaUrl(int page, String mode, String query, String genre, String status, String filterType) {
        String type = typeForMode(mode, filterType);
        String safeQuery = query == null ? "" : query.trim();
        String sort = sortForMode(mode, filterType);
        if (!safeQuery.isEmpty() && isDefaultSearchSort(mode)) sort = "newest";
        String safeGenre = normalizeGenreQuery(genre);
        String safeStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        HttpUrl.Builder builder = HttpUrl.parse(apiBase() + "/manga").newBuilder();
        builder.addQueryParameter("search", safeQuery);
        builder.addQueryParameter("genre", safeGenre);
        builder.addQueryParameter("status", safeStatus);
        builder.addQueryParameter("type", type);
        builder.addQueryParameter("sort", sort);
        builder.addQueryParameter("limit", String.valueOf(LIMIT));
        builder.addQueryParameter("offset", String.valueOf((Math.max(1, page) - 1) * LIMIT));
        return builder.build().toString();
    }

    private String mangaSearchUrl(String search, int page) {
        HttpUrl.Builder builder = HttpUrl.parse(apiBase() + "/manga").newBuilder();
        String safeSearch = search == null ? "" : search.trim();
        builder.addQueryParameter("search", safeSearch);
        builder.addQueryParameter("genre", "");
        builder.addQueryParameter("status", "");
        builder.addQueryParameter("type", "");
        builder.addQueryParameter("sort", "newest");
        builder.addQueryParameter("limit", String.valueOf(LIMIT));
        builder.addQueryParameter("offset", String.valueOf((Math.max(1, page) - 1) * LIMIT));
        return builder.build().toString();
    }

    private String exploreSearchUrl(String search, int page, String genre, String status, String type) {
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/explore").newBuilder();
        String safeSearch = search == null ? "" : search.trim();
        builder.addQueryParameter("search", safeSearch);
        String safeGenre = normalizeGenreQuery(genre);
        String safeStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        String safeType = normalizeApiType(type);
        if (!safeGenre.isEmpty()) builder.addQueryParameter("genre", safeGenre);
        if (!safeStatus.isEmpty()) builder.addQueryParameter("status", safeStatus);
        if (!safeType.isEmpty()) builder.addQueryParameter("type", safeType);
        int safePage = Math.max(1, page);
        if (safePage > 1) builder.addQueryParameter("page", String.valueOf(safePage));
        return builder.build().toString();
    }

    private String taxonomyUrl(String genre, int page, String sort) {
        return HttpUrl.parse(apiBase() + "/taxonomy/genres/" + urlSegment(genre)).newBuilder().addQueryParameter("page", String.valueOf(Math.max(1, page))).addQueryParameter("sort", sort).addQueryParameter("limit", String.valueOf(LIMIT)).build().toString();
    }

    private static boolean isMainTypeTab(String mode) {
        return "manga".equals(mode) || "manhwa".equals(mode) || "manhua".equals(mode) || "doujinshi".equals(mode);
    }

    private static boolean isPopularMode(String mode) {
        return "popular".equals(mode) || "popularity".equals(mode) || "views".equals(mode) || "rating".equals(mode) || "rate".equals(mode);
    }

    private static boolean isDefaultSearchSort(String mode) {
        String clean = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return clean.isEmpty() || "latest".equals(clean) || "latest_chapter".equals(clean) || "terbaru".equals(clean);
    }

    private static String typeForMode(String mode, String filterType) {
        if ("manga".equals(mode)) return "manga";
        if ("manhwa".equals(mode)) return "manhwa";
        if ("manhua".equals(mode)) return "manhua";
        if ("doujinshi".equals(mode)) return "doujinshi";
        String type = normalizeApiType(filterType);
        return type == null ? "" : type;
    }

    private static String sortForMode(String mode, String filterType) {
        String clean = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (isPopularMode(clean)) return "rating";
        if ("oldest".equals(clean) || "old".equals(clean)) return "oldest";
        if ("title_asc".equals(clean) || "title".equals(clean) || "az".equals(clean) || "a_z".equals(clean) || "a_a".equals(clean)) return "title_asc";
        if ("newest".equals(clean) || "new".equals(clean) || "added".equals(clean) || "latest_added".equals(clean)) return "newest";
        return "latest_chapter";
    }

    private static String taxonomySort(String mode) {
        String clean = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (isPopularMode(clean)) return "popular";
        return "latest";
    }

    private static String normalizeGenreQuery(String genre) {
        if (genre == null) return "";
        String value = genre.trim();
        if (value.isEmpty()) return "";
        String[] parts = value.split(",");
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null) continue;
            String clean = part.trim().toLowerCase(Locale.ROOT).replace(" ", "-");
            if (!clean.isEmpty() && seen.add(clean)) out.add(clean);
        }
        return TextUtils.join(",", out);
    }

    private static String normalizeApiType(String type) {
        if (type == null) return "";
        String clean = type.trim();
        if (clean.isEmpty()) return "";
        clean = clean.replace("type:", "").trim().toLowerCase(Locale.ROOT);
        if (clean.equals("manga")) return "manga";
        if (clean.equals("manhwa")) return "manhwa";
        if (clean.equals("manhua")) return "manhua";
        if (clean.equals("doujinshi") || clean.equals("doujin")) return "doujinshi";
        return clean;
    }

    private static boolean hasTypeFilter(String type) {
        return type != null && !type.trim().isEmpty();
    }

    private static boolean matchesType(MangaPost post, String type) {
        String expected = normalizeApiType(type);
        if (expected.isEmpty()) return true;
        String actual = normalizeApiType(post == null ? "" : post.typeLabel);
        if (actual.isEmpty() && post != null) actual = normalizeApiType(post.genre + " " + post.info);
        return expected.equals(actual) || actual.contains(expected) || expected.contains(actual);
    }

    private void getGenresFromLegacyApi(Result<ArrayList<GenreItem>> cb) {
        getJson(apiBase() + "/genres", new Result<JsonElement>() {
            @Override public void onSuccess(JsonElement root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenreItems(root);
                        if (out.isEmpty()) out = fallbackGenres();
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(out); }
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch(Exception e) {
                        ArrayList<GenreItem> fallback = fallbackGenres();
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(fallback); }
                        MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
                    }
                });
            }
            @Override public void onError(String message) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(fallback); }
                cb.onSuccess(fallback, false);
            }
        });
    }

    private ArrayList<GenreItem> parseGenreItems(JsonElement root) {
        ArrayList<GenreItem> out = new ArrayList<>();
        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        JsonArray terms = root != null && root.isJsonArray() ? root.getAsJsonArray() : getArray(obj, "terms");
        if (terms.size() == 0) terms = getArray(obj, "data");
        if (terms.size() == 0) terms = getArray(obj, "genres");
        if (terms.size() == 0) terms = getArray(obj, "items");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : terms) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String name = firstNonEmpty(getString(item, "name"), getString(item, "title"));
            String slug = firstNonEmpty(getString(item, "slug"), getString(item, "value"));
            if (name.isEmpty() || slug.isEmpty() || !seen.add(slug)) continue;
            out.add(new GenreItem(name, slug));
        }
        return out;
    }

    private void postViewSilently(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .headers(headersFor(url))
                    .post(RequestBody.create("", okhttp3.MediaType.parse("text/plain; charset=utf-8")))
                    .cacheControl(new CacheControl.Builder().noCache().noStore().build())
                    .build();
            CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
                @Override public void onFailure(Call call, IOException e) { }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    if (response.body() != null) response.body().close();
                }
            });
        } catch(Exception ignored) { }
    }

    private void getExploreJson(String exploreUrl, String apiFallbackUrl, Result<JsonElement> cb) {
        Request req = new Request.Builder().url(exploreUrl).headers(htmlHeadersFor(exploreUrl)).cacheControl(new CacheControl.Builder().noCache().noStore().build()).build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if ((response.code() == 403 || response.code() == 503)) {
                    warmupThenRetry(apiFallbackUrl, cb);
                    return;
                }
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code() + " DoujinDesu Explore")); return; }
                try {
                    JsonElement inline = extractExplorePayload(body, parseHttpDate(response.header("Date")));
                    if (inline != null && mangaArray(inline).size() > 0) {
                        MangaCoroutines.main(() -> cb.onSuccess(inline, false));
                        return;
                    }
                } catch(Exception ignored) { }

                getJson(apiFallbackUrl, cb);
            }
        });
    }

    private okhttp3.Headers htmlHeadersFor(String url) {
        return new okhttp3.Headers.Builder()
                .add("Referer", base() + "/")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .add("Accept-Language", "id-ID,id;q=0.9")
                .add("Cache-Control", "no-cache")
                .add("Pragma", "no-cache")
                .add("Sec-Fetch-Dest", "document")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Sec-GPC", "1")
                .add("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                .add("sec-ch-ua-mobile", "?1")
                .add("sec-ch-ua-platform", "\"Android\"")
                .add("User-Agent", UA_ANDROID_CHROME_152)
                .build();
    }

    private JsonElement extractExplorePayload(String html, long timestampMillis) {
        if (html == null || html.trim().isEmpty()) return null;
        String body = decodeHtmlEntities(html);
        JsonElement encPayload = extractEncRespFromText(body, timestampMillis);
        if (encPayload != null) return encPayload;
        JsonElement nextData = extractScriptJsonById(body, "__NEXT_DATA__");
        if (nextData != null) return nextData;
        JsonElement remixData = extractScriptJsonById(body, "__remixContext");
        if (remixData != null) return remixData;
        return null;
    }

    private static JsonElement extractEncRespFromText(String text, long timestampMillis) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"_enc_resp_\\\"\\s*:\\s*\\\"([0-9A-Fa-f]+)\\\"").matcher(text);
            if (!matcher.find()) return null;
            String decrypted = decryptEncResp(matcher.group(1), timestampMillis > 0L ? timestampMillis : System.currentTimeMillis());
            return JsonParser.parseString(decrypted);
        } catch(Exception ignored) { return null; }
    }

    private static JsonElement extractScriptJsonById(String html, String id) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?is)<script[^>]+id=[\\\"']" + java.util.regex.Pattern.quote(id) + "[\\\"'][^>]*>(.*?)</script>");
            java.util.regex.Matcher matcher = pattern.matcher(html);
            if (!matcher.find()) return null;
            String json = decodeHtmlEntities(matcher.group(1)).trim();
            if (json.isEmpty()) return null;
            return JsonParser.parseString(json);
        } catch(Exception ignored) { return null; }
    }

    private void getJson(String url, Result<JsonElement> cb) {
        getJson(url, cb, false);
    }

    private void getJson(String url, Result<JsonElement> cb, boolean retried) {
        Request req = new Request.Builder().url(url).headers(headersFor(url)).cacheControl(new CacheControl.Builder().noCache().noStore().build()).build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if ((response.code() == 403 || response.code() == 503) && !retried) {
                    warmupThenRetry(url, cb);
                    return;
                }
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code() + " DoujinDesu")); return; }
                try {
                    JsonElement element = JsonParser.parseString(body);
                    if (element != null && element.isJsonObject() && element.getAsJsonObject().has("_enc_resp_")) {
                        String enc = getString(element.getAsJsonObject(), "_enc_resp_");
                        long timestamp = parseHttpDate(response.header("Date"));
                        String decoded = decryptEncResp(enc, timestamp > 0L ? timestamp : System.currentTimeMillis());
                        element = JsonParser.parseString(decoded);
                    }
                    JsonElement finalElement = element;
                    MangaCoroutines.main(() -> cb.onSuccess(finalElement, false));
                } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Data DoujinDesu gagal didecrypt")); }
            }
        });
    }

    private void warmupThenRetry(String url, Result<JsonElement> cb) {
        Request warmup = new Request.Builder().url(base() + "/explore").headers(htmlHeaders()).cacheControl(new CacheControl.Builder().noCache().noStore().build()).build();
        CloudflareHelper.enqueue(client, warmup, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.body() != null) response.body().close();
                getJson(url, cb, true);
            }
        });
    }

    private okhttp3.Headers headersFor(String url) {
        return new okhttp3.Headers.Builder()
                .add("Referer", refererForApi(url))
                .add("Origin", base())
                .add("X-App-Secret", API_SECRET)
                .add("X-Device-Id", DEVICE_ID)
                .add("X-Device-Name", DEVICE_NAME)
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "id-ID,id;q=0.9")
                .add("Cache-Control", "no-cache")
                .add("Pragma", "no-cache")
                .add("Sec-Fetch-Dest", "empty")
                .add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Sec-GPC", "1")
                .add("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                .add("sec-ch-ua-mobile", "?1")
                .add("sec-ch-ua-platform", "\"Android\"")
                .add("User-Agent", UA_ANDROID_CHROME_152)
                .build();
    }

    private okhttp3.Headers htmlHeaders() {
        return new okhttp3.Headers.Builder()
                .add("Referer", base() + "/")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .add("Accept-Language", "id-ID,id;q=0.9")
                .add("Cache-Control", "no-cache")
                .add("Pragma", "no-cache")
                .add("Sec-Fetch-Dest", "document")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Sec-GPC", "1")
                .add("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                .add("sec-ch-ua-mobile", "?1")
                .add("sec-ch-ua-platform", "\"Android\"")
                .add("User-Agent", UA_ANDROID_CHROME_152)
                .build();
    }

    private String refererForApi(String rawUrl) {
        String fallback = base() + "/";
        try {
            HttpUrl httpUrl = HttpUrl.parse(rawUrl);
            if (httpUrl == null) return fallback;
            String path = httpUrl.encodedPath();
            if (path.startsWith("/api/chapters/")) {
                String id = path.substring("/api/chapters/".length());
                return base() + "/reader/" + id;
            }
            if (path.startsWith("/api/taxonomy/genres/")) {
                String slug = path.substring("/api/taxonomy/genres/".length());
                return base() + "/genres/" + slug;
            }
            if (path.equals("/api/taxonomy/genres")) return base() + "/genres";
            if (path.startsWith("/api/manga/")) {
                String slug = path.substring("/api/manga/".length());
                return base() + "/manga/" + slug;
            }
            if (path.equals("/api/manga")) return exploreReferer(httpUrl);
        } catch(Exception ignored) { }
        return fallback;
    }

    private String exploreReferer(HttpUrl apiUrl) {
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/explore").newBuilder();
        String search = apiUrl.queryParameter("search");
        String genre = apiUrl.queryParameter("genre");
        String status = apiUrl.queryParameter("status");
        String type = apiUrl.queryParameter("type");
        String sort = apiUrl.queryParameter("sort");
        String offset = apiUrl.queryParameter("offset");
        boolean hasSearch = search != null && !search.isEmpty();
        if (hasSearch) builder.addQueryParameter("search", search);
        if (genre != null && !genre.isEmpty()) builder.addQueryParameter("genre", genre);
        if (status != null && !status.isEmpty()) builder.addQueryParameter("status", status);
        if (type != null && !type.isEmpty()) builder.addQueryParameter("type", type);
        if (!hasSearch && sort != null && !sort.isEmpty()) builder.addQueryParameter("sort", sort);
        try {
            int safeOffset = offset == null ? 0 : Integer.parseInt(offset);
            int page = Math.max(1, (safeOffset / LIMIT) + 1);
            if (page > 1) builder.addQueryParameter("page", String.valueOf(page));
        } catch(Exception ignored) { }
        return builder.build().toString();
    }

    private MangaPost parsePost(JsonObject item) {
        if (item == null) return null;
        JsonObject d = getObject(item, "data");
        if (d == null) d = item;
        String slug = firstNonEmpty(getString(d, "slug"), getString(item, "slug"));
        if (slug.isEmpty()) return null;
        String title = cleanDisplay(firstNonEmpty(getString(d, "title"), getString(item, "title")));
        String cover = normalizeMediaUrl(firstNonEmpty(getString(d, "cover_url"), getString(d, "coverImage"), getString(d, "cover"), getString(d, "thumbnail"), getString(d, "image_url"), getString(d, "image"), getString(d, "banner_url"), getString(item, "cover_url")));
        String genres = parseGenres(d);
        String type = displayType(firstNonEmpty(getString(d, "type"), inferTypeFromText(genres + " " + title)));
        String author = authorName(d);
        String status = cleanDisplay(getString(d, "status"));
        String description = cleanSynopsis(firstNonEmpty(getString(d, "description"), getString(d, "synopsis")));
        LatestInfo latest = latestInfo(d);
        MangaPost post = new MangaPost(slug, title, cover, author, status, description, genres, type, latest.chapter, prettyDate(latest.date)).withSource(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU, "DoujinDesu");
        post.totalChapters = getInt(d, "chapter_count", getInt(getObject(d, "_count"), "chapters", getArray(d, "chapters").size()));
        post.info = buildInfo(d, post);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String slug, JsonObject obj) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        if (obj == null) return out;
        JsonObject d = getObject(obj, "data");
        if (d == null) d = obj;
        JsonArray chapters = getArray(d, "chapters");
        String mangaTitle = cleanDisplay(firstNonEmpty(getString(d, "title"), getString(obj, "title")));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : chapters) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            float number = getFloat(item, "chapter_number", getFloat(item, "index", -1f));
            if (number < 0f) number = numberFrom(getString(item, "title"));
            if (number < 0f) continue;
            String key = MangaChapter.formatIndex(number);
            if (!seen.add(key)) continue;
            String date = firstNonEmpty(getString(item, "created_at"), getString(item, "updated_at"));
            String chapterTitle = doujinChapterSuffix(getString(item, "title"), mangaTitle, number);
            MangaChapter chapter = new MangaChapter(slug, number, chapterTitle, prettyDate(date));
            chapter.chapterId = getString(item, "id");
            out.add(chapter);
            if (!chapter.chapterId.isEmpty()) {
                CHAPTER_ID_CACHE.put(slug + ":" + key, chapter.chapterId);
                CHAPTER_ID_CACHE.put(slug + ":" + number, chapter.chapterId);
            }
        }
        Collections.sort(out, (a, b) -> Float.compare(b.index, a.index));
        if (isCompleted(d) && !out.isEmpty()) {
            MangaChapter lastChapter = out.get(0);
            if (lastChapter != null && (lastChapter.title == null || !lastChapter.title.toLowerCase(Locale.ROOT).contains("end"))) {
                lastChapter.title = "Chapter " + MangaChapter.formatIndex(lastChapter.index) + " END";
            }
        }
        return out;
    }

    private static String doujinChapterSuffix(String rawTitle, String mangaTitle, float number) {
        String value = cleanDisplay(rawTitle);
        if (value.isEmpty()) return "";
        String idx = MangaChapter.formatIndex(number);
        value = value.replaceAll("(?i)^\\s*(?:chapter|chapters?|ch\\.?)\\s*", "").trim();
        value = value.replaceAll("^" + java.util.regex.Pattern.quote(idx) + "\\s*(?:[:\\-–—.]\\s*)?", "").trim();
        value = value.replaceAll("(?i)^\\s*(?:chapter|chapters?|ch\\.?)\\s*" + java.util.regex.Pattern.quote(idx) + "\\s*(?:[:\\-–—.]\\s*)?", "").trim();
        value = stripMangaTitleFromChapter(value, mangaTitle).trim();
        value = value.replaceAll("^[\\s:：\\-–—~～|/]+", "").replaceAll("[\\s:：\\-–—~～|/]+$", "").trim();
        if (value.isEmpty()) return "";
        if (sameChapterNumberText(value, idx)) return "";
        if (isSameTitle(value, mangaTitle)) return "";
        if (value.equalsIgnoreCase("end")) return "END";
        return value;
    }

    private static String stripMangaTitleFromChapter(String raw, String mangaTitle) {
        String value = cleanDisplay(raw);
        String title = cleanDisplay(mangaTitle);
        if (value.isEmpty() || title.isEmpty()) return value;
        if (isSameTitle(value, title)) return "";
        String lowerValue = value.toLowerCase(Locale.ROOT);
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        if (lowerValue.startsWith(lowerTitle)) return value.substring(title.length()).trim();
        if (lowerValue.endsWith(lowerTitle)) return value.substring(0, value.length() - title.length()).trim();
        String strippedSymbolsValue = normalizeTitleFingerprint(value);
        String strippedSymbolsTitle = normalizeTitleFingerprint(title);
        if (!strippedSymbolsTitle.isEmpty() && strippedSymbolsValue.equals(strippedSymbolsTitle)) return "";
        return value;
    }

    private static boolean isSameTitle(String a, String b) {
        String na = normalizeTitleFingerprint(a);
        String nb = normalizeTitleFingerprint(b);
        return !na.isEmpty() && na.equals(nb);
    }

    private static boolean sameChapterNumberText(String a, String b) {
        if (a == null || b == null) return false;
        try {
            float fa = Float.parseFloat(a.replace(",", ".").trim());
            float fb = Float.parseFloat(b.replace(",", ".").trim());
            return Math.abs(fa - fb) < 0.0001f;
        } catch(Exception ignored) {
            return a.trim().equalsIgnoreCase(b.trim());
        }
    }

    private static String normalizeTitleFingerprint(String raw) {
        if (raw == null) return "";
        return cleanDisplay(raw).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private LatestInfo latestInfo(JsonObject item) {
        JsonArray chapters = getArray(item, "chapters");
        float best = -1f;
        String bestDate = "";
        for (JsonElement element : chapters) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject chapter = element.getAsJsonObject();
            float number = getFloat(chapter, "chapter_number", -1f);
            if (number >= best) {
                best = number;
                bestDate = firstNonEmpty(getString(chapter, "created_at"), getString(chapter, "updated_at"));
            }
        }
        if (best >= 0f) return new LatestInfo("Chapter " + MangaChapter.formatIndex(best), bestDate);
        return new LatestInfo("", firstNonEmpty(getString(item, "updated_at"), getString(item, "created_at")));
    }

    private String parseGenres(JsonObject item) {
        ArrayList<String> list = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addNames(list, seen, termsByType(item, "genre"));
        JsonArray mangaGenres = getArray(item, "manga_genres");
        for (JsonElement element : mangaGenres) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject wrapper = element.getAsJsonObject();
            JsonObject genre = getObject(wrapper, "genres");
            String name = firstNonEmpty(getString(genre, "name"), getString(wrapper, "name"));
            addName(list, seen, name);
        }
        String terms = getString(item, "terms");
        if (!terms.isEmpty()) {
            String[] parts = terms.split(",");
            for (String part : parts) {
                String value = part == null ? "" : part.trim();
                int idx = value.indexOf(":");
                if (idx >= 0) value = value.substring(0, idx).trim();
                addName(list, seen, value);
            }
        }
        return TextUtils.join(", ", list);
    }

    private String buildInfo(JsonObject item, MangaPost post) {
        ArrayList<String> rows = new ArrayList<>();
        addInfo(rows, "Tipe", displayType(getString(item, "type")));
        addInfo(rows, "Author", authorName(item));
        addInfo(rows, "Status", post == null ? getString(item, "status") : post.status);
        addInfo(rows, "Serialisasi", firstNonEmpty(joinNames(termsByType(item, "series")), getString(item, "serialization")));
        addInfo(rows, "Rating", getString(item, "rating"));
        addInfo(rows, "Views", getString(item, "views"));
        addInfo(rows, "Alt Title", cleanDisplay(getString(item, "alt_titles").replace("|", ", ")));
        return TextUtils.join("||", rows);
    }

    private static void addInfo(ArrayList<String> rows, String label, String value) {
        if (value == null) return;
        String clean = cleanDisplay(value);
        if (!clean.isEmpty() && !"null".equalsIgnoreCase(clean) && !"-".equals(clean)) rows.add(label + ": " + clean);
    }

    private static String authorName(JsonObject item) {
        String fromTerms = joinNames(termsByType(item, "author"));
        if (!fromTerms.isEmpty()) return fromTerms;
        String fromArtists = joinNames(termsByType(item, "artist"));
        if (!fromArtists.isEmpty()) return fromArtists;
        String author = cleanDisplay(getString(item, "author"));
        if (!author.isEmpty() && !"-".equals(author)) return author;
        String artist = cleanDisplay(getString(item, "artist"));
        if (!artist.isEmpty() && !"-".equals(artist)) return artist;
        return "";
    }

    private static ArrayList<String> termsByType(JsonObject item, String type) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (item == null || type == null) return out;
        String expected = type.trim().toLowerCase(Locale.ROOT);
        String termList = getString(item, "term_list");
        if (!termList.isEmpty()) {
            String[] parts = termList.split("\\|");
            for (String part : parts) {
                if (part == null) continue;
                String[] columns = part.split(":");
                if (columns.length < 2) continue;
                String name = cleanDisplay(columns[0]);
                String termType = columns[1] == null ? "" : columns[1].trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty() || !expected.equals(termType)) continue;
                addName(out, seen, name);
            }
        }
        JsonArray array = getArray(item, expected);
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) continue;
            if (element.isJsonObject()) addName(out, seen, getString(element.getAsJsonObject(), "name"));
            else addName(out, seen, element.getAsString());
        }
        return out;
    }

    private static void addNames(ArrayList<String> out, LinkedHashSet<String> seen, ArrayList<String> names) {
        if (names == null) return;
        for (String name : names) addName(out, seen, name);
    }

    private static void addName(ArrayList<String> out, LinkedHashSet<String> seen, String raw) {
        if (out == null || seen == null) return;
        String clean = cleanDisplay(raw);
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean) || "-".equals(clean)) return;
        String key = clean.toLowerCase(Locale.ROOT);
        if (seen.add(key)) out.add(clean);
    }

    private static String joinNames(ArrayList<String> list) {
        if (list == null || list.isEmpty()) return "";
        return TextUtils.join(", ", list);
    }

    private static String displayType(String raw) {
        String clean = cleanDisplay(raw).toLowerCase(Locale.ROOT);
        if (clean.contains("manhwa")) return "Manhwa";
        if (clean.contains("manhua")) return "Manhua";
        if (clean.contains("doujinshi")) return "Doujinshi";
        if (clean.contains("doujin")) return "Doujinshi";
        if (clean.contains("manga")) return "Manga";
        return cleanDisplay(raw);
    }

    private static String cleanSynopsis(String raw) {
        String value = decodeHtmlEntities(raw);
        value = value.replaceAll("(?is)<p[^>]*>\\s*<strong>\\s*Download\\s+Batch\\s*</strong>.*?</p>", "");
        value = value.replaceAll("(?is)<strong>\\s*Download\\s+Batch\\s*</strong>.*", "");
        value = value.replaceAll("(?is)<p[^>]*>\\s*<b>\\s*Download\\s+Batch\\s*</b>.*?</p>", "");
        value = value.replaceAll("(?is)<b>\\s*Download\\s+Batch\\s*</b>.*", "");
        value = value.replaceAll("(?is)<strong>\\s*Sinopsis\\s*:?\\s*</strong>\\s*(<br\\s*/?>)?", "");
        value = value.replaceAll("(?is)<b>\\s*Sinopsis\\s*:?\\s*</b>\\s*(<br\\s*/?>)?", "");
        value = cleanDisplay(value);
        value = value.replaceAll("(?im)^\\s*Sinopsis\\s*:?\\s*", "");
        value = value.replaceAll("(?im)^\\s*Download\\s+Batch.*", "");
        value = stripChapterListFromSynopsis(value);
        value = value.replaceAll("\n{3,}", "\n\n").trim();
        return value;
    }

    private static String stripChapterListFromSynopsis(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String value = raw.replaceAll("(?is)(?:^|\\n)\\s*(?:chapter|chapters?)\\s*[0-9０-９]+\\s*(?:[-–—]\\s*[0-9０-９]+)?(?:\\s*[|,/]\\s*(?:chapter|chapters?)\\s*[0-9０-９]+\\s*(?:[-–—]\\s*[0-9０-９]+)?)+.*$", "");
        String[] lines = value.split("\\n");
        ArrayList<String> kept = new ArrayList<>();
        for (String line : lines) {
            String clean = line == null ? "" : line.trim();
            if (isChapterListLine(clean)) continue;
            kept.add(line);
        }
        return TextUtils.join("\n", kept).trim();
    }

    private static boolean isChapterListLine(String value) {
        if (value == null) return false;
        String clean = value.trim();
        if (clean.isEmpty()) return false;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("chapter") && !lower.startsWith("chapters")) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\bchapters?\\s*[0-9０-９]+(?:\\s*[-–—]\\s*[0-9０-９]+)?").matcher(clean);
        int count = 0;
        while (matcher.find()) count++;
        return count >= 2 || clean.matches("(?i)^\\s*chapters?\\s*[0-9０-９]+(?:\\s*[-–—]\\s*[0-9０-９]+)?\\s*$");
    }

    private static String cleanDisplay(String raw) {
        if (raw == null) return "";
        String value = decodeHtmlEntities(raw).replace('\u00A0', ' ');
        value = value.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        value = value.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        value = value.replaceAll("(?i)<br\\s*/?>", "\n");
        value = value.replaceAll("(?i)</div>\\s*<div[^>]*>", "\n");
        value = value.replaceAll("(?i)</p>\\s*<p[^>]*>", "\n\n");
        value = value.replaceAll("(?i)</li>\\s*<li[^>]*>", "\n");
        value = value.replaceAll("<[^>]+>", "");
        value = decodeHtmlEntities(value);
        value = value.replaceAll("[ \t]+", " ").replaceAll(" *\n *", "\n").trim();
        return value;
    }

    private static String decodeHtmlEntities(String raw) {
        if (raw == null) return "";
        String value = raw;
        for (int i = 0; i < 3; i++) {
            String before = value;
            value = value.replace("&nbsp;", " ").replace("&#160;", " ");
            value = value.replace("&quot;", "\"").replace("&#34;", "\"").replace("&apos;", "'").replace("&#039;", "'");
            value = value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
            value = decodeNumericEntities(value);
            if (before.equals(value)) break;
        }
        return value;
    }

    private static String decodeNumericEntities(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("&#(x?[0-9A-Fa-f]+);").matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String code = matcher.group(1);
            try {
                int radix = code.startsWith("x") || code.startsWith("X") ? 16 : 10;
                String number = radix == 16 ? code.substring(1) : code;
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(number, radix))));
            } catch(Exception e) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean isCompleted(JsonObject item) {
        String status = getString(item, "status").trim().toLowerCase(Locale.ROOT);
        return "completed".equals(status) || "finished".equals(status) || "complete".equals(status) || "end".equals(status) || "ended".equals(status);
    }

    private static String normalizeMediaUrl(String raw) {
        String url = decodeHtmlEntities(raw == null ? "" : raw).trim();
        if (url.isEmpty()) return "";
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return base() + url;
        return url;
    }

    private static JsonArray chapterContentUrls(JsonObject obj) {
        JsonArray urls = getArray(obj, "content_urls");
        if (urls.size() > 0) return urls;
        urls = getArray(obj, "contentUrls");
        if (urls.size() > 0) return urls;
        urls = getArray(obj, "content_url");
        if (urls.size() > 0) return urls;
        urls = getArray(obj, "contentUrl");
        if (urls.size() > 0) return urls;
        urls = getArray(obj, "images");
        if (urls.size() > 0) return urls;
        urls = getArray(obj, "pages");
        if (urls.size() > 0) return urls;
        return getArray(obj, "reader_images");
    }

    private static String chapterImageValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (!element.isJsonObject()) return "";
        JsonObject item = element.getAsJsonObject();
        return firstNonEmpty(
                getString(item, "url"),
                getString(item, "src"),
                getString(item, "data-src"),
                getString(item, "data-lazy-src"),
                getString(item, "data-original"),
                getString(item, "image_url"),
                getString(item, "imageUrl"),
                getString(item, "image"),
                getString(item, "content_url"),
                getString(item, "contentUrl"),
                getString(item, "file"),
                getString(item, "link"),
                getString(item, "path")
        );
    }

    private static String normalizeChapterImageUrl(String raw) {
        String url = decodeHtmlEntities(raw == null ? "" : raw).trim().replace("\\", "/");
        if (url.isEmpty()) return "";
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("/")) url = base() + url;
        return url.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace("|", "%7C");
    }

    private JsonArray mangaArray(JsonElement root) {
        if (root != null && root.isJsonArray()) return root.getAsJsonArray();
        JsonObject obj = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        if (obj == null) return new JsonArray();
        JsonArray mangaList = getArray(obj, "manga_list");
        if (mangaList.size() > 0) return mangaList;
        mangaList = getArray(obj, "mangaList");
        if (mangaList.size() > 0) return mangaList;
        JsonArray data = getArray(obj, "data");
        if (data.size() > 0) return data;
        JsonArray items = getArray(obj, "items");
        if (items.size() > 0) return items;
        JsonArray mangas = getArray(obj, "mangas");
        if (mangas.size() > 0) return mangas;
        JsonArray results = getArray(obj, "results");
        if (results.size() > 0) return results;
        return new JsonArray();
    }

    private boolean hasNext(JsonElement root, int itemCount, int page) {
        if (root != null && root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            JsonObject pagination = getObject(obj, "pagination");
            if (pagination != null) {
                int totalPages = getInt(pagination, "totalPages", getInt(pagination, "total_pages", page));
                int currentPage = getInt(pagination, "page", getInt(pagination, "current_page", page));
                return currentPage < totalPages;
            }
        }
        return itemCount >= LIMIT;
    }

    private static String decryptEncResp(String encResp, long timestampMillis) throws Exception {
        ArrayList<Long> buckets = new ArrayList<>();
        addBuckets(buckets, timestampMillis);
        addBuckets(buckets, System.currentTimeMillis());
        Exception last = null;
        for (long value : buckets) {
            try {
                String raw = decryptRaw(encResp, makeKey(value));
                return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
            } catch(Exception e) { last = e; }
        }
        if (last != null) throw last;
        throw new IllegalArgumentException("Gagal decrypt _enc_resp_");
    }

    private static void addBuckets(ArrayList<Long> buckets, long timestampMillis) {
        if (timestampMillis <= 0L) return;
        long bucket = timestampMillis / 3600000L;
        long[] candidates = new long[]{bucket, bucket - 1L, bucket + 1L, bucket - 2L, bucket + 2L};
        for (long value : candidates) if (!buckets.contains(value)) buckets.add(value);
    }

    private static String makeKey(long hourBucket) {
        String input = DECRYPT_SALT + "_" + hourBucket;
        int hash = 0;
        for (int i = 0; i < input.length(); i++) hash = (hash << 5) - hash + input.charAt(i);
        long seed = Math.abs((long) hash);
        if (seed == 0L) seed = 123456789L;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            seed = (seed * 1664525L + 1013904223L) % 4294967296L;
            key.append((char) (33 + seed % 93));
        }
        return key.toString();
    }

    private static String decryptRaw(String hex, String key) {
        ArrayList<Integer> bytes = new ArrayList<>();
        for (int i = 0; i + 1 < hex.length(); i += 2) bytes.add(Integer.parseInt(hex.substring(i, i + 2), 16));
        StringBuilder output = new StringBuilder();
        int state = 42;
        for (int i = 0; i < bytes.size(); i++) {
            int b = bytes.get(i);
            int keyByte = key.charAt(i % key.length());
            int value = b ^ keyByte ^ (i * 13) ^ state;
            output.append((char) (value & 255));
            state = (state + b) % 256;
        }
        return output.toString();
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            return format.parse(value).getTime();
        } catch(Exception e) { return 0L; }
    }

    public static String prettyDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String value = raw.trim();
        String[] patterns = new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(pattern, Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(in.parse(value));
            } catch(Exception ignored) { }
        }
        return value.length() > 10 ? value.substring(0, 10) : value;
    }

    private DoujinFilterSpec parseFilterSpec(String raw) {
        DoujinFilterSpec spec = new DoujinFilterSpec();
        if (raw == null || raw.trim().isEmpty()) return spec;
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) spec.type = value.substring(value.indexOf(":") + 1).trim();
            else if (lower.startsWith("status:")) spec.status = value.substring(value.indexOf(":") + 1).trim();
            else {
                if (lower.startsWith("genre:")) value = value.substring(value.indexOf(":") + 1).trim();
                if (lower.startsWith("genre/")) value = value.substring("genre/".length()).trim();
                spec.genre = value;
            }
        }
        return spec;
    }

    private String findChapterId(String slug, float index) {
        String key = slug + ":" + MangaChapter.formatIndex(index);
        String cached = CHAPTER_ID_CACHE.get(key);
        if (cached != null && !cached.trim().isEmpty()) return cached.trim();
        cached = CHAPTER_ID_CACHE.get(slug + ":" + index);
        if (cached != null && !cached.trim().isEmpty()) return cached.trim();
        ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(slug);
        if (chapters != null) {
            for (MangaChapter chapter : chapters) {
                if (chapter != null && Math.abs(chapter.index - index) < 0.001f && chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()) return chapter.chapterId.trim();
            }
        }
        return "";
    }

    private static String cleanSlug(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("http")) {
            int idx = value.indexOf("/manga/");
            if (idx >= 0) value = value.substring(idx + "/manga/".length());
        }
        value = value.split("\\?")[0];
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("manga/")) value = value.substring("manga/".length());
        return value;
    }

    private static String urlSegment(String value) {
        return value == null ? "" : value.trim().replace(" ", "%20");
    }

    private static float numberFrom(String raw) {
        if (raw == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(raw);
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch(Exception e) { return -1f; }
    }

    private static String inferTypeFromText(String raw) {
        String text = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (text.contains("manhwa")) return "manhwa";
        if (text.contains("manhua")) return "manhua";
        if (text.contains("doujinshi")) return "doujinshi";
        if (text.contains("manga")) return "manga";
        return "";
    }

    private static JsonObject getObject(JsonObject object, String key) {
        try { return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null; } catch(Exception e) { return null; }
    }

    private static JsonArray getArray(JsonObject object, String key) {
        try { return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); } catch(Exception e) { return new JsonArray(); }
    }

    private static String getString(JsonObject object, String key) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; } catch(Exception e) { return ""; }
    }

    private static int getInt(JsonObject object, String key, int def) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : def; } catch(Exception e) { return def; }
    }

    private static float getFloat(JsonObject object, String key, float def) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsFloat() : def; } catch(Exception e) { return def; }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) return value.trim();
        return "";
    }

    private static ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        String[][] items = new String[][]{{"Ahegao","ahegao"},{"Anal","anal"},{"Big Breast","big-breast"},{"Blowjob","blowjob"},{"Bondage","bondage"},{"Cheating","cheating"},{"Dark Skin","dark-skin"},{"Elf","elf"},{"Femdom","femdom"},{"Futanari","futanari"},{"Group","group"},{"Harem","harem"},{"Lactation","lactation"},{"Maid","maid"},{"MILF","milf"},{"Mind Control","mind-control"},{"Netorare","netorare"},{"Paizuri","paizuri"},{"Schoolgirl Uniform","schoolgirl-uniform"},{"Tentacles","tentacles"},{"Vanilla","vanilla"},{"Yuri","yuri"}};
        for (String[] item : items) out.add(new GenreItem(item[0], item[1]));
        return out;
    }

    private static class DoujinFilterSpec {
        String genre = "";
        String type = "";
        String status = "";
    }

    private static class LatestInfo {
        final String chapter;
        final String date;
        LatestInfo(String chapter, String date) {
            this.chapter = chapter == null ? "" : chapter;
            this.date = date == null ? "" : date;
        }
    }
}
