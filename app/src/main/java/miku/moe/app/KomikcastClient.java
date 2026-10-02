package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class KomikcastClient {
    public interface Result<T> { void onSuccess(T data, boolean hasNext); void onError(String message); }
    public static class GenreItem {
        public final String title;
        public final String value;
        public GenreItem(String title, String value) {
            this.title = title == null ? "" : title;
            this.value = value == null ? "" : value;
        }
    }

    public void genres(Result<ArrayList<GenreItem>> cb) {
        get(API + "/genres", new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = new ArrayList<>();
                        JsonArray data = getArray(root, "data");
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        for (JsonElement el : data) {
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject item = el.getAsJsonObject();
                            String id = getString(item, "id");
                            JsonObject genreData = getObject(item, "data");
                            String name = getString(genreData, "name");
                            if (!id.isEmpty() && !name.isEmpty() && seen.add(id)) out.add(new GenreItem(name, id));
                        }
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch (Exception e) {
                        MangaCoroutines.main(() -> cb.onError("Genre gagal dibaca"));
                    }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST); }
    private static final String API = "https://api.voratoon.com";
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

    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    protected String sourceLabel() { return "VoraToon"; }

    public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        if (query == null || query.trim().isEmpty()) {
            // Tanpa query: kalau filter hanya "type:xxx" (atau kosong) ambil langsung dari /browse?format=xxx&page=N
            String browseFormat = null;
            boolean onlyType = true;
            if (genre != null && !genre.trim().isEmpty()) {
                for (String part : genre.trim().split("\\|")) {
                    String token = part.trim();
                    if (token.isEmpty()) continue;
                    if (token.startsWith("type:")) browseFormat = token.substring("type:".length()).trim().toLowerCase(Locale.ROOT);
                    else { onlyType = false; break; }
                }
            }
            if (onlyType) {
                listVoratoonBrowse(page, sort, browseFormat, cb);
                return;
            }
        }
        try {
            StringBuilder url = new StringBuilder(API + "/series?includeMeta=true&takeChapter=1&take=30&page=" + page);
            String safeSort = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim().toLowerCase(Locale.ROOT);
            String sortOrder = "desc";
            // Nilai sort yang terbukti diterima API (HAR website): latest, popularity, bookmarks.
            // Dulu "popular" dikirim sebagai "totalViews" (tidak dikenal API) sehingga jatuh ke urutan terbaru.
            if (safeSort.equals("popular") || safeSort.equals("popularity") || safeSort.equals("views") || safeSort.equals("totalviews")) safeSort = "popularity";
            else if (safeSort.equals("bookmark") || safeSort.equals("bookmarks")) safeSort = "bookmarks";
            else if (safeSort.equals("newseries") || safeSort.equals("new") || safeSort.equals("created")) safeSort = "created"; // terbukti di HAR: New Series = sort=created
            else safeSort = "latest"; // project/mirror difilter di sisi app (lihat wantedType); rating & az tidak ada di HAR untuk API, hanya lewat halaman /browse
            final String wantedType = "project".equals(sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT)) ? "project"
                    : "mirror".equals(sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT)) ? "mirror" : "";
            url.append("&sort=").append(URLEncoder.encode(safeSort, "UTF-8")).append("&sortOrder=").append(sortOrder);
            if (query != null && !query.trim().isEmpty()) {
                url.append("&title=").append(URLEncoder.encode(query.trim(), "UTF-8"));
            }
            if (genre != null && !genre.trim().isEmpty()) {
                String rawFilter = genre.trim();
                StringBuilder apiFilter = new StringBuilder();
                for (String part : rawFilter.split("\\|")) {
                    String token = part.trim();
                    if (token.isEmpty()) continue;
                    if (token.startsWith("type:")) {
                        String format = token.substring("type:".length()).trim();
                        if (!format.isEmpty()) url.append("&format=").append(URLEncoder.encode(format, "UTF-8"));
                    } else if (token.startsWith("status:")) {
                        String status = token.substring("status:".length()).trim();
                        if (!status.isEmpty()) url.append("&status=").append(URLEncoder.encode(status, "UTF-8"));
                    } else {
                        if (apiFilter.length() > 0) apiFilter.append(';');
                        apiFilter.append("genreIds==").append(token);
                    }
                }
                if (apiFilter.length() > 0) url.append("&filter=").append(URLEncoder.encode(apiFilter.toString(), "UTF-8"));
            }
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 12); return; }
            get(key, new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            JsonArray data = getArray(root, "data");
                            for (JsonElement el : data) if (el != null && el.isJsonObject()) {
                                if (!wantedType.isEmpty()) {
                                    JsonObject itemData = getObject(el.getAsJsonObject(), "data");
                                    if (itemData != null && !wantedType.equalsIgnoreCase(getString(itemData, "type"))) continue;
                                }
                                MangaPost p = parsePost(el.getAsJsonObject());
                                String k = !p.slug.isEmpty() ? p.slug : p.title;
                                if (!k.isEmpty() && seen.add(k)) out.add(p);
                            }
                            boolean hasNext = false;
                            JsonObject meta = getObject(root, "meta");
                            if (meta != null) {
                                int p = getInt(meta, "page", page); int last = getInt(meta, "lastPage", page);
                                hasNext = p < last;
                            }
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            boolean finalHasNext = hasNext;
                            MangaCoroutines.main(() -> cb.onSuccess(out, finalHasNext));
                        } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar manga gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch (Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    private void listVoratoonBrowse(int page, String sort, String format, Result<ArrayList<MangaPost>> cb) {
        String browseUrl = voratoonBrowseUrl(page, sort, format);
        ArrayList<MangaPost> cached = LIST_CACHE.get(browseUrl);
        if (cached != null) {
            cb.onSuccess(new ArrayList<>(cached), cached.size() >= 24);
            return;
        }
        getHtml(browseUrl, new Result<String>() {
            @Override public void onSuccess(String html, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaPost> out = new ArrayList<>();
                        boolean hasNext = false;
                        // 1) Sumber utama: data series yang dikirim server untuk URL ini (sort/type/format sudah diterapkan server)
                        JsonObject initial = extractInitialData(html);
                        if (initial != null) {
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            JsonArray series = getArray(initial, "series");
                            for (JsonElement el : series) if (el != null && el.isJsonObject()) {
                                MangaPost post = parsePost(el.getAsJsonObject());
                                String key = !post.slug.isEmpty() ? post.slug : post.title;
                                if (key.isEmpty() || !seen.add(key)) continue;
                                post.latestChapter = latestChapterFromSeries(el.getAsJsonObject());
                                out.add(post);
                            }
                            JsonObject meta = getObject(initial, "seriesMeta");
                            if (meta != null) hasNext = getInt(meta, "page", page) < getInt(meta, "lastPage", page);
                        }
                        // 2) Cadangan: markup kartu lama
                        if (out.isEmpty()) {
                            Document document = Jsoup.parse(html, browseUrl);
                            parseLegacyCards(document, out);
                            hasNext = hasVoratoonNextPage(document);
                        }
                        if (out.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Daftar manga kosong atau format halaman berubah")); return; }
                        LIST_CACHE.put(browseUrl, new ArrayList<>(out));
                        boolean finalHasNext = hasNext;
                        MangaCoroutines.main(() -> cb.onSuccess(out, finalHasNext));
                    } catch (Exception e) {
                        MangaCoroutines.main(() -> cb.onError("Daftar manga gagal dibaca"));
                    }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void parseLegacyCards(Document document, ArrayList<MangaPost> out) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element card : document.select("article.card")) {
            Element titleLink = card.selectFirst("a.card-title");
            if (titleLink == null) continue;
            String title = titleLink.text().trim();
            String slug = extractSlug(titleLink.attr("abs:href").trim());
            if (slug.isEmpty()) continue;
            Element coverLink = card.selectFirst("a.card-image-wrapper");
            Element image = coverLink == null ? null : coverLink.selectFirst("img:not(.comic-type-flag)");
            String cover = image == null ? "" : image.attr("src").trim();
            Element chapterElement = card.selectFirst(".tag-chapters");
            Element statusElement = card.selectFirst(".tag-status");
            String chapter = chapterElement == null ? "" : chapterElement.text().trim();
            String status = normalizeVoratoonStatus(statusElement == null ? "" : statusElement.text().trim());
            String type = image == null ? "" : inferTypeFromFlag(card);
            MangaPost post = new MangaPost(slug, title, cover, "", status, "", "", type, chapter, "").withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST, "VoraToon");
            post.latestChapter = chapter;
            if (seen.add(slug)) out.add(post);
        }
    }

    private static String latestChapterFromSeries(JsonObject item) {
        try {
            JsonArray chapters = getArray(item, "chapters");
            if (chapters == null || chapters.size() == 0 || !chapters.get(0).isJsonObject()) return "";
            JsonObject first = chapters.get(0).getAsJsonObject();
            JsonElement index = first.get("chapterIndex");
            if (index == null || index.isJsonNull()) return "";
            String value = index.getAsString().trim();
            return value.isEmpty() ? "" : "Chapter " + value;
        } catch (Exception e) { return ""; }
    }

    /** Next.js App Router menaruh data halaman di self.__next_f.push([1,"..."]); gabungkan lalu ambil "initialData". */
    private static JsonObject extractInitialData(String html) {
        if (html == null || html.isEmpty()) return null;
        StringBuilder flight = new StringBuilder();
        String marker = "self.__next_f.push([1,\"";
        int from = 0;
        while (true) {
            int start = html.indexOf(marker, from);
            if (start < 0) break;
            int quote = start + marker.length() - 1;
            int i = quote + 1;
            while (i < html.length()) {
                char c = html.charAt(i);
                if (c == '\\') { i += 2; continue; }
                if (c == '"') break;
                i++;
            }
            if (i >= html.length()) break;
            try { flight.append(JsonParser.parseString(html.substring(quote, i + 1)).getAsString()); } catch (Exception ignored) { }
            from = i + 1;
        }
        // Kalau respons sudah berupa payload RSC mentah (bukan HTML), pakai langsung.
        String text = flight.length() > 0 ? flight.toString() : html;
        int key = text.indexOf("\"initialData\":");
        if (key < 0) return null;
        int begin = text.indexOf('{', key);
        if (begin < 0) return null;
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int k = begin; k < text.length(); k++) {
            char c = text.charAt(k);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                try { return JsonParser.parseString(text.substring(begin, k + 1)).getAsJsonObject(); } catch (Exception e) { return null; }
            }
        }
        return null;
    }

    private static String voratoonBrowseUrl(int page, String sort, String format) {
        String safeSort = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String safeFormat = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        // Parameter sama seperti website v5: ?format=..&type=project|mirror&sort=popular|bookmark|rating|az|newseries&page=N
        StringBuilder params = new StringBuilder();
        if (!safeFormat.isEmpty()) params.append("format=").append(safeFormat);
        String typeParam = "";
        String sortParam = "";
        switch (safeSort) {
            case "project": typeParam = "project"; break;
            case "mirror": typeParam = "mirror"; break;
            case "popular": case "popularity": case "views": sortParam = "popular"; break;
            case "bookmark": case "bookmarks": sortParam = "bookmark"; break;
            case "rating": sortParam = "rating"; break;
            case "az": case "za": case "title": sortParam = "az"; break;
            case "newseries": case "new": sortParam = "newseries"; break;
            default: break; // latest = tanpa parameter sort
        }
        if (!typeParam.isEmpty()) { if (params.length() > 0) params.append('&'); params.append("type=").append(typeParam); }
        if (!sortParam.isEmpty()) { if (params.length() > 0) params.append('&'); params.append("sort=").append(sortParam); }
        if (page > 1) { if (params.length() > 0) params.append('&'); params.append("page=").append(page); }
        return base() + "/browse" + (params.length() > 0 ? "?" + params : "");
    }

    private static String extractSlug(String href) {
        if (href == null || href.trim().isEmpty()) return "";
        String value = href.trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) value = value.substring(0, queryIndex);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : "";
    }

    private static String inferTypeFromFlag(Element card) {
        Element image = card.selectFirst("img.comic-type-flag");
        if (image == null) return "";
        String alt = image.attr("alt").trim().toLowerCase(Locale.ROOT);
        if (alt.contains("mangatoon")) return "MANGATOON";
        if (alt.contains("manhwa")) return "MANHWA";
        if (alt.contains("manhua")) return "MANHUA";
        if (alt.contains("manga")) return "MANGA";
        return "";
    }

    private static boolean hasVoratoonNextPage(Document document) {
        Element next = document.selectFirst("nav[aria-label=Pagination] a[aria-label=Halaman berikutnya]");
        return next != null && !next.attr("href").trim().isEmpty();
    }

    public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) {
            if (p != null && (p.latestChapter == null || p.latestChapter.trim().isEmpty()) && p.slug != null && !p.slug.isEmpty()) {
                remaining.incrementAndGet();
            }
        }
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
                @Override public void onError(String message) {
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
            });
        }
    }

    public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        get(API + "/series/" + slug, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        if (data == null) { MangaCoroutines.main(() -> cb.onError("Detail kosong")); return; }
                        MangaPost parsed = parsePost(data); DETAIL_CACHE.put(slug, parsed); MangaCoroutines.main(() -> cb.onSuccess(parsed, false));
                    } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Detail manga gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        get(API + "/series/" + slug + "/chapters", new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonArray data = getArray(root, "data");
                        for (JsonElement el : data) {
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject item = el.getAsJsonObject(); JsonObject d = getObject(item, "data"); if (d == null) d = item;
                            float idx = getFloat(d, "index", getFloat(item, "chapterIndex", -1));
                            if (idx < 0) continue;
                            String key = MangaChapter.formatIndex(idx);
                            if (!seen.add(key)) continue;
                            String rawDate = firstNonEmpty(getString(item, "createdAt"), getString(item, "updatedAt"), getString(d, "createdAt"), getString(d, "updatedAt"));
                            out.add(new MangaChapter(slug, idx, getString(d, "title"), prettyDate(rawDate)));
                        }
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out)); MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = slug + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        get(API + "/series/" + slug + "/chapters/" + MangaChapter.formatIndex(index), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonObject item = getObject(root, "data");
                        if (item == null) { MangaCoroutines.main(() -> cb.onError("Chapter kosong")); return; }
                        JsonObject d = getObject(item, "data"); if (d == null) d = item;
                        JsonArray images = getArray(d, "images");
                        for (JsonElement image : images) if (image != null && !image.isJsonNull()) {
                            String url = image.getAsString();
                            if (url != null && url.startsWith("http") && seen.add(url)) out.add(url);
                        }
                        PAGE_CACHE.put(pageKey, new ArrayList<>(out)); MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman chapter gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private static final String VORATOON_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36";
    private static final String VORATOON_SEC_CH_UA = "\"Chromium\";v=\"153\", \"Not_A Brand\";v=\"8\"";
    private static String voratoonReferer() { return base() + "/"; }

    private void getHtml(String url, Result<String> cb) {
        Request req = new Request.Builder().url(url)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "id-ID,id;q=0.7")
                .header("Referer", voratoonReferer())
                .header("Origin", base())
                .header("User-Agent", VORATOON_USER_AGENT)
                .header("sec-ch-ua", VORATOON_SEC_CH_UA)
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Linux\"")
                .header("sec-fetch-site", "same-origin")
                .header("sec-fetch-mode", "navigate")
                .header("sec-fetch-dest", "document")
                .build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code())); return; }
                MangaCoroutines.main(() -> cb.onSuccess(body, false));
            }
        });
    }

    private void get(String url, Result<JsonObject> cb) {
        Request req = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Accept-Language", "id-ID,id;q=0.7")
                .header("Referer", voratoonReferer())
                .header("Origin", base())
                .header("User-Agent", VORATOON_USER_AGENT)
                .header("sec-ch-ua", VORATOON_SEC_CH_UA)
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Linux\"")
                .header("sec-fetch-site", "same-site")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-dest", "empty")
                .header("sec-gpc", "1")
                .build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code())); return; }
                try { JsonObject obj = JsonParser.parseString(body).getAsJsonObject(); MangaCoroutines.main(() -> cb.onSuccess(obj, false)); }
                catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Data manga gagal dibaca")); }
            }
        });
    }

    private MangaPost parsePost(JsonObject item) {
        JsonObject d = getObject(item, "data");
        if (d == null) d = item;
        String slug = getString(d, "slug");
        ArrayList<String> gs = new ArrayList<>();
        JsonArray genres = getArray(d, "genres");
        for (JsonElement g : genres) {
            if (g == null || !g.isJsonObject()) continue;
            JsonObject genreItem = g.getAsJsonObject();
            JsonObject genreData = getObject(genreItem, "data");
            String name = getString(genreData, "name");
            if (!name.isEmpty()) gs.add(name);
        }
        String genre = android.text.TextUtils.join(", ", gs);
        String format = getString(d, "format");
        String typeLabel = format.isEmpty() ? inferTypeFromGenres(genre) : format;
        MangaPost post = new MangaPost(slug, getString(d, "title"), getString(d, "coverImage"), getString(d, "author"), normalizeVoratoonStatus(getString(d, "status")), getString(d, "synopsis"), genre, typeLabel, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST, "VoraToon");
        post.totalChapters = getInt(d, "totalChapters", 0);
        String releaseDate = getString(d, "releaseDate");
        if (!releaseDate.isEmpty()) post.info = "Rilis: " + releaseDate;
        return post;
    }

    private static String normalizeVoratoonStatus(String value) {
        String status = value == null ? "" : value.trim();
        if (status.isEmpty()) return "";
        if (status.equalsIgnoreCase("ongoing")) return "Ongoing";
        if (status.equalsIgnoreCase("completed")) return "Completed";
        if (status.equalsIgnoreCase("hiatus")) return "Hiatus";
        if (status.equalsIgnoreCase("cancelled") || status.equalsIgnoreCase("canceled")) return "Cancelled";
        return status.substring(0, 1).toUpperCase(Locale.ROOT) + status.substring(1);
    }

    private static String inferTypeFromGenres(String genre) {
        String text = genre == null ? "" : genre.toLowerCase(Locale.ROOT);
        if (text.contains("manhwa")) return "manhwa";
        if (text.contains("manhua")) return "manhua";
        if (text.contains("webtoon")) return "webtoon";
        return "manga";
    }
    public static String prettyDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Date date = parseDate(raw.trim());
        if (date == null) return raw;
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - date.getTime()) / 1000L);
        if (elapsedSeconds < 10L) return "Baru saja";
        if (elapsedSeconds < 60L) return elapsedSeconds + " detik lalu";
        long elapsedMinutes = elapsedSeconds / 60L;
        if (elapsedMinutes < 60L) return elapsedMinutes + " menit lalu";
        long elapsedHours = elapsedMinutes / 60L;
        if (elapsedHours < 24L) return elapsedHours + " jam lalu";
        long elapsedDays = elapsedHours / 24L;
        if (elapsedDays < 30L) return elapsedDays + " hari lalu";
        long elapsedMonths = elapsedDays / 30L;
        if (elapsedMonths < 12L) return elapsedMonths + " bulan lalu";
        return (elapsedMonths / 12L) + " tahun lalu";
    }

    private static Date parseDate(String raw) {
        String[] patterns = new String[]{
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mmXXX",
                "yyyy-MM-dd HH:mm:ss"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat input = new SimpleDateFormat(pattern, Locale.ROOT);
                input.setLenient(false);
                input.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = input.parse(raw);
                if (date != null) return date;
            } catch (Exception ignored) { }
        }
        return null;
    }
    private static JsonObject getObject(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; } catch(Exception e){return null;} }
    private static JsonArray getArray(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); } catch(Exception e){return new JsonArray();} }
    private static String getString(JsonObject o, String k) { try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; } catch(Exception e){return "";} }
    private static String firstNonEmpty(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return ""; }
    private static int getInt(JsonObject o, String k, int def) { try { return o.has(k) ? Integer.parseInt(o.get(k).getAsString()) : def; } catch(Exception e){return def;} }
    private static float getFloat(JsonObject o, String k, float def) { try { return o.has(k) ? Float.parseFloat(o.get(k).getAsString()) : def; } catch(Exception e){return def;} }
}
