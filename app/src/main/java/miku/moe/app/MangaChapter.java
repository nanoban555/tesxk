package miku.moe.app;

public class MangaChapter implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public String slug;
    public float index;
    public String title;
    public String date;
    public String chapterId;
    public MangaChapter(String slug, float index, String title, String date) {
        this.slug = slug;
        this.index = index;
        String cleanedTitle = cleanSuffix(title, index);
        this.title = cleanedTitle.isEmpty() ? "Chapter " + formatIndex(index) : "Chapter " + formatIndex(index) + " " + cleanedTitle;
        this.date = MangaDateFormatter.format(date);
        this.chapterId = "";
    }
    private static String cleanSuffix(String raw, float index) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        value = value.replaceAll("(?i)^chapter\\s*", "").trim();
        value = value.replaceAll("^:\\s*", "").trim();
        String idx = formatIndex(index);
        if (value.equals(idx) || sameChapterNumber(value, idx)) return "";
        if (value.matches("(?i)^" + java.util.regex.Pattern.quote(idx) + "\\s*:\\s*" + java.util.regex.Pattern.quote(idx) + "$")) return "";
        if (value.matches("(?i)^" + java.util.regex.Pattern.quote(idx) + "\\s*:\\s*end$")) return "End";
        if (value.equalsIgnoreCase("end")) return "End";
        return value;
    }
    private static boolean sameChapterNumber(String a, String b) {
        if (a == null || b == null) return false;
        try {
            float fa = Float.parseFloat(a.replace(",", ".").trim());
            float fb = Float.parseFloat(b.replace(",", ".").trim());
            return Math.abs(fa - fb) < 0.0001f;
        } catch(Exception ignored) {
            return a.trim().equalsIgnoreCase(b.trim());
        }
    }

    public static String formatIndex(float value) {
        if (value == (long) value) return String.valueOf((long) value);
        return String.valueOf(value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
