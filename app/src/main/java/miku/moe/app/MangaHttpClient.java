package miku.moe.app;

import android.content.Context;
import java.io.File;
import java.util.concurrent.TimeUnit;
import okhttp3.Cache;
import okhttp3.OkHttpClient;

public final class MangaHttpClient {
    private static final long CACHE_SIZE = 48L * 1024L * 1024L;
    private static volatile Context appContext;
    private static volatile Cache cache;

    private MangaHttpClient() {}

    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        cache();
    }

    public static OkHttpClient.Builder newBuilder() {
        OkHttpClient.Builder builder = CloudflareHelper.apply(new OkHttpClient.Builder())
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);
        Cache current = cache();
        if (current != null) builder.cache(current);
        return builder;
    }

    private static Cache cache() {
        Context context = appContext;
        if (context == null) return null;
        if (cache == null) {
            synchronized (MangaHttpClient.class) {
                if (cache == null) cache = new Cache(new File(context.getCacheDir(), "manga_source_http_cache"), CACHE_SIZE);
            }
        }
        return cache;
    }
}
