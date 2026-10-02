package miku.moe.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class MangaCoverCache {
    public interface Callback { void onComplete(boolean saved); }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object CLEAR_LOCK = new Object();
    private static final AtomicLong CLEAR_GENERATION = new AtomicLong();
    private static final OkHttpClient CLIENT = NetworkDohManager.apply(new OkHttpClient.Builder()).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HashMap<String, ArrayList<Callback>> PENDING = new HashMap<>();
    private static final long MAX_CACHE_BYTES = 220L * 1024L * 1024L;
    private static final int MAX_FILES = 4000;
    private static final int MAX_RESOLVED_ENTRIES = 6000;
    private static final long TOUCH_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final String MISSING = "";
    private static final LinkedHashMap<String, String> RESOLVED = new LinkedHashMap<String, String>(128, 0.75f, true);
    private static final HashSet<String> TOUCHED = new HashSet<>();

    private MangaCoverCache() {}

    public static String cachedUri(Context context, String url) {
        if (context == null || url == null) return null;
        String safeUrl = url.trim();
        if (safeUrl.isEmpty()) return null;
        String known = knownUri(safeUrl);
        if (known != null) return MISSING.equals(known) ? null : known;
        File file = fileFor(context, safeUrl);
        if (file == null) return null;
        if (!file.exists()) {
            markKnown(safeUrl, MISSING);
            return null;
        }
        if (file.length() <= 0) {
            try { file.delete(); } catch (Exception ignored) { }
            markKnown(safeUrl, MISSING);
            return null;
        }
        String uri = Uri.fromFile(file).toString();
        markKnown(safeUrl, uri);
        touch(file);
        return uri;
    }

    public static boolean isSaved(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String known = knownUri(url.trim());
        return known != null && !MISSING.equals(known);
    }

    public static boolean isSaved(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return false;
        if (isSaved(url)) return true;
        return cachedUri(context, url) != null;
    }

    private static String knownUri(String safeUrl) {
        synchronized (RESOLVED) {
            return RESOLVED.get(safeUrl);
        }
    }

    private static void markKnown(String url, String uri) {
        if (url == null || url.trim().isEmpty()) return;
        String safeUrl = url.trim();
        synchronized (RESOLVED) {
            RESOLVED.put(safeUrl, uri);
            while (RESOLVED.size() > MAX_RESOLVED_ENTRIES) {
                Iterator<String> iterator = RESOLVED.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    public static void forget(String url) {
        markKnown(url, MISSING);
    }

    private static void touch(File file) {
        String name = file.getName();
        synchronized (TOUCHED) {
            if (TOUCHED.contains(name)) return;
            if (TOUCHED.size() > MAX_RESOLVED_ENTRIES) TOUCHED.clear();
            TOUCHED.add(name);
        }
        try {
            long last = file.lastModified();
            if (System.currentTimeMillis() - last > TOUCH_INTERVAL_MS) file.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) { }
    }

    public static long sizeBytes(Context context) {
        File dir = cacheDir(context);
        return sizeOf(dir);
    }

    public static void clear(Context context) {
        File dir = cacheDir(context);
        ArrayList<Callback> canceled = new ArrayList<>();
        synchronized (CLEAR_LOCK) {
            CLEAR_GENERATION.incrementAndGet();
            synchronized (RESOLVED) { RESOLVED.clear(); }
            synchronized (TOUCHED) { TOUCHED.clear(); }
            synchronized (PENDING) {
                for (ArrayList<Callback> callbacks : PENDING.values()) canceled.addAll(callbacks);
                PENDING.clear();
            }
            deleteRecursive(dir);
        }
        if (!canceled.isEmpty()) MAIN.post(() -> {
            for (Callback callback : canceled) {
                try { callback.onComplete(false); } catch (Exception ignored) {}
            }
        });
    }

    public static void prune(Context context) {
        if (context == null) return;
        try { trimNow(context.getApplicationContext()); } catch (Exception ignored) {}
    }

    public static void delete(Context context, String url) {
        forget(url);
        File file = fileFor(context, url);
        if (file == null) return;
        try { if (file.exists()) file.delete(); } catch (Exception ignored) {}
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                File tmp = new File(parent, file.getName() + ".tmp");
                if (tmp.exists()) tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    public static void saveAsync(Context context, String url, String sourceId) {
        saveAsync(context, url, sourceId, null);
    }

    public static void saveAsync(Context context, String url, String sourceId, Callback callback) {
        saveAsync(context, url, sourceId, callback, false);
    }

    public static void saveAsync(Context context, String url, String sourceId, Callback callback, boolean forceNetwork) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        String key = sha256(safeUrl);
        long generation;
        ArrayList<Callback> callbacks;
        synchronized (CLEAR_LOCK) {
            generation = CLEAR_GENERATION.get();
            if (isSaved(safeUrl)) return;
            synchronized (PENDING) {
                ArrayList<Callback> existing = PENDING.get(key);
                if (existing != null) {
                    if (callback != null) existing.add(callback);
                    return;
                }
                callbacks = new ArrayList<>();
                if (callback != null) callbacks.add(callback);
                PENDING.put(key, callbacks);
            }
        }
        ArrayList<Callback> pendingCallbacks = callbacks;
        long saveGeneration = generation;
        EXECUTOR.execute(() -> {
            boolean saved = !MangaCacheController.hasActiveReaderSession() && save(app, safeUrl, sourceId, saveGeneration);
            finishPending(key, pendingCallbacks, saved);
        });
    }

    private static void finishPending(String key, ArrayList<Callback> expected, boolean saved) {
        ArrayList<Callback> callbacks;
        synchronized (PENDING) {
            if (PENDING.get(key) != expected) return;
            callbacks = PENDING.remove(key);
        }
        if (callbacks == null || callbacks.isEmpty()) return;
        MAIN.post(() -> {
            for (Callback callback : callbacks) {
                try { callback.onComplete(saved); } catch (Exception ignored) {}
            }
        });
    }

    private static boolean save(Context context, String url, String sourceId, long generation) {
        File file = fileFor(context, url);
        if (file == null) return false;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            File dir = file.getParentFile();
            synchronized (CLEAR_LOCK) {
                if (generation != CLEAR_GENERATION.get() || MangaCacheController.hasActiveReaderSession()) return false;
                if (file.exists() && file.length() > 0) {
                    markKnown(url, Uri.fromFile(file).toString());
                    return true;
                }
                if (dir != null && !dir.exists()) dir.mkdirs();
            }
            String requestUrl = MangaImageLoader.resolveImageUrl(url, sourceId);
            if (requestUrl.isEmpty() || requestUrl.startsWith("/")) return false;
            Request request = new Request.Builder().url(requestUrl).headers(MangaImageLoader.headersFor(requestUrl, sourceId)).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) return false;
                ResponseBody body = response.body();
                if (body == null) return false;
                long total = 0L;
                try (InputStream input = body.byteStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read > 0) {
                            out.write(buffer, 0, read);
                            total += read;
                        }
                    }
                    out.flush();
                }
                if (total <= 0L) {
                    tmp.delete();
                    return false;
                }
                synchronized (CLEAR_LOCK) {
                    if (generation != CLEAR_GENERATION.get()) {
                        tmp.delete();
                        return false;
                    }
                    if (file.exists()) file.delete();
                    boolean renamed = tmp.renameTo(file);
                    if (renamed) {
                        markKnown(url, Uri.fromFile(file).toString());
                        trim(context);
                    }
                    return renamed;
                }
            }
        } catch (Exception ignored) {
            try { tmp.delete(); } catch (Exception ignored2) {}
            return false;
        }
    }

    private static File fileFor(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return null;
        return new File(cacheDir(context), sha256(url.trim()) + extension(url));
    }

    private static File cacheDir(Context context) {
        if (context == null) return null;
        return new File(context.getApplicationContext().getFilesDir(), "manga_cover_cache");
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            char[] hex = new char[digest.length * 2];
            int index = 0;
            for (byte b : digest) {
                hex[index++] = HEX_CHARS[(b >> 4) & 0x0F];
                hex[index++] = HEX_CHARS[b & 0x0F];
            }
            return new String(hex);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static String extension(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        return ".jpg";
    }

    private static int savesSinceTrim = 0;

    private static void trim(Context context) {
        if (++savesSinceTrim < 20) return;
        savesSinceTrim = 0;
        trimNow(context);
    }

    private static void trimNow(Context context) {
        File dir = cacheDir(context);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        long total = 0L;
        ArrayList<File> regularFiles = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            regularFiles.add(f);
            total += Math.max(0L, f.length());
        }
        if (regularFiles.size() <= MAX_FILES && total <= MAX_CACHE_BYTES) return;
        java.util.Collections.sort(regularFiles, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        int excessFiles = regularFiles.size() - MAX_FILES;
        int deleted = 0;
        for (File f : regularFiles) {
            if (deleted >= excessFiles && total <= MAX_CACHE_BYTES) break;
            long len = Math.max(0L, f.length());
            if (!f.delete()) continue;
            total -= len;
            deleted++;
            purgeIndexEntry(f.getName());
        }
    }

    private static void purgeIndexEntry(String fileName) {
        if (fileName == null || fileName.isEmpty()) return;
        synchronized (RESOLVED) {
            Iterator<Map.Entry<String, String>> iterator = RESOLVED.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, String> entry = iterator.next();
                String value = entry.getValue();
                if (value != null && !value.isEmpty() && value.endsWith(fileName)) iterator.remove();
            }
        }
        synchronized (TOUCHED) {
            TOUCHED.remove(fileName);
        }
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
