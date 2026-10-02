package miku.moe.app;

import com.google.gson.*;
import java.io.IOException;
import java.util.regex.Matcher;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public abstract class NatsuIdClient extends KomikcastClient {
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build();
    private final MangaMemoryCache<String, MangaPost> detailCache = new MangaMemoryCache<>(64, CACHE_TTL);
    private final MangaMemoryCache<String, ArrayList<MangaChapter>> chapterCache = new MangaMemoryCache<>(64, CACHE_TTL);
    private final MangaMemoryCache<String, ArrayList<String>> pageCache = new MangaMemoryCache<>(32, CACHE_TTL);
    private final MangaMemoryCache<String, ArrayList<MangaPost>> listCache = new MangaMemoryCache<>(64, CACHE_TTL);
    private final MangaMemoryCache<String, Boolean> listHasNextCache = new MangaMemoryCache<>(64, CACHE_TTL);
    private final MangaMemoryCache<String, ArrayList<GenreItem>> genreCache = new MangaMemoryCache<>(2, 24L * 60L * 60L * 1000L);
    private volatile String nonce;

    protected abstract String sourceId();
    protected abstract String sourceName();
    protected abstract int rateLimitMillis();
    protected boolean transformJson(String body) { return false; }
    protected boolean forceChapterPageOne() { return false; }
    protected boolean chaptersFromPageHtml() { return false; }
    protected String readerImageSelector() { return "main .relative section > img"; }

    protected String natsuBase() { return MangaSettingsManager.getSourceDomain(sourceId()); }

    @Override protected String sourceLabel() { return sourceName(); }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) {
        list(page, sort, query, "", cb);
    }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        final int safePage = Math.max(1, page);
        final String safeQuery = query == null ? "" : query.trim();
        final FilterSpec filter = parseFilter(genre);
        final String safeSort = normalizeSort(sort);
        final String key = safePage + "|" + safeSort + "|" + safeQuery + "|" + filter.key();
        ArrayList<MangaPost> cached = listCache.get(key);
        if (cached != null) {
            Boolean cachedHasNext = listHasNextCache.get(key);
            cb.onSuccess(new ArrayList<>(cached), cachedHasNext != null && cachedHasNext);
            return;
        }
        MangaCoroutines.io(() -> {
            try {
                throttle();
                String body = postAdvancedSearch(safePage, safeQuery, filter, safeSort);
                ArrayList<MangaPost> out = parseSearch(body);
                boolean hasNext = hasNextPage(body);
                listCache.put(key, new ArrayList<>(out));
                listHasNextCache.put(key, hasNext);
                MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
            } catch (Exception e) {
                MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e)));
            }
        });
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = genreCache.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                throttle();
                Request req = request(natsuBase() + "/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc", false);
                String body = execute(req);
                JsonArray arr = parseArray(body);
                ArrayList<GenreItem> out = new ArrayList<>();
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String name = string(o, "name");
                    String slug = string(o, "slug");
                    if (!slug.isEmpty() && !name.isEmpty() && seen.add(slug)) out.add(new GenreItem(name, slug));
                }
                genreCache.put("genres", new ArrayList<>(out));
                MangaCoroutines.main(() -> cb.onSuccess(out, false));
            } catch (Exception e) {
                MangaCoroutines.main(() -> cb.onError("Genre " + sourceName() + " gagal dibaca"));
            }
        });
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        final String clean = cleanMangaSlug(slug);
        MangaPost cached = detailCache.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        MangaCoroutines.io(() -> {
            try {
                throttle();
                HttpUrl url = HttpUrl.parse(natsuBase() + "/wp-json/wp/v2/manga")
                        .newBuilder().addQueryParameter("slug[]", clean).addQueryParameter("_embed", null).build();
                String body = execute(request(url.toString(), true));
                JsonArray arr = parseArray(transformJsonBody(body));
                if (arr.size() == 0) throw new IOException("Manga not found");
                JsonObject detailJson = arr.get(0).getAsJsonObject();
                if (isNovel(detailJson)) throw new IOException("Novel is not supported");
                MangaPost post = parseManga(detailJson);
                detailCache.put(clean, post);
                MangaCoroutines.main(() -> cb.onSuccess(post, false));
            } catch (Exception e) {
                MangaCoroutines.main(() -> cb.onError("Detail " + sourceName() + " gagal dibaca"));
            }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        final String clean = cleanMangaSlug(slug);
        ArrayList<MangaChapter> cached = chapterCache.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                if (chaptersFromPageHtml()) {
                    ArrayList<MangaChapter> out = parseChaptersFromMangaPage(clean);
                    if (!out.isEmpty()) {
                        chapterCache.put(clean, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                        return;
                    }
                }
                throttle();
                HttpUrl mangaUrl = HttpUrl.parse(natsuBase() + "/wp-json/wp/v2/manga")
                        .newBuilder().addQueryParameter("slug[]", clean).addQueryParameter("_embed", null).build();
                String body = execute(request(mangaUrl.toString(), true));
                JsonArray arr = parseArray(transformJsonBody(body));
                String mangaId = "";
                if (arr.size() > 0) mangaId = string(arr.get(0).getAsJsonObject(), "id");
                if (mangaId.isEmpty()) {
                    Document d = Jsoup.parse(body, natsuBase());
                    Element gallery = d.selectFirst("#gallery-list");
                    if (gallery != null) mangaId = queryParam(gallery.attr("hx-get"), "manga_id");
                }
                if (mangaId.isEmpty()) throw new IOException("Manga id not found");
                String url = natsuBase() + "/wp-admin/admin-ajax.php";
                HttpUrl.Builder ub = HttpUrl.parse(url).newBuilder()
                        .addQueryParameter("manga_id", mangaId)
                        .addQueryParameter("action", "chapter_list");
                ub.addQueryParameter("page", forceChapterPageOne() ? "1" : String.valueOf(99 + new Random().nextInt(9900)));
                throttle();
                String chapterBody = execute(request(ub.build().toString(), false));
                ArrayList<MangaChapter> out = parseChapters(chapterBody);
                if (out.isEmpty()) {
                    ArrayList<MangaChapter> fallback = parseChaptersFromMangaPage(clean);
                    if (!fallback.isEmpty()) out = fallback;
                }
                final ArrayList<MangaChapter> result = out;
                chapterCache.put(clean, new ArrayList<>(result));
                MangaCoroutines.main(() -> cb.onSuccess(result, false));
            } catch (Exception e) {
                MangaCoroutines.main(() -> cb.onError("Chapter " + sourceName() + " gagal dibaca"));
            }
        });
    }

    private ArrayList<MangaChapter> parseChaptersFromMangaPage(String clean) throws Exception {
        ArrayList<MangaChapter> out = new ArrayList<>();
        try {
            String mangaPageUrl = natsuBase() + "/manga/" + clean + "/";
            Document d = Jsoup.parse(execute(request(mangaPageUrl, false)), mangaPageUrl);
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (Element row : d.select("#chapter-list div[data-chapter-number]")) {
                Element a = row.selectFirst("a[href]");
                if (a == null) continue;
                String href = a.absUrl("href");
                if (href.isEmpty() || !seen.add(href)) continue;
                String name = a.select("span").first() != null ? a.select("span").first().text().trim() : a.text().trim();
                if (name.isEmpty()) name = a.text().trim();
                float index = parseChapterIndex(name, out.size() + 1);
                String date = a.select("time").attr("datetime");
                if (date.isEmpty()) date = row.select("time").attr("datetime");
                MangaChapter chapter = new MangaChapter(href, index, name, date);
                chapter.chapterId = href;
                out.add(chapter);
            }
        } catch (Exception ignored) {}
        return out;
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        final String clean = cleanMangaSlug(slug);
        final String key = clean + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = pageCache.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(clean, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean ignored) {
                MangaChapter target = null;
                if (chapters != null) for (MangaChapter c : chapters) if (Math.abs(c.index - index) < 0.0001f) { target = c; break; }
                if (target == null && chapters != null && !chapters.isEmpty()) target = chapters.get(0);
                if (target == null || target.slug == null || target.slug.trim().isEmpty()) { cb.onError("Chapter " + sourceName() + " tidak ditemukan"); return; }
                String chapterUrl = target.slug.startsWith("http") ? target.slug : natsuBase() + target.slug;
                if (!chapterUrl.endsWith("/")) chapterUrl += "/";
                final String finalChapterUrl = chapterUrl;
                MangaCoroutines.io(() -> {
                    try {
                        throttle();
                        Document d = Jsoup.parse(execute(request(finalChapterUrl, true)), finalChapterUrl);
                        ArrayList<String> pages = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        for (Element img : d.select(readerImageSelector())) {
                            String src = img.absUrl("src");
                            if (src.isEmpty()) src = img.absUrl("data-src");
                            if (src.isEmpty()) src = img.absUrl("data-lazy-src");
                            if (!src.isEmpty() && seen.add(src)) pages.add(src);
                        }
                        pageCache.put(key, new ArrayList<>(pages));
                        MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                    } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman " + sourceName() + " gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String postAdvancedSearch(int page, String query, FilterSpec f, String sort) throws Exception {
        MultipartBody.Builder form = new MultipartBody.Builder().setType(MultipartBody.FORM);
        String n = getNonce();
        form.addFormDataPart("nonce", n == null ? "" : n);
        form.addFormDataPart("inclusion", f.inclusion);
        form.addFormDataPart("exclusion", f.exclusion);
        form.addFormDataPart("page", String.valueOf(page));
        form.addFormDataPart("genre", new Gson().toJson(f.genres));
        form.addFormDataPart("genre_exclude", new Gson().toJson(f.genresExcluded));
        form.addFormDataPart("author", "[]");
        form.addFormDataPart("artist", "[]");
        form.addFormDataPart("project", f.project ? "1" : "0");
        form.addFormDataPart("type", new Gson().toJson(f.types));
        form.addFormDataPart("status", new Gson().toJson(f.statuses));
        form.addFormDataPart("order", "desc");
        form.addFormDataPart("orderby", sort);
        form.addFormDataPart("query", query);
        throttle();
        return execute(new Request.Builder().url(natsuBase() + "/wp-admin/admin-ajax.php?action=advanced_search")
                .post(form.build()).header("Referer", natsuBase() + "/").header("Origin", natsuBase()).build());
    }

    private String getNonce() throws Exception {
        if (nonce != null && !nonce.isEmpty()) return nonce;
        synchronized (this) {
            if (nonce != null && !nonce.isEmpty()) return nonce;
            throttle();
            String body = execute(request(natsuBase() + "/wp-admin/admin-ajax.php?type=search_form&action=get_nonce", false));
            Element input = Jsoup.parseBodyFragment(body).selectFirst("input[name=search_nonce]");
            if (input == null || input.attr("value").trim().isEmpty()) throw new IOException("Unable to get search nonce");
            nonce = input.attr("value").trim();
            return nonce;
        }
    }

    private ArrayList<MangaPost> parseSearch(String body) {
        Document d = Jsoup.parseBodyFragment(body, natsuBase());
        ArrayList<String> slugs = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element a : d.select("div > a[href*=/manga/]:has(> img)")) {
            String abs = a.absUrl("href");
            String slug = cleanMangaSlug(abs);
            if (!slug.isEmpty() && seen.add(slug)) slugs.add(slug);
        }
        if (slugs.isEmpty()) return new ArrayList<>();
        try {
            HttpUrl.Builder ub = HttpUrl.parse(natsuBase() + "/wp-json/wp/v2/manga").newBuilder();
            for (String slug : slugs) ub.addQueryParameter("slug[]", slug);
            ub.addQueryParameter("per_page", String.valueOf(slugs.size() + 1)).addQueryParameter("_embed", "");
            String json = execute(request(ub.build().toString(), true));
            JsonArray arr = parseArray(transformJsonBody(json));
            HashMap<String, MangaPost> bySlug = new HashMap<>();
            for (JsonElement e : arr) if (e.isJsonObject()) { MangaPost p = parseManga(e.getAsJsonObject()); if (!isNovel(e.getAsJsonObject())) bySlug.put(p.slug, p); }
            ArrayList<MangaPost> out = new ArrayList<>();
            for (String s : slugs) { MangaPost p = bySlug.get(s); if (p != null) out.add(p); }
            return out;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private MangaPost parseManga(JsonObject o) {
        String slug = cleanMangaSlug(string(o, "slug"));
        String title = htmlText(rendered(o, "title"));
        String synopsis = htmlText(rendered(o, "content"));
        JsonObject emb = obj(o, "_embedded");
        String cover = "";
        JsonArray media = emb == null ? null : arr(emb, "wp:featuredmedia");
        if (media != null && media.size() > 0 && media.get(0).isJsonObject()) cover = string(media.get(0).getAsJsonObject(), "source_url");
        String author = joinTerms(emb, "series-author");
        String artist = joinTerms(emb, "artist");
        String genre = joinTerms(emb, "genre");
        String type = joinTerms(emb, "type");
        String statusRaw = joinTerms(emb, "status");
        String status = normalizeStatus(statusRaw);
        String allGenre = joinNonEmpty(genre, type, artist);
        String latest = "";
        MangaPost p = new MangaPost(slug, title, cover, author, status, synopsis, allGenre, type, latest, "")
                .withSource(sourceId(), sourceName());
        return p;
    }

    private ArrayList<MangaChapter> parseChapters(String body) {
        Document d = Jsoup.parseBodyFragment(body, natsuBase());
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element a : d.select("div a:has(time)")) {
            String href = a.absUrl("href");
            if (href.isEmpty()) continue;
            String name = a.select("span").text().trim();
            if (name.isEmpty()) name = a.text().trim();
            float index = parseChapterIndex(name, out.size() + 1);
            String date = a.select("time").attr("datetime");
            if (seen.add(href)) out.add(new MangaChapter(href, index, name, date));
        }
        return out;
    }

    private boolean hasNextPage(String body) { return Jsoup.parseBodyFragment(body).selectFirst("button:has(svg)") != null; }

    private String normalizeSort(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (s.equals("popular") || s.equals("popularity") || s.equals("rating") || s.equals("updated") || s.equals("bookmarked") || s.equals("title")) return s;
        return "updated";
    }

    private String transformJsonBody(String body) {
        if (body == null) return "";
        if (!transformJson(body)) return body;
        int start = -1;
        for (int i = 0; i < body.length(); i++) { char c = body.charAt(i); if (c == '{' || c == '[') { start = i; break; } }
        return start >= 0 ? body.substring(start) : body;
    }

    private Request request(String url, boolean json) {
        return new Request.Builder().url(url)
                .header("Referer", natsuBase() + "/").header("Origin", natsuBase())
                .header("Accept", json ? "application/json,text/plain,*/*" : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36")
                .build();
    }

    private String execute(Request req) throws Exception {
        try (Response r = CLIENT.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
            ResponseBody b = r.body();
            return b == null ? "" : b.string();
        }
    }

    private void throttle() { if (rateLimitMillis() > 0) try { Thread.sleep(rateLimitMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private static JsonArray parseArray(String body) {
        JsonElement e = JsonParser.parseString(body == null ? "[]" : body);
        if (e.isJsonArray()) return e.getAsJsonArray();
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("data") != null && o.get("data").isJsonArray()) return o.getAsJsonArray("data");
        }
        return new JsonArray();
    }
    private static JsonObject obj(JsonObject o, String key) { JsonElement e=o==null?null:o.get(key); return e!=null&&e.isJsonObject()?e.getAsJsonObject():null; }
    private static JsonArray arr(JsonObject o, String key) { JsonElement e=o==null?null:o.get(key); return e!=null&&e.isJsonArray()?e.getAsJsonArray():null; }
    private static String string(JsonObject o, String key) { try { JsonElement e=o==null?null:o.get(key); return e==null||e.isJsonNull()?"":e.getAsString().trim(); } catch(Exception e){return "";} }
    private static String rendered(JsonObject o, String key) { return string(obj(o, key), "rendered"); }
    private static String htmlText(String s) { return Jsoup.parseBodyFragment(s==null?"":s).text().trim(); }
    private static String joinTerms(JsonObject emb, String taxonomy) {
        JsonArray terms=arr(emb,"wp:term"); if(terms==null)return "";
        for(JsonElement group:terms) if(group.isJsonArray()) {
            JsonArray g=group.getAsJsonArray(); if(g.size()==0)continue;
            JsonObject first=g.get(0).isJsonObject()?g.get(0).getAsJsonObject():null;
            if(taxonomy.equals(string(first,"taxonomy"))) { ArrayList<String> a=new ArrayList<>(); for(JsonElement e:g) if(e.isJsonObject()){String n=string(e.getAsJsonObject(),"name");if(!n.isEmpty())a.add(n);} return String.join(", ",a); }
        }
        return "";
    }
    private static String joinNonEmpty(String... a){ArrayList<String> out=new ArrayList<>();for(String s:a)if(s!=null&&!s.trim().isEmpty())out.add(s.trim());return String.join(", ",out);}
    private static boolean isNovel(JsonObject o) {
        JsonObject emb = obj(o, "_embedded");
        String type = joinTerms(emb, "type");
        return type.toLowerCase(Locale.ROOT).contains("novel");
    }

    private static String normalizeStatus(String s){String x=s.toLowerCase(Locale.ROOT);if(x.contains("ongoing"))return "Ongoing";if(x.contains("completed"))return "Completed";if(x.contains("cancelled"))return "Cancelled";if(x.contains("hiatus"))return "On Hiatus";return s;}
    private static String cleanMangaSlug(String s){
        if(s==null)return "";String x=s.trim();try{if(x.startsWith("http")){HttpUrl u=HttpUrl.parse(x);if(u!=null){List<String> p=u.pathSegments();int i=p.indexOf("manga");if(i>=0&&i+1<p.size())return p.get(i+1);if(p.size()>1)return p.get(1);}}}catch(Exception ignored){}
        x=x.replaceAll("^/+|/+$",""); if(x.contains("/")){String[] p=x.split("/");for(int i=0;i<p.length;i++)if("manga".equalsIgnoreCase(p[i])&&i+1<p.length)return p[i+1];x=p[p.length-1];}return urlDecode(x);
    }
    private static String urlDecode(String s){try{return URLDecoder.decode(s, StandardCharsets.UTF_8.name());}catch(Exception e){return s;}}
    private static String queryParam(String url,String key){try{HttpUrl u=HttpUrl.parse(url);return u==null?"":(u.queryParameter(key)==null?"":u.queryParameter(key));}catch(Exception e){return "";}}
    private static float parseChapterIndex(String title,int fallback){
        if(title!=null){Matcher m=java.util.regex.Pattern.compile("(?i)(?:chapter|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(title);if(m.find())try{return Float.parseFloat(m.group(1).replace(',','.'));}catch(Exception ignored){}m=java.util.regex.Pattern.compile("(?:^|\\s)([0-9]+(?:[.,][0-9]+)?)(?:\\s|$)").matcher(title);if(m.find())try{return Float.parseFloat(m.group(1).replace(',','.'));}catch(Exception ignored){}}
        return fallback;
    }

    protected static class FilterSpec {
        String inclusion="OR", exclusion="OR"; ArrayList<String> genres=new ArrayList<>(), genresExcluded=new ArrayList<>(), types=new ArrayList<>(), statuses=new ArrayList<>(); boolean project;
        String key(){return inclusion+"/"+exclusion+"/"+genres+"/"+genresExcluded+"/"+types+"/"+statuses+"/"+project;}
    }
    private static FilterSpec parseFilter(String raw){
        FilterSpec f=new FilterSpec();
        if(raw==null||raw.trim().isEmpty()) return f;
        for(String part:raw.split("\\|")){
            String p=part==null?"":part.trim();
            if(p.isEmpty()) continue;
            int i=p.indexOf(':');
            String k=i>0?p.substring(0,i).trim().toLowerCase(Locale.ROOT):"genre";
            String v=i>0?p.substring(i+1).trim():p;
            if(v.isEmpty()) continue;
            switch(k){
                case "genre": f.genres.add(slugifyFilter(v)); break;
                case "genre_exclude": f.genresExcluded.add(slugifyFilter(v)); break;
                case "type": f.types.add(slugifyFilter(v)); break;
                case "status": f.statuses.add(slugifyFilter(v)); break;
                case "inclusion": f.inclusion=v.toUpperCase(Locale.ROOT); break;
                case "exclusion": f.exclusion=v.toUpperCase(Locale.ROOT); break;
                case "project": f.project="1".equals(v)||"true".equalsIgnoreCase(v); break;
                default: f.genres.add(slugifyFilter(v)); break;
            }
        }
        return f;
    }

    private static String slugifyFilter(String value){
        String v=value==null?"":value.trim();
        if(v.startsWith("http://")||v.startsWith("https://")){
            HttpUrl u=HttpUrl.parse(v); if(u!=null){ String q=u.queryParameter("slug"); if(q!=null&&!q.isEmpty()) v=q; }
        }
        try { v=URLDecoder.decode(v, StandardCharsets.UTF_8.name()); } catch(Exception ignored) { }
        return v.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$","");
    }
}
