package miku.moe.app;

import android.os.Bundle;

public class MikuAnimeXSearch extends BaseAnimeGridFragment {
    private static final String ARG_QUERY = "query";

    public static MikuAnimeXSearch newInstance(String query) {
        MikuAnimeXSearch fragment = new MikuAnimeXSearch();
        Bundle args = new Bundle();
        args.putString(ARG_QUERY, query == null ? "" : query);
        fragment.setArguments(args);
        return fragment;
    }

    @Override protected boolean isSearchPage() { return false; }
    @Override protected String screenTitle() { return "Anime X Nonton"; }
    @Override protected String initialQuery() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString(ARG_QUERY, "");
    }
}
