package miku.moe.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import javax.net.ssl.SSLException;

public final class CloudflareHelper {
    private static Context appContext;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, CopyOnWriteArrayList<PendingRequest>> pending = new ConcurrentHashMap<>();
    private static final Map<String, ChallengeInfo> challenges = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> resolving = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<SolvedListener> solvedListeners = new CopyOnWriteArrayList<>();
    private static final CookieJar cookieJar = new WebViewCookieJar();
    private static final Map<String, CachedBody> solvedBodies = new ConcurrentHashMap<>();
    private static final long SOLVED_BODY_TTL_MS = 2L * 60L * 1000L;
    private static final String COOKIE_PREF = "cloudflare_cookie_store";
    private static final String UA_PREF = "cloudflare_ua_store";
    private static final String COMMON_BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private CloudflareHelper() {}

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        CookieManager.getInstance().setAcceptCookie(true);

        removePersistedLegacyLiveCookieHosts();
        restoreCookies();
        NetworkDohManager.init(appContext);
    }

    public static CookieJar cookieJar() {
        return cookieJar;
    }

    public static String browserUserAgent() {
        return COMMON_BROWSER_USER_AGENT;
    }

    public static String userAgentForHost(String host) {
        String key = normalizeHostKey(host);
        if (key.isEmpty()) return COMMON_BROWSER_USER_AGENT;
        String stored = uaPrefs().getString(key, "");
        return stored == null || stored.trim().isEmpty() ? COMMON_BROWSER_USER_AGENT : stored.trim();
    }

    public static void setUserAgentForHost(String host, String userAgent) {
        String key = normalizeHostKey(host);
        String value = userAgent == null ? "" : userAgent.trim();
        if (key.isEmpty() || value.isEmpty()) return;
        uaPrefs().edit().putString(key, value).apply();
    }

    private static SharedPreferences uaPrefs() {
        return appContext.getSharedPreferences(UA_PREF, Context.MODE_PRIVATE);
    }

    private static String normalizeHostKey(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    public static OkHttpClient.Builder apply(OkHttpClient.Builder builder) {
        return NetworkDohManager.apply(builder)
                .cookieJar(cookieJar)
                .retryOnConnectionFailure(true)
                .addInterceptor(UserAgentSyncInterceptor.INSTANCE);
    }

    private static final class UserAgentSyncInterceptor implements okhttp3.Interceptor {
        static final UserAgentSyncInterceptor INSTANCE = new UserAgentSyncInterceptor();

        @Override public okhttp3.Response intercept(Chain chain) throws IOException {
            okhttp3.Request request = chain.request();
            String host = normalizeHostKey(request.url().host());
            boolean hasStoredCookies = !storedCookieHeader(host).isEmpty();
            boolean hasClearance = false;
            for (okhttp3.Cookie cookie : cookieJar.loadForRequest(request.url())) {
                if ("cf_clearance".equals(cookie.name())) {
                    hasClearance = true;
                    break;
                }
            }
            if (!hasStoredCookies && !hasClearance) return chain.proceed(request);
            String ua = userAgentForHost(host);
            if (request.header("User-Agent") != null && request.header("User-Agent").equals(ua)) return chain.proceed(request);
            okhttp3.Request synced = request.newBuilder().header("User-Agent", ua).build();
            return chain.proceed(synced);
        }
    }

    public static void enqueue(OkHttpClient client, Request request, String sourceLabel, Callback callback) {
        enqueue(client, request, sourceLabel, callback, 0);
    }

    public static String errorMessage(Throwable e) {
        if (isInternetError(e)) return "Tidak ada koneksi";
        String message = e == null ? "" : e.getMessage();
        if (message == null || message.trim().isEmpty()) return "Gagal memuat data. Coba lagi.";
        String lower = message.toLowerCase(Locale.ROOT);
        if (isRateLimitMessage(message)) return "Terlalu banyak request";
        if (lower.contains("cloudflare")) return "Lewati Cloudflare dulu";
        return message;
    }

    public static boolean isInternetError(Throwable e) {
        return e instanceof UnknownHostException || e instanceof ConnectException || e instanceof SocketTimeoutException || e instanceof SSLException;
    }

    public static boolean isCloudflareRequiredMessage(String message) {
        String text = value(message).toLowerCase(Locale.ROOT);
        return text.contains("harap selesaikan cloudflare") || text.contains("lewati cloudflare");
    }

    public static boolean needsResolution(String sourceLabel) {
        String label = cleanLabel(sourceLabel);
        for (ChallengeInfo info : challenges.values()) {
            if (label.equals(cleanLabel(info.sourceLabel))) return true;
        }
        return false;
    }

    public static boolean openResolverForSource(String sourceLabel) {
        return openResolverForSource(null, sourceIdForLabel(sourceLabel), sourceLabel);
    }

    public static boolean openResolverForSource(Context context, String sourceId, String sourceLabel) {
        String label = cleanLabel(sourceLabel);
        for (Map.Entry<String, ChallengeInfo> entry : challenges.entrySet()) {
            ChallengeInfo info = entry.getValue();
            if (label.equals(cleanLabel(info.sourceLabel))) {
                String host = entry.getKey();
                if (resolving.put(host, true) == null) openResolver(context, info.url, host, info.sourceLabel);
                return true;
            }
        }
        String fallbackUrl = fallbackUrlForSource(context, sourceId);
        String fallbackHost = hostOf(fallbackUrl);
        if (fallbackUrl.isEmpty() || fallbackHost.isEmpty()) return false;
        challenges.put(fallbackHost, new ChallengeInfo(fallbackUrl, fallbackHost, label));
        if (resolving.put(fallbackHost, true) == null) openResolver(context, fallbackUrl, fallbackHost, label);
        return true;
    }

    private static void enqueue(OkHttpClient client, Request request, String sourceLabel, Callback callback, int retry) {
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onFailure(call, new IOException(errorMessage(e), e));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyPreview = "";
                try {
                    ResponseBody peek = response.peekBody(256L * 1024L);
                    bodyPreview = peek == null ? "" : peek.string();
                } catch (Exception ignored) {}
                if (isCloudflare(response, bodyPreview) && retry < 2) {
                    response.close();
                    queue(client, request, sourceLabel, callback, retry + 1);
                    return;
                }
                callback.onResponse(call, response);
            }
        });
    }

    private static void queue(OkHttpClient client, Request request, String sourceLabel, Callback callback, int retry) {
        String host = request.url().host();
        pending.compute(host, (key, requests) -> {
            CopyOnWriteArrayList<PendingRequest> queued = requests == null ? new CopyOnWriteArrayList<>() : requests;
            queued.add(new PendingRequest(client, request, sourceLabel, callback, retry));
            return queued;
        });
        challenges.put(host, new ChallengeInfo(request.url().toString(), host, sourceLabel));
        callback.onFailure(client.newCall(request), new IOException("🆘 Harap selesaikan Cloudflare pada Source Manga " + cleanLabel(sourceLabel) + " 🆘"));
    }

    private static void openResolver(Context context, String url, String host, String sourceLabel) {
        Context target = context != null ? context : appContext;
        if (target == null) {
            retry(host);
            return;
        }
        Intent intent = new Intent(target, CloudflareWebViewActivity.class);
        if (!(target instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("url", url);
        intent.putExtra("host", host);
        intent.putExtra("sourceLabel", cleanLabel(sourceLabel));
        target.startActivity(intent);
    }

    public static void solved(String host) {
        if (host != null && !host.trim().isEmpty()) persistCookies("https://" + host.trim() + "/");
        resolving.remove(host);
        ChallengeInfo info = challenges.remove(host);
        retry(host);
        notifySolved(host, info == null ? "" : info.sourceLabel);
    }

    public static void addSolvedListener(SolvedListener listener) {
        if (listener != null && !solvedListeners.contains(listener)) solvedListeners.add(listener);
    }

    public static void removeSolvedListener(SolvedListener listener) {
        if (listener != null) solvedListeners.remove(listener);
    }

    private static void notifySolved(String host, String sourceLabel) {
        for (SolvedListener listener : solvedListeners) main.post(() -> listener.onCloudflareSolved(host, sourceLabel));
    }

    public static void keepPending(String host) {
        resolving.remove(host);
    }

    public static void cacheSolvedBody(String url, String body) {
        String key = normalizeUrl(url);
        if (key.isEmpty() || !looksLikeSolvedData(body)) return;
        solvedBodies.put(key, new CachedBody(body, System.currentTimeMillis()));
    }

    public static String cachedSolvedBody(String url) {
        String key = normalizeUrl(url);
        if (key.isEmpty()) return "";
        CachedBody cached = solvedBodies.get(key);
        if (cached == null) return "";
        if (System.currentTimeMillis() - cached.createdAt > SOLVED_BODY_TTL_MS) {
            solvedBodies.remove(key);
            return "";
        }
        return cached.body == null ? "" : cached.body;
    }

    public static void verifySolved(String url, String host, VerifyCallback callback) {
        persistCookies(url);
        if (host != null && !host.trim().isEmpty()) persistCookies("https://" + host.trim() + "/");
        OkHttpClient client = apply(new OkHttpClient.Builder()).build();
        boolean apiRequest = value(url).contains("/api/");
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgentForHost(host))
                .header("Accept", apiRequest ? "application/json, text/plain, */*" : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.7")
                .header("Referer", "https://" + host + "/")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", apiRequest ? "cors" : "navigate")
                .header("Sec-Fetch-Dest", apiRequest ? "empty" : "document")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (callback != null) main.post(() -> callback.onResult(false, errorMessage(e)));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyPreview = "";
                try {
                    ResponseBody peek = response.peekBody(256L * 1024L);
                    bodyPreview = peek == null ? "" : peek.string();
                } catch (Exception ignored) {}
                boolean solvedByBody = looksLikeSolvedData(bodyPreview);
                boolean solved = response.isSuccessful() && (solvedByBody || !isCloudflare(response, bodyPreview));
                if (solvedByBody) cacheSolvedBody(url, bodyPreview);
                response.close();
                if (callback != null) main.post(() -> callback.onResult(solved, solved ? "" : "Cloudflare belum selesai"));
            }
        });
    }

    public static void persistCookies(String url) {
        if (appContext == null || url == null || url.trim().isEmpty()) return;
        String host = hostOf(url);
        if (host.isEmpty() || usesLegacyLiveCookies(host)) return;
        try {
            String raw = CookieManager.getInstance().getCookie(url);
            if (raw == null || raw.trim().isEmpty()) return;
            saveCookieHeader(host, raw);
        } catch (Exception ignored) { }
    }

    private static void restoreCookies() {
        if (appContext == null) return;
        try {
            CookieManager manager = CookieManager.getInstance();
            for (Map.Entry<String, ?> entry : cookiePrefs().getAll().entrySet()) {
                String host = entry.getKey() == null ? "" : entry.getKey().trim();
                String raw = entry.getValue() instanceof String ? (String) entry.getValue() : "";
                if (host.isEmpty() || usesLegacyLiveCookies(host) || raw.trim().isEmpty()) continue;
                for (String pair : splitCookieHeader(raw)) manager.setCookie("https://" + host + "/", pair + "; Path=/");
            }
            manager.flush();
        } catch (Exception ignored) { }
    }

    private static String storedCookieHeader(String host) {
        if (appContext == null || host == null || host.trim().isEmpty() || usesLegacyLiveCookies(host)) return "";
        return cookiePrefs().getString(host.trim(), "");
    }

    private static void saveCookieHeader(String host, String raw) {
        if (appContext == null || host == null || host.trim().isEmpty() || usesLegacyLiveCookies(host) || raw == null || raw.trim().isEmpty()) return;
        String merged = mergeCookieHeaders(storedCookieHeader(host), raw);
        if (!merged.isEmpty()) cookiePrefs().edit().putString(host.trim(), merged).apply();
    }

    private static String mergeCookieHeaders(String first, String second) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        addCookiePairs(values, first);
        addCookiePairs(values, second);
        StringBuilder result = new StringBuilder();
        for (String pair : values.values()) {
            if (result.length() > 0) result.append("; ");
            result.append(pair);
        }
        return result.toString();
    }

    private static void addCookiePairs(LinkedHashMap<String, String> values, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        for (String part : raw.split(";")) {
            String pair = part.trim();
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            String name = pair.substring(0, idx).trim();
            String value = pair.substring(idx + 1).trim();
            if (name.isEmpty()) continue;
            values.put(name, name + "=" + value);
        }
    }

    private static List<String> splitCookieHeader(String raw) {
        ArrayList<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String part : raw.split(";")) {
            String pair = part.trim();
            if (pair.indexOf('=') > 0) result.add(pair);
        }
        return result;
    }

    private static SharedPreferences cookiePrefs() {
        return appContext.getSharedPreferences(COOKIE_PREF, Context.MODE_PRIVATE);
    }

    private static boolean usesLegacyLiveCookies(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return value.equals("ikiru.wtf") || value.endsWith(".ikiru.wtf") ||
                value.equals("crotpedia.net") || value.endsWith(".crotpedia.net");
    }

    private static void removePersistedLegacyLiveCookieHosts() {
        if (appContext == null) return;
        try {
            SharedPreferences prefs = cookiePrefs();
            SharedPreferences.Editor editor = null;
            for (String host : prefs.getAll().keySet()) {
                if (!usesLegacyLiveCookies(host)) continue;
                if (editor == null) editor = prefs.edit();
                editor.remove(host);
            }
            if (editor != null) editor.apply();
        } catch (Exception ignored) { }
    }

    private static String sourceIdForLabel(String sourceLabel) {
        String label = cleanLabel(sourceLabel).toLowerCase(Locale.ROOT);
        if (label.equals("komikcast")) return MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
        if (label.equals("shinigami")) return MangaSettingsManager.MANGA_SOURCE_SHINIGAMI;
        if (label.equals("doujindesu")) return MangaSettingsManager.MANGA_SOURCE_DOUJINDESU;
        if (label.equals("westmanga")) return MangaSettingsManager.MANGA_SOURCE_WESTMANGA;
        if (label.equals("bacakomik")) return MangaSettingsManager.MANGA_SOURCE_BACAKOMIK;
        if (label.equals("komikindo")) return MangaSettingsManager.MANGA_SOURCE_KOMIKINDO;
        if (label.equals("ikiru")) return MangaSettingsManager.MANGA_SOURCE_IKIRU;
        if (label.equals("komiku") || label.equals("komiku asia")) return MangaSettingsManager.MANGA_SOURCE_KOMIKU;
        if (label.equals("mangasusuku") || label.equals("mangasusu")) return MangaSettingsManager.MANGA_SOURCE_MANGASUSU;
        if (label.equals("komiku org") || label.equals("komikuorg")) return MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG;
        if (label.equals("cosmicscans") || label.equals("cosmic scans")) return MangaSettingsManager.MANGA_SOURCE_COSMICSCANS;
        if (label.equals("kiryuu official") || label.equals("kiryuuofficial")) return MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL;
        if (label.equals("natsu")) return MangaSettingsManager.MANGA_SOURCE_NATSU;
        if (label.equals("ainzscanss") || label.equals("ainz scanss") || label.equals("ainzscans")) return MangaSettingsManager.MANGA_SOURCE_AINZSCANSS;
        if (label.equals("apkomik")) return MangaSettingsManager.MANGA_SOURCE_APKOMIK;
        if (label.equals("comicaso")) return MangaSettingsManager.MANGA_SOURCE_COMICASO;
        if (label.equals("crotpedia")) return MangaSettingsManager.MANGA_SOURCE_CROTPEDIA;
        if (label.equals("ngomik") || label.equals("ngomik id")) return MangaSettingsManager.MANGA_SOURCE_NGOMIK;
        if (label.equals("mgkomik") || label.equals("mg komik") || label.equals("mg-komik")) return MangaSettingsManager.MANGA_SOURCE_MGKOMIK;
        if (label.equals("kumopoi") || label.equals("kumo poi")) return MangaSettingsManager.MANGA_SOURCE_KUMOPOI;
        if (label.equals("manhwa indo") || label.equals("manhwaindo")) return MangaSettingsManager.MANGA_SOURCE_MANHWAINDO;
        if (label.equals("soul scans") || label.equals("soulscans")) return MangaSettingsManager.MANGA_SOURCE_SOULSCANS;
        if (label.equals("kuromanga") || label.equals("kuro manga") || label.equals("kuromanga id")) return MangaSettingsManager.MANGA_SOURCE_KUROMANGA;
        if (label.equals("isekai komik") || label.equals("isekaikomik") || label.equals("isekai komik site")) return MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK;
        return "";
    }

    private static String fallbackUrlForSource(Context context, String sourceId) {
        String source = sourceId == null ? "" : sourceId.trim();
        if (!source.isEmpty() && MangaSettingsManager.isValidSource(source)) return MangaSettingsManager.getSourceDomain(context, source);
        return "";
    }

    private static String hostOf(String url) {
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed == null ? "" : parsed.getHost();
            return host == null ? "" : host.trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static void failed(String host) {
        failed(host, "Cloudflare belum selesai");
    }

    public static void failed(String host, String reason) {
        resolving.remove(host);
        challenges.remove(host);
        CopyOnWriteArrayList<PendingRequest> list = pending.remove(host);
        if (list == null) return;
        String message = reason == null || reason.trim().isEmpty() ? "Cloudflare belum selesai" : reason.trim();
        for (PendingRequest item : list) {
            item.callback.onFailure(item.client.newCall(item.request), new IOException(errorMessage(new IOException(message))));
        }
    }

    private static void retry(String host) {
        CopyOnWriteArrayList<PendingRequest> list = pending.remove(host);
        if (list == null) return;
        for (PendingRequest item : list) enqueue(item.client, item.request, item.sourceLabel, item.callback, item.retry);
    }

    private static boolean isCloudflare(Response response, String body) {
        int code = response.code();
        String server = value(response.header("server")).toLowerCase(Locale.ROOT);
        String text = value(body).toLowerCase(Locale.ROOT);
        if (code == 429 || isRateLimitMessage(text)) return false;
        boolean status = code == 403 || code == 503;
        boolean header = server.contains("cloudflare");
        boolean challenge = text.contains("cf-browser-verification") || text.contains("cf_clearance") || text.contains("just a moment") || text.contains("challenge-platform") || text.contains("/cdn-cgi/challenge-platform") || text.contains("checking your browser") || text.contains("attention required") || text.contains("turnstile");
        return status && challenge && (header || text.contains("cloudflare"));
    }

    private static boolean isRateLimitMessage(String message) {
        String text = value(message).toLowerCase(Locale.ROOT);
        return text.contains("too many request") || text.contains("too many requests") || text.contains("retcode\": 40029") || text.contains("retcode:40029") || text.contains("rate limit") || text.contains("429");
    }

    private static boolean looksLikeSolvedData(String body) {
        String text = value(body).trim();
        if (text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("cf-browser-verification") || lower.contains("/cdn-cgi/challenge-platform") || lower.contains("turnstile") || lower.contains("just a moment") || lower.contains("attention required") || lower.contains("checking your browser")) return false;
        if (!(text.startsWith("{") || text.startsWith("["))) return false;
        return lower.contains("\"items\"") || lower.contains("\"chapters\"") || lower.contains("\"pages\"") || lower.contains("\"coverurl\"") || lower.contains("\"slug\"");
    }

    private static String normalizeUrl(String url) {
        try {
            HttpUrl parsed = HttpUrl.parse(value(url));
            return parsed == null ? "" : parsed.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String cleanLabel(String value) {
        return value == null || value.trim().isEmpty() ? "source" : value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static final class ChallengeInfo {
        final String url;
        final String host;
        final String sourceLabel;

        ChallengeInfo(String url, String host, String sourceLabel) {
            this.url = url;
            this.host = host;
            this.sourceLabel = sourceLabel;
        }
    }

    public interface VerifyCallback {
        void onResult(boolean solved, String message);
    }

    public interface SolvedListener {
        void onCloudflareSolved(String host, String sourceLabel);
    }

    private static final class CachedBody {
        final String body;
        final long createdAt;

        CachedBody(String body, long createdAt) {
            this.body = body;
            this.createdAt = createdAt;
        }
    }

    private static final class PendingRequest {
        final OkHttpClient client;
        final Request request;
        final String sourceLabel;
        final Callback callback;
        final int retry;

        PendingRequest(OkHttpClient client, Request request, String sourceLabel, Callback callback, int retry) {
            this.client = client;
            this.request = request;
            this.sourceLabel = sourceLabel;
            this.callback = callback;
            this.retry = retry;
        }
    }

    private static final class WebViewCookieJar implements CookieJar {
        @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            CookieManager manager = CookieManager.getInstance();
            StringBuilder responseHeader = new StringBuilder();
            for (Cookie cookie : cookies) {
                manager.setCookie(url.toString(), cookie.toString());
                if (!usesLegacyLiveCookies(url.host())) {
                    if (responseHeader.length() > 0) responseHeader.append("; ");
                    responseHeader.append(cookie.name()).append('=').append(cookie.value());
                }
            }
            manager.flush();
            if (!usesLegacyLiveCookies(url.host())) {
                if (responseHeader.length() > 0) saveCookieHeader(url.host(), responseHeader.toString());
                persistCookies(url.toString());
            }
        }

        @Override public List<Cookie> loadForRequest(HttpUrl url) {
            ArrayList<Cookie> out = new ArrayList<>();
            String webViewRaw = "";
            try {
                String value = CookieManager.getInstance().getCookie(url.toString());
                if (value != null) webViewRaw = value;
            } catch (Exception ignored) { }
            
            String raw = usesLegacyLiveCookies(url.host()) ? webViewRaw : mergeCookieHeaders(storedCookieHeader(url.host()), webViewRaw);
            if (raw.isEmpty()) return out;
            if (!usesLegacyLiveCookies(url.host())) saveCookieHeader(url.host(), raw);
            for (String item : splitCookieHeader(raw)) {
                int idx = item.indexOf('=');
                if (idx <= 0) continue;
                try {
                    Cookie cookie = new Cookie.Builder()
                            .domain(url.host())
                            .path("/")
                            .name(item.substring(0, idx).trim())
                            .value(item.substring(idx + 1).trim())
                            .build();
                    out.add(cookie);
                } catch (IllegalArgumentException ignored) { }
            }
            return out;
        }
    }
}
