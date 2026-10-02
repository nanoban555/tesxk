package miku.moe.app;

public final class KiryuuOfficial extends NatsuIdClient {
    @Override protected String sourceId() { return MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL; }
    @Override protected String sourceName() { return "Kiryuu Official"; }
    @Override protected int rateLimitMillis() { return 250; }
    @Override protected boolean forceChapterPageOne() { return true; }
}
