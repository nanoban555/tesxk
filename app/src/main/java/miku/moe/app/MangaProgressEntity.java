package miku.moe.app;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "manga_progress")
public class MangaProgressEntity {
    @PrimaryKey
    @NonNull
    public String key;
    public String mangaKey;
    public String sourceId;
    public String slug;
    public float chapterIndex;
    public int page;
    public int totalPages;
    public long updatedAt;

    public MangaProgressEntity() {
        key = "";
        mangaKey = "";
        sourceId = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
        slug = "";
    }
}
