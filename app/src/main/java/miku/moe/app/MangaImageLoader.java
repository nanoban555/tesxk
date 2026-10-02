package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import coil.Coil;
import coil.ImageLoader;
import coil.request.ErrorResult;
import coil.request.ImageRequest;
import coil.request.Disposable;
import coil.request.CachePolicy;
import coil.request.SuccessResult;
import okhttp3.Headers;
import java.util.LinkedList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class MangaImageLoader {
    private static final Map<ImageView, Disposable> ACTIVE_REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final LinkedHashMap<String, Disposable> ACTIVE_PRELOADS = new LinkedHashMap<>();
    private static final LinkedList<PreloadItem> PENDING_PRELOADS = new LinkedList<>();
    private static final HashSet<String> PENDING_PRELOAD_KEYS = new HashSet<>();
    private static final int MAX_ACTIVE_PRELOADS = 2;
    private static final int MAX_PENDING_PRELOADS = 24;
    private static volatile Context APPLICATION_CONTEXT;
    private static final int MAX_DIMENSION_CACHE = 2400;
    private static final int MAX_PERSISTED_SIZES = 4000;
    private static final int MAX_IMAGE_REFERERS = 1500;
    private static final String SIZE_PREFS = "miku_manga_image_sizes";
    private static final long SIZE_FLUSH_INTERVAL_MS = 4000L;
    private static final LinkedHashMap<String, int[]> DIMENSION_CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static final LinkedHashMap<String, String> IMAGE_REFERERS = new LinkedHashMap<>(256, 0.75f, true);
    private static final HashMap<String, String> PENDING_SIZES = new HashMap<>();
    private static final android.os.Handler SIZE_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean sizeFlushScheduled = false;
    private static volatile boolean dimensionCacheLoaded = false;
    private static java.lang.reflect.Method MEMORY_CACHE_REMOVE;
    private static java.lang.reflect.Method MEMORY_CACHE_GET;
    private static java.lang.reflect.Method MEMORY_CACHE_TRIM;
    private static java.lang.reflect.Method DISK_CACHE_REMOVE;
    private static java.lang.reflect.Method DISK_CACHE_PRUNE;
    private static java.lang.reflect.Constructor<?> MEMORY_CACHE_KEY_CTOR;
    private static Class<?> MEMORY_CACHE_KEY_CLASS;

    private MangaImageLoader() {}

    public interface Callback { void onSuccess(); void onError(); }

    public static void load(ImageView target, String url) { load(target, url, false, null); }
    public static void load(ImageView target, String url, boolean crossfade) { load(target, url, crossfade, null); }

    public static void load(ImageView target, String url, boolean crossfade, Callback callback) {
        loadForSource(target, url, null, crossfade, callback);
    }

    public static void loadForSource(ImageView target, String url, String sourceId, boolean crossfade, Callback callback) {
        loadForSourceInternal(target, url, sourceId, crossfade, callback, false, false);
    }

    public static void loadCoverForSource(ImageView target, String url, String sourceId) {
        loadCoverForSource(target, url, sourceId, null);
    }

    public static void loadCoverForSource(ImageView target, String url, String sourceId, Callback callback) {
        loadForSourceInternal(target, url, sourceId, true, callback, false, false);
    }

    public static void loadCoverForSourceCachedOnly(ImageView target, String url, String sourceId) {
        loadCoverForSourceCachedOnly(target, url, sourceId, null);
    }

    public static void loadCoverForSourceCachedOnly(ImageView target, String url, String sourceId, Callback callback) {
        loadCoverForSourceInternal(target, url, sourceId, false, false, callback);
    }

    private static void loadCoverForSourceInternal(ImageView target, String url, String sourceId, boolean retryFromNetwork, boolean allowNetwork, Callback callback) {
        if (target == null) return;
        if (url == null || url.trim().isEmpty()) {
            target.setImageDrawable(null);
            if (callback != null) callback.onError();
            return;
        }
        Context context = target.getContext();
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        Object currentTag = target.getTag();
        if (requestKey.equals(currentTag) && target.getDrawable() != null && !retryFromNetwork) {
            target.animate().cancel();
            target.setAlpha(1f);
            if (callback != null) callback.onSuccess();
            return;
        }
        String cachedUrl = retryFromNetwork ? null : MangaCoverCache.cachedUri(context, safeUrl);
        boolean local = isLocalUri(safeUrl);
        boolean usingCached = cachedUrl != null && !cachedUrl.trim().isEmpty();
        String resolvedUrl = resolveImageUrl(safeUrl, sourceId);
        String requestData = usingCached ? cachedUrl : resolvedUrl;
        cancelPreload(requestKey);
        cancel(target);
        target.animate().cancel();
        target.setAlpha(1f);
        target.setTag(requestKey);
        ImageRequest request = new ImageRequest.Builder(target.getContext())
                .data(requestData)
                .headers(usingCached || local ? new Headers.Builder().build() : requestHeaders(resolvedUrl, sourceId, retryFromNetwork, false))
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(!usingCached && !local)
                .crossfade(usingCached || local ? 0 : 500)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(allowNetwork || usingCached || local ? CachePolicy.ENABLED : CachePolicy.DISABLED)
                .allowHardware(true)
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                        recordImageSize(requestKey, result.getDrawable());
                        if (requestKey.equals(target.getTag()) && callback != null) callback.onSuccess();
                    }
                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!requestKey.equals(target.getTag())) return;
                        if (usingCached && !retryFromNetwork) {
                            try { MangaCoverCache.delete(context.getApplicationContext(), safeUrl); } catch (Exception ignored) { }
                            if (allowNetwork) {
                                loadCoverForSourceInternal(target, safeUrl, sourceId, true, true, callback);
                                return;
                            }
                        }
                        if (!allowNetwork) target.setImageDrawable(null);
                        if (callback != null) callback.onError();
                    }
                })
                .build();
        ImageLoader imageLoader = Coil.imageLoader(context.getApplicationContext());
        Disposable disposable = imageLoader.enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    public static void registerImageContext(Context context) {
        if (context == null) return;
        APPLICATION_CONTEXT = context.getApplicationContext();
        loadPersistedImageSizes(APPLICATION_CONTEXT);
    }

    private static void loadForSourceInternal(ImageView target, String url, String sourceId, boolean crossfade, Callback callback, boolean retryFromNetwork, boolean directHeaderFallback) {
        if (target == null) return;
        if (url == null || url.trim().isEmpty()) {
            target.setImageDrawable(null);
            if (callback != null) callback.onError();
            return;
        }
        Context context = target.getContext();
        registerImageContext(context);
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        Object currentTag = target.getTag();
        if (requestKey.equals(currentTag) && target.getDrawable() != null && !retryFromNetwork) {
            target.animate().cancel();
            target.setAlpha(1f);
            if (callback != null) callback.onSuccess();
            return;
        }
        String cachedUrl = retryFromNetwork ? null : MangaCoverCache.cachedUri(context, safeUrl);
        boolean local = isLocalUri(safeUrl);
        boolean usingCached = cachedUrl != null && !cachedUrl.trim().isEmpty();
        String resolvedUrl = resolveImageUrl(safeUrl, sourceId);
        String requestData = usingCached ? cachedUrl : resolvedUrl;
        cancelPreload(requestKey);
        cancel(target);
        target.animate().cancel();
        target.setTag(requestKey);
        ImageRequest request = new ImageRequest.Builder(context)
                .data(requestData)
                .headers(usingCached || local ? new Headers.Builder().build() : requestHeaders(resolvedUrl, sourceId, retryFromNetwork, directHeaderFallback))
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(usingCached || local ? 0 : (crossfade ? 500 : 0))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .allowHardware(allowHardwareForTarget(target))
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                        recordImageSize(requestKey, result.getDrawable());
                        if (requestKey.equals(target.getTag()) && callback != null) callback.onSuccess();
                    }
                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!requestKey.equals(target.getTag())) return;
                        if (usingCached && !retryFromNetwork) {
                            try { MangaCoverCache.delete(context.getApplicationContext(), safeUrl); } catch (Exception ignored) { }
                            loadForSourceInternal(target, safeUrl, sourceId, crossfade, callback, true, false);
                            return;
                        }
                        if (!retryFromNetwork && isDoujinImageRequest(safeUrl, sourceId)) {
                            loadForSourceInternal(target, safeUrl, sourceId, crossfade, callback, true, false);
                            return;
                        }
                        if (!directHeaderFallback && shouldRetryWithDirectHeaders(resolvedUrl, sourceId, local)) {
                            loadForSourceInternal(target, safeUrl, sourceId, crossfade, callback, true, true);
                            return;
                        }
                        if (callback != null) callback.onError();
                    }
                })
                .build();
        Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    public static void preload(Context context, String url, String sourceId) {
        enqueuePreload(context, url, sourceId, false);
    }

    public static void preloadPriority(Context context, String url, String sourceId) {
        enqueuePreload(context, url, sourceId, true);
    }

    private static void enqueuePreload(Context context, String url, String sourceId, boolean priority) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        String cachedUrl = MangaCoverCache.cachedUri(app, safeUrl);
        if (cachedUrl != null && !cachedUrl.trim().isEmpty()) return;
        boolean startNow = false;
        synchronized (ACTIVE_PRELOADS) {
            if (ACTIVE_PRELOADS.containsKey(requestKey) || PENDING_PRELOAD_KEYS.contains(requestKey)) return;
            if (ACTIVE_PRELOADS.size() < MAX_ACTIVE_PRELOADS) {
                ACTIVE_PRELOADS.put(requestKey, null);
                startNow = true;
            } else {
                if (PENDING_PRELOADS.size() >= MAX_PENDING_PRELOADS) {
                    PreloadItem removed = PENDING_PRELOADS.pollLast();
                    if (removed != null) PENDING_PRELOAD_KEYS.remove(removed.requestKey);
                }
                PreloadItem item = new PreloadItem(app, safeUrl, sourceId, requestKey, priority);
                addPendingPreload(item, priority);
                PENDING_PRELOAD_KEYS.add(requestKey);
            }
        }
        if (startNow) startPreload(app, safeUrl, sourceId, requestKey, false);
    }

    private static void addPendingPreload(PreloadItem item, boolean priority) {
        if (item == null) return;
        if (!priority || PENDING_PRELOADS.isEmpty()) {
            PENDING_PRELOADS.addLast(item);
            return;
        }
        int insertIndex = 0;
        for (PreloadItem pending : PENDING_PRELOADS) {
            if (!pending.priority) break;
            insertIndex++;
        }
        PENDING_PRELOADS.add(insertIndex, item);
    }

    private static void startPreload(Context context, String safeUrl, String sourceId, String requestKey, boolean directHeaderFallback) {
        try {
            String resolvedUrl = resolveImageUrl(safeUrl, sourceId);
            ImageRequest request = new ImageRequest.Builder(context.getApplicationContext())
                    .data(resolvedUrl)
                    .headers(directHeaderFallback ? new Headers.Builder().build() : headersFor(resolvedUrl, sourceId))
                    .memoryCacheKey(requestKey)
                    .diskCacheKey(requestKey)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .listener(new ImageRequest.Listener() {
                        @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                            recordImageSize(requestKey, result.getDrawable());
                            finishPreload(context, requestKey);
                        }
                        @Override public void onError(ImageRequest request, ErrorResult result) {
                            if (!directHeaderFallback && shouldRetryWithDirectHeaders(resolvedUrl, sourceId, isLocalUri(safeUrl))) {
                                startPreload(context, safeUrl, sourceId, requestKey, true);
                                return;
                            }
                            finishPreload(context, requestKey);
                        }
                    })
                    .build();
            Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
            attachPreload(requestKey, disposable);
        } catch (Exception ignored) {
            finishPreload(context, requestKey);
        }
    }

    public static boolean isCached(Context context, String url, String sourceId) {
        if (context == null || url == null || url.trim().isEmpty()) return false;
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        String cachedUrl = MangaCoverCache.cachedUri(app, safeUrl);
        if (cachedUrl != null && !cachedUrl.trim().isEmpty()) return true;
        return hasMemoryCache(app, cacheKey(safeUrl, sourceId));
    }

    private static boolean hasMemoryCache(Context context, String requestKey) {
        if (context == null || requestKey == null || requestKey.trim().isEmpty()) return false;
        try {
            Object memoryCache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (memoryCache == null) return false;
            Class<?> keyClass = memoryCacheKeyClass();
            if (keyClass == null) return false;
            Object key = createMemoryCacheKey(keyClass, requestKey.trim());
            if (key == null) return false;
            java.lang.reflect.Method get = memoryCacheGetMethod(memoryCache, keyClass);
            if (get == null) return false;
            return get.invoke(memoryCache, key) != null;
        } catch (Exception ignored) { }
        return false;
    }

    private static void attachPreload(String requestKey, Disposable disposable) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        synchronized (ACTIVE_PRELOADS) {
            if (ACTIVE_PRELOADS.containsKey(requestKey)) ACTIVE_PRELOADS.put(requestKey, disposable);
            else if (disposable != null) disposable.dispose();
        }
    }

    private static void finishPreload(Context context, String requestKey) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        PreloadItem next = null;
        synchronized (ACTIVE_PRELOADS) {
            ACTIVE_PRELOADS.remove(requestKey);
            while (!PENDING_PRELOADS.isEmpty()) {
                next = PENDING_PRELOADS.pollFirst();
                if (next == null) continue;
                PENDING_PRELOAD_KEYS.remove(next.requestKey);
                if (ACTIVE_PRELOADS.containsKey(next.requestKey)) {
                    next = null;
                    continue;
                }
                ACTIVE_PRELOADS.put(next.requestKey, null);
                break;
            }
        }
        if (next != null) startPreload(next.context, next.url, next.sourceId, next.requestKey, false);
    }

    public static void preloadRange(Context context, java.util.List<String> pages, String sourceId, int center, int distance) {
        if (context == null || pages == null || pages.isEmpty()) return;
        int start = Math.max(0, center);
        int end = Math.min(pages.size() - 1, center + Math.max(1, distance));
        for (int i = start; i <= end; i++) preload(context, pages.get(i), sourceId);
    }

    public static void preloadRangePriority(Context context, java.util.List<String> pages, String sourceId, int start, int end) {
        if (context == null || pages == null || pages.isEmpty()) return;
        int safeStart = Math.max(0, Math.min(start, pages.size() - 1));
        int safeEnd = Math.max(safeStart, Math.min(end, pages.size() - 1));
        for (int i = safeStart; i <= safeEnd; i++) preloadPriority(context, pages.get(i), sourceId);
    }

    private static boolean allowHardwareForTarget(ImageView target) {
        if (target instanceof MangaWebtoonImageView) return !((MangaWebtoonImageView) target).isCropBorderEnabled();
        return true;
    }

    private static boolean isLocalUri(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("android.resource://") || lower.startsWith("file://") || lower.startsWith("content://");
    }

    private static final class PreloadItem {
        final Context context;
        final String url;
        final String sourceId;
        final String requestKey;
        final boolean priority;

        PreloadItem(Context context, String url, String sourceId, String requestKey, boolean priority) {
            this.context = context;
            this.url = url;
            this.sourceId = sourceId;
            this.requestKey = requestKey;
            this.priority = priority;
        }
    }

    public static int[] getKnownSize(String url, String sourceId) {
        if (url == null || url.trim().isEmpty()) return null;
        String requestKey = cacheKey(url.trim(), sourceId);
        synchronized (DIMENSION_CACHE) {
            int[] size = DIMENSION_CACHE.get(requestKey);
            if (size == null || size.length < 2) return null;
            return new int[]{size[0], size[1]};
        }
    }

    private static void recordImageSize(String requestKey, Drawable drawable) {
        if (requestKey == null || requestKey.trim().isEmpty() || drawable == null) return;
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) return;
        boolean changed;
        synchronized (DIMENSION_CACHE) {
            int[] stored = DIMENSION_CACHE.get(requestKey);
            changed = stored == null || stored.length < 2 || stored[0] != width || stored[1] != height;
            DIMENSION_CACHE.put(requestKey, new int[]{width, height});
            while (DIMENSION_CACHE.size() > MAX_DIMENSION_CACHE) {
                String firstKey = DIMENSION_CACHE.keySet().iterator().next();
                DIMENSION_CACHE.remove(firstKey);
            }
        }
        if (changed) queueSizeForStorage(requestKey, width, height);
    }

    private static void queueSizeForStorage(String requestKey, int width, int height) {
        synchronized (PENDING_SIZES) {
            PENDING_SIZES.put(requestKey, width + "x" + height);
        }
        if (sizeFlushScheduled) return;
        sizeFlushScheduled = true;
        SIZE_HANDLER.postDelayed(() -> MangaCoroutines.io(MangaImageLoader::flushImageSizes), SIZE_FLUSH_INTERVAL_MS);
    }

    private static void flushImageSizes() {
        sizeFlushScheduled = false;
        HashMap<String, String> batch;
        synchronized (PENDING_SIZES) {
            if (PENDING_SIZES.isEmpty()) return;
            batch = new HashMap<>(PENDING_SIZES);
            PENDING_SIZES.clear();
        }
        Context context = APPLICATION_CONTEXT;
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(SIZE_PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            if (prefs.getAll().size() > MAX_PERSISTED_SIZES) editor.clear();
            for (Map.Entry<String, String> entry : batch.entrySet()) editor.putString(entry.getKey(), entry.getValue());
            editor.apply();
        } catch (Exception ignored) { }
    }

    static void loadPersistedImageSizes(Context context) {
        if (context == null || dimensionCacheLoaded) return;
        dimensionCacheLoaded = true;
        Context app = context.getApplicationContext();
        MangaCoroutines.io(() -> {
            try {
                SharedPreferences prefs = app.getSharedPreferences(SIZE_PREFS, Context.MODE_PRIVATE);
                Map<String, ?> all = prefs.getAll();
                synchronized (DIMENSION_CACHE) {
                    for (Map.Entry<String, ?> entry : all.entrySet()) {
                        Object value = entry.getValue();
                        if (!(value instanceof String)) continue;
                        int split = ((String) value).indexOf('x');
                        if (split <= 0) continue;
                        try {
                            int width = Integer.parseInt(((String) value).substring(0, split));
                            int height = Integer.parseInt(((String) value).substring(split + 1));
                            if (width > 0 && height > 0) DIMENSION_CACHE.put(entry.getKey(), new int[]{width, height});
                        } catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) { }
            dimensionCacheLoaded = true;
        });
    }

    public static void trimImageCaches(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            Object diskCache = Coil.imageLoader(app).getDiskCache();
            if (diskCache != null) {
                java.lang.reflect.Method prune = diskCachePruneMethod(diskCache);
                if (prune != null) prune.invoke(diskCache);
            }
        } catch (Exception ignored) { }
        try {
            Object memoryCache = Coil.imageLoader(app).getMemoryCache();
            if (memoryCache != null) {
                java.lang.reflect.Method getMaxSize = memoryCache.getClass().getMethod("getMaxSize");
                java.lang.reflect.Method trim = memoryCacheTrimMethod(memoryCache);
                if (trim != null && getMaxSize != null) {
                    Object max = getMaxSize.invoke(memoryCache);
                    if (max instanceof Integer) trim.invoke(memoryCache, ((Integer) max).intValue() / 2);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static java.lang.reflect.Method memoryCacheTrimMethod(Object memoryCache) {
        if (MEMORY_CACHE_TRIM != null) return MEMORY_CACHE_TRIM;
        try { MEMORY_CACHE_TRIM = memoryCache.getClass().getMethod("trimToSize", int.class); } catch (Throwable ignored) { }
        return MEMORY_CACHE_TRIM;
    }

    private static java.lang.reflect.Method diskCachePruneMethod(Object diskCache) {
        if (DISK_CACHE_PRUNE != null) return DISK_CACHE_PRUNE;
        try { DISK_CACHE_PRUNE = diskCache.getClass().getMethod("prune"); } catch (Exception ignored) { }
        return DISK_CACHE_PRUNE;
    }

    private static void cancelPreload(String requestKey) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        synchronized (ACTIVE_PRELOADS) {
            Disposable active = ACTIVE_PRELOADS.remove(requestKey);
            if (active != null) active.dispose();
            java.util.Iterator<PreloadItem> iterator = PENDING_PRELOADS.iterator();
            while (iterator.hasNext()) {
                PreloadItem item = iterator.next();
                if (requestKey.equals(item.requestKey)) {
                    iterator.remove();
                    PENDING_PRELOAD_KEYS.remove(requestKey);
                    break;
                }
            }
        }
    }

    public static void registerImageReferer(String imageUrl, String referer) {
        String image = imageUrl == null ? "" : imageUrl.trim();
        String page = referer == null ? "" : referer.trim();
        if (image.isEmpty() || page.isEmpty()) return;
        synchronized (IMAGE_REFERERS) {
            IMAGE_REFERERS.put(image, page);
            while (IMAGE_REFERERS.size() > MAX_IMAGE_REFERERS) {
                String first = IMAGE_REFERERS.keySet().iterator().next();
                IMAGE_REFERERS.remove(first);
            }
        }
    }

    private static String registeredImageReferer(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return "";
        synchronized (IMAGE_REFERERS) {
            String value = IMAGE_REFERERS.get(imageUrl.trim());
            return value == null ? "" : value;
        }
    }

    private static String sourceIdKey(String sourceId) { return sourceId == null ? "" : sourceId.trim(); }

    public static String imageCacheKey(String url, String sourceId) { return cacheKey(url, sourceId); }

    public static String resolveImageUrl(String url, String sourceId) {
        return MangaSourceImageStrategy.resolveImageUrl(url, sourceId);
    }

    private static String cacheKey(String url, String sourceId) { return sourceIdKey(sourceId) + "|" + (url == null ? "" : url.trim()); }

    public static boolean isLoaded(ImageView target, String url, String sourceId) {
        if (target == null || url == null || url.trim().isEmpty()) return false;
        Object currentTag = target.getTag();
        return cacheKey(url.trim(), sourceId).equals(currentTag) && target.getDrawable() != null;
    }

    private static boolean shouldRetryWithDirectHeaders(String url, String sourceId, boolean local) {
        return MangaSourceImageStrategy.shouldRetryWithDirectHeaders(url, sourceId, local);
    }

    private static Headers requestHeaders(String url, String sourceId, boolean directRetry, boolean directHeaderFallback) {
        return MangaSourceImageStrategy.requestHeaders(url, sourceId, directRetry, directHeaderFallback, registeredImageReferer(url));
    }

    private static boolean isDoujinImageRequest(String url, String sourceId) {
        return MangaSourceImageStrategy.isDoujinImageRequest(url, sourceId);
    }

    public static Headers headersFor(String url, String sourceId) {
        return MangaSourceImageStrategy.headersFor(url, sourceId, registeredImageReferer(url));
    }

    public static void cancel(ImageView target) {
        if (target == null) return;
        try {
            Disposable disposable = ACTIVE_REQUESTS.remove(target);
            if (disposable != null) disposable.dispose();
        } catch (Exception ignored) { }
    }

    public static void cancelPreloads() {
        try {
            synchronized (ACTIVE_PRELOADS) {
                for (Disposable disposable : new java.util.ArrayList<>(ACTIVE_PRELOADS.values())) if (disposable != null) disposable.dispose();
                ACTIVE_PRELOADS.clear();
                PENDING_PRELOADS.clear();
                PENDING_PRELOAD_KEYS.clear();
            }
        } catch (Exception ignored) { }
    }

    public static void recycle(ImageView target) { if (target != null) { cancel(target); target.animate().cancel(); target.setAlpha(1f); target.setTag(null); } }

    public static void clear(ImageView target) { if (target != null) { recycle(target); target.setImageDrawable(null); } }

    public static void clearImageCache(Context context, List<String> urls, String sourceId) {
        clearImageCache(context, urls, sourceId, true);
    }

    public static void releaseImageMemoryCache(Context context, List<String> urls, String sourceId) {
        clearImageCache(context, urls, sourceId, false);
    }

    private static void clearImageCache(Context context, List<String> urls, String sourceId, boolean includeDiskCache) {
        if (context == null || urls == null || urls.isEmpty()) return;
        Context app = context.getApplicationContext();
        registerImageContext(app);
        HashSet<String> keys = new HashSet<>();
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            String safeUrl = url.trim();
            keys.add(cacheKey(safeUrl, sourceId));
            keys.add(safeUrl);
            if (includeDiskCache) {
                String cachedUrl = MangaCoverCache.cachedUri(app, safeUrl);
                if (cachedUrl != null && !cachedUrl.trim().isEmpty()) keys.add(cachedUrl.trim());
                MangaCoverCache.delete(app, safeUrl);
            }
        }
        if (includeDiskCache) {
            try {
                Object diskCache = Coil.imageLoader(app).getDiskCache();
                if (diskCache != null) {
                    java.lang.reflect.Method remove = diskCacheRemoveMethod(diskCache);
                    if (remove != null) {
                        for (String key : keys) {
                            if (key != null && !key.trim().isEmpty()) {
                                try { remove.invoke(diskCache, key.trim()); } catch (Exception ignored) { }
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        removeMemoryCacheKeys(app, keys);
    }

    private static java.lang.reflect.Method diskCacheRemoveMethod(Object diskCache) {
        if (DISK_CACHE_REMOVE != null) return DISK_CACHE_REMOVE;
        try { DISK_CACHE_REMOVE = diskCache.getClass().getMethod("remove", String.class); } catch (Exception ignored) { }
        return DISK_CACHE_REMOVE;
    }

    private static void removeMemoryCacheKeys(Context context, java.util.Set<String> keys) {
        if (context == null || keys == null || keys.isEmpty()) return;
        try {
            Object memoryCache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (memoryCache == null) return;
            Class<?> keyClass = memoryCacheKeyClass();
            if (keyClass == null) return;
            java.lang.reflect.Method remove = memoryCacheRemoveMethod(memoryCache, keyClass);
            if (remove == null) return;
            for (String key : keys) {
                if (key == null || key.trim().isEmpty()) continue;
                Object memoryKey = createMemoryCacheKey(keyClass, key.trim());
                if (memoryKey != null) {
                    try { remove.invoke(memoryCache, memoryKey); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }

    private static Class<?> memoryCacheKeyClass() {
        if (MEMORY_CACHE_KEY_CLASS != null) return MEMORY_CACHE_KEY_CLASS;
        try { MEMORY_CACHE_KEY_CLASS = Class.forName("coil.memory.MemoryCache$Key"); } catch (Exception ignored) { }
        return MEMORY_CACHE_KEY_CLASS;
    }

    private static java.lang.reflect.Method memoryCacheRemoveMethod(Object memoryCache, Class<?> keyClass) {
        if (MEMORY_CACHE_REMOVE != null) return MEMORY_CACHE_REMOVE;
        try { MEMORY_CACHE_REMOVE = memoryCache.getClass().getMethod("remove", keyClass); } catch (Exception ignored) { }
        return MEMORY_CACHE_REMOVE;
    }

    private static java.lang.reflect.Method memoryCacheGetMethod(Object memoryCache, Class<?> keyClass) {
        if (MEMORY_CACHE_GET != null) return MEMORY_CACHE_GET;
        try { MEMORY_CACHE_GET = memoryCache.getClass().getMethod("get", keyClass); } catch (Exception ignored) { }
        return MEMORY_CACHE_GET;
    }

    private static Object createMemoryCacheKey(Class<?> keyClass, String value) {
        try {
            if (MEMORY_CACHE_KEY_CTOR == null) MEMORY_CACHE_KEY_CTOR = keyClass.getConstructor(String.class);
            return MEMORY_CACHE_KEY_CTOR.newInstance(value);
        } catch (Exception ignored) { }
        try { return keyClass.getConstructor(String.class, java.util.Map.class).newInstance(value, java.util.Collections.emptyMap()); } catch (Exception ignored) { }
        return null;
    }

    public static void clearMemoryCache(Context context) {
        if (context == null) return;
        try {
            coil.memory.MemoryCache cache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (cache != null) cache.clear();
        } catch (Exception ignored) { }
    }
}

