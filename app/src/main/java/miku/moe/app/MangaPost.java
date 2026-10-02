package miku.moe.app;

public class MangaPost implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public String slug;
    public String title;
    public String coverImage;
    public String author;
    public String status;
    public String synopsis;
    public String genre;
    public String typeLabel;
    public String latestChapter;
    public String latestChapterDate;
    public String sourceId;
    public String sourceLabel;
    public String info;
    public float historyChapterIndex = -1f;
    public long historyLastRead = 0L;
    public int historyPage = 0;
    public int historyTotalPages = 0;
    public int totalChapters = 0;
    public transient int favoriteChapterBase = 0;
    public transient int favoriteChapterAdded = 0;

    public MangaPost(String slug, String title, String coverImage, String author, String status, String synopsis, String genre) {
        this(slug, title, coverImage, author, status, synopsis, genre, "", "", "");
    }

    public MangaPost(String slug, String title, String coverImage, String author, String status, String synopsis, String genre, String typeLabel) {
        this(slug, title, coverImage, author, status, synopsis, genre, typeLabel, "", "");
    }

    public MangaPost(String slug, String title, String coverImage, String author, String status, String synopsis, String genre, String typeLabel, String latestChapter, String latestChapterDate) {
        this.slug = slug == null ? "" : slug;
        this.title = title == null ? "" : title;
        this.coverImage = coverImage == null ? "" : coverImage;
        this.author = author == null ? "" : author;
        this.status = normalizeStatus(status);
        this.synopsis = synopsis == null ? "" : synopsis;
        this.genre = genre == null ? "" : genre;
        this.typeLabel = normalizeType(typeLabel, this.genre, this.status);
        this.latestChapter = latestChapter == null ? "" : latestChapter;
        this.latestChapterDate = latestChapterDate == null ? "" : latestChapterDate;
        this.sourceId = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
        this.sourceLabel = "VoraToon";
        this.info = "";
    }

    public String getTypeLabel() { return normalizeType(typeLabel, (genre == null ? "" : genre) + " " + (info == null ? "" : info) + " " + (synopsis == null ? "" : synopsis), status); }

    public MangaPost withSource(String sourceId, String sourceLabel) {
        String rawSourceId = sourceId == null ? "" : sourceId.trim();
        String normalizedSourceId = rawSourceId;
        boolean normalized = !rawSourceId.equals(normalizedSourceId);
        if (!MangaSettingsManager.isValidSource(normalizedSourceId)) {
            normalizedSourceId = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
            normalized = true;
        }
        this.sourceId = normalizedSourceId;
        String defaultLabel = MangaSourceFactory.labelForSourceId(normalizedSourceId);
        this.sourceLabel = normalized || sourceLabel == null || sourceLabel.trim().isEmpty() ? defaultLabel : sourceLabel.trim();
        return this;
    }

    public String getSourceId() {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_SHINIGAMI;
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_DOUJINDESU;
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_WESTMANGA;
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_BACAKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_KOMIKINDO;
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_IKIRU;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_KOMIKU;
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_MANGASUSU;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG;
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_COSMICSCANS;
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL;
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_NATSU;
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_AINZSCANSS;
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_APKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_COMICASO;
        if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_KUMOPOI;
        if (MangaSettingsManager.MANGA_SOURCE_MGKOMIK.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_MGKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_KOMIKTAP;
        if (MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_MANHWAINDO;
        if (MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_SOULSCANS;
        if (MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_KUROMANGA;
        if (MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_LUVYAA;
        if (MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN;
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_CROTPEDIA;
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) return MangaSettingsManager.MANGA_SOURCE_NGOMIK;
        return MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
    }

    public String getSourceLabel() {
        String normalizedSourceId = getSourceId();
        String rawSourceId = sourceId == null ? "" : sourceId.trim();
        if (!rawSourceId.equals(normalizedSourceId)) return MangaSourceFactory.labelForSourceId(normalizedSourceId);
        String label = sourceLabel == null ? "" : sourceLabel.trim();
        if (!label.isEmpty()) return label;
        return MangaSourceFactory.labelForSourceId(normalizedSourceId);
    }

    public static String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String normalizeType(String raw, String genre, String status) {
        String text = ((raw == null ? "" : raw) + " " + (genre == null ? "" : genre) + " " + (status == null ? "" : status)).toLowerCase();
        if (text.contains("manhwa") || text.contains("korea") || text.contains("korean") || text.contains("south korea")) return "MANHWA";
        if (text.contains("manhua") || text.contains("china") || text.contains("chinese") || text.contains("cina") || text.contains("tiongkok")) return "MANHUA";
        if (text.contains("webtoon") || text.contains("web toon")) return "WEBTOON";
        if (text.contains("image-set") || text.contains("image set") || text.contains("imageset")) return "IMAGE-SET";
        if (text.contains("novel")) return "NOVEL";
        if (text.contains("comic")) return "COMIC";
        if (text.contains("one-shot") || text.contains("oneshot") || text.contains("one shot")) return "ONESHOT";
        if (text.contains("doujinshi")) return "DOUJINSHI";
        if (text.contains("doujin")) return "DOUJIN";
        if (text.contains("manga") || text.contains("japan") || text.contains("japanese")) return "MANGA";
        if (raw != null && !raw.trim().isEmpty()) return raw.trim().toUpperCase();
        return "";
    }
}
