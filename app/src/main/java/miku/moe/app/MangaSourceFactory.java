package miku.moe.app;

import android.content.Context;
import java.util.ArrayList;

public final class MangaSourceFactory {
    private static final KomikcastClient KOMIKCAST = new KomikcastClient();
    private static final Shinigami SHINIGAMI = new Shinigami();
    private static final DoujinDesu DOUJINDESU = new DoujinDesu();
    private static final Westmanga WESTMANGA = new Westmanga();
    private static final BacaKomik BACAKOMIK = new BacaKomik();
    private static final KomikIndo KOMIKINDO = new KomikIndo();
    private static final Ikiru IKIRU = new Ikiru();
    private static final Komiku KOMIKU = new Komiku();
    private static final Mangasusu MANGASUSU = new Mangasusu();
    private static final KomikuOrg KOMIKU_ORG = new KomikuOrg();
    private static final CosmicScans COSMICSCANS = new CosmicScans();
    private static final KiryuuOfficial KIRYUU_OFFICIAL = new KiryuuOfficial();
    private static final Natsu NATSU = new Natsu();
    private static final Ainzscanss AINZSCANSS = new Ainzscanss();
    private static final Crotpedia CROTPEDIA = new Crotpedia();
    private static final Apkomik APKOMIK = new Apkomik();
    private static final Comicaso COMICASO = new Comicaso();
    private static final KumoPoi KUMOPOI = new KumoPoi();
    private static final Ngomik NGOMIK = new Ngomik();
    private static final Mgkomik MGKOMIK = new Mgkomik();
    private static final KomikTap KOMIKTAP = new KomikTap();
    private static final ManhwaIndo MANHWAINDO = new ManhwaIndo();
    private static final SoulScans SOULSCANS = new SoulScans();
    private static final Kuromanga KUROMANGA = new Kuromanga();
    private static final IsekaiKomik ISEKAIKOMIK = new IsekaiKomik();
    private static final Luvyaa LUVYAA = new Luvyaa();
    private static final SekteDoujin SEKTEDOUJIN = new SekteDoujin();

    private MangaSourceFactory() {}

    public static KomikcastClient create(Context context) {
        return createBySourceId(context == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : MangaSettingsManager.getMangaSource(context));
    }

    public static KomikcastClient createFor(MangaPost manga, Context fallbackContext) {
        if (manga != null) return createBySourceId(manga.getSourceId());
        return create(fallbackContext);
    }

    public static KomikcastClient createBySourceId(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return SHINIGAMI;
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return DOUJINDESU;
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return WESTMANGA;
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) return BACAKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return KOMIKINDO;
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return IKIRU;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return KOMIKU;
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return MANGASUSU;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return KOMIKU_ORG;
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return COSMICSCANS;
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return KIRYUU_OFFICIAL;
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return NATSU;
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return AINZSCANSS;
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) return CROTPEDIA;
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return APKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) return COMICASO;
        if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) return KUMOPOI;
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) return NGOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_MGKOMIK.equals(sourceId)) return MGKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId)) return KOMIKTAP;
        if (MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId)) return MANHWAINDO;
        if (MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId)) return SOULSCANS;
        if (MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId)) return KUROMANGA;
        if (MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId)) return ISEKAIKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(sourceId)) return LUVYAA;
        if (MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(sourceId)) return SEKTEDOUJIN;
        return KOMIKCAST;
    }

    /** Bersihkan semua memory cache milik satu source manga.
     *  Dipanggil setiap ada aksi refresh/reload eksplisit dari user supaya
     *  data yang dimuat ulang benar-benar dari network, bukan dari cache 12 jam. */
    public static void invalidateSourceCaches(String sourceId) {
        if (sourceId == null) return;
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) { Shinigami.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) { DoujinDesu.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) { Westmanga.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) { BacaKomik.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) { KomikIndo.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) { Ikiru.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) { Komiku.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) { Mangasusu.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) { KomikuOrg.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) { CosmicScans.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) { Ainzscanss.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) { Crotpedia.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) { Apkomik.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) { Comicaso.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) { KumoPoi.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) { Ngomik.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_MGKOMIK.equals(sourceId)) { Mgkomik.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId)) { KomikTap.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId)) { ManhwaIndo.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId)) { SoulScans.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId)) { Kuromanga.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId)) { IsekaiKomik.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(sourceId)) { Luvyaa.clearSessionCaches(); return; }
        if (MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(sourceId)) { SekteDoujin.clearSessionCaches(); return; }
        // KiryuuOfficial, Natsu, dan source lain: pakai cache milik KomikcastClient.
        KomikcastClient.clearSessionCaches();
    }

    public static String getActiveSourceLabel(Context context) {
        return labelForSourceId(context == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : MangaSettingsManager.getMangaSource(context));
    }

    public static String[] allSourceIds() {
        return new String[]{
                MangaSettingsManager.MANGA_SOURCE_KOMIKCAST,
                MangaSettingsManager.MANGA_SOURCE_SHINIGAMI,
                MangaSettingsManager.MANGA_SOURCE_DOUJINDESU,
                MangaSettingsManager.MANGA_SOURCE_WESTMANGA,
                MangaSettingsManager.MANGA_SOURCE_BACAKOMIK,
                MangaSettingsManager.MANGA_SOURCE_KOMIKINDO,
                MangaSettingsManager.MANGA_SOURCE_IKIRU,
                MangaSettingsManager.MANGA_SOURCE_KOMIKU,
                MangaSettingsManager.MANGA_SOURCE_MANGASUSU,
                MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG,
                MangaSettingsManager.MANGA_SOURCE_COSMICSCANS,
                MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL,
                MangaSettingsManager.MANGA_SOURCE_NATSU,
                MangaSettingsManager.MANGA_SOURCE_AINZSCANSS,
                MangaSettingsManager.MANGA_SOURCE_CROTPEDIA,
                MangaSettingsManager.MANGA_SOURCE_APKOMIK,
                MangaSettingsManager.MANGA_SOURCE_COMICASO,
                MangaSettingsManager.MANGA_SOURCE_KUMOPOI,
                MangaSettingsManager.MANGA_SOURCE_NGOMIK,
                MangaSettingsManager.MANGA_SOURCE_MGKOMIK,
                MangaSettingsManager.MANGA_SOURCE_KOMIKTAP,
                MangaSettingsManager.MANGA_SOURCE_MANHWAINDO,
                MangaSettingsManager.MANGA_SOURCE_SOULSCANS,
                MangaSettingsManager.MANGA_SOURCE_KUROMANGA,
                MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK,
                MangaSettingsManager.MANGA_SOURCE_LUVYAA,
                MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN
        };
    }

    public static ArrayList<String> enabledSourceIds(Context context) {
        if (context == null) {
            ArrayList<String> result = new ArrayList<>();
            for (String source : allSourceIds()) result.add(source);
            return result;
        }
        return MangaSettingsManager.getEnabledMangaSources(context);
    }

    public static String labelForSourceId(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return "Shinigami";
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return "DoujinDesu";
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return "Westmanga";
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) return "BacaKomik";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return "Komikindo";
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return "Ikiru";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return "Komiku Asia";
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return "Mangasusu";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return "Komiku Org";
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return "CosmicScans";
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return "Kiryuu Official";
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return "Natsu";
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return "Ainzscanss";
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) return "Crotpedia";
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return "Apkomik";
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) return "Comicaso";
        if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) return "KumoPoi";
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) return "Ngomik";
        if (MangaSettingsManager.MANGA_SOURCE_MGKOMIK.equals(sourceId)) return "Mgkomik";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId)) return "KomikTap";
        if (MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId)) return "Manhwa Indo";
        if (MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId)) return "Soul Scans";
        if (MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId)) return "Kuromanga";
        if (MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId)) return "Isekai Komik";
        if (MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(sourceId)) return "Luvyaa";
        if (MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(sourceId)) return "SekteDoujin";
        return "VoraToon";
    }

    public static String iconForSourceId(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI);
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU);
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_WESTMANGA);
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_BACAKOMIK);
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKINDO);
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_IKIRU);
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKU);
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_MANGASUSU);
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG);
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return "android.resource://miku.moe.app/drawable/ic_source_cosmicscans";
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL);
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_NATSU);
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_AINZSCANSS);
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_CROTPEDIA);
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_APKOMIK);
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_COMICASO);
        if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KUMOPOI);
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_NGOMIK);
        if (MangaSettingsManager.MANGA_SOURCE_MGKOMIK.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_MGKOMIK);
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKTAP);
        if (MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_MANHWAINDO);
        if (MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_SOULSCANS);
        if (MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KUROMANGA);
        if (MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK);
        if (MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_LUVYAA);
        if (MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN);
        return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
    }
}
