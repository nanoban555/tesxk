package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Calendar;
import java.util.List;

public final class MangaHistoryManager {
    public static class Progress { 
        public final int page; 
        public final int totalPages; 
        public Progress(int page, int totalPages){ this.page=page; this.totalPages=totalPages; } 
    }

    public static class Entry {
        public final MangaPost manga; 
        public final String chapterTitle; 
        public final float chapterIndex; 
        public final int page; 
        public final int totalPages; 
        public final long time;
        public final String key;
        Entry(MangaPost manga, String chapterTitle, float chapterIndex, int page, int totalPages, long time, String key) { 
            this.manga = manga; 
            this.chapterTitle = chapterTitle; 
            this.chapterIndex = chapterIndex; 
            this.page = page; 
            this.totalPages = totalPages; 
            this.time = time; 
            this.key = key;
        }
    }

    private static final String PREFS = "miku_manga_history"; 
    private static final String KEY_ITEMS = "items"; 
    private static final String KEY_PROGRESS = "chapter_progress";
    private static final String MIGRATION_PREFS = "miku_manga_room_migration";
    private static final String KEY_MIGRATED_HISTORY = "history_migrated";
    private static final int MAX_PROGRESS_ITEMS = 700;
    private static final long ENTRIES_CACHE_MS = 450L;
    private static final ArrayList<Entry> ENTRIES_CACHE = new ArrayList<>();
    private static long entriesCacheAt = 0L;
    private static boolean entriesCacheValid = false;

    private MangaHistoryManager() {}

    public static void save(Context c, MangaPost manga, MangaChapter chapter, int page, int totalPages) {
        save(c, manga, chapter, page, totalPages, System.currentTimeMillis());
    }

    public static void save(Context c, MangaPost manga, MangaChapter chapter, int page, int totalPages, long readAt) {
        try {
            if (c == null || manga == null || chapter == null || manga.slug == null || manga.slug.trim().isEmpty()) return;
            
            ArrayList<JSONObject> all = raw(c);
            String mangaSlug = manga.slug.trim();
            String sourceId = manga.getSourceId();
            String sourceLabel = manga.getSourceLabel();
            long now = readAt > 0L ? readAt : System.currentTimeMillis();
            String dayKey = makeDayKey(now);
            String progressKey = sourceId + ":" + mangaSlug;
            String historyKey = progressKey + ":" + dayKey;
            float currentChapterIndex = chapter.index;
            int safeTotal = Math.max(1, totalPages);
            int safePage = Math.max(0, Math.min(page, safeTotal - 1));

            saveChapterProgress(c, progressKey, currentChapterIndex, safePage, safeTotal, now, true);

            JSONObject previous = null;
            for (int i = all.size() - 1; i >= 0; i--) {
                JSONObject old = all.get(i);
                String oldSource = old.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                String oldDayKey = old.optString("dayKey", makeDayKey(old.optLong("time", 0L)));
                if (mangaSlug.equals(old.optString("slug")) && sourceId.equals(oldSource)) {
                    if (previous == null) previous = old;
                    if (dayKey.equals(oldDayKey)) {
                        if (old.optLong("time", 0L) > now) return;
                        all.remove(i);
                    }
                }
            }

            JSONObject o = new JSONObject();
            o.put("key", historyKey);
            o.put("dayKey", dayKey);
            o.put("slug", mangaSlug);
            o.put("sourceId", sourceId);
            o.put("sourceLabel", valueOrPrevious(previous, "sourceLabel", sourceLabel));
            o.put("title", valueOrPrevious(previous, "title", manga.title));
            o.put("cover", valueOrPrevious(previous, "cover", manga.coverImage));
            o.put("author", valueOrPrevious(previous, "author", manga.author));
            o.put("status", valueOrPrevious(previous, "status", manga.status));
            o.put("synopsis", valueOrPrevious(previous, "synopsis", manga.synopsis));
            o.put("genre", valueOrPrevious(previous, "genre", manga.genre));
            o.put("typeLabel", valueOrPrevious(previous, "typeLabel", MangaLabelUtils.typeLabel(manga)));
            o.put("latestChapter", valueOrPrevious(previous, "latestChapter", manga.latestChapter));
            o.put("latestChapterDate", valueOrPrevious(previous, "latestChapterDate", manga.latestChapterDate));
            o.put("totalChapters", Math.max(Math.max(manga.totalChapters, 0), previous == null ? 0 : previous.optInt("totalChapters", 0)));
            o.put("chapterIndex", currentChapterIndex);
            o.put("chapterTitle", valueOrPrevious(previous, "chapterTitle", chapter.title));
            o.put("chapterSlug", valueOrPrevious(previous, "chapterSlug", chapter.slug));
            o.put("chapterId", valueOrPrevious(previous, "chapterId", chapter.chapterId));
            o.put("page", safePage);
            o.put("totalPages", safeTotal);
            o.put("time", now);

            all.add(0, o); 
            persist(c, all);
            saveCoverIfEnabled(c, o.optString("cover", manga.coverImage), sourceId);
        } catch (Exception ignored) {}
    }

    public static void saveReaderProgress(Context c, MangaPost manga, MangaChapter chapter, int page, int totalPages) {
        saveReaderProgress(c, manga, chapter, page, totalPages, System.currentTimeMillis());
    }

    public static void saveReaderProgress(Context c, MangaPost manga, MangaChapter chapter, int page, int totalPages, long readAt) {
        try {
            if (c == null || manga == null || chapter == null || manga.slug == null || manga.slug.trim().isEmpty()) return;
            String mangaSlug = manga.slug.trim();
            String sourceId = manga.getSourceId();
            String mangaKey = sourceId + ":" + mangaSlug;
            int safeTotal = Math.max(1, totalPages);
            int safePage = Math.max(0, Math.min(page, safeTotal - 1));
            saveChapterProgress(c, mangaKey, chapter.index, safePage, safeTotal, readAt, false);
            updateHistoryProgress(c, manga, chapter, safePage, safeTotal, readAt);
            MangaRoomEvents.notifyChanged();
        } catch (Exception ignored) {}
    }

    private static void updateHistoryProgress(Context c, MangaPost manga, MangaChapter chapter, int page, int totalPages, long readAt) {
        if (c == null || manga == null || chapter == null || manga.slug == null || manga.slug.trim().isEmpty()) return;
        try {
            ensureMigrated(c);
            String slug = manga.slug.trim();
            String sourceId = manga.getSourceId();
            long time = readAt > 0L ? readAt : System.currentTimeMillis();
            String dayKey = makeDayKey(time);
            String key = sourceId + ":" + slug + ":" + dayKey;
            MangaHistoryEntity row = dao(c).getHistoryItem(key);
            if (row == null) {
                save(c, manga, chapter, page, totalPages, time);
                return;
            }
            if (row.time > time) return;
            row.slug = slug;
            row.sourceId = sourceId;
            row.sourceLabel = valueOrPrevious(row.sourceLabel, manga.getSourceLabel());
            row.title = valueOrPrevious(row.title, manga.title);
            row.cover = valueOrPrevious(row.cover, manga.coverImage);
            row.author = valueOrPrevious(row.author, manga.author);
            row.status = valueOrPrevious(row.status, manga.status);
            row.synopsis = valueOrPrevious(row.synopsis, manga.synopsis);
            row.genre = valueOrPrevious(row.genre, manga.genre);
            row.typeLabel = valueOrPrevious(row.typeLabel, MangaLabelUtils.typeLabel(manga));
            row.latestChapter = valueOrPrevious(row.latestChapter, manga.latestChapter);
            row.latestChapterDate = valueOrPrevious(row.latestChapterDate, manga.latestChapterDate);
            row.totalChapters = Math.max(row.totalChapters, Math.max(0, manga.totalChapters));
            row.chapterIndex = chapter.index;
            row.chapterTitle = valueOrPrevious(row.chapterTitle, chapter.title);
            row.chapterSlug = valueOrPrevious(row.chapterSlug, chapter.slug);
            row.chapterId = valueOrPrevious(row.chapterId, chapter.chapterId);
            row.page = Math.max(0, Math.min(page, Math.max(1, totalPages) - 1));
            row.totalPages = Math.max(1, totalPages);
            row.time = time;
            dao(c).upsertHistoryItem(row);
            invalidateEntriesCache();
            MangaRoomEvents.notifyChanged();
        } catch (Exception ignored) {}
    }

    public static int getPage(Context c, String slug, float chapterIndex) { 
        Progress p = getProgress(c, slug, chapterIndex); 
        return p == null ? 0 : p.page; 
    }

    public static int getPage(Context c, MangaPost manga, float chapterIndex) {
        if (manga == null) return getPage(c, (String)null, chapterIndex);
        Progress p = getSavedChapterProgress(c, manga.getSourceId() + ":" + manga.slug, chapterIndex);
        if (p != null) return p.page;
        p = getProgress(c, manga.slug, chapterIndex);
        return p == null ? 0 : p.page;
    }

    public static int getTotalPages(Context c, MangaPost manga, float chapterIndex) {
        Progress p = getProgress(c, manga, chapterIndex);
        return p == null ? 0 : Math.max(1, p.totalPages);
    }

    public static Progress getProgress(Context c, String slug, float chapterIndex) { 
        if (slug == null) return null;
        String normalizedSlug = slug.trim();
        if (normalizedSlug.isEmpty()) return null;
        String currentSource = MangaSettingsManager.getMangaSource(c);
        Progress saved = getSavedChapterProgress(c, currentSource + ":" + normalizedSlug, chapterIndex);
        if (saved != null) return saved;
        for (JSONObject o : raw(c)) {
            String source = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            if (!currentSource.equals(source)) continue;
            if (normalizedSlug.equals(o.optString("slug")) && Math.abs((float)o.optDouble("chapterIndex", -1d) - chapterIndex) < 0.001f) {
                return new Progress(Math.max(0, o.optInt("page", 0)), Math.max(1, o.optInt("totalPages", 1)));
            }
        }
        return null; 
    }

    public static Progress getProgress(Context c, MangaPost manga, float chapterIndex) {
        if (manga == null) return null;
        String slug = manga.slug == null ? "" : manga.slug.trim();
        if (slug.isEmpty()) return null;
        String sourceId = manga.getSourceId();
        Progress saved = getSavedChapterProgress(c, sourceId + ":" + slug, chapterIndex);
        if (saved != null) return saved;
        if (!hasHistoryFor(c, sourceId, slug)) {
            removeProgressForManga(c, sourceId, slug);
            return null;
        }
        for (JSONObject o : raw(c)) {
            String source = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            if (slug.equals(o.optString("slug")) && sourceId.equals(source) && Math.abs((float)o.optDouble("chapterIndex", -1d) - chapterIndex) < 0.001f) {
                return new Progress(Math.max(0, o.optInt("page", 0)), Math.max(1, o.optInt("totalPages", 1)));
            }
        }
        return null;
    }

    public static ArrayList<Entry> entries(Context c) { 
        long now = SystemClock.elapsedRealtime();
        synchronized (ENTRIES_CACHE) {
            if (entriesCacheValid && !ENTRIES_CACHE.isEmpty() && now - entriesCacheAt < ENTRIES_CACHE_MS) return new ArrayList<>(ENTRIES_CACHE);
        }
        ArrayList<Entry> out = new ArrayList<>(); 
        HashSet<String> shownKeys = new HashSet<>();
        for (JSONObject o : raw(c)) { 
            String slug = o.optString("slug");
            String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            String dayKey = o.optString("dayKey", makeDayKey(o.optLong("time", 0L)));
            String uniqueKey = o.optString("key", sourceId + ":" + slug + ":" + dayKey);
            if (slug == null || slug.trim().isEmpty() || shownKeys.contains(uniqueKey)) continue;
            shownKeys.add(uniqueKey);
            MangaPost p = new MangaPost(
                o.optString("slug"), 
                o.optString("title"), 
                o.optString("cover"), 
                o.optString("author"), 
                o.optString("status"), 
                o.optString("synopsis"), 
                o.optString("genre"), 
                o.optString("typeLabel"), 
                o.optString("latestChapter"), 
                o.optString("latestChapterDate")
            ).withSource(sourceId, o.optString("sourceLabel", MangaSourceFactory.labelForSourceId(sourceId))); 
            p.totalChapters = o.optInt("totalChapters", 0); 
            p.historyChapterIndex = (float)o.optDouble("chapterIndex", -1d); 
            p.historyPage = Math.max(0, o.optInt("page", 0)); 
            p.historyTotalPages = Math.max(1, o.optInt("totalPages", 1)); 
            p.historyLastRead = o.optLong("time", 0L); 
            out.add(new Entry(p, o.optString("chapterTitle"), p.historyChapterIndex, p.historyPage, p.historyTotalPages, p.historyLastRead, uniqueKey)); 
        } 
        synchronized (ENTRIES_CACHE) {
            ENTRIES_CACHE.clear();
            ENTRIES_CACHE.addAll(out);
            entriesCacheAt = SystemClock.elapsedRealtime();
            entriesCacheValid = true;
        }
        return out; 
    }

    public static void invalidateEntriesCache() {
        synchronized (ENTRIES_CACHE) {
            ENTRIES_CACHE.clear();
            entriesCacheValid = false;
        }
    }

    public static float getLastReadChapterIndex(Context c, MangaPost manga) {
        if (c == null || manga == null || manga.slug == null) return -1f;
        String slug = manga.slug.trim();
        String sourceId = manga.getSourceId();
        if (slug.isEmpty()) return -1f;
        float fallback = -1f;
        for (Entry e : entries(c)) {
            if (e == null || e.manga == null || !slug.equals(e.manga.slug)) continue;
            if (sourceId.equals(e.manga.getSourceId())) return e.chapterIndex;
            if (fallback < 0f) fallback = e.chapterIndex;
        }
        return fallback;
    }

    public static void cleanOrphanProgress(Context c, MangaPost manga) {
        if (c == null || manga == null) return;
        String slug = manga.slug == null ? "" : manga.slug.trim();
        if (slug.isEmpty()) return;
        String sourceId = manga.getSourceId();
        if (!hasHistoryFor(c, sourceId, slug)) removeProgressForManga(c, sourceId, slug);
    }

    public static boolean hasHistoryFor(Context c, MangaPost manga) {
        if (c == null || manga == null) return false;
        String slug = manga.slug == null ? "" : manga.slug.trim();
        if (slug.isEmpty()) return false;
        return hasHistoryFor(c, manga.getSourceId(), slug);
    }

    private static boolean hasHistoryFor(Context c, String sourceId, String slug) {
        if (c == null || sourceId == null || slug == null || slug.trim().isEmpty()) return false;
        String normalizedSlug = slug.trim();
        for (JSONObject o : raw(c)) {
            String itemSource = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            if (normalizedSlug.equals(o.optString("slug")) && sourceId.equals(itemSource)) return true;
        }
        return false;
    }

    private static void removeProgressForManga(Context c, String sourceId, String slug) {
        if (c == null || sourceId == null || slug == null || slug.trim().isEmpty()) return;
        try {
            String prefix = sourceId + ":" + slug.trim() + "_";
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            Iterator<String> iterator = root.keys();
            ArrayList<String> toRemove = new ArrayList<>();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (key.startsWith(prefix)) toRemove.add(key);
            }
            dao(c).deleteProgressByPrefix(prefix);
            if (toRemove.isEmpty()) return;
            for (String key : toRemove) {
                root.remove(key);
                dao(c).deleteProgress(key);
            }
            p(c).edit().putString(KEY_PROGRESS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static ArrayList<MangaPost> asPosts(Context c) { 
        ArrayList<MangaPost> out = new ArrayList<>(); 
        for (Entry e : entries(c)) { 
            e.manga.latestChapter = e.chapterTitle; 
            e.manga.latestChapterDate = "Hal. " + (e.page + 1) + "/" + Math.max(1, e.totalPages); 
            out.add(e.manga); 
        } 
        return out; 
    }

    public static void clear(Context c) { 
        if (c == null) return;
        try {
            dao(c).clearHistory();
            dao(c).clearProgress();
            invalidateEntriesCache();
            p(c).edit().putString(KEY_ITEMS, "[]").remove(KEY_PROGRESS).apply();
            MangaRoomEvents.notifyChanged();
        } catch (Exception ignored) {}
    }

    public static void delete(Context c, java.util.HashSet<String> keys) {
        if (c == null || keys == null || keys.isEmpty()) return;
        ArrayList<JSONObject> all = raw(c);
        HashSet<String> targetMangaKeys = new HashSet<>();
        for (JSONObject o : all) {
            if (o == null) continue;
            String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            String slug = o.optString("slug", "").trim();
            if (slug.isEmpty()) continue;
            String dayKey = o.optString("dayKey", makeDayKey(o.optLong("time", 0L)));
            String mangaKey = sourceId + ":" + slug;
            String itemKey = o.optString("key", mangaKey + ":" + dayKey);
            if (keys.contains(mangaKey) || keys.contains(itemKey)) targetMangaKeys.add(mangaKey);
        }
        if (targetMangaKeys.isEmpty()) return;
        HashSet<String> removedProgressKeys = new HashSet<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            JSONObject o = all.get(i);
            if (o == null) continue;
            String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            String slug = o.optString("slug", "").trim();
            String mangaKey = sourceId + ":" + slug;
            if (targetMangaKeys.contains(mangaKey)) {
                removedProgressKeys.add(mangaKey + "_" + (float)o.optDouble("chapterIndex", -1d));
                all.remove(i);
            }
        }
        removeDeletedProgress(c, all, targetMangaKeys, removedProgressKeys);
        persist(c, all);
    }

    private static void removeDeletedProgress(Context c, ArrayList<JSONObject> remainingHistory, HashSet<String> changedMangaKeys, HashSet<String> removedProgressKeys) {
        if (c == null || changedMangaKeys == null || changedMangaKeys.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            HashSet<String> keepProgressKeys = new HashSet<>();
            for (JSONObject o : remainingHistory) {
                String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                String slug = o.optString("slug");
                String mangaKey = sourceId + ":" + slug;
                if (changedMangaKeys.contains(mangaKey)) keepProgressKeys.add(mangaKey + "_" + (float)o.optDouble("chapterIndex", -1d));
            }
            for (String mangaKey : changedMangaKeys) {
                boolean hasRemainingProgress = false;
                for (String keepKey : keepProgressKeys) {
                    if (keepKey != null && keepKey.startsWith(mangaKey + "_")) {
                        hasRemainingProgress = true;
                        break;
                    }
                }
                if (!hasRemainingProgress) dao(c).deleteProgressByPrefix(mangaKey + "_");
            }
            Iterator<String> iterator = root.keys();
            ArrayList<String> toRemove = new ArrayList<>();
            while (iterator.hasNext()) {
                String key = iterator.next();
                for (String mangaKey : changedMangaKeys) {
                    if (key.startsWith(mangaKey + "_") && (removedProgressKeys.contains(key) || !keepProgressKeys.contains(key))) {
                        toRemove.add(key);
                        break;
                    }
                }
            }
            for (String key : toRemove) {
                root.remove(key);
                dao(c).deleteProgress(key);
            }
            p(c).edit().putString(KEY_PROGRESS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static int count(Context c) { 
        return entries(c).size(); 
    }

    public static void updateEntry(Context c, Entry entry, MangaPost freshManga, MangaChapter freshChapter, int totalChapters) {
        if (c == null || entry == null || entry.manga == null) return;
        try {
            String oldSlug = entry.manga.slug == null ? "" : entry.manga.slug.trim();
            String sourceId = entry.manga.getSourceId();
            String entryKey = entry.key == null ? "" : entry.key.trim();
            if (oldSlug.isEmpty()) return;
            ArrayList<JSONObject> all = raw(c);
            for (JSONObject o : all) {
                String itemSource = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                String itemSlug = o.optString("slug");
                String itemDayKey = o.optString("dayKey", makeDayKey(o.optLong("time", 0L)));
                String itemKey = o.optString("key", itemSource + ":" + itemSlug + ":" + itemDayKey);
                float itemChapterIndex = (float)o.optDouble("chapterIndex", -1d);
                if (!entryKey.isEmpty()) {
                    if (!entryKey.equals(itemKey)) continue;
                } else if (!sourceId.equals(itemSource) || !oldSlug.equals(itemSlug) || Math.abs(itemChapterIndex - entry.chapterIndex) >= 0.001f) {
                    continue;
                }
                if (freshManga != null) {
                    putIfNotEmpty(o, "slug", freshManga.slug);
                    putIfNotEmpty(o, "title", freshManga.title);
                    putIfNotEmpty(o, "cover", freshManga.coverImage);
                    putIfNotEmpty(o, "author", freshManga.author);
                    putIfNotEmpty(o, "status", freshManga.status);
                    putIfNotEmpty(o, "synopsis", freshManga.synopsis);
                    putIfNotEmpty(o, "genre", freshManga.genre);
                    putIfNotEmpty(o, "typeLabel", MangaLabelUtils.typeLabel(freshManga));
                    putIfNotEmpty(o, "latestChapter", freshManga.latestChapter);
                    putIfNotEmpty(o, "latestChapterDate", freshManga.latestChapterDate);
                    o.put("sourceId", freshManga.getSourceId());
                    o.put("sourceLabel", freshManga.getSourceLabel());
                }
                if (freshChapter != null) {
                    putIfNotEmpty(o, "chapterTitle", freshChapter.title);
                    putIfNotEmpty(o, "chapterSlug", freshChapter.slug);
                    putIfNotEmpty(o, "chapterId", freshChapter.chapterId);
                    o.put("chapterIndex", freshChapter.index);
                }
                if (totalChapters > 0) o.put("totalChapters", totalChapters);
                break;
            }
            persist(c, all);
            MangaPost coverManga = freshManga == null ? entry.manga : freshManga;
            saveCoverIfEnabled(c, coverManga.coverImage, coverManga.getSourceId());
        } catch (Exception ignored) {}
    }

    private static void saveCoverIfEnabled(Context c, String coverImage, String sourceId) {
        if (c == null || coverImage == null || coverImage.trim().isEmpty()) return;
        if (!MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(c)) return;
        MangaCoverCache.saveAsync(c.getApplicationContext(), coverImage, sourceId);
    }

    private static String valueOrPrevious(JSONObject previous, String key, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) return text;
        if (previous == null) return "";
        return previous.optString(key, "");
    }

    private static void putIfNotEmpty(JSONObject o, String key, String value) {
        try {
            if (o != null && value != null && !value.trim().isEmpty()) o.put(key, value.trim());
        } catch (Exception ignored) {}
    }

    private static void saveChapterProgress(Context c, String slug, float chapterIndex, int page, int totalPages) {
        saveChapterProgress(c, slug, chapterIndex, page, totalPages, System.currentTimeMillis(), true);
    }

    private static void saveChapterProgress(Context c, String slug, float chapterIndex, int page, int totalPages, boolean saveLegacyProgress) {
        saveChapterProgress(c, slug, chapterIndex, page, totalPages, System.currentTimeMillis(), saveLegacyProgress);
    }

    private static void saveChapterProgress(Context c, String slug, float chapterIndex, int page, int totalPages, long readAt, boolean saveLegacyProgress) {
        if (c == null || slug == null) return;
        try {
            ensureMigrated(c);
            int safeTotal = Math.max(1, totalPages);
            String mangaKey = slug.trim();
            MangaProgressEntity row = new MangaProgressEntity();
            row.key = progressKey(mangaKey, chapterIndex);
            row.mangaKey = mangaKey;
            int split = mangaKey.indexOf(':');
            row.sourceId = split > 0 ? mangaKey.substring(0, split) : MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
            row.slug = split > 0 && split + 1 < mangaKey.length() ? mangaKey.substring(split + 1) : mangaKey;
            row.chapterIndex = chapterIndex;
            row.page = Math.max(0, Math.min(page, safeTotal - 1));
            row.totalPages = safeTotal;
            row.updatedAt = readAt > 0L ? readAt : System.currentTimeMillis();
            synchronized (MangaHistoryManager.class) {
                MangaDao mangaDao = dao(c);
                MangaProgressEntity existing = mangaDao.getProgress(row.key);
                if (existing != null && existing.updatedAt >= row.updatedAt) return;
                mangaDao.upsertProgress(row);
            }
            if (saveLegacyProgress) {
                trimRoomProgress(c);
                saveLegacyChapterProgress(c, mangaKey, chapterIndex, row.page, row.totalPages);
            }
        } catch (Exception ignored) {}
    }

    private static Progress getSavedChapterProgress(Context c, String slug, float chapterIndex) {
        if (c == null || slug == null) return null;
        try {
            ensureMigrated(c);
            MangaProgressEntity row = dao(c).getProgress(progressKey(slug.trim(), chapterIndex));
            if (row != null) return new Progress(Math.max(0, row.page), Math.max(1, row.totalPages));
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            JSONObject o = root.optJSONObject(slug.trim() + "_" + chapterIndex);
            if (o == null) return null;
            return new Progress(Math.max(0, o.optInt("page", 0)), Math.max(1, o.optInt("totalPages", 1)));
        } catch (Exception ignored) { return null; }
    }

    private static void trimProgress(JSONObject root) {
        if (root == null || root.length() <= MAX_PROGRESS_ITEMS) return;
        try {
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = root.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            int removeCount = Math.max(0, keys.size() - MAX_PROGRESS_ITEMS);
            for (int i = 0; i < removeCount; i++) root.remove(keys.get(i));
        } catch (Exception ignored) {}
    }

    private static ArrayList<JSONObject> raw(Context c) { 
        ArrayList<JSONObject> list = new ArrayList<>(); 
        if (c == null) return list; 
        try {
            ensureMigrated(c);
            List<MangaHistoryEntity> rows = dao(c).getHistory();
            for (MangaHistoryEntity row : rows) list.add(toJson(row));
        } catch(Exception ignored) {} 
        return list; 
    }

    private static ArrayList<JSONObject> rawLegacy(Context c) {
        ArrayList<JSONObject> list = new ArrayList<>();
        if (c == null) return list;
        try {
            JSONArray arr = new JSONArray(p(c).getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        } catch(Exception ignored) {}
        return list;
    }

    private static boolean persist(Context c, ArrayList<JSONObject> list) {
        invalidateEntriesCache();
        if (c == null || list == null) return false;
        JSONArray arr = new JSONArray(); 
        ArrayList<MangaHistoryEntity> rows = new ArrayList<>();
        int position = 0;
        for (JSONObject o: list) {
            if (o == null) continue;
            arr.put(o);
            MangaHistoryEntity row = toEntity(o, position++);
            if (row != null && row.slug != null && !row.slug.trim().isEmpty()) rows.add(row);
        }
        try {
            MangaDao mangaDao = dao(c);
            mangaDao.replaceHistory(rows);
            p(c).edit().putString(KEY_ITEMS, arr.toString()).apply();
            MangaRoomEvents.notifyChanged();
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void ensureMigrated(Context c) {
        if (c == null) return;
        SharedPreferences prefs = migrationPrefs(c);
        if (prefs.getBoolean(KEY_MIGRATED_HISTORY, false)) return;
        synchronized (MangaHistoryManager.class) {
            if (prefs.getBoolean(KEY_MIGRATED_HISTORY, false)) return;
            try {
                ArrayList<JSONObject> old = rawLegacy(c);
                if (!old.isEmpty() && !persist(c, old)) return;
                if (!migrateProgress(c)) return;
                prefs.edit().putBoolean(KEY_MIGRATED_HISTORY, true).apply();
            } catch (Exception ignored) {}
        }
    }

    private static boolean migrateProgress(Context c) {
        try {
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            Iterator<String> iterator = root.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                JSONObject o = root.optJSONObject(key);
                if (o == null) continue;
                int indexSplit = key.lastIndexOf('_');
                if (indexSplit <= 0 || indexSplit + 1 >= key.length()) continue;
                String mangaKey = key.substring(0, indexSplit);
                float chapterIndex;
                try { chapterIndex = Float.parseFloat(key.substring(indexSplit + 1)); } catch (Exception ignored) { continue; }
                int sourceSplit = mangaKey.indexOf(':');
                MangaProgressEntity row = new MangaProgressEntity();
                row.key = key;
                row.mangaKey = mangaKey;
                row.sourceId = sourceSplit > 0 ? mangaKey.substring(0, sourceSplit) : MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
                row.slug = sourceSplit > 0 && sourceSplit + 1 < mangaKey.length() ? mangaKey.substring(sourceSplit + 1) : mangaKey;
                row.chapterIndex = chapterIndex;
                row.page = Math.max(0, o.optInt("page", 0));
                row.totalPages = Math.max(1, o.optInt("totalPages", 1));
                row.updatedAt = System.currentTimeMillis();
                dao(c).upsertProgress(row);
            }
            trimRoomProgress(c);
            return true;
        } catch(Exception ignored) { return false; }
    }

    private static JSONObject toJson(MangaHistoryEntity row) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", safe(row.key));
            o.put("dayKey", safe(row.dayKey));
            o.put("slug", safe(row.slug));
            o.put("sourceId", safe(row.sourceId));
            o.put("sourceLabel", safe(row.sourceLabel));
            o.put("title", safe(row.title));
            o.put("cover", safe(row.cover));
            o.put("author", safe(row.author));
            o.put("status", safe(row.status));
            o.put("synopsis", safe(row.synopsis));
            o.put("genre", safe(row.genre));
            o.put("typeLabel", safe(row.typeLabel));
            o.put("latestChapter", safe(row.latestChapter));
            o.put("latestChapterDate", safe(row.latestChapterDate));
            o.put("totalChapters", Math.max(0, row.totalChapters));
            o.put("chapterIndex", row.chapterIndex);
            o.put("chapterTitle", safe(row.chapterTitle));
            o.put("chapterSlug", safe(row.chapterSlug));
            o.put("chapterId", safe(row.chapterId));
            o.put("page", Math.max(0, row.page));
            o.put("totalPages", Math.max(1, row.totalPages));
            o.put("time", row.time);
        } catch (Exception ignored) {}
        return o;
    }

    private static MangaHistoryEntity toEntity(JSONObject o, int position) {
        if (o == null) return null;
        MangaHistoryEntity row = new MangaHistoryEntity();
        String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        String slug = o.optString("slug", "").trim();
        String dayKey = o.optString("dayKey", makeDayKey(o.optLong("time", 0L)));
        row.key = o.optString("key", sourceId + ":" + slug + ":" + dayKey);
        row.dayKey = dayKey;
        row.slug = slug;
        row.sourceId = sourceId;
        row.sourceLabel = o.optString("sourceLabel", MangaSourceFactory.labelForSourceId(sourceId));
        row.title = o.optString("title", "");
        row.cover = o.optString("cover", "");
        row.author = o.optString("author", "");
        row.status = o.optString("status", "");
        row.synopsis = o.optString("synopsis", "");
        row.genre = o.optString("genre", "");
        row.typeLabel = o.optString("typeLabel", "");
        row.latestChapter = o.optString("latestChapter", "");
        row.latestChapterDate = o.optString("latestChapterDate", "");
        row.totalChapters = Math.max(0, o.optInt("totalChapters", 0));
        row.chapterIndex = (float)o.optDouble("chapterIndex", -1d);
        row.chapterTitle = o.optString("chapterTitle", "");
        row.chapterSlug = o.optString("chapterSlug", "");
        row.chapterId = o.optString("chapterId", "");
        row.page = Math.max(0, o.optInt("page", 0));
        row.totalPages = Math.max(1, o.optInt("totalPages", 1));
        row.time = o.optLong("time", 0L);
        row.position = position;
        return row;
    }

    private static String progressKey(String mangaKey, float chapterIndex) {
        return mangaKey.trim() + "_" + chapterIndex;
    }

    private static void saveLegacyChapterProgress(Context c, String slug, float chapterIndex, int page, int totalPages) {
        if (c == null || slug == null) return;
        try {
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            JSONObject o = new JSONObject();
            int safeTotal = Math.max(1, totalPages);
            o.put("page", Math.max(0, Math.min(page, safeTotal - 1)));
            o.put("totalPages", safeTotal);
            root.put(progressKey(slug.trim(), chapterIndex), o);
            trimProgress(root);
            p(c).edit().putString(KEY_PROGRESS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static void trimRoomProgress(Context c) {
        try {
            List<MangaProgressEntity> all = dao(c).getAllProgress();
            int removeCount = Math.max(0, all.size() - MAX_PROGRESS_ITEMS);
            for (int i = 0; i < removeCount; i++) dao(c).deleteProgress(all.get(i).key);
        } catch (Exception ignored) {}
    }

    private static MangaDao dao(Context c) {
        return MangaDatabase.get(c).mangaDao();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String valueOrPrevious(String previous, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) return text;
        return previous == null ? "" : previous;
    }

    private static String makeDayKey(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        return cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.DAY_OF_YEAR);
    }

    private static SharedPreferences p(Context c) { 
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); 
    }

    private static SharedPreferences migrationPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE);
    }
}
