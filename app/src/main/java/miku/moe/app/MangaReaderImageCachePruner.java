package miku.moe.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class MangaReaderImageCachePruner {
    static final int RETAIN_CHAPTER_DISTANCE = 1;
    private final LinkedHashMap<Integer, ArrayList<String>> chapterPages = new LinkedHashMap<>();

    void rememberChapterPages(int chapterPosition, ArrayList<String> pages) {
        if (chapterPosition < 0 || pages == null || pages.isEmpty()) return;
        ArrayList<String> cleanPages = new ArrayList<>();
        for (String page : pages) if (page != null && !page.trim().isEmpty()) cleanPages.add(page.trim());
        if (!cleanPages.isEmpty()) chapterPages.put(chapterPosition, cleanPages);
    }

    void prune(Context context, String sourceId, int currentChapterPosition) {
        if (context == null || currentChapterPosition < 0 || chapterPages.isEmpty()) return;
        ArrayList<String> removed = new ArrayList<>();
        Iterator<Map.Entry<Integer, ArrayList<String>>> iterator = chapterPages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ArrayList<String>> entry = iterator.next();
            if (Math.abs(entry.getKey() - currentChapterPosition) > RETAIN_CHAPTER_DISTANCE) {
                removed.addAll(new ArrayList<>(entry.getValue()));
                iterator.remove();
            }
        }
        if (removed.isEmpty()) return;
        Context app = context.getApplicationContext();
        MangaCoroutines.io(() -> MangaImageLoader.releaseImageMemoryCache(app, removed, sourceId));
    }

    void clear() {
        chapterPages.clear();
    }
}
