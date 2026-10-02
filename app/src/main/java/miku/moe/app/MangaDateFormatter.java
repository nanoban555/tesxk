package miku.moe.app;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MangaDateFormatter {
    private static final Locale OUTPUT_LOCALE = new Locale("id", "ID");
    private static final SimpleDateFormat OUTPUT = new SimpleDateFormat("d MMMM yyyy", OUTPUT_LOCALE);
    private static final String MONTH_PATTERN = "January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec|Januari|Februari|Maret|April|Mei|Juni|Juli|Agustus|September|Oktober|November|Desember|Agu|Okt|Des";

    private MangaDateFormatter() {
    }

    public static String format(String raw) {
        if (raw == null) return "";
        String value = clean(raw);
        if (value.isEmpty()) return "";
        String relative = parseRelative(value);
        if (!relative.isEmpty()) return relative;
        String iso = parseIso(value);
        if (!iso.isEmpty()) return iso;
        String monthNameDate = parseMonthNameDate(value);
        if (!monthNameDate.isEmpty()) return monthNameDate;
        String numeric = parseNumericDate(value);
        if (!numeric.isEmpty()) return numeric;
        return trimAfterKnownDate(value);
    }

    private static String clean(String raw) {
        String value = raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        value = value.replaceAll("(?i)^(rilis|release|released|updated|update|uploaded|upload|date|tanggal)\\s*:?\\s*", "").trim();
        value = value.replaceAll("(?i)\\b(on|at)\\b\\s*", "").trim();
        return value;
    }

    private static String parseRelative(String value) {
        String lower = value.toLowerCase(Locale.ROOT).trim();
        if (lower.equals("yesterday") || lower.equals("kemarin")) return "Kemarin";
        if (lower.contains("just now") || lower.contains("baru saja")) return "Baru saja";
        if (lower.contains("hari ini") || lower.equals("today")) return "Hari ini";
        Matcher matcher = Pattern.compile("(?i)\\b(a|an|se|\\d+)\\s*(second|seconds|sec|secs|detik|minute|minutes|min|mins|menit|hour|hours|hr|hrs|jam|day|days|hari|week|weeks|minggu|month|months|mon|mons|mont|bulan|year|years|yr|yrs|tahun)\\b(?:\\s*(ago|yang lalu|lalu))?").matcher(lower);
        if (!matcher.find()) return "";
        int amount = parseAmount(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        return amount + " " + normalizeRelativeUnit(unit) + " yang lalu";
    }

    private static int parseAmount(String value) {
        if (value == null) return 1;
        String lower = value.toLowerCase(Locale.ROOT).trim();
        if (lower.equals("a") || lower.equals("an") || lower.equals("se")) return 1;
        try {
            return Math.max(1, Integer.parseInt(lower));
        } catch(Exception ignored) {
            return 1;
        }
    }

    private static String normalizeRelativeUnit(String unit) {
        if (unit == null) return "hari";
        String lower = unit.toLowerCase(Locale.ROOT).trim();
        if (lower.startsWith("second") || lower.startsWith("sec") || lower.equals("detik")) return "detik";
        if (lower.startsWith("minute") || lower.startsWith("min") || lower.equals("menit")) return "menit";
        if (lower.startsWith("hour") || lower.startsWith("hr") || lower.equals("jam")) return "jam";
        if (lower.startsWith("day") || lower.equals("hari")) return "hari";
        if (lower.startsWith("week") || lower.equals("minggu")) return "minggu";
        if (lower.startsWith("month") || lower.startsWith("mon") || lower.startsWith("mont") || lower.equals("bulan")) return "bulan";
        if (lower.startsWith("year") || lower.startsWith("yr") || lower.equals("tahun")) return "tahun";
        return "hari";
    }

    private static String parseIso(String value) {
        Matcher matcher = Pattern.compile("\\b(\\d{4}-\\d{1,2}-\\d{1,2})(?:[T\\s][0-9:.+-Zz]+)?\\b").matcher(value);
        if (!matcher.find()) return "";
        String candidate = matcher.group(0).replace("Z", "+00:00").replace("z", "+00:00");
        String[] patterns = new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            String formatted = parseWithPattern(candidate, pattern, Locale.ROOT);
            if (!formatted.isEmpty()) return formatted;
        }
        return "";
    }

    private static String parseMonthNameDate(String value) {
        String normalized = normalizeMonthNames(value.replace(',', ' ').replaceAll("\\s+", " ").trim());
        Matcher dayFirst = Pattern.compile("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?[-\\s/]+(" + MONTH_PATTERN + ")[-\\s/,]+(\\d{2,4})\\b").matcher(normalized);
        if (dayFirst.find()) return formatDateParts(dayFirst.group(1), normalizeMonth(dayFirst.group(2)), normalizeYear(dayFirst.group(3)));
        Matcher monthFirst = Pattern.compile("(?i)\\b(" + MONTH_PATTERN + ")[-\\s/]+(\\d{1,2})(?:st|nd|rd|th)?[-\\s/,]+(\\d{2,4})\\b").matcher(normalized);
        if (monthFirst.find()) return formatDateParts(monthFirst.group(2), normalizeMonth(monthFirst.group(1)), normalizeYear(monthFirst.group(3)));
        return "";
    }

    private static String parseNumericDate(String value) {
        Matcher yearFirst = Pattern.compile("\\b(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b").matcher(value);
        if (yearFirst.find()) return formatDateParts(yearFirst.group(3), yearFirst.group(2), yearFirst.group(1));
        Matcher date = Pattern.compile("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b").matcher(value);
        if (!date.find()) return "";
        int first = toInt(date.group(1));
        int second = toInt(date.group(2));
        String year = normalizeYear(date.group(3));
        if (first > 12) return formatDateParts(date.group(1), date.group(2), year);
        if (second > 12) return formatDateParts(date.group(2), date.group(1), year);
        return formatDateParts(date.group(1), date.group(2), year);
    }

    private static String trimAfterKnownDate(String value) {
        Matcher matcher = Pattern.compile("(?i)^.*?((?:\\d{1,2}[-\\s/]+(?:" + MONTH_PATTERN + ")[-\\s/,]+\\d{2,4})|(?:(?:" + MONTH_PATTERN + ")[-\\s/]+\\d{1,2}(?:st|nd|rd|th)?[-\\s/,]+\\d{2,4})|(?:\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4})).*$").matcher(value);
        if (matcher.matches()) {
            String candidate = matcher.group(1);
            if (candidate.equals(value)) return value;
            String formatted = format(candidate);
            if (!formatted.isEmpty() && !formatted.equals(candidate)) return formatted;
        }
        return value.replaceAll("(?i)\\s*(comments?|views?|dibaca|komentar|chapter|chapters?)\\b.*$", "").trim();
    }

    private static String normalizeMonthNames(String value) {
        return value.replaceAll("(?i)\\bJanuari\\b", "January")
                .replaceAll("(?i)\\bFebruari\\b", "February")
                .replaceAll("(?i)\\bMaret\\b", "March")
                .replaceAll("(?i)\\bMei\\b", "May")
                .replaceAll("(?i)\\bJuni\\b", "June")
                .replaceAll("(?i)\\bJuli\\b", "July")
                .replaceAll("(?i)\\bAgustus\\b", "August")
                .replaceAll("(?i)\\bOktober\\b", "October")
                .replaceAll("(?i)\\bAgu\\b", "August")
                .replaceAll("(?i)\\bOkt\\b", "October")
                .replaceAll("(?i)\\bDes\\b", "December")
                .replaceAll("(?i)\\bDesember\\b", "December");
    }

    private static String normalizeMonth(String value) {
        String month = normalizeMonthNames(value).toLowerCase(Locale.ROOT);
        if (month.startsWith("jan")) return "1";
        if (month.startsWith("feb")) return "2";
        if (month.startsWith("mar")) return "3";
        if (month.startsWith("apr")) return "4";
        if (month.startsWith("may")) return "5";
        if (month.startsWith("jun")) return "6";
        if (month.startsWith("jul")) return "7";
        if (month.startsWith("aug")) return "8";
        if (month.startsWith("sep")) return "9";
        if (month.startsWith("oct")) return "10";
        if (month.startsWith("nov")) return "11";
        if (month.startsWith("dec")) return "12";
        return value;
    }

    private static String normalizeYear(String value) {
        if (value == null) return "";
        String year = value.trim();
        if (year.length() == 2) {
            int y = toInt(year);
            return String.valueOf(y >= 70 ? 1900 + y : 2000 + y);
        }
        return year;
    }

    private static String formatDateParts(String day, String month, String year) {
        int d = toInt(day);
        int m = toInt(month);
        int y = toInt(year);
        if (d < 1 || d > 31 || m < 1 || m > 12 || y < 1900 || y > 2100) return "";
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT);
        calendar.setLenient(false);
        calendar.clear();
        calendar.set(Calendar.YEAR, y);
        calendar.set(Calendar.MONTH, m - 1);
        calendar.set(Calendar.DAY_OF_MONTH, d);
        try {
            return format(calendar.getTime());
        } catch(Exception ignored) {
            return "";
        }
    }

    private static String parseWithPattern(String value, String pattern, Locale locale) {
        try {
            SimpleDateFormat input = new SimpleDateFormat(pattern, locale);
            input.setLenient(false);
            input.setTimeZone(TimeZone.getDefault());
            ParsePosition position = new ParsePosition(0);
            Date date = input.parse(value, position);
            if (date != null && position.getIndex() > 0) return format(date);
        } catch(Exception ignored) {
        }
        return "";
    }

    private static String format(Date date) {
        synchronized (OUTPUT) {
            return OUTPUT.format(date);
        }
    }

    private static int toInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch(Exception ignored) {
            return -1;
        }
    }
}
