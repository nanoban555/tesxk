package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

public final class MangaRoomEvents {
    public interface Listener {
        void onMangaRoomChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<Listener> listeners = new ArrayList<>();

    private MangaRoomEvents() {}

    public static void addListener(Listener listener) {
        if (listener == null) return;
        synchronized (listeners) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public static void notifyChanged() {
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        MAIN.post(() -> {
            for (Listener listener : copy) if (listener != null) listener.onMangaRoomChanged();
        });
    }
}
