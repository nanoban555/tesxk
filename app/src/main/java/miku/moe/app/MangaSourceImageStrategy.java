package miku.moe.app;

import java.util.Locale;
import okhttp3.Headers;
import okhttp3.HttpUrl;

public final class MangaSourceImageStrategy {
    private static final String UA_ANDROID_CHROME_149 = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36";
    private static final String UA_ANDROID_CHROME_151 = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36";
    private static final String UA_ANDROID_CHROME_152 = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36";
    private static final String DEFAULT_IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";

    private MangaSourceImageStrategy() {}

    public static String resolveImageUrl(String url, String sourceId) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) return "";

        if (value.startsWith("//")) return "https:" + value;

        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_IKIRU) && (value.startsWith("http://") || value.startsWith("https://"))) return value;
        if (!value.startsWith("/")) return value;

        String base = MangaSettingsManager.getSourceDomain(sourceId);
        if (base == null || base.trim().isEmpty()) return value;
        String cleanBase = trimTrailingSlash(base.trim());
        return cleanBase + value;
    }

    public static Headers requestHeaders(String url, String sourceId, boolean directRetry, boolean directHeaderFallback, String registeredReferer) {
        if (directHeaderFallback) return emptyHeaders();
        if (directRetry && isDoujinImageRequest(url, sourceId)) return doujinRetryHeaders();
        return headersFor(url, sourceId, registeredReferer);
    }

    public static Headers headersFor(String url, String sourceId, String registeredReferer) {
        String safeUrl = url == null ? "" : url.trim();
        String lowerUrl = lower(safeUrl);

        String pageReferer = registeredReferer == null ? "" : registeredReferer.trim();

        if (isApkomikImage(safeUrl, sourceId)) {
            String referer = pageReferer.isEmpty() ? ensureTrailingSlash(sourceBase(MangaSettingsManager.MANGA_SOURCE_APKOMIK)) : pageReferer;
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .build();
        }

        if (isKomikTapImage(safeUrl, sourceId)) {
            String referer = ensureTrailingSlash(sourceBase(MangaSettingsManager.MANGA_SOURCE_KOMIKTAP));
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.8")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .build();
        }

        if (isManhwaIndoImage(safeUrl, sourceId)) {
            String base = sourceBase(MangaSettingsManager.MANGA_SOURCE_MANHWAINDO);
            String referer = pageReferer.isEmpty() ? ensureTrailingSlash(base) : pageReferer;
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("Origin", base)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .build();
        }

        if (isSoulScansImage(safeUrl, sourceId)) {
            String base = sourceBase(MangaSettingsManager.MANGA_SOURCE_SOULSCANS);
            String referer = pageReferer.isEmpty() ? ensureTrailingSlash(base) : pageReferer;
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("Origin", base)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Cache-Control", "no-cache")
                    .set("Pragma", "no-cache")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .set("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                    .set("sec-ch-ua-mobile", "?1")
                    .set("sec-ch-ua-platform", "\"Android\"")
                    .build();
        }

        if (isKuromangaImage(safeUrl, sourceId)) {
            String base = sourceBase(MangaSettingsManager.MANGA_SOURCE_KUROMANGA);
            String referer = pageReferer.isEmpty() ? ensureTrailingSlash(base) : pageReferer;
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("Origin", base)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Cache-Control", "no-cache")
                    .set("Pragma", "no-cache")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .set("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                    .set("sec-ch-ua-mobile", "?1")
                    .set("sec-ch-ua-platform", "\"Android\"")
                    .build();
        }

        if (isIsekaiKomikImage(safeUrl, sourceId)) {
            String base = sourceBase(MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK);
            String referer = pageReferer.isEmpty() ? ensureTrailingSlash(base) : pageReferer;
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("Origin", base)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Cache-Control", "no-cache")
                    .set("Pragma", "no-cache")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .set("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                    .set("sec-ch-ua-mobile", "?1")
                    .set("sec-ch-ua-platform", "\"Android\"")
                    .build();
        }

        if (isLuvyaaImage(safeUrl, sourceId)) {
            String base = sourceBase(MangaSettingsManager.MANGA_SOURCE_LUVYAA);
            String referer = pageReferer.isEmpty() ? ensureTrailingSlash(base) : pageReferer;
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .build();
        }

        if (isSekteDoujinImage(safeUrl, sourceId)) {
            String base = sourceBase(MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN);
            String referer = pageReferer.isEmpty() ? ensureTrailingSlash(base) : pageReferer;
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .build();
        }

        if (isIkiruImage(safeUrl, sourceId, pageReferer)) {
            String base = sourceBase(MangaSettingsManager.MANGA_SOURCE_IKIRU);
            String referer = ikiruReaderCdnImage(safeUrl) ? ensureTrailingSlash(base) : (pageReferer.isEmpty() ? ensureTrailingSlash(base) : pageReferer);
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.7")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .build();
        }

        if (usesNoCustomHeaders(safeUrl, sourceId)) return emptyHeaders();

        if (lowerUrl.contains("kumo.gorae.my.id") || isKumoMediaImage(lowerUrl)) {
            String fetchSite = isKumoMediaImage(lowerUrl) ? "same-site" : "cross-site";
            return new Headers.Builder()
                    .set("Referer", "https://beta.kumopoi.com/")
                    .set("User-Agent", UA_ANDROID_CHROME_151)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.8")
                    .set("Sec-Fetch-Site", fetchSite)
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .set("sec-ch-ua", "\"Chromium\";v=\"151\", \"Not_A Brand\";v=\"99\"")
                    .set("sec-ch-ua-mobile", "?1")
                    .set("sec-ch-ua-platform", "\"Android\"")
                    .build();
        }

        if (isDoujinImageRequest(safeUrl, sourceId)) {
            String referer = ensureTrailingSlash(sourceBase(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU));
            return new Headers.Builder()
                    .set("Referer", referer)
                    .set("User-Agent", UA_ANDROID_CHROME_152)
                    .set("Accept", DEFAULT_IMAGE_ACCEPT)
                    .set("Accept-Language", "id-ID,id;q=0.9")
                    .set("Cache-Control", "no-cache")
                    .set("Pragma", "no-cache")
                    .set("Sec-Fetch-Dest", "image")
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Site", fetchSite(referer, safeUrl))
                    .set("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                    .set("sec-ch-ua-mobile", "?1")
                    .set("sec-ch-ua-platform", "\"Android\"")
                    .build();
        }

        RefererPolicy refererPolicy = refererPolicyFor(safeUrl, sourceId, pageReferer);
        boolean komikuImage = isKomikuImage(safeUrl, sourceId);
        boolean ngomikImage = isNgomikImage(safeUrl, sourceId);
        String accept = ngomikImage ? "*/*" : DEFAULT_IMAGE_ACCEPT;

        Headers.Builder builder = new Headers.Builder()
                .set("Referer", refererPolicy.referer)
                .set("User-Agent", UA_ANDROID_CHROME_149)
                .set("Accept", accept);

        if (!ngomikImage && !komikuImage) builder.set("Origin", refererPolicy.origin);

        if (komikuImage) {
            builder.set("User-Agent", CloudflareHelper.browserUserAgent())
                    .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .set("Sec-Fetch-Dest", "image")
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Site", fetchSite(refererPolicy.referer, safeUrl));
        } else if (ngomikImage) {
            builder.set("Accept-Language", "id-ID,id;q=0.6,en-US;q=0.4,en;q=0.3")
                    .set("Sec-GPC", "1")
                    .set("Sec-Fetch-Site", "cross-site")
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Dest", "image")
                    .set("sec-ch-ua-platform", "\"Android\"")
                    .set("sec-ch-ua-mobile", "?1")
                    .set("sec-ch-ua", "\"Brave\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"");
        } else {
            builder.set("Cache-Control", "public, max-age=604800");
        }
        return builder.build();
    }

    private static boolean isKumoMediaImage(String lowerUrl) {
        return lowerUrl.contains("api.kumopoi.com/api/v1/media/signed")
                || lowerUrl.contains("api.kumopoi.com/api/v1/media/chapter/deliver");
    }

    public static boolean shouldRetryWithDirectHeaders(String url, String sourceId, boolean local) {
        if (local) return false;
        String safeUrl = url == null ? "" : url.trim();
        String lowerUrl = lower(safeUrl);
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) return false;
        return !usesNoCustomHeaders(safeUrl, sourceId);
    }

    public static boolean usesNoCustomHeaders(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL)
                || lowerUrl.contains("kiryuu.to")
                || lowerUrl.contains("yuucdn.com");
    }

    private static boolean isIkiruImage(String url, String sourceId, String pageReferer) {
        String lowerUrl = lower(url);
        String lowerReferer = lower(pageReferer);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_IKIRU)
                || lowerUrl.contains("ikiru.wtf")
                || lowerUrl.contains("kiru.kyut.dev")
                || ((lowerUrl.contains("cdn.uqni.net/images/") || lowerUrl.contains("r2.uqni.net/images/")) && lowerReferer.contains("ikiru.wtf"));
    }

    private static boolean ikiruReaderCdnImage(String url) {
        String lowerUrl = lower(url);
        return lowerUrl.contains("kiru.kyut.dev/wp-content/scr/")
                || lowerUrl.contains("cdn.uqni.net/images/")
                || lowerUrl.contains("r2.uqni.net/images/");
    }

    private static boolean isApkomikImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_APKOMIK)
                || lowerUrl.contains("apkomik.com")
                || lowerUrl.contains("cdnap.site");
    }

    private static boolean isKomikTapImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KOMIKTAP)
                || lowerUrl.contains("komiktap.info/wp-content/uploads")
                || lowerUrl.contains("wibulep.xyz/uploads/manga-images/");
    }

    private static boolean isManhwaIndoImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        // Host FIFU eksternal (cover dari plugin Featured Image from URL): memblokir
        // hotlink bila dikirimi referer asing -> biarkan ditangani cabang Ikiru.
        if (lowerUrl.contains("ikiru.wtf")) return false;
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_MANHWAINDO)
                || lowerUrl.contains("manhwaindo.my/wp-content/uploads")
                || lowerUrl.contains("gmbr.pro/uploads/manga-images/")
                || lowerUrl.contains("gmbr.pro/uploads/");
    }

    private static boolean isSoulScansImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_SOULSCANS)
                || lowerUrl.contains("v1.soulscans.org")
                || lowerUrl.contains("img.soulscans.org")
                || lowerUrl.contains("ss.dbm.my.id")
                || lowerUrl.contains("soulscans.org/api/uploads")
                || lowerUrl.contains("soulscans.org/uploads");
    }

    private static boolean isKuromangaImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KUROMANGA)
                || lowerUrl.contains("kuromanga.id")
                || lowerUrl.contains("yuucdn.com")
                || lowerUrl.contains("i0.wp.com/kuromanga.id")
                || lowerUrl.contains("i1.wp.com/kuromanga.id")
                || lowerUrl.contains("i2.wp.com/kuromanga.id")
                || lowerUrl.contains("i3.wp.com/kuromanga.id");
    }

    private static boolean isIsekaiKomikImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK)
                || lowerUrl.contains("ch1.isekaikomik.site")
                || lowerUrl.contains("isekaikomik.site")
                || lowerUrl.contains("cdn1.isekaikomik.com")
                || lowerUrl.contains("cdn2.isekaikomik.com")
                || lowerUrl.contains("cdn3.isekaikomik.com")
                || lowerUrl.contains("isekaikomik.com/wp-content/img")
                || lowerUrl.contains("i0.wp.com/ch1.isekaikomik.site")
                || lowerUrl.contains("i1.wp.com/ch1.isekaikomik.site")
                || lowerUrl.contains("i2.wp.com/ch1.isekaikomik.site")
                || lowerUrl.contains("i3.wp.com/ch1.isekaikomik.site");
    }

    private static boolean isLuvyaaImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_LUVYAA)
                || lowerUrl.contains("cdn-nyaa.link");
    }

    private static boolean isSekteDoujinImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN)
                || lowerUrl.contains("cdn.uqni.net")
                || lowerUrl.contains("cdn.komikindo.info");
    }

    public static boolean isDoujinImageRequest(String url, String sourceId) {
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_DOUJINDESU)) return true;
        String safeUrl = lower(url);
        return safeUrl.contains("doujin.desu.xxx")
                || safeUrl.contains("desu.photos")
                || safeUrl.contains("desu.pics")
                || safeUrl.contains("amz-ch.desu.pics")
                || safeUrl.contains("ch-img.desu.pics")
                || safeUrl.contains("pic.desu.xxx")
                || safeUrl.contains("cdn-static.desu.xxx");
    }

    private static Headers doujinRetryHeaders() {
        String referer = ensureTrailingSlash(sourceBase(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU));
        return new Headers.Builder()
                .set("Referer", referer)
                .set("User-Agent", UA_ANDROID_CHROME_152)
                .set("Accept", DEFAULT_IMAGE_ACCEPT)
                .set("Accept-Language", "id-ID,id;q=0.9")
                .set("Cache-Control", "no-cache")
                .set("Pragma", "no-cache")
                .set("Sec-Fetch-Dest", "image")
                .set("Sec-Fetch-Mode", "no-cors")
                .set("Sec-Fetch-Site", "cross-site")
                .set("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Brave\";v=\"152\"")
                .set("sec-ch-ua-mobile", "?1")
                .set("sec-ch-ua-platform", "\"Android\"")
                .build();
    }

    private static Headers emptyHeaders() {
        return new Headers.Builder().build();
    }

    private static RefererPolicy refererPolicyFor(String url, String sourceId, String pageReferer) {
        String lowerUrl = lower(url);

        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_WESTMANGA)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_WESTMANGA);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_BACAKOMIK)) return fixedPolicy("https://bacakomik.my/", "https://bacakomik.my");
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KOMIKINDO)) return fixedPolicy("https://komikindo.ch/", "https://komikindo.ch");
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KOMIKU)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_KOMIKU, pageReferer);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_MANGASUSU)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_MANGASUSU);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_SHINIGAMI)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_COSMICSCANS)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_COSMICSCANS);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_NATSU)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_NATSU);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_AINZSCANSS)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_AINZSCANSS);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_APKOMIK)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_APKOMIK, pageReferer);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_COMICASO)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_COMICASO);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_CROTPEDIA)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_CROTPEDIA);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_NGOMIK)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_NGOMIK);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KUMOPOI)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_KUMOPOI);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_MGKOMIK)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_MGKOMIK, pageReferer);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KOMIKTAP)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_KOMIKTAP);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_MANHWAINDO)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_MANHWAINDO, pageReferer);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_SOULSCANS)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_SOULSCANS, pageReferer);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KUROMANGA)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_KUROMANGA, pageReferer);
        if (sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK, pageReferer);

        if (lowerUrl.contains("westmanga.co") || lowerUrl.contains("westmanga.my")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_WESTMANGA);
        if (lowerUrl.contains("bacakomik.my")) return fixedPolicy("https://bacakomik.my/", "https://bacakomik.my");
        if (lowerUrl.contains("komikindo.ch")) return fixedPolicy("https://komikindo.ch/", "https://komikindo.ch");
        if (isKomikuImage(url, sourceId)) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_KOMIKU, pageReferer);
        if (lowerUrl.contains("shngm") || lowerUrl.contains("shinigami")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI);
        if (lowerUrl.contains("cosmicscans") || lowerUrl.contains("csmcscns.id") || lowerUrl.contains("dbm.my.id") || lowerUrl.contains("skyfile.me") || lowerUrl.contains("cdn.uqni.net/users/217/")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_COSMICSCANS);
        if (lowerUrl.contains("natsu.one") || lowerUrl.contains("natsu.tv") || lowerUrl.contains("uqni.net")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_NATSU);
        if (lowerUrl.contains("ainzscans01.com") || lowerUrl.contains("cdnainz.lonedev.my.id")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_AINZSCANSS);
        if (lowerUrl.contains("apkomik.com") || lowerUrl.contains("cdnap.site")) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_APKOMIK, pageReferer);
        if (lowerUrl.contains("komiku.org")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG);
        if (lowerUrl.contains("comicaso.pro") || lowerUrl.contains("imgmanga.com") || lowerUrl.contains("imgmacha.com") || lowerUrl.contains("basrat.online") || lowerUrl.contains("gurihnyoh.site") || lowerUrl.contains("jeletot.fun")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_COMICASO);
        if (lowerUrl.contains("crotpedia.net") || lowerUrl.contains("cover.eromanga.cfd") || lowerUrl.contains("eromanga.cfd")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_CROTPEDIA);
        if (isNgomikImage(url, sourceId)) return basePolicy(MangaSettingsManager.MANGA_SOURCE_NGOMIK);
        if (lowerUrl.contains("kumopoi.org")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_KUMOPOI);
        if (lowerUrl.contains("mgkomik.cc") || lowerUrl.contains("mgkomik.my.id") || lowerUrl.contains("mgis.my.id")) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_MGKOMIK, pageReferer);
        if (lowerUrl.contains("komiktap.info") || lowerUrl.contains("wibulep.xyz/uploads/manga-images/")) return basePolicy(MangaSettingsManager.MANGA_SOURCE_KOMIKTAP);
        if (lowerUrl.contains("manhwaindo.my") || lowerUrl.contains("gmbr.pro/uploads/manga-images/") || lowerUrl.contains("gmbr.pro/uploads/")) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_MANHWAINDO, pageReferer);
        if (lowerUrl.contains("soulscans.org") || lowerUrl.contains("img.soulscans.org") || lowerUrl.contains("ss.dbm.my.id")) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_SOULSCANS, pageReferer);
        if (lowerUrl.contains("kuromanga.id") || lowerUrl.contains("yuucdn.com")) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_KUROMANGA, pageReferer);
        if (lowerUrl.contains("isekaikomik.site") || lowerUrl.contains("isekaikomik.com/wp-content/img") || lowerUrl.contains("cdn1.isekaikomik.com") || lowerUrl.contains("cdn2.isekaikomik.com") || lowerUrl.contains("cdn3.isekaikomik.com")) return basePolicyWithReferer(MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK, pageReferer);

        return basePolicy(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
    }

    private static boolean isKomikuImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_KOMIKU)
                || lowerUrl.contains("img.komiku.org")
                || lowerUrl.contains("komikid.org")
                || lowerUrl.contains("content.komiku.me")
                || lowerUrl.contains("cdnkomiku.xyz")
                || lowerUrl.contains("komiku.ae")
                || lowerUrl.contains("thumbnail.komiku")
                || lowerUrl.contains("update.komiku")
                || lowerUrl.contains("komiku.asia");
    }

    private static boolean isNgomikImage(String url, String sourceId) {
        String lowerUrl = lower(url);
        return sourceEquals(sourceId, MangaSettingsManager.MANGA_SOURCE_NGOMIK)
                || lowerUrl.contains("ngomik.cc")
                || lowerUrl.contains("bid-cdn.cloud");
    }

    private static RefererPolicy basePolicy(String sourceId) {
        String base = sourceBase(sourceId);
        return fixedPolicy(ensureTrailingSlash(base), base);
    }

    private static RefererPolicy basePolicyWithReferer(String sourceId, String registeredReferer) {
        String base = sourceBase(sourceId);
        String page = registeredReferer == null ? "" : registeredReferer.trim();
        return fixedPolicy(page.isEmpty() ? ensureTrailingSlash(base) : page, base);
    }

    private static RefererPolicy fixedPolicy(String referer, String origin) {
        String safeOrigin = origin == null ? "" : origin.trim();
        String safeReferer = referer == null ? "" : referer.trim();
        if (safeOrigin.isEmpty()) safeOrigin = sourceBase(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        if (safeReferer.isEmpty()) safeReferer = ensureTrailingSlash(safeOrigin);
        return new RefererPolicy(safeReferer, safeOrigin);
    }

    private static String sourceBase(String sourceId) {
        String base = MangaSettingsManager.getSourceDomain(sourceId);
        if (base == null || base.trim().isEmpty()) base = MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        if (base == null) return "";
        return trimTrailingSlash(base.trim());
    }

    private static String ensureTrailingSlash(String value) {
        String safe = value == null ? "" : value.trim();
        return safe.endsWith("/") ? safe : safe + "/";
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static boolean sourceEquals(String sourceId, String expected) {
        return expected != null && expected.equals(sourceId);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String fetchSite(String referer, String targetUrl) {
        try {
            HttpUrl ref = HttpUrl.parse(referer);
            HttpUrl target = HttpUrl.parse(targetUrl);
            if (ref == null || target == null) return "cross-site";
            if (ref.host().equalsIgnoreCase(target.host())) return "same-origin";
            String refSite = registrableSite(ref.host());
            String targetSite = registrableSite(target.host());
            return !refSite.isEmpty() && refSite.equalsIgnoreCase(targetSite) ? "same-site" : "cross-site";
        } catch (Exception ignored) {
            return "cross-site";
        }
    }

    private static String registrableSite(String host) {
        if (host == null) return "";
        String clean = host.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return "";
        String[] parts = clean.split("\\.");
        if (parts.length < 2) return clean;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static final class RefererPolicy {
        final String referer;
        final String origin;

        RefererPolicy(String referer, String origin) {
            this.referer = referer;
            this.origin = origin;
        }
    }
}
