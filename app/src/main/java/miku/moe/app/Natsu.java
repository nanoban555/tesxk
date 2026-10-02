package miku.moe.app;

public final class Natsu extends NatsuIdClient {
    @Override protected String sourceId() { return MangaSettingsManager.MANGA_SOURCE_NATSU; }
    @Override protected String sourceName() { return "Natsu"; }
    @Override protected int rateLimitMillis() { return 250; }
}
