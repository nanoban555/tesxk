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
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Apkomik extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_APKOMIK); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36";
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    public static void clearSessionCaches() {
        DETAIL_CACHE.clear();
        CHAPTER_CACHE.clear();
        PAGE_CACHE.clear();
        LIST_CACHE.clear();
        GENRE_CACHE.clear();
    }

    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Apkomik"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            boolean searching = query != null && !query.trim().isEmpty();
            HttpUrl url = buildListUrl(safePage, sort, query, genre);
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null && !cached.isEmpty()) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= expectedPageSize(sort, query, genre)); return; }
            String requestType = displayTypeLabel(extractTypeFilter(genre));
            loadListUrl(key, safePage, sort, query, genre, requestType, searching, false, cb);
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    private void loadListUrl(String url, int page, String sort, String query, String genre, String fallbackType, boolean searching, boolean alreadyFallback, Result<ArrayList<MangaPost>> cb) {
        getDocument(url, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaPost> out = parseListPosts(document, fallbackType);
                        boolean next = hasNextPage(document);
                        if (!out.isEmpty()) {
                            LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                            return;
                        }
                        HttpUrl fallback = alreadyFallback ? null : buildFallbackListUrl(page, sort, query, genre);
                        if (fallback != null && !fallback.toString().equals(url)) {
                            MangaCoroutines.main(() -> loadListUrl(fallback.toString(), page, sort, query, genre, fallbackType, searching, true, cb));
                            return;
                        }
                        if (searching && page == 1) {
                            String safeQuery = query == null ? "" : query.trim();
                            MangaCoroutines.main(() -> searchAjax(safeQuery, new Result<ArrayList<MangaPost>>() {
                                @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                                    if (data != null && !data.isEmpty()) {
                                        LIST_CACHE.put(url, new ArrayList<>(data));
                                        cb.onSuccess(data, hasNext);
                                    } else cb.onError("Pencarian Apkomik kosong. Endpoint berhasil tetapi tidak ada item untuk: " + safeQuery);
                                }
                                @Override public void onError(String message) { cb.onError(message); }
                            }));
                            return;
                        }
                        MangaCoroutines.main(() -> cb.onError("Daftar Apkomik kosong. Endpoint berhasil tetapi selector tidak menemukan item: " + url));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Apkomik gagal dibaca")); }
                });
            }
            @Override public void onError(String message) {
                if (searching && page == 1) {
                    String safeQuery = query == null ? "" : query.trim();
                    searchAjax(safeQuery, cb);
                } else cb.onError(message);
            }
        });
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null && !cached.isEmpty()) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(base() + "/manga/?status=&type=&order=update", new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(document);
                        if (!out.isEmpty()) GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> {
                            if (!out.isEmpty()) cb.onSuccess(out, false);
                            else cb.onError("Genre Apkomik kosong dari endpoint filter");
                        });
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Genre Apkomik gagal dibaca")); }
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
        String detailUrl = toAbsolute(slug);
        getDocument(detailUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(slug, document);
                        ArrayList<MangaChapter> chapters = parseChapters(document);
                        String postId = postId(document);
                        if (chapters.isEmpty() && !postId.isEmpty()) {
                            MangaCoroutines.main(() -> getChaptersAjax(postId, detailUrl, new Result<ArrayList<MangaChapter>>() {
                                @Override public void onSuccess(ArrayList<MangaChapter> ajaxChapters, boolean hasNext) { finishDetail(slug, post, ajaxChapters, cb); }
                                @Override public void onError(String message) { finishDetail(slug, post, chapters, cb); }
                            }));
                        } else {
                            MangaCoroutines.main(() -> finishDetail(slug, post, chapters, cb));
                        }
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Apkomik gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void finishDetail(String slug, MangaPost post, ArrayList<MangaChapter> chapters, Result<MangaPost> cb) {
        ArrayList<MangaChapter> safeChapters = chapters == null ? new ArrayList<>() : chapters;
        if (post == null || ((post.title == null || post.title.trim().isEmpty()) && safeChapters.isEmpty())) {
            cb.onError("Detail Apkomik kosong");
            return;
        }
        post.totalChapters = safeChapters.size();
        DETAIL_CACHE.put(slug, post);
        if (!safeChapters.isEmpty()) CHAPTER_CACHE.put(slug, new ArrayList<>(safeChapters));
        cb.onSuccess(post, false);
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null && !cached.isEmpty()) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String detailUrl = toAbsolute(slug);
        getDocument(detailUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = parseChapters(document);
                        if (!out.isEmpty()) {
                            CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            return;
                        }
                        String postId = postId(document);
                        if (!postId.isEmpty()) {
                            MangaCoroutines.main(() -> getChaptersAjax(postId, detailUrl, new Result<ArrayList<MangaChapter>>() {
                                @Override public void onSuccess(ArrayList<MangaChapter> ajaxChapters, boolean hasNext) {
                                    if (ajaxChapters != null && !ajaxChapters.isEmpty()) {
                                        CHAPTER_CACHE.put(slug, new ArrayList<>(ajaxChapters));
                                        cb.onSuccess(ajaxChapters, false);
                                    } else cb.onError("Chapter Apkomik kosong dari AJAX get_chapters");
                                }
                                @Override public void onError(String message) { cb.onError(message); }
                            }));
                        } else MangaCoroutines.main(() -> cb.onError("Chapter Apkomik kosong dan post_id tidak ditemukan"));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter Apkomik gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = slug + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null && !cached.isEmpty()) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = null;
                if (chapters != null) for (MangaChapter ch : chapters) if (Math.abs(ch.index - index) < 0.0001f) { chapter = ch; break; }
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter Apkomik tidak ditemukan"); return; }
                String chapterUrl = toAbsolute(chapter.slug);
                getDocument(chapterUrl, new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                if (out.isEmpty()) { MangaCoroutines.main(() -> cb.onError("Halaman Apkomik kosong dari reader")); return; }
                                for (String pageUrl : out) MangaImageLoader.registerImageReferer(pageUrl, chapterUrl);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Apkomik gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private HttpUrl buildListUrl(int page, String sort, String query, String genre) throws Exception {
        boolean searching = query != null && !query.trim().isEmpty();
        String genreId = extractGenreFilter(genre);
        String typeFilter = extractTypeFilter(genre);
        String statusFilter = extractStatusFilter(genre);
        boolean filteringGenre = !genreId.isEmpty();
        boolean filteringType = !typeFilter.isEmpty();
        boolean filteringStatus = !statusFilter.isEmpty();
        if (searching) {
            String path = page > 1 ? base() + "/page/" + page + "/" : base() + "/";
            HttpUrl.Builder builder = HttpUrl.parse(path).newBuilder();
            builder.addQueryParameter("s", query.trim());
            return builder.build();
        }
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (!filteringGenre && !filteringType && !filteringStatus) {
            if (s.isEmpty() || "latest".equals(s) || "update".equals(s) || "terbaru".equals(s)) return pagedPath("/manga-terbaru/", page);
            if ("project".equals(s) || "projects".equals(s)) return pagedPath("/project/", page);
            if ("manga".equals(s)) return pagedPath("/manga-terbaru/", page);
            if ("manhwa".equals(s)) return pagedPath("/manhwa-terbaru/", page);
            if ("manhua".equals(s)) return pagedPath("/manhua-terbaru/", page);
        }
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/manga/").newBuilder();
        if (page > 1) builder.addQueryParameter("page", String.valueOf(page));
        if (filteringGenre) builder.addQueryParameter("genre[]", genreId);
        String order = orderParam(sort);
        if (filteringStatus) builder.addQueryParameter("status", statusFilter);
        if (filteringType) builder.addQueryParameter("type", typeFilter);
        if (!filteringGenre && !filteringType && !filteringStatus && "update".equals(order)) {
            builder.addQueryParameter("status", "");
            builder.addQueryParameter("type", "");
            builder.addQueryParameter("order", "update");
        } else {
            builder.addQueryParameter("order", filteringGenre && "update".equals(order) ? "" : order);
        }
        return builder.build();
    }

    private HttpUrl buildFallbackListUrl(int page, String sort, String query, String genre) {
        try {
            if (query != null && !query.trim().isEmpty()) return null;
            String genreId = extractGenreFilter(genre);
            String typeFilter = extractTypeFilter(genre);
            String statusFilter = extractStatusFilter(genre);
            String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
            if (genreId.isEmpty() && typeFilter.isEmpty() && statusFilter.isEmpty()) {
                if (s.isEmpty() || "latest".equals(s) || "update".equals(s) || "terbaru".equals(s)) return advancedUrl(page, "", "", "update");
                if ("manga".equals(s) || "manhwa".equals(s) || "manhua".equals(s)) return advancedUrl(page, "", s, "update");
            }
        } catch(Exception ignored) { }
        return null;
    }

    private HttpUrl advancedUrl(int page, String genreId, String type, String order) {
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/manga/").newBuilder();
        if (page > 1) builder.addQueryParameter("page", String.valueOf(page));
        if (genreId != null && !genreId.trim().isEmpty()) builder.addQueryParameter("genre[]", genreId.trim());
        String safeType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String safeOrder = order == null ? "" : order.trim().toLowerCase(Locale.ROOT);
        if (genreId == null || genreId.trim().isEmpty()) {
            builder.addQueryParameter("status", "");
            builder.addQueryParameter("type", safeType);
        } else if (!safeType.isEmpty()) {
            builder.addQueryParameter("type", safeType);
        }
        builder.addQueryParameter("order", safeOrder);
        return builder.build();
    }

    private String orderParam(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s) || "views".equals(s)) return "popular";
        if ("az".equals(s) || "a-z".equals(s) || "title".equals(s)) return "title";
        if ("za".equals(s) || "z-a".equals(s) || "titlereverse".equals(s)) return "titlereverse";
        if ("added".equals(s) || "latest_added".equals(s) || "new".equals(s)) return "latest";
        if ("latest".equals(s) || "update".equals(s) || "terbaru".equals(s) || s.isEmpty()) return "update";
        return "update";
    }

    private HttpUrl pagedPath(String path, int page) {
        String safePath = path.startsWith("/") ? path : "/" + path;
        if (!safePath.endsWith("/")) safePath = safePath + "/";
        String url = page > 1 ? base() + safePath + "page/" + page + "/" : base() + safePath;
        return HttpUrl.parse(url);
    }

    private ArrayList<MangaPost> parseListPosts(Document document, String fallbackType) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : document.select(".listupd .bs, .mrgn .bs")) {
            MangaPost post = parseListPost(element, fallbackType);
            String unique = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
            if (!unique.isEmpty() && seen.add(unique)) out.add(post);
        }
        return out;
    }

    private MangaPost parseListPost(Element element, String fallbackType) {
        Element link = element.selectFirst(".bsx > a[href], a[href]");
        String slug = link == null ? "" : withoutDomain(link.attr("abs:href"));
        String title = text(element.selectFirst(".tt, .titleheading h2, h2"));
        if (title.isEmpty() && link != null) title = link.attr("title").trim();
        String cover = image(element.selectFirst(".limit img, img"));
        String type = parseType(element.selectFirst(".limit .type, .type"));
        if (type.isEmpty() && fallbackType != null && !fallbackType.trim().isEmpty()) type = fallbackType.trim();
        String latest = text(element.selectFirst(".epxs, .adds .epxs"));
        MangaPost post = new MangaPost(slug, title, cover, "", "", "", "", type, latest, "").withSource(MangaSettingsManager.MANGA_SOURCE_APKOMIK, "Apkomik");
        if (type.isEmpty()) post.typeLabel = "";
        return post;
    }

    private MangaPost parseSearchPost(JsonObject item) {
        String slug = withoutDomain(jsonString(item, "post_link"));
        String title = jsonString(item, "post_title");
        String cover = jsonString(item, "post_image");
        if (cover.isEmpty()) cover = imageFromHtml(jsonString(item, "post_image_html"));
        String genre = jsonString(item, "post_genres");
        String status = jsonString(item, "post_status");
        String type = jsonString(item, "post_type");
        String latest = jsonString(item, "post_latest");
        if (!latest.isEmpty()) {
            String prefix = jsonString(item, "post_ch");
            if (prefix.isEmpty()) prefix = "Chapter ";
            latest = prefix.trim().replaceAll("\\s+$", "") + (prefix.trim().endsWith(".") ? " " : " ") + latest.trim();
            latest = latest.replace("Ch. ", "Chapter ").trim();
        }
        MangaPost post = new MangaPost(slug, title, cover, "", status, "", genre, type, latest, "").withSource(MangaSettingsManager.MANGA_SOURCE_APKOMIK, "Apkomik");
        if (type.isEmpty()) post.typeLabel = "";
        return post;
    }

    private MangaPost parseDetail(String slug, Document document) {
        String title = text(document.selectFirst(".infox h1.entry-title, h1.entry-title"));
        String author = fieldValue(document, "Author");
        String status = firstNonEmpty(infoValue(document, "Status"), fieldValue(document, "Status"));
        String type = firstNonEmpty(infoValue(document, "Type"), fieldValue(document, "Type"));
        String genre = joinText(document.select(".infox .mgen a, .mgen a"));
        String synopsis = text(document.selectFirst(".infox .entry-content-single p, .entry-content-single p"));
        String cover = image(document.selectFirst(".bigcontent .thumb img, .thumbook .thumb img, .infox img"));
        MangaPost post = new MangaPost(slug, title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_APKOMIK, "Apkomik");
        if (type.isEmpty()) post.typeLabel = "";
        Element latest = document.selectFirst("#chapterlist li .chapternum, select[name=chapter] option[value]");
        if (latest != null) post.latestChapter = text(latest);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : document.select("#chapterlist li")) {
            Element urlElement = element.selectFirst(".eph-num a[href], a[href]");
            if (urlElement == null) continue;
            String url = withoutDomain(urlElement.attr("abs:href"));
            String name = text(urlElement.selectFirst(".chapternum"));
            if (name.isEmpty()) name = text(urlElement);
            float index = parseChapterIndex(firstNonEmpty(element.attr("data-num"), name), out.size() + 1);
            String date = text(urlElement.selectFirst(".chapterdate"));
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, date));
        }
        if (out.isEmpty()) out.addAll(parseChapterOptions(document, seen));
        return out;
    }

    private ArrayList<MangaChapter> parseChapterOptions(Document document, LinkedHashSet<String> seen) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> localSeen = seen == null ? new LinkedHashSet<>() : seen;
        for (Element option : document.select("select[name=chapter] option[value], select#chapter option[value], option[data-id][value]")) {
            String value = option.attr("value").trim();
            if (value.isEmpty() || value.equals("#") || value.startsWith("?")) continue;
            String url = withoutDomain(option.attr("abs:value"));
            if (url.isEmpty() || !url.contains("chapter")) url = withoutDomain(value);
            String name = text(option);
            if (name.isEmpty() || name.equalsIgnoreCase("Select Chapter")) continue;
            float index = parseChapterIndex(firstNonEmpty(option.attr("data-num"), name), out.size() + 1);
            if (!url.isEmpty() && localSeen.add(url)) out.add(new MangaChapter(url, index, name, ""));
        }
        return out;
    }

    private ArrayList<MangaChapter> parseChapterOptionsHtml(String html) {
        Document doc = Jsoup.parse("<select name=\"chapter\">" + (html == null ? "" : html) + "</select>", base() + "/");
        return parseChapterOptions(doc, new LinkedHashSet<>());
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String html = document.outerHtml();
        try {
            Matcher tsReader = Pattern.compile("ts_reader\\.run\\((\\{.*?\\})\\);", Pattern.DOTALL).matcher(html);
            while (tsReader.find()) {
                JsonObject root = JsonParser.parseString(tsReader.group(1)).getAsJsonObject();
                JsonArray sources = jsonArray(root, "sources");
                for (JsonElement sourceElement : sources) {
                    if (sourceElement == null || !sourceElement.isJsonObject()) continue;
                    JsonArray images = jsonArray(sourceElement.getAsJsonObject(), "images");
                    for (JsonElement imageElement : images) if (imageElement != null && !imageElement.isJsonNull()) addPage(out, seen, imageElement.getAsString());
                }
            }
        } catch(Exception ignored) { }
        Matcher matcher = Pattern.compile("\\\"images\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(html);
        while (matcher.find()) {
            Matcher imageMatcher = Pattern.compile("\\\"(https?:\\\\/\\\\/[^\\\"]+)\\\"").matcher(matcher.group(1));
            while (imageMatcher.find()) addPage(out, seen, imageMatcher.group(1).replace("\\/", "/"));
        }
        Matcher directImageMatcher = Pattern.compile("https?:\\\\/\\\\/(?:cdnap\\.site|01\\.apkomik\\.com)\\\\/[^\\\"'<>\\s)]+", Pattern.CASE_INSENSITIVE).matcher(html);
        while (directImageMatcher.find()) addPage(out, seen, directImageMatcher.group().replace("\\/", "/"));
        if (out.isEmpty()) {
            Elements images = document.select("#readerarea img, .readerarea img, .chapterbody img, .entry-content-single img");
            for (Element img : images) addPage(out, seen, image(img));
        }
        return out;
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element input : document.select("input.genre-item[name=\"genre[]\"]")) {
            String value = input.attr("value").trim();
            if (value.isEmpty() || !seen.add(value)) continue;
            String label = "";
            String id = input.attr("id");
            if (!id.isEmpty()) label = text(document.selectFirst("label[for=\"" + id + "\"]"));
            if (label.isEmpty()) label = text(input.parent());
            if (!label.isEmpty()) out.add(new GenreItem(label, value));
        }
        return out;
    }

    private void searchAjax(String query, Result<ArrayList<MangaPost>> cb) {
        RequestBody body = new FormBody.Builder().add("action", "ts_ac_do_search").add("ts_ac_query", query == null ? "" : query.trim()).build();
        postForm(base() + "/", body, "*/*", new Result<String>() {
            @Override public void onSuccess(String raw, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaPost> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                        JsonArray sections = jsonArray(root, "series");
                        for (JsonElement sectionElement : sections) {
                            if (sectionElement == null || !sectionElement.isJsonObject()) continue;
                            JsonArray all = jsonArray(sectionElement.getAsJsonObject(), "all");
                            for (JsonElement itemElement : all) {
                                if (itemElement == null || !itemElement.isJsonObject()) continue;
                                MangaPost post = parseSearchPost(itemElement.getAsJsonObject());
                                String unique = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
                                if (!unique.isEmpty() && seen.add(unique)) out.add(post);
                            }
                        }
                        boolean hasNext = out.size() >= 10;
                        MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("AJAX search Apkomik gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void getChaptersAjax(String postId, String referer, Result<ArrayList<MangaChapter>> cb) {
        RequestBody body = new FormBody.Builder().add("action", "get_chapters").add("id", postId == null ? "" : postId.trim()).build();
        postForm(referer, body, "*/*", new Result<String>() {
            @Override public void onSuccess(String raw, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = parseChapterOptionsHtml(raw);
                        MangaCoroutines.main(() -> {
                            if (!out.isEmpty()) cb.onSuccess(out, false);
                            else cb.onError("AJAX chapter Apkomik kosong");
                        });
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("AJAX chapter Apkomik gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void getDocument(String url, Result<Document> cb) {
        Request req = new Request.Builder().url(url)
                .header("Referer", base() + "/")
                .header("Origin", base())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", USER_AGENT)
                .build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                if (body.trim().isEmpty()) { MAIN.post(() -> cb.onError("Respons Apkomik kosong")); return; }
                try { Document document = Jsoup.parse(body, url); MAIN.post(() -> cb.onSuccess(document, false)); }
                catch(Exception e) { MAIN.post(() -> cb.onError("Data Apkomik gagal dibaca")); }
            }
        });
    }

    private void postForm(String referer, RequestBody body, String accept, Result<String> cb) {
        String safeReferer = referer == null || referer.trim().isEmpty() ? base() + "/" : referer.trim();
        Request req = new Request.Builder().url(base() + "/wp-admin/admin-ajax.php")
                .post(body)
                .header("x-requested-with", "XMLHttpRequest")
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept == null || accept.trim().isEmpty() ? "*/*" : accept.trim())
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Origin", base())
                .header("Referer", safeReferer)
                .build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyText = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                if (bodyText.trim().isEmpty()) { MAIN.post(() -> cb.onError("Respons AJAX Apkomik kosong")); return; }
                MAIN.post(() -> cb.onSuccess(bodyText, false));
            }
        });
    }

    private static boolean hasNextPage(Document document) {
        return document != null && !document.select("a.next.page-numbers, a.r[href*=page], a[rel=next], .pagination a:matches((?i)berikutnya|next), .hpage a.r").isEmpty();
    }

    private static int expectedPageSize(String sort, String query, String genre) {
        if (query != null && !query.trim().isEmpty()) return 10;
        if (genre != null && !genre.trim().isEmpty()) return 30;
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || "latest".equals(s) || "manga".equals(s) || "manhwa".equals(s) || "manhua".equals(s) || "project".equals(s)) return 20;
        return 30;
    }

    private static void addPage(ArrayList<String> out, LinkedHashSet<String> seen, String url) {
        if (url == null) return;
        String value = url.trim().replace("\\/", "/");
        if (value.startsWith("//")) value = "https:" + value;
        if (!value.startsWith("http")) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("readerarea.svg") || lower.contains("/iklan") || lower.contains("/ads") || lower.contains("gravatar.com") || lower.contains("logo")) return;
        if (!(lower.contains("cdnap.site") || lower.contains("/upload/gambar/") || lower.matches(".*\\.(jpg|jpeg|png|webp|avif)(\\?.*)?$"))) return;
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
        String[] attrs = {"abs:data-src", "abs:data-lazy-src", "abs:data-original", "abs:data-echo", "abs:src", "data-src", "data-lazy-src", "data-original", "data-echo", "src"};
        for (String attr : attrs) {
            String v = element.attr(attr).trim();
            if (!v.isEmpty()) return normalizeImageUrl(v);
        }
        String srcset = element.attr("srcset").trim();
        if (srcset.isEmpty()) srcset = element.attr("data-srcset").trim();
        if (!srcset.isEmpty()) {
            String first = srcset.split(",")[0].trim().split("\\s+")[0].trim();
            return normalizeImageUrl(first);
        }
        return "";
    }

    private static String normalizeImageUrl(String value) {
        if (value == null) return "";
        String v = value.trim().replace("\\/", "/");
        if (v.startsWith("//")) return "https:" + v;
        if (v.startsWith("/")) return base() + v;
        return v;
    }

    private static String imageFromHtml(String html) {
        if (html == null || html.trim().isEmpty()) return "";
        try { return image(Jsoup.parse(html, base() + "/").selectFirst("img")); }
        catch(Exception ignored) { return ""; }
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

    private static String fieldValue(Document document, String key) {
        for (Element row : document.select(".infox .fmed, .fmed")) {
            String label = text(row.selectFirst("b, .label"));
            if (label.equalsIgnoreCase(key)) return text(row.selectFirst("span, a, i, time"));
        }
        return "";
    }

    private static String infoValue(Document document, String key) {
        for (Element row : document.select(".tsinfo .imptdt, .imptdt")) {
            String rowText = row.text().trim();
            if (!rowText.toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT))) continue;
            String value = text(row.selectFirst("i, a, span"));
            if (!value.isEmpty()) return value;
            return rowText.replaceFirst("(?i)^" + Pattern.quote(key), "").trim();
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

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static float parseChapterIndex(String name, int fallback) {
        if (name == null) return fallback;
        Matcher chapterMatcher = Pattern.compile("(?i)(?:chapter|ch\\.)\\s*([0-9]+(?:[.,-][0-9]+)?)").matcher(name);
        if (chapterMatcher.find()) {
            try { return Float.parseFloat(chapterMatcher.group(1).replace(",", ".").replace("-", ".")); } catch(Exception ignored) { }
        }
        Matcher numberMatcher = Pattern.compile("([0-9]+(?:[.,-][0-9]+)?)").matcher(name);
        if (numberMatcher.find()) {
            try { return Float.parseFloat(numberMatcher.group(1).replace(",", ".").replace("-", ".")); } catch(Exception ignored) { }
        }
        return fallback;
    }

    private static String extractGenreFilter(String genre) {
        if (genre == null) return "";
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (value.isEmpty() || lower.startsWith("type:") || lower.startsWith("status:")) continue;
            return value;
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
            if (value.equals("manga")) return "manga";
            if (value.equals("manhwa")) return "manhwa";
            if (value.equals("manhua")) return "manhua";
            if (value.equals("comic")) return "comic";
            if (value.equals("novel")) return "novel";
        }
        return "";
    }

    private static String extractStatusFilter(String genre) {
        if (genre == null) return "";
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.toLowerCase(Locale.ROOT).startsWith("status:")) continue;
            value = value.substring(value.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT);
            if (value.equals("ongoing")) return "ongoing";
            if (value.equals("completed") || value.equals("complete")) return "completed";
            if (value.equals("hiatus")) return "hiatus";
        }
        return "";
    }

    private static String displayTypeLabel(String type) {
        if (type == null || type.trim().isEmpty()) return "";
        String t = type.trim().toLowerCase(Locale.ROOT);
        if ("manga".equals(t)) return "Manga";
        if ("manhwa".equals(t)) return "Manhwa";
        if ("manhua".equals(t)) return "Manhua";
        if ("comic".equals(t)) return "Comic";
        if ("novel".equals(t)) return "Novel";
        return type.trim();
    }

    private static String postId(Document document) {
        if (document == null) return "";
        for (Element element : document.select("[class]")) {
            for (String cls : element.classNames()) {
                Matcher matcher = Pattern.compile("^post-([0-9]+)$").matcher(cls);
                if (matcher.find()) return matcher.group(1);
            }
        }
        Matcher m = Pattern.compile("\\\"post_id\\\"\\s*:\\s*([0-9]+)").matcher(document.outerHtml());
        if (m.find()) return m.group(1);
        return "";
    }

    private static JsonArray jsonArray(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return new JsonArray();
        try {
            JsonElement element = object.get(key);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        } catch(Exception ignored) { return new JsonArray(); }
    }

    private static String jsonString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return "";
        try {
            JsonElement element = object.get(key);
            return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
        } catch(Exception ignored) { return ""; }
    }
}
