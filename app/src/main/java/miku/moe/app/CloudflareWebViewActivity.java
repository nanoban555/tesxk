package miku.moe.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.color.MaterialColors;
import com.google.gson.JsonParser;
import java.util.Locale;

public class CloudflareWebViewActivity extends Activity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private TextView info;
    private String host;
    private String startUrl;
    private boolean done;
    private boolean verifying;
    private long openedAt;
    private long lastVerifyAt;

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWebViewSystemBars(this);
        startUrl = getIntent().getStringExtra("url");
        host = getIntent().getStringExtra("host");
        String sourceLabel = getIntent().getStringExtra("sourceLabel");
        openedAt = System.currentTimeMillis();
        if (startUrl == null || startUrl.trim().isEmpty()) {
            finish();
            return;
        }
        if (!hasInternet()) {
            if (host != null) CloudflareHelper.failed(host, "Tidak ada koneksi");
            finish();
            return;
        }
        setTitle("Cloudflare " + (sourceLabel == null ? "" : sourceLabel));
        FrameLayout root = new FrameLayout(this);
        int surface = ThemeManager.getForcedNightColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK);
        int surfaceVariant = ThemeManager.getForcedNightColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0xCC000000);
        int onSurface = ThemeManager.getForcedNightColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        root.setBackgroundColor(surface);
        swipeRefreshLayout = new SwipeRefreshLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        info = new TextView(this);
        info.setText("Selesaikan Cloudflare sampai halaman benar-benar terbuka. Jangan tutup halaman ini sebelum verifikasi selesai.");
        info.setTextColor(onSurface);
        info.setTextSize(14f);
        info.setGravity(Gravity.CENTER);
        info.setBackgroundColor(surfaceVariant);
        int pad = dp(12);
        info.setPadding(pad, pad, pad, pad);
        swipeRefreshLayout.addView(webView, new SwipeRefreshLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (webView == null) {
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                return;
            }
            if (info != null) info.setText("Memuat ulang halaman Cloudflare...");
            webView.reload();
        });
        swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> webView != null && webView.canScrollVertically(-1));
        root.addView(swipeRefreshLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        barLp.gravity = Gravity.TOP;
        root.addView(progressBar, barLp);
        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.gravity = Gravity.BOTTOM;
        root.addView(info, infoLp);
        setContentView(root);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        String challengeUa = CloudflareHelper.userAgentForHost(host);
        settings.setUserAgentString(challengeUa);
        CloudflareHelper.setUserAgentForHost(host, challengeUa);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                if (progress >= 100) {
                    if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                    checkSolvedFromWebViewBodyDelayed();
                    checkSolvedDelayed();
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String finishedUrl) {
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                checkSolvedFromWebViewBodyDelayed();
                checkSolvedDelayed();
            }

            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                if (done) return;
                String currentUrl = view == null ? "" : String.valueOf(view.getUrl());
                String failedUrl = failingUrl == null ? "" : failingUrl;
                if (host == null || !failedUrl.contains(host) && !currentUrl.contains(host)) return;
                done = true;
                CloudflareHelper.failed(host, "Tidak ada koneksi");
                finish();
            }
        });
        webView.loadUrl(startUrl);
    }

    private void checkSolvedDelayed() {
        if (webView == null) return;
        webView.postDelayed(this::checkSolved, 1500);
    }

    private void checkSolvedFromWebViewBodyDelayed() {
        if (webView == null) return;
        webView.postDelayed(this::checkSolvedFromWebViewBody, 250);
    }

    private void checkSolvedFromWebViewBody() {
        if (done || host == null || webView == null) return;
        webView.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()", encoded -> {
            if (done || host == null || webView == null) return;
            String currentUrl = webView.getUrl() == null ? "" : webView.getUrl();
            if (currentUrl.isEmpty() || !currentUrl.contains(host)) return;
            String bodyText = decodeJsString(encoded);
            if (!looksLikeSolvedResponse(bodyText)) return;
            done = true;
            if (info != null) info.setText("Cloudflare selesai. Memuat ulang source...");
            CloudflareHelper.cacheSolvedBody(currentUrl, bodyText);
            persistCurrentCookies();
            CookieManager.getInstance().flush();
            CloudflareHelper.solved(host);
            finish();
        });
    }

    private void checkSolved() {
        if (done || verifying || host == null || webView == null) return;
        String currentUrl = webView.getUrl() == null ? "" : webView.getUrl();
        if (!currentUrl.contains(host)) return;
        checkSolvedFromWebViewBody();
        if (done) return;
        String title = webView.getTitle() == null ? "" : webView.getTitle().toLowerCase();
        if (title.contains("too many request") || title.contains("too many requests")) {
            done = true;
            CloudflareHelper.failed(host, "Terlalu banyak request");
            finish();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - openedAt < 3500L || now - lastVerifyAt < 2500L) return;
        CloudflareHelper.persistCookies(currentUrl);
        CloudflareHelper.persistCookies("https://" + host + "/");
        String cookies = CookieManager.getInstance().getCookie("https://" + host + "/");
        boolean hasClearance = cookies != null && cookies.contains("cf_clearance");
        if (!hasClearance && isChallengeTitle(title)) return;
        lastVerifyAt = now;
        verifying = true;
        if (info != null) info.setText("Memeriksa hasil Cloudflare...");
        CookieManager.getInstance().flush();
        CloudflareHelper.verifySolved(startUrl, host, (solved, message) -> {
            verifying = false;
            if (done) return;
            if (solved) {
                done = true;
                CloudflareHelper.solved(host);
                finish();
            } else if (info != null) {
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                info.setText("Cloudflare belum selesai. Selesaikan captcha sampai halaman asli terbuka.");
            }
        });
    }

    @Override public void onBackPressed() {
        persistCurrentCookies();
        if (host != null) CloudflareHelper.keepPending(host);
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        persistCurrentCookies();
        if (!done && host != null) CloudflareHelper.keepPending(host);
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(null);
            swipeRefreshLayout.setOnChildScrollUpCallback(null);
            swipeRefreshLayout = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void persistCurrentCookies() {
        if (webView != null) {
            String currentUrl = webView.getUrl();
            if (currentUrl != null && !currentUrl.trim().isEmpty()) CloudflareHelper.persistCookies(currentUrl);
        }
        if (startUrl != null && !startUrl.trim().isEmpty()) CloudflareHelper.persistCookies(startUrl);
        if (host != null && !host.trim().isEmpty()) CloudflareHelper.persistCookies("https://" + host + "/");
    }

    private boolean isChallengeTitle(String title) {
        return title.contains("just a moment") || title.contains("attention required") || title.contains("cloudflare") || title.contains("checking");
    }

    private String decodeJsString(String encoded) {
        if (encoded == null || encoded.equals("null")) return "";
        try {
            return JsonParser.parseString(encoded).getAsString();
        } catch (Exception ignored) {
            String value = encoded;
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/").trim();
        }
    }

    private boolean looksLikeSolvedResponse(String bodyText) {
        String text = bodyText == null ? "" : bodyText.trim();
        if (text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("cf-browser-verification") || lower.contains("/cdn-cgi/challenge-platform") || lower.contains("cf_clearance") || lower.contains("turnstile") || lower.contains("just a moment") || lower.contains("attention required") || lower.contains("checking your browser")) return false;
        if (!(text.startsWith("{") || text.startsWith("["))) return false;
        return lower.contains("\"items\"") || lower.contains("\"chapters\"") || lower.contains("\"pages\"") || lower.contains("\"coverurl\"") || lower.contains("\"slug\"");
    }

    private boolean hasInternet() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
