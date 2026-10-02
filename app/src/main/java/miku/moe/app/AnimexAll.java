package miku.moe.app;

import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.color.MaterialColors;

public class AnimexAll extends AppCompatActivity {
    public static final String EXTRA_SOURCE_ID = "source_id";
    public static final String EXTRA_SOURCE_LABEL = "source_label";
    public static final String EXTRA_QUERY = "query";

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_miku_all);
        setupSystemBars();
        String sourceId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        String sourceLabel = getIntent().getStringExtra(EXTRA_SOURCE_LABEL);
        if (sourceId == null || sourceId.trim().isEmpty()) sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
        if (sourceLabel == null || sourceLabel.trim().isEmpty()) sourceLabel = AnimeSettingsManager.labelForSourceId(sourceId);
        android.widget.TextView titleTextView = findViewById(R.id.titleTextView);
        android.view.View backButton = findViewById(R.id.backButton);
        if (titleTextView != null) titleTextView.setText(sourceLabel);
        if (backButton != null) backButton.setOnClickListener(v -> onBackPressed());
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbarVisibility);
        if (savedInstanceState == null) {
            String query = getIntent().getStringExtra(EXTRA_QUERY);
            Fragment fragment = createSourceFragment(sourceId, query == null ? "" : query);
            getSupportFragmentManager().beginTransaction().replace(R.id.mikuAllContainer, fragment, "anime_all_grid").commit();
        }
        updateToolbarVisibility();
    }

    private Fragment createSourceFragment(String sourceId, String query) {
        return BrowseSourceAnime.newSource(sourceId, AnimeSettingsManager.labelForSourceId(sourceId), query);
    }

    public void openDetail(int categoryId) { openDetail(categoryId, -1); }

    public void openDetail(int categoryId, int channelId) {
        AnimePost post = new AnimePost("", "", categoryId, channelId);
        post.sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
        openAnimeDetailV2(post);
    }

    public void openAnimekuDetail(int categoryId, int videoId, String title, String imageUrl, String genre, String rating, int year, String views, String episodeCount) {
        openAnimekuDetail(categoryId, videoId, title, imageUrl, genre, rating, year, views, episodeCount, "");
    }

    public void openAnimekuDetail(int categoryId, int videoId, String title, String imageUrl, String genre, String rating, int year, String views, String episodeCount, String description) {
        AnimePost post = new AnimePost(imageUrl, title, categoryId, videoId);
        post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
        post.genre = genre == null ? "" : genre;
        post.rating = rating == null ? "" : rating;
        post.year = year;
        post.countView = views == null ? "" : views;
        post.episodeCount = episodeCount == null ? "" : episodeCount;
        post.description = description == null ? "" : description;
        openAnimeDetailV2(post);
    }

    public void openAnimeLoverzDetail(String slug, String title, String imageUrl, String genre, String rating, String status, String description) {
        AnimePost post = new AnimePost(imageUrl, title, slug == null ? -1 : Math.abs(slug.hashCode()), -1);
        post.sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ;
        post.slug = slug == null ? "" : slug;
        post.genre = genre == null ? "" : genre;
        post.rating = rating == null ? "" : rating;
        post.statusVideo = status == null ? "" : status;
        post.description = description == null ? "" : description;
        openAnimeDetailV2(post);
    }

    public void openAnimeDetailV2(AnimePost post) {
        if (post == null) return;
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, AnimeDetailV2Fragment.newInstance(post), "anime_detail_v2")
                .addToBackStack("anime_detail_v2")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    public void openAnimeGenreResult(String sourceId, String sourceLabel, String genreTitle, String genreValue) {
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, BrowseSourceAnime.newGenre(sourceId, sourceLabel, genreTitle, genreValue), "browse_source_anime_genre")
                .addToBackStack("browse_source_anime_genre")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    private Fragment getCurrentContentFragment() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment != null && fragment.isAdded() && !fragment.isHidden() && fragment.getId() == R.id.mikuAllContainer) return fragment;
        }
        return getSupportFragmentManager().findFragmentById(R.id.mikuAllContainer);
    }

    private void updateToolbarVisibility() {
        View toolbar = findViewById(R.id.mikuAllToolbar);
        if (toolbar == null) return;
        boolean showToolbar = getSupportFragmentManager().getBackStackEntryCount() == 0;
        toolbar.setVisibility(showToolbar ? View.VISIBLE : View.GONE);
    }

    private void setupSystemBars() {
        ThemeManager.applySystemBars(this);
    }

    @Override protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
    }

}
