package miku.moe.app;

public class AnimePost {
    public String sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
    public String imgUrl;
    public String categoryName;
    public String channelName = "";
    public int categoryId;
    public int channelId;
    public String genre = "";
    public String rating = "";
    public int year = 0;
    public int scheduleDay = -1;
    public String episodeCount = "";
    public String created = "";
    public String countView = "";
    public boolean ongoing = false;
    public String statusVideo = "";
    public boolean hdAvailable = false;
    public boolean fhdAvailable = false;
    public String description = "";
    public String slug = "";

    public AnimePost(String imgUrl, String categoryName, int categoryId) {
        this(imgUrl, categoryName, categoryId, -1);
    }

    public AnimePost(String imgUrl, String categoryName, int categoryId, int channelId) {
        this.imgUrl = imgUrl;
        this.categoryName = categoryName;
        this.categoryId = categoryId;
        this.channelId = channelId;
    }
}
