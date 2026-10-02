package miku.moe.app;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;

public class MangaFavoriteTabsFragment extends Fragment {
    private static final String KEY_SELECTED_TAB = "selected_tab";
    private static final String TAG_FAVORITE = "manga_tab_favorite";
    private static final String TAG_HISTORY = "manga_tab_history";
    private static final int TAB_FAVORITE = 0;
    private static final int TAB_HISTORY = 1;

    private TabLayout tabLayout;
    private LinearLayout favoriteActionContainer;
    private LinearLayout historyActionContainer;
    private MaterialButton clearHistoryButton;
    private TextView collectionTitleTextView;
    private int selectedTab = TAB_FAVORITE;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) selectedTab = savedInstanceState.getInt(KEY_SELECTED_TAB, TAB_FAVORITE);
        return inflater.inflate(R.layout.fragment_manga_favorite_tabs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tabLayout = view.findViewById(R.id.mangaFavoriteTabLayout);
        favoriteActionContainer = view.findViewById(R.id.favoriteActionContainer);
        historyActionContainer = view.findViewById(R.id.historyActionContainer);
        clearHistoryButton = view.findViewById(R.id.clearHistoryButton);
        collectionTitleTextView = view.findViewById(R.id.collectionTitleTextView);
        setupHeaderActions(view);
        setupTabs();
        showSelectedTab(selectedTab);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTab);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateVisibleActions();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            updateVisibleActions();
        }
    }

    @Override
    public void onDestroyView() {
        tabLayout = null;
        favoriteActionContainer = null;
        historyActionContainer = null;
        clearHistoryButton = null;
        collectionTitleTextView = null;
        super.onDestroyView();
    }

    public void refreshFavorites() {
        Fragment favorite = getChildFragmentManager().findFragmentByTag(TAG_FAVORITE);
        if (favorite instanceof MangaFavoriteFragment) ((MangaFavoriteFragment) favorite).refreshFavorites();
    }

    public void reload() {
        Fragment favorite = getChildFragmentManager().findFragmentByTag(TAG_FAVORITE);
        Fragment history = getChildFragmentManager().findFragmentByTag(TAG_HISTORY);
        if (favorite instanceof MangaFavoriteFragment) ((MangaFavoriteFragment) favorite).refreshFavorites();
        if (history instanceof MangaHistoryFragment) ((MangaHistoryFragment) history).reload();
    }

    public void updateHistoryActionState(boolean empty, int selectedCount) {
        if (clearHistoryButton == null) return;
        clearHistoryButton.setVisibility(empty ? View.GONE : View.VISIBLE);
        clearHistoryButton.setText("");
        clearHistoryButton.setIconResource(R.drawable.ic_delete);
        clearHistoryButton.setIconPadding(0);
        int tintColor = selectedCount == 0 ? MaterialColors.getColor(clearHistoryButton, androidx.appcompat.R.attr.colorPrimary) : 0xFFD32F2F;
        clearHistoryButton.setIconTint(ColorStateList.valueOf(tintColor));
        clearHistoryButton.setContentDescription(selectedCount == 0 ? "Hapus history" : "Hapus " + selectedCount + " history");
    }

    private void setupHeaderActions(View view) {
        View updateFavoriteButton = view.findViewById(R.id.updateFavoriteButton);
        View importFavoriteButton = view.findViewById(R.id.importFavoriteButton);
        View exportFavoriteButton = view.findViewById(R.id.exportFavoriteButton);
        View refreshFavoriteButton = view.findViewById(R.id.refreshFavoriteButton);
        View refreshHistoryButton = view.findViewById(R.id.refreshHistoryButton);

        if (updateFavoriteButton != null) updateFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::openUpdateFromHeader));
        if (importFavoriteButton != null) importFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::importFavoriteFromHeader));
        if (exportFavoriteButton != null) exportFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::exportFavoriteFromHeader));
        if (refreshFavoriteButton != null) refreshFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::refreshFavoriteFromHeader));
        if (refreshHistoryButton != null) refreshHistoryButton.setOnClickListener(v -> withHistoryFragment(MangaHistoryFragment::refreshHistoryFromHeader));
        if (clearHistoryButton != null) clearHistoryButton.setOnClickListener(v -> withHistoryFragment(MangaHistoryFragment::clearHistoryFromHeader));
    }

    private void setupTabs() {
        if (tabLayout == null) return;
        tabLayout.clearOnTabSelectedListeners();
        if (tabLayout.getTabCount() == 0) {
            tabLayout.addTab(tabLayout.newTab().setText("Favorite"));
            tabLayout.addTab(tabLayout.newTab().setText("History"));
        }
        TabLayout.Tab tab = tabLayout.getTabAt(selectedTab);
        if (tab != null) tab.select();
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectedTab = tab.getPosition() == TAB_HISTORY ? TAB_HISTORY : TAB_FAVORITE;
                showSelectedTab(selectedTab);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                reload();
            }
        });
    }

    private void showSelectedTab(int tabIndex) {
        if (!isAdded()) return;
        updateVisibleActions();
        FragmentManager fragmentManager = getChildFragmentManager();
        Fragment favorite = fragmentManager.findFragmentByTag(TAG_FAVORITE);
        Fragment history = fragmentManager.findFragmentByTag(TAG_HISTORY);
        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);

        if (favorite == null) {
            favorite = new MangaFavoriteFragment();
            transaction.add(R.id.mangaFavoriteTabContainer, favorite, TAG_FAVORITE);
        }
        if (history == null) {
            history = new MangaHistoryFragment();
            transaction.add(R.id.mangaFavoriteTabContainer, history, TAG_HISTORY).hide(history);
        }

        if (tabIndex == TAB_HISTORY) {
            transaction.hide(favorite).show(history);
        } else {
            transaction.hide(history).show(favorite);
        }
        transaction.commitAllowingStateLoss();
        updateVisibleActions();
    }

    private void updateVisibleActions() {
        if (favoriteActionContainer != null) {
            favoriteActionContainer.setVisibility(selectedTab == TAB_FAVORITE ? View.VISIBLE : View.GONE);
        }
        if (historyActionContainer != null) {
            historyActionContainer.setVisibility(selectedTab == TAB_HISTORY ? View.VISIBLE : View.GONE);
        }
        if (collectionTitleTextView != null) {
            collectionTitleTextView.setText(selectedTab == TAB_HISTORY ? "Riwayat Manga" : "Favorit Manga");
        }
    }

    private interface FragmentAction<T extends Fragment> {
        void run(T fragment);
    }

    private void withFavoriteFragment(FragmentAction<MangaFavoriteFragment> action) {
        Fragment f = getChildFragmentManager().findFragmentByTag(TAG_FAVORITE);
        if (f instanceof MangaFavoriteFragment && f.isAdded()) {
            action.run((MangaFavoriteFragment) f);
        }
    }

    private void withHistoryFragment(FragmentAction<MangaHistoryFragment> action) {
        Fragment f = getChildFragmentManager().findFragmentByTag(TAG_HISTORY);
        if (f instanceof MangaHistoryFragment && f.isAdded()) {
            action.run((MangaHistoryFragment) f);
        }
    }
}
