package miku.moe.app;

import android.os.Bundle;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class BaseMangaGridFragment extends Fragment {
    protected GridView gridView; protected ProgressBar progressBar, bottomProgressBar; protected TextView titleTextView, helper, headerSubtitle;
    protected TextInputLayout searchInputLayout; protected TextInputEditText searchEditText; protected TabLayout tabs, sourceTabs;
    protected HorizontalScrollView genreScrollView, typeScrollView; protected ChipGroup genreChipGroup, typeChipGroup; protected View searchHeaderCard; protected TextView genreTitleTextView, typeTitleTextView;
    private ImageView genreFilterButton;
    private View errorFeedbackView; private ImageView errorFeedbackIcon; private TextView errorFeedbackTitle, errorFeedbackMessage; private MaterialButton errorFeedbackButton;
    private boolean waitingCloudflareResolver = false;
    protected final ArrayList<MangaPost> posts = new ArrayList<>(); protected MangaGridAdapter adapter;
    protected int page = 1; protected boolean loading=false, hasMore=true; protected KomikcastClient api;
    private MangaGridViewModel gridViewModel;
    protected final HashSet<String> loadedSlugs = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int loadGeneration = 0;
    private int savedFirstVisiblePosition = 0;
    private int savedTopOffset = 0;
    private static final int PREFETCH_DISTANCE = 2;
    private static final int MAX_COVER_PRELOAD = 3;
    private static final int FILTERED_AUTO_LOAD_TARGET = 12;
    private static final int FILTERED_AUTO_LOAD_LIMIT = 3;
    private static final long PAGE_CACHE_TTL = 8L * 60L * 1000L;
    private static final MangaMemoryCache<String, PageCache> PAGE_CACHE = new MangaMemoryCache<>(56, PAGE_CACHE_TTL);
    private static final String TAB_DEFAULT = "__default";
    private static final String TAB_GLOBAL = "__global";
    private final LinkedHashMap<String, GlobalSourceState> globalStates = new LinkedHashMap<>();
    private String activeSearchTab = TAB_GLOBAL;
    private String activeGlobalSourceId = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
    private int globalGeneration = 0;
    private boolean suppressTabEvents = false;
    private boolean suppressGlobalSourceTabEvents = false;
    private String homeSourceId = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
    private boolean suppressHomeSourceTabEvents = false;
    private int filteredAutoLoadCount = 0;
    private boolean enrichingVisible = false;
    private final ArrayList<KomikcastClient.GenreItem> genreMenuItems = new ArrayList<>();
    private boolean genreMenuLoading = false;
    private final HashSet<String> latestEnrichKeys = new HashSet<>();
    private static final int LATEST_ENRICH_BATCH = 4;
    private static final float DPI_600 = 600f;
    protected String query=""; protected String sort="latest"; protected String selectedGenre=""; protected String selectedTypeLabel="";
    protected String title(){ return searchPage() ? "Pencarian Manga" : ""; } protected boolean searchPage(){ return false; } protected boolean staticList(){ return false; } protected boolean showSortTabs(){ return true; } protected String forcedSourceId(){ return ""; } protected boolean showHomeSourceTabs(){ return true; } protected boolean showTypeFilterChips(){ return true; } protected boolean showTitleHeader(){ return searchPage() || !empty(forcedSourceId()); } protected boolean useIkiruLayout(){ return !empty(forcedSourceId()); } protected String searchSourceOnlyId(){ return ""; } protected boolean showSearchSourceTabs(){ return true; } protected String initialQuery(){ return ""; } protected boolean stableMangaGrid(){ return true; } protected int searchGridColumns(){ return 4; }
    protected void onPostClick(MangaPost p){
        if (!isAdded()) return;
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).openMangaDetail(p);
        else if (getActivity() instanceof MikuAll) ((MikuAll) getActivity()).openMangaDetail(p);
    }
    protected void onLatestChapterClick(MangaPost post) {
        if (!isAdded() || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(post.getSourceId());
        sourceApi.chapters(post.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (!isAdded()) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (chapters == null || chapters.isEmpty()) {
                    Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                int pos = findChapterPosition(chapters, post.latestChapter);
                if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).openMangaReader(post, new ArrayList<>(chapters), pos);
                else if (getActivity() instanceof MikuAll) ((MikuAll) getActivity()).openMangaReader(post, new ArrayList<>(chapters), pos);
            }
            @Override public void onError(String message) {
                if (!isAdded()) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                Toast.makeText(requireContext(), message == null || message.trim().isEmpty() ? "Gagal membuka chapter" : message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int findChapterPosition(ArrayList<MangaChapter> chapters, String latestChapter) {
        float target = parseChapterIndex(latestChapter);
        if (target >= 0f) {
            for (int i = 0; i < chapters.size(); i++) {
                if (Math.abs(chapters.get(i).index - target) < 0.001f) return i;
            }
        }
        int newest = 0;
        for (int i = 1; i < chapters.size(); i++) {
            if (chapters.get(i).index > chapters.get(newest).index) newest = i;
        }
        return newest;
    }

    private float parseChapterIndex(String text) {
        if (text == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'));
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch (Exception ignored) { return -1f; }
    }

    private static final String[][] GENRES = new String[][]{
            {"4-Koma","4-Koma"},{"Adventure","Adventure"},{"Cooking","Cooking"},{"Game","Game"},{"Gore","Gore"},{"Harem","Harem"},{"Historical","Historical"},{"Horror","Horror"},{"Isekai","Isekai"},{"Josei","Josei"},{"Magic","Magic"},{"Martial Arts","Martial Arts"},{"Mature","Mature"},{"Mecha","Mecha"},{"Medical","Medical"},{"Military","Military"},{"Music","Music"},{"Mystery","Mystery"},{"One-Shot","One-Shot"},{"Police","Police"},{"Psychological","Psychological"},{"Reincarnation","Reincarnation"},{"Romance","Romance"},{"School","School"},{"School Life","School Life"},{"Sci-Fi","Sci-Fi"},{"Seinen","Seinen"},{"Shoujo","Shoujo"},{"Shoujo Ai","Shoujo Ai"},{"Action","Action"},{"Comedy","Comedy"},{"Demons","Demons"},{"Drama","Drama"},{"Ecchi","Ecchi"},{"Fantasy","Fantasy"},{"Gender Bender","Gender Bender"},{"Shounen","Shounen"},{"Shounen Ai","Shounen Ai"},{"Slice of Life","Slice of Life"},{"Sports","Sports"},{"Super Power","Super Power"},{"Supernatural","Supernatural"},{"Thriller","Thriller"},{"Tragedy","Tragedy"},{"Vampire","Vampire"},{"Webtoons","Webtoons"},{"Yuri","Yuri"}
    };

    private static final String[][] COMICASO_NORMAL_GENRES = new String[][]{
            {"Action","action"},{"Adaptation","adaptation"},{"Adult","adult"},{"Adventure","adventure"},{"College Life","college-life"},{"Comedy","comedy"},{"Cooking","cooking"},{"Crime","crime"},{"Drama","drama"},{"Ecchi","ecchi"},{"Fantasy","fantasy"},{"Full Color","full-color"},{"Harem","harem"},{"Historical","historical"},{"Horror","horror"},{"Isekai","isekai"},{"Josei(W)","josei-w"},{"Magic","magic"},{"Martial Arts","martial-arts"},{"Mature","mature"},{"Mystery","mystery"},{"Office Workers","office-workers"},{"Omegaverse","omegaverse"},{"Psychological","psychological"},{"Reincarnation","reincarnation"},{"Romance","romance"},{"School Life","school-life"},{"Seinen(M)","seinen-m"},{"Shoujo(G)","shoujo-g"},{"Shounen ai","shounen-ai"},{"Shounen(B)","shounen-b"},{"Showbiz","showbiz"},{"Slice of Life","slice-of-life"},{"Smut","smut"},{"Sports","sports"},{"Supernatural","supernatural"},{"Tragedy","tragedy"},{"Yakuzas","yakuzas"},{"Yaoi(BL)","yaoi-bl"}
    };

    private static final String[][] COMICASO_ADULT_GENRES = new String[][]{
            {"Action","action"},{"Adaptation","adaptation"},{"Adult","adult"},{"Comedy","comedy"},{"Crime","crime"},{"Drama","drama"},{"Ecchi","ecchi"},{"Fantasy","fantasy"},{"Full Color","full-color"},{"Futanari","futanari"},{"Harem","harem"},{"Hentai","hentai"},{"Historical","historical"},{"Horror","horror"},{"Josei(W)","josei-w"},{"Lolicon","lolicon"},{"Mature","mature"},{"Mystery","mystery"},{"Omegaverse","omegaverse"},{"Psychological","psychological"},{"Romance","romance"},{"School Life","school-life"},{"Sci-Fi","sci-fi"},{"Seinen(M)","seinen-m"},{"Shounen ai","shounen-ai"},{"Shounen(B)","shounen-b"},{"Showbiz","showbiz"},{"Slice of Life","slice-of-life"},{"Smut","smut"},{"Sports","sports"},{"Supernatural","supernatural"},{"Yakuzas","yakuzas"},{"Yaoi(BL)","yaoi-bl"},{"Yuri(GL)","yuri-gl"}
    };

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle b) { return inflater.inflate(useIkiruLayout() ? R.layout.fragment_ikiru_manga_grid : R.layout.fragment_manga_grid, c, false); }

    private static class PageCache {
        final ArrayList<MangaPost> data;
        final boolean hasNext;
        PageCache(ArrayList<MangaPost> data, boolean hasNext) {
            this.data = data == null ? new ArrayList<>() : new ArrayList<>(data);
            this.hasNext = hasNext;
        }
    }

    /** Hapus entri page cache (browse + global search) milik satu source saja. */
    private static void clearPageCacheForSource(String sourceId) {
        if (sourceId == null) return;
        String cleaned = sourceId.trim().toLowerCase(Locale.ROOT);
        PAGE_CACHE.removeByPrefix(cleaned + "|");
        PAGE_CACHE.removeByPrefix("global|" + cleaned + "|");
    }

    private static class GlobalSourceState {
        final String sourceId;
        final String sourceLabel;
        final ArrayList<MangaPost> data = new ArrayList<>();
        final HashSet<String> slugs = new HashSet<>();
        int page = 1;
        boolean loading = false;
        boolean hasMore = true;
        boolean loadedOnce = false;
        String lastError = "";
        GlobalSourceState(String sourceId) {
            this.sourceId = sourceId;
            this.sourceLabel = MangaSourceFactory.labelForSourceId(sourceId);
        }
    }

    private String cacheKey(int targetPage) {
        String source = currentSourceId();
        String typeKey = usesLocalTypeFilter(source) && !MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(source) ? "" : selectedTypeLabel;
        return source + "|" + targetPage + "|" + cleanKey(sort) + "|" + cleanKey(query) + "|" + cleanKey(selectedGenre) + "|" + cleanKey(typeKey);
    }

    private String globalCacheKey(String sourceId, int targetPage) {
        String typeKey = usesLocalTypeFilter(sourceId) && !MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId) ? "" : selectedTypeLabel;
        return "global|" + cleanKey(sourceId) + "|" + targetPage + "|" + cleanKey(query) + "|" + cleanKey(selectedGenre) + "|" + cleanKey(typeKey);
    }

    private String cleanKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void saveScrollPosition() {
        if (gridView == null) return;
        savedFirstVisiblePosition = gridView.getFirstVisiblePosition();
        View firstChild = gridView.getChildAt(0);
        savedTopOffset = firstChild == null ? 0 : firstChild.getTop() - gridView.getPaddingTop();
    }

    private void restoreScrollPosition() {
        if (gridView == null) return;
        mainHandler.post(() -> {
            if (gridView != null) gridView.setSelectionFromTop(savedFirstVisiblePosition, savedTopOffset);
        });
    }

    @Override public void onPause() {
        saveScrollPosition();
        super.onPause();
    }

    @Override public void onDestroyView() {
        loadGeneration++;
        globalGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        errorFeedbackView = null;
        errorFeedbackIcon = null;
        errorFeedbackTitle = null;
        errorFeedbackMessage = null;
        errorFeedbackButton = null;
        if (tabs != null) tabs.clearOnTabSelectedListeners();
        if (sourceTabs != null) sourceTabs.clearOnTabSelectedListeners();
        if (gridView != null) {
            gridView.setOnScrollListener(null);
            gridView.setRecyclerListener(null);
            gridView.setAdapter(null);
        }
        gridView = null;
        adapter = null;
        super.onDestroyView();
    }

    private void setupGridPerformance() {
        if (gridView == null) return;
        gridView.setSmoothScrollbarEnabled(true);
        gridView.setScrollingCacheEnabled(false);
        gridView.setAnimationCacheEnabled(false);
        gridView.setCacheColorHint(0x00000000);
        gridView.setRecyclerListener(scrapView -> { });
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        gridViewModel = new ViewModelProvider(this).get(MangaGridViewModel.class);
        homeSourceId = !empty(forcedSourceId()) ? forcedSourceId() : (isAdded() ? MangaSettingsManager.getMangaSource(requireContext()) : MangaSettingsManager.MANGA_SOURCE_KOMIKCAST); api = MangaSourceFactory.createBySourceId(homeSourceId); gridView=v.findViewById(R.id.gridView); ViewCompat.setNestedScrollingEnabled(gridView,true); progressBar=v.findViewById(R.id.progressBar); bottomProgressBar=v.findViewById(R.id.bottomProgressBar); titleTextView=v.findViewById(R.id.titleTextView); searchInputLayout=v.findViewById(R.id.searchInputLayout); searchEditText=v.findViewById(R.id.searchEditText); helper=v.findViewById(R.id.searchHelperTextView); tabs=v.findViewById(R.id.homeTabLayout); sourceTabs=v.findViewById(R.id.sourceTabLayout); configureScrollableTabs(tabs); configureScrollableTabs(sourceTabs); genreScrollView=v.findViewById(R.id.genreScrollView); genreChipGroup=v.findViewById(R.id.genreChipGroup); typeScrollView=v.findViewById(R.id.typeScrollView); typeChipGroup=v.findViewById(R.id.typeChipGroup); genreTitleTextView=v.findViewById(R.id.genreTitleTextView); typeTitleTextView=v.findViewById(R.id.typeTitleTextView); genreFilterButton=v.findViewById(R.id.genreFilterButton); headerSubtitle=v.findViewById(R.id.headerSubtitleTextView); searchHeaderCard=v.findViewById(R.id.searchHeaderCard); setupErrorFeedback(v);
        boolean ikiruLayout = useIkiruLayout();
        if (searchHeaderCard != null) {
            searchHeaderCard.setVisibility((searchPage() || ikiruLayout) ? View.VISIBLE : View.GONE);
            if (ikiruLayout && !searchPage()) searchHeaderCard.setOnClickListener(x -> {
                String sourceId = currentSourceId();
                String sourceLabel = MangaSourceFactory.labelForSourceId(sourceId);
                if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).openMangaBrowseSource(sourceId, sourceLabel, "");
                else if (getActivity() instanceof MikuAll) ((MikuAll) getActivity()).openMangaBrowseSource(sourceId, sourceLabel, "");
            });
        }
        if (titleTextView != null) { titleTextView.setText(title()); titleTextView.setVisibility(showTitleHeader() ? View.VISIBLE : View.GONE); }
        if (headerSubtitle != null) {
            headerSubtitle.setText(searchPage() ? "Cari judul atau genre" : "");
            headerSubtitle.setVisibility(searchPage() ? View.VISIBLE : View.GONE);
        }
        setupGridPerformance(); applyGridColumns(); adapter=new MangaGridAdapter(requireContext(), posts, p -> onPostClick(p), false, p -> onLatestChapterClick(p)); adapter.setIkiruStyle(ikiruLayout); adapter.setSearchCompact(searchPage()); gridView.setAdapter(adapter); setupGridSingleTapHandling();
        if (searchPage()) {
            activeGlobalSourceId = MangaSettingsManager.getMangaSource(requireContext());
            searchInputLayout.setVisibility(View.VISIBLE); if (helper != null) { helper.setVisibility(showSearchSourceTabs() ? View.VISIBLE : View.GONE); helper.setText(showSearchSourceTabs() ? "Global untuk semua source." : ""); } searchInputLayout.setHint("Cari manga"); searchEditText.setHint("Cari manga");
            String startQuery = initialQuery();
            if (!empty(startQuery) && empty(query)) {
                query = startQuery;
                searchEditText.setText(startQuery);
                searchEditText.setSelection(searchEditText.getText() == null ? 0 : searchEditText.getText().length());
            }
            searchEditText.setOnEditorActionListener((tv, actionId, event) -> { if(actionId== EditorInfo.IME_ACTION_SEARCH){ query=tv.getText()==null?"":tv.getText().toString(); reload(); return true;} return false; });
            searchInputLayout.setEndIconOnClickListener(x->{ query=searchEditText.getText()==null?"":searchEditText.getText().toString(); reload(); });
            if (showSearchSourceTabs()) setupSearchTabs(); else hideSearchSourceTabs();
            setupGenreChips();
            setupTypeFilterChips();
        } else {
            String sourceLabel = MangaSourceFactory.labelForSourceId(currentSourceId());
            if (searchInputLayout != null) searchInputLayout.setVisibility(ikiruLayout ? View.VISIBLE : View.GONE);
            if (searchEditText != null && ikiruLayout) { searchEditText.setText(""); searchEditText.setHint(sourceLabel); searchEditText.setFocusable(false); searchEditText.setClickable(false); }
            if (searchInputLayout != null && ikiruLayout) searchInputLayout.setHint(sourceLabel);
            if (helper != null) helper.setVisibility(View.GONE);
            if (!staticList() && showTypeFilterChips()) setupTypeFilterChips(); else if (genreScrollView != null) genreScrollView.setVisibility(View.GONE);
        }
        if (!searchPage() && !staticList()) { if (showHomeSourceTabs()) setupHomeSourceTabs(); else if (sourceTabs != null) sourceTabs.setVisibility(View.GONE); if (showSortTabs()) setupSortTabs(); else if (tabs != null) { tabs.setVisibility(View.GONE); tabs.removeAllTabs(); } }
        gridView.setOnScrollListener(new AbsListView.OnScrollListener(){ public void onScrollStateChanged(AbsListView v,int s){} public void onScroll(AbsListView v,int first,int visible,int total){ if(!staticList() && total>0 && visible>0 && first+visible>=total-PREFETCH_DISTANCE) { if(searchPage() && !TAB_DEFAULT.equals(activeSearchTab)) loadGlobalNextPage(); else if(hasMore && !loading) load(false); } }});
        reload();
    }

    private void configureScrollableTabs(TabLayout tabLayout) {
        if (tabLayout == null) return;
        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabLayout.setTabGravity(TabLayout.GRAVITY_START);
        tabLayout.setTabIndicatorFullWidth(true);
        tabLayout.setInlineLabel(false);
        tabLayout.setTabRippleColorResource(android.R.color.transparent);
        tabLayout.post(tabLayout::requestLayout);
    }

    private void setupSortTabs() {
        if (tabs == null) return;
        configureScrollableTabs(tabs);
        tabs.setVisibility(View.VISIBLE);
        tabs.clearOnTabSelectedListeners();
        tabs.removeAllTabs();
        boolean doujinDesu = MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(currentSourceId());
        boolean ikiru = MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(currentSourceId());
        boolean komiku = MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(currentSourceId());
        boolean mangasusu = MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(currentSourceId());
        boolean komikuOrg = MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(currentSourceId());
        boolean cosmicScans = MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(currentSourceId());
        boolean kiryuuOfficial = MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(currentSourceId());
        boolean natsu = MangaSettingsManager.MANGA_SOURCE_NATSU.equals(currentSourceId());
        boolean ainzscanss = MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(currentSourceId());
        boolean apkomik = MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(currentSourceId());
        boolean comicaso = MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(currentSourceId());
        boolean kumopoi = MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(currentSourceId());
        boolean crotpedia = MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(currentSourceId());
        boolean ngomik = MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(currentSourceId());
        boolean komikcast = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST.equals(currentSourceId());
        boolean komikTap = MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(currentSourceId());
        boolean manhwaIndo = MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(currentSourceId());
        boolean soulScans = MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(currentSourceId());
        boolean kuromanga = MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(currentSourceId());
        boolean isekaiKomik = MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(currentSourceId());
        boolean luvyaa = MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(currentSourceId());
        boolean sektedoujin = MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(currentSourceId());
        if (komikcast) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Project").setTag("project"));
            tabs.addTab(tabs.newTab().setText("Miror").setTag("mirror"));
            tabs.addTab(tabs.newTab().setText("Most Bookmark").setTag("bookmark"));
            tabs.addTab(tabs.newTab().setText("New Series").setTag("newseries"));
            tabs.addTab(tabs.newTab().setText("Rating").setTag("rating"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
        } else if (comicaso) {
            tabs.addTab(tabs.newTab().setText("Update").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("New").setTag("new"));
            tabs.addTab(tabs.newTab().setText("Completed").setTag("completed"));
            tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
            tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
            tabs.addTab(tabs.newTab().setText("Manhua").setTag("manhua"));
            tabs.addTab(tabs.newTab().setText("Dewasa Update").setTag("adult_update"));
            tabs.addTab(tabs.newTab().setText("Dewasa New").setTag("adult_new"));
            tabs.addTab(tabs.newTab().setText("Dewasa Completed").setTag("adult_completed"));
            tabs.addTab(tabs.newTab().setText("Manga Dewasa").setTag("adult_manga"));
            tabs.addTab(tabs.newTab().setText("Manhwa Dewasa").setTag("adult_manhwa"));
            tabs.addTab(tabs.newTab().setText("Manhua Dewasa").setTag("adult_manhua"));
        } else if (crotpedia) {
            tabs.addTab(tabs.newTab().setText("Update").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popularity"));
            tabs.addTab(tabs.newTab().setText("Rating").setTag("rating"));
            tabs.addTab(tabs.newTab().setText("Latest Added").setTag("added"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
            tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
            tabs.addTab(tabs.newTab().setText("Image-set").setTag("image-set"));
            tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
            tabs.addTab(tabs.newTab().setText("One-shot").setTag("one-shot"));
            tabs.addTab(tabs.newTab().setText("Doujinshi").setTag("doujinshi"));
        } else if (ngomik) {
            tabs.addTab(tabs.newTab().setText("Update").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Added").setTag("added"));
            tabs.addTab(tabs.newTab().setText("Popular").setTag("popularity"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
        } else if (komikTap) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Added").setTag("added"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
        } else if (manhwaIndo) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Added").setTag("added"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Completed").setTag("completed"));
            tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
            tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
            tabs.addTab(tabs.newTab().setText("Manhua").setTag("manhua"));
        } else if (soulScans) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Added").setTag("added"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
            tabs.addTab(tabs.newTab().setText("Project").setTag("project"));
            tabs.addTab(tabs.newTab().setText("Completed").setTag("completed"));
            tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
            tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
            tabs.addTab(tabs.newTab().setText("Manhua").setTag("manhua"));
        } else if (kuromanga) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Added").setTag("added"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
        } else if (isekaiKomik) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Added").setTag("added"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Completed").setTag("completed"));
        } else if (luvyaa) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Baru Ditambahkan").setTag("added"));
            tabs.addTab(tabs.newTab().setText("Project").setTag("project"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
            tabs.addTab(tabs.newTab().setText("Selesai").setTag("completed"));
            tabs.addTab(tabs.newTab().setText("Berjalan").setTag("ongoing"));
            tabs.addTab(tabs.newTab().setText("Hiatus").setTag("hiatus"));
        } else if (sektedoujin) {
            tabs.addTab(tabs.newTab().setText("Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText("Populer").setTag("popular"));
            tabs.addTab(tabs.newTab().setText("Baru Ditambahkan").setTag("added"));
            tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
            tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
        } else {
            tabs.addTab(tabs.newTab().setText(ainzscanss ? "Latest" : natsu || kiryuuOfficial ? "Update" : "Terbaru").setTag("latest"));
            tabs.addTab(tabs.newTab().setText(ainzscanss ? "Top Views" : "Populer").setTag(ainzscanss ? "views" : ikiru ? "popular" : "popularity"));
            if (ikiru) {
                tabs.addTab(tabs.newTab().setText("Rating").setTag("rating"));
                tabs.addTab(tabs.newTab().setText("Project").setTag("project"));
            } else if (komikuOrg) {
                tabs.addTab(tabs.newTab().setText("Judul Terbaru").setTag("title_latest"));
                tabs.addTab(tabs.newTab().setText("Random").setTag("random"));
                tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
                tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
                tabs.addTab(tabs.newTab().setText("Manhua").setTag("manhua"));
            } else if (komiku) {
                tabs.addTab(tabs.newTab().setText("Newest").setTag("newest"));
                tabs.addTab(tabs.newTab().setText("Rating").setTag("rating"));
                tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
                tabs.addTab(tabs.newTab().setText("Ongoing").setTag("ongoing"));
                tabs.addTab(tabs.newTab().setText("Completed").setTag("completed"));
                tabs.addTab(tabs.newTab().setText("Hiatus").setTag("hiatus"));
                tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
                tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
                tabs.addTab(tabs.newTab().setText("Manhua").setTag("manhua"));
            } else if (mangasusu) {
                tabs.addTab(tabs.newTab().setText("Baru ditambahkan").setTag("added"));
            } else if (cosmicScans) {
                tabs.addTab(tabs.newTab().setText("New Added").setTag("added"));
                tabs.addTab(tabs.newTab().setText("A-Z").setTag("az"));
                tabs.addTab(tabs.newTab().setText("Z-A").setTag("za"));
                tabs.addTab(tabs.newTab().setText("Project").setTag("project"));
            } else if (natsu || kiryuuOfficial || kumopoi) {
                tabs.addTab(tabs.newTab().setText("Project").setTag("project"));
            } else if (ainzscanss) {
                tabs.addTab(tabs.newTab().setText("Top Favorite").setTag("bookmark"));
                tabs.addTab(tabs.newTab().setText("Top Rate").setTag("rate"));
            } else if (apkomik) {
                tabs.addTab(tabs.newTab().setText("Project").setTag("project"));
                tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
                tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
                tabs.addTab(tabs.newTab().setText("Manhua").setTag("manhua"));
            } else if (doujinDesu) {
                tabs.addTab(tabs.newTab().setText("Newest").setTag("newest"));
                tabs.addTab(tabs.newTab().setText("A-Z").setTag("title_asc"));
                tabs.addTab(tabs.newTab().setText("Oldest").setTag("oldest"));
                tabs.addTab(tabs.newTab().setText("Manga").setTag("manga"));
                tabs.addTab(tabs.newTab().setText("Manhwa").setTag("manhwa"));
                tabs.addTab(tabs.newTab().setText("Manhua").setTag("manhua"));
                tabs.addTab(tabs.newTab().setText("Doujinshi").setTag("doujinshi"));
            }
        }
        int selected = komikcast ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("project".equals(sort)) ? 2 : ("mirror".equals(sort)) ? 3 : ("bookmark".equals(sort)) ? 4 : ("newseries".equals(sort)) ? 5 : ("rating".equals(sort)) ? 6 : ("az".equals(sort)) ? 7 : 0) : komikTap ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort)) ? 2 : ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort)) ? 3 : ("za".equals(sort) || "z-a".equals(sort) || "titlereverse".equals(sort)) ? 4 : 0) : manhwaIndo ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort)) ? 2 : ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort)) ? 3 : ("completed".equals(sort) || "complete".equals(sort)) ? 4 : "manga".equals(sort) ? 5 : "manhwa".equals(sort) ? 6 : "manhua".equals(sort) ? 7 : 0) : soulScans ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort)) ? 2 : ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort)) ? 3 : ("za".equals(sort) || "z-a".equals(sort) || "titlereverse".equals(sort)) ? 4 : ("project".equals(sort) || "projects".equals(sort)) ? 5 : ("completed".equals(sort) || "complete".equals(sort)) ? 6 : "manga".equals(sort) ? 7 : "manhwa".equals(sort) ? 8 : "manhua".equals(sort) ? 9 : 0) : kuromanga ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort)) ? 2 : ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort)) ? 3 : ("za".equals(sort) || "z-a".equals(sort) || "titlereverse".equals(sort)) ? 4 : 0) : isekaiKomik ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort)) ? 2 : ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort)) ? 3 : ("completed".equals(sort) || "complete".equals(sort)) ? 4 : 0) : luvyaa ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort)) ? 2 : ("project".equals(sort) || "projects".equals(sort)) ? 3 : ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort)) ? 4 : ("za".equals(sort) || "z-a".equals(sort) || "titlereverse".equals(sort)) ? 5 : ("completed".equals(sort) || "complete".equals(sort)) ? 6 : ("ongoing".equals(sort)) ? 7 : ("hiatus".equals(sort)) ? 8 : 0) : sektedoujin ? (("popular".equals(sort) || "popularity".equals(sort)) ? 1 : ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort)) ? 2 : ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort)) ? 3 : ("za".equals(sort) || "z-a".equals(sort) || "titlereverse".equals(sort)) ? 4 : 0) : sortTabIndex(doujinDesu, ikiru, komiku, mangasusu, komikuOrg, cosmicScans, kiryuuOfficial, natsu, ainzscanss, apkomik, comicaso, kumopoi, crotpedia, ngomik);
        TabLayout.Tab selectedTab = tabs.getTabAt(selected);
        if (selectedTab != null) tabs.selectTab(selectedTab);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){
            public void onTabSelected(TabLayout.Tab tab){
                Object tag = tab.getTag();
                sort = tag == null ? "latest" : tag.toString();
                selectedGenre = "";
                selectedTypeLabel = "";
                String defaultType = defaultTypeForSort(sort);
                if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(currentSourceId())) setupGenreChips();
                else if (!MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(currentSourceId()) && defaultType != null && !defaultType.trim().isEmpty()) selectedTypeLabel = defaultType;
                updateGenreFilterButtonState();
                setupTypeFilterChips();
                reload();
            }
            public void onTabUnselected(TabLayout.Tab tab){}
            public void onTabReselected(TabLayout.Tab tab){
                selectedGenre = "";
                selectedTypeLabel = "";
                String defaultType = defaultTypeForSort(sort);
                if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(currentSourceId())) setupGenreChips();
                else if (!MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(currentSourceId()) && defaultType != null && !defaultType.trim().isEmpty()) selectedTypeLabel = defaultType;
                updateGenreFilterButtonState();
                setupTypeFilterChips();
                reload();
            }
        });
    }

    private int sortTabIndex(boolean doujinDesu, boolean ikiru, boolean komiku, boolean mangasusu, boolean komikuOrg, boolean cosmicScans, boolean kiryuuOfficial, boolean natsu, boolean ainzscanss, boolean apkomik, boolean comicaso, boolean kumopoi, boolean crotpedia, boolean ngomik) {
        if (comicaso && "new".equals(sort)) return 1;
        if (comicaso && ("completed".equals(sort) || "complete".equals(sort))) return 2;
        if (comicaso && "manga".equals(sort)) return 3;
        if (comicaso && "manhwa".equals(sort)) return 4;
        if (comicaso && "manhua".equals(sort)) return 5;
        if (comicaso && "adult_update".equals(sort)) return 6;
        if (comicaso && "adult_new".equals(sort)) return 7;
        if (comicaso && "adult_completed".equals(sort)) return 8;
        if (comicaso && "adult_manga".equals(sort)) return 9;
        if (comicaso && "adult_manhwa".equals(sort)) return 10;
        if (comicaso && "adult_manhua".equals(sort)) return 11;
        if (crotpedia && ("popularity".equals(sort) || "popular".equals(sort))) return 1;
        if (crotpedia && ("rating".equals(sort) || "rate".equals(sort))) return 2;
        if (crotpedia && ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort))) return 3;
        if (crotpedia && ("az".equals(sort) || "title".equals(sort) || "a-z".equals(sort))) return 4;
        if (crotpedia && ("za".equals(sort) || "titlereverse".equals(sort) || "z-a".equals(sort))) return 5;
        if (crotpedia && "manga".equals(sort)) return 6;
        if (crotpedia && ("image-set".equals(sort) || "imageset".equals(sort) || "image_set".equals(sort))) return 7;
        if (crotpedia && "manhwa".equals(sort)) return 8;
        if (crotpedia && ("one-shot".equals(sort) || "oneshot".equals(sort) || "one_shot".equals(sort))) return 9;
        if (crotpedia && "doujinshi".equals(sort)) return 10;
        if (ainzscanss && ("views".equals(sort) || "popularity".equals(sort) || "popular".equals(sort))) return 1;
        if (ainzscanss && ("bookmark".equals(sort) || "favorite".equals(sort) || "favorites".equals(sort))) return 2;
        if (ainzscanss && ("rate".equals(sort) || "rating".equals(sort))) return 3;
        if (apkomik && ("project".equals(sort) || "projects".equals(sort))) return 2;
        if (apkomik && "manga".equals(sort)) return 3;
        if (apkomik && "manhwa".equals(sort)) return 4;
        if (apkomik && "manhua".equals(sort)) return 5;
        if (ikiru && ("rating".equals(sort) || "rate".equals(sort))) return 2;
        if (ikiru && ("project".equals(sort) || "projects".equals(sort))) return 3;
        if ("popularity".equals(sort) || "popular".equals(sort)) return 1;
        if (komiku && ("newest".equals(sort) || "new".equals(sort) || "added".equals(sort) || "latest_added".equals(sort))) return 2;
        if (komiku && ("rating".equals(sort) || "rate".equals(sort))) return 3;
        if (komiku && ("az".equals(sort) || "a-z".equals(sort) || "title".equals(sort))) return 4;
        if (komiku && "ongoing".equals(sort)) return 5;
        if (komiku && ("completed".equals(sort) || "complete".equals(sort))) return 6;
        if (komiku && "hiatus".equals(sort)) return 7;
        if (komiku && "manga".equals(sort)) return 8;
        if (komiku && "manhwa".equals(sort)) return 9;
        if (komiku && "manhua".equals(sort)) return 10;
        if (mangasusu && ("added".equals(sort) || "latest_added".equals(sort) || "new".equals(sort))) return 2;
        if (komikuOrg && ("title_latest".equals(sort) || "judul_terbaru".equals(sort) || "date".equals(sort))) return 2;
        if (komikuOrg && ("random".equals(sort) || "rand".equals(sort))) return 3;
        if (komikuOrg && "manga".equals(sort)) return 4;
        if (komikuOrg && "manhwa".equals(sort)) return 5;
        if (komikuOrg && "manhua".equals(sort)) return 6;
        if (cosmicScans && ("added".equals(sort) || "new".equals(sort) || "new_added".equals(sort) || "latest_added".equals(sort))) return 2;
        if (cosmicScans && ("all".equals(sort) || "allcomics".equals(sort) || "all_comics".equals(sort) || "az".equals(sort))) return 3;
        if (cosmicScans && ("za".equals(sort) || "z-a".equals(sort))) return 4;
        if (cosmicScans && ("project".equals(sort) || "projects".equals(sort))) return 5;
        if ((natsu || kiryuuOfficial || kumopoi) && ("project".equals(sort) || "projects".equals(sort))) return 2;
        if (doujinDesu && ("newest".equals(sort) || "new".equals(sort) || "added".equals(sort))) return 2;
        if (doujinDesu && ("title_asc".equals(sort) || "az".equals(sort) || "a-z".equals(sort) || "a_a".equals(sort) || "a-a".equals(sort))) return 3;
        if (doujinDesu && ("oldest".equals(sort) || "old".equals(sort))) return 4;
        if (doujinDesu && "manga".equals(sort)) return 5;
        if (doujinDesu && "manhwa".equals(sort)) return 6;
        if (doujinDesu && "manhua".equals(sort)) return 7;
        if (doujinDesu && "doujinshi".equals(sort)) return 8;
        if (!doujinDesu && !ikiru && !komiku && !mangasusu && !komikuOrg && !cosmicScans && !kiryuuOfficial && !natsu && !ainzscanss && !apkomik && !comicaso && !kumopoi && !crotpedia && !ngomik && !MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(currentSourceId()) && !MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(currentSourceId()) && !MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(currentSourceId()) && !MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(currentSourceId()) && ("manga".equals(sort) || "manhwa".equals(sort) || "manhua".equals(sort) || "ongoing".equals(sort) || "completed".equals(sort) || "added".equals(sort) || "random".equals(sort) || "title_latest".equals(sort) || "project".equals(sort) || "views".equals(sort) || "bookmark".equals(sort) || "rate".equals(sort) || "rating".equals(sort))) {
            sort = "latest";
            selectedTypeLabel = "";
        }
        return 0;
    }

    private String defaultTypeForSort(String value) {
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(currentSourceId())) return "";
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(currentSourceId())) return "";
        if (!MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(currentSourceId()) && !MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(currentSourceId())) return "";
        if ("manga".equals(value)) return "MANGA";
        if ("manhwa".equals(value)) return "MANHWA";
        if ("manhua".equals(value)) return "MANHUA";
        if ("doujinshi".equals(value)) return "DOUJINSHI";
        return "";
    }

    private void setupGridSingleTapHandling() {
        if (gridView == null) return;
        gridView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gridView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= posts.size()) return;
            onPostClick(posts.get(position));
        });
        gridView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && searchEditText != null && searchEditText.hasFocus()) {
                searchEditText.clearFocus();
                InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
            }
            return false;
        });
    }

    private String currentSourceId() {
        if (!empty(forcedSourceId())) return forcedSourceId();
        if (searchPage()) {
            if (TAB_GLOBAL.equals(activeSearchTab)) return activeGlobalSourceId == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : activeGlobalSourceId;
            return isAdded() ? MangaSettingsManager.getMangaSource(requireContext()) : MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
        }
        if (!isAdded()) return homeSourceId == null || homeSourceId.trim().isEmpty() ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : homeSourceId;
        if (homeSourceId == null || homeSourceId.trim().isEmpty() || !MangaSettingsManager.isMangaSourceEnabled(requireContext(), homeSourceId)) homeSourceId = MangaSettingsManager.getFirstEnabledMangaSource(requireContext());
        return homeSourceId;
    }

    private void setupHomeSourceTabs() {
        if (sourceTabs == null || searchPage() || staticList()) return;
        configureScrollableTabs(sourceTabs);
        sourceTabs.setVisibility(View.VISIBLE);
        suppressHomeSourceTabEvents = true;
        sourceTabs.removeAllTabs();
        homeSourceId = currentSourceId();
        for (String sourceId : MangaSourceFactory.enabledSourceIds(requireContext())) {
            TabLayout.Tab tab = sourceTabs.newTab();
            tab.setTag(sourceId);
            tab.setCustomView(createSourceTabView(sourceId));
            sourceTabs.addTab(tab, sourceId.equals(currentSourceId()));
        }
        updateHomeSourceTabStyles();
        suppressHomeSourceTabEvents = false;
        sourceTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){
            public void onTabSelected(TabLayout.Tab tab){
                if (suppressHomeSourceTabEvents) return;
                Object tag = tab.getTag();
                String sourceId = tag == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : tag.toString();
                if (sourceId.equals(homeSourceId)) { updateHomeSourceTabStyles(); return; }
                homeSourceId = sourceId;
                sort = "latest";
                selectedGenre = "";
                selectedTypeLabel = "";
                MangaSettingsManager.setMangaSource(requireContext(), homeSourceId);
                api = MangaSourceFactory.createBySourceId(homeSourceId);
                setupTypeFilterChips();
                if (showSortTabs()) setupSortTabs();
                updateHomeSourceTabStyles();
                reload();
            }
            public void onTabUnselected(TabLayout.Tab tab){}
            public void onTabReselected(TabLayout.Tab tab){
                if (suppressHomeSourceTabEvents) return;
                updateHomeSourceTabStyles();
                reload();
            }
        });
    }

    private void updateHomeSourceTabStyles() {
        if (sourceTabs == null || !isAdded()) return;
        int primary = MaterialColors.getColor(requireView(), androidx.appcompat.R.attr.colorPrimary);
        int normal = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < sourceTabs.getTabCount(); i++) {
            TabLayout.Tab tab = sourceTabs.getTabAt(i);
            if (tab == null || tab.getCustomView() == null) continue;
            boolean selected = tab.isSelected();
            View custom = tab.getCustomView();
            if (custom instanceof LinearLayout) {
                LinearLayout layout = (LinearLayout) custom;
                for (int j = 0; j < layout.getChildCount(); j++) {
                    View child = layout.getChildAt(j);
                    if (child instanceof TextView) ((TextView) child).setTextColor(selected ? primary : normal);
                    child.setAlpha(selected ? 1f : 0.72f);
                }
            }
        }
    }

    private View createSourceTabView(String sourceId) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        int horizontal = dpi600(36);
        int vertical = dpi600(22);
        layout.setPadding(horizontal, vertical, horizontal, vertical);
        layout.setMinimumWidth(dpi600(220));
        ImageView icon = new ImageView(requireContext());
        int size = dpi600(92);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(size, size);
        icon.setLayoutParams(iconParams);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        TextView label = new TextView(requireContext());
        label.setText(MangaSourceFactory.labelForSourceId(sourceId));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(42));
        label.setGravity(android.view.Gravity.CENTER);
        label.setSingleLine(true);
        label.setHorizontallyScrolling(false);
        label.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        label.setTextColor(MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant));
        layout.addView(icon);
        layout.addView(label);
        MangaImageLoader.loadForSource(icon, MangaSourceFactory.iconForSourceId(sourceId), sourceId, false, null);
        return layout;
    }

    private void setupSearchTabs() {
        if (sourceTabs != null) {
            sourceTabs.clearOnTabSelectedListeners();
            sourceTabs.removeAllTabs();
            sourceTabs.setVisibility(View.GONE);
        }
        activeSearchTab = TAB_GLOBAL;
        if (isAdded() && (activeGlobalSourceId == null || activeGlobalSourceId.trim().isEmpty() || !MangaSettingsManager.isMangaSourceEnabled(requireContext(), activeGlobalSourceId))) activeGlobalSourceId = MangaSettingsManager.getMangaSource(requireContext());
        setupGlobalSearchSourceTabs();
        updateSearchSourceTabsVisibility();
    }

    private void setupGlobalSearchSourceTabs() {
        if (tabs == null || !searchPage()) return;
        String onlySource = searchSourceOnlyId();
        configureScrollableTabs(tabs);
        suppressGlobalSourceTabEvents = true;
        tabs.removeAllTabs();
        boolean selectedFound = false;
        if (!onlySource.trim().isEmpty()) activeGlobalSourceId = onlySource;
        else if (!MangaSettingsManager.isMangaSourceEnabled(requireContext(), activeGlobalSourceId)) activeGlobalSourceId = MangaSettingsManager.getFirstEnabledMangaSource(requireContext());
        java.util.ArrayList<String> tabSources = new java.util.ArrayList<>();
        if (!onlySource.trim().isEmpty()) tabSources.add(onlySource); else tabSources.addAll(MangaSourceFactory.enabledSourceIds(requireContext()));
        for (String sourceId : tabSources) {
            if (!selectedFound && sourceId.equals(activeGlobalSourceId)) selectedFound = true;
            TabLayout.Tab tab = tabs.newTab();
            tab.setTag(sourceId);
            tab.setCustomView(createSourceTabView(sourceId));
            tabs.addTab(tab, sourceId.equals(activeGlobalSourceId));
        }
        if (!selectedFound && tabs.getTabCount() > 0) {
            TabLayout.Tab first = tabs.getTabAt(0);
            if (first != null && first.getTag() != null) activeGlobalSourceId = first.getTag().toString();
            tabs.selectTab(first);
        }
        updateSearchSourceTabStyles();
        suppressGlobalSourceTabEvents = false;
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){
            public void onTabSelected(TabLayout.Tab tab){
                if (suppressGlobalSourceTabEvents) return;
                Object tag = tab.getTag();
                activeGlobalSourceId = tag == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : tag.toString();
                selectedGenre = "";
                api = MangaSourceFactory.createBySourceId(activeGlobalSourceId);
                updateSearchSourceTabStyles();
                setupGenreChips();
                setupTypeFilterChips();
                if (TAB_GLOBAL.equals(activeSearchTab)) {
                    if (globalStates.isEmpty() && query != null && !query.trim().isEmpty()) reloadGlobalSearch();
                    else renderGlobalTab(true);
                }
            }
            public void onTabUnselected(TabLayout.Tab tab){}
            public void onTabReselected(TabLayout.Tab tab){
                if (suppressGlobalSourceTabEvents) return;
                Object tag = tab.getTag();
                activeGlobalSourceId = tag == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : tag.toString();
                selectedGenre = "";
                api = MangaSourceFactory.createBySourceId(activeGlobalSourceId);
                updateSearchSourceTabStyles();
                setupGenreChips();
                setupTypeFilterChips();
                if (TAB_GLOBAL.equals(activeSearchTab)) renderGlobalTab(true);
            }
        });
    }

    private void updateSearchSourceTabsVisibility() {
        if (tabs == null || !searchPage()) return;
        if (!showSearchSourceTabs()) {
            hideSearchSourceTabs();
            return;
        }
        tabs.setVisibility(View.VISIBLE);
        updateSearchSourceTabStyles();
    }

    private void hideSearchSourceTabs() {
        if (tabs != null) {
            tabs.clearOnTabSelectedListeners();
            tabs.removeAllTabs();
            tabs.setVisibility(View.GONE);
        }
        if (sourceTabs != null) {
            sourceTabs.clearOnTabSelectedListeners();
            sourceTabs.removeAllTabs();
            sourceTabs.setVisibility(View.GONE);
        }
        String onlySource = searchSourceOnlyId();
        if (onlySource != null && !onlySource.trim().isEmpty()) {
            activeGlobalSourceId = onlySource;
            api = MangaSourceFactory.createBySourceId(activeGlobalSourceId);
        }
    }

    private void rebuildSearchTabs() {
        if (!searchPage()) return;
        if (!showSearchSourceTabs()) { hideSearchSourceTabs(); return; }
        if (sourceTabs != null) {
            suppressTabEvents = true;
            for (int i = 0; i < sourceTabs.getTabCount(); i++) {
                TabLayout.Tab tab = sourceTabs.getTabAt(i);
                if (tab != null && activeSearchTab.equals(tab.getTag())) sourceTabs.selectTab(tab);
            }
            suppressTabEvents = false;
        }
        updateSearchSourceTabsVisibility();
        updateSearchSourceTabStyles();
    }

    private void updateSearchSourceTabStyles() {
        if (tabs == null || !isAdded()) return;
        int primary = MaterialColors.getColor(requireView(), androidx.appcompat.R.attr.colorPrimary);
        int normal = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < tabs.getTabCount(); i++) {
            TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab == null || tab.getCustomView() == null) continue;
            boolean selected = tab.isSelected();
            View custom = tab.getCustomView();
            if (custom instanceof LinearLayout) {
                LinearLayout layout = (LinearLayout) custom;
                for (int j = 0; j < layout.getChildCount(); j++) {
                    View child = layout.getChildAt(j);
                    if (child instanceof TextView) ((TextView) child).setTextColor(selected ? primary : normal);
                    child.setAlpha(selected ? 1f : 0.72f);
                }
            }
        }
    }

    private void switchGlobalTab(String tab, boolean allowReload) {
        activeSearchTab = TAB_GLOBAL;
        updateSearchSourceTabsVisibility();
        query = searchEditText == null || searchEditText.getText() == null ? query : searchEditText.getText().toString();
        if (query == null || query.trim().isEmpty()) {
            posts.clear();
            loadedSlugs.clear();
            hasMore = false;
            loading = false;
            if (adapter != null) adapter.notifyDataSetChanged();
            if (helper != null) helper.setText("Ketik judul manga");
            hideLoading();
            rebuildSearchTabs();
            return;
        }
        if (globalStates.isEmpty() || allowReload && !hasGlobalLoadedForQuery()) reloadGlobalSearch();
        else renderGlobalTab(true);
    }

    private boolean hasGlobalLoadedForQuery() {
        for (GlobalSourceState state : globalStates.values()) if (state.loadedOnce || state.loading) return true;
        return false;
    }

    private void setupTypeFilterChips() {
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKCAST.equals(currentSourceId())) {
            if (typeScrollView != null) typeScrollView.setVisibility(View.GONE);
            if (genreScrollView != null && genreScrollView == typeScrollView) genreScrollView.setVisibility(View.GONE);
            return;
        }
        boolean useSeparateTypeGroup = typeScrollView != null && typeChipGroup != null;
        if (!useSeparateTypeGroup && (genreScrollView == null || genreChipGroup == null || searchPage())) return;
        HorizontalScrollView targetScroll = useSeparateTypeGroup ? typeScrollView : genreScrollView;
        ChipGroup targetGroup = useSeparateTypeGroup ? typeChipGroup : genreChipGroup;
        if (targetScroll == null || targetGroup == null) return;
        targetScroll.setVisibility(View.VISIBLE);
        if (typeTitleTextView != null) typeTitleTextView.setVisibility(View.GONE);
        targetGroup.removeAllViews();
        targetGroup.setSingleSelection(true);
        targetGroup.setSelectionRequired(true);
        String[][] filters = typeFiltersForSource(currentSourceId());
        boolean selectedExists = false;
        for (String[] filter : filters) {
            if (filter.length > 0 && filter[0].equals(selectedTypeLabel)) {
                selectedExists = true;
                break;
            }
        }
        if (!selectedExists) selectedTypeLabel = "";
        for (String[] filter : filters) {
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setText(filter[1]);
            chip.setTag(filter[0]);
            chip.setCheckable(true);
            chip.setChecked(filter[0].equals(selectedTypeLabel));
            applyChipTheme(chip);
            chip.setOnClickListener(v -> { Object tag = v.getTag(); selectTypeFilter(tag == null ? "" : tag.toString()); ((Chip)v).setChecked(true); });
            targetGroup.addView(chip);
        }
    }

    private void selectTypeFilter(String value) {
        selectedTypeLabel = value == null ? "" : value.trim();
        if (!usesLocalTypeFilter(currentSourceId())) {
            query = "";
            if (searchEditText != null) searchEditText.setText("");
        }
        reload();
    }

    private String[][] typeFiltersForSource(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKCAST.equals(sourceId)) return new String[0][0];
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}, {"DOUJINSHI", "Doujinshi"}};
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"DOUJINSHI", "Doujinshi"}, {"IMAGE-SET", "Image-set"}, {"ONESHOT", "One-shot"}};
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}};
        if (MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(sourceId)) return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}, {"DOUJINSHI", "Doujinshi"}};
        return new String[][]{{"", "Semua"}, {"MANGA", "Manga"}, {"MANHWA", "Manhwa"}, {"MANHUA", "Manhua"}, {"WEBTOON", "Webtoon"}};
    }

    private String requestFilterForSource(String sourceId) {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) return "";
        String genre = selectedGenre == null ? "" : selectedGenre.trim();
        String rawType = selectedTypeLabel == null ? "" : selectedTypeLabel.trim();
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) {
            String normalizedType = normalizeTypeFilterValue(rawType);
            String typeFilter = normalizedType.isEmpty() ? "" : "type:" + normalizedType;
            return genre.isEmpty() ? typeFilter : typeFilter.isEmpty() ? genre : genre + "|" + typeFilter;
        }
        if (usesLocalTypeFilter(sourceId)) return genre;
        if (rawType.isEmpty()) return genre;
        String normalizedType = normalizeTypeFilterValue(rawType);
        if (normalizedType.isEmpty()) return genre;
        boolean supportsTypeKey = MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKCAST.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_LUVYAA.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN.equals(sourceId);
        String typeFilter = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST.equals(sourceId) ? "type:" + normalizedType : supportsTypeKey ? "type:" + normalizedType : normalizedType;
        return genre.isEmpty() ? typeFilter : genre + "|" + typeFilter;
    }

    private String normalizeTypeFilterValue(String rawType) {
        if (rawType == null) return "";
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if ("MANHWA".equals(type)) return "manhwa";
        if ("MANHUA".equals(type)) return "manhua";
        if ("DOUJINSHI".equals(type)) return "doujinshi";
        if ("IMAGE-SET".equals(type)) return "image-set";
        if ("ONESHOT".equals(type) || "ONE-SHOT".equals(type)) return "one-shot";
        if ("COMIC".equals(type)) return "comic";
        if ("NOVEL".equals(type)) return "novel";
        if ("MANGA".equals(type)) return "manga";
        return rawType.trim();
    }

    private boolean usesLocalTypeFilter(String sourceId) {
        return MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId);
    }

    private void setupGenreChips() {
        if (useGenreFilterMenu()) {
            setupGenreFilterMenu();
            return;
        }
        if (genreFilterButton != null) genreFilterButton.setVisibility(View.GONE);
        if (genreScrollView == null || genreChipGroup == null) return;
        genreScrollView.setVisibility(View.VISIBLE);
        if (genreTitleTextView != null) genreTitleTextView.setVisibility(View.GONE);
        String sourceId = currentSourceId();
        api = MangaSourceFactory.createBySourceId(sourceId);
        renderSourceFallbackGenreChips(sourceId);
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            return;
        }
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        api.genres(new KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>>() {
            @Override public void onSuccess(ArrayList<KomikcastClient.GenreItem> data, boolean hasNext) {
                if (!isAdded() || !sourceId.equals(currentSourceId())) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (data != null && !data.isEmpty()) renderGenreChips(data); else renderStaticGenreChips();
            }
            @Override public void onError(String message) {
                if (!isAdded() || !sourceId.equals(currentSourceId())) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                renderSourceFallbackGenreChips(sourceId);
            }
        });
    }

    private boolean useGenreFilterMenu() {
        return searchPage() && useIkiruLayout() && genreFilterButton != null;
    }

    private void setupGenreFilterMenu() {
        if (genreScrollView != null) genreScrollView.setVisibility(View.GONE);
        if (genreTitleTextView != null) genreTitleTextView.setVisibility(View.GONE);
        if (genreFilterButton == null) return;
        genreFilterButton.setVisibility(View.VISIBLE);
        genreFilterButton.setOnClickListener(v -> showGenreFilterMenu());
        String sourceId = currentSourceId();
        api = MangaSourceFactory.createBySourceId(sourceId);
        renderSourceFallbackGenreMenu(sourceId);
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) {
            genreMenuLoading = false;
            updateGenreFilterButtonState();
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            return;
        }
        genreMenuLoading = true;
        updateGenreFilterButtonState();
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        api.genres(new KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>>() {
            @Override public void onSuccess(ArrayList<KomikcastClient.GenreItem> data, boolean hasNext) {
                if (!isAdded() || !sourceId.equals(currentSourceId())) return;
                genreMenuLoading = false;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (data != null && !data.isEmpty()) renderGenreMenuItems(data); else renderSourceFallbackGenreMenu(sourceId);
            }
            @Override public void onError(String message) {
                if (!isAdded() || !sourceId.equals(currentSourceId())) return;
                genreMenuLoading = false;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                renderSourceFallbackGenreMenu(sourceId);
            }
        });
    }

    private void renderSourceFallbackGenreMenu(String sourceId) {
        ArrayList<KomikcastClient.GenreItem> items = new ArrayList<>();
        for (String[] pair : staticGenresForSource(sourceId)) items.add(new KomikcastClient.GenreItem(pair[0], pair[1]));
        renderGenreMenuItems(items);
    }

    private void renderGenreMenuItems(ArrayList<KomikcastClient.GenreItem> items) {
        genreMenuItems.clear();
        genreMenuItems.add(new KomikcastClient.GenreItem("Semua", ""));
        HashSet<String> seen = new HashSet<>();
        seen.add("");
        if (items != null) {
            for (KomikcastClient.GenreItem item : items) {
                if (item == null || item.title.trim().isEmpty() || item.value.trim().isEmpty()) continue;
                String itemValue = item.value.trim();
                if (itemValue.startsWith("type:") || itemValue.startsWith("status:")) continue;
                if (!seen.add(itemValue)) continue;
                genreMenuItems.add(new KomikcastClient.GenreItem(item.title.trim(), itemValue));
            }
        }
        updateGenreFilterButtonState();
    }

    private void showGenreFilterMenu() {
        if (!isAdded() || genreFilterButton == null) return;
        if (genreMenuLoading && genreMenuItems.size() <= 1) {
            Toast.makeText(requireContext(), "Memuat genre...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (genreMenuItems.isEmpty()) renderSourceFallbackGenreMenu(currentSourceId());
        PopupMenu popupMenu = new PopupMenu(requireContext(), genreFilterButton);
        String selected = selectedGenre == null ? "" : selectedGenre.trim();
        for (int i = 0; i < genreMenuItems.size(); i++) {
            KomikcastClient.GenreItem item = genreMenuItems.get(i);
            android.view.MenuItem menuItem = popupMenu.getMenu().add(0, i, i, item.title);
            menuItem.setCheckable(true);
            menuItem.setChecked(item.value.equals(selected));
        }
        popupMenu.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            if (index < 0 || index >= genreMenuItems.size()) return true;
            KomikcastClient.GenreItem genreItem = genreMenuItems.get(index);
            selectedGenre = genreItem.value == null ? "" : genreItem.value.trim();
            updateGenreFilterButtonState();
            reload();
            return true;
        });
        popupMenu.show();
    }

    private void updateGenreFilterButtonState() {
        View view = getView();
        if (genreFilterButton == null || !isAdded() || view == null) return;
        boolean active = selectedGenre != null && !selectedGenre.trim().isEmpty();
        int color = MaterialColors.getColor(view, active ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant);
        genreFilterButton.setColorFilter(color);
        genreFilterButton.setContentDescription(active ? "Filter genre aktif" : "Filter genre");
    }

    private void renderStaticGenreChips() {
        renderSourceFallbackGenreChips(currentSourceId());
    }

    private void renderSourceFallbackGenreChips(String sourceId) {
        ArrayList<KomikcastClient.GenreItem> items = new ArrayList<>();
        for (String[] pair : staticGenresForSource(sourceId)) items.add(new KomikcastClient.GenreItem(pair[0], pair[1]));
        renderGenreChips(items);
    }

    private String[][] staticGenresForSource(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return new String[][]{{"Action","action"},{"Adventure","adventure"},{"Comedy","comedy"},{"Drama","drama"},{"Fantasy","fantasy"},{"Harem","harem"},{"Isekai","isekai"},{"Magic","magic"},{"Martial Arts","martial-arts"},{"Romance","romance"},{"School Life","school-life"},{"Seinen","seinen"},{"Shounen","shounen"},{"Slice of Life","slice-of-life"},{"Supernatural","supernatural"}};
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return new String[][]{{"Ahegao","ahegao"},{"Anal","anal"},{"Big Breast","big-breast"},{"Blowjob","blowjob"},{"Bondage","bondage"},{"Cheating","cheating"},{"Dark Skin","dark-skin"},{"Elf","elf"},{"Femdom","femdom"},{"Futanari","futanari"},{"Group","group"},{"Harem","harem"},{"Lactation","lactation"},{"Maid","maid"},{"MILF","milf"},{"Mind Control","mind-control"},{"Netorare","netorare"},{"Paizuri","paizuri"},{"Schoolgirl Uniform","schoolgirl-uniform"},{"Tentacles","tentacles"},{"Vanilla","vanilla"},{"Yuri","yuri"}};
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return new String[][]{{"4-Koma","4-Koma"},{"Action","Action"},{"Adaptation","Adaptation"},{"Adventure","Adventure"},{"apocalypse","apocalypse"},{"Comedy","Comedy"},{"Cooking","Cooking"},{"Crime","Crime"},{"Demon","Demon"},{"Drama","Drama"},{"Ecchi","Ecchi"},{"Fantasy","Fantasy"},{"Game","Game"},{"Gender bender","Gender bender"},{"Harem","Harem"},{"Historical","Historical"},{"Horror","Horror"},{"Isekai","Isekai"},{"Josei","Josei"},{"Magic","Magic"},{"Martial Arts","Martial Arts"},{"Mature","Mature"},{"Mecha","Mecha"},{"Medical","Medical"},{"Military","Military"},{"Monsters","Monsters"},{"murim","murim"},{"Mystery","Mystery"},{"One-Shot","One-Shot"},{"Police","Police"},{"Psychological","Psychological"},{"Regression","Regression"},{"Reincarnation","Reincarnation"},{"Revenge","Revenge"},{"Romance","Romance"},{"School","School"},{"School Life","School Life"},{"Sci-Fi","Sci-Fi"},{"Seinen","Seinen"},{"Shoujo","Shoujo"},{"Shoujo Ai","Shoujo Ai"},{"Shounen","Shounen"},{"Shounen Ai","Shounen Ai"},{"Slice of Life","Slice of Life"},{"Sports","Sports"},{"Super Power","Super Power"},{"Superhero","Superhero"},{"Supernatural","Supernatural"},{"Survival","Survival"},{"System","System"},{"Thriller","Thriller"},{"Tragedy","Tragedy"},{"Vampire","Vampire"},{"Webtoons","Webtoons"},{"Wuxia","Wuxia"}};
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return new String[][]{{"Action","action"},{"Adventure","adventure"},{"Comedy","comedy"},{"Demons","demons"},{"Drama","drama"},{"Fantasy","fantasy"},{"Historical","historical"},{"Horror","horror"},{"Isekai","isekai"},{"Martial Arts","martial-arts"},{"Mystery","mystery"},{"Romance","romance"},{"School Life","school-life"},{"Seinen","seinen"},{"Shounen","shounen"},{"Supernatural","supernatural"}};
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return new String[][]{{"Action","Action"},{"Adventure","Adventure"},{"Comedy","Comedy"},{"Drama","Drama"},{"Fantasy","Fantasy"},{"Harem","Harem"},{"Isekai","Isekai"},{"Martial Arts","Martial Arts"},{"Romance","Romance"},{"School Life","School Life"},{"Shounen","Shounen"},{"Slice of Life","Slice of Life"},{"Supernatural","Supernatural"}};
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return new String[][]{{"Action","genre:action"},{"Adventure","genre:adventure"},{"Comedy","genre:comedy"},{"Drama","genre:drama"},{"Fantasy","genre:fantasy"},{"Isekai","genre:isekai"},{"Romance","genre:romance"},{"School Life","genre:school-life"},{"Shounen","genre:shounen"},{"Slice of Life","genre:slice-of-life"},{"Supernatural","genre:supernatural"}};
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return new String[][]{{"Action","action"},{"Adventure","adventure"},{"Comedy","comedy"},{"Drama","drama"},{"Fantasy","fantasy"},{"Martial Arts","martial-arts"},{"Romance","romance"},{"School Life","school-life"},{"Shounen","shounen"},{"Supernatural","supernatural"},{"System","system"},{"Thriller","thriller"},{"Murim","murim"}};
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return new String[][]{{"Action","action"},{"Adventure","adventure"},{"Comedy","comedy"},{"Drama","drama"},{"Fantasy","fantasy"},{"Isekai","isekai"},{"Martial Arts","martial-arts"},{"Romance","romance"},{"School Life","school-life"},{"Seinen","seinen"},{"Shounen","shounen"},{"Slice of Life","slice-of-life"},{"Supernatural","supernatural"}};
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return new String[][]{{"Action","action"},{"Adventure","adventure"},{"Comedy","comedy"},{"Drama","drama"},{"Fantasy","fantasy"},{"Harem","harem"},{"Isekai","isekai"},{"Magic","magic"},{"Manhua","manhua"},{"Manhwa","manhwa"},{"Martial Arts","martial-arts"},{"Romance","romance"},{"School Life","school-life"},{"Seinen","seinen"},{"Shounen","shounen"},{"Slice of Life","slice-of-life"},{"Supernatural","supernatural"}};
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return new String[][]{{"4-Koma","2268"},{"Action","14"},{"Adult","11229"},{"Adventure","15"},{"apocalypse","8996"},{"Comedy","2"},{"Cooking","772"},{"Crime","3741"},{"Demon","4641"},{"Demons","45"},{"Doujinshi","3722"},{"Drama","11"},{"dungeons","8803"},{"Ecchi","28"},{"Fantasy","16"},{"Game","84"},{"Gender bender","74"},{"Gore","3354"},{"Harem","29"},{"Historical","24"},{"Horror","136"},{"Isekai","17"},{"Josei","1155"},{"Lolicon","3991"},{"Magic","41"},{"Martial Arts","18"},{"Mature","43"},{"Mecha","111"},{"Medical","3386"},{"Military","1476"},{"monsters","9299"},{"Music","146"},{"Mystery","99"},{"One-Shot","50"},{"Parody","372"},{"Police","1048"},{"Post apocalyptic","6646"},{"Psychological","90"},{"Regression","8798"},{"Reincarnation","4017"},{"Romance","3"},{"School","12"},{"School Life","566"},{"Sci-Fi","85"},{"Seinen","36"},{"sepernatural","4626"},{"Shotacon","10359"},{"Shoujo","53"},{"Shoujo Ai","31"},{"Shounen","5"},{"Shounen Ai","2479"},{"Si-fi","3717"},{"Slice of Life","13"},{"Smut","3760"},{"Sports","265"},{"Super Power","103"},{"Supernatural","19"},{"Superpowers","6781"},{"Thriller","143"},{"Tragedy","20"},{"Vampire","634"},{"Yuri","75"},{"Zombies","3894"}};
        if (MangaSettingsManager.MANGA_SOURCE_COMICASO.equals(sourceId)) return isComicasoAdultSort(sort) ? COMICASO_ADULT_GENRES : COMICASO_NORMAL_GENRES;
        if (MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId)) return new String[][]{{"2 Penetration","2-penetration"},{"3 Penetration","3-penetration"},{"Ahegao","ahegao"},{"Anal","anal"},{"Apron","apron"},{"Bdsm","bdsm"},{"Big Breast","big-breast"},{"Big Penis","big-penis"},{"Blackmail","blackmail"},{"Bloomers","bloomers"},{"Blowjob","blowjob"},{"Body Swap","body-swap"},{"Bondage","bondage"},{"Bukkake","bukkake"},{"Bunny Girl","bunny-girl"},{"Censored","censored"},{"Cervix Penetration","cervix-penetration"},{"Cheating","cheating"},{"Chinese Dress","chinese-dress"},{"Colored","colored"},{"Condom","condom"},{"Cosplay","cosplay"},{"Crossdressing","crossdressing"},{"Dark Skin","dark-skin"},{"Deepthroat","deepthroat"},{"Double Penetration","double-penetration"},{"Drama","drama"},{"Elf","elf"},{"Emotionless","emotionless"},{"exhibitionism","exhibitionism"},{"Fanbox","fanbox"},{"Female Only","female-only"},{"Femboy","femboy"},{"Femdom","femdom"},{"FFM Threesome","ffm-threesome"},{"Filming","filming"},{"Fingering","fingering"},{"Footjob","footjob"},{"Force","force"},{"Fox Girl","fox-girl"},{"Futanari","futanari"},{"Gender Bender","gender-bender"},{"Glasses","glasses"},{"Group","group"},{"Hair Buns","hair-buns"},{"Handjob","handjob"},{"Harem","harem"},{"Impregnation","impregnation"},{"Incest","incest"},{"Inseki","inseki"},{"Kemonomimi","kemonomimi"},{"Kimono","kimono"},{"Kissing","kissing"},{"Kogal","kogal"},{"Kuudere","kuudere"},{"Lactation","lactation"},{"Lingerie","lingerie"},{"Loli","loli"},{"Maid","maid"},{"Manhwa","manhwa"},{"Masturbation","masturbation"},{"Milf","milf"},{"Mind Break","mind-break"},{"Mind Control","mind-control"},{"MMF Threesome","mmf-threesome"},{"Monster","monster"},{"Nakadashi","nakadashi"},{"Netorare","netorare"},{"Netorase","netorase"},{"Netori","netori"},{"No Penetration","no-penetration"},{"Nun","nun"},{"Nurse","nurse"},{"Office Lady","office-lady"},{"Old Man","old-man"},{"Osananajimi","osananajimi"},{"Oyakodon","oyakodon"},{"Paizuri","paizuri"},{"Pantyhose","pantyhose"},{"Parodi: Ao no Hako","parodi-ao-no-hako"},{"Parodi: Arknights","parodi-arknights"},{"Parodi: Azur Lane","parodi-azur-lane"},{"Parodi: Blue Archive","parodi-blue-archive"},{"Parodi: Bocchi the Rock!","parodi-bocchi-the-rock"},{"Parodi: Boku no Hero Academia","parodi-boku-no-hero-academia"},{"Parodi: Boku no Kokoro no Yabai Yatsu","parodi-boku-no-kokoro-no-yabai-yatsu"},{"Parodi: Bokutachi wa Benkyou ga Dekinai","parodi-bokutachi-wa-benkyou-ga-dekinai"},{"Parodi: Fate Grand Order","parodi-fate-grand-order"},{"Parodi: Genshin Impact","parodi-genshin-impact"},{"Parodi: Girls Frontline","parodi-girls-frontline"},{"Parodi: Gotoubun no Hanayome","parodi-gotoubun-no-hanayome"},{"Parodi: Hololive","parodi-hololive"},{"Parodi: Honkai Impact","parodi-honkai-impact"},{"Parodi: Honkai Star Rail","parodi-honkai-star-rail"},{"Parodi: Kantai Collection","parodi-kantai-collection"},{"Parodi: Kyoukai no Kanata","parodi-kyoukai-no-kanata"},{"Parodi: Love Live","parodi-love-live"},{"Parodi: Make Heroine ga Oosugiru","parodi-make-heroine-ga-oosugiru"},{"Parodi: Nanabun no Nijyuuni","parodi-nanabun-no-nijyuuni"},{"Parodi: Nijisanji","parodi-nijisanji"},{"Parodi: Nikke Goddes of Factory","parodi-nikke-goddes-of-factory"},{"Parodi: Princess Connect!","parodi-princess-connect"},{"Parodi: Seishun Buta Yarou wa Bunny Girl Senpai no Yume o Minai","parodi-seishun-buta-yarou-wa-bunny-girl-senpai-no-yume-o-minai"},{"Parodi: Sono Bisque Doll wa Koi o Suru","parodi-sono-bisque-doll-wa-koi-o-suru"},{"Parodi: Sousou no Frieren","parodi-sousou-no-frieren"},{"Parodi: The iDOLM@STER","parodi-the-idolmster"},{"Parodi: To Love-Ru","parodi-to-love-ru"},{"Parodi: Tokidoki Bosotto Russia-go de Dereru Tonari no Alya-san","parodi-tokidoki-bosotto-russia-go-de-dereru-tonari-no-alya-san"},{"Parodi: Touhou","parodi-touhou"},{"Parodi: Xenoblade Chronicles","parodi-xenoblade-chronicles"},{"Parodi: Zenless Zone Zero","parodi-zenless-zone-zero"},{"Pegging","pegging"},{"Pixiv","pixiv"},{"Ponytail","ponytail"},{"Possession","possession"},{"Pregnant","pregnant"},{"Prostitution","prostitution"},{"Rape","rape"},{"Reincarnation","reincarnation"},{"Rimjob","rimjob"},{"Romance","romance"},{"Shimaidon","shimaidon"},{"Shotacon","shotacon"},{"Sister","sister"},{"Sleeping","sleeping"},{"Sole Female","sole-female"},{"Sole Male","sole-male"},{"Squirting","squirting"},{"Stockings","stockings"},{"Stomach Deformation","stomach-deformation"},{"Succubus","succubus"},{"Sweating","sweating"},{"Swimsuit","swimsuit"},{"Tankoubon","tankoubon"},{"Teacher","teacher"},{"Tomboy","tomboy"},{"Toys","toys"},{"Tsundere","tsundere"},{"Twins","twins"},{"Twintails","twintails"},{"Uncensored","uncensored"},{"Uniform","uniform"},{"Vanilla","vanilla"},{"Virginity","virginity"},{"Webtoon","webtoon"},{"X-Ray","x-ray"},{"Yandere","yandere"},{"Yuri","yuri"}};
        if (MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId)) return new String[][]{{"Action","action"},{"Adult","adult"},{"Adventure","adventure"},{"Bloody","bloody"},{"Comedy","comedy"},{"Cooking","cooking"},{"Demons","demons"},{"Drama","drama"},{"Ecchi","ecchi"},{"Fantasy","fantasy"},{"Game","game"},{"Gender Bender","gender-bender"},{"Harem","harem"},{"Historical","historical"},{"Horror","horror"},{"Isekai","isekai"},{"Josei","josei"},{"Lolicon","lolicon"},{"Mafia","mafia"},{"Magic","magic"},{"Martial Arts","martial-arts"},{"Mature","mature"},{"Mecha","mecha"},{"Medical","medical"},{"Mystery","mystery"},{"Overpowered","overpowered"},{"Psychological","psychological"},{"Reincarnation","reincarnation"},{"Returner","returner"},{"Revenge","revenge"},{"Romance","romance"},{"School","school"},{"School Life","school-life"},{"Sci-fi","sci-fi"},{"Seinen","seinen"},{"Shotacon","shotacon"},{"Shoujo","shoujo"},{"Shoujo Ai","shoujo-ai"},{"Shounen","shounen"},{"Shounen Ai","shounen-ai"},{"Slice of Life","slice-of-life"},{"Smut","smut"},{"Sports","sports"},{"Superhero","superhero"},{"Supernatural","supernatural"},{"Thriller","thriller"},{"Tragedy","tragedy"},{"Yuri","yuri"}};
        if (MangaSettingsManager.MANGA_SOURCE_KUMOPOI.equals(sourceId)) return new String[][]{{"Action","41"},{"Adventure","42"},{"Comedy","5"},{"Drama","19"},{"Fantasy","43"},{"Harem","6"},{"Martial Arts","448"},{"Romance","7"},{"School Life","27"},{"Seinen","8"},{"Shounen","44"},{"Slice of Life","29"},{"Supernatural","79"}};
        return GENRES;
    }

    private boolean isComicasoAdultSort(String value) {
        String sortValue = value == null ? "" : value.trim();
        return "adult_update".equals(sortValue) || "adult_new".equals(sortValue) || "adult_completed".equals(sortValue) || "adult_manga".equals(sortValue) || "adult_manhwa".equals(sortValue) || "adult_manhua".equals(sortValue);
    }

    private void renderGenreChips(ArrayList<KomikcastClient.GenreItem> items) {
        if (genreChipGroup == null) return;
        genreChipGroup.removeAllViews();
        genreChipGroup.setSingleSelection(true);
        genreChipGroup.setSelectionRequired(true);
        Chip all = new Chip(requireContext());
        all.setId(View.generateViewId());
        all.setText("Semua");
        all.setCheckable(true);
        all.setChecked(selectedGenre == null || selectedGenre.isEmpty());
        applyChipTheme(all);
        all.setOnClickListener(v -> { selectedGenre=""; ((Chip)v).setChecked(true); reload(); });
        genreChipGroup.addView(all);
        if (items == null) return;
        for (KomikcastClient.GenreItem item : items) {
            if (item == null || item.title.trim().isEmpty() || item.value.trim().isEmpty()) continue;
            String itemValue = item.value.trim();
            if (itemValue.startsWith("type:") || itemValue.startsWith("status:")) continue;
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setText(item.title);
            chip.setCheckable(true);
            chip.setTag(itemValue);
            chip.setChecked(itemValue.equals(selectedGenre));
            applyChipTheme(chip);
            chip.setOnClickListener(v -> { Object tag = v.getTag(); selectedGenre = tag == null ? "" : tag.toString(); ((Chip)v).setChecked(true); reload(); });
            genreChipGroup.addView(chip);
        }
    }
    private void applyChipTheme(Chip chip) {
        int primary = MaterialColors.getColor(requireView(), androidx.appcompat.R.attr.colorPrimary);
        int onSurface = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        int surface = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSurfaceContainer);
        int[][] states = new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} };
        chip.setTextColor(new ColorStateList(states, new int[]{ primary, onSurface }));
        chip.setChipStrokeColor(new ColorStateList(states, new int[]{ primary, onSurface }));
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{ surface, surface }));
        chip.setRippleColor(ColorStateList.valueOf(primary));
    }

    private void showInlineMessage(String message) {
        String text = message == null || message.trim().isEmpty() ? "Gagal memuat data. Coba lagi." : message.trim();
        if (helper != null) {
            helper.setVisibility(View.VISIBLE);
            helper.setText(text);
            helper.setAlpha(0f);
            helper.animate().alpha(1f).setDuration(160).start();
        }
        if (headerSubtitle != null && !searchPage()) { headerSubtitle.setVisibility(View.VISIBLE); headerSubtitle.setText(text); }
    }

    private void clearInlineMessage() {
        if (helper != null) {
            if (searchPage()) { if (showSearchSourceTabs()) helper.setText("Global untuk semua source."); else helper.setVisibility(View.GONE); }
            else helper.setVisibility(View.GONE);
        }
        if (headerSubtitle != null && !searchPage()) { headerSubtitle.setText(""); headerSubtitle.setVisibility(View.GONE); }
    }

    private void setupErrorFeedback(View root) {
        if (!(root instanceof ViewGroup) || errorFeedbackView != null) return;
        Context context = requireContext();
        FrameLayout container = new FrameLayout(context);
        container.setVisibility(View.GONE);
        container.setClickable(true);
        container.setFocusable(true);
        container.setBackgroundColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface));
        ViewGroup.LayoutParams baseParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.setLayoutParams(baseParams);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER);
        int horizontal = dp(28);
        content.setPadding(horizontal, dp(24), horizontal, dp(24));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER);
        errorFeedbackIcon = new ImageView(context);
        int iconSize = dp(96);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        errorFeedbackIcon.setImageResource(R.drawable.ic_feedback_offline);
        errorFeedbackIcon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)));
        content.addView(errorFeedbackIcon, iconParams);
        errorFeedbackTitle = new TextView(context);
        errorFeedbackTitle.setGravity(android.view.Gravity.CENTER);
        errorFeedbackTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium);
        errorFeedbackTitle.setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface));
        errorFeedbackTitle.setText("Whoops!");
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(18);
        content.addView(errorFeedbackTitle, titleParams);
        errorFeedbackMessage = new TextView(context);
        errorFeedbackMessage.setGravity(android.view.Gravity.CENTER);
        errorFeedbackMessage.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        errorFeedbackMessage.setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(14);
        content.addView(errorFeedbackMessage, messageParams);
        errorFeedbackButton = new MaterialButton(context);
        errorFeedbackButton.setAllCaps(false);
        errorFeedbackButton.setText("Retry");
        errorFeedbackButton.setMinHeight(dp(54));
        errorFeedbackButton.setCornerRadius(dp(27));
        errorFeedbackButton.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(180), dp(56));
        buttonParams.topMargin = dp(28);
        content.addView(errorFeedbackButton, buttonParams);
        container.addView(content, contentParams);
        ((ViewGroup) root).addView(container);
        errorFeedbackView = container;
    }

    private void showMangaLoadError(String message) {
        String sourceId = currentSourceId();
        String sourceLabel = MangaSourceFactory.labelForSourceId(sourceId);
        boolean cloudflare = CloudflareHelper.isCloudflareRequiredMessage(message) || CloudflareHelper.needsResolution(sourceLabel) || isCloudflareText(message);
        boolean internet = isInternetText(message);
        String title = "Whoops!";
        String body;
        String button;
        if (cloudflare) {
            body = "Source " + sourceLabel + " membutuhkan verifikasi Cloudflare. Selesaikan verifikasi lalu coba lagi.";
            button = "Selesaikan Cloudflare";
        } else if (internet) {
            body = "Tidak ada koneksi internet atau koneksi sedang tidak stabil. Periksa jaringan lalu coba lagi.";
            button = "Retry";
        } else {
            body = "Ada masalah dengan jaringan atau server source sedang down. Silakan coba lagi nanti.";
            button = "Retry";
        }
        showFullErrorFeedback(title, body, button, cloudflare, sourceId, sourceLabel);
        showInlineMessage(cloudflare ? body : "Manga gagal dimuat: " + normalizeErrorMessage(message));
    }

    private void showFullErrorFeedback(String title, String message, String button, boolean cloudflare, String sourceId, String sourceLabel) {
        if (errorFeedbackView == null || !shouldShowFullErrorFeedback()) return;
        if (errorFeedbackTitle != null) errorFeedbackTitle.setText(title);
        if (errorFeedbackMessage != null) errorFeedbackMessage.setText(message);
        if (errorFeedbackButton != null) {
            errorFeedbackButton.setText(button);
            errorFeedbackButton.setOnClickListener(v -> {
                if (cloudflare) {
                    boolean opened = CloudflareHelper.openResolverForSource(requireContext(), sourceId, sourceLabel);
                    if (opened) waitingCloudflareResolver = true;
                    else Toast.makeText(requireContext(), "Gagal membuka halaman Cloudflare", Toast.LENGTH_SHORT).show();
                } else reload();
            });
        }
        if (gridView != null) gridView.setVisibility(View.GONE);
        errorFeedbackView.setVisibility(View.VISIBLE);
        errorFeedbackView.setAlpha(0f);
        errorFeedbackView.animate().alpha(1f).setDuration(160).start();
    }

    private void hideErrorFeedback() {
        if (errorFeedbackView != null) {
            errorFeedbackView.animate().cancel();
            errorFeedbackView.setVisibility(View.GONE);
        }
        if (gridView != null) gridView.setVisibility(View.VISIBLE);
    }

    private boolean shouldShowFullErrorFeedback() {
        return !empty(forcedSourceId()) || !empty(searchSourceOnlyId());
    }

    private String normalizeErrorMessage(String message) {
        if (message == null || message.trim().isEmpty()) return "Gagal memuat data. Coba lagi.";
        return message.trim();
    }

    private boolean isCloudflareText(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return text.contains("cloudflare") || text.contains("cf_clearance") || text.contains("challenge") || text.contains("captcha") || text.contains("turnstile") || text.contains("403") || text.contains("503");
    }

    private boolean isInternetText(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return text.contains("tidak ada koneksi") || text.contains("internet") || text.contains("unknownhost") || text.contains("failed to connect") || text.contains("timeout") || text.contains("timed out") || text.contains("connection") || text.contains("network") || text.contains("ssl");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int dpi600(int value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().densityDpi / DPI_600));
    }

     public void onResume(){ super.onResume(); applyGridColumns(); if (searchPage() && isAdded()) { String source = searchSourceOnlyId() == null || searchSourceOnlyId().trim().isEmpty() ? MangaSettingsManager.getMangaSource(requireContext()) : searchSourceOnlyId(); if (activeGlobalSourceId == null || activeGlobalSourceId.trim().isEmpty()) activeGlobalSourceId = source; if (!showSearchSourceTabs()) hideSearchSourceTabs(); setupGenreChips(); setupTypeFilterChips(); } if (!searchPage() && !staticList() && sourceTabs != null) { sourceTabs.clearOnTabSelectedListeners(); if (showHomeSourceTabs()) setupHomeSourceTabs(); else sourceTabs.setVisibility(View.GONE); if (showTypeFilterChips()) setupTypeFilterChips(); else if (genreScrollView != null) genreScrollView.setVisibility(View.GONE); } if (waitingCloudflareResolver && isAdded()) { waitingCloudflareResolver = false; mainHandler.postDelayed(this::reload, 400L); } }

    public void refreshSourceSettings() {
        if (!isAdded()) return;
        if (searchPage()) {
            activeGlobalSourceId = MangaSettingsManager.getMangaSource(requireContext());
            if (showSearchSourceTabs()) setupSearchTabs(); else hideSearchSourceTabs();
            setupGenreChips();
            setupTypeFilterChips();
            reload();
            return;
        }
        if (!staticList()) {
            if (!MangaSettingsManager.isMangaSourceEnabled(requireContext(), homeSourceId)) homeSourceId = MangaSettingsManager.getFirstEnabledMangaSource(requireContext());
            if (sourceTabs != null) sourceTabs.clearOnTabSelectedListeners();
            if (showHomeSourceTabs()) setupHomeSourceTabs(); else if (sourceTabs != null) sourceTabs.setVisibility(View.GONE);
            if (showTypeFilterChips()) setupTypeFilterChips(); else if (genreScrollView != null) genreScrollView.setVisibility(View.GONE);
            reload();
        }
    }
    protected void applyGridColumns(){
        if(gridView!=null && isAdded()) {
            gridView.setNumColumns(searchPage() ? searchGridColumns() : MangaSettingsManager.getMangaGridColumns(requireContext()));
            if (searchPage()) {
                gridView.setHorizontalSpacing(dp(4));
                gridView.setVerticalSpacing(dp(4));
                gridView.setPadding(dp(8), gridView.getPaddingTop(), dp(8), gridView.getPaddingBottom());
            } else {
                gridView.setHorizontalSpacing(dp(8));
                gridView.setVerticalSpacing(dp(useIkiruLayout() ? 9 : 14));
                gridView.setPadding(dp(12), gridView.getPaddingTop(), dp(12), gridView.getPaddingBottom());
            }
        }
    }
    public void reload(){
        query = searchEditText == null || searchEditText.getText() == null ? query : searchEditText.getText().toString();
        if(searchPage()) { activeSearchTab = TAB_GLOBAL; api = MangaSourceFactory.createBySourceId(currentSourceId()); setupGenreChips(); reloadGlobalSearch(); return; }
        if(adapter!=null) adapter.setShowSourceLabel(false);
        if (!searchPage()) api = MangaSourceFactory.createBySourceId(currentSourceId());
        clearInlineMessage();
        hideErrorFeedback();
        MangaSourceFactory.invalidateSourceCaches(currentSourceId());
        clearPageCacheForSource(currentSourceId());
        applyGridColumns(); loadGeneration++; page=1; hasMore=true; loading=false; filteredAutoLoadCount=0; savedFirstVisiblePosition=0; savedTopOffset=0; loadedSlugs.clear(); posts.clear(); if(adapter!=null) adapter.notifyDataSetChanged(); if(staticList()) loadStatic(); else load(true);
    }
    protected void loadStatic() {}
    protected void load(boolean clear){
        if (loading || !hasMore) return;
        int targetPage = page;
        int generation = loadGeneration;
        String key = cacheKey(targetPage);
        PageCache cached = PAGE_CACHE.get(key);
        if (cached != null && cached.data != null && !cached.data.isEmpty()) {
            appendPage(cached.data, cached.hasNext, clear, generation);
            return;
        }
        loading=true;
        showLoading(clear || posts.isEmpty());
        if (gridViewModel == null) gridViewModel = new ViewModelProvider(this).get(MangaGridViewModel.class);
        gridViewModel.loadList(currentSourceId(), targetPage, sort, query, requestFilterForSource(currentSourceId())).whenComplete((result, error) -> {
            if(!isAdded() || generation != loadGeneration) return;
            if (error == null && result != null) {
                ArrayList<MangaPost> data = result.getData();
                boolean next = result.getHasNext();
                if (data != null && !data.isEmpty()) PAGE_CACHE.put(key, new PageCache(data, next));
                hideErrorFeedback();
                appendPage(data, next, clear, generation);
            } else {
                loading=false;
                hideLoading();
                String msg = error == null || error.getMessage() == null ? "Gagal memuat manga" : error.getMessage();
                showMangaLoadError(msg);
            }
        });
    }

    private void reloadGlobalSearch() {
        applyGridColumns();
        loadGeneration++;
        int generation = ++globalGeneration;
        page = 1;
        hasMore = true;
        loading = false;
        savedFirstVisiblePosition = 0;
        savedTopOffset = 0;
        loadedSlugs.clear();
        posts.clear();
        globalStates.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        hideErrorFeedback();
        rebuildSearchTabs();
        String q = query == null ? "" : query.trim();
        boolean hasGenre = selectedGenre != null && !selectedGenre.trim().isEmpty();
        boolean hasType = selectedTypeLabel != null && !selectedTypeLabel.trim().isEmpty();
        boolean hasFilter = hasGenre || hasType;
        if (q.isEmpty() && !hasFilter) {
            hasMore = false;
            hideLoading();
            hideErrorFeedback();
            if (helper != null) helper.setVisibility(View.GONE);
            return;
        }
        if (helper != null) {
            if (showSearchSourceTabs()) {
                helper.setVisibility(View.VISIBLE);
                String label = MangaSourceFactory.labelForSourceId(activeGlobalSourceId);
                helper.setText(hasFilter && q.isEmpty() ? "Memuat filter " + label + "..." : "Mencari " + q + "...");
            } else {
                helper.setVisibility(View.GONE);
            }
        }
        showLoading(true);
        AtomicInteger remaining = new AtomicInteger(0);
        java.util.ArrayList<String> sourceIds = new java.util.ArrayList<>();
        String onlySource = searchSourceOnlyId();
        if (!onlySource.trim().isEmpty()) {
            sourceIds.add(onlySource);
            activeGlobalSourceId = onlySource;
        } else if (hasFilter) {
            sourceIds.add(activeGlobalSourceId);
        } else {
            sourceIds.addAll(MangaSourceFactory.enabledSourceIds(requireContext()));
        }
        for (String sourceId : sourceIds) {
            GlobalSourceState state = new GlobalSourceState(sourceId);
            globalStates.put(sourceId, state);
            remaining.incrementAndGet();
            loadGlobalSourcePage(state, generation, true, () -> {
                if (!isAdded() || generation != globalGeneration) return;
                if (remaining.decrementAndGet() <= 0) {
                    hideLoading();
                    renderGlobalTab(false);
                    int total = 0;
                    for (GlobalSourceState s : globalStates.values()) total += s.data.size();
                    if (helper != null) helper.setVisibility(View.GONE);
                }
            });
        }
    }

    private void loadGlobalNextPage() {
        if (!searchPage() || !TAB_GLOBAL.equals(activeSearchTab)) return;
        GlobalSourceState state = globalStates.get(activeGlobalSourceId);
        if (state != null && state.hasMore && !state.loading) {
            showLoading(false);
            int generation = globalGeneration;
            loadGlobalSourcePage(state, generation, false, () -> { if (isAdded() && generation == globalGeneration) renderGlobalTab(false); });
        }
    }

    private void loadGlobalSourcePage(GlobalSourceState state, int generation, boolean firstPage, Runnable done) {
        if (state == null || state.loading || !state.hasMore) { if (done != null) done.run(); return; }
        if (firstPage) {
            MangaSourceFactory.invalidateSourceCaches(state.sourceId);
            clearPageCacheForSource(state.sourceId);
        }
        state.loading = true;
        int targetPage = Math.max(1, state.page);
        String key = globalCacheKey(state.sourceId, targetPage);
        PageCache cached = PAGE_CACHE.get(key);
        if (cached != null && cached.data != null && !cached.data.isEmpty()) {
            appendGlobalPage(state, cached.data, cached.hasNext, generation);
            if (done != null) done.run();
            return;
        }
        String genre = state.sourceId.equals(activeGlobalSourceId) ? requestFilterForSource(state.sourceId) : "";
        if (gridViewModel == null) gridViewModel = new ViewModelProvider(this).get(MangaGridViewModel.class);
        gridViewModel.loadList(state.sourceId, targetPage, "latest", query, genre).whenComplete((result, error) -> {
            if (!isAdded() || generation != globalGeneration) return;
            if (error == null && result != null) {
                ArrayList<MangaPost> data = result.getData();
                boolean next = result.getHasNext();
                if (data != null && !data.isEmpty()) PAGE_CACHE.put(key, new PageCache(data, next));
                state.lastError = "";
                hideErrorFeedback();
                appendGlobalPage(state, data, next, generation);
                if (done != null) done.run();
            } else {
                String msg = error == null || error.getMessage() == null ? "" : error.getMessage();
                state.loading = false;
                state.loadedOnce = true;
                state.hasMore = false;
                state.lastError = msg;
                if (done != null) done.run();
            }
        });
    }

    private void appendGlobalPage(GlobalSourceState state, ArrayList<MangaPost> data, boolean next, int generation) {
        if (state == null || generation != globalGeneration) return;
        int before = state.data.size();
        ArrayList<MangaPost> appended = new ArrayList<>();
        if (data != null) {
            for (MangaPost post : data) {
                if (post == null) continue;
                post.withSource(state.sourceId, state.sourceLabel);
                applySelectedTypeFallback(post);
                String itemKey = sourceItemKey(post);
                if (!itemKey.isEmpty() && state.slugs.add(itemKey)) {
                    MangaLabelUtils.applyHiddenLabels(getContext(), post);
                    state.data.add(post);
                    appended.add(post);
                    if (isAdded() && appended.size() <= MAX_COVER_PRELOAD) MangaImageLoader.preload(requireContext(), post.coverImage, post.getSourceId());
                }
            }
        }
        state.hasMore = next && state.data.size() > before;
        state.page++;
        state.loading = false;
        state.loadedOnce = true;
        if (!appended.isEmpty() && state.sourceId.equals(activeGlobalSourceId)) enrichLatestBatched(state.sourceId, appended, generation, true);
        rebuildSearchTabs();
        renderGlobalTab(false);
    }

    private void renderGlobalTab(boolean animate) {
        if (!searchPage() || !TAB_GLOBAL.equals(activeSearchTab)) return;
        ArrayList<MangaPost> visible = new ArrayList<>();
        GlobalSourceState state = globalStates.get(activeGlobalSourceId);
        if (state != null) {
            for (MangaPost post : state.data) if (matchesTypeFilter(post)) visible.add(post);
            hasMore = state.hasMore;
            loading = state.loading;
        } else {
            hasMore = false;
            loading = false;
        }
        loadedSlugs.clear();
        posts.clear();
        ArrayList<MangaPost> appended = new ArrayList<>();
        for (MangaPost post : visible) {
            String key = sourceItemKey(post);
            if (!key.isEmpty() && loadedSlugs.add(key)) {
                posts.add(post);
                appended.add(post);
            }
        }
        hideLoading();
        if (posts.isEmpty() && state != null && state.loadedOnce && !state.loading && state.lastError != null && !state.lastError.trim().isEmpty()) showMangaLoadError(state.lastError); else hideErrorFeedback();
        if (adapter != null) {
            adapter.setShowSourceLabel(false);
            if (animate) adapter.animateItems(appended);
            adapter.notifyDataSetChanged();
        }
        enrichVisibleGlobalPosts();
        if (helper != null && state != null && state.loadedOnce && !state.loading) {
            if (showSearchSourceTabs()) {
                String label = MangaSourceFactory.labelForSourceId(activeGlobalSourceId);
                boolean hasGenre = selectedGenre != null && !selectedGenre.trim().isEmpty();
                String prefix = hasGenre ? "Hasil genre " : "Hasil pencarian ";
                helper.setText(posts.isEmpty() ? prefix + label + " 0" : prefix + label + " " + posts.size());
            } else {
                helper.setVisibility(View.GONE);
            }
        }
        if (gridView != null) {
            gridView.animate().cancel();
            gridView.setAlpha(1f);
        }
        rebuildSearchTabs();
    }

    private void enrichLatestBatched(String sourceId, ArrayList<MangaPost> items, int generation, boolean global) {
        if (!isAdded() || items == null || items.isEmpty()) return;
        ArrayList<MangaPost> targets = new ArrayList<>();
        for (MangaPost post : items) {
            String key = sourceItemKey(post);
            if (post != null && post.slug != null && !post.slug.trim().isEmpty() && shouldEnrichPost(sourceId, post) && !key.isEmpty() && latestEnrichKeys.add(key)) targets.add(post);
        }
        if (targets.isEmpty()) return;
        enrichLatestBatch(sourceId, targets, 0, generation, global);
    }

    private boolean shouldEnrichPost(String sourceId, MangaPost post) {
        if (post == null) return false;
        boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel(getContext());
        boolean loadType = MangaSettingsManager.shouldLoadTypeLabel(getContext());
        if (!loadChapter && !loadType) return false;
        boolean missingChapter = loadChapter && (post.latestChapter == null || post.latestChapter.trim().isEmpty());
        String rawType = post.typeLabel == null ? "" : post.typeLabel.trim();
        boolean missingType = loadType && rawType.isEmpty();
        boolean komikindoDefaultType = loadType && MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId) && "MANGA".equalsIgnoreCase(rawType);
        boolean enrichTypeSource = loadType && (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_CROTPEDIA.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId));
        return missingChapter || (enrichTypeSource && (missingType || komikindoDefaultType));
    }

    private void enrichLatestBatch(String sourceId, ArrayList<MangaPost> targets, int start, int generation, boolean global) {
        if (!isAdded() || targets == null || start >= targets.size()) return;
        if (global && generation != globalGeneration) return;
        if (!global && generation != loadGeneration) return;
        int end = Math.min(targets.size(), start + LATEST_ENRICH_BATCH);
        ArrayList<MangaPost> batch = new ArrayList<>(targets.subList(start, end));
        MangaSourceFactory.createBySourceId(sourceId).enrichLatest(batch, () -> {
            if (!isAdded()) return;
            if (global && generation != globalGeneration) return;
            if (!global && generation != loadGeneration) return;
            if (end >= targets.size()) {
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            mainHandler.postDelayed(() -> enrichLatestBatch(sourceId, targets, end, generation, global), 120);
        });
    }

    private void enrichVisibleGlobalPosts() {
        if (!searchPage() || !TAB_GLOBAL.equals(activeSearchTab) || enrichingVisible || gridView == null) return;
        GlobalSourceState state = globalStates.get(activeGlobalSourceId);
        if (state == null || state.data.isEmpty()) return;
        int first = Math.max(0, gridView.getFirstVisiblePosition());
        int last = Math.min(state.data.size(), first + Math.max(gridView.getChildCount(), 8) + 4);
        ArrayList<MangaPost> visible = new ArrayList<>();
        for (int i = first; i < last; i++) {
            MangaPost post = state.data.get(i);
            String key = sourceItemKey(post);
            if (post != null && post.slug != null && !post.slug.trim().isEmpty() && shouldEnrichPost(activeGlobalSourceId, post) && !key.isEmpty() && latestEnrichKeys.add(key)) visible.add(post);
        }
        if (visible.isEmpty()) return;
        enrichingVisible = true;
        int generation = globalGeneration;
        MangaSourceFactory.createBySourceId(activeGlobalSourceId).enrichLatest(visible, () -> {
            enrichingVisible = false;
            if (isAdded() && generation == globalGeneration) renderGlobalTab(false);
        });
    }

    private void applySelectedTypeFallback(MangaPost post) {
        if (!MangaSettingsManager.shouldLoadTypeLabel(getContext())) return;
        if (post == null || selectedTypeLabel == null || selectedTypeLabel.trim().isEmpty()) return;
        String type = selectedTypeLabel.trim().toUpperCase(Locale.ROOT);
        if (!"MANGA".equals(type) && !"MANHWA".equals(type) && !"MANHUA".equals(type) && !"DOUJINSHI".equals(type) && !"IMAGE-SET".equals(type) && !"ONESHOT".equals(type) && !"ONE-SHOT".equals(type) && !"COMIC".equals(type) && !"NOVEL".equals(type)) return;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(post.getSourceId()) || usesLocalTypeFilter(post.getSourceId())) return;
        String raw = post.typeLabel == null ? "" : post.typeLabel.trim();
        if (raw.isEmpty() || "MANGA".equalsIgnoreCase(raw)) post.typeLabel = type;
    }

    private boolean hasTypeFilter() {
        if (selectedTypeLabel == null || selectedTypeLabel.trim().isEmpty()) return false;
        if (searchPage()) return !empty(searchSourceOnlyId()) || !empty(activeGlobalSourceId);
        return true;
    }

    private boolean matchesTypeFilter(MangaPost post) {
        if (!hasTypeFilter() || post == null) return true;
        String sourceId = post.getSourceId();
        if (sourceId == null || sourceId.trim().isEmpty()) sourceId = currentSourceId();
        if (!usesLocalTypeFilter(sourceId)) return true;
        String type = post.getTypeLabel();
        return type != null && normalizeTypeForCompare(type).equals(normalizeTypeForCompare(selectedTypeLabel));
    }

    private String normalizeTypeForCompare(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if ("ONE-SHOT".equals(type)) return "ONESHOT";
        return type;
    }

    private String sourceItemKey(MangaPost post) {
        if (post == null) return "";
        String slug = post.slug == null ? "" : post.slug.trim();
        String title = post.title == null ? "" : post.title.trim();
        String base = slug.isEmpty() ? title : slug;
        if (base.isEmpty()) return "";
        return post.getSourceId() + ":" + base;
    }

    private void showLoading(boolean initial) {
        if (progressBar != null) progressBar.setVisibility(initial ? View.VISIBLE : View.GONE);
        if (bottomProgressBar != null) bottomProgressBar.setVisibility(initial ? View.GONE : View.VISIBLE);
    }

    private void hideLoading() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (bottomProgressBar != null) bottomProgressBar.setVisibility(View.GONE);
    }

    protected boolean empty(String value){ return value == null || value.trim().isEmpty(); }

    private void appendPage(ArrayList<MangaPost> data, boolean next, boolean clear, int generation){
        if(!isAdded() || generation != loadGeneration) return;
        if (clear) {
            loadedSlugs.clear();
            posts.clear();
        }
        int before = posts.size();
        ArrayList<MangaPost> appended = new ArrayList<>();
        if (data != null) {
            String sourceId = currentSourceId();
            String sourceLabel = MangaSourceFactory.labelForSourceId(sourceId);
            for(MangaPost post:data){
                if (post == null) continue;
                post.withSource(sourceId, sourceLabel);
                applySelectedTypeFallback(post);
                if (!matchesTypeFilter(post)) continue;
                String itemKey = post.slug==null||post.slug.isEmpty()?post.title:post.slug;
                if(itemKey != null && !itemKey.isEmpty() && loadedSlugs.add(itemKey)) {
                    MangaLabelUtils.applyHiddenLabels(getContext(), post);
                    posts.add(post);
                    appended.add(post);
                    if (appended.size() <= MAX_COVER_PRELOAD) MangaImageLoader.preload(requireContext(), post.coverImage, post.getSourceId());
                }
            }
        }
        boolean typeFilterActive = hasTypeFilter();
        hasMore = typeFilterActive ? next : next && posts.size() > before;
        page++;
        loading=false;
        hideLoading();
        hideErrorFeedback();
        if (adapter != null) {
            if (clear) adapter.animateItems(appended);
            adapter.notifyDataSetChanged();
        }
        if (clear) {
            if (gridView != null) {
                gridView.animate().cancel();
                gridView.setAlpha(1f);
            }
            restoreScrollPosition();
        }
        if (!staticList() && !appended.isEmpty()) enrichLatestBatched(currentSourceId(), appended, generation, false);
        if (typeFilterActive && hasMore && posts.size() < FILTERED_AUTO_LOAD_TARGET && filteredAutoLoadCount < FILTERED_AUTO_LOAD_LIMIT) {
            filteredAutoLoadCount++;
            mainHandler.post(() -> {
                if (isAdded() && generation == loadGeneration && hasMore && !loading) load(false);
            });
        }
    }
}
