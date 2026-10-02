package miku.moe.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public class MangaDetailFragment extends Fragment {
    private MangaPost manga;
    private ImageView imageView, backdropImageView;
    private TextView titleText, genreText, ratingText, yearText, synopsisText, expandText, viewsText, countText, relatedTitleText, relatedMessageText;
    private TextView japaneseText, englishText, typeText, airedText, premieredText, studiosText, sourceText, durationText;
    private LinearLayout detailGenreChipGroup, chapterHeaderLayout, stickyChapterHeaderLayout;
    private TextView stickyChapterCountText;
    private MaterialButton favoriteButton, startButton, saveImageButton, orderButton, chapterLayoutButton, stickyOrderButton, stickyChapterLayoutButton;
    private GridView chaptersListView;
    private ScrollView detailScrollView;
    private View relatedMangaCard;
    private RecyclerView relatedRecyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private final ArrayList<MangaChapter> chapters = new ArrayList<>();
    private final ArrayList<MangaPost> relatedMangas = new ArrayList<>();
    private final ArrayList<MangaChapter> pendingChapters = new ArrayList<>();
    private final Handler chapterHandler = new Handler(Looper.getMainLooper());
    private static final int CHAPTER_RENDER_BATCH_SIZE = 35;
    private MangaChapterAdapter chapterAdapter;
    private RelatedMangaAdapter relatedAdapter;
    private KomikcastClient api;
    private MangaDetailViewModel detailViewModel;
    private static final String CHAPTER_ORDER_PREFS = "miku_manga_detail_order";
    private static final String DETAIL_CACHE_PREFS = "miku_manga_detail_cache";
    private boolean newestFirst = false;
    private boolean skipNextResumeRefresh = false;
    private SharedPreferences favoritePreferences;
    private SharedPreferences historyPreferences;
    private int loadGeneration = 0;
    private int activeRefreshGeneration = 0;
    private int pendingRefreshRequests = 0;
    private int relatedGeneration = 0;
    private boolean relatedLoaded = false;
    private boolean detailGlassBarsApplied = false;
    private float chapterTouchLastY = 0f;
    private int previousStatusBarColor = Color.TRANSPARENT;
    private int previousNavigationBarColor = Color.TRANSPARENT;
    private int previousSystemUiVisibility = 0;
    private boolean previousStatusBarContrastEnforced = true;
    private boolean previousNavigationBarContrastEnforced = true;
    private int previousNavigationBarDividerColor = Color.TRANSPARENT;
    private final SharedPreferences.OnSharedPreferenceChangeListener favoriteChangeListener = (prefs, key) -> {
        if ("items".equals(key) && isReadyForVisibleReload()) updateFav();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener historyChangeListener = (prefs, key) -> {
        if ("items".equals(key) || "chapter_progress".equals(key)) refreshChapterProgressIndicators();
    };
    private final MangaRoomEvents.Listener roomListener = () -> {
        if (!isReadyForVisibleReload()) return;
        updateFav();
        refreshChapterProgressIndicators();
    };

    public MangaDetailFragment(){}

    public MangaDetailFragment(MangaPost manga){ this.manga=manga; }

    public static MangaDetailFragment newInstance(MangaPost manga) {
        MangaDetailFragment fragment = new MangaDetailFragment();
        Bundle args = new Bundle();
        args.putSerializable("manga", manga);
        fragment.setArguments(args);
        return fragment;
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreArguments();
    }

    private void restoreArguments() {
        if (manga != null || getArguments() == null) return;
        Object value = getArguments().getSerializable("manga");
        if (value instanceof MangaPost) manga = (MangaPost) value;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_manga_detail, container, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        restoreArguments();
        if (manga == null) manga = new MangaPost("", "Manga", "", "", "", "", "");
        MangaHistoryManager.cleanOrphanProgress(requireContext(), manga);
        detailViewModel = new ViewModelProvider(this).get(MangaDetailViewModel.class);
        api = MangaSourceFactory.createFor(manga, requireContext());
        MaterialToolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(x -> requireActivity().onBackPressed());
        imageView=v.findViewById(R.id.imageView); backdropImageView=v.findViewById(R.id.backdropImageView);
        applyBackdropBlur();
        titleText=v.findViewById(R.id.categoryNameTextView); genreText=v.findViewById(R.id.genreTextView); ratingText=v.findViewById(R.id.ratingTextView); yearText=v.findViewById(R.id.yearTextView);
        synopsisText=v.findViewById(R.id.synopsisTextView); expandText=v.findViewById(R.id.expandSynopsisTextView); viewsText=v.findViewById(R.id.viewsTextView);
        japaneseText=v.findViewById(R.id.japaneseTextView); englishText=v.findViewById(R.id.englishTextView); typeText=v.findViewById(R.id.typeTextView); airedText=v.findViewById(R.id.airedTextView); premieredText=v.findViewById(R.id.premieredTextView); studiosText=v.findViewById(R.id.studiosTextView); sourceText=v.findViewById(R.id.sourceTextView); durationText=v.findViewById(R.id.durationTextView);
        favoriteButton=v.findViewById(R.id.favoriteButton); startButton=v.findViewById(R.id.startButton); saveImageButton=v.findViewById(R.id.saveImageButton); orderButton=v.findViewById(R.id.orderChapterButton); chapterLayoutButton=v.findViewById(R.id.chapterLayoutButton);
        detailGenreChipGroup=v.findViewById(R.id.detailGenreChipGroup);
        chaptersListView=v.findViewById(R.id.chaptersListView); relatedMangaCard=v.findViewById(R.id.relatedMangaCard); relatedRecyclerView=v.findViewById(R.id.relatedMangaRecyclerView); relatedTitleText=v.findViewById(R.id.relatedMangaTitleTextView); relatedMessageText=v.findViewById(R.id.relatedMangaMessageTextView); swipeRefreshLayout=v.findViewById(R.id.detailSwipeRefreshLayout); countText=v.findViewById(R.id.chapterCountTextView);
        setupSwipeRefresh();
        setupRelatedMangas();
        setupChapterGridPerformance();
        setupStickyChapterHeader(v);
        newestFirst = getSavedChapterOrder();
        bindHeader(false);
        applyCachedSnapshot();
        titleText.setOnClickListener(x -> showTitleMenu());
        titleText.setOnLongClickListener(x -> { showTitleMenu(); return true; });
        expandText.setOnClickListener(x -> { boolean expanded = synopsisText.getMaxLines() > 6; synopsisText.animate().alpha(0.75f).setDuration(80).withEndAction(() -> { synopsisText.setMaxLines(expanded ? 6 : Integer.MAX_VALUE); expandText.setText(expanded ? "Lihat Selengkapnya" : "Lihat lebih sedikit"); synopsisText.animate().alpha(1f).setDuration(140).start(); }).start(); });
        favoriteButton.setOnClickListener(x -> updateFavoriteStateFromDetail());
        startButton.setOnClickListener(x -> openStartChapter());
        saveImageButton.setOnClickListener(x -> saveCover());
        orderButton.setOnClickListener(x -> { newestFirst = !newestFirst; saveChapterOrder(); applyOrder(true); });
        chapterLayoutButton.setOnClickListener(x -> toggleChapterLayout());
        chaptersListView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        chaptersListView.setNestedScrollingEnabled(true);
        chaptersListView.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if(action == MotionEvent.ACTION_DOWN){
                chapterTouchLastY = event.getY();
                setChapterParentIntercept(false);
                return false;
            }
            if(action == MotionEvent.ACTION_MOVE){
                float y = event.getY();
                float dy = y - chapterTouchLastY;
                chapterTouchLastY = y;
                boolean childCanScroll = dy < 0f ? chaptersListView.canScrollList(1) : dy > 0f && chaptersListView.canScrollList(-1);
                setChapterParentIntercept(!childCanScroll);
                return false;
            }
            if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL){
                setChapterParentIntercept(true);
            }
            return false;
        });
        chaptersListView.setOnItemClickListener((parent, view, position, id) -> openChapter(position));
        skipNextResumeRefresh = true;
        load(chapters.isEmpty());
    }

    @Override public void onStart(){
        super.onStart();
        favoritePreferences = requireContext().getApplicationContext().getSharedPreferences("miku_manga_favorites", Context.MODE_PRIVATE);
        historyPreferences = requireContext().getApplicationContext().getSharedPreferences("miku_manga_history", Context.MODE_PRIVATE);
        favoritePreferences.registerOnSharedPreferenceChangeListener(favoriteChangeListener);
        historyPreferences.registerOnSharedPreferenceChangeListener(historyChangeListener);
        MangaRoomEvents.addListener(roomListener);
        updateFav();
        refreshChapterProgressIndicators();
    }

    @Override public void onResume(){
        super.onResume();
        applyDetailGlassSystemBars();
        updateFav();
        newestFirst = getSavedChapterOrder();
        applyChapterLayout();
        MangaHistoryManager.cleanOrphanProgress(requireContext(), manga);
        refreshChapterProgressIndicators();
        if(skipNextResumeRefresh) skipNextResumeRefresh = false; else load(false);
    }

    @Override public void onPause(){
        restoreDetailSystemBars();
        super.onPause();
    }

    @Override public void onHiddenChanged(boolean hidden){
        super.onHiddenChanged(hidden);
        if(hidden) restoreDetailSystemBars();
        else {
            applyDetailGlassSystemBars();
            updateFav();
            refreshChapterProgressIndicators();
        }
    }

    private boolean isReadyForVisibleReload() {
        if (!isAdded() || getView() == null || isHidden() || !isResumed()) return false;
        Fragment parent = getParentFragment();
        while (parent != null) {
            if (!parent.isAdded() || parent.isHidden() || !parent.isResumed()) return false;
            parent = parent.getParentFragment();
        }
        return true;
    }

    private void refreshChapterProgressIndicators() {
        chapterHandler.post(this::refreshChapterProgressIndicatorsNow);
        chapterHandler.postDelayed(this::refreshChapterProgressIndicatorsNow, 250L);
    }

    private void refreshChapterProgressIndicatorsNow() {
        if (!isAdded() || getView() == null || isHidden() || chapterAdapter == null) return;
        chapterAdapter.notifyDataSetChanged();
    }

    @Override public void onStop(){
        if(favoritePreferences != null) favoritePreferences.unregisterOnSharedPreferenceChangeListener(favoriteChangeListener);
        if(historyPreferences != null) historyPreferences.unregisterOnSharedPreferenceChangeListener(historyChangeListener);
        MangaRoomEvents.removeListener(roomListener);
        super.onStop();
    }

    @Override public void onDestroyView(){
        restoreDetailSystemBars();
        loadGeneration++;
        chapterHandler.removeCallbacksAndMessages(null);
        pendingChapters.clear();
        if(imageView != null) MangaImageLoader.clear(imageView);
        if(backdropImageView != null) MangaImageLoader.clear(backdropImageView);
        if(chaptersListView != null){
            chaptersListView.setOnItemClickListener(null);
            chaptersListView.setRecyclerListener(null);
            chaptersListView.setAdapter(null);
        }
        relatedGeneration++;
        relatedMangas.clear();
        if(relatedRecyclerView != null) relatedRecyclerView.setAdapter(null);
        if(swipeRefreshLayout != null){
            swipeRefreshLayout.setOnRefreshListener(null);
            swipeRefreshLayout.setRefreshing(false);
        }
        imageView = null;
        backdropImageView = null;
        chaptersListView = null;
        detailScrollView = null;
        chapterHeaderLayout = null;
        stickyChapterHeaderLayout = null;
        stickyChapterCountText = null;
        stickyOrderButton = null;
        stickyChapterLayoutButton = null;
        relatedMangaCard = null;
        relatedRecyclerView = null;
        chapterAdapter = null;
        relatedAdapter = null;
        swipeRefreshLayout = null;
        super.onDestroyView();
    }

    private void applyDetailGlassSystemBars(){
        if(!isAdded()) return;
        ThemeManager.applySystemBars(requireActivity());
        detailGlassBarsApplied = true;
    }

    private void restoreDetailSystemBars(){
        if(!detailGlassBarsApplied || getActivity() == null) return;
        ThemeManager.applySystemBars(requireActivity());
        detailGlassBarsApplied = false;
    }

    private void setupChapterGridPerformance(){
        if(chaptersListView == null) return;
        chaptersListView.setSmoothScrollbarEnabled(true);
        chaptersListView.setScrollingCacheEnabled(false);
        chaptersListView.setAnimationCacheEnabled(false);
        chaptersListView.setCacheColorHint(0x00000000);
    }

    private void setChapterParentIntercept(boolean allowIntercept){
        if(chaptersListView != null) chaptersListView.requestDisallowInterceptTouchEvent(!allowIntercept);
        if(detailScrollView != null) detailScrollView.requestDisallowInterceptTouchEvent(!allowIntercept);
        if(swipeRefreshLayout != null) swipeRefreshLayout.setEnabled(allowIntercept);
    }

    private void setupStickyChapterHeader(View root){
        detailScrollView = root.findViewById(R.id.detailScrollView);
        chapterHeaderLayout = root.findViewById(R.id.chapterHeaderLayout);
        if(detailScrollView == null || chapterHeaderLayout == null || !(root instanceof ViewGroup)) return;
        stickyChapterHeaderLayout = new LinearLayout(requireContext());
        stickyChapterHeaderLayout.setOrientation(LinearLayout.HORIZONTAL);
        stickyChapterHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        stickyChapterHeaderLayout.setPadding(dp(24), dp(8), dp(24), dp(8));
        stickyChapterHeaderLayout.setBackgroundColor(Color.argb(238, 8, 7, 16));
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stickyChapterHeaderLayout.setElevation(dp(8));
        stickyChapterCountText = new TextView(requireContext());
        stickyChapterCountText.setTextColor(Color.WHITE);
        stickyChapterCountText.setTextSize(22);
        stickyChapterCountText.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stickyChapterHeaderLayout.addView(stickyChapterCountText, textParams);
        stickyOrderButton = createStickyChapterButton(R.drawable.ic_swap_vertical);
        stickyChapterLayoutButton = createStickyChapterButton(R.drawable.ic_grid_2);
        stickyOrderButton.setOnClickListener(x -> orderButton.performClick());
        stickyChapterLayoutButton.setOnClickListener(x -> chapterLayoutButton.performClick());
        stickyChapterHeaderLayout.addView(stickyOrderButton);
        stickyChapterHeaderLayout.addView(stickyChapterLayoutButton);
        stickyChapterHeaderLayout.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        params.topMargin = dp(34);
        ((ViewGroup) root).addView(stickyChapterHeaderLayout, params);
        detailScrollView.getViewTreeObserver().addOnScrollChangedListener(this::updateStickyChapterHeader);
        stickyChapterHeaderLayout.post(this::updateStickyChapterHeader);
    }

    private MaterialButton createStickyChapterButton(int icon){
        MaterialButton button = new MaterialButton(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(58), dp(58));
        params.leftMargin = dp(8);
        button.setLayoutParams(params);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(16), dp(16), dp(16), dp(16));
        button.setText("");
        button.setIconResource(icon);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(0);
        button.setIconTint(ColorStateList.valueOf(Color.WHITE));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.argb(56, 255, 255, 255)));
        button.setCornerRadius(dp(29));
        return button;
    }

    private void updateStickyChapterHeader(){
        if(detailScrollView == null || chapterHeaderLayout == null || stickyChapterHeaderLayout == null) return;
        int threshold = Math.max(0, chapterHeaderLayout.getTop() - dp(34));
        boolean show = detailScrollView.getScrollY() >= threshold;
        stickyChapterHeaderLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if(stickyChapterCountText != null) stickyChapterCountText.setText("Daftar Chapter (" + chapters.size() + ")");
        if(stickyChapterLayoutButton != null && isAdded()) stickyChapterLayoutButton.setIconResource(MangaSettingsManager.isChapterGrid2(requireContext()) ? R.drawable.ic_view_list_manga : R.drawable.ic_grid_2);
    }

    private int dp(int value){
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bindHeader(boolean animate){
        titleText.setText(manga.title);
        genreText.setText(buildMetaLine());
        setPillText(ratingText, manga.getSourceLabel());
        setPillText(yearText, manga.getTypeLabel());
        synopsisText.setText(empty(manga.synopsis)?"Sinopsis belum tersedia.":manga.synopsis); if(viewsText!=null) viewsText.setVisibility(View.GONE);
        if(!empty(manga.info)) {
            String[] infoRows = manga.info.replace("\n", "||").split("\\|\\|");
            if(isSoulScans()) infoRows = filterSoulScansInfoRows(infoRows);
            if(isKuromanga() || isIsekaiKomik()) infoRows = filterHiddenGenreInfoRows(infoRows);
            setInfo(japaneseText, infoRows.length > 0 ? infoRows[0] : "Judul: " + manga.title);
            setInfo(englishText, infoRows.length > 1 ? infoRows[1] : "");
            setInfo(typeText, infoRows.length > 2 ? infoRows[2] : "");
            setInfo(airedText, infoRows.length > 3 ? infoRows[3] : "");
            setInfo(premieredText, infoRows.length > 4 ? infoRows[4] : "");
            setInfo(studiosText, infoRows.length > 5 ? infoRows[5] : "Sumber: " + manga.getSourceLabel());
            setInfo(sourceText, infoRows.length > 6 ? infoRows[6] : "");
            setInfo(durationText, "Sumber: " + manga.getSourceLabel());
        } else {
            setInfo(japaneseText, "Judul: "+manga.title); setInfo(englishText, isSoulScans() || empty(manga.author)?"":"Author: "+manga.author); setInfo(typeText, "Tipe: "+manga.getTypeLabel()); setInfo(airedText, empty(manga.status)?"":"Status: "+manga.status); setInfo(premieredText, empty(manga.genre) || isKuromanga() || isIsekaiKomik()?"":"Genre: "+manga.genre); setInfo(studiosText, "Sumber: " + manga.getSourceLabel()); setInfo(sourceText, "Mode: Baca Manga"); setInfo(durationText, "Reader: vertical webtoon");
        }
        bindGenreChips();
        updateFav(); loadImage(imageView, manga.coverImage, animate); loadImage(backdropImageView, manga.coverImage, animate);
        if (animate) animateHeader();
    }

    private boolean isSoulScans(){
        return manga != null && MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(manga.getSourceId());
    }

    private boolean isKuromanga(){
        return manga != null && MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(manga.getSourceId());
    }

    private boolean isIsekaiKomik(){
        return manga != null && MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(manga.getSourceId());
    }

    private String[] filterSoulScansInfoRows(String[] rows){
        ArrayList<String> out = new ArrayList<>();
        if(rows == null) return new String[0];
        for(String row : rows){
            if(row == null) continue;
            String key = row;
            int idx = key.indexOf(':');
            if(idx >= 0) key = key.substring(0, idx);
            key = key.trim().toLowerCase(Locale.ROOT);
            if(soulScansHiddenInfoKey(key)) continue;
            out.add(row);
        }
        return out.toArray(new String[0]);
    }

    private boolean soulScansHiddenInfoKey(String key){
        return "author".equals(key) || "rating".equals(key) || "rating count".equals(key) || "views".equals(key) || "uploader".equals(key) || "readers".equals(key) || "language".equals(key) || "followers".equals(key);
    }

    private String[] filterHiddenGenreInfoRows(String[] rows){
        ArrayList<String> out = new ArrayList<>();
        if(rows == null) return new String[0];
        for(String row : rows){
            if(row == null) continue;
            String key = row;
            int idx = key.indexOf(':');
            if(idx >= 0) key = key.substring(0, idx);
            key = key.trim().toLowerCase(Locale.ROOT);
            if("genre".equals(key)) continue;
            out.add(row);
        }
        return out.toArray(new String[0]);
    }

    private String buildMetaLine(){
        ArrayList<String> parts = new ArrayList<>();
        if(!isSoulScans() && !empty(manga.author)) parts.add(manga.author);
        if(!empty(manga.getTypeLabel())) parts.add(manga.getTypeLabel());
        if(!empty(manga.status)) parts.add(manga.status);
        if(!empty(manga.latestChapterDate)) parts.add(manga.latestChapterDate);
        if(parts.isEmpty()) parts.add(manga.getSourceLabel());
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < parts.size(); i++){
            if(i > 0) builder.append(" | ");
            builder.append(parts.get(i));
        }
        return builder.toString();
    }

    private void setPillText(TextView view, String text){
        if(view == null) return;
        if(empty(text)){
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(text);
    }

    private void applyBackdropBlur(){
        if(backdropImageView == null) return;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            backdropImageView.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP));
        }
    }

    private void animateHeader(){
        View root = getView();
        if(root == null) return;
        View content = root.findViewById(R.id.detailContentLayout);
        if(content == null) return;
        content.setAlpha(0.92f);
        content.setTranslationY(10f);
        content.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    private void bindGenreChips(){
        if(detailGenreChipGroup == null || !isAdded()) return;
        detailGenreChipGroup.removeAllViews();
        ArrayList<TextView> labels = new ArrayList<>();
        ArrayList<String> genres = parseGenreList(manga == null ? "" : manga.genre);
        if(genres.isEmpty()){
            labels.add(createDetailGenreLabel("Genre belum tersedia", false, null));
        } else {
            for(String name : genres){
                labels.add(createDetailGenreLabel(name, true, v -> openGenreResult(name)));
            }
        }
        detailGenreChipGroup.post(() -> layoutGenreLabels(labels));
    }

    private void layoutGenreLabels(ArrayList<TextView> labels){
        if(detailGenreChipGroup == null || labels == null || !isAdded()) return;
        detailGenreChipGroup.removeAllViews();
        int availableWidth = detailGenreChipGroup.getWidth() - detailGenreChipGroup.getPaddingStart() - detailGenreChipGroup.getPaddingEnd();
        if(availableWidth <= 0) availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
        LinearLayout row = createGenreRow();
        int rowWidth = 0;
        int horizontalGap = dp(7);
        for(TextView label : labels){
            label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int labelWidth = label.getMeasuredWidth() + (horizontalGap * 2);
            if(row.getChildCount() > 0 && rowWidth + labelWidth > availableWidth){
                detailGenreChipGroup.addView(row);
                row = createGenreRow();
                rowWidth = 0;
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            params.setMargins(horizontalGap / 2, dp(3), horizontalGap / 2, dp(3));
            row.addView(label, params);
            rowWidth += labelWidth;
        }
        if(row.getChildCount() > 0) detailGenreChipGroup.addView(row);
    }

    private LinearLayout createGenreRow(){
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private ArrayList<String> parseGenreList(String raw){
        ArrayList<String> out = new ArrayList<>();
        if(raw == null) return out;
        String[] parts = raw.replace("/", ",").replace("•", ",").split(",");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for(String part : parts){
            String item = part == null ? "" : part.trim();
            if(item.isEmpty()) continue;
            String lower = item.toLowerCase(java.util.Locale.ROOT);
            if(seen.add(lower)) out.add(item);
        }
        return out;
    }

    private void openGenreResult(String genreName){
        if(!isAdded() || manga == null || genreName == null || genreName.trim().isEmpty()) return;
        String sourceId = manga.getSourceId();
        KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(sourceId);
        sourceApi.genres(new KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>>() {
            @Override public void onSuccess(ArrayList<KomikcastClient.GenreItem> data, boolean next) {
                if(!isAdded()) return;
                String value = findGenreValue(data, genreName);
                openGenreTarget(sourceId, MangaSourceFactory.labelForSourceId(sourceId), genreName, value);
            }
            @Override public void onError(String message) {
                if(!isAdded()) return;
                openGenreTarget(sourceId, MangaSourceFactory.labelForSourceId(sourceId), genreName, genreName);
            }
        });
    }

    private String findGenreValue(ArrayList<KomikcastClient.GenreItem> data, String genreName){
        if(data != null){
            String target = normalizeGenreName(genreName);
            for(KomikcastClient.GenreItem item : data){
                if(item == null || item.title == null) continue;
                if(normalizeGenreName(item.title).equals(target)) return item.value == null || item.value.trim().isEmpty() ? genreName : item.value;
            }
        }
        return genreName;
    }

    private String normalizeGenreName(String text){
        return text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "-").replace("_", "-");
    }

    private TextView createDetailGenreLabel(String text, boolean enabled, View.OnClickListener listener){
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(12f);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        label.setMinWidth(0);
        label.setMinHeight(0);
        label.setPadding(dp(12), 0, dp(12), 0);
        label.setBackgroundResource(R.drawable.manga_detail_pill_background);
        label.setEnabled(enabled);
        label.setClickable(enabled && listener != null);
        if(listener != null) label.setOnClickListener(listener);
        return label;
    }

    private int dp(float value){
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setupRelatedMangas(){
        if(relatedRecyclerView == null || !isAdded()) return;
        relatedAdapter = new RelatedMangaAdapter(requireContext(), relatedMangas, post -> {
            openDetailTarget(post);
        });
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false);
        relatedRecyclerView.setLayoutManager(layoutManager);
        relatedRecyclerView.setAdapter(relatedAdapter);
        relatedRecyclerView.setItemAnimator(null);
        relatedRecyclerView.setItemViewCacheSize(10);
        relatedRecyclerView.setNestedScrollingEnabled(false);
        showRelatedState(false, "");
    }

    private void loadRelatedMangas(){
        if(!isAdded() || manga == null || relatedRecyclerView == null) return;
        int generation = ++relatedGeneration;
        relatedMangas.clear();
        if(relatedAdapter != null) relatedAdapter.notifyDataSetChanged();
        showRelatedState(false, "");
        final MangaPost baseManga = manga;
        final KomikcastClient sourceApi = MangaSourceFactory.createFor(baseManga, requireContext());
        ArrayList<String> baseGenres = parseGenreList(baseManga.genre);
        sourceApi.genres(new KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>>() {
            @Override public void onSuccess(ArrayList<KomikcastClient.GenreItem> data, boolean hasNext) {
                if(!isAdded() || generation != relatedGeneration) return;
                HashMap<String, String> mappedGenres = mapGenreValues(data, baseGenres);
                fetchRelatedCandidates(sourceApi, baseManga, baseGenres, mappedGenres, generation);
            }
            @Override public void onError(String message) {
                if(!isAdded() || generation != relatedGeneration) return;
                fetchRelatedCandidates(sourceApi, baseManga, baseGenres, new HashMap<>(), generation);
            }
        });
    }

    private HashMap<String, String> mapGenreValues(ArrayList<KomikcastClient.GenreItem> items, ArrayList<String> baseGenres){
        HashMap<String, String> result = new HashMap<>();
        if(baseGenres == null || baseGenres.isEmpty()) return result;
        HashMap<String, String> all = new HashMap<>();
        if(items != null){
            for(KomikcastClient.GenreItem item : items){
                if(item == null) continue;
                String key = normalizeGenreName(item.title);
                if(!key.isEmpty()) all.put(key, item.value == null || item.value.trim().isEmpty() ? item.title : item.value);
            }
        }
        for(String genre : baseGenres){
            String key = normalizeGenreName(genre);
            if(all.containsKey(key)) result.put(genre, all.get(key));
            else result.put(genre, genre);
        }
        return result;
    }

    private void fetchRelatedCandidates(KomikcastClient sourceApi, MangaPost baseManga, ArrayList<String> baseGenres, HashMap<String, String> mappedGenres, int generation){
        ArrayList<RelatedRequest> requests = buildRelatedRequests(baseGenres, mappedGenres);
        if(requests.isEmpty()) requests.add(new RelatedRequest(1, "latest", "", ""));
        LinkedHashMap<String, RelatedCandidate> candidates = new LinkedHashMap<>();
        AtomicInteger remaining = new AtomicInteger(requests.size());
        for(RelatedRequest request : requests){
            sourceApi.list(request.page, request.sort, "", request.genreValue, new KomikcastClient.Result<ArrayList<MangaPost>>() {
                @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                    collectRelatedCandidates(candidates, data, baseManga, request.genreName);
                    finishRelatedFetch(sourceApi, baseManga, candidates, remaining, generation);
                }
                @Override public void onError(String message) {
                    finishRelatedFetch(sourceApi, baseManga, candidates, remaining, generation);
                }
            });
        }
    }

    private ArrayList<RelatedRequest> buildRelatedRequests(ArrayList<String> baseGenres, HashMap<String, String> mappedGenres){
        ArrayList<RelatedRequest> requests = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if(baseGenres != null){
            int limit = Math.min(2, baseGenres.size());
            for(int i = 0; i < limit; i++){
                String genreName = baseGenres.get(i);
                String value = mappedGenres == null ? genreName : mappedGenres.get(genreName);
                if(empty(value)) value = genreName;
                addRelatedRequest(requests, seen, 1, "popular", genreName, value);
            }
        }
        addRelatedRequest(requests, seen, 1, "popular", "", "");
        addRelatedRequest(requests, seen, 1, "latest", "", "");
        return requests;
    }

    private void addRelatedRequest(ArrayList<RelatedRequest> requests, HashSet<String> seen, int page, String sort, String genreName, String genreValue){
        if(requests.size() >= 4) return;
        String key = page + "|" + sort + "|" + (genreValue == null ? "" : genreValue.trim().toLowerCase(Locale.ROOT));
        if(seen.add(key)) requests.add(new RelatedRequest(page, sort, genreName, genreValue));
    }

    private void collectRelatedCandidates(LinkedHashMap<String, RelatedCandidate> candidates, ArrayList<MangaPost> data, MangaPost baseManga, String matchedGenre){
        if(data == null || baseManga == null) return;
        for(MangaPost post : data){
            if(post == null || isSameManga(baseManga, post)) continue;
            post.withSource(baseManga.getSourceId(), baseManga.getSourceLabel());
            String key = relatedKey(post);
            if(key.isEmpty()) continue;
            RelatedCandidate candidate = candidates.get(key);
            if(candidate == null){
                candidate = new RelatedCandidate(post);
                candidates.put(key, candidate);
            } else {
                mergeRelatedPost(candidate.post, post);
            }
            candidate.score += scoreRelated(baseManga, post, matchedGenre);
        }
    }

    private void finishRelatedFetch(KomikcastClient sourceApi, MangaPost baseManga, LinkedHashMap<String, RelatedCandidate> candidates, AtomicInteger remaining, int generation){
        if(remaining.decrementAndGet() > 0) return;
        if(!isAdded() || generation != relatedGeneration) return;
        ArrayList<RelatedCandidate> ranked = new ArrayList<>(candidates.values());
        Collections.sort(ranked, new Comparator<RelatedCandidate>() {
            @Override public int compare(RelatedCandidate a, RelatedCandidate b) {
                int score = Integer.compare(b.score, a.score);
                if(score != 0) return score;
                return relatedKey(a.post).compareTo(relatedKey(b.post));
            }
        });
        ArrayList<MangaPost> result = new ArrayList<>();
        for(RelatedCandidate candidate : ranked){
            if(candidate == null || candidate.post == null) continue;
            result.add(candidate.post);
            if(result.size() >= 24) break;
        }
        applyRelatedResult(result, generation);
    }

    private void enrichRelatedChapters(KomikcastClient sourceApi, ArrayList<MangaPost> result, int generation){
        if(result == null || result.isEmpty()){
            applyRelatedResult(new ArrayList<>(), generation);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(result.size());
        for(MangaPost post : result){
            if(post == null || empty(post.slug)){
                if(remaining.decrementAndGet() <= 0) applyRelatedResult(result, generation);
                continue;
            }
            sourceApi.chapters(post.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) {
                    applyLatestChapterLabel(post, data);
                    if(remaining.decrementAndGet() <= 0) applyRelatedResult(result, generation);
                }
                @Override public void onError(String message) {
                    if(remaining.decrementAndGet() <= 0) applyRelatedResult(result, generation);
                }
            });
        }
    }

    private void applyLatestChapterLabel(MangaPost post, ArrayList<MangaChapter> data){
        if(post == null || data == null || data.isEmpty()) return;
        MangaChapter newest = data.get(0);
        for(MangaChapter chapter : data){
            if(chapter != null && newest != null && chapter.index > newest.index) newest = chapter;
        }
        if(newest == null) return;
        String title = newest.title == null ? "" : newest.title.trim();
        if(empty(title)) title = "Chapter " + MangaChapter.formatIndex(newest.index);
        else if(!title.toLowerCase(Locale.ROOT).contains("chapter") && !title.toLowerCase(Locale.ROOT).startsWith("ch")) title = "Chapter " + MangaChapter.formatIndex(newest.index) + " - " + title;
        post.latestChapter = title;
        post.latestChapterDate = newest.date == null ? "" : newest.date;
        post.totalChapters = Math.max(post.totalChapters, data.size());
    }

    private void applyRelatedResult(ArrayList<MangaPost> result, int generation){
        if(!isAdded() || generation != relatedGeneration) return;
        relatedMangas.clear();
        if(result != null) relatedMangas.addAll(result);
        if(relatedAdapter != null){
            relatedAdapter.notifyDataSetChanged();
            if (relatedRecyclerView != null) {
                relatedRecyclerView.animate().cancel();
                relatedRecyclerView.setAlpha(1f);
            }
        }
        showRelatedState(true, relatedMangas.isEmpty() ? "Suggestions belum tersedia untuk manga ini" : "");
    }

    private void showRelatedState(boolean visible, String message){
        if(relatedRecyclerView == null || relatedTitleText == null || relatedMessageText == null) return;
        if(relatedMangaCard != null) relatedMangaCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        relatedTitleText.setVisibility(View.VISIBLE);
        relatedRecyclerView.setVisibility(visible && relatedMangas.size() > 0 ? View.VISIBLE : View.GONE);
        relatedMessageText.setVisibility(visible && relatedMangas.isEmpty() ? View.VISIBLE : View.GONE);
        relatedMessageText.setText(message == null ? "" : message);
    }

    private int scoreRelated(MangaPost baseManga, MangaPost post, String matchedGenre){
        int score = 1;
        if(!empty(matchedGenre)) score += 8;
        ArrayList<String> baseGenres = parseGenreList(baseManga.genre);
        ArrayList<String> postGenres = parseGenreList(post.genre);
        HashSet<String> baseSet = new HashSet<>();
        for(String genre : baseGenres) baseSet.add(normalizeGenreName(genre));
        for(String genre : postGenres) if(baseSet.contains(normalizeGenreName(genre))) score += 5;
        if(baseManga.getTypeLabel().equalsIgnoreCase(post.getTypeLabel())) score += 3;
        if(!empty(baseManga.status) && baseManga.status.equalsIgnoreCase(post.status)) score += 2;
        if(!empty(baseManga.author) && baseManga.author.equalsIgnoreCase(post.author)) score += 4;
        if(!empty(post.latestChapter)) score += 1;
        return score;
    }

    private boolean isSameManga(MangaPost a, MangaPost b){
        if(a == null || b == null) return false;
        String as = a.slug == null ? "" : a.slug.trim();
        String bs = b.slug == null ? "" : b.slug.trim();
        if(!as.isEmpty() && as.equalsIgnoreCase(bs)) return true;
        String at = a.title == null ? "" : a.title.trim();
        String bt = b.title == null ? "" : b.title.trim();
        return !at.isEmpty() && at.equalsIgnoreCase(bt);
    }

    private String relatedKey(MangaPost post){
        if(post == null) return "";
        if(post.slug != null && !post.slug.trim().isEmpty()) return post.slug.trim().toLowerCase(Locale.ROOT);
        return post.title == null ? "" : post.title.trim().toLowerCase(Locale.ROOT);
    }

    private void mergeRelatedPost(MangaPost target, MangaPost source){
        if(target == null || source == null) return;
        if(empty(target.coverImage)) target.coverImage = source.coverImage;
        if(empty(target.genre)) target.genre = source.genre;
        if(empty(target.author)) target.author = source.author;
        if(empty(target.status)) target.status = source.status;
        if(empty(target.synopsis)) target.synopsis = source.synopsis;
        if(empty(target.latestChapter)) target.latestChapter = source.latestChapter;
        if(empty(target.latestChapterDate)) target.latestChapterDate = source.latestChapterDate;
        target.totalChapters = Math.max(target.totalChapters, source.totalChapters);
    }

    private static class RelatedRequest {
        final int page;
        final String sort;
        final String genreName;
        final String genreValue;
        RelatedRequest(int page, String sort, String genreName, String genreValue){
            this.page = page;
            this.sort = sort == null ? "latest" : sort;
            this.genreName = genreName == null ? "" : genreName;
            this.genreValue = genreValue == null ? "" : genreValue;
        }
    }

    private static class RelatedCandidate {
        final MangaPost post;
        int score;
        RelatedCandidate(MangaPost post){
            this.post = post;
        }
    }

    private void updateFavoriteStateFromDetail(){
        if(!isAdded() || manga == null) return;
        updateMangaLatestChapterFromChapters();
        saveSnapshot();
        if(MangaFavoriteManager.isFavorite(requireContext(), manga)) MangaFavoriteManager.remove(requireContext(), manga); else MangaFavoriteManager.add(requireContext(), manga);
        updateFav();
        Toast.makeText(requireContext(), "Favorite manga diperbarui", Toast.LENGTH_SHORT).show();
    }

    private void updateFav(){
        if(!isAdded() || favoriteButton == null || manga == null) return;
        boolean favorite = MangaFavoriteManager.isFavorite(requireContext(), manga);
        favoriteButton.setText(favorite ? "Hapus Favorite" : "Favorite");
        favoriteButton.setIconTint(ColorStateList.valueOf(favorite ? Color.rgb(255, 54, 84) : Color.WHITE));
    }

    private void load(boolean showLoading){
        if(!isAdded() || manga == null || empty(manga.slug)) return;
        int generation = ++loadGeneration;
        beginRefreshLoading(generation, showLoading || (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()));
        showChapterLoading("Memuat chapter...");
        final String currentSourceId = manga.getSourceId();
        final String currentSourceLabel = manga.getSourceLabel();
        if (detailViewModel == null) detailViewModel = new ViewModelProvider(this).get(MangaDetailViewModel.class);
        detailViewModel.loadDetail(currentSourceId, manga.slug).whenComplete((p, error) -> {
            if(!isAdded() || generation != loadGeneration)return;
            if(error == null && p != null) {
                applyMangaResult(p, currentSourceId, currentSourceLabel);
                saveSnapshot();
            }
            finishRefreshLoading(generation);
        });
        detailViewModel.loadChapters(currentSourceId, manga.slug).whenComplete((data, error) -> {
            if(!isAdded() || generation != loadGeneration)return;
            if(error == null) {
                updateChapters(data == null ? new ArrayList<>() : data, currentSourceId, currentSourceLabel);
                saveSnapshot();
            } else {
                hideChapterLoading();
                String msg = error.getMessage() == null ? "" : error.getMessage();
                showDetailMessage("Chapter gagal dimuat: " + msg);
            }
            finishRefreshLoading(generation);
        });
    }

    private void updateMangaLatestChapterFromChapters(){
        if(manga == null) return;
        manga.totalChapters = Math.max(manga.totalChapters, chapters.size());
        if(chapters.isEmpty()) return;
        MangaChapter newest = null;
        for(MangaChapter chapter : chapters){
            if(chapter == null) continue;
            if(newest == null || chapter.index > newest.index) newest = chapter;
        }
        if(newest == null) return;
        manga.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
        manga.latestChapterDate = newest.date == null ? "" : newest.date;
    }

    private void applyMangaResult(MangaPost p, String currentSourceId, String currentSourceLabel){
        if (p == null) return;
        if (empty(p.title)) p.title = manga.title;
        if (empty(p.coverImage)) p.coverImage = manga.coverImage;
        if (empty(p.latestChapter)) p.latestChapter = manga.latestChapter;
        if (empty(p.latestChapterDate)) p.latestChapterDate = manga.latestChapterDate;
        p.withSource(currentSourceId, currentSourceLabel);
        String resolvedType = MangaLabelUtils.typeLabel(p);
        if(!empty(resolvedType)) p.typeLabel = resolvedType;
        p.totalChapters = Math.max(p.totalChapters, manga.totalChapters);
        if(!chapters.isEmpty()) p.totalChapters = Math.max(p.totalChapters, chapters.size());
        manga=p;
        bindHeader(true);
    }

    private void updateChapters(ArrayList<MangaChapter> data, String currentSourceId, String currentSourceLabel){
        String before = chaptersSignature(chapters);
        chapterHandler.removeCallbacksAndMessages(null);
        pendingChapters.clear();
        chapters.clear();
        if(data != null) chapters.addAll(data);
        manga.withSource(currentSourceId, currentSourceLabel);
        updateMangaLatestChapterFromChapters();
        if (MangaFavoriteManager.isFavorite(requireContext(), manga)) MangaFavoriteManager.add(requireContext(), manga);
        applyOrder(!before.equals(chaptersSignature(chapters)));
        hideChapterLoading();
        saveSnapshot();
        loadRelatedMangasOnce();
    }

    private void renderNextChapterBatch(boolean animate){
        if(!isAdded() || chaptersListView == null) return;
        if(!pendingChapters.isEmpty()){
            chapters.addAll(pendingChapters);
            pendingChapters.clear();
        }
        applyOrder(animate);
        hideChapterLoading();
        saveSnapshot();
        loadRelatedMangasOnce();
    }

    private void loadRelatedMangasOnce(){
        if(relatedLoaded) return;
        relatedLoaded = true;
        mainHandlerPostRelated();
    }

    private void mainHandlerPostRelated(){
        if(!isAdded()) return;
        chapterHandler.postDelayed(() -> {
            if(isAdded()) loadRelatedMangas();
        }, 180);
    }

    private void applyOrder(){ applyOrder(false); }

    private void applyOrder(boolean animate){
        Collections.sort(chapters, (a,b)-> Float.compare(a.index,b.index));
        if(newestFirst) Collections.reverse(chapters);
        if(orderButton != null) orderButton.setText("");
        applyChapterLayout();
        if(chapterAdapter==null){
            chapterAdapter=new MangaChapterAdapter(requireContext(), manga, chapters);
            chaptersListView.setAdapter(chapterAdapter);
        } else {
            chapterAdapter.notifyDataSetChanged();
        }
        manga.totalChapters = chapters.size();
        if(countText != null) countText.setText("Daftar Chapter (" + chapters.size() + ")");
        updateStickyChapterHeader();
        if(animate) animateChaptersList();
    }

    private void animateChaptersList(){
        if(chaptersListView == null) return;
        chaptersListView.setAlpha(0.88f);
        chaptersListView.setTranslationY(12f);
        chaptersListView.animate().alpha(1f).translationY(0f).setDuration(190).start();
    }

    private String chaptersSignature(ArrayList<MangaChapter> list){
        StringBuilder builder = new StringBuilder();
        for(MangaChapter ch : list){
            if(ch == null) continue;
            builder.append(ch.slug == null ? "" : ch.slug).append('|').append(ch.index).append('|').append(ch.title == null ? "" : ch.title).append('|').append(ch.date == null ? "" : ch.date).append('\n');
        }
        return builder.toString();
    }

    private void showDetailMessage(String message){
        if(countText == null) return;
        String text = message == null || message.trim().isEmpty() ? "Gagal memuat data. Coba lagi." : message.trim();
        countText.setText(text);
        countText.setAlpha(0f);
        countText.animate().alpha(1f).setDuration(160).start();
    }

    private void setupSwipeRefresh(){
        if(swipeRefreshLayout == null) return;
        int primary = MaterialColors.getColor(swipeRefreshLayout, androidx.appcompat.R.attr.colorPrimary);
        int surface = MaterialColors.getColor(swipeRefreshLayout, com.google.android.material.R.attr.colorSurface);
        swipeRefreshLayout.setColorSchemeColors(primary);
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(surface);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (manga != null) MangaSourceFactory.invalidateSourceCaches(manga.getSourceId());
            load(true);
        });
    }

    private void beginRefreshLoading(int generation, boolean showLoading){
        activeRefreshGeneration = generation;
        pendingRefreshRequests = 2;
        if(showLoading) setSwipeRefreshing(true);
    }

    private void finishRefreshLoading(int generation){
        if(generation != activeRefreshGeneration) return;
        pendingRefreshRequests = Math.max(0, pendingRefreshRequests - 1);
        if(pendingRefreshRequests == 0) setSwipeRefreshing(false);
    }

    private void setSwipeRefreshing(boolean refreshing){
        if(swipeRefreshLayout == null) return;
        swipeRefreshLayout.post(() -> {
            if(swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(refreshing);
        });
    }

    private void showChapterLoading(String text){
        if(countText != null && text != null) countText.setText(text);
    }

    private void hideChapterLoading(){
    }

    private void applyCachedSnapshot(){
        if(!isAdded() || manga == null || empty(manga.slug)) return;
        try{
            String raw = requireContext().getApplicationContext().getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE).getString(cacheKey(), "");
            if(empty(raw)) return;
            JSONObject root = new JSONObject(raw);
            JSONObject m = root.optJSONObject("manga");
            if(m != null){
                MangaPost cached = new MangaPost(manga.slug, m.optString("title", manga.title), m.optString("coverImage", manga.coverImage), m.optString("author", manga.author), m.optString("status", manga.status), m.optString("synopsis", manga.synopsis), m.optString("genre", manga.genre), m.optString("typeLabel", manga.typeLabel), m.optString("latestChapter", manga.latestChapter), m.optString("latestChapterDate", manga.latestChapterDate));
                cached.withSource(manga.getSourceId(), manga.getSourceLabel());
                cached.info = m.optString("info", manga.info);
                String cachedType = MangaLabelUtils.normalizeStoredType(cached.typeLabel, (cached.genre == null ? "" : cached.genre) + " " + (cached.status == null ? "" : cached.status) + " " + (cached.info == null ? "" : cached.info) + " " + (cached.synopsis == null ? "" : cached.synopsis));
                if(!cachedType.isEmpty()) cached.typeLabel = cachedType;
                cached.totalChapters = m.optInt("totalChapters", manga.totalChapters);
                manga = cached;
                bindHeader(false);
            }
            JSONArray arr = root.optJSONArray("chapters");
            if(arr != null && arr.length() > 0){
                chapterHandler.removeCallbacksAndMessages(null);
                pendingChapters.clear();
                chapters.clear();
                for(int i=0;i<arr.length();i++){
                    JSONObject item = arr.optJSONObject(i);
                    if(item == null) continue;
                    chapters.add(new MangaChapter(item.optString("slug", manga.slug), (float)item.optDouble("index", -1), item.optString("title", ""), item.optString("date", "")));
                }
                applyOrder(false);
            }
        }catch(Exception ignored){}
    }

    private void saveSnapshot(){
        if(!isAdded() || manga == null || empty(manga.slug)) return;
        MangaPost snapshotManga = manga;
        ArrayList<MangaChapter> snapshotChapters = new ArrayList<>(chapters);
        Context appContext = requireContext().getApplicationContext();
        String key = cacheKey();
        MangaCoroutines.io(() -> {
            try{
                JSONObject root = new JSONObject();
                JSONObject m = new JSONObject();
                String snapshotType = MangaLabelUtils.typeLabel(snapshotManga);
                if(!empty(snapshotType)) snapshotManga.typeLabel = snapshotType;
                m.put("title", snapshotManga.title); m.put("coverImage", snapshotManga.coverImage); m.put("author", snapshotManga.author); m.put("status", snapshotManga.status); m.put("synopsis", snapshotManga.synopsis); m.put("genre", snapshotManga.genre); m.put("typeLabel", snapshotType); m.put("info", snapshotManga.info); m.put("latestChapter", snapshotManga.latestChapter); m.put("latestChapterDate", snapshotManga.latestChapterDate); m.put("totalChapters", snapshotManga.totalChapters);
                root.put("manga", m);
                JSONArray arr = new JSONArray();
                int limit = Math.min(snapshotChapters.size(), 700);
                for(int i=0;i<limit;i++){
                    MangaChapter ch = snapshotChapters.get(i);
                    JSONObject item = new JSONObject();
                    item.put("slug", ch.slug); item.put("index", ch.index); item.put("title", ch.title); item.put("date", ch.date);
                    arr.put(item);
                }
                root.put("chapters", arr);
                appContext.getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE).edit().putString(key, root.toString()).apply();
            }catch(Exception ignored){}
        });
    }

    private String cacheKey(){
        String source = manga == null ? "" : manga.getSourceId();
        String version = "";
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(source)) version = "_detail_v2";
        return source + "_" + (manga == null ? "" : manga.slug) + version;
    }
    private boolean getSavedChapterOrder(){ if(!isAdded()) return false; return requireContext().getApplicationContext().getSharedPreferences(CHAPTER_ORDER_PREFS, Context.MODE_PRIVATE).getBoolean(orderKey(), false); }
    private void saveChapterOrder(){ if(!isAdded()) return; requireContext().getApplicationContext().getSharedPreferences(CHAPTER_ORDER_PREFS, Context.MODE_PRIVATE).edit().putBoolean(orderKey(), newestFirst).apply(); }
    private String orderKey(){ return "global_chapter_order_newest_first"; }
    private void openStartChapter(){
        if(chapters.isEmpty()){ Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show(); return; }
        int pos = findResumeChapterPosition();
        if(pos < 0) pos = findEarliestChapterPosition();
        openChapter(pos);
    }

    private int findResumeChapterPosition(){
        float target = MangaHistoryManager.getLastReadChapterIndex(requireContext(), manga);
        if(target < 0f && manga != null && MangaHistoryManager.hasHistoryFor(requireContext(), manga)) target = manga.historyChapterIndex;
        if(target < 0f) return -1;
        for(int i=0;i<chapters.size();i++){
            MangaChapter chapter = chapters.get(i);
            if(chapter != null && Math.abs(chapter.index - target) < 0.001f) return i;
        }
        return -1;
    }
    private int findEarliestChapterPosition(){ if(chapters.isEmpty()) return -1; int best = 0; float bestIndex = chapters.get(0).index; for(int i=1;i<chapters.size();i++){ if(chapters.get(i).index < bestIndex){ bestIndex = chapters.get(i).index; best = i; } } return best; }
    private void showTitleMenu(){ if(!isAdded() || manga == null) return; PopupMenu menu = new PopupMenu(requireContext(), titleText); menu.getMenu().add("Salin judul Manga"); menu.setOnMenuItemClickListener(item -> { copyTitleToClipboard(); return true; }); menu.show(); }
    private void copyTitleToClipboard(){ String title = manga == null || empty(manga.title) ? "Manga" : manga.title; ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE); if(clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Judul Manga", title)); Toast.makeText(requireContext(), "Judul manga disalin", Toast.LENGTH_SHORT).show(); }
    private void toggleChapterLayout(){ if(!isAdded()) return; boolean nextGrid = !MangaSettingsManager.isChapterGrid2(requireContext()); MangaSettingsManager.setChapterLayout(requireContext(), nextGrid ? MangaSettingsManager.CHAPTER_LAYOUT_GRID_2 : MangaSettingsManager.CHAPTER_LAYOUT_DEFAULT); applyChapterLayout(); if(chapterAdapter != null) chapterAdapter.notifyDataSetChanged(); animateChaptersList(); }
    private void applyChapterLayout(){ if(chaptersListView!=null && isAdded()) { boolean grid = MangaSettingsManager.isChapterGrid2(requireContext()); chaptersListView.setNumColumns(grid ? 2 : 1); if(chapterLayoutButton != null) chapterLayoutButton.setIconResource(grid ? R.drawable.ic_view_list_manga : R.drawable.ic_grid_2); if(stickyChapterLayoutButton != null) stickyChapterLayoutButton.setIconResource(grid ? R.drawable.ic_view_list_manga : R.drawable.ic_grid_2); } }
    private void openChapter(int position){ if(position>=0 && position<chapters.size() && isAdded()) openReaderTarget(manga, new ArrayList<>(chapters), position); }
    private void openDetailTarget(MangaPost post){ if(!isAdded() || post == null) return; if(requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaDetail(post); else if(requireActivity() instanceof MikuAll) ((MikuAll) requireActivity()).openMangaDetail(post); }
    private void openGenreTarget(String sourceId, String sourceLabel, String genreTitle, String genreValue){ if(!isAdded()) return; if(requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaGenreResult(sourceId, sourceLabel, genreTitle, genreValue); else if(requireActivity() instanceof MikuAll) ((MikuAll) requireActivity()).openMangaGenreResult(sourceId, sourceLabel, genreTitle, genreValue); }
    private void openReaderTarget(MangaPost manga, ArrayList<MangaChapter> chapters, int position){ if(!isAdded()) return; if(requireActivity() instanceof MainActivity) ((MainActivity)requireActivity()).openMangaReader(manga, chapters, position); else if(requireActivity() instanceof MikuAll) ((MikuAll)requireActivity()).openMangaReader(manga, chapters, position); }
    private void saveCover(){ if(empty(manga.coverImage)){ Toast.makeText(requireContext(), "Sampul belum tersedia", Toast.LENGTH_SHORT).show(); return;} Glide.with(this).asBitmap().load(glideUrl(manga.coverImage)).into(new CustomTarget<Bitmap>(){ public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> tr){ try{ String name="manga_"+System.currentTimeMillis()+".jpg"; ContentValues values=new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME,name); values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg"); if(Build.VERSION.SDK_INT>=29) values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MikuMoe"); Uri uri=requireContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); if(uri==null) throw new Exception("uri null"); OutputStream out=requireContext().getContentResolver().openOutputStream(uri); bitmap.compress(Bitmap.CompressFormat.JPEG,95,out); if(out!=null) out.close(); Toast.makeText(requireContext(), "Sampul tersimpan", Toast.LENGTH_SHORT).show(); }catch(Exception e){ Toast.makeText(requireContext(), "Sampul gagal disimpan", Toast.LENGTH_SHORT).show(); }} public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder){} }); }
    private void loadImage(ImageView v, String url, boolean animate){ if(v == null) return; if(empty(url)){ MangaImageLoader.clear(v); return; } v.animate().cancel(); v.setAlpha(1f); MangaImageLoader.loadForSource(v, url, manga == null ? null : manga.getSourceId(), true, null); }
    private GlideUrl glideUrl(String url){
        String sourceId=manga==null?"":manga.getSourceId();
        String base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI);
        else if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU);
        else if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_WESTMANGA);
        else if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_BACAKOMIK);
        else if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKINDO);
        else if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_IKIRU);
        else if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKU);
        else if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_MANGASUSU);
        else if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL);
        else if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_NATSU);
        else if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_AINZSCANSS);
        else if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_APKOMIK);
        else if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_COMICASO);
        else if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_CROTPEDIA);
        else if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_NGOMIK);
        else if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KUMOPOI);
        else if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) base=MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_COSMICSCANS);
        String referer=base.endsWith("/")?base:base+"/";
        String origin=base;
        return new GlideUrl(url, new LazyHeaders.Builder().addHeader("Referer",referer).addHeader("Origin",origin).addHeader("User-Agent","Mozilla/5.0").addHeader("Accept","image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8").build());
    }
    private void setInfo(TextView t, String s){ if(empty(s)||s.endsWith(": ")) t.setVisibility(View.GONE); else { t.setVisibility(View.VISIBLE); t.setText(s); } }
    private boolean empty(String s){ return s==null||s.trim().isEmpty(); }
}
