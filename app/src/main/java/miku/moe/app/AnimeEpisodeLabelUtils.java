package miku.moe.app;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AnimeEpisodeLabelUtils {
    private static final Pattern FRACTION = Pattern.compile("(?i)^\\s*(\\d+(?:[.,]\\d+)?)\\s*/\\s*\\d+(?:[.,]\\d+)?\\s*(?:episode|episodes|eps|ep)?\\b");
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)\\bS\\d{1,3}\\s*E\\s*(\\d+(?:[.,]\\d+)?)\\b");
    private static final Pattern PREFIX = Pattern.compile("(?i)\\b(?:episode|episodes|eps|ep)\\s*[-:#.]?\\s*(\\d+(?:[.,]\\d+)?)\\b");
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:episode|episodes|eps|ep)\\b");
    private static final Pattern SHORT = Pattern.compile("(?i)\\bE\\s*(\\d+(?:[.,]\\d+)?)\\b");
    private static final Pattern PART = Pattern.compile("(?i)\\b(?:part|pt)\\s*[-:#.]?\\s*(\\d+(?:[.,]\\d+)?)\\b");
    private static final Pattern VIDEO_COUNT = Pattern.compile("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:video|videos)\\b");
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*$");
    private static final Pattern TAIL = Pattern.compile("(?i)(?:^|[\\s\\-–—_])0*(\\d+(?:[.,]\\d+)?)\\s*(?:sub\\s*indo|subtitle\\s*indonesia)?\\s*$");
    private static final Pattern LAST_WORD = Pattern.compile("(?i)([a-z]+)\\s*$");

    private AnimeEpisodeLabelUtils() {}

    public static String latestLabel(AnimePost post) {
        if (post == null) return "";
        String value = normalize(post.channelName);
        if (!value.isEmpty()) return value;
        return normalize(post.episodeCount);
    }

    public static String normalize(String value) {
        String number = extractNumber(value);
        return number.isEmpty() ? "" : "Ep " + number;
    }

    public static String historyLabel(String value) {
        String number = extractNumber(value);
        return number.isEmpty() ? "" : "Episode " + number;
    }

    public static String extractNumber(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "";
        String number = match(FRACTION, clean);
        if (number.isEmpty()) number = match(SEASON_EPISODE, clean);
        if (number.isEmpty()) number = match(PREFIX, clean);
        if (number.isEmpty()) number = match(SUFFIX, clean);
        if (number.isEmpty()) number = match(SHORT, clean);
        if (number.isEmpty()) number = match(PART, clean);
        if (number.isEmpty()) number = match(VIDEO_COUNT, clean);
        if (number.isEmpty()) number = match(NUMBER_ONLY, clean);
        if (number.isEmpty()) number = tailNumber(clean);
        return normalizeNumber(number);
    }

    public static double numericValue(String value) {
        String number = extractNumber(value);
        if (number.isEmpty()) return -1d;
        try {
            return Double.parseDouble(number);
        } catch (Exception ignored) {
            return -1d;
        }
    }

    public static String fromCount(int count) {
        return count > 0 ? "Ep " + count : "";
    }

    private static String tailNumber(String value) {
        String clean = value.replaceAll("(?i)\\b(?:HD|FHD|UHD|SD)\\b", " ").replaceAll("(?i)\\b(?:360|480|720|1080|1440|2160)p\\b", " ").trim().replaceAll("\\s+", " ");
        Matcher matcher = TAIL.matcher(clean);
        if (!matcher.find()) return "";
        String candidate = normalizeNumber(matcher.group(1));
        if (candidate.isEmpty()) return "";
        try {
            int whole = new BigDecimal(candidate).intValueExact();
            if (whole >= 1900 && whole <= 2099) return "";
            if (whole == 360 || whole == 480 || whole == 720 || whole == 1080 || whole == 1440 || whole == 2160) return "";
        } catch (Exception ignored) { }
        String prefix = clean.substring(0, matcher.start()).trim();
        Matcher word = LAST_WORD.matcher(prefix);
        String lastWord = word.find() ? word.group(1).toLowerCase(Locale.ROOT) : "";
        if (lastWord.equals("season") || lastWord.equals("series") || lastWord.equals("cour") || lastWord.equals("seasonal")) return "";
        return candidate;
    }

    private static String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String normalizeNumber(String value) {
        String clean = clean(value).replace(',', '.');
        if (clean.isEmpty()) return "";
        try {
            BigDecimal number = new BigDecimal(clean).stripTrailingZeros();
            return number.toPlainString();
        } catch (Exception ignored) {
            return clean;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("<[^>]+>", " ").replace('&', ' ').trim().replaceAll("\\s+", " ");
        if (clean.equalsIgnoreCase("null") || clean.equals("-") || clean.toLowerCase(Locale.ROOT).contains("belum tersedia")) return "";
        return clean;
    }
}
