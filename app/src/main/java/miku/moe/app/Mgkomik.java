package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
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
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Mgkomik extends KomikcastClient {
    private static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_MGKOMIK;
    private static final String SOURCE_LABEL = "Mgkomik";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(32, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(4, CACHE_TTL);
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    protected static String base() { return MangaSettingsManager.getSourceDomain(SOURCE_ID); }

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
            int safePage = Math.max(1, page);
            String url = buildListUrl(safePage, sort, query, genre);
            ArrayList<MangaPost> cached = LIST_CACHE.get(url);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 20); return; }
            getDocument(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(document);
                            if (out.isEmpty() && hasUnexpectedMangaMarkup(document)) {
                                MangaCoroutines.main(() -> cb.onError("Daftar Mgkomik gagal dibaca: selector tidak cocok"));
                                return;
                            }
                            boolean hasNext = hasNextPage(document, safePage, out.size());
                            if (!out.isEmpty()) LIST_CACHE.put(url, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch (Exception e) {
                            MangaCoroutines.main(() -> cb.onError("Daftar Mgkomik gagal dibaca"));
                        }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch (Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getDocument(base() + "/search/", new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(document);
                        if (out.isEmpty()) out = fallbackGenres();
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch (Exception e) {
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

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel() && !MangaSettingsManager.shouldLoadTypeLabel()) {
            if (done != null) MangaCoroutines.main(done);
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost post : list) {
            if (post != null && post.slug != null && !post.slug.trim().isEmpty()
                    && ((post.latestChapter == null || post.latestChapter.trim().isEmpty()) || (post.typeLabel == null || post.typeLabel.trim().isEmpty()))) {
                remaining.incrementAndGet();
            }
        }
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost post : list) {
            if (post == null || post.slug == null || post.slug.trim().isEmpty()) continue;
            boolean missingChapter = post.latestChapter == null || post.latestChapter.trim().isEmpty();
            boolean missingType = post.typeLabel == null || post.typeLabel.trim().isEmpty();
            if (!missingChapter && !missingType) continue;
            detail(post.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        if ((post.latestChapter == null || post.latestChapter.trim().isEmpty()) && detail.latestChapter != null) post.latestChapter = detail.latestChapter;
                        if ((post.latestChapterDate == null || post.latestChapterDate.trim().isEmpty()) && detail.latestChapterDate != null) post.latestChapterDate = detail.latestChapterDate;
                        if ((post.typeLabel == null || post.typeLabel.trim().isEmpty()) && detail.typeLabel != null) post.typeLabel = detail.typeLabel;
                        if ((post.genre == null || post.genre.trim().isEmpty()) && detail.genre != null) post.genre = detail.genre;
                        if ((post.status == null || post.status.trim().isEmpty()) && detail.status != null) post.status = detail.status;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Mgkomik kosong"); return; }
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getDocument(seriesUrl(clean), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(clean, document);
                        if (post.title == null || post.title.trim().isEmpty()) {
                            MangaCoroutines.main(() -> cb.onError("Detail Mgkomik kosong"));
                            return;
                        }
                        ArrayList<MangaChapter> chapters = parseChapters(clean, document);
                        post.totalChapters = chapters.size();
                        if (!chapters.isEmpty()) {
                            MangaChapter newest = chapters.get(0);
                            for (MangaChapter chapter : chapters) if (chapter.index > newest.index) newest = chapter;
                            post.latestChapter = newest.title == null ? "" : newest.title;
                            post.latestChapterDate = newest.date == null ? "" : newest.date;
                        }
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch (Exception e) {
                        MangaCoroutines.main(() -> cb.onError("Detail Mgkomik gagal dibaca"));
                    }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty()) { cb.onError("Slug Mgkomik kosong"); return; }
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
        String directChapterUrl = cleanChapterUrl(slug);
        String clean = cleanSeriesSlug(slug);
        if (clean.isEmpty() && directChapterUrl.isEmpty()) { cb.onError("Slug chapter Mgkomik kosong"); return; }
        String key = (directChapterUrl.isEmpty() ? clean : directChapterUrl) + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        if (!directChapterUrl.isEmpty()) { loadPages(directChapterUrl, key, cb); return; }
        MangaChapter cachedChapter = findCachedChapter(clean, index);
        if (cachedChapter != null && cachedChapter.chapterId != null && !cachedChapter.chapterId.trim().isEmpty()) {
            loadPages(cachedChapter.chapterId, key, cb);
            return;
        }
        chapters(clean, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = findChapter(chapters, index);
                String url = chapter != null && chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()
                        ? chapter.chapterId : chapterUrl(clean, index, false);
                loadPages(url, key, cb);
            }
            @Override public void onError(String message) {
                loadPages(chapterUrl(clean, index, false), key, cb);
            }
        });
    }

    private void loadPages(String chapterUrl, String key, Result<ArrayList<String>> cb) {
        String url = resolveUrl(chapterUrl);
        getDocument(url, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> out = parsePages(document, url);
                        if (out.isEmpty()) {
                            MangaCoroutines.main(() -> cb.onError("Gambar Mgkomik tidak ditemukan"));
                            return;
                        }
                        PAGE_CACHE.put(key, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch (Exception e) {
                        MangaCoroutines.main(() -> cb.onError("Reader Mgkomik gagal dibaca"));
                    }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String buildListUrl(int page, String sort, String query, String filter) {
        String q = query == null ? "" : query.trim();
        FilterParts parts = parseFilter(filter);
        String order = orderParam(sort);
        if (!q.isEmpty() || parts.useAdvancedSearch()) return advancedSearchUrl(page, q, order, parts);
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        String simpleFilter = firstNonEmpty(parts.type, parts.genre);
        if ("project".equals(s)) return comicUrl(page, "", "latest", true, false);
        if ("completed".equals(s) || "complete".equals(s)) return comicUrl(page, simpleFilter, "latest", false, true);
        if ("manga".equals(s) || "manhwa".equals(s) || "manhua".equals(s)) return comicUrl(page, s, "latest", false, false);
        return comicUrl(page, simpleFilter, order, false, false);
    }

    private String comicUrl(int page, String filter, String order, boolean project, boolean completed) {
        HttpUrl baseUrl = HttpUrl.parse(base() + "/komik/");
        HttpUrl.Builder builder = baseUrl == null ? new HttpUrl.Builder().scheme("https").host("web1.mgkomik.cc").addPathSegment("komik") : baseUrl.newBuilder();
        if (project) builder.addQueryParameter("project", "1");
        if (completed) builder.addQueryParameter("completed", "1");
        builder.addQueryParameter("filter", filter == null ? "" : filter.trim());
        builder.addQueryParameter("order_by", order == null || order.trim().isEmpty() ? "latest" : order.trim());
        builder.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        return builder.build().toString();
    }

    private String advancedSearchUrl(int page, String query, String order, FilterParts parts) {
        HttpUrl parsed = HttpUrl.parse(base() + "/search/");
        HttpUrl.Builder builder = parsed == null ? new HttpUrl.Builder().scheme("https").host("web1.mgkomik.cc").addPathSegment("search") : parsed.newBuilder();
        if (query != null && !query.trim().isEmpty()) builder.addQueryParameter("q", query.trim());
        boolean hasAdvancedFilter = false;
        if (parts != null) {
            if (!parts.status.isEmpty()) { builder.addQueryParameter("status", parts.status); hasAdvancedFilter = true; }
            if (!parts.type.isEmpty()) { builder.addQueryParameter("types[]", parts.type); hasAdvancedFilter = true; }
            for (String genre : parts.genres) if (genre != null && !genre.trim().isEmpty()) { builder.addQueryParameter("genres[]", genre.trim()); hasAdvancedFilter = true; }
        }
        String cleanOrder = order == null ? "" : order.trim();
        if (!cleanOrder.isEmpty() && !"relevance".equals(cleanOrder) && (hasAdvancedFilter || !"latest".equals(cleanOrder))) builder.addQueryParameter("order_by", cleanOrder);
        if (page > 1) builder.addQueryParameter("page", String.valueOf(page));
        return builder.build().toString();
    }

    private String orderParam(String sort) {
        String value = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(value) || "popularity".equals(value) || "trending".equals(value)) return "trending";
        if ("views".equals(value) || "view".equals(value) || "most_views".equals(value)) return "views";
        if ("new".equals(value) || "added".equals(value) || "new-manga".equals(value) || "latest_added".equals(value)) return "new-manga";
        if ("az".equals(value) || "a-z".equals(value) || "alphabet".equals(value) || "title".equals(value)) return "alphabet";
        if ("relevance".equals(value)) return "relevance";
        return "latest";
    }

    private ArrayList<MangaPost> parseList(Document document) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements cards = document.select(".manga-grid .manga-card, .manga-card");
        for (Element card : cards) {
            Element link = card.tagName().equalsIgnoreCase("a") && card.hasAttr("href") ? card : card.selectFirst("a.manga-title[href], .card-cover a[href], a[href*=/komik/]");
            if (link == null) continue;
            String href = link.absUrl("href");
            if (href.isEmpty()) href = resolveUrl(link.attr("href"));
            if (href.toLowerCase(Locale.ROOT).contains("/chapter-")) continue;
            String slug = cleanSeriesSlug(href);
            if (slug.isEmpty() || !seen.add(slug)) continue;
            String title = firstNonEmpty(text(card, ".manga-title"), link.attr("title"), link.text(), imageAlt(card.selectFirst("img.manga-cover, .card-cover img")));
            if (title.isEmpty()) continue;
            String cover = imageUrl(card.selectFirst("img.manga-cover, .card-cover img"));
            String status = normalizeStatus(text(card, ".manga-status-badge"));
            String type = normalizeType(firstNonEmpty(text(card, ".flag-badge"), attr(card.selectFirst(".flag-badge"), "title")));
            String latest = cleanChapterText(text(card, ".chapter-capsule"));
            String date = text(card, ".chapter-date");
            MangaPost post = new MangaPost(slug, title, cover, "", status, "", "", type, latest, date).withSource(SOURCE_ID, SOURCE_LABEL);
            out.add(post);
        }
        return out;
    }

    private MangaPost parseDetail(String slug, Document document) {
        String title = cleanTitle(firstNonEmpty(text(document, "h1.manga-title"), text(document, "h1"), document.title() == null ? "" : document.title()));
        String cover = imageUrl(document.selectFirst("img.manga-cover-large, .manga-cover-wrapper img, img.manga-cover"));
        String synopsis = text(document, "#sinopsisContainer");
        if (synopsis.isEmpty()) synopsis = text(document, ".manga-description p");
        if (synopsis.isEmpty()) synopsis = text(document, ".manga-description").replaceFirst("(?i)^sinopsis\\s*", "").trim();
        String genre = joinTexts(document.select(".genre-list a.genre-tag, a.genre-tag"));
        String type = "";
        String status = "";
        Elements metas = document.select(".manga-meta .meta-item");
        if (!metas.isEmpty()) type = normalizeType(metas.get(0).text());
        for (Element meta : metas) {
            String value = meta.text().trim();
            if (isStatus(value)) status = normalizeStatus(value);
            else if (type.isEmpty()) type = normalizeType(value);
        }
        if (type.isEmpty()) type = normalizeType(genre);
        String alt = firstNonEmpty(text(document, "#altModalText"), text(document, ".manga-title-alt"));
        MangaPost post = new MangaPost(slug, title, cover, "", status, synopsis, genre, type).withSource(SOURCE_ID, SOURCE_LABEL);
        post.info = alt.isEmpty() ? "" : "Alternative Title: " + alt;
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(String seriesSlug, Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element item : document.select("#chapterList .chapter-list-item, .chapter-list .chapter-list-item, li.chapter-list-item")) {
            Element link = item.selectFirst("a.chapter-link[href], a[href*=/chapter-]");
            if (link == null) continue;
            String href = link.absUrl("href");
            if (href.isEmpty()) href = resolveUrl(link.attr("href"));
            String title = firstNonEmpty(text(item, ".chapter-number"), item.attr("data-chapter-title"), link.text());
            float index = parseChapterIndex(firstNonEmpty(item.attr("data-chapter-number"), title, href), -1f);
            if (index < 0) continue;
            String key = MangaChapter.formatIndex(index);
            if (!seen.add(key)) continue;
            MangaChapter chapter = new MangaChapter(seriesSlug, index, title, text(item, ".chapter-date"));
            chapter.chapterId = href;
            out.add(chapter);
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document, String chapterUrl) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("#readingContent img, .reading-content img, .reader-content img, .chapter-content img");
        if (images.isEmpty()) images = document.select("img[src*=static2.mgis.my.id], img[data-src*=static2.mgis.my.id], img[src*=mgis.my.id]");
        for (Element img : images) {
            String url = imageUrl(img);
            if (!isReaderImage(url)) continue;
            if (seen.add(url)) {
                MangaImageLoader.registerImageReferer(url, base() + "/");
                if (chapterUrl != null && !chapterUrl.trim().isEmpty()) MangaImageLoader.registerImageReferer(url, base() + "/");
                out.add(url);
            }
        }
        return out;
    }

    private ArrayList<GenreItem> parseGenres(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element input : document.select("input.genre-checkbox[value]")) {
            String value = input.attr("value").trim();
            if (value.isEmpty() || !seen.add(value)) continue;
            String title = "";
            String id = input.id();
            if (!id.isEmpty()) title = text(document, "label[for=" + id + "]");
            if (title.isEmpty()) title = titleFromSlug(value);
            out.add(new GenreItem(title, value));
        }
        appendTypeFilters(out, seen);
        appendStatusFilters(out, seen);
        return out;
    }

    private boolean hasNextPage(Document document, int currentPage, int size) {
        for (Element link : document.select(".page-link[href], .pagination a[href], a[href*='page=']")) {
            String text = link.text().trim().toLowerCase(Locale.ROOT);
            String href = link.attr("href");
            if (text.contains("next")) return true;
            int p = queryInt(href, "page", -1);
            if (p > currentPage) return true;
        }
        return size >= 20;
    }

    private boolean hasUnexpectedMangaMarkup(Document document) {
        return document.selectFirst(".manga-grid, .manga-card, .card-cover, .manga-title") != null;
    }

    private void getDocument(String url, Result<Document> cb) {
        getDocument(new Request.Builder().url(url).headers(headers(url)).build(), cb);
    }

    private void getDocument(Request request, Result<Document> cb) {
        CloudflareHelper.enqueue(client, request, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) { main.post(() -> cb.onError("HTTP " + response.code())); return; }
                if (looksLikeCloudflare(body)) { main.post(() -> cb.onError("Lewati Cloudflare dulu")); return; }
                try {
                    Document document = Jsoup.parse(body, request.url().toString());
                    main.post(() -> cb.onSuccess(document, false));
                } catch (Exception e) { main.post(() -> cb.onError("Data Mgkomik gagal dibaca")); }
            }
        });
    }

    private Headers headers(String url) {
        String referer = base() + "/";
        return new Headers.Builder()
                .set("Referer", referer)
                .set("Origin", base())
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .set("Cache-Control", "max-age=0")
                .build();
    }

    private FilterParts parseFilter(String raw) {
        FilterParts parts = new FilterParts();
        if (raw == null) return parts;
        for (String chunk : raw.split("[|,]")) {
            String token = chunk == null ? "" : chunk.trim();
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) {
                parts.type = normalizeFilterValue(token.substring(5));
            } else if (lower.startsWith("status:")) {
                parts.status = normalizeStatusParam(token.substring(7));
            } else if (lower.startsWith("genre:")) {
                addGenre(parts, token.substring(6));
            } else if (isKnownStatusParam(lower)) {
                parts.status = normalizeStatusParam(token);
            } else if (isKnownTypeParam(lower) && parts.type.isEmpty()) {
                parts.type = normalizeFilterValue(token);
            } else {
                addGenre(parts, token);
            }
        }
        return parts;
    }

    private void addGenre(FilterParts parts, String value) {
        String clean = normalizeFilterValue(value);
        if (clean.isEmpty()) return;
        parts.genre = parts.genre.isEmpty() ? clean : parts.genre;
        parts.genres.add(clean);
    }

    private String seriesUrl(String slug) {
        return base() + "/komik/" + pathSegment(slug) + "/";
    }

    private String chapterUrl(String slug, float index, boolean padded) {
        String number = MangaChapter.formatIndex(index);
        if (padded && Math.abs(index - Math.round(index)) < 0.001f && index >= 0 && index < 10) number = String.format(Locale.ROOT, "%02d", Math.round(index));
        return base() + "/komik/" + pathSegment(slug) + "/chapter-" + number + "/";
    }

    private MangaChapter findCachedChapter(String clean, float index) {
        return findChapter(CHAPTER_CACHE.get(clean), index);
    }

    private MangaChapter findChapter(ArrayList<MangaChapter> chapters, float index) {
        if (chapters == null) return null;
        for (MangaChapter chapter : chapters) if (chapter != null && Math.abs(chapter.index - index) < 0.001f) return chapter;
        return null;
    }

    private String cleanSeriesSlug(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) value = parsed.encodedPath();
        }
        int q = value.indexOf('?');
        if (q >= 0) value = value.substring(0, q);
        value = value.replace("\\", "/");
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        String[] parts = value.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("komik".equalsIgnoreCase(parts[i]) && i + 1 < parts.length) return decodePath(parts[i + 1]);
        }
        if (parts.length > 0) return decodePath(parts[0]);
        return decodePath(value);
    }

    private String cleanChapterUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.contains("/chapter-") && !lower.matches(".*chapter-[0-9].*")) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (!value.startsWith("/")) {
            if (value.startsWith("komik/")) return resolveUrl("/" + value);
            String series = cleanSeriesSlug(value);
            float number = parseChapterIndex(value, -1f);
            if (!series.isEmpty() && number >= 0) return chapterUrl(series, number, false);
        }
        return resolveUrl(value);
    }

    private String resolveUrl(String value) {
        if (value == null) return "";
        String safe = value.trim();
        if (safe.isEmpty()) return "";
        if (safe.startsWith("//")) return "https:" + safe;
        if (safe.startsWith("http://") || safe.startsWith("https://")) return safe;
        if (!safe.startsWith("/")) safe = "/" + safe;
        return base() + safe;
    }

    private String imageUrl(Element img) {
        if (img == null) return "";
        String[] attrs = {"src", "data-src", "data-lazy-src", "data-original", "data-full", "data-image"};
        for (String attr : attrs) {
            String value = img.hasAttr(attr) ? img.attr(attr).trim() : "";
            if (!value.isEmpty() && !value.startsWith("data:")) return resolveImageUrl(value);
        }
        String srcset = img.attr("srcset");
        if (!srcset.trim().isEmpty()) {
            String first = srcset.split(",")[0].trim().split("\\s+")[0].trim();
            if (!first.isEmpty() && !first.startsWith("data:")) return resolveImageUrl(first);
        }
        return "";
    }

    private String resolveImageUrl(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) return "";
        if (safe.startsWith("//")) return "https:" + safe;
        if (safe.startsWith("http://") || safe.startsWith("https://")) return safe;
        return resolveUrl(safe);
    }

    private boolean isReaderImage(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http")) return false;
        if (lower.contains("banner/") || lower.contains("avatar") || lower.contains("flagcdn.com") || lower.contains("gravatar") || lower.contains("0.gif")) return false;
        return lower.contains("static2.mgis.my.id") || lower.contains("static.mgis.my.id") || lower.contains("/manga/") || lower.contains("/data/manga_");
    }

    private String normalizeType(String raw) {
        String value = raw == null ? "" : raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("manhwa") || lower.contains("korea")) return "MANHWA";
        if (lower.contains("manhua") || lower.contains("china")) return "MANHUA";
        if (lower.contains("manga") || lower.contains("jepang") || lower.contains("japan")) return "MANGA";
        return MangaPost.normalizeType(value, value, "");
    }

    private String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("ongoing") || lower.contains("on-going")) return "Ongoing";
        if (lower.equals("end") || lower.contains("completed") || lower.contains("complete") || lower.contains("tamat")) return "Completed";
        if (lower.contains("hold") || lower.contains("hiatus")) return "Hiatus";
        return value;
    }

    private boolean isStatus(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return lower.contains("ongoing") || lower.contains("on-going") || lower.contains("completed") || lower.equals("end") || lower.contains("hiatus") || lower.contains("hold");
    }

    private String cleanChapterText(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile("(?i)chapter\\s*[0-9]+(?:[.,][0-9]+)?(?:\\s*-\\s*end)?").matcher(value);
        if (matcher.find()) return matcher.group().trim();
        return value;
    }

    private float parseChapterIndex(String raw, float fallback) {
        if (raw == null) return fallback;
        Matcher matcher = Pattern.compile("(?i)chapter[-\\s_]*([0-9]+(?:[.,][0-9]+)?)").matcher(raw);
        if (matcher.find()) return parseFloat(matcher.group(1), fallback);
        matcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)").matcher(raw);
        if (matcher.find()) return parseFloat(matcher.group(1), fallback);
        return fallback;
    }

    private float parseFloat(String raw, float fallback) {
        try { return Float.parseFloat((raw == null ? "" : raw).replace(',', '.')); }
        catch (Exception e) { return fallback; }
    }

    private int queryInt(String href, String name, int fallback) {
        try {
            HttpUrl parsed = HttpUrl.parse(resolveUrl(href));
            if (parsed == null) return fallback;
            String value = parsed.queryParameter(name);
            if (value == null) return fallback;
            return Integer.parseInt(value);
        } catch (Exception e) { return fallback; }
    }

    private String text(Element root, String selector) {
        if (root == null || selector == null || selector.trim().isEmpty()) return "";
        Element element = root.selectFirst(selector);
        return text(element);
    }

    private String text(Element element) {
        return element == null ? "" : element.text().trim().replaceAll("\\s+", " ");
    }

    private String attr(Element element, String name) {
        return element == null || name == null ? "" : element.attr(name).trim();
    }

    private String imageAlt(Element img) {
        return img == null ? "" : img.attr("alt").trim();
    }

    private String joinTexts(Elements elements) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (elements != null) for (Element element : elements) {
            String value = text(element);
            if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) values.add(value);
        }
        return join(values, ", ");
    }

    private String join(ArrayList<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private String cleanTitle(String value) {
        String title = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        title = title.replaceFirst("(?i)\\s*-\\s*MGKOMIK$", "").trim();
        return title;
    }

    private String normalizeFilterValue(String value) {
        String safe = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        safe = safe.replace('_', '-').replaceAll("\\s+", "-");
        safe = safe.replaceAll("[^a-z0-9\\-]", "");
        while (safe.contains("--")) safe = safe.replace("--", "-");
        return safe.replaceAll("^-+", "").replaceAll("-+$", "");
    }

    private String normalizeStatusParam(String value) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("ongoing") || lower.equals("on-going")) return "on-going";
        if (lower.equals("completed") || lower.equals("complete") || lower.equals("end") || lower.equals("tamat")) return "end";
        if (lower.equals("hiatus") || lower.equals("on-hold") || lower.equals("hold")) return "on-hold";
        return normalizeFilterValue(value);
    }

    private boolean isKnownTypeParam(String value) {
        return "manga".equals(value) || "manhwa".equals(value) || "manhua".equals(value);
    }

    private boolean isKnownStatusParam(String value) {
        return "ongoing".equals(value) || "on-going".equals(value) || "completed".equals(value) || "complete".equals(value) || "end".equals(value) || "hiatus".equals(value) || "on-hold".equals(value);
    }

    private void appendTypeFilters(ArrayList<GenreItem> out, LinkedHashSet<String> seen) {
        addSynthetic(out, seen, "type:manga", "Manga");
        addSynthetic(out, seen, "type:manhwa", "Manhwa");
        addSynthetic(out, seen, "type:manhua", "Manhua");
    }

    private void appendStatusFilters(ArrayList<GenreItem> out, LinkedHashSet<String> seen) {
        addSynthetic(out, seen, "status:on-going", "Status: Ongoing");
        addSynthetic(out, seen, "status:end", "Status: Completed");
        addSynthetic(out, seen, "status:on-hold", "Status: Hiatus");
    }

    private void addSynthetic(ArrayList<GenreItem> out, LinkedHashSet<String> seen, String value, String title) {
        String key = value.toLowerCase(Locale.ROOT);
        if (seen.add(key)) out.add(new GenreItem(title, value));
    }

    private ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String[][] values = {
                {"Action", "action"}, {"Adventure", "adventure"}, {"Comedy", "comedy"}, {"Drama", "drama"},
                {"Fantasy", "fantasy"}, {"Historical", "historical"}, {"Horror", "horror"}, {"Isekai", "isekai"},
                {"Magic", "magic"}, {"Martial Arts", "martial-arts"}, {"Murim", "murim"}, {"Mystery", "mystery"},
                {"Regression", "regression"}, {"Reincarnation", "reincarnation"}, {"Romance", "romance"},
                {"School Life", "school-life"}, {"Sci-Fi", "sci-fi"}, {"Seinen", "seinen"}, {"Shoujo", "shoujo"},
                {"Shounen", "shounen"}, {"Slice of Life", "slice-of-life"}, {"Supernatural", "supernatural"},
                {"Survival", "survival"}, {"System", "system"}, {"Thriller", "thriller"}, {"Webtoon", "webtoon"}
        };
        for (String[] item : values) addSynthetic(out, seen, item[1], item[0]);
        appendTypeFilters(out, seen);
        appendStatusFilters(out, seen);
        return out;
    }

    private String titleFromSlug(String value) {
        String clean = value == null ? "" : value.trim().replace('-', ' ');
        StringBuilder builder = new StringBuilder();
        for (String part : clean.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) builder.append(part.substring(1));
        }
        return builder.toString();
    }

    private String pathSegment(String slug) {
        String clean = slug == null ? "" : slug.trim();
        clean = clean.replaceAll("^/+", "").replaceAll("/+$", "");
        return clean.replace("/", "");
    }

    private String decodePath(String value) {
        try { return java.net.URLDecoder.decode(value == null ? "" : value.trim(), "UTF-8"); }
        catch (Exception e) { return value == null ? "" : value.trim(); }
    }

    private boolean looksLikeCloudflare(String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("cf-browser-verification") || lower.contains("checking your browser") || lower.contains("cloudflare") && lower.contains("challenge");
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static final class FilterParts {
        String genre = "";
        String type = "";
        String status = "";
        final ArrayList<String> genres = new ArrayList<>();

        boolean useAdvancedSearch() {
            return !status.isEmpty() || !type.isEmpty() && !genre.isEmpty() || genres.size() > 1;
        }
    }
}
