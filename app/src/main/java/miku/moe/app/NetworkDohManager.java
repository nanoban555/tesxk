package miku.moe.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class NetworkDohManager {
    private static Context appContext;
    private static final DynamicDns DYNAMIC_DNS = new DynamicDns();
    private static final ConcurrentHashMap<Integer, Dns> DNS_CACHE = new ConcurrentHashMap<>();
    private static final MediaType DNS_MESSAGE = MediaType.get("application/dns-message");
    private static final int TYPE_A = 1;
    private static final int TYPE_AAAA = 28;

    private NetworkDohManager() {}

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static OkHttpClient.Builder apply(OkHttpClient.Builder builder) {
        OkHttpClient.Builder target = builder == null ? new OkHttpClient.Builder() : builder;
        return target.dns(DYNAMIC_DNS);
    }

    public static void refresh() {
        DNS_CACHE.clear();
    }

    private static Dns dnsFor(int provider) {
        if (provider == MangaSettingsManager.DOH_DISABLED) return Dns.SYSTEM;
        Dns cached = DNS_CACHE.get(provider);
        if (cached != null) return cached;
        Dns created = createDns(provider);
        if (created == null) return Dns.SYSTEM;
        Dns old = DNS_CACHE.putIfAbsent(provider, created);
        return old != null ? old : created;
    }

    private static Dns createDns(int provider) {
        try {
            switch (provider) {
                case MangaSettingsManager.DOH_CLOUDFLARE:
                    return build("https://cloudflare-dns.com/dns-query", new String[]{"162.159.36.1", "162.159.46.1", "1.1.1.1", "1.0.0.1", "162.159.132.53", "2606:4700:4700::1111", "2606:4700:4700::1001", "2606:4700:4700::0064", "2606:4700:4700::6400"});
                case MangaSettingsManager.DOH_GOOGLE:
                    return build("https://dns.google/dns-query", new String[]{"8.8.4.4", "8.8.8.8", "2001:4860:4860::8888", "2001:4860:4860::8844"});
                case MangaSettingsManager.DOH_ADGUARD:
                    return build("https://dns-unfiltered.adguard.com/dns-query", new String[]{"94.140.14.140", "94.140.14.141", "2a10:50c0::1:ff", "2a10:50c0::2:ff"});
                case MangaSettingsManager.DOH_QUAD9:
                    return build("https://dns.quad9.net/dns-query", new String[]{"9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"});
                case MangaSettingsManager.DOH_ALIDNS:
                    return build("https://dns.alidns.com/dns-query", new String[]{"223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1"});
                case MangaSettingsManager.DOH_DNSPOD:
                    return build("https://doh.pub/dns-query", new String[]{"1.12.12.12", "120.53.53.53"});
                case MangaSettingsManager.DOH_360:
                    return build("https://doh.360.cn/dns-query", new String[]{"101.226.4.6", "218.30.118.6", "123.125.81.6", "140.207.198.6", "180.163.249.75", "101.199.113.208", "36.99.170.86"});
                case MangaSettingsManager.DOH_QUAD101:
                    return build("https://dns.twnic.tw/dns-query", new String[]{"101.101.101.101", "2001:de4::101", "2001:de4::102"});
                case MangaSettingsManager.DOH_MULLVAD:
                    return build("https://dns.mullvad.net/dns-query", new String[]{"194.242.2.2", "2a07:e340::2"});
                case MangaSettingsManager.DOH_CONTROLD:
                    return build("https://freedns.controld.com/p0", new String[]{"76.76.2.0", "76.76.10.0", "2606:1a40::", "2606:1a40:1::"});
                case MangaSettingsManager.DOH_NJALLA:
                    return build("https://dns.njal.la/dns-query", new String[]{"95.215.19.53", "2001:67c:2354:2::53"});
                case MangaSettingsManager.DOH_SHECAN:
                    return build("https://free.shecan.ir/dns-query", new String[]{"178.22.122.100", "185.51.200.2"});
                default:
                    return Dns.SYSTEM;
            }
        } catch (Exception e) {
            return Dns.SYSTEM;
        }
    }

    private static Dns build(String url, String[] hosts) throws Exception {
        HttpUrl endpoint = HttpUrl.get(url);
        ArrayList<InetAddress> bootstrap = new ArrayList<>();
        for (String host : hosts) bootstrap.add(InetAddress.getByName(host));
        OkHttpClient client = new OkHttpClient.Builder().dns(new BootstrapDns(endpoint.host(), bootstrap)).build();
        return new WireDohDns(client, endpoint);
    }

    private static final class DynamicDns implements Dns {
        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            Context context = appContext;
            int provider = context == null ? MangaSettingsManager.DOH_DISABLED : MangaSettingsManager.getDohProvider(context);
            Dns dns = dnsFor(provider);
            return dns.lookup(hostname);
        }
    }

    private static final class BootstrapDns implements Dns {
        private final String host;
        private final List<InetAddress> addresses;

        BootstrapDns(String host, List<InetAddress> addresses) {
            this.host = host;
            this.addresses = addresses;
        }

        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            if (host.equalsIgnoreCase(hostname)) return addresses;
            return Dns.SYSTEM.lookup(hostname);
        }
    }

    private static final class WireDohDns implements Dns {
        private final OkHttpClient client;
        private final HttpUrl endpoint;
        private final AtomicInteger ids = new AtomicInteger(1);

        WireDohDns(OkHttpClient client, HttpUrl endpoint) {
            this.client = client;
            this.endpoint = endpoint;
        }

        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            ArrayList<InetAddress> result = new ArrayList<>();
            UnknownHostException failure = null;
            try {
                result.addAll(query(hostname, TYPE_AAAA));
            } catch (UnknownHostException e) {
                failure = e;
            }
            try {
                result.addAll(query(hostname, TYPE_A));
            } catch (UnknownHostException e) {
                if (failure == null) failure = e;
            }
            if (!result.isEmpty()) return result;
            if (failure != null) throw failure;
            throw new UnknownHostException(hostname);
        }

        private List<InetAddress> query(String hostname, int type) throws UnknownHostException {
            int id = ids.getAndIncrement() & 0xffff;
            byte[] payload;
            try {
                payload = buildQuery(id, hostname, type);
            } catch (IOException e) {
                throw unknown(hostname, e);
            }
            Request request = new Request.Builder()
                    .url(endpoint)
                    .post(RequestBody.create(payload, DNS_MESSAGE))
                    .header("Accept", "application/dns-message")
                    .header("Content-Type", "application/dns-message")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("DoH gagal");
                return parseResponse(response.body().bytes(), id, type);
            } catch (IOException e) {
                throw unknown(hostname, e);
            }
        }

        private UnknownHostException unknown(String hostname, Throwable cause) {
            UnknownHostException exception = new UnknownHostException(hostname);
            exception.initCause(cause);
            return exception;
        }
    }

    private static byte[] buildQuery(int id, String hostname, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, id);
        writeShort(out, 0x0100);
        writeShort(out, 1);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        String ascii = IDN.toASCII(hostname);
        String[] labels = ascii.split("\\.");
        for (String label : labels) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) throw new IOException("Host tidak valid");
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0);
        writeShort(out, type);
        writeShort(out, 1);
        return out.toByteArray();
    }

    private static List<InetAddress> parseResponse(byte[] data, int id, int type) throws IOException {
        if (data == null || data.length < 12) throw new IOException("DNS response kosong");
        if (readShort(data, 0) != id) throw new IOException("DNS id tidak cocok");
        int flags = readShort(data, 2);
        if ((flags & 0x000f) != 0) throw new IOException("DNS rcode " + (flags & 0x000f));
        int qd = readShort(data, 4);
        int an = readShort(data, 6);
        int pos = 12;
        for (int i = 0; i < qd; i++) {
            pos = skipName(data, pos);
            pos += 4;
            if (pos > data.length) throw new IOException("DNS question rusak");
        }
        ArrayList<InetAddress> addresses = new ArrayList<>();
        for (int i = 0; i < an; i++) {
            pos = skipName(data, pos);
            if (pos + 10 > data.length) throw new IOException("DNS answer rusak");
            int answerType = readShort(data, pos);
            pos += 2;
            int answerClass = readShort(data, pos);
            pos += 2;
            pos += 4;
            int length = readShort(data, pos);
            pos += 2;
            if (pos + length > data.length) throw new IOException("DNS rdata rusak");
            if (answerClass == 1 && answerType == type && ((type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16))) {
                byte[] address = new byte[length];
                System.arraycopy(data, pos, address, 0, length);
                addresses.add(InetAddress.getByAddress(address));
            }
            pos += length;
        }
        return addresses;
    }

    private static int skipName(byte[] data, int pos) throws IOException {
        while (true) {
            if (pos >= data.length) throw new IOException("DNS name rusak");
            int length = data[pos] & 0xff;
            if (length == 0) return pos + 1;
            if ((length & 0xc0) == 0xc0) {
                if (pos + 1 >= data.length) throw new IOException("DNS pointer rusak");
                return pos + 2;
            }
            pos += 1 + length;
        }
    }

    private static int readShort(byte[] data, int pos) {
        return ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }
}
