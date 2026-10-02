package miku.moe.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;

public class MikuAll extends AppCompatActivity {
    private final Handler mangaNavigationHandler = new Handler(Looper.getMainLooper());
    private boolean mangaNavigationLocked = false;
    public static final String EXTRA_SOURCE_ID = "source_id";
    public static final String EXTRA_SOURCE_LABEL = "source_label";

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_miku_all);
        setupSystemBars();
        String sourceId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        String sourceLabel = getIntent().getStringExtra(EXTRA_SOURCE_LABEL);
        if (sourceId == null || sourceId.trim().isEmpty()) sourceId = MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
        if (sourceLabel == null || sourceLabel.trim().isEmpty()) sourceLabel = MangaSourceFactory.labelForSourceId(sourceId);
        android.widget.TextView titleTextView = findViewById(R.id.titleTextView);
        android.view.View backButton = findViewById(R.id.backButton);
        if (titleTextView != null) titleTextView.setText(sourceLabel);
        if (backButton != null) backButton.setOnClickListener(v -> onBackPressed());
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbarVisibility);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.mikuAllContainer, MikuAllFragment.newInstance(sourceId, sourceLabel), "miku_all_grid").commit();
        }
        updateToolbarVisibility();
    }

    public void openMangaDetail(MangaPost manga) {
        if (manga == null || !lockMangaNavigation()) return;
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, createMangaDetailFragment(manga), "manga_detail")
                .addToBackStack("manga_detail")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    private Fragment createMangaDetailFragment(MangaPost manga) {
        if (MangaSettingsManager.isDetailUiV2(this)) return MangaDetailV2Fragment.newInstance(manga);
        if (MangaSettingsManager.isDetailUiV1(this)) return MangaDetailV1Fragment.newInstance(manga);
        return MangaDetailFragment.newInstance(manga);
    }
    public void openMangaBrowseSource(String sourceId, String sourceLabel, String query) {
        if (!lockMangaNavigation()) return;
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, BrowseSourceScreen.newSource(sourceId, sourceLabel, query), "browse_source_screen")
                .addToBackStack("browse_source_screen")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    public void openMangaGenreResult(String sourceId, String sourceLabel, String genreTitle, String genreValue) {
        if (!lockMangaNavigation()) return;
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, BrowseSourceScreen.newGenre(sourceId, sourceLabel, genreTitle, genreValue), "browse_source_genre")
                .addToBackStack("browse_source_genre")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    public void openMangaReader(MangaPost manga, ArrayList<MangaChapter> chapters, int chapterPos) {
        if (manga == null || !lockMangaNavigation()) return;
        try { MangaImageLoader.clearMemoryCache(this); } catch (Exception ignored) { }
        try { MangaMemoryCache.trimRegistered(); } catch (Exception ignored) { }
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, createMangaReaderFragment(manga, chapters, chapterPos), "manga_reader")
                .addToBackStack("manga_reader")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    private boolean lockMangaNavigation() {
        if (mangaNavigationLocked) return false;
        mangaNavigationLocked = true;
        mangaNavigationHandler.removeCallbacksAndMessages(null);
        mangaNavigationHandler.postDelayed(() -> mangaNavigationLocked = false, 520);
        return true;
    }

    private Fragment getCurrentContentFragment() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment != null && fragment.isAdded() && !fragment.isHidden() && fragment.getId() == R.id.mikuAllContainer) return fragment;
        }
        return getSupportFragmentManager().findFragmentById(R.id.mikuAllContainer);
    }

    private Fragment createMangaReaderFragment(MangaPost manga, ArrayList<MangaChapter> chapters, int chapterPos) {
        String ui = MangaSettingsManager.getReaderUi(this);
        if (MangaSettingsManager.READER_UI_V2.equals(ui)) return MangaReaderFragmentV2.newInstance(manga, chapters, chapterPos);
        if (MangaSettingsManager.READER_UI_V1.equals(ui)) return MangaReaderFragmentV1.newInstance(manga, chapters, chapterPos);
        return MangaReaderFragment.newInstance(manga, chapters, chapterPos);
    }

    private void updateToolbarVisibility() {
        View toolbar = findViewById(R.id.mikuAllToolbar);
        if (toolbar == null) return;
        boolean showToolbar = getSupportFragmentManager().getBackStackEntryCount() == 0;
        toolbar.setVisibility(showToolbar ? View.VISIBLE : View.GONE);
    }

    @Override protected void onDestroy() {
        mangaNavigationHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        Fragment current = getCurrentContentFragment();
        if (!(current instanceof MangaReaderFragment) && !(current instanceof MangaReaderFragmentV1) && !(current instanceof MangaReaderFragmentV2)) ThemeManager.applySystemBars(this);
    }

    private void setupSystemBars() {
        ThemeManager.applySystemBars(this);
    }
}
