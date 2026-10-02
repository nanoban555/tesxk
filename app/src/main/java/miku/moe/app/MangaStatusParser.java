package miku.moe.app;

import java.util.Locale;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Ambil status langsung dari baris "Status" di info series pada halaman detail website sumber.
 * Elemen ".status" generik sengaja TIDAK dipakai duluan karena di banyak tema bisa menunjuk
 * ke badge/kartu lain (mis. "Completed" milik series terkait), sehingga status di app beda dengan website.
 */
public final class MangaStatusParser {
    private MangaStatusParser() {}

    public static String fromInfoRows(Document document) {
        if (document == null) return "";
        // 1) Tabel info: <tr><td>Status</td><td>Ongoing</td></tr>
        for (Element row : document.select(".infotable tr, .inftable tr, table.infotable tr")) {
            Elements cells = row.select("td, th");
            if (cells.size() < 2) continue;
            String key = cells.get(0).text().replace(":", "").trim().toLowerCase(Locale.ROOT);
            if (key.equals("status")) {
                String value = cells.get(1).text().trim();
                if (!value.isEmpty()) return value;
            }
        }
        // 2) Tema MangaStream/Themesia: <div class="imptdt">Status <i>Ongoing</i></div>
        for (Element item : document.select(".tsinfo .imptdt, .imptdt")) {
            String full = item.text().trim();
            if (!full.toLowerCase(Locale.ROOT).startsWith("status")) continue;
            Element value = item.selectFirst("i, a, span");
            String text = value != null ? value.text().trim() : full.substring("status".length()).replace(":", "").trim();
            if (!text.isEmpty()) return text;
        }
        // 3) Tema Komiku/BacaKomik: <div class="infox"><div class="spe"><span><b>Status:</b> Ongoing</span>
        for (Element span : document.select(".infox .spe span, .spe span")) {
            String full = span.text().trim();
            if (!full.toLowerCase(Locale.ROOT).startsWith("status")) continue;
            String text = full.substring("status".length()).replace(":", "").trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }
}
