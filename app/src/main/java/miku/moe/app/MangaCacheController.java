package miku.moe.app;

import android.content.Context;
import java.io.File;

public final class MangaCacheController {
    private static final String DETAIL_CACHE_PREFS = "miku_manga_detail_cache";
    private static final int DETAIL_CACHE_LIMIT = 60;
    private static int ACTIVE_READER_SESSIONS = 0;

    private MangaCacheController() {}

    public static void cleanOnAppStart(Context context) {
        clean(context, true);
    }

    public static void trimMangaCache(Context context, boolean deep) {
        if (!deep && hasActiveReaderSession()) return;
        clean(context, deep);
    }

    public static synchronized void beginReaderSession() {
        ACTIVE_READER_SESSIONS++;
    }

    public static synchronized void endReaderSession() {
        if (ACTIVE_READER_SESSIONS > 0) ACTIVE_READER_SESSIONS--;
    }

    public static synchronized boolean hasActiveReaderSession() {
        return ACTIVE_READER_SESSIONS > 0;
    }

    public static void clearReaderImageCache(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { MangaImageLoader.cancelPreloads(); } catch (Exception ignored) {}
            try { MangaMemoryCache.trimRegistered(); } catch (Exception ignored) {}
            try { MangaImageLoader.trimImageCaches(app); } catch (Exception ignored) {}
            try { deleteContents(new File(app.getCacheDir(), "manga_reader_pages")); } catch (Exception ignored) {}
            try { deleteContents(new File(app.getCacheDir(), "manga_temp")); } catch (Exception ignored) {}
            try { MangaCoverCache.prune(app); } catch (Exception ignored) {}
        }, "MangaReaderImageCleaner").start();
    }

    public static long totalCacheBytes(Context context) {
        if (context == null) return 0L;
        Context app = context.getApplicationContext();
        return sizeOf(app.getCacheDir()) + MangaCoverCache.sizeBytes(app);
    }

    public static void clearAllMangaCache(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try { MangaMemoryCache.clearRegistered(); } catch (Exception ignored) {}
        try { MangaImageLoader.clearMemoryCache(app); } catch (Exception ignored) {}
        deleteContents(app.getCacheDir());
        MangaCoverCache.clear(app);
        try { app.getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE).edit().clear().apply(); } catch (Exception ignored) {}
    }

    private static void clean(Context context, boolean deep) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (deep) MangaMemoryCache.clearRegistered(); else MangaMemoryCache.trimRegistered();
                File cache = app.getCacheDir();
                deleteContents(new File(cache, "manga_reader_pages"));
                deleteContents(new File(cache, "manga_temp"));
                trimSharedPreferences(app);
                if (deep) MangaImageLoader.trimImageCaches(app);
            } catch (Exception ignored) {}
        }, "MangaCacheCleaner").start();
    }

    private static void trimSharedPreferences(Context context) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE);
            java.util.Map<String, ?> all = prefs.getAll();
            if (all.size() <= DETAIL_CACHE_LIMIT) return;
            java.util.ArrayList<String> keys = new java.util.ArrayList<>(all.keySet());
            java.util.Collections.sort(keys);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            int remove = Math.max(0, keys.size() - DETAIL_CACHE_LIMIT);
            for (int i = 0; i < remove; i++) editor.remove(keys.get(i));
            editor.apply();
        } catch (Exception ignored) {}
    }

    private static void deleteContents(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) deleteRecursive(child);
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());
        File[] children = file.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) total += sizeOf(child);
        return total;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        try { file.delete(); } catch (Exception ignored) {}
    }
}
