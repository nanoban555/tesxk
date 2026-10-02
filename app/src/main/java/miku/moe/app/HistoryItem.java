package miku.moe.app;

public class HistoryItem {
    public int channelId;
    public int categoryId;
    public String categoryName;
    public String title;
    public String imageUrl;
    public String videoUrl;
    public String slug;
    public long position;
    public long duration;
    public long lastWatched;
    public String sourceId;

    public HistoryItem(int channelId, int categoryId, String title, String imageUrl, String videoUrl,
                       long position, long duration, long lastWatched) {
        this(channelId, categoryId, "", title, imageUrl, videoUrl, position, duration, lastWatched, AnimeSettingsManager.SOURCE_DEFAULT);
    }

    public HistoryItem(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl,
                       long position, long duration, long lastWatched) {
        this(channelId, categoryId, categoryName, title, imageUrl, videoUrl, position, duration, lastWatched, AnimeSettingsManager.SOURCE_DEFAULT);
    }

    public HistoryItem(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl,
                       long position, long duration, long lastWatched, String sourceId) {
        this.channelId = channelId;
        this.categoryId = categoryId;
        this.categoryName = categoryName == null ? "" : categoryName;
        this.title = title == null ? "" : title;
        this.imageUrl = imageUrl == null ? "" : imageUrl;
        this.videoUrl = videoUrl == null ? "" : videoUrl;
        this.slug = "";
        this.position = position;
        this.duration = duration;
        this.lastWatched = lastWatched;
        this.sourceId = AnimeSettingsManager.isValidSource(sourceId) ? sourceId : AnimeSettingsManager.SOURCE_DEFAULT;
    }
}
