package miku.moe.app;

import android.os.Bundle;

public class MikuAnimekuSearch extends BaseAnimekuGridFragment {
    private static final String ARG_QUERY = "query";

    public static MikuAnimekuSearch newInstance(String query) {
        MikuAnimekuSearch fragment = new MikuAnimekuSearch();
        Bundle args = new Bundle();
        args.putString(ARG_QUERY, query == null ? "" : query);
        fragment.setArguments(args);
        return fragment;
    }

    @Override protected boolean isSearchPage() { return false; }
    @Override protected String screenTitle() { return "Animeku"; }
    @Override protected String initialQuery() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString(ARG_QUERY, "");
    }
}
