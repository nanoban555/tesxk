package miku.moe.app;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

public final class SslTrustHelper {
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
    private static final HostnameVerifier VERIFY_ALL = new HostnameVerifier() {
        @Override public boolean verify(String hostname, SSLSession session) { return true; }
    };

    private SslTrustHelper() {}

    public static OkHttpClient.Builder apply(OkHttpClient.Builder builder) {
        OkHttpClient.Builder target = builder == null ? new OkHttpClient.Builder() : builder;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            target.sslSocketFactory(context.getSocketFactory(), TRUST_ALL);
            target.hostnameVerifier(VERIFY_ALL);
        } catch (Exception ignored) {}
        return target;
    }
}
