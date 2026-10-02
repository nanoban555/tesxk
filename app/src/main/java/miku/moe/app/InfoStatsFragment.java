package miku.moe.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.webkit.MimeTypeMap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import com.google.android.material.tabs.TabLayout;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class InfoStatsFragment extends Fragment {
    private View statsView, settingsView, downloaderView, devView;
    private TextView statsText, downloaderStatusText, downloaderTitleText, mangaCacheSizeText, uiSettingsTitle, animeSettingsTitle, mangaSettingsTitle, httpsSettingsTitle;
    private ImageView uiSettingsArrow, animeSettingsArrow, mangaSettingsArrow, httpsSettingsArrow;
    private RadioGroup uiThemeRadioGroup, qualityRadioGroup, externalPlayerRadioGroup, animeHomeStyleRadioGroup, mangaHomeStyleRadioGroup, mangaGridRadioGroup, chapterLayoutRadioGroup, mangaReaderUiRadioGroup, mangaDetailUiRadioGroup, mangaReaderImageScaleRadioGroup, downloaderPlatformGroup, dohProviderRadioGroup;
    private LinearLayout mangaSourceCheckGroup, animeSourceCheckGroup, uiSettingsContent, animeSettingsContent, mangaSettingsContent, httpsSettingsContent;
    private CheckBox animeSourceDefaultCheck, animeSourceAnimekuCheck, animeSourceAnimeloverzCheck, animeSourceDramoraCheck, animeSourceAnichinCheck, mangaSourceKomikcastCheck, mangaSourceShinigamiCheck, mangaSourceDoujindesuCheck, mangaSourceWestmangaCheck, mangaSourceBacakomikCheck, mangaSourceKomikindoCheck, mangaSourceIkiruCheck, mangaSourceKomikuCheck, mangaSourceMangasusuCheck, mangaSourceKomikuOrgCheck, mangaSourceCosmicScansCheck, mangaSourceKiryuuOfficialCheck, mangaSourceNatsuCheck, mangaSourceAinzscanssCheck, mangaSourceApkomikCheck, mangaSourceComicasoCheck, mangaSourceKumoPoiCheck, mangaSourceMgkomikCheck, mangaSourceCrotpediaCheck, mangaSourceNgomikCheck, mangaSourceKomikTapCheck, mangaSourceManhwaIndoCheck, mangaSourceSoulScansCheck, mangaSourceKuromangaCheck, mangaSourceIsekaiKomikCheck, mangaSourceLuvyaaCheck, mangaSourceSekteDoujinCheck;
    private SwitchMaterial mangaModeSwitch, autoLandscapeSwitch, mangaReaderZoomSwitch, mangaReaderPhotoViewZoomSwitch, mangaReaderDoubleTapZoomSwitch, mangaReaderCropBorderSwitch, mangaReaderPageTransitionSwitch, mangaReaderInlinePreloadSwitch, mangaReaderNextChapterAutoSwitch, mangaReaderNextChapterManualSwitch, mangaAutoSaveImageSwitch, hideAnimeLatestEpisodeLabelSwitch, hideMangaLatestChapterLabelSwitch, hideMangaTypeLabelSwitch, hideAllMangaLabelsSwitch, compactMangaDataUiSwitch, boldMangaTitleSwitch;
    private EditText downloaderInput, mangaReaderNextChapterThresholdInput, mangaReaderNextChapterDurationInput;
    private ImageView downloaderThumbnail;
    private FrameLayout downloaderPreviewFrame;
    private PlayerView downloaderVideoPreview;
    private RadioGroup videoScaleRadioGroup;
    private HorizontalScrollView downloaderPhotoScroll;
    private LinearLayout downloaderPhotoList;
    private LinearLayout downloaderResultList;
    private ExoPlayer downloaderPlayer;
    private ProgressBar downloaderProgress;
    private MaterialButton downloaderFetchButton, clearMangaCacheButton, animeHomeSourceButton, mangaHomeSourceButton;
    private final DownloaderCookieJar downloaderCookieJar = new DownloaderCookieJar();
    private final OkHttpClient downloaderClient = new OkHttpClient.Builder().cookieJar(downloaderCookieJar).followRedirects(true).followSslRedirects(true).build();
    private boolean updatingMangaReaderPreloadSwitches, updatingMangaReaderZoomSwitches, updatingMangaDetailUiOptions;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_info_stats, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TabLayout tabs = view.findViewById(R.id.infoTabLayout);
        statsView = view.findViewById(R.id.statsContainer);
        settingsView = view.findViewById(R.id.settingsContainer);
        downloaderView = view.findViewById(R.id.downloaderContainer);
        devView = view.findViewById(R.id.developerContainer);
        statsText = view.findViewById(R.id.statsTextView);
        uiSettingsContent = view.findViewById(R.id.uiSettingsContent);
        animeSettingsContent = view.findViewById(R.id.animeSettingsContent);
        mangaSettingsContent = view.findViewById(R.id.mangaSettingsContent);
        httpsSettingsContent = view.findViewById(R.id.httpsSettingsContent);
        uiSettingsTitle = view.findViewById(R.id.uiSettingsTitle);
        animeSettingsTitle = view.findViewById(R.id.animeSettingsTitle);
        mangaSettingsTitle = view.findViewById(R.id.mangaSettingsTitle);
        httpsSettingsTitle = view.findViewById(R.id.httpsSettingsTitle);
        uiSettingsArrow = view.findViewById(R.id.uiSettingsArrow);
        animeSettingsArrow = view.findViewById(R.id.animeSettingsArrow);
        mangaSettingsArrow = view.findViewById(R.id.mangaSettingsArrow);
        httpsSettingsArrow = view.findViewById(R.id.httpsSettingsArrow);
        mangaCacheSizeText = view.findViewById(R.id.mangaCacheSizeText);
        clearMangaCacheButton = view.findViewById(R.id.clearMangaCacheButton);
        uiThemeRadioGroup = view.findViewById(R.id.uiThemeRadioGroup);
        animeSourceCheckGroup = view.findViewById(R.id.animeSourceCheckGroup);
        animeSourceDefaultCheck = view.findViewById(R.id.animeSourceDefaultCheck);
        animeSourceAnimekuCheck = view.findViewById(R.id.animeSourceAnimekuCheck);
        animeSourceAnimeloverzCheck = view.findViewById(R.id.animeSourceAnimeloverzCheck);
        animeSourceDramoraCheck = view.findViewById(R.id.animeSourceDramoraCheck);
        animeSourceAnichinCheck = view.findViewById(R.id.animeSourceAnichinCheck);
        animeHomeStyleRadioGroup = view.findViewById(R.id.animeHomeStyleRadioGroup);
        animeHomeSourceButton = view.findViewById(R.id.animeHomeSourceButton);
        hideAnimeLatestEpisodeLabelSwitch = view.findViewById(R.id.hideAnimeLatestEpisodeLabelSwitch);
        qualityRadioGroup = view.findViewById(R.id.qualityRadioGroup);
        externalPlayerRadioGroup = view.findViewById(R.id.externalPlayerRadioGroup);
        videoScaleRadioGroup = view.findViewById(R.id.videoScaleRadioGroup);
        autoLandscapeSwitch = view.findViewById(R.id.autoLandscapeSwitch);
        mangaHomeStyleRadioGroup = view.findViewById(R.id.mangaHomeStyleRadioGroup);
        mangaGridRadioGroup = view.findViewById(R.id.mangaGridRadioGroup);
        chapterLayoutRadioGroup = view.findViewById(R.id.chapterLayoutRadioGroup);
        mangaReaderUiRadioGroup = view.findViewById(R.id.mangaReaderUiRadioGroup);
        mangaDetailUiRadioGroup = view.findViewById(R.id.mangaDetailUiRadioGroup);
        mangaReaderZoomSwitch = view.findViewById(R.id.mangaReaderZoomSwitch);
        mangaReaderPhotoViewZoomSwitch = view.findViewById(R.id.mangaReaderPhotoViewZoomSwitch);
        mangaReaderDoubleTapZoomSwitch = view.findViewById(R.id.mangaReaderDoubleTapZoomSwitch);
        mangaReaderCropBorderSwitch = view.findViewById(R.id.mangaReaderCropBorderSwitch);
        mangaReaderImageScaleRadioGroup = view.findViewById(R.id.mangaReaderImageScaleRadioGroup);
        mangaReaderPageTransitionSwitch = view.findViewById(R.id.mangaReaderPageTransitionSwitch);
        mangaReaderInlinePreloadSwitch = view.findViewById(R.id.mangaReaderInlinePreloadSwitch);
        mangaReaderNextChapterAutoSwitch = view.findViewById(R.id.mangaReaderNextChapterAutoSwitch);
        mangaReaderNextChapterManualSwitch = view.findViewById(R.id.mangaReaderNextChapterManualSwitch);
        mangaReaderNextChapterThresholdInput = view.findViewById(R.id.mangaReaderNextChapterThresholdInput);
        mangaReaderNextChapterDurationInput = view.findViewById(R.id.mangaReaderNextChapterDurationInput);
        mangaAutoSaveImageSwitch = view.findViewById(R.id.mangaAutoSaveImageSwitch);
        hideMangaLatestChapterLabelSwitch = view.findViewById(R.id.hideMangaLatestChapterLabelSwitch);
        hideMangaTypeLabelSwitch = view.findViewById(R.id.hideMangaTypeLabelSwitch);
        hideAllMangaLabelsSwitch = view.findViewById(R.id.hideAllMangaLabelsSwitch);
        compactMangaDataUiSwitch = view.findViewById(R.id.compactMangaDataUiSwitch);
        boldMangaTitleSwitch = view.findViewById(R.id.boldMangaTitleSwitch);
        mangaSourceCheckGroup = view.findViewById(R.id.mangaSourceCheckGroup);
        mangaHomeSourceButton = view.findViewById(R.id.mangaHomeSourceButton);
        mangaSourceKomikcastCheck = view.findViewById(R.id.mangaSourceKomikcastRadio);
        mangaSourceShinigamiCheck = view.findViewById(R.id.mangaSourceShinigamiRadio);
        mangaSourceDoujindesuCheck = view.findViewById(R.id.mangaSourceDoujindesuRadio);
        mangaSourceWestmangaCheck = view.findViewById(R.id.mangaSourceWestmangaRadio);
        mangaSourceBacakomikCheck = view.findViewById(R.id.mangaSourceBacakomikRadio);
        mangaSourceKomikindoCheck = view.findViewById(R.id.mangaSourceKomikindoRadio);
        mangaSourceIkiruCheck = view.findViewById(R.id.mangaSourceIkiruRadio);
        mangaSourceKomikuCheck = view.findViewById(R.id.mangaSourceKomikuRadio);
        mangaSourceMangasusuCheck = view.findViewById(R.id.mangaSourceMangasusuRadio);
        mangaSourceKomikuOrgCheck = view.findViewById(R.id.mangaSourceKomikuOrgRadio);
        mangaSourceCosmicScansCheck = view.findViewById(R.id.mangaSourceCosmicScansRadio);
        mangaSourceKiryuuOfficialCheck = view.findViewById(R.id.mangaSourceKiryuuOfficialRadio);
        mangaSourceNatsuCheck = view.findViewById(R.id.mangaSourceNatsuRadio);
        mangaSourceAinzscanssCheck = view.findViewById(R.id.mangaSourceAinzscanssRadio);
        mangaSourceApkomikCheck = view.findViewById(R.id.mangaSourceApkomikRadio);
        mangaSourceComicasoCheck = view.findViewById(R.id.mangaSourceComicasoRadio);
        mangaSourceKumoPoiCheck = view.findViewById(R.id.mangaSourceKumoPoiRadio);
        mangaSourceMgkomikCheck = view.findViewById(R.id.mangaSourceMgkomikRadio);
        mangaSourceCrotpediaCheck = view.findViewById(R.id.mangaSourceCrotpediaRadio);
        mangaSourceNgomikCheck = view.findViewById(R.id.mangaSourceNgomikRadio);
        mangaSourceKomikTapCheck = view.findViewById(R.id.mangaSourceKomikTapRadio);
        mangaSourceManhwaIndoCheck = view.findViewById(R.id.mangaSourceManhwaIndoRadio);
        mangaSourceSoulScansCheck = view.findViewById(R.id.mangaSourceSoulScansRadio);
        mangaSourceKuromangaCheck = view.findViewById(R.id.mangaSourceKuromangaRadio);
        mangaSourceIsekaiKomikCheck = view.findViewById(R.id.mangaSourceIsekaiKomikRadio);
        mangaSourceLuvyaaCheck = view.findViewById(R.id.mangaSourceLuvyaaRadio);
        mangaSourceSekteDoujinCheck = view.findViewById(R.id.mangaSourceSekteDoujinRadio);
        mangaModeSwitch = view.findViewById(R.id.mangaModeSwitch);
        downloaderPlatformGroup = view.findViewById(R.id.downloaderPlatformGroup);
        dohProviderRadioGroup = view.findViewById(R.id.dohProviderRadioGroup);
        downloaderInput = view.findViewById(R.id.downloaderInput);
        downloaderFetchButton = view.findViewById(R.id.downloaderFetchButton);
        downloaderStatusText = view.findViewById(R.id.downloaderStatusText);
        downloaderTitleText = view.findViewById(R.id.downloaderTitleText);
        downloaderThumbnail = view.findViewById(R.id.downloaderThumbnail);
        downloaderPreviewFrame = view.findViewById(R.id.downloaderPreviewFrame);
        downloaderVideoPreview = view.findViewById(R.id.downloaderVideoPreview);
        downloaderPhotoScroll = view.findViewById(R.id.downloaderPhotoScroll);
        downloaderPhotoList = view.findViewById(R.id.downloaderPhotoList);
        downloaderResultList = view.findViewById(R.id.downloaderResultList);
        downloaderProgress = view.findViewById(R.id.downloaderProgress);
        setupCollapsibleSettings(view);
        setupUiThemeOptions();
        setupAnimeSourceOptions();
        setupAnimeHomeStyleOption();
        setupAnimeHomeSourceOption();
        setupAnimeLabelVisibilityOption();
        setupQualityOptions();
        setupExternalPlayerOptions();
        setupVideoScaleOption();
        setupAutoLandscapeOption();
        setupMangaModeOption();
        setupMangaHomeStyleOption();
        setupMangaCacheOptions();
        setupMangaAutoSaveImageOption();
        setupMangaLabelVisibilityOptions();
        setupCompactMangaDataUiOption();
        setupBoldMangaTitleOption();
        setupMangaHomeSourceOption();
        setupMangaSourceOptions();
        setupMangaLayoutOptions();
        setupMangaDetailUiOptions();
        setupMangaReaderUiOptions();
        setupMangaReaderZoomOption();
        setupMangaReaderPhotoViewZoomOption();
        setupMangaReaderDoubleTapZoomOption();
        setupMangaReaderCropBorderOption();
        setupMangaReaderImageScaleOption();
        setupMangaReaderPageTransitionOption();
        setupMangaReaderInlinePreloadOption();
        setupMangaReaderNextChapterOptions();
        setupDohProviderOptions();
        setupDownloader();
        TabLayout.Tab statisticsTab = tabs.newTab().setText("Statistik");
        tabs.addTab(statisticsTab);
        tabs.addTab(tabs.newTab().setText("Pengaturan"));
        tabs.addTab(tabs.newTab().setText("Downloader"));
        tabs.addTab(tabs.newTab().setText("Developer"));
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabs.setTabGravity(TabLayout.GRAVITY_CENTER);
        hideTab(tabs, statisticsTab.getPosition());
        tabs.post(() -> hideTab(tabs, statisticsTab.getPosition()));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { show(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) { refreshStats(); }
        });
        view.findViewById(R.id.channelButton).setOnClickListener(v -> open("https://t.me/zksoqpwjsj"));
        view.findViewById(R.id.developerButton).setOnClickListener(v -> open("https://t.me/Miku01v"));
        refreshStats();
        TabLayout.Tab settingsTab = tabs.getTabAt(1);
        if (settingsTab != null) settingsTab.select();
        show(1);
    }

    private void hideTab(TabLayout tabs, int position) {
        if (tabs == null || position < 0) return;
        View tabStrip = tabs.getChildAt(0);
        if (!(tabStrip instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) tabStrip;
        if (position >= group.getChildCount()) return;
        View tabView = group.getChildAt(position);
        tabView.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = tabView.getLayoutParams();
        if (params != null) {
            params.width = 0;
            tabView.setLayoutParams(params);
        }
        tabView.requestLayout();
        tabs.requestLayout();
    }

    private void setupCollapsibleSettings(View root) {
        MaterialCardView uiHeader = root.findViewById(R.id.uiSettingsHeader);
        MaterialCardView animeHeader = root.findViewById(R.id.animeSettingsHeader);
        MaterialCardView mangaHeader = root.findViewById(R.id.mangaSettingsHeader);
        MaterialCardView httpsHeader = root.findViewById(R.id.httpsSettingsHeader);
        setSettingsSectionExpanded(uiHeader, uiSettingsContent, uiSettingsArrow, false);
        setSettingsSectionExpanded(animeHeader, animeSettingsContent, animeSettingsArrow, false);
        setSettingsSectionExpanded(mangaHeader, mangaSettingsContent, mangaSettingsArrow, false);
        setSettingsSectionExpanded(httpsHeader, httpsSettingsContent, httpsSettingsArrow, false);
        View.OnClickListener uiToggle = v -> setSettingsSectionExpanded(uiHeader, uiSettingsContent, uiSettingsArrow, uiSettingsContent == null || uiSettingsContent.getVisibility() != View.VISIBLE);
        View.OnClickListener animeToggle = v -> setSettingsSectionExpanded(animeHeader, animeSettingsContent, animeSettingsArrow, animeSettingsContent == null || animeSettingsContent.getVisibility() != View.VISIBLE);
        View.OnClickListener mangaToggle = v -> setSettingsSectionExpanded(mangaHeader, mangaSettingsContent, mangaSettingsArrow, mangaSettingsContent == null || mangaSettingsContent.getVisibility() != View.VISIBLE);
        View.OnClickListener httpsToggle = v -> setSettingsSectionExpanded(httpsHeader, httpsSettingsContent, httpsSettingsArrow, httpsSettingsContent == null || httpsSettingsContent.getVisibility() != View.VISIBLE);
        if (uiHeader != null) uiHeader.setOnClickListener(uiToggle);
        if (animeHeader != null) animeHeader.setOnClickListener(animeToggle);
        if (mangaHeader != null) mangaHeader.setOnClickListener(mangaToggle);
        if (httpsHeader != null) httpsHeader.setOnClickListener(httpsToggle);
        if (uiSettingsTitle != null) uiSettingsTitle.setOnClickListener(uiToggle);
        if (animeSettingsTitle != null) animeSettingsTitle.setOnClickListener(animeToggle);
        if (mangaSettingsTitle != null) mangaSettingsTitle.setOnClickListener(mangaToggle);
        if (httpsSettingsTitle != null) httpsSettingsTitle.setOnClickListener(httpsToggle);
    }

    private void setSettingsSectionExpanded(MaterialCardView header, LinearLayout content, ImageView indicator, boolean expanded) {
        if (content != null) content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (indicator != null) {
            indicator.setVisibility(View.VISIBLE);
            indicator.setImageResource(expanded ? R.drawable.ic_settings_arrow_down : R.drawable.ic_settings_arrow_right);
        }
        applySettingsGroupShape(header, content, expanded);
    }

    private void applySettingsGroupShape(MaterialCardView header, LinearLayout content, boolean expanded) {
        float outerRadius = dpToPx(26);
        float innerRadius = dpToPx(24);
        if (header != null) {
            int id = header.getId();
            if (id == R.id.uiSettingsHeader) {
                header.setShapeAppearanceModel(settingsShape(outerRadius, outerRadius, 0f, 0f));
            } else if (id == R.id.httpsSettingsHeader) {
                header.setShapeAppearanceModel(settingsShape(0f, 0f, outerRadius, outerRadius));
            } else {
                header.setShapeAppearanceModel(settingsShape(0f, 0f, 0f, 0f));
            }
            header.setCardElevation(0f);
            header.setStrokeWidth(0);
        }
        if (content == null) return;
        int cardCount = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof MaterialCardView) cardCount++;
        }
        int cardPosition = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!(child instanceof MaterialCardView)) continue;
            MaterialCardView card = (MaterialCardView) child;
            boolean first = cardPosition == 0;
            boolean last = cardPosition == cardCount - 1;
            float topLeft = first ? innerRadius : 0f;
            float topRight = first ? innerRadius : 0f;
            float bottomRight = last ? innerRadius : 0f;
            float bottomLeft = last ? innerRadius : 0f;
            card.setShapeAppearanceModel(settingsShape(topLeft, topRight, bottomRight, bottomLeft));
            card.setCardElevation(0f);
            card.setStrokeWidth(0);
            ViewGroup.LayoutParams params = card.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                margins.topMargin = first ? dpToPx(8) : dpToPx(1);
                margins.bottomMargin = last ? dpToPx(8) : 0;
                card.setLayoutParams(margins);
            }
            cardPosition++;
        }
    }

    private ShapeAppearanceModel settingsShape(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        return ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(topLeft)
                .setTopRightCornerSize(topRight)
                .setBottomRightCornerSize(bottomRight)
                .setBottomLeftCornerSize(bottomLeft)
                .build();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void setupDohProviderOptions() {
        if (dohProviderRadioGroup == null) return;
        int provider = MangaSettingsManager.getDohProvider(requireContext());
        if (provider == MangaSettingsManager.DOH_CLOUDFLARE) dohProviderRadioGroup.check(R.id.dohCloudflareRadio);
        else if (provider == MangaSettingsManager.DOH_GOOGLE) dohProviderRadioGroup.check(R.id.dohGoogleRadio);
        else if (provider == MangaSettingsManager.DOH_ADGUARD) dohProviderRadioGroup.check(R.id.dohAdGuardRadio);
        else if (provider == MangaSettingsManager.DOH_QUAD9) dohProviderRadioGroup.check(R.id.dohQuad9Radio);
        else if (provider == MangaSettingsManager.DOH_ALIDNS) dohProviderRadioGroup.check(R.id.dohAliDnsRadio);
        else if (provider == MangaSettingsManager.DOH_DNSPOD) dohProviderRadioGroup.check(R.id.dohDnsPodRadio);
        else if (provider == MangaSettingsManager.DOH_360) dohProviderRadioGroup.check(R.id.doh360Radio);
        else if (provider == MangaSettingsManager.DOH_QUAD101) dohProviderRadioGroup.check(R.id.dohQuad101Radio);
        else if (provider == MangaSettingsManager.DOH_MULLVAD) dohProviderRadioGroup.check(R.id.dohMullvadRadio);
        else if (provider == MangaSettingsManager.DOH_CONTROLD) dohProviderRadioGroup.check(R.id.dohControlDRadio);
        else if (provider == MangaSettingsManager.DOH_NJALLA) dohProviderRadioGroup.check(R.id.dohNjallaRadio);
        else if (provider == MangaSettingsManager.DOH_SHECAN) dohProviderRadioGroup.check(R.id.dohShecanRadio);
        else dohProviderRadioGroup.check(R.id.dohDisabledRadio);
        dohProviderRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int selected = MangaSettingsManager.DOH_DISABLED;
            if (checkedId == R.id.dohCloudflareRadio) selected = MangaSettingsManager.DOH_CLOUDFLARE;
            else if (checkedId == R.id.dohGoogleRadio) selected = MangaSettingsManager.DOH_GOOGLE;
            else if (checkedId == R.id.dohAdGuardRadio) selected = MangaSettingsManager.DOH_ADGUARD;
            else if (checkedId == R.id.dohQuad9Radio) selected = MangaSettingsManager.DOH_QUAD9;
            else if (checkedId == R.id.dohAliDnsRadio) selected = MangaSettingsManager.DOH_ALIDNS;
            else if (checkedId == R.id.dohDnsPodRadio) selected = MangaSettingsManager.DOH_DNSPOD;
            else if (checkedId == R.id.doh360Radio) selected = MangaSettingsManager.DOH_360;
            else if (checkedId == R.id.dohQuad101Radio) selected = MangaSettingsManager.DOH_QUAD101;
            else if (checkedId == R.id.dohMullvadRadio) selected = MangaSettingsManager.DOH_MULLVAD;
            else if (checkedId == R.id.dohControlDRadio) selected = MangaSettingsManager.DOH_CONTROLD;
            else if (checkedId == R.id.dohNjallaRadio) selected = MangaSettingsManager.DOH_NJALLA;
            else if (checkedId == R.id.dohShecanRadio) selected = MangaSettingsManager.DOH_SHECAN;
            MangaSettingsManager.setDohProvider(requireContext(), selected);
            Toast.makeText(requireContext(), "HTTPS / DoH: " + MangaSettingsManager.getDohProviderLabel(requireContext()), Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupUiThemeOptions() {
        if (uiThemeRadioGroup == null) return;
        uiThemeRadioGroup.setOnCheckedChangeListener(null);
        uiThemeRadioGroup.removeAllViews();
        String theme = ThemeManager.getTheme(requireContext());
        String[] values = ThemeManager.getThemeValues();
        String[] labels = ThemeManager.getThemeLabels();
        for (int i = 0; i < values.length; i++) {
            RadioButton radioButton = new RadioButton(requireContext());
            radioButton.setId(View.generateViewId());
            radioButton.setTag(values[i]);
            radioButton.setText(labels[i]);
            radioButton.setLayoutParams(new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
            uiThemeRadioGroup.addView(radioButton);
            if (values[i].equals(theme)) radioButton.setChecked(true);
        }
        uiThemeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checkedView = group.findViewById(checkedId);
            if (checkedView == null || checkedView.getTag() == null) return;
            String selected = checkedView.getTag().toString();
            if (selected.equals(ThemeManager.getTheme(requireContext()))) return;
            ThemeManager.setTheme(requireContext(), selected);
            Toast.makeText(requireContext(), "Tema: " + ThemeManager.getThemeLabel(requireContext()), Toast.LENGTH_SHORT).show();
            android.app.Activity activity = getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.getWindow().getDecorView().post(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
            });
        });
    }

    private void setupMangaLabelVisibilityOptions() {
        if (hideMangaLatestChapterLabelSwitch != null) {
            hideMangaLatestChapterLabelSwitch.setChecked(MangaSettingsManager.isHideLatestChapterLabelEnabled(requireContext()));
            hideMangaLatestChapterLabelSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                MangaSettingsManager.setHideLatestChapterLabelEnabled(requireContext(), isChecked);
                Toast.makeText(requireContext(), isChecked ? "Label chapter terbaru disembunyikan" : "Label chapter terbaru ditampilkan", Toast.LENGTH_SHORT).show();
                refreshMangaSourceSettings();
                refreshStats();
            });
        }
        if (hideMangaTypeLabelSwitch != null) {
            hideMangaTypeLabelSwitch.setChecked(MangaSettingsManager.isHideTypeLabelEnabled(requireContext()));
            hideMangaTypeLabelSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                MangaSettingsManager.setHideTypeLabelEnabled(requireContext(), isChecked);
                Toast.makeText(requireContext(), isChecked ? "Label manga/manhua/manhwa disembunyikan" : "Label manga/manhua/manhwa ditampilkan", Toast.LENGTH_SHORT).show();
                refreshMangaSourceSettings();
                refreshStats();
            });
        }
        if (hideAllMangaLabelsSwitch != null) {
            hideAllMangaLabelsSwitch.setChecked(MangaSettingsManager.isHideAllMangaLabelsEnabled(requireContext()));
            hideAllMangaLabelsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                MangaSettingsManager.setHideAllMangaLabelsEnabled(requireContext(), isChecked);
                Toast.makeText(requireContext(), isChecked ? "Label chapter terbaru disembunyikan" : "Label chapter terbaru mengikuti pengaturan", Toast.LENGTH_SHORT).show();
                refreshMangaSourceSettings();
                refreshStats();
            });
        }
    }

    private void setupCompactMangaDataUiOption() {
        if (compactMangaDataUiSwitch == null) return;
        compactMangaDataUiSwitch.setChecked(MangaSettingsManager.isCompactMangaDataUiEnabled(requireContext()));
        compactMangaDataUiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setCompactMangaDataUiEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "UI data manga di dalam cover aktif" : "UI data manga normal", Toast.LENGTH_SHORT).show();
            refreshMangaRoots();
            refreshStats();
        });
    }

    private void setupBoldMangaTitleOption() {
        if (boldMangaTitleSwitch == null) return;
        boldMangaTitleSwitch.setChecked(MangaSettingsManager.isBoldMangaTitleEnabled(requireContext()));
        boldMangaTitleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setBoldMangaTitleEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Judul manga bold aktif" : "Judul manga bold mati", Toast.LENGTH_SHORT).show();
            refreshMangaRoots();
            refreshStats();
        });
    }

    private void setupMangaAutoSaveImageOption() {
        if (mangaAutoSaveImageSwitch == null) return;
        mangaAutoSaveImageSwitch.setChecked(MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(requireContext()));
        mangaAutoSaveImageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setAutoSaveFavoriteHistoryImagesEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Auto save image favorite & history aktif" : "Auto save image favorite & history mati", Toast.LENGTH_SHORT).show();
            if (isChecked) autoSaveFavoriteHistoryImagesNow();
            refreshMangaCacheSize();
            refreshStats();
        });
    }

    private void setupMangaCacheOptions() {
        refreshMangaCacheSize();
        if (clearMangaCacheButton != null) clearMangaCacheButton.setOnClickListener(v -> showClearMangaCacheDialog());
    }

    private void showClearMangaCacheDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Cache")
                .setMessage("Hapus cache manga yang tersimpan?")
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya", (dialog, which) -> clearMangaCache())
                .show();
    }

    private void clearMangaCache() {
        Context app = requireContext().getApplicationContext();
        if (clearMangaCacheButton != null) clearMangaCacheButton.setEnabled(false);
        new Thread(() -> {
            MangaCacheController.clearAllMangaCache(app);
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (clearMangaCacheButton != null) clearMangaCacheButton.setEnabled(true);
                Toast.makeText(requireContext(), "Cache manga berhasil dibersihkan", Toast.LENGTH_SHORT).show();
                refreshMangaCacheSize();
            });
        }, "MangaCacheClearer").start();
    }

    private void refreshMangaCacheSize() {
        if (mangaCacheSizeText == null || !isAdded()) return;
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            long size = MangaCacheController.totalCacheBytes(app);
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (isAdded() && mangaCacheSizeText != null) mangaCacheSizeText.setText("Total Cache: " + formatBytes(size));
            });
        }, "MangaCacheSizer").start();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024d) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024d) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024d);
    }

    private void autoSaveFavoriteHistoryImagesNow() {
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            for (MangaPost post : MangaFavoriteManager.getFavorites(app)) {
                if (post != null && post.coverImage != null && !post.coverImage.trim().isEmpty()) MangaCoverCache.saveAsync(app, post.coverImage, post.getSourceId());
            }
            for (MangaHistoryManager.Entry entry : MangaHistoryManager.entries(app)) {
                if (entry != null && entry.manga != null && entry.manga.coverImage != null && !entry.manga.coverImage.trim().isEmpty()) MangaCoverCache.saveAsync(app, entry.manga.coverImage, entry.manga.getSourceId());
            }
        }, "MangaAutoSaveImages").start();
    }

    private void setupAnimeHomeStyleOption() {
        if (animeHomeStyleRadioGroup == null) return;
        animeHomeStyleRadioGroup.setOnCheckedChangeListener(null);
        animeHomeStyleRadioGroup.check(AnimeSettingsManager.isHomeStyleV1(requireContext()) ? R.id.animeHomeStyleV1Radio : R.id.animeHomeStyleDefaultRadio);
        animeHomeStyleRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = checkedId == R.id.animeHomeStyleV1Radio ? AnimeSettingsManager.HOME_STYLE_V1 : AnimeSettingsManager.HOME_STYLE_DEFAULT;
            AnimeSettingsManager.setHomeStyle(requireContext(), selected);
            Toast.makeText(requireContext(), AnimeSettingsManager.getHomeStyleLabel(requireContext()), Toast.LENGTH_SHORT).show();
            refreshStats();
            refreshAnimeHomeSettings();
        });
    }

    private void setupAnimeHomeSourceOption() {
        updateAnimeHomeSourceButton();
        if (animeHomeSourceButton != null) animeHomeSourceButton.setOnClickListener(v -> showAnimeHomeSourceDialog());
    }

    private void setupAnimeLabelVisibilityOption() {
        if (hideAnimeLatestEpisodeLabelSwitch == null) return;
        hideAnimeLatestEpisodeLabelSwitch.setChecked(AnimeSettingsManager.isHideLatestEpisodeLabelEnabled(requireContext()));
        hideAnimeLatestEpisodeLabelSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AnimeSettingsManager.setHideLatestEpisodeLabelEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Label episode terbaru disembunyikan" : "Label episode terbaru ditampilkan", Toast.LENGTH_SHORT).show();
            refreshAnimeHomeSettings();
            refreshStats();
        });
    }

    private void showAnimeHomeSourceDialog() {
        if (!isAdded()) return;
        String[] sourceIds = AnimeSettingsManager.allSourceIds();
        String[] labels = new String[sourceIds.length];
        int checked = 0;
        String current = AnimeSettingsManager.getHomeV1Source(requireContext());
        for (int i = 0; i < sourceIds.length; i++) {
            labels[i] = AnimeSettingsManager.labelForSourceId(sourceIds[i]);
            if (sourceIds[i].equals(current)) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Source Home Anime v1")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which < 0 || which >= sourceIds.length) return;
                    AnimeSettingsManager.setHomeV1Source(requireContext(), sourceIds[which]);
                    updateAnimeHomeSourceButton();
                    refreshStats();
                    refreshAnimeHomeSettings();
                    dialog.dismiss();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void updateAnimeHomeSourceButton() {
        if (animeHomeSourceButton == null || !isAdded()) return;
        animeHomeSourceButton.setText("Source Home Anime v1: " + AnimeSettingsManager.getHomeV1SourceLabel(requireContext()));
    }

    private void refreshAnimeHomeSettings() {
        if (getActivity() instanceof MainActivity && getView() != null) getView().post(() -> ((MainActivity) getActivity()).refreshAnimeHomeSettings());
    }

    private void setupAnimeSourceOptions() {
        if (animeSourceDefaultCheck == null || animeSourceAnimekuCheck == null || animeSourceAnimeloverzCheck == null || animeSourceDramoraCheck == null || animeSourceAnichinCheck == null) return;
        syncAnimeSourceChecks();
        animeSourceDefaultCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updateAnimeSourceEnabled(AnimeSettingsManager.SOURCE_DEFAULT, isChecked));
        animeSourceAnimekuCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updateAnimeSourceEnabled(AnimeSettingsManager.SOURCE_ANIMEKU, isChecked));
        animeSourceAnimeloverzCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updateAnimeSourceEnabled(AnimeSettingsManager.SOURCE_ANIMELOVERZ, isChecked));
        animeSourceDramoraCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updateAnimeSourceEnabled(AnimeSettingsManager.SOURCE_DRAMORA, isChecked));
        animeSourceAnichinCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updateAnimeSourceEnabled(AnimeSettingsManager.SOURCE_ANICHIN, isChecked));
    }

    private void syncAnimeSourceChecks() {
        if (animeSourceDefaultCheck != null) animeSourceDefaultCheck.setChecked(AnimeSettingsManager.isAnimeSourceEnabled(requireContext(), AnimeSettingsManager.SOURCE_DEFAULT));
        if (animeSourceAnimekuCheck != null) animeSourceAnimekuCheck.setChecked(AnimeSettingsManager.isAnimeSourceEnabled(requireContext(), AnimeSettingsManager.SOURCE_ANIMEKU));
        if (animeSourceAnimeloverzCheck != null) animeSourceAnimeloverzCheck.setChecked(AnimeSettingsManager.isAnimeSourceEnabled(requireContext(), AnimeSettingsManager.SOURCE_ANIMELOVERZ));
        if (animeSourceDramoraCheck != null) animeSourceDramoraCheck.setChecked(AnimeSettingsManager.isAnimeSourceEnabled(requireContext(), AnimeSettingsManager.SOURCE_DRAMORA));
        if (animeSourceAnichinCheck != null) animeSourceAnichinCheck.setChecked(AnimeSettingsManager.isAnimeSourceEnabled(requireContext(), AnimeSettingsManager.SOURCE_ANICHIN));
    }

    private void updateAnimeSourceEnabled(String source, boolean enabled) {
        AnimeSettingsManager.setAnimeSourceEnabled(requireContext(), source, enabled);
        syncAnimeSourceChecks();
        Toast.makeText(requireContext(), "Source anime aktif: " + AnimeSettingsManager.getAnimeSourceLabel(requireContext()), Toast.LENGTH_SHORT).show();
        refreshStats();
        updateAnimeHomeSourceButton();
        refreshAnimeHomeSettings();
    }

    private void setupQualityOptions() {
        if (qualityRadioGroup == null) return;
        String quality = PlaybackQualityManager.getQuality(requireContext());
        if (PlaybackQualityManager.QUALITY_SD.equals(quality)) qualityRadioGroup.check(R.id.qualitySdRadio);
        else if (PlaybackQualityManager.QUALITY_FHD.equals(quality)) qualityRadioGroup.check(R.id.qualityFhdRadio);
        else qualityRadioGroup.check(R.id.qualityHdRadio);

        qualityRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = PlaybackQualityManager.QUALITY_HD;
            if (checkedId == R.id.qualitySdRadio) selected = PlaybackQualityManager.QUALITY_SD;
            else if (checkedId == R.id.qualityFhdRadio) selected = PlaybackQualityManager.QUALITY_FHD;
            PlaybackQualityManager.setQuality(requireContext(), selected);
            Toast.makeText(requireContext(), "Resolusi default: " + PlaybackQualityManager.getQualityLabel(selected), Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupExternalPlayerOptions() {
        if (externalPlayerRadioGroup == null) return;
        String player = PlaybackQualityManager.getPlayer(requireContext());
        if (PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) externalPlayerRadioGroup.check(R.id.playerChooserRadio);
        else if (PlaybackQualityManager.PLAYER_VLC.equals(player)) externalPlayerRadioGroup.check(R.id.playerVlcRadio);
        else if (PlaybackQualityManager.PLAYER_MPV.equals(player)) externalPlayerRadioGroup.check(R.id.playerMpvRadio);
        else externalPlayerRadioGroup.check(R.id.playerInternalRadio);

        externalPlayerRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = PlaybackQualityManager.PLAYER_INTERNAL;
            if (checkedId == R.id.playerChooserRadio) selected = PlaybackQualityManager.PLAYER_CHOOSER;
            else if (checkedId == R.id.playerVlcRadio) selected = PlaybackQualityManager.PLAYER_VLC;
            else if (checkedId == R.id.playerMpvRadio) selected = PlaybackQualityManager.PLAYER_MPV;
            PlaybackQualityManager.setPlayer(requireContext(), selected);
            Toast.makeText(requireContext(), "Player anime: " + PlaybackQualityManager.getPlayerLabel(selected), Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupVideoScaleOption() {
        if (videoScaleRadioGroup == null) return;
        String scale = AnimeSettingsManager.getVideoScale(requireContext());
        if (AnimeSettingsManager.VIDEO_SCALE_FIT.equals(scale)) videoScaleRadioGroup.check(R.id.videoScaleFitRadio);
        else if (AnimeSettingsManager.VIDEO_SCALE_STRETCH.equals(scale)) videoScaleRadioGroup.check(R.id.videoScaleStretchRadio);
        else videoScaleRadioGroup.check(R.id.videoScaleNormalRadio);

        videoScaleRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = AnimeSettingsManager.VIDEO_SCALE_NORMAL;
            if (checkedId == R.id.videoScaleFitRadio) selected = AnimeSettingsManager.VIDEO_SCALE_FIT;
            else if (checkedId == R.id.videoScaleStretchRadio) selected = AnimeSettingsManager.VIDEO_SCALE_STRETCH;
            AnimeSettingsManager.setVideoScale(requireContext(), selected);
            Toast.makeText(requireContext(), "Skala video: " + AnimeSettingsManager.getVideoScaleLabel(selected), Toast.LENGTH_SHORT).show();
        });
    }

    private void setupAutoLandscapeOption() {
        if (autoLandscapeSwitch == null) return;
        autoLandscapeSwitch.setChecked(PlaybackQualityManager.isAutoLandscape(requireContext()));
        autoLandscapeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PlaybackQualityManager.setAutoLandscape(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Auto landscape aktif" : "Auto landscape mati", Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupMangaModeOption() {
        if (mangaModeSwitch == null) return;
        mangaModeSwitch.setChecked(MangaSettingsManager.isMangaModeEnabled(requireContext()));
        mangaModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setMangaModeEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Mode baca manga aktif" : "Mode anime aktif", Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof MainActivity && getView() != null) {
                getView().post(() -> ((MainActivity) getActivity()).restartForMangaModeChange());
            }
        });
    }

    private void setupMangaHomeStyleOption() {
        if (mangaHomeStyleRadioGroup == null) return;
        mangaHomeStyleRadioGroup.check(MangaSettingsManager.isHomeStyleV1(requireContext()) ? R.id.mangaHomeStyleV1Radio : R.id.mangaHomeStyleDefaultRadio);
        mangaHomeStyleRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = checkedId == R.id.mangaHomeStyleV1Radio ? MangaSettingsManager.HOME_STYLE_V1 : MangaSettingsManager.HOME_STYLE_DEFAULT;
            MangaSettingsManager.setHomeStyle(requireContext(), selected);
            Toast.makeText(requireContext(), MangaSettingsManager.getHomeStyleLabel(requireContext()), Toast.LENGTH_SHORT).show();
            refreshStats();
            refreshMangaRoots();
        });
    }

    private void setupMangaHomeSourceOption() {
        updateMangaHomeSourceButton();
        if (mangaHomeSourceButton != null) mangaHomeSourceButton.setOnClickListener(v -> showMangaHomeSourceDialog());
    }

    private void showMangaHomeSourceDialog() {
        if (!isAdded()) return;
        String[] sourceIds = MangaSourceFactory.allSourceIds();
        String[] labels = new String[sourceIds.length];
        int checked = 0;
        String current = MangaSettingsManager.getHomeV1Source(requireContext());
        for (int i = 0; i < sourceIds.length; i++) {
            labels[i] = MangaSourceFactory.labelForSourceId(sourceIds[i]);
            if (sourceIds[i].equals(current)) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Source Home v1")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which < 0 || which >= sourceIds.length) return;
                    MangaSettingsManager.setHomeV1Source(requireContext(), sourceIds[which]);
                    updateMangaHomeSourceButton();
                    refreshStats();
                    refreshMangaRoots();
                    dialog.dismiss();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void updateMangaHomeSourceButton() {
        if (mangaHomeSourceButton == null || !isAdded()) return;
        mangaHomeSourceButton.setText("Source Home v1: " + MangaSettingsManager.getHomeV1SourceLabel(requireContext()));
    }

    private void setupMangaSourceOptions() {
        if (mangaSourceCheckGroup == null) return;
        bindMangaSourceCheck(mangaSourceKomikcastCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        bindMangaSourceCheck(mangaSourceShinigamiCheck, MangaSettingsManager.MANGA_SOURCE_SHINIGAMI);
        bindMangaSourceCheck(mangaSourceDoujindesuCheck, MangaSettingsManager.MANGA_SOURCE_DOUJINDESU);
        bindMangaSourceCheck(mangaSourceWestmangaCheck, MangaSettingsManager.MANGA_SOURCE_WESTMANGA);
        bindMangaSourceCheck(mangaSourceBacakomikCheck, MangaSettingsManager.MANGA_SOURCE_BACAKOMIK);
        bindMangaSourceCheck(mangaSourceKomikindoCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKINDO);
        bindMangaSourceCheck(mangaSourceIkiruCheck, MangaSettingsManager.MANGA_SOURCE_IKIRU);
        bindMangaSourceCheck(mangaSourceKomikuCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKU);
        bindMangaSourceCheck(mangaSourceMangasusuCheck, MangaSettingsManager.MANGA_SOURCE_MANGASUSU);
        bindMangaSourceCheck(mangaSourceKomikuOrgCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG);
        bindMangaSourceCheck(mangaSourceCosmicScansCheck, MangaSettingsManager.MANGA_SOURCE_COSMICSCANS);
        bindMangaSourceCheck(mangaSourceKiryuuOfficialCheck, MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL);
        bindMangaSourceCheck(mangaSourceNatsuCheck, MangaSettingsManager.MANGA_SOURCE_NATSU);
        bindMangaSourceCheck(mangaSourceAinzscanssCheck, MangaSettingsManager.MANGA_SOURCE_AINZSCANSS);
        bindMangaSourceCheck(mangaSourceApkomikCheck, MangaSettingsManager.MANGA_SOURCE_APKOMIK);
        bindMangaSourceCheck(mangaSourceComicasoCheck, MangaSettingsManager.MANGA_SOURCE_COMICASO);
        bindMangaSourceCheck(mangaSourceKumoPoiCheck, MangaSettingsManager.MANGA_SOURCE_KUMOPOI);
        bindMangaSourceCheck(mangaSourceMgkomikCheck, MangaSettingsManager.MANGA_SOURCE_MGKOMIK);
        bindMangaSourceCheck(mangaSourceCrotpediaCheck, MangaSettingsManager.MANGA_SOURCE_CROTPEDIA);
        bindMangaSourceCheck(mangaSourceNgomikCheck, MangaSettingsManager.MANGA_SOURCE_NGOMIK);
        bindMangaSourceCheck(mangaSourceKomikTapCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKTAP);
        bindMangaSourceCheck(mangaSourceManhwaIndoCheck, MangaSettingsManager.MANGA_SOURCE_MANHWAINDO);
        bindMangaSourceCheck(mangaSourceSoulScansCheck, MangaSettingsManager.MANGA_SOURCE_SOULSCANS);
        bindMangaSourceCheck(mangaSourceKuromangaCheck, MangaSettingsManager.MANGA_SOURCE_KUROMANGA);
        bindMangaSourceCheck(mangaSourceIsekaiKomikCheck, MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK);
        bindMangaSourceCheck(mangaSourceLuvyaaCheck, MangaSettingsManager.MANGA_SOURCE_LUVYAA);
        bindMangaSourceCheck(mangaSourceSekteDoujinCheck, MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN);
    }

    private void bindMangaSourceCheck(CheckBox checkBox, String sourceId) {
        if (checkBox == null) return;
        checkBox.setChecked(MangaSettingsManager.isMangaSourceEnabled(requireContext(), sourceId));
        checkBox.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_edit, 0);
        checkBox.setCompoundDrawablePadding(dp(10));
        checkBox.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP && checkBox.getCompoundDrawables()[2] != null) {
                int start = checkBox.getWidth() - checkBox.getPaddingEnd() - checkBox.getCompoundDrawables()[2].getBounds().width() - dp(18);
                if (event.getX() >= start) {
                    showDomainDialog(sourceId);
                    return true;
                }
            }
            return false;
        });
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setMangaSourceEnabled(requireContext(), sourceId, isChecked);
            syncMangaSourceChecks();
            updateMangaHomeSourceButton();
            refreshMangaSourceSettings();
            refreshStats();
        });
    }

    private void syncMangaSourceChecks() {
        setSourceCheckWithoutEvent(mangaSourceKomikcastCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        setSourceCheckWithoutEvent(mangaSourceShinigamiCheck, MangaSettingsManager.MANGA_SOURCE_SHINIGAMI);
        setSourceCheckWithoutEvent(mangaSourceDoujindesuCheck, MangaSettingsManager.MANGA_SOURCE_DOUJINDESU);
        setSourceCheckWithoutEvent(mangaSourceWestmangaCheck, MangaSettingsManager.MANGA_SOURCE_WESTMANGA);
        setSourceCheckWithoutEvent(mangaSourceBacakomikCheck, MangaSettingsManager.MANGA_SOURCE_BACAKOMIK);
        setSourceCheckWithoutEvent(mangaSourceKomikindoCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKINDO);
        setSourceCheckWithoutEvent(mangaSourceIkiruCheck, MangaSettingsManager.MANGA_SOURCE_IKIRU);
        setSourceCheckWithoutEvent(mangaSourceKomikuCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKU);
        setSourceCheckWithoutEvent(mangaSourceMangasusuCheck, MangaSettingsManager.MANGA_SOURCE_MANGASUSU);
        setSourceCheckWithoutEvent(mangaSourceKomikuOrgCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG);
        setSourceCheckWithoutEvent(mangaSourceCosmicScansCheck, MangaSettingsManager.MANGA_SOURCE_COSMICSCANS);
        setSourceCheckWithoutEvent(mangaSourceKiryuuOfficialCheck, MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL);
        setSourceCheckWithoutEvent(mangaSourceNatsuCheck, MangaSettingsManager.MANGA_SOURCE_NATSU);
        setSourceCheckWithoutEvent(mangaSourceAinzscanssCheck, MangaSettingsManager.MANGA_SOURCE_AINZSCANSS);
        setSourceCheckWithoutEvent(mangaSourceApkomikCheck, MangaSettingsManager.MANGA_SOURCE_APKOMIK);
        setSourceCheckWithoutEvent(mangaSourceComicasoCheck, MangaSettingsManager.MANGA_SOURCE_COMICASO);
        setSourceCheckWithoutEvent(mangaSourceKumoPoiCheck, MangaSettingsManager.MANGA_SOURCE_KUMOPOI);
        setSourceCheckWithoutEvent(mangaSourceMgkomikCheck, MangaSettingsManager.MANGA_SOURCE_MGKOMIK);
        setSourceCheckWithoutEvent(mangaSourceCrotpediaCheck, MangaSettingsManager.MANGA_SOURCE_CROTPEDIA);
        setSourceCheckWithoutEvent(mangaSourceNgomikCheck, MangaSettingsManager.MANGA_SOURCE_NGOMIK);
        setSourceCheckWithoutEvent(mangaSourceKomikTapCheck, MangaSettingsManager.MANGA_SOURCE_KOMIKTAP);
        setSourceCheckWithoutEvent(mangaSourceManhwaIndoCheck, MangaSettingsManager.MANGA_SOURCE_MANHWAINDO);
        setSourceCheckWithoutEvent(mangaSourceSoulScansCheck, MangaSettingsManager.MANGA_SOURCE_SOULSCANS);
        setSourceCheckWithoutEvent(mangaSourceKuromangaCheck, MangaSettingsManager.MANGA_SOURCE_KUROMANGA);
        setSourceCheckWithoutEvent(mangaSourceIsekaiKomikCheck, MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK);
        setSourceCheckWithoutEvent(mangaSourceLuvyaaCheck, MangaSettingsManager.MANGA_SOURCE_LUVYAA);
        setSourceCheckWithoutEvent(mangaSourceSekteDoujinCheck, MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN);
        updateMangaHomeSourceButton();
    }

    private void setSourceCheckWithoutEvent(CheckBox checkBox, String sourceId) {
        if (checkBox == null) return;
        checkBox.setOnCheckedChangeListener(null);
        checkBox.setChecked(MangaSettingsManager.isMangaSourceEnabled(requireContext(), sourceId));
        checkBox.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_edit, 0);
        checkBox.setCompoundDrawablePadding(dp(10));
        checkBox.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP && checkBox.getCompoundDrawables()[2] != null) {
                int start = checkBox.getWidth() - checkBox.getPaddingEnd() - checkBox.getCompoundDrawables()[2].getBounds().width() - dp(18);
                if (event.getX() >= start) {
                    showDomainDialog(sourceId);
                    return true;
                }
            }
            return false;
        });
        bindMangaSourceCheck(checkBox, sourceId);
    }

    private void showDomainDialog(String sourceId) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setText(MangaSettingsManager.getSourceDomain(requireContext(), sourceId));
        input.setSelectAllOnFocus(true);
        int pad = dp(20);
        input.setPadding(pad, dp(8), pad, dp(8));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Domain " + MangaSourceFactory.labelForSourceId(sourceId))
                .setView(input)
                .setNegativeButton("Gunakan default domain", (dialog, which) -> {
                    MangaSettingsManager.resetSourceDomain(requireContext(), sourceId);
                    Toast.makeText(requireContext(), "Domain default digunakan", Toast.LENGTH_SHORT).show();
                    refreshStats();
                })
                .setPositiveButton("Simpan", (dialog, which) -> {
                    MangaSettingsManager.setSourceDomain(requireContext(), sourceId, input.getText() == null ? "" : input.getText().toString());
                    Toast.makeText(requireContext(), "Domain source disimpan", Toast.LENGTH_SHORT).show();
                    refreshStats();
                })
                .show();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void setupMangaLayoutOptions() {
        if (mangaGridRadioGroup != null) {
            mangaGridRadioGroup.check(MangaSettingsManager.getMangaGridColumns(requireContext()) == 3 ? R.id.mangaGrid3Radio : R.id.mangaGrid2Radio);
            mangaGridRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                MangaSettingsManager.setMangaGridColumns(requireContext(), checkedId == R.id.mangaGrid3Radio ? 3 : 2);
                Toast.makeText(requireContext(), checkedId == R.id.mangaGrid3Radio ? "Grid manga: 3 kolom" : "Grid manga: 2 kolom", Toast.LENGTH_SHORT).show();
                refreshMangaRoots();
            });
        }
        if (chapterLayoutRadioGroup != null) {
            chapterLayoutRadioGroup.check(MangaSettingsManager.isChapterGrid2(requireContext()) ? R.id.chapterGrid2Radio : R.id.chapterDefaultRadio);
            chapterLayoutRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                MangaSettingsManager.setChapterLayout(requireContext(), checkedId == R.id.chapterGrid2Radio ? MangaSettingsManager.CHAPTER_LAYOUT_GRID_2 : MangaSettingsManager.CHAPTER_LAYOUT_DEFAULT);
                Toast.makeText(requireContext(), checkedId == R.id.chapterGrid2Radio ? "Chapter manga: grid 2" : "Chapter manga: default", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void setupMangaDetailUiOptions() {
        if (mangaDetailUiRadioGroup == null) return;
        syncMangaDetailUiSelection();
        mangaDetailUiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingMangaDetailUiOptions) return;
            String selected = MangaSettingsManager.DETAIL_UI_DEFAULT;
            if (checkedId == R.id.mangaDetailUiV2Radio) selected = MangaSettingsManager.DETAIL_UI_V2;
            else if (checkedId == R.id.mangaDetailUiV1Radio) selected = MangaSettingsManager.DETAIL_UI_V1;
            MangaSettingsManager.setDetailUi(requireContext(), selected);
            syncMangaDetailUiSelection();
            Toast.makeText(requireContext(), "UI Detail Manga: " + MangaSettingsManager.getDetailUiLabel(requireContext()), Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void syncMangaDetailUiSelection() {
        if (mangaDetailUiRadioGroup == null || !isAdded()) return;
        updatingMangaDetailUiOptions = true;
        String detailUi = MangaSettingsManager.getDetailUi(requireContext());
        if (MangaSettingsManager.DETAIL_UI_V2.equals(detailUi)) mangaDetailUiRadioGroup.check(R.id.mangaDetailUiV2Radio);
        else if (MangaSettingsManager.DETAIL_UI_V1.equals(detailUi)) mangaDetailUiRadioGroup.check(R.id.mangaDetailUiV1Radio);
        else mangaDetailUiRadioGroup.check(R.id.mangaDetailUiDefaultRadio);
        updatingMangaDetailUiOptions = false;
    }

    private void setupMangaReaderUiOptions() {
        if (mangaReaderUiRadioGroup == null) return;
        String ui = MangaSettingsManager.getReaderUi(requireContext());
        if (MangaSettingsManager.READER_UI_V2.equals(ui)) mangaReaderUiRadioGroup.check(R.id.mangaReaderUiV2Radio);
        else if (MangaSettingsManager.READER_UI_V1.equals(ui)) mangaReaderUiRadioGroup.check(R.id.mangaReaderUiV1Radio);
        else mangaReaderUiRadioGroup.check(R.id.mangaReaderUiDefaultRadio);

        mangaReaderUiRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = MangaSettingsManager.READER_UI_DEFAULT;
            if (checkedId == R.id.mangaReaderUiV2Radio) selected = MangaSettingsManager.READER_UI_V2;
            else if (checkedId == R.id.mangaReaderUiV1Radio) selected = MangaSettingsManager.READER_UI_V1;
            MangaSettingsManager.setReaderUi(requireContext(), selected);
            Toast.makeText(requireContext(), MangaSettingsManager.getReaderUiLabel(requireContext()), Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupMangaReaderZoomOption() {
        if (mangaReaderZoomSwitch == null) return;
        syncMangaReaderZoomSwitches();
        mangaReaderZoomSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingMangaReaderZoomSwitches) return;
            MangaSettingsManager.setReaderZoomEnabled(requireContext(), isChecked);
            syncMangaReaderZoomSwitches();
            Toast.makeText(requireContext(), isChecked ? "Zoom gambar manga aktif" : "Zoom gambar manga mati", Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupMangaReaderPhotoViewZoomOption() {
        if (mangaReaderPhotoViewZoomSwitch == null) return;
        syncMangaReaderZoomSwitches();
        mangaReaderPhotoViewZoomSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingMangaReaderZoomSwitches) return;
            MangaSettingsManager.setReaderPhotoViewZoomEnabled(requireContext(), isChecked);
            syncMangaReaderZoomSwitches();
            Toast.makeText(requireContext(), isChecked ? "Zoom gambar v1 aktif" : "Zoom gambar v1 mati", Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void syncMangaReaderZoomSwitches() {
        if (!isAdded()) return;
        updatingMangaReaderZoomSwitches = true;
        if (mangaReaderZoomSwitch != null) mangaReaderZoomSwitch.setChecked(MangaSettingsManager.isReaderZoomEnabled(requireContext()));
        if (mangaReaderPhotoViewZoomSwitch != null) mangaReaderPhotoViewZoomSwitch.setChecked(MangaSettingsManager.isReaderPhotoViewZoomEnabled(requireContext()));
        updatingMangaReaderZoomSwitches = false;
    }

    private void setupMangaReaderDoubleTapZoomOption() {
        if (mangaReaderDoubleTapZoomSwitch == null) return;
        mangaReaderDoubleTapZoomSwitch.setChecked(MangaSettingsManager.isReaderDoubleTapZoomEnabled(requireContext()));
        mangaReaderDoubleTapZoomSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setReaderDoubleTapZoomEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Double tap zoom aktif" : "Double tap zoom mati", Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupMangaReaderCropBorderOption() {
        if (mangaReaderCropBorderSwitch == null) return;
        mangaReaderCropBorderSwitch.setChecked(MangaSettingsManager.isReaderCropBorderEnabled(requireContext()));
        mangaReaderCropBorderSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setReaderCropBorderEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Crop border aktif" : "Crop border mati", Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupMangaReaderImageScaleOption() {
        if (mangaReaderImageScaleRadioGroup == null) return;
        String scale = MangaSettingsManager.getReaderImageScale(requireContext());
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(scale)) mangaReaderImageScaleRadioGroup.check(R.id.mangaReaderScaleFitScreenRadio);
        else if (MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(scale)) mangaReaderImageScaleRadioGroup.check(R.id.mangaReaderScaleOriginalRadio);
        else mangaReaderImageScaleRadioGroup.check(R.id.mangaReaderScaleFitWidthRadio);
        mangaReaderImageScaleRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = MangaSettingsManager.IMAGE_SCALE_FIT_WIDTH;
            if (checkedId == R.id.mangaReaderScaleFitScreenRadio) selected = MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN;
            else if (checkedId == R.id.mangaReaderScaleOriginalRadio) selected = MangaSettingsManager.IMAGE_SCALE_ORIGINAL;
            MangaSettingsManager.setReaderImageScale(requireContext(), selected);
            Toast.makeText(requireContext(), "Skala gambar: " + MangaSettingsManager.getReaderImageScaleLabel(requireContext()), Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupMangaReaderPageTransitionOption() {
        if (mangaReaderPageTransitionSwitch == null) return;
        mangaReaderPageTransitionSwitch.setChecked(MangaSettingsManager.isReaderPageTransitionEnabled(requireContext()));
        mangaReaderPageTransitionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            MangaSettingsManager.setReaderPageTransitionEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(), isChecked ? "Page transition aktif" : "Page transition mati", Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void setupMangaReaderInlinePreloadOption() {
        if (mangaReaderInlinePreloadSwitch == null) return;
        syncMangaReaderPreloadSwitches();
        mangaReaderInlinePreloadSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingMangaReaderPreloadSwitches) return;
            MangaSettingsManager.setReaderInlineChapterPreloadEnabled(requireContext(), isChecked);
            syncMangaReaderPreloadSwitches();
            Toast.makeText(requireContext(), isChecked ? "Preload gambar chapter berikutnya aktif" : "Preload gambar chapter berikutnya mati", Toast.LENGTH_SHORT).show();
            refreshStats();
        });
    }

    private void syncMangaReaderPreloadSwitches() {
        if (!isAdded()) return;
        MangaSettingsManager.normalizeReaderChapterPreloadModes(requireContext());
        updatingMangaReaderPreloadSwitches = true;
        if (mangaReaderInlinePreloadSwitch != null) mangaReaderInlinePreloadSwitch.setChecked(MangaSettingsManager.isReaderInlineChapterPreloadEnabled(requireContext()));
        if (mangaReaderNextChapterAutoSwitch != null) mangaReaderNextChapterAutoSwitch.setChecked(MangaSettingsManager.isReaderNextChapterAutoEnabled(requireContext()));
        if (mangaReaderNextChapterManualSwitch != null) mangaReaderNextChapterManualSwitch.setChecked(MangaSettingsManager.isReaderNextChapterManualEnabled(requireContext()));
        updatingMangaReaderPreloadSwitches = false;
    }

    private void setupMangaReaderNextChapterOptions() {
        syncMangaReaderPreloadSwitches();
        if (mangaReaderNextChapterAutoSwitch != null) {
            mangaReaderNextChapterAutoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingMangaReaderPreloadSwitches) return;
                MangaSettingsManager.setReaderNextChapterAutoEnabled(requireContext(), isChecked);
                syncMangaReaderPreloadSwitches();
                Toast.makeText(requireContext(), isChecked ? "Auto chapter berikutnya aktif" : "Auto chapter berikutnya mati", Toast.LENGTH_SHORT).show();
                refreshStats();
            });
        }
        if (mangaReaderNextChapterManualSwitch != null) {
            mangaReaderNextChapterManualSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingMangaReaderPreloadSwitches) return;
                MangaSettingsManager.setReaderNextChapterManualEnabled(requireContext(), isChecked);
                syncMangaReaderPreloadSwitches();
                Toast.makeText(requireContext(), isChecked ? "Manual chapter berikutnya aktif" : "Manual chapter berikutnya mati", Toast.LENGTH_SHORT).show();
                refreshStats();
            });
        }
        if (mangaReaderNextChapterThresholdInput != null) {
            mangaReaderNextChapterThresholdInput.setText(MangaSettingsManager.getReaderNextChapterThresholdText(requireContext()));
            mangaReaderNextChapterThresholdInput.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) saveNextChapterThreshold(); });
            mangaReaderNextChapterThresholdInput.setOnEditorActionListener((v, actionId, event) -> {
                saveNextChapterThreshold();
                mangaReaderNextChapterThresholdInput.clearFocus();
                return false;
            });
        }
        if (mangaReaderNextChapterDurationInput != null) {
            mangaReaderNextChapterDurationInput.setText(MangaSettingsManager.getReaderNextChapterDurationText(requireContext()));
            mangaReaderNextChapterDurationInput.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) saveNextChapterDuration(); });
            mangaReaderNextChapterDurationInput.setOnEditorActionListener((v, actionId, event) -> {
                saveNextChapterDuration();
                mangaReaderNextChapterDurationInput.clearFocus();
                return false;
            });
        }
    }

    private void saveNextChapterThreshold() {
        if (mangaReaderNextChapterThresholdInput == null || !isAdded()) return;
        String value = mangaReaderNextChapterThresholdInput.getText() == null ? "" : mangaReaderNextChapterThresholdInput.getText().toString().trim();
        int threshold = 2;
        if (!value.isEmpty()) {
            try { threshold = Integer.parseInt(value); } catch (Exception ignored) { threshold = 2; }
        }
        MangaSettingsManager.setReaderNextChapterThreshold(requireContext(), threshold);
        mangaReaderNextChapterThresholdInput.setText(MangaSettingsManager.getReaderNextChapterThresholdText(requireContext()));
        Toast.makeText(requireContext(), "Preload chapter berikutnya: " + MangaSettingsManager.getReaderNextChapterThreshold(requireContext()) + " gambar terakhir", Toast.LENGTH_SHORT).show();
        refreshStats();
    }

    private void saveNextChapterDuration() {
        if (mangaReaderNextChapterDurationInput == null || !isAdded()) return;
        String value = mangaReaderNextChapterDurationInput.getText() == null ? "" : mangaReaderNextChapterDurationInput.getText().toString().trim();
        int duration = 3;
        if (!value.isEmpty()) {
            try { duration = Integer.parseInt(value); } catch (Exception ignored) { duration = 3; }
        }
        MangaSettingsManager.setReaderNextChapterDurationSeconds(requireContext(), duration);
        mangaReaderNextChapterDurationInput.setText(MangaSettingsManager.getReaderNextChapterDurationText(requireContext()));
        Toast.makeText(requireContext(), "Durasi preload chapter berikutnya: " + MangaSettingsManager.getReaderNextChapterDurationSeconds(requireContext()) + " detik", Toast.LENGTH_SHORT).show();
        refreshStats();
    }

    private void refreshMangaRoots() {
        if (getActivity() instanceof MainActivity && MangaSettingsManager.isMangaModeEnabled(requireContext())) {
            getView().post(() -> ((MainActivity) getActivity()).restartForMangaModeChange());
        }
    }

    private void refreshMangaSourceSettings() {
        if (getActivity() instanceof MainActivity && MangaSettingsManager.isMangaModeEnabled(requireContext())) {
            getView().post(() -> ((MainActivity) getActivity()).refreshMangaSourceSettings());
        }
    }

    private void show(int pos) {
        statsView.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
        if (settingsView != null) settingsView.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
        if (downloaderView != null) downloaderView.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
        devView.setVisibility(pos == 3 ? View.VISIBLE : View.GONE);
        if (pos == 0) refreshStats();
        if (pos == 1) refreshMangaCacheSize();
    }

    private void setupDownloader() {
        if (downloaderFetchButton == null) return;
        if (downloaderPlatformGroup != null) {
            downloaderPlatformGroup.setOnCheckedChangeListener((group, checkedId) -> resetDownloaderUi(checkedId == R.id.downloaderTiktokRadio ? "TikTok Downloader." : "Facebook Downloader."));
        }
        downloaderFetchButton.setOnClickListener(v -> fetchDownloaderLinks());
    }

    private void fetchDownloaderLinks() {
        if (downloaderInput == null) return;
        String url = downloaderInput.getText() == null ? "" : downloaderInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "Masukkan link terlebih dahulu", Toast.LENGTH_SHORT).show();
            return;
        }
        downloaderInput.setText("");
        boolean tiktok = downloaderPlatformGroup != null && downloaderPlatformGroup.getCheckedRadioButtonId() == R.id.downloaderTiktokRadio;
        if (tiktok) fetchTiktokLinks(url); else fetchFacebookLinks(url);
    }

    private void fetchFacebookLinks(String url) {
        resetDownloaderUi("Fetch Data");
        setDownloaderLoading(true, "Fetch Data");
        RequestBody body = new FormBody.Builder().add("id", url).add("locale", "id").build();
        Request request = new Request.Builder()
                .url("https://getmyfb.com/process")
                .post(body)
                .addHeader("HX-Trigger", "form")
                .addHeader("HX-Target", "target")
                .addHeader("HX-Current-URL", "https://getmyfb.com/id")
                .addHeader("HX-Request", "true")
                .addHeader("Origin", "https://getmyfb.com")
                .addHeader("Referer", "https://getmyfb.com/id")
                .addHeader("User-Agent", userAgent())
                .build();
        downloaderClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runDownloaderUi(() -> setDownloaderLoading(false, "Gagal mengambil data."));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String html = response.body() == null ? "" : response.body().string();
                List<DownloaderItem> items = parseFacebookItems(html);
                String title = parseFacebookTitle(html);
                String thumbnail = parseFacebookThumbnail(html);
                runDownloaderUi(() -> showFacebookResult(title, thumbnail, items));
            }
        });
    }

    private void fetchTiktokLinks(String url) {
        resetDownloaderUi("Fetch Data");
        setDownloaderLoading(true, "Fetch Data Tiktok");
        RequestBody body = new FormBody.Builder()
                .add("id", url)
                .add("locale", "id")
                .add("tt", "UHdZdzRm")
                .add("debug", "ab=0&loc=ID")
                .build();
        Request request = new Request.Builder()
                .url("https://ssstik.io/abc?url=dl")
                .post(body)
                .addHeader("HX-Trigger", "_gcaptcha_pt")
                .addHeader("HX-Target", "target")
                .addHeader("HX-Current-URL", "https://ssstik.io/id")
                .addHeader("HX-Request", "true")
                .addHeader("Origin", "https://ssstik.io")
                .addHeader("Referer", "https://ssstik.io/id")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("User-Agent", userAgent())
                .build();
        downloaderClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runDownloaderUi(() -> setDownloaderLoading(false, "Gagal mengambil data."));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String html = response.body() == null ? "" : response.body().string();
                TiktokResult result = parseTiktokResult(html);
                runDownloaderUi(() -> showTiktokResult(result));
            }
        });
    }

    private String userAgent() {
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36";
    }

    private List<DownloaderItem> parseFacebookItems(String html) {
        ArrayList<DownloaderItem> items = new ArrayList<>();
        Document document = Jsoup.parse(html, "https://getmyfb.com/id");
        Elements links = document.select(".results-list-item a[href]");
        for (Element link : links) {
            String href = link.absUrl("href");
            if (href.isEmpty()) href = link.attr("href");
            if (href.isEmpty()) continue;
            String label = link.parent() == null ? link.text() : link.parent().ownText();
            if (label == null || label.trim().isEmpty()) label = link.text();
            String fileName = link.attr("download");
            items.add(new DownloaderItem(label.trim(), href, fileName, "Facebook", "https://getmyfb.com/id"));
        }
        return items;
    }

    private String parseFacebookTitle(String html) {
        Document document = Jsoup.parse(html);
        Element title = document.selectFirst(".results-item-text");
        if (title == null) return "Facebook Video";
        String value = title.text().trim();
        return value.isEmpty() ? "Facebook Video" : value;
    }

    private String parseFacebookThumbnail(String html) {
        Document document = Jsoup.parse(html, "https://getmyfb.com/id");
        Element image = document.selectFirst(".results-item-image[src]");
        return image == null ? "" : image.absUrl("src");
    }

    private TiktokResult parseTiktokResult(String html) {
        TiktokResult result = new TiktokResult();
        Document document = Jsoup.parse(html, "https://ssstik.io/id");
        Element author = document.selectFirst("#avatarAndTextUsual h2, #avatar_and_text h2");
        Element title = document.selectFirst(".maintext");
        String authorText = author == null ? "TikTok" : author.text().trim();
        String titleText = title == null ? "" : title.text().trim();
        result.title = titleText.isEmpty() ? authorText : authorText + " - " + titleText;
        Element coverStyle = document.selectFirst("style:contains(background-image)");
        if (coverStyle != null) result.thumbnail = extractCssUrl(coverStyle.data());
        Elements slides = document.select(".splide__slide");
        for (Element slide : slides) {
            Element image = slide.selectFirst("img[data-splide-lazy], img[src]");
            Element link = slide.selectFirst("a.slide[href], a.download_link.slide[href]");
            if (image == null && link == null) continue;
            String preview = image == null ? "" : image.hasAttr("data-splide-lazy") ? image.absUrl("data-splide-lazy") : image.absUrl("src");
            String href = link == null ? preview : link.absUrl("href");
            if (!href.isEmpty()) result.photos.add(new DownloaderItem("Foto " + (result.photos.size() + 1), href, "tiktok-foto-" + (result.photos.size() + 1) + ".webp", "TikTok", "https://ssstik.io/id"));
            if (!preview.isEmpty()) result.photoPreviews.add(preview);
        }
        if (!result.photos.isEmpty()) result.isPhoto = true;
        if (!result.isPhoto) {
            Element noWatermark = document.selectFirst("a.without_watermark[href], a.download_link.without_watermark[href]");
            Element music = document.selectFirst("a.music[href]");
            if (noWatermark != null) {
                String href = noWatermark.absUrl("href");
                result.videoUrl = href;
                result.items.add(new DownloaderItem("Video tanpa watermark", href, "tiktok-video.mp4", "TikTok", "https://ssstik.io/id"));
            }
            if (music != null) result.items.add(new DownloaderItem("MP3", music.absUrl("href"), "tiktok-audio.mp3", "TikTok", "https://ssstik.io/id"));
        } else {
            Element music = document.selectFirst("a.music[href]");
            if (music != null) result.items.add(new DownloaderItem("MP3", music.absUrl("href"), "tiktok-audio.mp3", "TikTok", "https://ssstik.io/id"));
        }
        return result;
    }

    private String extractCssUrl(String css) {
        int start = css.indexOf("url(");
        if (start < 0) return "";
        int end = css.indexOf(")", start);
        if (end <= start) return "";
        return css.substring(start + 4, end).replace("\"", "").replace("'", "").trim();
    }

    private void showFacebookResult(String title, String thumbnail, List<DownloaderItem> items) {
        setDownloaderLoading(false, items.isEmpty() ? "Link download tidak ditemukan." : "Pilih kualitas untuk mengunduh.");
        if (downloaderTitleText != null) downloaderTitleText.setText(title);
        String previewUrl = findFirstVideoUrl(items);
        if (!previewUrl.isEmpty()) showVideoPreview(previewUrl); else showThumbnail(thumbnail);
        if (downloaderResultList == null) return;
        downloaderResultList.removeAllViews();
        for (DownloaderItem item : items) addDownloaderButton(item);
    }

    private void showTiktokResult(TiktokResult result) {
        setDownloaderLoading(false, result.isEmpty() ? "Data TikTok tidak ditemukan." : result.isPhoto ? "Geser foto lalu unduh gambar yang dipilih." : "Preview video tersedia, pilih format download.");
        if (downloaderTitleText != null) downloaderTitleText.setText(result.title == null || result.title.isEmpty() ? "TikTok" : result.title);
        if (downloaderResultList != null) downloaderResultList.removeAllViews();
        if (result.isPhoto) {
            showPhotoSlider(result.photoPreviews, result.photos);
            for (DownloaderItem item : result.items) addDownloaderButton(item);
        } else {
            if (result.videoUrl != null && !result.videoUrl.isEmpty()) showVideoPreview(result.videoUrl); else showThumbnail(result.thumbnail);
            for (DownloaderItem item : result.items) addDownloaderButton(item);
        }
    }

    private void showThumbnail(String thumbnail) {
        releaseDownloaderPlayer();
        if (downloaderVideoPreview != null) downloaderVideoPreview.setVisibility(View.GONE);
        if (downloaderPhotoScroll != null) downloaderPhotoScroll.setVisibility(View.GONE);
        if (downloaderPreviewFrame == null || downloaderThumbnail == null) return;
        if (thumbnail == null || thumbnail.isEmpty()) {
            downloaderPreviewFrame.setVisibility(View.GONE);
            return;
        }
        downloaderPreviewFrame.setVisibility(View.VISIBLE);
        downloaderThumbnail.setVisibility(View.VISIBLE);
        com.bumptech.glide.Glide.with(this).load(thumbnail).apply(RequestOptions.bitmapTransform(new RoundedCorners(dp(24)))).centerCrop().into(downloaderThumbnail);
    }

    private void showVideoPreview(String videoUrl) {
        if (downloaderPreviewFrame != null) downloaderPreviewFrame.setVisibility(View.GONE);
        if (downloaderPhotoScroll != null) downloaderPhotoScroll.setVisibility(View.GONE);
        if (downloaderVideoPreview == null) return;
        releaseDownloaderPlayer();
        downloaderVideoPreview.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        applyDownloaderVideoRatio(9f / 16f);
        downloaderVideoPreview.setVisibility(View.VISIBLE);
        downloaderPlayer = new ExoPlayer.Builder(requireContext()).build();
        downloaderVideoPreview.setPlayer(downloaderPlayer);
        downloaderPlayer.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    float ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height;
                    applyDownloaderVideoRatio(ratio);
                }
            }
        });
        downloaderPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        downloaderPlayer.setPlayWhenReady(false);
        downloaderPlayer.prepare();
    }

    private void applyDownloaderVideoRatio(float ratio) {
        if (downloaderVideoPreview == null || ratio <= 0f) return;
        int width = getResources().getDisplayMetrics().widthPixels - dp(88);
        int height = Math.round(width / ratio);
        int minHeight = dp(220);
        int maxHeight = ratio < 1f ? dp(560) : dp(360);
        height = Math.max(minHeight, Math.min(height, maxHeight));
        ViewGroup.LayoutParams params = downloaderVideoPreview.getLayoutParams();
        params.height = height;
        downloaderVideoPreview.setLayoutParams(params);
    }

    private String findFirstVideoUrl(List<DownloaderItem> items) {
        if (items == null) return "";
        for (DownloaderItem item : items) {
            String label = item.label == null ? "" : item.label.toLowerCase();
            String url = item.url == null ? "" : item.url;
            if (!url.isEmpty() && !label.contains("mp3")) return url;
        }
        return "";
    }

    private void showPhotoSlider(List<String> previews, List<DownloaderItem> photos) {
        releaseDownloaderPlayer();
        if (downloaderPreviewFrame != null) downloaderPreviewFrame.setVisibility(View.GONE);
        if (downloaderVideoPreview != null) downloaderVideoPreview.setVisibility(View.GONE);
        if (downloaderPhotoScroll == null || downloaderPhotoList == null) return;
        downloaderPhotoScroll.setVisibility(View.VISIBLE);
        downloaderPhotoList.removeAllViews();
        int cardWidth = getResources().getDisplayMetrics().widthPixels - dp(72);
        for (int i = 0; i < previews.size(); i++) {
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMarginEnd(dp(12));
            card.setLayoutParams(cardParams);
            ImageView image = new ImageView(requireContext());
            image.setAdjustViewBounds(true);
            image.setMaxHeight(dp(460));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundResource(R.drawable.downloader_preview_background);
            image.setClipToOutline(true);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            com.bumptech.glide.Glide.with(this).load(previews.get(i)).apply(RequestOptions.bitmapTransform(new RoundedCorners(dp(24)))).fitCenter().into(image);
            if (i < photos.size()) {
                DownloaderItem item = photos.get(i);
                MaterialButton button = new MaterialButton(requireContext());
                button.setText("Unduh gambar ini");
                button.setAllCaps(false);
                button.setOnClickListener(v -> startDownload(item));
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
                buttonParams.topMargin = dp(8);
                card.addView(button, buttonParams);
            }
            downloaderPhotoList.addView(card);
        }
    }

    private void addDownloaderButton(DownloaderItem item) {
        if (downloaderResultList == null) return;
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(item.label == null || item.label.isEmpty() ? "Unduh" : "Unduh " + item.label);
        button.setMinHeight(dp(52));
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        button.setOnClickListener(v -> startDownload(item));
        downloaderResultList.addView(button);
    }

    private void startDownload(DownloaderItem item) {
        if (item == null || item.url == null || item.url.trim().isEmpty()) {
            Toast.makeText(requireContext(), "Link download tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        Context context = requireContext().getApplicationContext();
        String fileName = safeDownloaderFileName(item);
        Toast.makeText(requireContext(), "Download dimulai", Toast.LENGTH_SHORT).show();
        setDownloaderLoading(true, "Mengunduh " + fileName);
        new Thread(() -> {
            try {
                DownloaderResponse downloaderResponse = openDownloaderResponse(item);
                String finalFileName = resolveDownloaderFileName(item, fileName, downloaderResponse.response);
                String mimeType = guessDownloaderMimeType(finalFileName, downloaderResponse.response.header("Content-Type"));
                try (Response response = downloaderResponse.response; ResponseBody body = response.body()) {
                    if (body == null) throw new IOException("Body download kosong");
                    saveDownloaderFile(context, body.byteStream(), finalFileName, mimeType);
                }
                runDownloaderUi(() -> {
                    setDownloaderLoading(false, "Download selesai: " + finalFileName);
                    Toast.makeText(requireContext(), "Download selesai di folder Download", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runDownloaderUi(() -> {
                    setDownloaderLoading(false, "Download gagal: " + readableDownloadError(e));
                    Toast.makeText(requireContext(), "Download gagal", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private DownloaderResponse openDownloaderResponse(DownloaderItem item) throws IOException {
        String url = item.url.trim();
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Request request = downloaderRequest(url, item, attempt);
            Response response = downloaderClient.newCall(request).execute();
            if (isDownloadResponse(response)) return new DownloaderResponse(response);
            String nextUrl = extractDownloaderRedirect(response);
            response.close();
            if (nextUrl == null || nextUrl.trim().isEmpty() || nextUrl.equals(url)) {
                lastError = new IOException("Server tidak mengirim file download");
                break;
            }
            url = nextUrl;
        }
        throw lastError == null ? new IOException("Download gagal") : lastError;
    }

    private Request downloaderRequest(String url, DownloaderItem item, int attempt) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Accept", "video/mp4,video/*,audio/*,image/*,application/octet-stream,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Connection", "keep-alive")
                .header("Referer", item.referer == null || item.referer.isEmpty() ? refererForPlatform(item.platform) : item.referer);
        String cookie = downloaderCookieJar.cookieHeader(url);
        if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        if (attempt > 0) builder.header("Range", "bytes=0-");
        return builder.build();
    }

    private boolean isDownloadResponse(Response response) {
        if (!response.isSuccessful() || response.body() == null) return false;
        String contentType = response.header("Content-Type", "").toLowerCase(Locale.US);
        String disposition = response.header("Content-Disposition", "").toLowerCase(Locale.US);
        if (disposition.contains("attachment") || disposition.contains("filename=")) return true;
        if (contentType.startsWith("video/") || contentType.startsWith("audio/") || contentType.startsWith("image/")) return true;
        if (contentType.equals("application/octet-stream") || contentType.equals("binary/octet-stream")) return true;
        return false;
    }

    private String extractDownloaderRedirect(Response response) throws IOException {
        String location = response.header("Location");
        if (location != null && !location.trim().isEmpty()) return response.request().url().resolve(location) == null ? location : response.request().url().resolve(location).toString();
        ResponseBody body = response.body();
        if (body == null) return "";
        String html = body.string();
        Document document = Jsoup.parse(html, response.request().url().toString());
        Element meta = document.selectFirst("meta[http-equiv=refresh], meta[http-equiv=Refresh]");
        if (meta != null) {
            String content = meta.attr("content");
            int index = content.toLowerCase(Locale.US).indexOf("url=");
            if (index >= 0) {
                String value = content.substring(index + 4).replace("'", "").replace("\"", "").trim();
                okhttp3.HttpUrl resolved = response.request().url().resolve(value);
                return resolved == null ? value : resolved.toString();
            }
        }
        Element link = document.selectFirst("a[download][href], a[href*=/download], a[href*=dl=], a[href*=download]");
        if (link != null) {
            String href = link.absUrl("href");
            if (href.isEmpty()) href = link.attr("href");
            return href;
        }
        return "";
    }

    private String resolveDownloaderFileName(DownloaderItem item, String fallback, Response response) {
        String fromHeader = fileNameFromDisposition(response.header("Content-Disposition"));
        if (fromHeader != null && !fromHeader.trim().isEmpty()) return fromHeader.replaceAll("[\\\\/:*?\"<>|]+", "-");
        String cleanFallback = fallback == null || fallback.trim().isEmpty() ? safeDownloaderFileName(item) : fallback;
        if (hasDownloaderExtension(cleanFallback)) return cleanFallback;
        String mimeType = response.header("Content-Type");
        String extension = extensionFromMimeType(mimeType, item);
        return cleanFallback + extension;
    }

    private String fileNameFromDisposition(String disposition) {
        if (disposition == null || disposition.trim().isEmpty()) return null;
        String[] parts = disposition.split(";");
        for (String part : parts) {
            String value = part.trim();
            if (value.toLowerCase(Locale.US).startsWith("filename*=")) {
                int index = value.indexOf("''");
                if (index >= 0) return Uri.decode(value.substring(index + 2).replace("\"", ""));
                return Uri.decode(value.substring(10).replace("\"", ""));
            }
            if (value.toLowerCase(Locale.US).startsWith("filename=")) return value.substring(9).replace("\"", "").trim();
        }
        return null;
    }

    private boolean hasDownloaderExtension(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private String extensionFromMimeType(String mimeType, DownloaderItem item) {
        String lower = mimeType == null ? "" : mimeType.toLowerCase(Locale.US);
        if (lower.contains("mp4")) return ".mp4";
        if (lower.contains("mpeg") || lower.contains("mp3")) return ".mp3";
        if (lower.contains("webp")) return ".webp";
        if (lower.contains("jpeg") || lower.contains("jpg")) return ".jpg";
        if (lower.contains("png")) return ".png";
        String label = item == null || item.label == null ? "" : item.label.toLowerCase(Locale.US);
        if (label.contains("mp3") || label.contains("music") || label.contains("audio")) return ".mp3";
        if (label.contains("foto") || label.contains("photo") || label.contains("gambar")) return ".webp";
        return ".mp4";
    }

    private String readableDownloadError(Exception e) {
        String message = e == null ? "unknown" : e.getMessage();
        return message == null || message.trim().isEmpty() ? "server menolak file" : message;
    }

    private String refererForPlatform(String platform) {
        String value = platform == null ? "" : platform.toLowerCase(Locale.US);
        if (value.contains("facebook")) return "https://getmyfb.com/";
        if (value.contains("tiktok")) return "https://ssstik.io/";
        return "https://www.google.com/";
    }

    private String guessDownloaderMimeType(String fileName, String contentType) {
        if (contentType != null) {
            String clean = contentType.split(";")[0].trim();
            if (!clean.isEmpty() && !clean.equalsIgnoreCase("application/octet-stream")) return clean;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        String mime = extension == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.US));
        return mime == null ? "application/octet-stream" : mime;
    }

    private void saveDownloaderFile(Context context, InputStream inputStream, String fileName, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveDownloaderFileMediaStore(context, inputStream, fileName, mimeType);
        } else {
            saveDownloaderFileLegacy(context, inputStream, fileName);
        }
    }

    private void saveDownloaderFileMediaStore(Context context, InputStream inputStream, String fileName, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Miku Moe");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Gagal membuat file download");
        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) throw new IOException("Gagal membuka file download");
            copyDownloaderStream(inputStream, outputStream);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private void saveDownloaderFileLegacy(Context context, InputStream inputStream, String fileName) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Miku Moe");
        if ((!dir.exists() && !dir.mkdirs()) || !dir.canWrite()) {
            File fallback = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Miku Moe");
            if (!fallback.exists() && !fallback.mkdirs()) throw new IOException("Gagal membuat folder download");
            dir = fallback;
        }
        File file = new File(dir, fileName);
        try (OutputStream outputStream = new FileOutputStream(file)) {
            copyDownloaderStream(inputStream, outputStream);
        }
    }

    private void copyDownloaderStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
        }
        outputStream.flush();
    }

    private String safeDownloaderFileName(DownloaderItem item) {
        String name = item.fileName == null ? "" : item.fileName.trim();
        if (name.isEmpty()) {
            String platform = item.platform == null ? "download" : item.platform.toLowerCase();
            String label = item.label == null ? "file" : item.label.toLowerCase().replaceAll("[^a-z0-9]+", "-");
            String ext = item.url != null && item.url.toLowerCase().contains(".webp") ? ".webp" : item.label != null && item.label.toLowerCase().contains("mp3") ? ".mp3" : ".mp4";
            name = platform + "-" + label + ext;
        }
        return name.replaceAll("[\\\\/:*?\"<>|]+", "-");
    }

    private void resetDownloaderUi(String message) {
        releaseDownloaderPlayer();
        if (downloaderResultList != null) downloaderResultList.removeAllViews();
        if (downloaderPhotoList != null) downloaderPhotoList.removeAllViews();
        if (downloaderTitleText != null) downloaderTitleText.setText("");
        if (downloaderPreviewFrame != null) downloaderPreviewFrame.setVisibility(View.GONE);
        if (downloaderVideoPreview != null) downloaderVideoPreview.setVisibility(View.GONE);
        if (downloaderPhotoScroll != null) downloaderPhotoScroll.setVisibility(View.GONE);
        setDownloaderLoading(false, message);
    }

    private void setDownloaderLoading(boolean loading, String message) {
        if (downloaderProgress != null) downloaderProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (downloaderFetchButton != null) downloaderFetchButton.setEnabled(!loading);
        if (downloaderStatusText != null) downloaderStatusText.setText(message);
    }

    private void runDownloaderUi(Runnable runnable) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(runnable);
    }

    private void releaseDownloaderPlayer() {
        if (downloaderPlayer != null) {
            downloaderPlayer.release();
            downloaderPlayer = null;
        }
        if (downloaderVideoPreview != null) downloaderVideoPreview.setPlayer(null);
    }

    @Override public void onDestroyView() {
        releaseDownloaderPlayer();
        super.onDestroyView();
    }

    private static class DownloaderItem {
        final String label;
        final String url;
        final String fileName;
        final String platform;
        final String referer;

        DownloaderItem(String label, String url, String fileName, String platform, String referer) {
            this.label = label;
            this.url = url;
            this.fileName = fileName;
            this.platform = platform;
            this.referer = referer;
        }
    }

    private static class DownloaderResponse {
        final Response response;

        DownloaderResponse(Response response) {
            this.response = response;
        }
    }

    private static class DownloaderCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> store = new ConcurrentHashMap<>();

        @Override public void saveFromResponse(okhttp3.HttpUrl url, List<Cookie> cookies) {
            if (cookies == null || cookies.isEmpty()) return;
            store.put(url.host(), new ArrayList<>(cookies));
        }

        @Override public List<Cookie> loadForRequest(okhttp3.HttpUrl url) {
            ArrayList<Cookie> result = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, List<Cookie>> entry : store.entrySet()) {
                String host = entry.getKey();
                if (!url.host().equals(host) && !url.host().endsWith("." + host)) continue;
                for (Cookie cookie : entry.getValue()) {
                    if (cookie.expiresAt() > now) result.add(cookie);
                }
            }
            return result;
        }

        String cookieHeader(String rawUrl) {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(rawUrl);
            if (url == null) return "";
            List<Cookie> cookies = loadForRequest(url);
            if (cookies.isEmpty()) return "";
            ArrayList<String> values = new ArrayList<>();
            for (Cookie cookie : cookies) values.add(cookie.name() + "=" + cookie.value());
            return android.text.TextUtils.join("; ", values);
        }
    }

    private static class TiktokResult {
        String title;
        String thumbnail;
        String videoUrl;
        boolean isPhoto;
        final ArrayList<String> photoPreviews = new ArrayList<>();
        final ArrayList<DownloaderItem> photos = new ArrayList<>();
        final ArrayList<DownloaderItem> items = new ArrayList<>();

        boolean isEmpty() {
            return videoUrl == null && photos.isEmpty() && items.isEmpty();
        }
    }

    public void refreshStats() {
        if (!isAdded() || statsText == null) return;
        boolean animeku = AnimeSettingsManager.isAnimekuSource(requireContext());
        int fav = FavoriteManager.getFavorites(requireContext()).size();
        int hist = animeku ? AnimekuHistoryManager.getHistory(requireContext()).size() : HistoryManager.getHistory(requireContext()).size();
        String quality = PlaybackQualityManager.getQuality(requireContext());
        int mfav = MangaFavoriteManager.getFavorites(requireContext()).size();
        int mhist = MangaHistoryManager.count(requireContext());
        String animeSource = AnimeSettingsManager.getAnimeSourceLabel(requireContext());
        String mode = MangaSettingsManager.isMangaModeEnabled(requireContext()) ? "Baca Manga / " + MangaSettingsManager.getMangaSourceLabel(requireContext()) : "Anime / " + animeSource;
        String enabledSources = enabledMangaSourceLabels();
        String mangaGrid = MangaSettingsManager.getMangaGridColumns(requireContext()) + " kolom";
        String chapterLayout = MangaSettingsManager.isChapterGrid2(requireContext()) ? "Grid 2" : "Default";
        String readerUi = MangaSettingsManager.getReaderUiLabel(requireContext());
        String detailUi = MangaSettingsManager.getDetailUiLabel(requireContext());
        String readerScale = MangaSettingsManager.getReaderImageScaleLabel(requireContext());
        String player = PlaybackQualityManager.getPlayerLabel(PlaybackQualityManager.getPlayer(requireContext()));
        String autoLandscape = PlaybackQualityManager.isAutoLandscape(requireContext()) ? "Aktif" : "Mati";
        String nextChapterMode = MangaSettingsManager.isReaderNextChapterAutoEnabled(requireContext()) ? "Auto" : MangaSettingsManager.isReaderNextChapterManualEnabled(requireContext()) ? "Manual" : "Mati";
        String inlinePreload = MangaSettingsManager.isReaderInlineChapterPreloadEnabled(requireContext()) ? "Aktif" : "Mati";
        String dohProvider = MangaSettingsManager.getDohProviderLabel(requireContext());
        statsText.setText("Mode: " + mode + "\nStyle Home Anime: " + AnimeSettingsManager.getHomeStyleLabel(requireContext()) + "\nSource Home Anime v1: " + AnimeSettingsManager.getHomeV1SourceLabel(requireContext()) + "\nFavorite anime: " + fav + " item\nHistory anime: " + hist + " item\nStyle home manga: " + MangaSettingsManager.getHomeStyleLabel(requireContext()) + "\nSource Home manga v1: " + MangaSettingsManager.getHomeV1SourceLabel(requireContext()) + "\nSource manga: " + MangaSettingsManager.getMangaSourceLabel(requireContext()) + "\nSource tampil: " + enabledSources + "\nFavorite manga: " + mfav + " item\nHistory manga: " + mhist + " item\nGrid: " + mangaGrid + "\nChapter: " + chapterLayout + "\nDetail manga: " + detailUi + "\nReader: " + readerUi + "\nSkala gambar: " + readerScale + "\nPreload gambar inline: " + inlinePreload + "\nPreload next chapter: " + nextChapterMode + " (" + MangaSettingsManager.getReaderNextChapterThreshold(requireContext()) + " gambar, " + MangaSettingsManager.getReaderNextChapterDurationSeconds(requireContext()) + " detik)" + "\nHTTPS / DoH: " + dohProvider + "\nPlayer anime: " + player + "\nResolusi: " + PlaybackQualityManager.getQualityLabel(quality) + "\nTema: " + ThemeManager.getThemeLabel(requireContext()));
    }

    private String enabledMangaSourceLabels() {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (String source : MangaSettingsManager.getEnabledMangaSources(requireContext())) labels.add(MangaSourceFactory.labelForSourceId(source));
        return labels.isEmpty() ? "VoraToon" : android.text.TextUtils.join(", ", labels);
    }

    private void open(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
