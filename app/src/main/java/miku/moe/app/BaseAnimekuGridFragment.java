package miku.moe.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class BaseAnimekuGridFragment extends Fragment {
    private static final String TAG = "AnimekuGrid";
    private static final String API_BASE = "https://pencarinafkah.xyz/vA6//api/";
    private static final String API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO";
    private static final String IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/";
    private static final int PAGE_SIZE = 20;
    private static final int PREFETCH_DISTANCE = 6;

    protected GridView gridView;
    protected ProgressBar progressBar;
    protected TextView titleTextView;
    protected TextInputLayout searchInputLayout;
    protected TextInputEditText searchEditText;
    protected HorizontalScrollView genreScrollView;
    protected ChipGroup genreChipGroup;
    protected TextView searchHelperTextView;
    protected TabLayout homeTabLayout;
    protected View searchHeaderCard;
    protected ImageView genreFilterButton;
    protected final ArrayList<AnimePost> animePosts = new ArrayList<>();
    protected AnimekuGridAdapter adapter;
    protected int currentPage = 0;
    protected boolean isLoading = false;
    protected boolean hasMoreData = true;

    private final HashSet<String> loadedKeys = new HashSet<>();
    private RequestQueue requestQueue;
    private int savedFirstVisiblePosition = 0;
    private int savedTopOffset = 0;
    private int homeTabMode = 0;
    private String lastQuery = "";
    private String selectedGenre = "";
    private final ArrayList<String> genreMenuItems = new ArrayList<>();
    private boolean genreMenuLoading = false;

    protected abstract boolean isSearchPage();
    protected abstract String screenTitle();
    protected String initialQuery() { return ""; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(requireActivity() instanceof AnimexAll ? R.layout.fragment_ikiru_manga_grid : R.layout.fragment_animeku_grid, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requestQueue = Volley.newRequestQueue(requireContext().getApplicationContext());
        gridView = view.findViewById(R.id.gridView);
        ViewCompat.setNestedScrollingEnabled(gridView, true);
        progressBar = view.findViewById(R.id.progressBar);
        titleTextView = view.findViewById(R.id.titleTextView);
        if (requireActivity() instanceof AnimexAll) {
            titleTextView.setVisibility(View.GONE);
            TextView subtitle = view.findViewById(R.id.headerSubtitleTextView);
            if (subtitle != null) subtitle.setVisibility(View.GONE);
        }
        searchInputLayout = view.findViewById(R.id.searchInputLayout);
        searchEditText = view.findViewById(R.id.searchEditText);
        genreScrollView = view.findViewById(R.id.genreScrollView);
        genreChipGroup = view.findViewById(R.id.genreChipGroup);
        searchHelperTextView = view.findViewById(R.id.searchHelperTextView);
        homeTabLayout = view.findViewById(R.id.homeTabLayout);
        searchHeaderCard = view.findViewById(R.id.searchHeaderCard);
        genreFilterButton = view.findViewById(R.id.genreFilterButton);
        titleTextView.setText(screenTitle());

        adapter = new AnimekuGridAdapter(requireContext(), animePosts, post -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).openAnimekuDetail(post.categoryId, post.channelId, post.categoryName, post.imgUrl, post.genre, post.rating, post.year, post.countView, post.episodeCount, post.description);
            } else if (requireActivity() instanceof AnimexAll) {
                ((AnimexAll) requireActivity()).openAnimekuDetail(post.categoryId, post.channelId, post.categoryName, post.imgUrl, post.genre, post.rating, post.year, post.countView, post.episodeCount, post.description);
            }
        }, false);
        gridView.setAdapter(adapter);
        setupInfiniteScroll();

        boolean inAnimexAll = requireActivity() instanceof AnimexAll;
        if (isSearchPage()) {
            homeTabLayout.setVisibility(View.GONE);
            if (searchHeaderCard != null) searchHeaderCard.setVisibility(View.VISIBLE);
            setupSearchUi();
            applyInitialQueryIfNeeded();
        } else {
            if (inAnimexAll) {
                if (searchHeaderCard != null) searchHeaderCard.setVisibility(View.VISIBLE);
                searchInputLayout.setVisibility(View.VISIBLE);
                searchInputLayout.setHint(screenTitle());
                searchEditText.setHint("Cari anime");
                if (searchHelperTextView != null) searchHelperTextView.setVisibility(View.GONE);
                setupSearchUi();
            } else {
                if (searchHeaderCard != null) searchHeaderCard.setVisibility(View.GONE);
                searchInputLayout.setVisibility(View.GONE);
                if (genreScrollView != null) genreScrollView.setVisibility(View.GONE);
                if (searchHelperTextView != null) searchHelperTextView.setVisibility(View.GONE);
                if (genreFilterButton != null) genreFilterButton.setVisibility(View.GONE);
            }
            setupHomeTabs();
            if (inAnimexAll && applyInitialQueryIfNeeded()) return;
            if (animePosts.isEmpty()) fetchHomeData(1, true); else {
                adapter.notifyDataSetChanged();
                restoreScrollPosition();
            }
        }
    }

    @Override
    public void onPause() {
        saveScrollPosition();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (requestQueue != null) requestQueue.cancelAll(TAG);
        super.onDestroyView();
    }

    private void saveScrollPosition() {
        if (gridView == null) return;
        savedFirstVisiblePosition = gridView.getFirstVisiblePosition();
        View firstChild = gridView.getChildAt(0);
        savedTopOffset = firstChild == null ? 0 : firstChild.getTop() - gridView.getPaddingTop();
    }

    private void restoreScrollPosition() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (gridView != null) gridView.setSelectionFromTop(savedFirstVisiblePosition, savedTopOffset);
        });
    }

    private void setupInfiniteScroll() {
        gridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}
            @Override public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (totalItemCount == 0 || visibleItemCount == 0) return;
                boolean reachedBottom = firstVisibleItem + visibleItemCount >= totalItemCount - PREFETCH_DISTANCE;
                if (reachedBottom && !isLoading && hasMoreData) loadNextPage();
            }
        });
    }

    private void requestNextPageIfNeeded() {
        if (!isAdded() || getView() == null || isLoading || !hasMoreData || gridView == null || adapter == null) return;
        int total = adapter.getCount();
        if (total <= 0) return;
        int lastVisible = gridView.getLastVisiblePosition();
        if (lastVisible < 0) return;
        if (lastVisible >= total - PREFETCH_DISTANCE) loadNextPage();
    }

    private void requestNextPageAfterRender() {
        if (!hasMoreData || gridView == null) return;
        gridView.post(this::requestNextPageIfNeeded);
    }

    private void setupHomeTabs() {
        if (homeTabLayout == null) return;
        homeTabLayout.setVisibility(View.VISIBLE);
        if (requireActivity() instanceof AnimexAll) {
            ViewGroup.MarginLayoutParams lp = homeTabLayout.getLayoutParams() instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) homeTabLayout.getLayoutParams() : null;
            if (lp != null) {
                lp.topMargin = 0;
                homeTabLayout.setLayoutParams(lp);
            }
        }
        if (homeTabLayout.getTabCount() == 0) {
            homeTabLayout.addTab(homeTabLayout.newTab().setText("Terbaru"));
            homeTabLayout.addTab(homeTabLayout.newTab().setText("Populer"));
            homeTabLayout.addTab(homeTabLayout.newTab().setText("completed"));
            homeTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    int pos = tab.getPosition();
                    if (homeTabMode == pos && !animePosts.isEmpty() && lastQuery.isEmpty() && selectedGenre.isEmpty()) return;
                    homeTabMode = pos;
                    lastQuery = "";
                    selectedGenre = "";
                    searchEditText.setText("");
                    updateGenreFilterButtonState();
                    resetPagingState();
                    fetchHomeData(1, true);
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
        TabLayout.Tab selected = homeTabLayout.getTabAt(homeTabMode);
        if (selected != null && !selected.isSelected()) selected.select();
    }

    private void setupSearchUi() {
        boolean inAnimexAll = requireActivity() instanceof AnimexAll;
        searchInputLayout.setVisibility(View.VISIBLE);
        if (inAnimexAll) {
            if (genreScrollView != null) genreScrollView.setVisibility(View.GONE);
            if (searchHelperTextView != null) searchHelperTextView.setVisibility(View.GONE);
            setupGenreFilterMenu();
        } else {
            if (genreFilterButton != null) genreFilterButton.setVisibility(View.GONE);
            if (genreScrollView != null) genreScrollView.setVisibility(View.VISIBLE);
            if (searchHelperTextView != null) {
                searchHelperTextView.setVisibility(View.VISIBLE);
                searchHelperTextView.setText("Cari judul anime atau pilih genre Animeku");
            }
            if (genreChipGroup != null && genreChipGroup.getChildCount() == 0) fetchGenreList();
        }
        searchInputLayout.setEndIconOnClickListener(v -> startSearch());
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch();
                return true;
            }
            return false;
        });
        if (!lastQuery.isEmpty()) searchEditText.setText(lastQuery);
        if (!animePosts.isEmpty()) {
            adapter.notifyDataSetChanged();
            restoreScrollPosition();
        }
    }

    private boolean applyInitialQueryIfNeeded() {
        String startQuery = initialQuery() == null ? "" : initialQuery().trim();
        if (startQuery.isEmpty() || !lastQuery.isEmpty()) return false;
        lastQuery = startQuery;
        selectedGenre = "";
        searchEditText.setText(startQuery);
        searchEditText.setSelection(searchEditText.getText() == null ? 0 : searchEditText.getText().length());
        updateGenreFilterButtonState();
        resetPagingState();
        fetchSearchData(startQuery, 1, true);
        return true;
    }

    private void setupGenreFilterMenu() {
        if (genreFilterButton == null) return;
        genreFilterButton.setVisibility(View.VISIBLE);
        genreFilterButton.setOnClickListener(v -> showGenreFilterMenu());
        if (genreMenuItems.isEmpty() && !genreMenuLoading) fetchGenreMenuList();
        updateGenreFilterButtonState();
    }

    private void fetchGenreMenuList() {
        if (requestQueue == null) return;
        genreMenuLoading = true;
        String url = API_BASE + "get_genre_index?api_key=" + API_KEY;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONArray array = parseGenreIndex(response);
                renderGenreMenuItems(array);
            } catch (Exception e) {
                Log.e(TAG, "Genre menu parse error", e);
            } finally {
                genreMenuLoading = false;
                updateGenreFilterButtonState();
            }
        }, error -> {
            Log.e(TAG, "Genre menu network error", error);
            genreMenuLoading = false;
            updateGenreFilterButtonState();
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void renderGenreMenuItems(JSONArray genres) {
        genreMenuItems.clear();
        if (genres == null) return;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < genres.length(); i++) {
            JSONObject item = genres.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("genre_anime", "").trim();
            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.US))) continue;
            genreMenuItems.add(name);
        }
    }

    private void showGenreFilterMenu() {
        if (genreFilterButton == null || !isAdded()) return;
        if (genreMenuLoading && genreMenuItems.isEmpty()) {
            showToast("Memuat genre...");
            return;
        }
        PopupMenu popupMenu = new PopupMenu(requireContext(), genreFilterButton);
        android.view.MenuItem all = popupMenu.getMenu().add(0, 0, 0, "Semua");
        all.setCheckable(true);
        all.setChecked(selectedGenre.isEmpty());
        for (int i = 0; i < genreMenuItems.size(); i++) {
            String genre = genreMenuItems.get(i);
            android.view.MenuItem item = popupMenu.getMenu().add(0, i + 1, i + 1, genre);
            item.setCheckable(true);
            item.setChecked(genre.equalsIgnoreCase(selectedGenre));
        }
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 0) {
                selectedGenre = "";
                updateGenreFilterButtonState();
                resetPagingState();
                if (!lastQuery.isEmpty()) fetchSearchData(lastQuery, 1, true); else fetchHomeData(1, true);
                return true;
            }
            int index = item.getItemId() - 1;
            if (index >= 0 && index < genreMenuItems.size()) {
                selectedGenre = genreMenuItems.get(index);
                lastQuery = "";
                searchEditText.setText("");
                updateGenreFilterButtonState();
                resetPagingState();
                fetchGenreData(selectedGenre, 1, true);
            }
            return true;
        });
        popupMenu.show();
    }

    private void updateGenreFilterButtonState() {
        if (genreFilterButton == null || getView() == null) return;
        boolean active = selectedGenre != null && !selectedGenre.trim().isEmpty();
        int color = MaterialColors.getColor(getView(), active ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant);
        genreFilterButton.setColorFilter(color);
        genreFilterButton.setContentDescription(active ? "Filter genre aktif" : "Filter genre");
    }

    private void startSearch() {
        String query = searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim();
        if (query.isEmpty()) {
            showToast("Masukkan judul anime");
            return;
        }
        lastQuery = query;
        selectedGenre = "";
        if (genreChipGroup != null) genreChipGroup.clearCheck();
        updateGenreFilterButtonState();
        resetPagingState();
        fetchSearchData(query, 1, true);
    }

    private void loadNextPage() {
        int nextPage = Math.max(1, currentPage + 1);
        if (!lastQuery.isEmpty()) {
            fetchSearchData(lastQuery, nextPage, false);
            return;
        }
        if (!selectedGenre.isEmpty()) {
            fetchGenreData(selectedGenre, nextPage, false);
            return;
        }
        if (isSearchPage()) return;
        fetchHomeData(nextPage, false);
    }

    public void refreshHome() {
        if (!isAdded() || isSearchPage()) return;
        resetPagingState();
        fetchHomeData(1, true);
    }

    private void resetPagingState() {
        currentPage = 0;
        hasMoreData = true;
        loadedKeys.clear();
        animePosts.clear();
        savedFirstVisiblePosition = 0;
        savedTopOffset = 0;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void fetchHomeData(int page, boolean reset) {
        String endpoint;
        if (homeTabMode == 1) endpoint = "get_category_popular";
        else if (homeTabMode == 2) endpoint = "get_category_complete";
        else endpoint = "get_videos";
        String url = API_BASE + endpoint + "?page=" + page + "&count=" + PAGE_SIZE + "&api_key=" + API_KEY;
        launchGet(url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    showToast("Gagal mengambil data Animeku");
                    return;
                }
                int countTotal = json.optInt("count_total", -1);
                if (homeTabMode == 0) appendLatest(json.optJSONArray("latest_anime"), page, reset, countTotal);
                else appendCategories(json.optJSONArray("new_anime"), page, reset, countTotal, false);
            } catch (Exception e) {
                Log.e(TAG, "Parse error", e);
                showToast("Terjadi kesalahan parsing data Animeku");
            }
        });
    }

    private void fetchSearchData(String query, int page, boolean reset) {
        String url = API_BASE + "get_category_genre?search=" + encode(query) + "&page=" + page + "&count=" + PAGE_SIZE + "&sort=c.category_name%20ASC&api_key=" + API_KEY;
        launchGet(url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    showToast("Gagal mencari Animeku");
                    return;
                }
                int countTotal = json.optInt("count_total", json.optInt("count", -1));
                JSONArray filtered = filterCategoriesByTitle(json.optJSONArray("categories"), query);
                appendCategories(filtered, page, reset, countTotal, true);
                if (animePosts.isEmpty()) showToast("Anime tidak ditemukan");
            } catch (Exception e) {
                Log.e(TAG, "Search parse error", e);
                showToast("Terjadi kesalahan parsing pencarian Animeku");
            }
        });
    }

    private void fetchGenreList() {
        String url = API_BASE + "get_genre_index?api_key=" + API_KEY;
        launchGet(url, response -> {
            try {
                JSONArray array = parseGenreIndex(response);
                renderGenreChips(array);
            } catch (Exception e) {
                Log.e(TAG, "Genre list parse error", e);
                showToast("Gagal mengambil daftar genre Animeku");
            }
        });
    }

    private void fetchGenreData(String genreName, int page, boolean reset) {
        String url = API_BASE + "get_category_genre?search=" + encode(genreName) + "&page=" + page + "&count=" + PAGE_SIZE + "&sort=c.category_name%20ASC&api_key=" + API_KEY;
        launchGet(url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    showToast("Gagal mengambil genre Animeku");
                    return;
                }
                int countTotal = json.optInt("count_total", json.optInt("count", -1));
                JSONArray filtered = filterCategoriesByGenre(json.optJSONArray("categories"), genreName);
                appendCategories(filtered, page, reset, countTotal, true);
                if (animePosts.isEmpty()) showToast("Genre ini belum memiliki anime");
            } catch (Exception e) {
                Log.e(TAG, "Genre parse error", e);
                showToast("Terjadi kesalahan parsing genre Animeku");
            }
        });
    }

    private JSONArray parseGenreIndex(String response) throws Exception {
        try {
            return new JSONArray(response);
        } catch (Exception ignored) {
            JSONArray array = new JSONArray();
            Matcher matcher = Pattern.compile("genre_anime\\s*:\\s*\"([^\"]+)\"").matcher(response == null ? "" : response);
            while (matcher.find()) {
                JSONObject item = new JSONObject();
                item.put("genre_anime", matcher.group(1));
                array.put(item);
            }
            if (array.length() == 0) throw ignored;
            return array;
        }
    }

    private void renderGenreChips(JSONArray genres) {
        if (genreChipGroup == null) return;
        genreChipGroup.removeAllViews();
        genreChipGroup.setSingleSelection(true);
        genreChipGroup.setSelectionRequired(false);

        Chip allChip = createGenreChip("Semua");
        allChip.setOnClickListener(v -> {
            selectedGenre = "";
            if (genreChipGroup != null) genreChipGroup.clearCheck();
            resetPagingState();
            if (!lastQuery.isEmpty()) {
                searchEditText.setText(lastQuery);
                fetchSearchData(lastQuery, 1, true);
            }
        });
        genreChipGroup.addView(allChip);

        if (genres == null) return;
        for (int i = 0; i < genres.length(); i++) {
            JSONObject item = genres.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("genre_anime", "").trim();
            if (name.isEmpty()) continue;
            Chip chip = createGenreChip(name);
            chip.setOnClickListener(v -> {
                selectedGenre = name;
                lastQuery = "";
                searchEditText.setText("");
                resetPagingState();
                fetchGenreData(name, 1, true);
            });
            genreChipGroup.addView(chip);
        }
    }

    private Chip createGenreChip(String text) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setCheckable(true);
        chip.setClickable(true);
        chip.setSingleLine(true);
        chip.setChipMinHeight(dp(40));
        return chip;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private JSONArray filterCategoriesByTitle(JSONArray array, String query) {
        JSONArray result = new JSONArray();
        if (array == null) return result;
        String needle = normalize(query);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String title = normalize(item.optString("category_name", ""));
            if (title.contains(needle)) result.put(item);
        }
        return result;
    }

    private JSONArray filterCategoriesByGenre(JSONArray array, String genreName) {
        JSONArray result = new JSONArray();
        if (array == null) return result;
        String needle = normalize(genreName);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String genre = normalize(item.optString("genre", ""));
            String status = normalize(item.optString("status_video", ""));
            if (genre.contains(needle) || status.contains(needle)) result.put(item);
        }
        return result;
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value == null ? "" : value.replace(" ", "%20");
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US).replace("-", " ").replace("_", " ");
        normalized = normalized.replace("demonds", "demons").replace("pyscological", "psychological");
        while (normalized.contains("  ")) normalized = normalized.replace("  ", " ");
        return normalized;
    }

    private void launchGet(String url, ResponseConsumer consumer) {
        if (isLoading || requestQueue == null) return;
        isLoading = true;
        showLoading(true);
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                consumer.accept(response);
            } finally {
                finishLoading();
            }
        }, error -> {
            Log.e(TAG, "Network error", error);
            finishLoading();
            showToast("Kesalahan jaringan Animeku");
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void appendLatest(JSONArray array, int page, boolean reset, int countTotal) {
        if (reset) {
            animePosts.clear();
            loadedKeys.clear();
        }
        if (array == null || array.length() == 0) {
            hasMoreData = false;
            adapter.notifyDataSetChanged();
            return;
        }
        int added = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            int categoryId = item.optInt("cat_id", -1);
            int videoId = item.optInt("vid", -1);
            if (categoryId <= 0 || videoId <= 0) continue;
            String uniqueKey = "latest_" + videoId + "_" + categoryId;
            if (loadedKeys.contains(uniqueKey)) continue;
            loadedKeys.add(uniqueKey);
            String title = cleanAnimeTitle(item.optString("category_name", ""));
            String videoTitle = item.optString("video_title", "");
            AnimePost post = new AnimePost(imageUrl(firstUseful(item.optString("category_image", ""), item.optString("video_thumbnail", ""))), title, categoryId, videoId);
            post.channelName = episodeLabel(videoTitle);
            post.episodeCount = episodeLabel(videoTitle);
            post.statusVideo = normalizeStatus(item.optString("status_video", ""));
            post.ongoing = isOngoing(post.statusVideo);
            animePosts.add(post);
            added++;
        }
        currentPage = page;
        hasMoreData = countTotal > 0 ? animePosts.size() < countTotal : array.length() >= PAGE_SIZE;
        if (!reset && added == 0) hasMoreData = false;
        adapter.notifyDataSetChanged();
        if (reset) restoreScrollPosition();
        requestNextPageAfterRender();
    }

    private void appendCategories(JSONArray array, int page, boolean reset, int countTotal, boolean searchResult) {
        if (reset) {
            animePosts.clear();
            loadedKeys.clear();
        }
        if (array == null || array.length() == 0) {
            hasMoreData = false;
            adapter.notifyDataSetChanged();
            return;
        }
        int added = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            int categoryId = item.optInt("cid", item.optInt("cat_id", -1));
            if (categoryId <= 0) continue;
            String uniqueKey = "cat_" + homeTabMode + "_" + categoryId;
            if (loadedKeys.contains(uniqueKey)) continue;
            loadedKeys.add(uniqueKey);
            String title = cleanAnimeTitle(item.optString("category_name", ""));
            AnimePost post = new AnimePost(imageUrl(item.optString("category_image", "")), title, categoryId, -1);
            post.channelName = "";
            post.episodeCount = latestEpisodeLabel(item.optString("video_count", ""));
            post.statusVideo = normalizeStatus(item.optString("status_video", ""));
            post.ongoing = isOngoing(post.statusVideo);
            animePosts.add(post);
            added++;
        }
        currentPage = page;
        hasMoreData = countTotal > 0 ? animePosts.size() < countTotal : array.length() >= PAGE_SIZE;
        if (!reset && added == 0) hasMoreData = false;
        adapter.notifyDataSetChanged();
        if (reset) restoreScrollPosition();
        requestNextPageAfterRender();
    }

    private String cleanAnimeTitle(String value) {
        if (value == null) return "";
        String cleaned = value.trim().replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("(?i)\\s+Eps?\\s*[-:]*\\s*\\d+.*$", "").trim();
        cleaned = cleaned.replaceAll("(?i)\\s+Episode\\s*[-:]*\\s*\\d+.*$", "").trim();
        return cleaned;
    }

    private String episodeLabel(String value) {
        int episode = extractEpisodeNumber(value);
        if (episode != Integer.MAX_VALUE) return String.format(Locale.US, "Episode %02d", episode);
        return "";
    }

    private String latestEpisodeLabel(String value) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())) return "";
        try {
            int count = Integer.parseInt(value.trim());
            if (count > 0) return String.format(Locale.US, "Episode %02d", count);
        } catch (Exception ignored) { }
        return "";
    }

    private int extractEpisodeNumber(String title) {
        if (title == null) return Integer.MAX_VALUE;
        String text = title.toLowerCase(Locale.US);
        Matcher matcher = Pattern.compile("(?:episode|eps|ep|e)\\s*[-:]*\\s*(\\d+)").matcher(text);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); } catch (Exception ignored) { }
        }
        matcher = Pattern.compile("\\b(\\d{1,4})\\b").matcher(text);
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n != 360 && n != 480 && n != 720 && n != 1080) return n;
            } catch (Exception ignored) { }
        }
        return Integer.MAX_VALUE;
    }

    private String normalizeStatus(String value) {
        if (value == null) return "";
        String raw = value.trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return "";
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("complete") || lower.contains("finished")) return "Completed";
        if (lower.contains("ongoing") || lower.contains("on going") || lower.contains("currently")) return "Ongoing";
        return raw;
    }

    private String imageUrl(String image) {
        if (image == null) return "";
        String value = image.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        return IMAGE_BASE + value;
    }

    private boolean isOngoing(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.US);
        return !v.isEmpty() && !v.contains("complete") && !v.contains("finished") && !v.equals("selesai");
    }

    private boolean isPlayable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private String firstUseful(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty() && !"null".equalsIgnoreCase(primary.trim())) return primary.trim();
        return fallback == null ? "" : fallback.trim();
    }

    private void showLoading(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void finishLoading() {
        isLoading = false;
        showLoading(false);
    }

    protected void showToast(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "Your Videos Channel");
        h.put("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)");
        h.put("Accept", "application/vnd.yourapi.v1.full+json");
        return h;
    }

    private interface ResponseConsumer {
        void accept(String response);
    }
}
