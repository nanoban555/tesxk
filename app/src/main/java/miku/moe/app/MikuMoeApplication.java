package miku.moe.app;

import android.app.Application;
import coil.Coil;
import coil.ImageLoader;
import coil.ImageLoaderFactory;
import coil.disk.DiskCache;
import coil.memory.MemoryCache;
import com.google.android.material.color.DynamicColors;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import java.io.File;
import java.util.concurrent.TimeUnit;

public class MikuMoeApplication extends Application implements ImageLoaderFactory {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        MikuLifecycleKeepAlive.install(this);
        MangaSettingsManager.init(this);
        NetworkDohManager.init(this);
        ThemeManager.applyNightMode(this);
        CloudflareHelper.init(this);
        MangaHttpClient.init(this);
        MangaCacheController.cleanOnAppStart(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
        Coil.setImageLoader(newImageLoader());
        MangaImageLoader.registerImageContext(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_UI_HIDDEN && MangaCacheController.hasActiveReaderSession()) return;
        if (level >= TRIM_MEMORY_UI_HIDDEN) MangaCacheController.trimMangaCache(this, level >= TRIM_MEMORY_MODERATE);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        MangaCacheController.trimMangaCache(this, true);
    }

    @Override
    public ImageLoader newImageLoader() {
        File imageCache = new File(getCacheDir(), "manga_image_cache");
        File httpCache = new File(getCacheDir(), "manga_http_cache");
        OkHttpClient client = CloudflareHelper.apply(new OkHttpClient.Builder())
                .cache(new Cache(httpCache, 24L * 1024L * 1024L))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        return new ImageLoader.Builder(this)
                .okHttpClient(client)
                .memoryCache(new MemoryCache.Builder(this).maxSizePercent(0.14).build())
                .diskCache(new DiskCache.Builder().directory(imageCache).maxSizeBytes(128L * 1024L * 1024L).build())
                .crossfade(false)
                .respectCacheHeaders(false)
                .allowHardware(false)
                .build();
    }
}
