package miku.moe.app;

import java.util.ArrayList;

public final class MangaUpdateCheckLogic {
    private MangaUpdateCheckLogic() {}

    public static MangaChapter newestChapter(ArrayList<MangaChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return null;
        MangaChapter newest = null;
        for (MangaChapter chapter : chapters) {
            if (chapter == null) continue;
            if (newest == null || chapter.index > newest.index) newest = chapter;
        }
        return newest;
    }

    public static String chapterLabel(float index) {
        return "Chapter " + MangaChapter.formatIndex(index);
    }

    public static void applyLatestChapter(MangaPost post, ArrayList<MangaChapter> chapters) {
        if (post == null || chapters == null || chapters.isEmpty()) return;
        MangaChapter newest = newestChapter(chapters);
        if (newest == null) return;
        post.latestChapter = chapterLabel(newest.index);
        post.latestChapterDate = newest.date == null ? "" : newest.date;
        post.totalChapters = Math.max(post.totalChapters, chapters.size());
    }

    public static int calculateAddedFromBaseline(float baseIndex, int baseTotal, ArrayList<MangaChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return 0;
        if (baseIndex > 0f) return countNewChapters(chapters, baseIndex);
        if (baseTotal > 0 && chapters.size() > baseTotal) return chapters.size() - baseTotal;
        return 0;
    }

    public static int calculateAddedFromPost(MangaPost oldPost, ArrayList<MangaChapter> chapters) {
        if (oldPost == null) return 0;
        float oldIndex = parseChapterIndex(oldPost.latestChapter);
        int oldTotal = favoriteChapterTotal(oldPost);
        return calculateAddedFromBaseline(oldIndex, oldTotal, chapters);
    }

    public static int countNewChapters(ArrayList<MangaChapter> chapters, float threshold) {
        if (chapters == null || chapters.isEmpty() || threshold <= 0f) return 0;
        int count = 0;
        for (MangaChapter chapter : chapters) {
            if (chapter != null && chapter.index > threshold) count++;
        }
        return count;
    }

    public static int favoriteChapterTotal(MangaPost post) {
        if (post == null) return 0;
        if (post.totalChapters > 0) return post.totalChapters;
        float chapterIndex = parseChapterIndex(post.latestChapter);
        if (chapterIndex > 0f) return Math.round(chapterIndex);
        return 0;
    }

    public static int displayBase(MangaPost oldPost, float baseIndex, int baseTotal) {
        if (baseTotal > 0) return baseTotal;
        if (baseIndex > 0f) return Math.round(baseIndex);
        return favoriteChapterTotal(oldPost);
    }

    public static float parseChapterIndex(String text) {
        if (text == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:[.,]\\d+)?)").matcher(text);
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1).replace(',', '.')); } catch(Exception e) { return -1f; }
    }

    public static ArrayList<String> collectNewChapterTitles(ArrayList<MangaChapter> chapters, float threshold, int max) {
        ArrayList<String> titles = new ArrayList<>();
        if (chapters == null || chapters.isEmpty() || threshold <= 0f || max <= 0) return titles;
        ArrayList<MangaChapter> sorted = new ArrayList<>(chapters);
        sorted.sort((a, b) -> Float.compare(b == null ? 0f : b.index, a == null ? 0f : a.index));
        for (MangaChapter chapter : sorted) {
            if (chapter == null) continue;
            if (chapter.index > threshold) {
                String title = chapter.title == null ? "" : chapter.title.trim();
                if (!title.isEmpty()) titles.add(title);
            }
            if (titles.size() >= max) break;
        }
        return titles;
    }
}
