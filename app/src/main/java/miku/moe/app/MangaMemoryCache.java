package miku.moe.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MangaMemoryCache<K, V> {
    private static final ArrayList<MangaMemoryCache<?, ?>> REGISTERED = new ArrayList<>();
    private final int maxSize;
    private final long ttlMs;
    private final LinkedHashMap<K, Entry<V>> map;

    public MangaMemoryCache(int maxSize, long ttlMs) {
        this.maxSize = Math.max(4, maxSize);
        this.ttlMs = Math.max(0L, ttlMs);
        this.map = new LinkedHashMap<>(16, 0.75f, true);
        synchronized (REGISTERED) { REGISTERED.add(this); }
    }

    public V get(K key) {
        if (key == null) return null;
        synchronized (map) {
            Entry<V> entry = map.get(key);
            if (entry == null) return null;
            if (expired(entry)) {
                map.remove(key);
                return null;
            }
            return entry.value;
        }
    }

    public void put(K key, V value) {
        if (key == null || value == null) return;
        synchronized (map) {
            map.put(key, new Entry<>(value));
            trimLocked();
        }
    }

    public void remove(K key) {
        if (key == null) return;
        synchronized (map) { map.remove(key); }
    }

    /** Hapus semua entri yang kuncinya diawali prefix (dipakai untuk invalidasi per-source). */
    public void removeByPrefix(String prefix) {
        if (prefix == null) return;
        synchronized (map) {
            ArrayList<K> doomed = new ArrayList<>();
            for (K key : map.keySet()) {
                if (key instanceof String && ((String) key).startsWith(prefix)) doomed.add(key);
            }
            for (K key : doomed) map.remove(key);
        }
    }

    public int size() {
        synchronized (map) { return map.size(); }
    }

    public void clear() {
        synchronized (map) { map.clear(); }
    }

    public void trimToHalf() {
        synchronized (map) {
            int target = Math.max(2, maxSize / 2);
            while (map.size() > target) {
                K first = map.keySet().iterator().next();
                map.remove(first);
            }
        }
    }

    private boolean expired(Entry<V> entry) {
        return ttlMs > 0L && System.currentTimeMillis() - entry.time > ttlMs;
    }

    private void trimLocked() {
        ArrayList<K> expiredKeys = new ArrayList<>();
        for (Map.Entry<K, Entry<V>> entry : map.entrySet()) if (expired(entry.getValue())) expiredKeys.add(entry.getKey());
        for (K key : expiredKeys) map.remove(key);
        while (map.size() > maxSize) {
            K first = map.keySet().iterator().next();
            map.remove(first);
        }
    }

    public static void clearRegistered() {
        ArrayList<MangaMemoryCache<?, ?>> copy;
        synchronized (REGISTERED) { copy = new ArrayList<>(REGISTERED); }
        for (MangaMemoryCache<?, ?> cache : copy) if (cache != null) cache.clear();
    }

    public static void trimRegistered() {
        ArrayList<MangaMemoryCache<?, ?>> copy;
        synchronized (REGISTERED) { copy = new ArrayList<>(REGISTERED); }
        for (MangaMemoryCache<?, ?> cache : copy) if (cache != null) cache.trimToHalf();
    }

    private static final class Entry<T> {
        final T value;
        final long time;
        Entry(T value) {
            this.value = value;
            this.time = System.currentTimeMillis();
        }
    }
}
