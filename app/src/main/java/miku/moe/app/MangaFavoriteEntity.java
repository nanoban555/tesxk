package miku.moe.app;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "manga_favorites")
public class MangaFavoriteEntity {
    @PrimaryKey
    @NonNull
    public String key;
    public String slug;
    public String sourceId;
    public String sourceLabel;
    public String title;
    public String cover;
    public String author;
    public String status;
    public String synopsis;
    public String genre;
    public String typeLabel;
    public String info;
    public String latestChapter;
    public String latestChapterDate;
    public int totalChapters;
    public long savedAt;
    public int position;

    public MangaFavoriteEntity() {
        key = "";
        slug = "";
        sourceId = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
        sourceLabel = "";
        title = "";
        cover = "";
        author = "";
        status = "";
        synopsis = "";
        genre = "";
        typeLabel = "";
        info = "";
        latestChapter = "";
        latestChapterDate = "";
    }
}
