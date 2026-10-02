package miku.moe.app;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "manga_history")
public class MangaHistoryEntity {
    @PrimaryKey
    @NonNull
    public String key;
    public String dayKey;
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
    public String latestChapter;
    public String latestChapterDate;
    public int totalChapters;
    public float chapterIndex;
    public String chapterTitle;
    public String chapterSlug;
    public String chapterId;
    public int page;
    public int totalPages;
    public long time;
    public int position;

    public MangaHistoryEntity() {
        key = "";
        dayKey = "";
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
        latestChapter = "";
        latestChapterDate = "";
        chapterTitle = "";
        chapterSlug = "";
        chapterId = "";
    }
}
