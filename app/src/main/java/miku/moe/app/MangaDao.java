package miku.moe.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao
public interface MangaDao {
    @Query("SELECT * FROM manga_favorites ORDER BY position ASC, savedAt DESC")
    List<MangaFavoriteEntity> getFavorites();

    @Query("SELECT * FROM manga_favorites WHERE `key` = :key LIMIT 1")
    MangaFavoriteEntity getFavorite(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFavorites(List<MangaFavoriteEntity> items);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFavorite(MangaFavoriteEntity item);

    @Query("DELETE FROM manga_favorites WHERE `key` = :key")
    void deleteFavorite(String key);

    @Query("DELETE FROM manga_favorites")
    void clearFavorites();

    @Transaction
    default void replaceFavorites(List<MangaFavoriteEntity> items) {
        clearFavorites();
        if (items != null && !items.isEmpty()) upsertFavorites(items);
    }

    @Query("SELECT * FROM manga_history ORDER BY position ASC, time DESC")
    List<MangaHistoryEntity> getHistory();

    @Query("SELECT * FROM manga_history WHERE `key` = :key LIMIT 1")
    MangaHistoryEntity getHistoryItem(String key);

    @Query("SELECT `key` FROM manga_history ORDER BY time DESC LIMIT -1 OFFSET :maxItems")
    List<String> getHistoryKeysAfter(int maxItems);

    @Query("DELETE FROM manga_history WHERE `key` IN (:keys)")
    void deleteHistoryKeys(List<String> keys);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertHistory(List<MangaHistoryEntity> items);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertHistoryItem(MangaHistoryEntity item);

    @Query("DELETE FROM manga_history")
    void clearHistory();

    @Transaction
    default void replaceHistory(List<MangaHistoryEntity> items) {
        clearHistory();
        if (items != null && !items.isEmpty()) upsertHistory(items);
    }

    @Query("SELECT * FROM manga_progress WHERE `key` = :key LIMIT 1")
    MangaProgressEntity getProgress(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertProgress(MangaProgressEntity item);

    @Query("SELECT * FROM manga_progress ORDER BY updatedAt ASC")
    List<MangaProgressEntity> getAllProgress();

    @Query("DELETE FROM manga_progress WHERE `key` = :key")
    void deleteProgress(String key);

    @Query("DELETE FROM manga_progress WHERE substr(`key`, 1, length(:prefix)) = :prefix")
    void deleteProgressByPrefix(String prefix);

    @Query("DELETE FROM manga_progress")
    void clearProgress();
}
