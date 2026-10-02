package miku.moe.app;

public class HomeFragment extends BaseAnimeGridFragment {
    @Override protected boolean isSearchPage() { return false; }
    @Override protected String screenTitle() { return "Anime Terbaru"; }
}
