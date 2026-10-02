package miku.moe.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.color.MaterialColors;

public class ComicasoWebViewActivity extends Activity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ProgressBar progressBar;

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWebViewSystemBars(this);
        String url = normalizeUrl(getIntent().getStringExtra("url"));
        String sourceLabel = getIntent().getStringExtra("sourceLabel");
        setTitle((sourceLabel == null || sourceLabel.trim().isEmpty() ? "Comicaso" : sourceLabel.trim()) + " WebView");
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.getForcedNightColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK));
        root.addView(createToolbar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        swipeRefreshLayout = new SwipeRefreshLayout(this);
        webView = new WebView(this);
        swipeRefreshLayout.addView(webView, new SwipeRefreshLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (webView != null) webView.reload();
            else if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
        });
        swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> webView != null && webView.canScrollVertically(-1));
        root.addView(swipeRefreshLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (progressBar == null) return;
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String finishedUrl) {
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                CookieManager.getInstance().flush();
            }

            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
            }
        });
        webView.loadUrl(url);
    }

    private View createToolbar() {
        FrameLayout toolbar = new FrameLayout(this);
        int surface = ThemeManager.getForcedNightColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK);
        int onSurface = ThemeManager.getForcedNightColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        toolbar.setBackgroundColor(surface);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setColorFilter(onSurface);
        back.setOnClickListener(v -> handleBack());
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        backLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        toolbar.addView(back, backLp);
        TextView title = new TextView(this);
        title.setText("Comicaso WebView");
        title.setTextColor(onSurface);
        title.setTextSize(18f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        titleLp.leftMargin = dp(56);
        titleLp.rightMargin = dp(56);
        toolbar.addView(title, titleLp);
        ImageButton refresh = new ImageButton(this);
        refresh.setImageResource(R.drawable.ic_refresh);
        refresh.setBackgroundColor(Color.TRANSPARENT);
        refresh.setColorFilter(onSurface);
        refresh.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        FrameLayout.LayoutParams refreshLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        refreshLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        toolbar.addView(refresh, refreshLp);
        return toolbar;
    }

    private String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = MangaSettingsManager.getSourceDomain(this, MangaSettingsManager.MANGA_SOURCE_COMICASO);
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
        return value;
    }

    private void handleBack() {
        finish();
    }

    @Override public void onBackPressed() {
        handleBack();
    }

    @Override protected void onPause() {
        CookieManager.getInstance().flush();
        Comicaso.clearSessionCaches();
        super.onPause();
    }

    @Override protected void onDestroy() {
        CookieManager.getInstance().flush();
        Comicaso.clearSessionCaches();
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
