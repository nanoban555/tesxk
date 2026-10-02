package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CosmicScans extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_COSMICSCANS); }
    private static final String API = "https://cdncid.csmcscns.id/v1/manga";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final String PAGE_SIZE = "24";
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
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

    private static final Map<String, String> CURSOR_CACHE = new HashMap<>();
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("https?://[^'\\\"<>\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_SRC_PATTERN = Pattern.compile("(?i)<img[^>]+(?:src|data-src|data-lazy-src)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]");
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "CosmicScans"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        int safePage = Math.max(1, page);
        String cleanQuery = query == null ? "" : query.trim();
        String genreSlug = extractGenreFilter(genre);
        String typeFilter = extractTypeFilter(genre);
        String sortValue = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (("manga".equals(sortValue) || "manhwa".equals(sortValue) || "manhua".equals(sortValue)) && typeFilter.isEmpty()) typeFilter = sortValue;
        if (!cleanQuery.isEmpty()) {
            if (safePage > 1) { cb.onSuccess(new ArrayList<>(), false); return; }
            fetchSearch(cleanQuery, genreSlug, typeFilter, cb);
            return;
        }
        String route = routeFor(sortValue, genreSlug);
        String routeKey = route + "|genre=" + genreSlug + "|type=" + typeFilter;
        if (safePage > 1 && cursorFor(routeKey, safePage).isEmpty()) {
            list(safePage - 1, sort, query, genre, new Result<ArrayList<MangaPost>>() {
                @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                    if (cursorFor(routeKey, safePage).isEmpty()) cb.onSuccess(new ArrayList<>(), false); else list(safePage, sort, query, genre, cb);
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        HttpUrl url = buildListUrl(route, genreSlug, cursorFor(routeKey, safePage));
        fetchList(url, routeKey, safePage, typeFilter, genreSlug, cb);
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        ArrayList<GenreItem> out = new ArrayList<>();
        out.add(new GenreItem("Action", "action"));
        out.add(new GenreItem("Adventure", "adventure"));
        out.add(new GenreItem("Comedy", "comedy"));
        out.add(new GenreItem("Drama", "drama"));
        out.add(new GenreItem("Fantasy", "fantasy"));
        out.add(new GenreItem("Martial Arts", "martial-arts"));
        out.add(new GenreItem("Romance", "romance"));
        out.add(new GenreItem("School Life", "school-life"));
        out.add(new GenreItem("Shounen", "shounen"));
        out.add(new GenreItem("Supernatural", "supernatural"));
        out.add(new GenreItem("System", "system"));
        out.add(new GenreItem("Thriller", "thriller"));
        out.add(new GenreItem("Murim", "murim"));
        GENRE_CACHE.put("genres", new ArrayList<>(out));
        cb.onSuccess(out, false);
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (needsListEnrichment(p, loadChapter, loadType)) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (!needsListEnrichment(p, loadChapter, loadType)) continue;
            detail(p.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        if (loadType && detail.typeLabel != null && !detail.typeLabel.trim().isEmpty()) p.typeLabel = detail.getTypeLabel();
                        if (detail.genre != null && !detail.genre.trim().isEmpty()) p.genre = detail.genre;
                        if (detail.status != null && !detail.status.trim().isEmpty()) p.status = detail.status;
                        if (loadChapter && detail.latestChapter != null && !detail.latestChapter.trim().isEmpty()) p.latestChapter = detail.latestChapter;
                        if (loadChapter && detail.latestChapterDate != null && !detail.latestChapterDate.trim().isEmpty()) p.latestChapterDate = detail.latestChapterDate;
                    }
                    finishEnrichment(remaining, done);
                }
                @Override public void onError(String message) { finishEnrichment(remaining, done); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String mangaSlug = normalizeSlug(slug);
        if (mangaSlug.isEmpty()) { cb.onError("Slug CosmicScans kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(mangaSlug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(API + "/mangaDetail/" + encodePath(mangaSlug), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        if (data == null) { MangaCoroutines.main(() -> cb.onError("Detail CosmicScans kosong")); return; }
                        MangaPost post = parsePost(data, true);
                        ArrayList<MangaChapter> chapters = parseChapters(data);
                        post.totalChapters = chapters.size();
                        if (!chapters.isEmpty()) {
                            MangaChapter newest = chapters.get(0);
                            post.latestChapter = newest.title;
                            post.latestChapterDate = newest.date;
                        }
                        DETAIL_CACHE.put(mangaSlug, post);
                        CHAPTER_CACHE.put(mangaSlug, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail CosmicScans gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String mangaSlug = normalizeSlug(slug);
        if (mangaSlug.isEmpty()) { cb.onError("Slug CosmicScans kosong"); return; }
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(mangaSlug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        detail(mangaSlug, new Result<MangaPost>() {
            @Override public void onSuccess(MangaPost data, boolean hasNext) {
                ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(mangaSlug);
                cb.onSuccess(chapters == null ? new ArrayList<>() : new ArrayList<>(chapters), false);
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String rawSlug = slug == null ? "" : slug.trim();
        String directChapterSlug = normalizeChapterSlug(rawSlug);
        String mangaSlug = normalizeSlug(rawSlug);
        if (looksLikeChapterSlug(rawSlug) && !directChapterSlug.isEmpty()) {
            String inferredMangaSlug = inferMangaSlugFromChapterSlug(directChapterSlug);
            String directKey = directChapterSlug + "#direct";
            ArrayList<String> cached = PAGE_CACHE.get(directKey);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
            ArrayList<String> candidates = chapterSlugCandidates(inferredMangaSlug, directChapterSlug, index);
            fetchPagesByChapterCandidates(inferredMangaSlug, candidates, directKey, cb);
            return;
        }
        if (mangaSlug.isEmpty()) { cb.onError("Slug CosmicScans kosong"); return; }
        String pageKey = mangaSlug + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(mangaSlug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter target = findChapterForIndex(chapters, index);
                ArrayList<String> candidates = chapterSlugCandidates(mangaSlug, target == null ? "" : target.slug, index);
                addNearbyChapterSlugs(candidates, chapters, index);
                if (candidates.isEmpty()) { cb.onError("Chapter CosmicScans tidak ditemukan"); return; }
                fetchPagesByChapterCandidates(mangaSlug, candidates, pageKey, cb);
            }
            @Override public void onError(String message) {
                ArrayList<String> candidates = chapterSlugCandidates(mangaSlug, "", index);
                if (candidates.isEmpty()) cb.onError(message); else fetchPagesByChapterCandidates(mangaSlug, candidates, pageKey, cb);
            }
        });
    }

    private void fetchSearch(String query, String genreSlug, String typeFilter, Result<ArrayList<MangaPost>> cb) {
        try {
            HttpUrl.Builder builder = HttpUrl.parse(API + "/search").newBuilder();
            builder.addQueryParameter("q", query);
            builder.addQueryParameter("limit", PAGE_SIZE);
            String url = builder.build().toString();
            String cacheKey = url + "|type=" + typeFilter + "|genre=" + (genreSlug == null ? "" : genreSlug);
            ArrayList<MangaPost> cached = LIST_CACHE.get(cacheKey);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
            getJson(url, new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parsePostList(getArray(root, "data"), typeFilter, genreSlug);
                            LIST_CACHE.put(cacheKey, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, false));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Pencarian CosmicScans gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    private void fetchPagesByChapterCandidates(String mangaSlug, ArrayList<String> candidates, String pageKey, Result<ArrayList<String>> cb) {
        ArrayList<String> cleanCandidates = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                String clean = normalizeChapterSlug(candidate);
                if (!clean.isEmpty() && seen.add(clean)) cleanCandidates.add(clean);
            }
        }
        if (cleanCandidates.isEmpty()) { cb.onError("Chapter CosmicScans tidak ditemukan"); return; }
        fetchPagesByChapterCandidate(mangaSlug, cleanCandidates, 0, pageKey, false, cb);
    }

    private void fetchPagesByChapterCandidate(String mangaSlug, ArrayList<String> candidates, int position, String pageKey, boolean triedDetailFallback, Result<ArrayList<String>> cb) {
        if (candidates == null || position < 0 || position >= candidates.size()) {
            if (!triedDetailFallback && mangaSlug != null && !mangaSlug.trim().isEmpty()) {
                float fallbackIndex = candidates == null || candidates.isEmpty() ? -1f : parseChapterIndexFromSlug(candidates.get(0));
                if (fallbackIndex >= 0f) {
                    chapters(mangaSlug, new Result<ArrayList<MangaChapter>>() {
                        @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                            ArrayList<String> retry = new ArrayList<>();
                            if (candidates != null) retry.addAll(candidates);
                            addNearbyChapterSlugs(retry, chapters, fallbackIndex);
                            if (retry.size() == (candidates == null ? 0 : candidates.size())) cb.onSuccess(new ArrayList<>(), false);
                            else fetchPagesByChapterCandidate(mangaSlug, retry, candidates == null ? 0 : candidates.size(), pageKey, true, cb);
                        }
                        @Override public void onError(String message) { cb.onSuccess(new ArrayList<>(), false); }
                    });
                    return;
                }
            }
            cb.onSuccess(new ArrayList<>(), false);
            return;
        }
        String cleanChapterSlug = normalizeChapterSlug(candidates.get(position));
        if (cleanChapterSlug.isEmpty()) {
            fetchPagesByChapterCandidate(mangaSlug, candidates, position + 1, pageKey, triedDetailFallback, cb);
            return;
        }
        getJson(API + "/readingPage/" + encodePath(cleanChapterSlug), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        ArrayList<String> out = data == null ? new ArrayList<>() : parsePages(data);
                        if (out.isEmpty()) {
                            MangaCoroutines.main(() -> fetchPagesByChapterCandidate(mangaSlug, candidates, position + 1, pageKey, triedDetailFallback, cb));
                            return;
                        }
                        PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                        ArrayList<MangaChapter> other = parseOtherChapters(data);
                        if (mangaSlug != null && !mangaSlug.trim().isEmpty() && !other.isEmpty()) CHAPTER_CACHE.put(mangaSlug, other);
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) {
                        MangaCoroutines.main(() -> fetchPagesByChapterCandidate(mangaSlug, candidates, position + 1, pageKey, triedDetailFallback, cb));
                    }
                });
            }
            @Override public void onError(String message) { fetchPagesByChapterCandidate(mangaSlug, candidates, position + 1, pageKey, triedDetailFallback, cb); }
        });
    }

    private void fetchWebsiteReaderPages(String mangaSlug, String chapterSlug, String pageKey, Result<ArrayList<String>> cb) {
        String cleanChapterSlug = normalizeChapterSlug(chapterSlug);
        if (cleanChapterSlug.isEmpty()) { cb.onError("Chapter CosmicScans tidak ditemukan"); return; }
        getText(normalizedBase() + "/chapter/" + encodePath(cleanChapterSlug), new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> out = parsePagesFromHtml(body);
                        PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman CosmicScans gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void fetchList(HttpUrl url, String routeKey, int page, String typeFilter, String genreFilter, Result<ArrayList<MangaPost>> cb) {
        String cacheKey = url.toString() + "|type=" + typeFilter + "|genre=" + (genreFilter == null ? "" : genreFilter);
        ArrayList<MangaPost> cached = LIST_CACHE.get(cacheKey);
        if (cached != null) {
            Boolean next = NEXT_CACHE.get(cacheKey);
            cb.onSuccess(new ArrayList<>(cached), next != null && next);
            return;
        }
        getJson(url.toString(), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaPost> out = parsePostList(getArray(root, "data"), typeFilter, genreFilter);
                        JsonObject cursor = getObject(root, "cursor");
                        boolean hasNext = getBoolean(cursor, "hasNext", false);
                        String nextCursor = getString(cursor, "nextCursor");
                        if (hasNext && !nextCursor.isEmpty()) putCursor(routeKey, page + 1, nextCursor);
                        LIST_CACHE.put(cacheKey, new ArrayList<>(out));
                        NEXT_CACHE.put(cacheKey, hasNext);
                        MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar CosmicScans gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private HttpUrl buildListUrl(String route, String genreSlug, String after) {
        HttpUrl.Builder builder;
        if ("project".equals(route)) {
            builder = HttpUrl.parse(API + "/latestProject").newBuilder();
            builder.addQueryParameter("limit", PAGE_SIZE);
        } else {
            builder = HttpUrl.parse(API + "/filter").newBuilder();
            builder.addQueryParameter("order_by", route);
            builder.addQueryParameter("limit", PAGE_SIZE);
        }
        if (after != null && !after.isEmpty()) builder.addQueryParameter("after", after);
        if (genreSlug != null && !genreSlug.isEmpty()) builder.addQueryParameter("genre", genreSlug);
        return builder.build();
    }

    private ArrayList<MangaPost> parsePostList(JsonArray data, String typeFilter, String genreFilter) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            MangaPost post = parsePost(element.getAsJsonObject(), false);
            if (!matchesType(post, typeFilter)) continue;
            if (!matchesGenre(post, genreFilter)) continue;
            String key = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
            if (!key.isEmpty() && seen.add(key)) out.add(post);
        }
        return out;
    }

    private MangaPost parsePost(JsonObject item, boolean detail) {
        String slug = normalizeSlug(getString(item, "slug"));
        String title = getString(item, "title");
        String cover = getString(item, "cover");
        String status = getString(item, "status");
        String synopsis = detail ? cleanSynopsis(getString(item, "sinopsis")) : "";
        String genre = joinGenres(item);
        String type = firstNonEmpty(getString(item, "type"), guessType(item));
        String author = getString(item, "author");
        MangaPost post = new MangaPost(slug, title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_COSMICSCANS, "CosmicScans");
        JsonObject chapter = firstChapter(item);
        if (chapter != null) {
            String num = getString(chapter, "chapterNum");
            if (!num.isEmpty()) post.latestChapter = "Chapter " + num.trim();
            post.latestChapterDate = getString(chapter, "time");
        }
        if (detail) {
            ArrayList<String> info = new ArrayList<>();
            addInfo(info, "Judul", title);
            addInfo(info, "Status", status);
            addInfo(info, "Tipe", post.getTypeLabel());
            addInfo(info, "Author", author);
            addInfo(info, "Genre", genre);
            post.info = joinInfo(info);
        }
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(JsonObject item) {
        return parseChapterArray(getArray(item, "chapters"));
    }

    private ArrayList<MangaChapter> parseOtherChapters(JsonObject item) {
        return parseChapterArray(getArray(item, "otherChapters"));
    }

    private ArrayList<MangaChapter> parseChapterArray(JsonArray array) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!redirectLink(item).isEmpty()) continue;
            String slug = normalizeChapterSlug(getString(item, "slug"));
            if (slug.isEmpty() || !seen.add(slug)) continue;
            String rawNum = firstNonEmpty(getString(item, "chapterNum"), slug);
            float index = parseChapterIndex(rawNum, out.size() + 1);
            String title = chapterSuffix(rawNum, index);
            String date = getString(item, "time");
            out.add(new MangaChapter(slug, index, title, date));
        }
        return out;
    }

    private ArrayList<String> parsePages(JsonObject data) {
        if (!redirectLink(data).isEmpty()) return new ArrayList<>();
        ArrayList<String> out = new ArrayList<>();
        for (JsonElement element : getArray(data, "chapters")) {
            if (element == null || !element.isJsonPrimitive()) continue;
            String src = firstImageSrc(element.getAsString());
            if (!src.isEmpty()) out.add(src);
        }
        return out;
    }

    private static String firstImageSrc(String html) {
        if (html == null || html.trim().isEmpty()) return "";
        String value = html.replace("\\/", "/").replace("&amp;", "&");
        Matcher matcher = IMAGE_SRC_PATTERN.matcher(value);
        if (!matcher.find()) return "";
        String url = matcher.group(1).trim().replace("\\/", "/").replace("&amp;", "&");
        if (url.startsWith("//")) url = "https:" + url;
        return url.startsWith("http") ? url : "";
    }

    private ArrayList<String> parsePagesFromHtml(String body) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        collectPageUrlsFromString(body, out, seen);
        return out;
    }

    private void getJson(String url, Result<JsonObject> cb) {
        Request req = new Request.Builder().url(url).header("Referer", normalizedBase() + "/").header("Origin", normalizedBase()).header("Accept", "application/json").header("Cache-Control", "no-cache").header("Pragma", "no-cache").header("Accept-Language", "id-ID,id;q=0.8").header("Sec-GPC", "1").header("Sec-Fetch-Site", "cross-site").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty").header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                try {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    MAIN.post(() -> cb.onSuccess(root, false));
                } catch(Exception e) { MAIN.post(() -> cb.onError("Data CosmicScans gagal dibaca")); }
            }
        });
    }

    private void getText(String url, Result<String> cb) {
        Request req = new Request.Builder().url(url).header("Referer", normalizedBase() + "/").header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").header("Cache-Control", "no-cache").header("Pragma", "no-cache").header("Accept-Language", "id-ID,id;q=0.8").header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                MAIN.post(() -> cb.onSuccess(body, false));
            }
        });
    }

    private static String routeFor(String sort, String genreSlug) {
        String value = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("project".equals(value) || "projects".equals(value)) return "project";
        if ("all".equals(value) || "allcomics".equals(value) || "all_comics".equals(value) || "az".equals(value)) return "az";
        if ("za".equals(value) || "z-a".equals(value) || "desc".equals(value)) return "za";
        if ("added".equals(value) || "new".equals(value) || "new_added".equals(value) || "latest_added".equals(value)) return "added";
        if ("popular".equals(value) || "popularity".equals(value) || "views".equals(value)) return "popular";
        return "update";
    }

    private static JsonObject firstChapter(JsonObject item) {
        JsonArray chapters = getArray(item, "chapters");
        for (JsonElement element : chapters) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject chapter = element.getAsJsonObject();
            if (redirectLink(chapter).isEmpty()) return chapter;
        }
        return null;
    }

    private static String joinGenres(JsonObject item) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectGenres(values, getArray(item, "genres"));
        collectGenres(values, getArray(item, "genre"));
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static void collectGenres(LinkedHashSet<String> out, JsonArray array) {
        if (out == null || array == null) return;
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) continue;
            String value = "";
            if (element.isJsonPrimitive()) value = element.getAsString();
            else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                value = firstNonEmpty(getString(object, "name"), getString(object, "title"), getString(object, "slug"));
            }
            value = value == null ? "" : value.trim();
            if (!value.isEmpty()) out.add(value);
        }
    }

    private static boolean matchesType(MangaPost post, String typeFilter) {
        String filter = normalizeTypeFilter(typeFilter);
        if (filter.isEmpty()) return true;
        String type = post == null ? "" : post.getTypeLabel().toLowerCase(Locale.ROOT);
        return type.equals(filter) || type.contains(filter);
    }

    private static boolean matchesGenre(MangaPost post, String genreFilter) {
        String filter = normalizeGenreValue(genreFilter);
        if (filter.isEmpty()) return true;
        String genre = post == null || post.genre == null ? "" : post.genre;
        String normalized = normalizeGenreValue(genre);
        if (normalized.equals(filter)) return true;
        for (String part : genre.split(",")) if (normalizeGenreValue(part).equals(filter)) return true;
        return false;
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
            return normalizeTypeFilter(value.substring(value.indexOf(':') + 1));
        }
        return "";
    }

    private static String normalizeTypeFilter(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("manga") || value.equals("manhwa") || value.equals("manhua")) return value;
        if (value.equals("webtoon")) return value;
        return "";
    }

    private static String normalizeGenreValue(String genre) {
        if (genre == null) return "";
        String value = genre.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("genre:")) value = value.substring(value.indexOf(':') + 1).trim();
        value = legacyGenreIdToSlug(value);
        value = value.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z]+", "-").replaceAll("^-+", "").replaceAll("-+$", "");
        return value;
    }

    private static String legacyGenreIdToSlug(String value) {
        if (value == null) return "";
        String v = value.trim();
        if ("14".equals(v)) return "action";
        if ("33".equals(v)) return "adventure";
        if ("22".equals(v)) return "comedy";
        if ("23".equals(v)) return "drama";
        if ("27".equals(v)) return "fantasy";
        if ("34".equals(v)) return "horror";
        if ("42".equals(v)) return "martial-arts";
        if ("37".equals(v)) return "romance";
        if ("39".equals(v)) return "school-life";
        if ("16".equals(v)) return "shounen";
        if ("41".equals(v)) return "supernatural";
        return v;
    }

    private static boolean looksLikeChapterSlug(String value) {
        if (value == null) return false;
        String clean = value.trim().toLowerCase(Locale.ROOT);
        return clean.contains("/chapter/") || clean.startsWith("chapter/") || clean.contains("-chapter-");
    }

    private static String buildChapterSlug(String mangaSlug, float index) {
        String cleanMangaSlug = normalizeSlug(mangaSlug);
        if (cleanMangaSlug.isEmpty()) return "";
        String chapter = MangaChapter.formatIndex(index).replace(",", ".").replace(".", "-");
        if (chapter.isEmpty()) return "";
        return cleanMangaSlug + "-chapter-" + chapter;
    }

    private static ArrayList<String> chapterSlugCandidates(String mangaSlug, String preferredSlug, float index) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addChapterCandidate(out, seen, preferredSlug);
        String cleanMangaSlug = normalizeSlug(mangaSlug);
        String formatted = MangaChapter.formatIndex(index).replace(",", ".");
        String dash = formatted.replace(".", "-");
        if (!cleanMangaSlug.isEmpty() && !dash.isEmpty()) {
            addChapterCandidate(out, seen, cleanMangaSlug + "-chapter-" + dash);
            addChapterCandidate(out, seen, cleanMangaSlug + "-chapte-" + dash);
            addChapterCandidate(out, seen, cleanMangaSlug + "-cahpter-" + dash);
            if (dash.matches("^[0-9]$")) {
                addChapterCandidate(out, seen, cleanMangaSlug + "-chapter-0" + dash);
                addChapterCandidate(out, seen, cleanMangaSlug + "-chapter-0" + dash + "-1");
                addChapterCandidate(out, seen, cleanMangaSlug + "-chapter-0" + dash + "-2");
            } else if (dash.matches("^[0-9]-[0-9]+$")) {
                addChapterCandidate(out, seen, cleanMangaSlug + "-chapter-0" + dash);
            }
        }
        return out;
    }

    private static void addNearbyChapterSlugs(ArrayList<String> out, ArrayList<MangaChapter> chapters, float index) {
        if (out == null || chapters == null || chapters.isEmpty()) return;
        LinkedHashSet<String> seen = new LinkedHashSet<>(out);
        MangaChapter exact = findChapterForIndex(chapters, index);
        if (exact != null) addChapterCandidate(out, seen, exact.slug);
        int base = (int) Math.floor(index);
        for (MangaChapter chapter : chapters) {
            if (chapter == null || chapter.slug == null) continue;
            if (Math.abs(chapter.index - index) < 0.0001f) addChapterCandidate(out, seen, chapter.slug);
        }
        for (MangaChapter chapter : chapters) {
            if (chapter == null || chapter.slug == null) continue;
            int chapterBase = (int) Math.floor(chapter.index);
            if (chapterBase == base && chapter.index >= index && chapter.index < base + 1f) addChapterCandidate(out, seen, chapter.slug);
        }
    }

    private static void addChapterCandidate(ArrayList<String> out, LinkedHashSet<String> seen, String slug) {
        if (out == null || seen == null) return;
        String clean = normalizeChapterSlug(slug);
        if (!clean.isEmpty() && seen.add(clean)) out.add(clean);
    }

    private static MangaChapter findChapterForIndex(ArrayList<MangaChapter> chapters, float index) {
        if (chapters == null || chapters.isEmpty()) return null;
        MangaChapter exact = null;
        for (MangaChapter chapter : chapters) if (chapter != null && Math.abs(chapter.index - index) < 0.0001f) { exact = chapter; break; }
        if (exact != null) return exact;
        int base = (int) Math.floor(index);
        MangaChapter best = null;
        for (MangaChapter chapter : chapters) {
            if (chapter == null) continue;
            int chapterBase = (int) Math.floor(chapter.index);
            if (chapterBase != base || chapter.index < index || chapter.index >= base + 1f) continue;
            if (best == null || chapter.index < best.index) best = chapter;
        }
        return best;
    }

    private static String inferMangaSlugFromChapterSlug(String chapterSlug) {
        String clean = normalizeChapterSlug(chapterSlug);
        if (clean.isEmpty()) return "";
        Matcher matcher = Pattern.compile("(?i)^(.+?)-(?:chapter|chapte|cahpter)-[0-9]+(?:-[0-9]+)?(?:-[a-z0-9]+)?$").matcher(clean);
        if (matcher.find()) return normalizeSlug(matcher.group(1));
        int index = clean.toLowerCase(Locale.ROOT).lastIndexOf("-chapter-");
        if (index > 0) return normalizeSlug(clean.substring(0, index));
        index = clean.toLowerCase(Locale.ROOT).lastIndexOf("-chapte-");
        if (index > 0) return normalizeSlug(clean.substring(0, index));
        index = clean.toLowerCase(Locale.ROOT).lastIndexOf("-cahpter-");
        if (index > 0) return normalizeSlug(clean.substring(0, index));
        return "";
    }

    private static String normalizeSlug(String slug) {
        if (slug == null) return "";
        String value = slug.trim();
        if (value.isEmpty()) return "";
        try {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) value = parsed.encodedPath();
        } catch(Exception ignored) { }
        value = value.replaceAll("[?#].*$", "");
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.startsWith("manga/")) value = value.substring("manga/".length());
        if (value.startsWith("series/")) value = value.substring("series/".length());
        if (value.startsWith("comic/")) value = value.substring("comic/".length());
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.trim();
    }

    private static String normalizeChapterSlug(String slug) {
        if (slug == null) return "";
        String value = slug.trim();
        try {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) value = parsed.encodedPath();
        } catch(Exception ignored) { }
        value = value.replaceAll("[?#].*$", "");
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.startsWith("manga/")) value = value.substring("manga/".length());
        if (value.startsWith("chapter/")) value = value.substring("chapter/".length());
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        return value;
    }

    private static String chapterSuffix(String raw, float index) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        value = value.replaceAll("(?i)^chapter\\s*", "").trim();
        Matcher matcher = Pattern.compile("^[0-9]+(?:[.,][0-9]+)?").matcher(value);
        if (matcher.find()) value = value.substring(matcher.end()).trim();
        value = value.replaceAll("^[-:;.,\\s]+", "").trim();
        String idx = MangaChapter.formatIndex(index);
        if (value.equals(idx)) return "";
        return value;
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

    private static float parseChapterIndexFromSlug(String slug) {
        String clean = normalizeChapterSlug(slug);
        if (clean.isEmpty()) return -1f;
        Matcher matcher = Pattern.compile("(?i)(?:chapter|chapte|cahpter)-([0-9]+(?:-[0-9]+)?)").matcher(clean);
        if (matcher.find()) {
            String value = matcher.group(1).replace('-', '.');
            try { return Float.parseFloat(value); } catch(Exception ignored) { }
        }
        return -1f;
    }

    private static void collectPageUrlsFromString(String raw, ArrayList<String> out, LinkedHashSet<String> seen) {
        if (raw == null || raw.trim().isEmpty()) return;
        String value = raw.replace("\\/", "/").replace("&amp;", "&");
        Matcher srcMatcher = IMAGE_SRC_PATTERN.matcher(value);
        while (srcMatcher.find()) addPage(out, seen, srcMatcher.group(1));
        Matcher urlMatcher = IMAGE_URL_PATTERN.matcher(value);
        while (urlMatcher.find()) addPage(out, seen, urlMatcher.group());
    }

    private static void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String url) {
        if (url == null) return;
        String value = url.trim().replace("\\/", "/").replace("&amp;", "&");
        if (value.startsWith("//")) value = "https:" + value;
        if (!value.startsWith("http")) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("readerarea.svg") || lower.contains("/iklan") || lower.contains("banner") || lower.contains("ads")) return;
        if (seen.add(value)) out.add(value);
    }

    private static String guessType(JsonObject item) {
        String text = (joinGenres(item) + " " + getString(item, "title")).toLowerCase(Locale.ROOT);
        if (text.contains("naver") || text.contains("kakao") || text.contains("daum") || text.contains("manhwa") || text.contains("webtoon")) return "MANHWA";
        if (text.contains("manhua")) return "MANHUA";
        if (text.contains("manga") || text.contains("shounen") || text.contains("seinen") || text.contains("shoujo")) return "MANGA";
        return "";
    }

    private static String cleanSynopsis(String raw) {
        if (raw == null) return "";
        return raw.replace("\\r", "\r").replace("\\n", "\n").replaceAll("\\s+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static boolean needsListEnrichment(MangaPost post, boolean loadChapter, boolean loadType) {
        if (post == null) return false;
        if (loadChapter) {
            String chapter = post.latestChapter == null ? "" : post.latestChapter.trim();
            if (chapter.isEmpty()) return true;
        }
        if (loadType) {
            String type = post.getTypeLabel() == null ? "" : post.getTypeLabel().trim();
            if (type.isEmpty()) return true;
        }
        return false;
    }

    private static void finishEnrichment(java.util.concurrent.atomic.AtomicInteger remaining, Runnable done) {
        if (remaining == null) {
            if (done != null) MangaCoroutines.main(done);
            return;
        }
        if (remaining.decrementAndGet() <= 0 && done != null) MangaCoroutines.main(done);
    }

    private static void addInfo(ArrayList<String> out, String label, String value) {
        if (out == null || label == null || value == null) return;
        String clean = value.trim();
        if (!clean.isEmpty()) out.add(label + ": " + clean);
    }

    private static String joinInfo(ArrayList<String> info) {
        StringBuilder sb = new StringBuilder();
        if (info != null) for (String item : info) {
            if (item == null || item.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("||");
            sb.append(item.trim());
        }
        return sb.toString();
    }

    private static String cursorFor(String key, int page) {
        if (key == null || page <= 1) return "";
        synchronized (CURSOR_CACHE) { return CURSOR_CACHE.get(key + "#" + page) == null ? "" : CURSOR_CACHE.get(key + "#" + page); }
    }

    private static void putCursor(String key, int page, String cursor) {
        if (key == null || cursor == null || cursor.trim().isEmpty() || page <= 1) return;
        synchronized (CURSOR_CACHE) { CURSOR_CACHE.put(key + "#" + page, cursor.trim()); }
    }

    private static String encodePath(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20"); }
        catch(Exception ignored) { return value == null ? "" : value; }
    }

    private static String normalizedBase() {
        String value = base();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static JsonObject getObject(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return null;
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray getArray(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return "";
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        try { return element.getAsString().trim(); } catch(Exception ignored) { return ""; }
    }

    private static String redirectLink(JsonObject object) {
        return firstNonEmpty(getString(object, "redirect_link"), getString(object, "redirectLink"));
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        if (object == null || key == null || !object.has(key)) return fallback;
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        try { return element.getAsBoolean(); } catch(Exception ignored) { return fallback; }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
}
