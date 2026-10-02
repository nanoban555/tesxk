package miku.moe.app;

import android.os.Bundle;
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

public abstract class BaseAnimeLoverzGridFragment extends Fragment {
    private static final String TAG = "AnimeLoverzGrid";
    private static final String API_BASE = "https://apps.animekita.org/api/v1.2.5";
    private static final int PAGE_SIZE = 20;
    private static final int PREFETCH_DISTANCE = 6;
    private static final String[] GENRE_LABELS = new String[] {
            "Action", "Adventure", "Comedy", "Demons", "Drama", "Ecchi", "Fantasy", "Game", "Harem", "Historical",
            "Horror", "Josei", "Magic", "Martial Arts", "Mecha", "Military", "Music", "Mystery", "Psychological", "Parody",
            "Police", "Romance", "Samurai", "School", "Sci-Fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen", "Slice of Life",
            "Sports", "Space", "Super Power", "Supernatural", "Thriller", "Vampire", "Yaoi", "Yuri"
    };
    private static final String[] GENRE_URLS = new String[] {
            "action/", "adventure/", "comedy/", "demons/", "drama/", "ecchi/", "fantasy/", "game/", "harem/", "historical/",
            "horror/", "josei/", "magic/", "martial-arts/", "mecha/", "military/", "music/", "mystery/", "psychological/", "parody/",
            "police/", "romance/", "samurai/", "school/", "sci-fi/", "seinen/", "shoujo/", "shoujo-ai/", "shounen/", "slice-of-life/",
            "sports/", "space/", "super-power/", "supernatural/", "thriller/", "vampire/", "yaoi/", "yuri/"
    };

    protected GridView gridView;
    protected ProgressBar progressBar;
    protected TextView titleTextView;
    protected TextInputLayout searchInputLayout;
    protected TextInputEditText searchEditText;
    protected HorizontalScrollView genreScrollView;
    protected TextView searchHelperTextView;
    protected TabLayout homeTabLayout;
    protected View searchHeaderCard;
    protected ImageView genreFilterButton;
    protected final ArrayList<AnimePost> animePosts = new ArrayList<>();
    protected AnimeGridAdapter adapter;
    protected int currentPage = 0;
    protected boolean isLoading = false;
    protected boolean hasMoreData = true;

    private final HashSet<String> loadedKeys = new HashSet<>();
    private RequestQueue requestQueue;
    private String lastQuery = "";
    private String selectedCategory = "all";
    private String selectedGenre = "";

    protected abstract String screenTitle();
    protected String initialQuery() { return ""; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(requireActivity() instanceof AnimexAll ? R.layout.fragment_ikiru_manga_grid : R.layout.fragment_anime_grid, container, false);
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
        searchHelperTextView = view.findViewById(R.id.searchHelperTextView);
        homeTabLayout = view.findViewById(R.id.homeTabLayout);
        searchHeaderCard = view.findViewById(R.id.searchHeaderCard);
        genreFilterButton = view.findViewById(R.id.genreFilterButton);
        titleTextView.setText(screenTitle());
        if (searchHeaderCard != null) searchHeaderCard.setVisibility(View.VISIBLE);
        searchInputLayout.setVisibility(View.VISIBLE);
        searchInputLayout.setHint(screenTitle());
        searchEditText.setHint("Cari anime");
        if (genreScrollView != null) genreScrollView.setVisibility(View.GONE);
        if (searchHelperTextView != null) searchHelperTextView.setVisibility(View.GONE);
        adapter = new AnimeGridAdapter(requireContext(), animePosts, this::openAnime);
        gridView.setAdapter(adapter);
        setupInfiniteScroll();
        setupSearchUi();
        setupHomeTabs();
        if (applyInitialQueryIfNeeded()) return;
        if (animePosts.isEmpty()) fetchCategoryData("all", 1, true);
    }

    @Override
    public void onDestroyView() {
        if (requestQueue != null) requestQueue.cancelAll(TAG);
        if (gridView != null) gridView.setAdapter(null);
        super.onDestroyView();
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

    private void setupSearchUi() {
        setupGenreFilterMenu();
        searchInputLayout.setEndIconOnClickListener(v -> startSearch());
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch();
                return true;
            }
            return false;
        });
        if (!lastQuery.isEmpty()) searchEditText.setText(lastQuery);
    }

    private void setupHomeTabs() {
        if (homeTabLayout == null) return;
        homeTabLayout.setVisibility(View.VISIBLE);
        ViewGroup.MarginLayoutParams lp = homeTabLayout.getLayoutParams() instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) homeTabLayout.getLayoutParams() : null;
        if (lp != null) {
            lp.topMargin = 0;
            homeTabLayout.setLayoutParams(lp);
        }
        if (homeTabLayout.getTabCount() == 0) {
            addTab("Terbaru", "all");
            addTab("Baru Upload", "new");
            addTab("Donghua", "donghua");
            addTab("Movie", "movie");
            homeTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    Object tag = tab.getTag();
                    String value = tag == null ? "all" : tag.toString();
                    if (value.equals(selectedCategory) && animePosts.size() > 0 && lastQuery.isEmpty() && selectedGenre.isEmpty()) return;
                    selectedCategory = value;
                    selectedGenre = "";
                    lastQuery = "";
                    searchEditText.setText("");
                    updateGenreFilterButtonState();
                    resetPagingState();
                    fetchCategoryData(selectedCategory, 1, true);
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
        for (int i = 0; i < homeTabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = homeTabLayout.getTabAt(i);
            Object tag = tab == null ? null : tab.getTag();
            if (selectedCategory.equals(tag == null ? "all" : tag.toString())) {
                if (!tab.isSelected()) tab.select();
                break;
            }
        }
    }

    private void addTab(String label, String value) {
        TabLayout.Tab tab = homeTabLayout.newTab().setText(label);
        tab.setTag(value);
        homeTabLayout.addTab(tab);
    }

    private void setupGenreFilterMenu() {
        if (genreFilterButton == null) return;
        genreFilterButton.setVisibility(View.VISIBLE);
        genreFilterButton.setOnClickListener(v -> showGenreFilterMenu());
        updateGenreFilterButtonState();
    }

    private void showGenreFilterMenu() {
        if (genreFilterButton == null || !isAdded()) return;
        PopupMenu popupMenu = new PopupMenu(requireContext(), genreFilterButton);
        android.view.MenuItem all = popupMenu.getMenu().add(0, 0, 0, "Semua");
        all.setCheckable(true);
        all.setChecked(selectedGenre.isEmpty());
        for (int i = 0; i < GENRE_LABELS.length; i++) {
            android.view.MenuItem item = popupMenu.getMenu().add(0, i + 1, i + 1, GENRE_LABELS[i]);
            item.setCheckable(true);
            item.setChecked(GENRE_LABELS[i].equalsIgnoreCase(selectedGenre));
        }
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 0) {
                selectedGenre = "";
                updateGenreFilterButtonState();
                resetPagingState();
                if (!lastQuery.isEmpty()) fetchSearchData(lastQuery, 1, true); else fetchCategoryData(selectedCategory.isEmpty() ? "all" : selectedCategory, 1, true);
                return true;
            }
            int index = item.getItemId() - 1;
            if (index >= 0 && index < GENRE_LABELS.length) {
                selectedGenre = GENRE_LABELS[index];
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

    private void startSearch() {
        String query = searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), "Masukkan judul anime", Toast.LENGTH_SHORT).show();
            return;
        }
        lastQuery = query;
        selectedGenre = "";
        updateGenreFilterButtonState();
        resetPagingState();
        fetchSearchData(query, 1, true);
    }

    private void loadNextPage() {
        int nextPage = Math.max(1, currentPage + 1);
        if (!lastQuery.trim().isEmpty()) fetchSearchData(lastQuery, nextPage, false);
        else if (!selectedGenre.trim().isEmpty()) fetchGenreData(selectedGenre, nextPage, false);
        else if (!"movie".equals(selectedCategory)) fetchCategoryData(selectedCategory.isEmpty() ? "all" : selectedCategory, nextPage, false);
    }

    private void resetPagingState() {
        currentPage = 0;
        hasMoreData = true;
        loadedKeys.clear();
        animePosts.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void fetchCategoryData(String category, int page, boolean reset) {
        if (page > 1 && ("all".equals(category) || "donghua".equals(category))) {
            fetchCategoryFallbackData(category, page, reset);
            return;
        }
        String url;
        if ("new".equals(category)) url = API_BASE + "/baruupload.php?page=" + page;
        else if ("donghua".equals(category)) url = API_BASE + "/home/ongoing.php?page=" + page + "&type=donghua";
        else if ("movie".equals(category)) url = API_BASE + "/movie.php";
        else url = API_BASE + "/home/ongoing.php?page=" + page + "&type=all";
        launchGet(url, response -> {
            try {
                JSONArray array = new JSONArray(response);
                appendList(array, page, reset, !"movie".equals(category));
            } catch (Exception e) {
                Log.e(TAG, "Category parse error", e);
                showToast("Terjadi kesalahan parsing Animeloverz");
            }
        });
    }

    private void fetchCategoryFallbackData(String category, int page, boolean reset) {
        int fallbackPage = Math.max(1, page - 1);
        if ("donghua".equals(category)) {
            String url = API_BASE + "/search.php?keyword=donghua&page=" + fallbackPage + "&per_page=" + PAGE_SIZE;
            launchSearch(url, page, reset, "donghua", false);
            return;
        }
        String url = API_BASE + "/baruupload.php?page=" + fallbackPage;
        launchGet(url, response -> {
            try {
                JSONArray array = new JSONArray(response);
                appendList(array, page, reset, array.length() >= 10);
            } catch (Exception e) {
                Log.e(TAG, "Category fallback parse error", e);
                showToast("Terjadi kesalahan parsing Animeloverz");
            }
        });
    }

    private void fetchGenreData(String genre, int page, boolean reset) {
        String url = API_BASE + "/genreseries.php?page=" + page + "&url=" + encode(getGenreUrl(genre));
        launchGet(url, response -> {
            try {
                JSONArray array = new JSONArray(response);
                appendList(array, page, reset, array.length() > 0);
                if (animePosts.isEmpty()) showToast("Genre ini belum memiliki anime");
            } catch (Exception e) {
                Log.e(TAG, "Genre parse error", e);
                showToast("Terjadi kesalahan parsing genre Animeloverz");
            }
        });
    }

    private void fetchSearchData(String query, int page, boolean reset) {
        String url = API_BASE + "/search.php?keyword=" + encode(query) + "&page=" + page + "&per_page=" + PAGE_SIZE;
        launchSearch(url, page, reset, query, false);
    }

    private void launchSearch(String url, int page, boolean reset, String keyword, boolean filterGenre) {
        launchGet(url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONArray data = json.optJSONArray("data");
                JSONArray result = new JSONArray();
                boolean hasNext = false;
                if (data != null) {
                    for (int d = 0; d < data.length(); d++) {
                        JSONObject block = data.optJSONObject(d);
                        if (block == null) continue;
                        JSONArray items = block.optJSONArray("result");
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item == null) continue;
                                if (filterGenre && !matchesGenre(item, keyword)) continue;
                                result.put(item);
                            }
                        }
                        JSONObject pagination = block.optJSONObject("pagination");
                        if (pagination != null) hasNext = pagination.optBoolean("has_next", false);
                    }
                }
                appendList(result, page, reset, hasNext);
                if (animePosts.isEmpty()) showToast(filterGenre ? "Genre ini belum memiliki anime" : "Anime tidak ditemukan");
            } catch (Exception e) {
                Log.e(TAG, "Search parse error", e);
                showToast("Terjadi kesalahan parsing pencarian Animeloverz");
            }
        });
    }

    private boolean matchesGenre(JSONObject item, String genre) {
        JSONArray array = item == null ? null : item.optJSONArray("genre");
        if (array == null) return true;
        String target = normalize(genre);
        for (int i = 0; i < array.length(); i++) if (normalize(array.optString(i, "")).contains(target)) return true;
        return false;
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
            showToast("Kesalahan jaringan Animeloverz");
        }) {
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void appendList(JSONArray array, int page, boolean reset, boolean next) {
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
            AnimePost post = parsePost(item);
            if (post == null) continue;
            String key = dedupeKey(post);
            if (!loadedKeys.add(key)) continue;
            animePosts.add(post);
            added++;
        }
        currentPage = page;
        hasMoreData = next && added > 0;
        adapter.notifyDataSetChanged();
        requestNextPageAfterRender();
    }

    private AnimePost parsePost(JSONObject item) {
        if (item == null) return null;
        String title = firstNonEmpty(item.optString("judul", ""), firstNonEmpty(item.optString("anime_name", ""), firstNonEmpty(item.optString("title", ""), item.optString("name", "")))).trim();
        String slug = firstNonEmpty(item.optString("url", ""), firstNonEmpty(item.optString("link", ""), firstNonEmpty(item.optString("slug", ""), item.optString("permalink", "")))).trim();
        if (title.isEmpty() || slug.isEmpty()) return null;
        int id = parseId(item.optString("id", ""), slug);
        AnimePost post = new AnimePost(firstNonEmpty(item.optString("cover", ""), firstNonEmpty(item.optString("thumb", ""), firstNonEmpty(item.optString("thumbnail", ""), item.optString("image", "")))), title, id, -1);
        post.sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ;
        post.slug = slug;
        post.channelName = firstNonEmpty(item.optString("lastch", ""), firstNonEmpty(item.optString("episode", ""), item.optString("ch", "")));
        post.episodeCount = firstNonEmpty(item.optString("total_episode", ""), item.optString("episode_count", ""));
        post.genre = firstNonEmpty(joinArray(item.optJSONArray("genre")), joinArray(item.optJSONArray("genres")));
        post.rating = firstNonEmpty(item.optString("score", ""), firstNonEmpty(item.optString("rating", ""), item.optString("rate", "")));
        post.statusVideo = firstNonEmpty(item.optString("status", ""), firstNonEmpty(item.optString("release_status", ""), item.optString("anime_status", "")));
        post.description = firstNonEmpty(item.optString("sinopsis", ""), firstNonEmpty(item.optString("synopsis", ""), firstNonEmpty(item.optString("description", ""), item.optString("desc", ""))));
        post.ongoing = !post.statusVideo.toLowerCase(Locale.US).contains("complete");
        return post;
    }

    private int parseId(String raw, String slug) {
        try {
            int id = Integer.parseInt(raw.trim());
            if (id > 0) return id;
        } catch (Exception ignored) { }
        int value = slug == null ? 1 : slug.hashCode();
        return value == Integer.MIN_VALUE ? 1 : Math.abs(value);
    }

    private String dedupeKey(AnimePost post) {
        if (post == null) return "";
        String slug = post.slug == null ? "" : post.slug.trim();
        while (slug.endsWith("/")) slug = slug.substring(0, slug.length() - 1);
        if (!slug.isEmpty()) return slug.toLowerCase(Locale.US);
        return String.valueOf(post.categoryId);
    }

    private String joinArray(JSONArray array) {
        if (array == null) return "";
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) values.add(value);
        }
        return android.text.TextUtils.join(", ", values);
    }

    private String getGenreUrl(String genre) {
        String target = normalize(genre);
        for (int i = 0; i < GENRE_LABELS.length && i < GENRE_URLS.length; i++) {
            if (normalize(GENRE_LABELS[i]).equals(target)) return GENRE_URLS[i];
        }
        return normalize(genre).replace(" ", "-") + "/";
    }

    private String firstNonEmpty(String first, String second) {
        String a = first == null ? "" : first.trim();
        if (!a.isEmpty()) return a;
        return second == null ? "" : second.trim();
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
        return value.trim().toLowerCase(Locale.US).replace("-", " ").replace("_", " ");
    }

    private void openAnime(AnimePost post) {
        if (post == null) return;
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openAnimeLoverzDetail(post.slug, post.categoryName, post.imgUrl, post.genre, post.rating, post.statusVideo, post.description);
        } else if (requireActivity() instanceof AnimexAll) {
            ((AnimexAll) requireActivity()).openAnimeLoverzDetail(post.slug, post.categoryName, post.imgUrl, post.genre, post.rating, post.statusVideo, post.description);
        }
    }

    protected void showToast(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void showLoading(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void finishLoading() {
        isLoading = false;
        showLoading(false);
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", "Dart/3.9 (dart:io)");
        h.put("accept", "application/json");
        return h;
    }

    private interface ResponseConsumer {
        void accept(String response);
    }
}
