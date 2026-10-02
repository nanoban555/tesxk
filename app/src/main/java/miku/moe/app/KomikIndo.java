package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
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

public class KomikIndo extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKINDO); }
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

    @Override protected String sourceLabel() { return "Komikindo"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            String requestedType = requestedTypeFromFilter(genre);
            HttpUrl url = buildListUrl(page, sort, query, genre);
            String key = url.toString() + "#" + requestedType;
            ArrayList<MangaPost> cached = requestedType.isEmpty() ? LIST_CACHE.get(key) : null;
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
                            MangaCoroutines.main(() -> finishListResult(out, requestedType, key, next, cb));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Komikindo gagal dibaca")); }
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
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) {
            if (needsEnrich(p)) remaining.incrementAndGet();
        }
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (!needsEnrich(p)) continue;
            detail(p.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                    if (detail != null) {
                        String detailType = loadType ? detail.getTypeLabel() : "";
                        if (loadType && !detailType.trim().isEmpty()) p.typeLabel = detailType;
                        if (p.genre == null || p.genre.trim().isEmpty()) p.genre = detail.genre == null ? "" : detail.genre;
                    }
                    if (!loadChapter) {
                        if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                        return;
                    }
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
                @Override public void onError(String message) {
                    if (!loadChapter) {
                        if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                        return;
                    }
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
                        @Override public void onError(String error) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
                    });
                }
            });
        }
    }

    private boolean needsEnrich(MangaPost p) {
        if (p == null || p.slug == null || p.slug.trim().isEmpty()) return false;
        boolean missingChapter = MangaSettingsManager.shouldLoadLatestChapterLabel() && (p.latestChapter == null || p.latestChapter.trim().isEmpty());
        boolean missingType = MangaSettingsManager.shouldLoadTypeLabel() && (p.typeLabel == null || p.typeLabel.trim().isEmpty() || "MANGA".equalsIgnoreCase(p.typeLabel.trim()));
        return missingChapter || missingType;
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
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Komikindo gagal dibaca")); }
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
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter Komikindo gagal dibaca")); }
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
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter Komikindo tidak ditemukan"); return; }
                getDocument(toAbsolute(chapter.slug), new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Komikindo gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private HttpUrl buildListUrl(int page, String sort, String query, String genre) throws Exception {
        String path = base() + "/daftar-manga/page/" + Math.max(1, page) + "/";
        HttpUrl.Builder builder = HttpUrl.parse(path).newBuilder();
        if (query != null && !query.trim().isEmpty()) builder.addQueryParameter("title", query.trim());
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s)) builder.addQueryParameter("order", "popular");
        else if ("latest".equals(s) || "update".equals(s)) builder.addQueryParameter("order", "update");
        else builder.addQueryParameter("order", "latest");
        addFilterParameter(builder, genre);
        return builder.build();
    }

    private void addFilterParameter(HttpUrl.Builder builder, String genre) {
        if (builder == null || genre == null) return;
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String key = "genre[]";
            String id = value;
            int split = value.indexOf(':');
            if (split > 0 && split < value.length() - 1) {
                key = value.substring(0, split).trim();
                id = value.substring(split + 1).trim();
            }
            if ("type".equals(key) || "type[]".equals(key)) {
                id = normalizeTypeSlug(id);
                if (!id.isEmpty()) builder.addQueryParameter("type", id);
                continue;
            } else if (!"status".equals(key) && !"status[]".equals(key) && !"format".equals(key) && !"format[]".equals(key)) {
                id = normalizeSlug(id);
            }
            if (id.isEmpty()) continue;
            if (!key.endsWith("[]")) key = key + "[]";
            builder.addQueryParameter(key, id);
        }
    }

    private void finishListResult(ArrayList<MangaPost> out, String requestedType, String key, boolean next, Result<ArrayList<MangaPost>> cb) {
        if (requestedType == null || requestedType.trim().isEmpty()) {
            LIST_CACHE.put(key, new ArrayList<>(out));
            cb.onSuccess(out, next);
            return;
        }
        enrichLatest(out, () -> {
            ArrayList<MangaPost> filtered = new ArrayList<>();
            for (MangaPost post : out) {
                if (post != null && requestedType.equalsIgnoreCase(post.getTypeLabel())) filtered.add(post);
            }
            cb.onSuccess(filtered, next);
        });
    }

    private String requestedTypeFromFilter(String filter) {
        if (filter == null) return "";
        String[] parts = filter.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            int split = value.indexOf(':');
            String key = split > 0 ? value.substring(0, split).trim() : "";
            String id = split > 0 && split < value.length() - 1 ? value.substring(split + 1).trim() : value;
            String normalized = MangaPost.normalizeType(id, "", "");
            if (("type".equalsIgnoreCase(key) || "type[]".equalsIgnoreCase(key)) && ("MANGA".equals(normalized) || "MANHWA".equals(normalized) || "MANHUA".equals(normalized))) return normalized;
        }
        return "";
    }

    private static String normalizeTypeSlug(String value) {
        String type = MangaPost.normalizeType(value, "", "");
        if ("MANHWA".equals(type)) return "manhwa";
        if ("MANHUA".equals(type)) return "manhua";
        if ("MANGA".equals(type)) return "manga";
        return normalizeSlug(value);
    }

    private MangaPost parseListPost(Element element) {
        Element link = element.selectFirst("div.animposx > a, a");
        String slug = link == null ? "" : withoutDomain(link.attr("abs:href"));
        String title = text(element.selectFirst("div.tt h3, .tt h3, .tt h4, h3, h4, .tt"));
        String cover = image(element.selectFirst("div.limit img, img"));
        String rawType = firstNonEmpty(parseTypeFromElement(element), inferTypeFromText(element.text()));
        MangaPost post = new MangaPost(slug, title, cover, "", "", "", rawType, rawType, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKINDO, "Komikindo");
        String latest = text(element.selectFirst(".lsch a, .lsch, .epxs, .chapter"));
        if (!latest.isEmpty()) post.latestChapter = latest;
        return post;
    }

    private MangaPost parseDetail(String slug, Document document) {
        Element info = document.selectFirst("div.infoanime");
        String title = text(document.selectFirst("#breadcrumbs li:last-child span, h1, .entry-title"));
        String author = cleanInfo(text(document.selectFirst(".infox .spe span:contains(Pengarang)")), "Pengarang");
        String status = cleanInfo(text(document.selectFirst(".infox .spe span:contains(Status), .infox > .spe > span:nth-child(2)")), "Status");
        if (status.isEmpty()) status = text(document.selectFirst(".infox > .spe > span:nth-child(2)"));
        String type = firstNonEmpty(cleanInfo(text(document.selectFirst(".infox .spe span:contains(Jenis Komik)")), "Jenis Komik"), cleanInfo(text(document.selectFirst(".infox .spe span:contains(Type)")), "Type"), cleanInfo(text(document.selectFirst(".infox .spe span:contains(Tipe)")), "Tipe"), inferTypeFromText(document.select(".infox .spe, .infoanime, .seriestucont, .entry-content").text()));
        String genre = info == null ? "" : joinText(info.select(".infox .genre-info a, .infox .spe span:contains(Grafis:) a, .infox .spe span:contains(Tema:) a, .infox .spe span:contains(Konten:) a, .infox .spe span:contains(Jenis Komik:) a"));
        String synopsis = text(document.selectFirst("div.desc > .entry-content.entry-content-single p, div.desc p, .entry-content-single p"));
        String marker = "bercerita tentang ";
        int pos = synopsis.toLowerCase(Locale.ROOT).indexOf(marker);
        if (pos >= 0) synopsis = synopsis.substring(pos + marker.length()).trim();
        String cover = image(document.selectFirst(".thumb > img:nth-child(1), .thumb img"));
        return new MangaPost(slug, title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKINDO, "Komikindo");
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
        Elements images = document.select("div.img-landmine img, .entry-content img, .chapter-content img, article img");
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
                catch(Exception e) { MAIN.post(() -> cb.onError("Data Komikindo gagal dibaca")); }
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
            if (value != null && value.trim().startsWith("http")) return value.trim().split("\\?", 2)[0];
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
        if (date == null) return "";
        String value = date.trim();
        if (value.isEmpty()) return "";
        if (value.toLowerCase(Locale.ROOT).contains("yang lalu")) return value;
        return value;
    }

    private static String parseTypeFromElement(Element element) {
        if (element == null) return "";
        String direct = text(element.selectFirst(".type, .limit .type, .typeflag, .colored, .bt .type, .manga-type"));
        String inferred = inferTypeFromText(direct);
        if (!inferred.isEmpty()) return inferred;
        for (Element candidate : element.select("a[href], span[class], div[class]")) {
            String value = firstNonEmpty(candidate.attr("class"), candidate.attr("href"), candidate.text());
            inferred = inferTypeFromText(value);
            if (!inferred.isEmpty()) return inferred;
        }
        return "";
    }

    private static String normalizeSlug(String value) {
        if (value == null) return "";
        String out = value.trim().toLowerCase(Locale.ROOT);
        out = out.replace(".", "");
        out = out.replaceAll("[^a-z0-9]+", "-");
        out = out.replaceAll("^-+", "").replaceAll("-+$", "");
        return out;
    }

    private static String inferTypeFromText(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("manhwa") || lower.contains("korea") || lower.contains("korean")) return "Manhwa";
        if (lower.contains("manhua") || lower.contains("china") || lower.contains("chinese")) return "Manhua";
        if (lower.contains("doujinshi") || lower.contains("doujin")) return "Doujinshi";
        if (lower.contains("manga") || lower.contains("japan") || lower.contains("japanese")) return "Manga";
        return "";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static void fillGenres() {
        addGenre("Action", "genre:action"); addGenre("Adventure", "genre:adventure"); addGenre("Comedy", "genre:comedy"); addGenre("Crime", "genre:crime"); addGenre("Drama", "genre:drama"); addGenre("Fantasy", "genre:fantasy"); addGenre("Girls Love", "genre:girls-love"); addGenre("Harem", "genre:harem"); addGenre("Historical", "genre:historical"); addGenre("Horror", "genre:horror"); addGenre("Isekai", "genre:isekai"); addGenre("Magical Girls", "genre:magical-girls"); addGenre("Mecha", "genre:mecha"); addGenre("Medical", "genre:medical"); addGenre("Philosophical", "genre:philosophical"); addGenre("Psychological", "genre:psychological"); addGenre("Romance", "genre:romance"); addGenre("Sci-Fi", "genre:sci-fi"); addGenre("Shoujo Ai", "genre:shoujo-ai"); addGenre("Shounen Ai", "genre:shounen-ai"); addGenre("Slice of Life", "genre:slice-of-life"); addGenre("Sports", "genre:sports"); addGenre("Superhero", "genre:superhero"); addGenre("Thriller", "genre:thriller"); addGenre("Tragedy", "genre:tragedy"); addGenre("Wuxia", "genre:wuxia"); addGenre("Yuri", "genre:yuri"); addGenre("Alien", "tema:aliens"); addGenre("Animal", "tema:animals"); addGenre("Cooking", "tema:cooking"); addGenre("Crossdressing", "tema:crossdressing"); addGenre("Delinquent", "tema:delinquents"); addGenre("Demon", "tema:demons"); addGenre("Ecchi", "tema:ecchi"); addGenre("Gal", "tema:gyaru"); addGenre("Genderswap", "tema:genderswap"); addGenre("Ghost", "tema:ghosts"); addGenre("Incest", "tema:incest"); addGenre("Loli", "tema:loli"); addGenre("Mafia", "tema:mafia"); addGenre("Magic", "tema:magic"); addGenre("Martial Arts", "tema:martial-arts"); addGenre("Military", "tema:military"); addGenre("Monster Girls", "tema:monster-girls"); addGenre("Monsters", "tema:monsters"); addGenre("Music", "tema:music"); addGenre("Ninja", "tema:ninja"); addGenre("Office Workers", "tema:office-workers"); addGenre("Police", "tema:police"); addGenre("Post-Apocalyptic", "tema:post-apocalyptic"); addGenre("Reincarnation", "tema:reincarnation"); addGenre("Reverse Harem", "tema:reverse-harem"); addGenre("Samurai", "tema:samurai"); addGenre("School Life", "tema:school-life"); addGenre("Shota", "tema:shota"); addGenre("Smut", "tema:smut"); addGenre("Supernatural", "tema:supernatural"); addGenre("Survival", "tema:survival"); addGenre("Time Travel", "tema:time-travel"); addGenre("Traditional Games", "tema:traditional-games"); addGenre("Vampires", "tema:vampires"); addGenre("Video Games", "tema:video-games"); addGenre("Villainess", "tema:villainess"); addGenre("Virtual Reality", "tema:virtual-reality"); addGenre("Zombies", "tema:zombies"); addGenre("Josei", "demografis:josei"); addGenre("Seinen", "demografis:seinen"); addGenre("Shoujo", "demografis:shoujo"); addGenre("Shounen", "demografis:shounen"); addGenre("Manga", "type:Manga"); addGenre("Manhua", "type:Manhua"); addGenre("Manhwa", "type:Manhwa"); addGenre("Black & White", "format:0"); addGenre("Full Color", "format:1"); addGenre("Ongoing", "status:Ongoing"); addGenre("Completed", "status:Completed"); addGenre("Gore", "konten:gore"); addGenre("Sexual Violence", "konten:sexual-violence");
    }

    private static void addGenre(String title, String value) { GENRES.add(new GenreItem(title, value)); }
}
