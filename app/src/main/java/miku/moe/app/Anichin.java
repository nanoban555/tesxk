package miku.moe.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Parser + video extractor untuk Anichin (https://anichin.moe), situs donghua WordPress.
 *
 * Aturan pemutaran (sesuai permintaan):
 *  - Jika episode punya sumber Rumble yang playable -> KEMBALIKAN HANYA URL Rumble.
 *  - Jika tidak ada Rumble -> fallback berurutan: OK.ru, VidHide-family, Dailymotion
 *    (asli, bukan via webview), D-Tube, Doodstream. Semua langsung streamable di ExoPlayer,
 *    TIDAK PERNAH webview. Terabox di-skip total.
 */
public final class Anichin {
    private static final String BASE = "https://anichin.moe";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Set<String> ORDERS = new HashSet<>();
    private static final Map<String, String> GENRE_CACHE = new LinkedHashMap<>(); // label -> slug

    static {
        ORDERS.add("update");
        ORDERS.add("popular");
        ORDERS.add("title");
        ORDERS.add("titlereverse");
        ORDERS.add("latest");
        ORDERS.add("rating");
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(new CookieJar() {
                private final Map<String, List<Cookie>> store = new HashMap<>();

                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                    String host = url.host();
                    List<Cookie> existing = store.get(host);
                    if (existing == null) {
                        existing = new ArrayList<>();
                        store.put(host, existing);
                    }
                    for (Cookie cookie : cookies) {
                        boolean replaced = false;
                        for (int i = 0; i < existing.size(); i++) {
                            if (existing.get(i).name().equals(cookie.name())) {
                                existing.set(i, cookie);
                                replaced = true;
                                break;
                            }
                        }
                        if (!replaced) existing.add(cookie);
                    }
                }

                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                    List<Cookie> result = new ArrayList<>();
                    String host = url.host();
                    for (Map.Entry<String, List<Cookie>> entry : store.entrySet()) {
                        if (host.equals(entry.getKey()) || host.endsWith("." + entry.getKey())) {
                            result.addAll(entry.getValue());
                        }
                    }
                    return result;
                }
            })
            .build();

    private Anichin() {}

    public static String sourceLabel() {
        return "Anichin";
    }

    // ------------------------------------------------------------------ listing

    public static PageResult listing(String order, int page) throws IOException {
        String o = order == null ? "" : order.trim().toLowerCase(Locale.ROOT);
        if (!ORDERS.contains(o)) o = "update";
        // PENTING: ?order= hanya dihormati di halaman /anime/ (form filter action="/anime").
        // Homepage (/) mengabaikan parameter order -> semua tab tampil sama.
        String url = page <= 1 ? BASE + "/anime/?order=" + o : BASE + "/anime/?page=" + page + "&order=" + o;
        Document doc = Jsoup.parse(get(url));
        ArrayList<AnimePost> items = parseCards(doc);
        return new PageResult(items, hasNextListing(doc), items.size());
    }

    public static PageResult search(String query, int page) throws IOException {
        String q = query == null ? "" : query.trim();
        if (!useful(q)) return new PageResult(new ArrayList<>(), false, 0);
        String enc = URLEncoder.encode(q, "UTF-8");
        String url = page <= 1 ? BASE + "/?s=" + enc : BASE + "/page/" + page + "/?s=" + enc;
        Document doc = Jsoup.parse(get(url));
        ArrayList<AnimePost> items = parseCards(doc);
        return new PageResult(items, hasNextPaged(doc, page), items.size());
    }

    public static PageResult genre(String genreLabel, int page) throws IOException {
        String slug = genreSlugFor(genreLabel);
        if (!useful(slug)) return new PageResult(new ArrayList<>(), false, 0);
        String url = page <= 1 ? BASE + "/genres/" + slug + "/" : BASE + "/genres/" + slug + "/page/" + page + "/";
        Document doc = Jsoup.parse(get(url));
        ArrayList<AnimePost> items = parseCards(doc);
        return new PageResult(items, hasNextPaged(doc, page), items.size());
    }

    public static ArrayList<String> genres() throws IOException {
        synchronized (GENRE_CACHE) {
            if (!GENRE_CACHE.isEmpty()) return new ArrayList<>(GENRE_CACHE.keySet());
        }
        Document doc = Jsoup.parse(get(BASE + "/anime/"));
        Map<String, String> map = new LinkedHashMap<>();
        for (Element input : doc.select("form.filters input[name=\"genre[]\"]")) {
            String slug = input.attr("value").trim();
            String name = "";
            Element sibling = input.nextElementSibling();
            if (sibling != null && "label".equalsIgnoreCase(sibling.tagName())) name = sibling.text().trim();
            if (!useful(name) && input.parent() != null) {
                Element label = input.parent().selectFirst("label");
                if (label != null) name = label.text().trim();
            }
            if (useful(slug) && useful(name) && !map.containsKey(name)) map.put(name, slug);
        }
        synchronized (GENRE_CACHE) {
            GENRE_CACHE.putAll(map);
        }
        return new ArrayList<>(map.keySet());
    }

    private static String genreSlugFor(String genreLabel) throws IOException {
        if (!useful(genreLabel)) return "";
        synchronized (GENRE_CACHE) {
            if (!GENRE_CACHE.isEmpty()) {
                String target = normalize(genreLabel);
                for (Map.Entry<String, String> entry : GENRE_CACHE.entrySet()) {
                    if (normalize(entry.getKey()).equals(target)) return entry.getValue();
                }
            }
        }
        genres();
        synchronized (GENRE_CACHE) {
            String target = normalize(genreLabel);
            for (Map.Entry<String, String> entry : GENRE_CACHE.entrySet()) {
                if (normalize(entry.getKey()).equals(target)) return entry.getValue();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ detail

    public static DetailResult detail(String animeSlug) throws IOException {
        String slug = trimSlashes(animeSlug == null ? "" : animeSlug);
        AnimePost fallback = new AnimePost("", "", -1, -1);
        fallback.sourceId = AnimeSettingsManager.SOURCE_ANICHIN;
        fallback.slug = slug;
        if (!useful(slug)) return new DetailResult(fallback, "", new ArrayList<>(), new LinkedHashMap<>(), new ArrayList<>());

        Document doc = Jsoup.parse(get(BASE + "/" + slug + "/"));

        String title = textOf(doc.selectFirst("h1.entry-title"));
        if (!useful(title)) title = doc.title().trim();
        String alter = textOf(doc.selectFirst("span.alter"));
        String poster = absUrl(attrOf(doc.selectFirst("div.thumb img"), "src"));
        if (!useful(poster)) poster = absUrl(attrOf(doc.selectFirst("div.thumb img"), "data-src"));
        String rating = textOf(doc.selectFirst(".infox .numscore"));
        if (!useful(rating)) rating = textOf(doc.selectFirst(".numscore"));
        String description = textOf(doc.selectFirst("div.desc"));

        ArrayList<String> genres = new ArrayList<>();
        for (Element a : doc.select("div.genxed a")) {
            String g = a.text().trim();
            if (useful(g) && !genres.contains(g)) genres.add(g);
        }

        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        String statusRaw = "";
        String episodeCountRaw = "";
        for (Element span : doc.select("div.spe > span")) {
            Element b = span.selectFirst("b");
            String label = b == null ? "" : b.text().trim();
            if (label.endsWith(":")) label = label.substring(0, label.length() - 1).trim();
            String full = span.text().trim();
            String value = full;
            if (useful(label) && full.startsWith(label)) value = full.substring(label.length()).trim();
            if (value.startsWith(":")) value = value.substring(1).trim();
            if (!useful(label) || !useful(value)) continue; // jangan ambil field kosong
            String low = label.toLowerCase(Locale.ROOT);
            if (low.equals("status")) {
                statusRaw = value;
                continue;
            }
            if (low.equals("rating")) continue; // sudah tampil via post.rating
            if (low.equals("episode")) {
                episodeCountRaw = value;
                continue;
            }
            rows.put(label, value);
        }
        if (useful(alter)) rows.put("Judul Alternatif", alter);

        AnimePost post = new AnimePost(poster, cleanTitle(title), positiveId(slug), -1);
        post.sourceId = AnimeSettingsManager.SOURCE_ANICHIN;
        post.slug = slug;
        post.genre = joinStrings(genres);
        post.rating = rating.replaceAll("[^0-9.]", "").trim();
        post.description = description;
        post.statusVideo = normalizeStatus(statusRaw);
        post.ongoing = isOngoing(post.statusVideo);
        post.episodeCount = episodeCountRaw;

        ArrayList<EpisodeResult> episodes = parseEpisodes(doc);
        if (episodes.isEmpty() && doc.selectFirst("select.mirror") != null) {
            // Halaman movie: tidak ada daftar episode, tapi video player (select.mirror)
            // langsung ada di halaman detail. Buat satu entri agar bisa diputar.
            String pageUrl = BASE + "/" + slug + "/";
            String label = useful(title) ? title : "Movie";
            episodes.add(new EpisodeResult(positiveId(pageUrl), label, "", pageUrl, "", 1));
        }
        return new DetailResult(post, description, genres, rows, episodes);
    }

    private static ArrayList<EpisodeResult> parseEpisodes(Document doc) {
        ArrayList<EpisodeResult> result = new ArrayList<>();
        Pattern numPattern = Pattern.compile("-episode-(\\d+)", Pattern.CASE_INSENSITIVE);
        for (Element a : doc.select("div.eplister ul li a")) {
            String href = a.attr("href").trim();
            if (!useful(href)) continue;
            Matcher m = numPattern.matcher(href);
            if (!m.find()) continue;
            int num;
            try {
                num = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
                continue;
            }
            if (num <= 0) continue;
            String epUrl = absUrl(href);
            String date = textOf(a.selectFirst(".epl-date"));
            // Judul tampil "Episode N" saja (bukan judul panjang ala website)
            result.add(new EpisodeResult(positiveId(epUrl), "Episode " + num, date, epUrl, "", num));
        }
        result.sort((x, y) -> Integer.compare(x.episodeNumber, y.episodeNumber));
        return result;
    }

    // ------------------------------------------------------------------ playback

    /**
     * Resolve URL video langsung (streamable di ExoPlayer, tanpa webview) untuk satu episode.
     * Urutan: Rumble dulu — jika ada Rumble yang playable, HANYA URL Rumble yang dikembalikan.
     * Jika tidak ada, fallback: OK.ru -> VidHide-family -> Dailymotion asli -> D-Tube -> Doodstream.
     */
    public static ArrayList<QualityResult> playback(String episodeUrl) throws IOException {
        ArrayList<QualityResult> result = new ArrayList<>();
        if (!useful(episodeUrl)) return result;
        String pageUrl = episodeUrl.trim();
        if (!pageUrl.startsWith("http")) pageUrl = BASE + (pageUrl.startsWith("/") ? "" : "/") + pageUrl;

        Document doc;
        try {
            doc = Jsoup.parse(get(pageUrl));
        } catch (IOException e) {
            return result;
        }

        ArrayList<Mirror> mirrors = parseMirrors(doc);

        // 1) Rumble first: kumpulkan SEMUA url video dari Rumble, abaikan server lain.
        ArrayList<QualityResult> rumble = new ArrayList<>();
        for (Mirror mirror : mirrors) {
            if (!isRumble(mirror)) continue;
            try {
                for (QualityResult q : rumbleUrls(mirror)) addUrl(rumble, q.quality, q.label, q.url);
            } catch (Exception ignored) {}
        }
        if (!rumble.isEmpty()) return rumble;

        // 2) Tidak ada Rumble -> server lain yang mendukung pemutaran langsung.
        for (Mirror mirror : mirrors) {
            if (isRumble(mirror) || isSkipped(mirror)) continue;
            try {
                for (QualityResult q : resolveMirror(mirror)) addUrl(result, q.quality, q.label, q.url);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static ArrayList<Mirror> parseMirrors(Document doc) {
        ArrayList<Mirror> out = new ArrayList<>();
        for (Element opt : doc.select("select.mirror option")) {
            String b64 = opt.attr("value").trim();
            String label = opt.text().trim();
            if (!useful(b64)) continue; // placeholder "Pilih Server Video"
            String src = "";
            try {
                String decoded = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                Element iframe = Jsoup.parseBodyFragment(decoded).selectFirst("iframe");
                if (iframe != null) src = iframe.attr("src").trim();
                if (!useful(src) && iframe != null) src = iframe.attr("data-src").trim();
            } catch (Exception ignored) {}
            if (!useful(src)) continue;
            if (src.startsWith("//")) src = "https:" + src;
            out.add(new Mirror(label, src));
        }
        return out;
    }

    private static boolean isRumble(Mirror mirror) {
        String label = mirror.label.toLowerCase(Locale.ROOT);
        String src = mirror.src.toLowerCase(Locale.ROOT);
        return label.contains("rumble") || src.contains("rumble.com");
    }

    /** Server yang hanya bisa diputar via webview / jelek -> di-skip total. */
    private static boolean isSkipped(Mirror mirror) {
        String src = mirror.src.toLowerCase(Locale.ROOT);
        return src.contains("nunadrama") || src.contains("rpmvid") || src.contains("rpmshare")
                || src.contains("abyssplayer") || src.contains("terabox");
    }

    // ------------------------------------------------------------ Rumble

    private static ArrayList<QualityResult> rumbleUrls(Mirror mirror) throws IOException {
        ArrayList<QualityResult> out = new ArrayList<>();
        Matcher m = Pattern.compile("rumble\\.com/embed/([A-Za-z0-9]+)").matcher(mirror.src);
        if (!m.find()) return out;
        String id = m.group(1);
        String body = get("https://rumble.com/embedJS/u3/?request=video&ver=2&v=" + id);
        if (body == null || body.trim().equals("false")) return out; // video mati
        try {
            JSONObject json = new JSONObject(body);
            // HLS master adaptif (utama untuk ExoPlayer)
            addUrl(out, PlaybackQualityManager.QUALITY_FHD, "Rumble HLS Auto", jsonPath(json, "ua", "hls", "auto", "url"));
            addUrl(out, PlaybackQualityManager.QUALITY_FHD, "Rumble HLS Auto", jsonPath(json, "u", "hls", "url"));
            // MP4 langsung per resolusi (jika ada)
            scanMp4(json, "", out);
        } catch (Exception ignored) {}
        return out;
    }

    private static void scanMp4(Object node, String hint, ArrayList<QualityResult> out) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String key = it.next();
                String childHint = hint;
                if (key.matches("\\d+")) childHint = key;
                scanMp4(obj.opt(key), childHint, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) scanMp4(arr.opt(i), hint, out);
        } else if (node instanceof String) {
            String s = ((String) node).trim();
            String low = s.toLowerCase(Locale.ROOT);
            if (!(low.startsWith("http://") || low.startsWith("https://"))) return;
            String noQuery = low.contains("?") ? low.substring(0, low.indexOf('?')) : low;
            if (!noQuery.endsWith(".mp4")) return;
            String label = "Rumble MP4";
            String quality = PlaybackQualityManager.QUALITY_HD;
            try {
                int res = Integer.parseInt(hint.replaceAll("\\D+", ""));
                if (res > 0) {
                    label = "Rumble " + res + "p";
                    quality = res >= 720 ? PlaybackQualityManager.QUALITY_FHD
                            : res >= 480 ? PlaybackQualityManager.QUALITY_HD
                            : PlaybackQualityManager.QUALITY_SD;
                }
            } catch (Exception ignored) {}
            addUrl(out, quality, label, s);
        }
    }

    // ------------------------------------------------------------ fallback mirrors

    private static ArrayList<QualityResult> resolveMirror(Mirror mirror) throws IOException {
        String src = mirror.src;
        String low = src.toLowerCase(Locale.ROOT);
        String noQuery = low.contains("?") ? low.substring(0, low.indexOf('?')) : low;

        // File langsung
        if (noQuery.endsWith(".mp4")) {
            ArrayList<QualityResult> out = new ArrayList<>();
            addUrl(out, PlaybackQualityManager.QUALITY_HD, mirrorLabel(mirror, "MP4"), src);
            return out;
        }
        if (noQuery.endsWith(".m3u8")) {
            ArrayList<QualityResult> out = new ArrayList<>();
            addUrl(out, PlaybackQualityManager.QUALITY_FHD, mirrorLabel(mirror, "HLS"), src);
            return out;
        }

        if (low.contains("ok.ru")) return resolveOkru(src);
        // VidHide-family: cek URL dan LABEL (domain custom spt morencius.com/callistanise.com
        // tidak mengandung kata "vidhide" di URL, tapi labelnya "VidHide [ADS]").
        String labelLow = mirror.label.toLowerCase(Locale.ROOT);
        if (containsAny(low, "vidhide", "streamwish", "filemoon", "strwish", "wishfast", "streamhide", "vidmoly", "vidhidepro", "vidhideplus", "morencius", "callistanise")
                || containsAny(labelLow, "vidhide", "streamwish", "filemoon")) return resolveVidhide(src);
        if (low.contains("dailymotion.com")) return resolveDailymotion(src);
        if (low.contains("d.tube")) return resolveDtube(src);
        if (labelLow.contains("dood")) return resolveDood(mirror);
        return new ArrayList<>();
    }

    private static String mirrorLabel(Mirror mirror, String suffix) {
        String label = mirror.label.trim();
        if (label.isEmpty()) return "Server " + suffix;
        int cut = label.indexOf('[');
        if (cut > 0) label = label.substring(0, cut).trim();
        return label.isEmpty() ? "Server " + suffix : label + " " + suffix;
    }

    private static ArrayList<QualityResult> resolveOkru(String embedUrl) throws IOException {
        ArrayList<QualityResult> out = new ArrayList<>();
        Matcher m = Pattern.compile("ok\\.ru/videoembed/(\\d+)").matcher(embedUrl);
        if (!m.find()) return out;
        String html = get("https://ok.ru/videoembed/" + m.group(1));
        Matcher dm = Pattern.compile("data-options=\"([^\"]+)\"").matcher(html);
        if (!dm.find()) {
            dm = Pattern.compile("data-options='([^']+)'").matcher(html);
            if (!dm.find()) return out;
        }
        JSONObject metadata = null;
        try {
            String jsonStr = org.jsoup.parser.Parser.unescapeEntities(dm.group(1), false);
            JSONObject root = new JSONObject(jsonStr);
            JSONObject options = root.optJSONObject("options");
            JSONObject flashvars = options == null ? null : options.optJSONObject("flashvars");
            metadata = flashvars == null ? null : flashvars.optJSONObject("metadata");
        } catch (Exception ignored) {}
        if (metadata == null) return out;
        addUrl(out, PlaybackQualityManager.QUALITY_FHD, "Ok.ru HLS", metadata.optString("hlsManifestUrl", ""));
        JSONArray videos = metadata.optJSONArray("videos");
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.optJSONObject(i);
                if (v == null || v.optBoolean("disallowed", false)) continue;
                String url = v.optString("url", "");
                String name = v.optString("name", "").toLowerCase(Locale.ROOT);
                String quality = (name.contains("full") || name.contains("1080"))
                        ? PlaybackQualityManager.QUALITY_FHD
                        : (name.contains("hd") || name.contains("720") || name.contains("sd"))
                        ? PlaybackQualityManager.QUALITY_HD
                        : PlaybackQualityManager.QUALITY_SD;
                addUrl(out, quality, "Ok.ru " + v.optString("name", ""), url);
            }
        }
        return out;
    }

    private static ArrayList<QualityResult> resolveVidhide(String embedUrl) throws IOException {
        ArrayList<QualityResult> out = new ArrayList<>();
        String html = get(embedUrl);
        Matcher m = Pattern.compile(
                "eval\\(function\\(p,a,c,k,e,d\\).*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)",
                Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String payload;
            int radix, count;
            try {
                payload = m.group(1);
                radix = Integer.parseInt(m.group(2));
                count = Integer.parseInt(m.group(3));
            } catch (Exception ignored) {
                continue;
            }
            String[] tokens = m.group(4).split("\\|", -1);
            String unpacked = deanUnpack(payload, radix, count, tokens);
            String hls = firstGroup(unpacked, "\"hls2\"\\s*:\\s*\"([^\"]+)\"");
            if (!useful(hls)) hls = firstGroup(unpacked, "'hls2'\\s*:\\s*'([^']+)'");
            if (!useful(hls)) hls = firstGroup(unpacked, "\"hls4\"\\s*:\\s*\"([^\"]+)\"");
            if (useful(hls)) {
                addUrl(out, PlaybackQualityManager.QUALITY_FHD, "VidHide HLS", hls);
                break;
            }
        }
        return out;
    }

    /** Unpacker algoritma Dean Edwards packer (dipakai VidHide/StreamWish/Filemoon). */
    private static String deanUnpack(String p, int radix, int count, String[] tokens) {
        if (radix < 2) radix = 36;
        String[] dict = new String[count];
        for (int i = 0; i < count; i++) {
            String word = baseRadix(i, radix);
            dict[i] = (i < tokens.length && !tokens[i].isEmpty()) ? tokens[i] : word;
        }
        String result = p;
        for (int i = count - 1; i >= 0; i--) {
            String word = baseRadix(i, radix);
            result = result.replaceAll("\\b" + Pattern.quote(word) + "\\b", Matcher.quoteReplacement(dict[i]));
        }
        return result;
    }

    private static String baseRadix(int value, int radix) {
        StringBuilder sb = new StringBuilder();
        long n = value;
        do {
            long d = n % radix;
            n /= radix;
            sb.insert(0, d > 35 ? (char) (d + 29) : Character.forDigit((int) d, 36));
            if (n <= 0) break;
        } while (true);
        return sb.toString();
    }

    private static ArrayList<QualityResult> resolveDailymotion(String src) throws IOException {
        ArrayList<QualityResult> out = new ArrayList<>();
        Matcher m = Pattern.compile("dailymotion\\.com/(?:embed/)?video/([A-Za-z0-9]+)").matcher(src);
        if (!m.find()) return out;
        String body = get("https://www.dailymotion.com/player/metadata/video/" + m.group(1), "https://www.dailymotion.com/");
        try {
            JSONObject qualities = new JSONObject(body).optJSONObject("qualities");
            JSONArray auto = qualities == null ? null : qualities.optJSONArray("auto");
            if (auto != null && auto.length() > 0) {
                addUrl(out, PlaybackQualityManager.QUALITY_FHD, "Dailymotion HLS",
                        auto.optJSONObject(0).optString("url", ""));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static ArrayList<QualityResult> resolveDtube(String src) throws IOException {
        ArrayList<QualityResult> out = new ArrayList<>();
        Matcher m = Pattern.compile("[?&]v=([0-9a-fA-F-]{36})").matcher(src);
        if (!m.find()) {
            m = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})").matcher(src);
        }
        if (!m.find()) return out;
        String uuid = m.group(1);
        for (String host : new String[]{"https://nas1.d.tube", "https://nas2.d.tube"}) {
            String url = host + "/videos/" + uuid + "/master.m3u8";
            try {
                String body = get(url);
                if (body.contains("#EXTM3U")) {
                    addUrl(out, PlaybackQualityManager.QUALITY_FHD, "D-Tube HLS", url);
                    break;
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static ArrayList<QualityResult> resolveDood(Mirror mirror) throws IOException {
        ArrayList<QualityResult> out = new ArrayList<>();
        HttpUrl hu = HttpUrl.parse(mirror.src);
        if (hu == null) return out;
        String origin = hu.scheme() + "://" + hu.host();
        String html = get(mirror.src, BASE + "/");
        Matcher pm = Pattern.compile("/pass_md5/([^'\"\\s<>]+)").matcher(html);
        if (!pm.find()) return out;
        String prefix;
        try {
            prefix = get(origin + "/pass_md5/" + pm.group(1), mirror.src).trim();
        } catch (Exception ignored) {
            return out;
        }
        if (!useful(prefix) || prefix.contains(" ") || prefix.contains("<")) return out;
        String token = firstGroup(html, "[?&]token=([A-Za-z0-9]+)");
        if (!useful(token)) token = firstGroup(html, "token\\s*[:=]\\s*['\"]([A-Za-z0-9]+)['\"]");
        if (!useful(token)) return out;
        String finalUrl = prefix + randomAlphaNum(10) + "?token=" + token + "&expiry=" + System.currentTimeMillis();
        addUrl(out, PlaybackQualityManager.QUALITY_HD, "Doodstream", finalUrl);
        return out;
    }

    // ------------------------------------------------------------------ http

    private static String get(String url) throws IOException {
        return get(url, null);
    }

    private static String get(String url, String referer) throws IOException {
        Headers.Builder hb = new Headers.Builder()
                .add("User-Agent", UA)
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8");
        if (useful(referer)) hb.add("Referer", referer);
        Request request = new Request.Builder().url(url).headers(hb.build()).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            return response.body() == null ? "" : response.body().string();
        }
    }

    // ------------------------------------------------------------------ html helpers

    private static ArrayList<AnimePost> parseCards(Document doc) {
        ArrayList<AnimePost> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element a : doc.select("article.bs div.bsx a.tip")) {
            String href = a.attr("href").trim();
            String title = firstUseful(a.attr("title"), a.text());
            if (!useful(href) || !useful(title)) continue;
            String slug = trimSlashes(href);
            if (slug.startsWith("http")) {
                try {
                    HttpUrl parsed = HttpUrl.parse(slug);
                    if (parsed != null) slug = trimSlashes(parsed.encodedPath());
                } catch (Exception ignored) {}
            }
            // hanya path anime (bukan halaman episode)
            if (slug.toLowerCase(Locale.ROOT).contains("-episode-")) continue;
            if (!seen.add(slug)) continue;
            String img = a.select("img").attr("src");
            if (!useful(img)) img = a.select("img").attr("data-src");
            AnimePost post = new AnimePost(absUrl(img), cleanTitle(title), positiveId(slug), -1);
            post.sourceId = AnimeSettingsManager.SOURCE_ANICHIN;
            post.slug = slug;
            String epx = textOf(a.selectFirst(".epx"));
            if (useful(epx)) post.episodeCount = epx;
            String status = textOf(a.selectFirst(".status"));
            if (useful(status)) {
                post.statusVideo = normalizeStatus(status);
                post.ongoing = isOngoing(post.statusVideo);
            }
            String typez = textOf(a.selectFirst(".typez"));
            if (useful(typez)) post.channelName = typez;
            items.add(post);
        }
        return items;
    }

    private static boolean hasNextListing(Document doc) {
        for (Element a : doc.select("div.hpage a.r")) {
            if (a.text().toLowerCase(Locale.ROOT).contains("selanjutnya")) return true;
        }
        return false;
    }

    private static boolean hasNextPaged(Document doc, int page) {
        String needle = "/page/" + (page + 1) + "/";
        for (Element a : doc.select("a[href]")) {
            if (a.attr("href").contains(needle)) return true;
        }
        return false;
    }

    private static String absUrl(String value) {
        String v = value == null ? "" : value.trim();
        if (!useful(v)) return "";
        if (v.startsWith("http://") || v.startsWith("https://")) return v;
        if (v.startsWith("//")) return "https:" + v;
        if (!v.startsWith("/")) v = "/" + v;
        return BASE + v;
    }

    private static String textOf(Element element) {
        return element == null ? "" : element.text().trim();
    }

    private static String attrOf(Element element, String attr) {
        return element == null ? "" : element.attr(attr).trim();
    }

    // ------------------------------------------------------------------ misc helpers

    private static void addUrl(ArrayList<QualityResult> out, String quality, String label, String url) {
        String u = url == null ? "" : url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return;
        for (QualityResult q : out) if (q.url.equals(u)) return;
        out.add(new QualityResult(quality, label, u));
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private static String firstGroup(String text, String regex) {
        if (text == null) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String jsonPath(JSONObject json, String... keys) {
        JSONObject current = json;
        for (int i = 0; i < keys.length; i++) {
            if (current == null) return "";
            if (i == keys.length - 1) return current.optString(keys[i], "");
            current = current.optJSONObject(keys[i]);
        }
        return "";
    }

    private static String randomAlphaNum(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private static String trimSlashes(String value) {
        String v = value == null ? "" : value.trim();
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static int positiveId(String value) {
        int hash = value == null ? 1 : value.hashCode();
        return hash == Integer.MIN_VALUE ? 1 : Math.abs(hash);
    }

    private static String cleanTitle(String value) {
        String raw = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return raw;
    }

    private static String joinStrings(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (!useful(value)) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private static String firstUseful(String first, String second) {
        if (useful(first)) return first.trim();
        return useful(second) ? second.trim() : "";
    }

    private static boolean useful(String value) {
        String raw = value == null ? "" : value.trim();
        return !raw.isEmpty() && !"null".equalsIgnoreCase(raw) && !"-".equals(raw);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
    }

    private static String normalizeStatus(String value) {
        String raw = value == null ? "" : value.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("completed") || lower.contains("tamat") || lower.contains("finish")) return "Completed";
        if (lower.contains("ongoing") || lower.contains("on-going") || lower.contains("berjalan")) return "Ongoing";
        return raw;
    }

    private static boolean isOngoing(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("ongoing") || lower.contains("on-going") || lower.contains("berjalan");
    }

    private static final class Mirror {
        final String label;
        final String src;

        Mirror(String label, String src) {
            this.label = label == null ? "" : label;
            this.src = src == null ? "" : src;
        }
    }

    public static final class PageResult {
        public final ArrayList<AnimePost> items;
        public final boolean hasMore;
        public final int rawCount;

        PageResult(ArrayList<AnimePost> items, boolean hasMore, int rawCount) {
            this.items = items;
            this.hasMore = hasMore;
            this.rawCount = rawCount;
        }
    }

    public static final class DetailResult {
        public final AnimePost post;
        public final String description;
        public final ArrayList<String> genres;
        public final LinkedHashMap<String, String> rows;
        public final ArrayList<EpisodeResult> episodes;

        DetailResult(AnimePost post, String description, ArrayList<String> genres, LinkedHashMap<String, String> rows, ArrayList<EpisodeResult> episodes) {
            this.post = post;
            this.description = description;
            this.genres = genres;
            this.rows = rows;
            this.episodes = episodes;
        }
    }

    public static final class EpisodeResult {
        public final int id;
        public final String title;
        public final String subtitle;
        public final String episodeId;
        public final String thumbnail;
        public final int episodeNumber;

        EpisodeResult(int id, String title, String subtitle, String episodeId, String thumbnail, int episodeNumber) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.episodeId = episodeId;
            this.thumbnail = thumbnail;
            this.episodeNumber = episodeNumber;
        }
    }

    public static final class QualityResult {
        public final String quality;
        public final String label;
        public final String url;

        QualityResult(String quality, String label, String url) {
            this.quality = quality;
            this.label = label;
            this.url = url;
        }
    }
}
