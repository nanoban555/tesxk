package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MangaLatestChapterResolver {
    public interface Callback {
        void onResolved(MangaPost post, boolean changed);
    }

    private static final int MAX_CONCURRENT = 3;
    private static final int MAX_CACHE = 256;
    private static final long RETRY_DELAY_MS = 120000L;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    private static final HashMap<String, ArrayList<PendingCallback>> PENDING = new HashMap<>();
    private static final LinkedHashMap<String, CacheEntry> CACHE = new LinkedHashMap<>(32, 0.75f, true);
    private static final HashMap<String, Long> FAILED_AT = new HashMap<>();
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static int running;

    private MangaLatestChapterResolver() {}

    public static void resolve(MangaPost post, Callback callback) {
        if (post == null) return;
        String ready = normalize(post.latestChapter);
        if (!ready.isEmpty()) {
            post.latestChapter = ready;
            dispatch(callback, post, false);
            return;
        }
        String sourceId = post.getSourceId();
        String slug = post.slug == null ? "" : post.slug.trim();
        if (sourceId == null || sourceId.trim().isEmpty() || slug.isEmpty()) {
            dispatch(callback, post, false);
            return;
        }
        String key = sourceId.trim() + "|" + slug;
        CacheEntry cached;
        boolean retryBlocked = false;
        boolean startPump = false;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            pruneFailed(now);
            cached = CACHE.get(key);
            if (cached == null) {
                Long failedAt = FAILED_AT.get(key);
                if (failedAt != null && now - failedAt < RETRY_DELAY_MS) {
                    retryBlocked = true;
                } else {
                    ArrayList<PendingCallback> callbacks = PENDING.get(key);
                    if (callbacks != null) {
                        callbacks.add(new PendingCallback(post, callback));
                        return;
                    }
                    callbacks = new ArrayList<>();
                    callbacks.add(new PendingCallback(post, callback));
                    PENDING.put(key, callbacks);
                    QUEUE.addLast(new Request(key, sourceId.trim(), slug, post));
                    startPump = true;
                }
            }
        }
        if (cached != null) {
            boolean changed = apply(post, cached.chapter, cached.totalChapters);
            dispatch(callback, post, changed);
            return;
        }
        if (retryBlocked) {
            dispatch(callback, post, false);
            return;
        }
        if (startPump) pump();
    }

    private static void pump() {
        ArrayList<Request> requests = new ArrayList<>();
        synchronized (LOCK) {
            while (running < MAX_CONCURRENT && !QUEUE.isEmpty()) {
                Request request = QUEUE.removeFirst();
                running++;
                requests.add(request);
            }
        }
        for (Request request : requests) start(request);
    }

    private static void start(Request request) {
        MangaRepository.INSTANCE.latestChapterDataFuture(request.sourceId, request.slug).whenComplete((data, error) -> {
            String chapter = data == null ? "" : normalize(data.getChapter());
            int total = data == null ? 0 : data.getTotalChapters();
            if (chapter.isEmpty() && request.post.totalChapters > 0) {
                chapter = "Chapter " + request.post.totalChapters;
                total = Math.max(total, request.post.totalChapters);
            }
            ArrayList<PendingCallback> callbacks;
            synchronized (LOCK) {
                running = Math.max(0, running - 1);
                callbacks = PENDING.remove(request.key);
                if (!chapter.isEmpty()) {
                    CACHE.put(request.key, new CacheEntry(chapter, total));
                    FAILED_AT.remove(request.key);
                    trimCache();
                } else {
                    FAILED_AT.put(request.key, System.currentTimeMillis());
                }
            }
            if (callbacks != null) {
                for (PendingCallback pending : callbacks) {
                    boolean changed = !chapter.isEmpty() && apply(pending.post, chapter, total);
                    dispatch(pending.callback, pending.post, changed);
                }
            }
            pump();
        });
    }

    private static boolean apply(MangaPost post, String chapter, int total) {
        if (post == null || chapter == null || chapter.isEmpty()) return false;
        boolean changed = !chapter.equals(normalize(post.latestChapter));
        post.latestChapter = chapter;
        if (total > post.totalChapters) {
            post.totalChapters = total;
            changed = true;
        }
        return changed;
    }

    public static String normalize(String value) {
        if (value == null) return "";
        Matcher matcher = CHAPTER_PATTERN.matcher(value.trim());
        if (!matcher.find()) return "";
        return "Chapter " + matcher.group(1).replace(',', '.');
    }

    private static void trimCache() {
        while (CACHE.size() > MAX_CACHE) {
            Map.Entry<String, CacheEntry> first = CACHE.entrySet().iterator().next();
            CACHE.remove(first.getKey());
        }
    }

    private static void pruneFailed(long now) {
        ArrayList<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : FAILED_AT.entrySet()) {
            Long time = entry.getValue();
            if (time == null || now - time >= RETRY_DELAY_MS) expired.add(entry.getKey());
        }
        for (String key : expired) FAILED_AT.remove(key);
    }

    private static void dispatch(Callback callback, MangaPost post, boolean changed) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResolved(post, changed));
    }

    private static final class Request {
        final String key;
        final String sourceId;
        final String slug;
        final MangaPost post;

        Request(String key, String sourceId, String slug, MangaPost post) {
            this.key = key;
            this.sourceId = sourceId;
            this.slug = slug;
            this.post = post;
        }
    }

    private static final class PendingCallback {
        final MangaPost post;
        final Callback callback;

        PendingCallback(MangaPost post, Callback callback) {
            this.post = post;
            this.callback = callback;
        }
    }

    private static final class CacheEntry {
        final String chapter;
        final int totalChapters;

        CacheEntry(String chapter, int totalChapters) {
            this.chapter = chapter;
            this.totalChapters = totalChapters;
        }
    }
}
