package miku.moe.app;

import android.os.Bundle;

public class MikuAnimeLoverzSearch extends BaseAnimeLoverzGridFragment {
    private static final String ARG_QUERY = "query";

    public static MikuAnimeLoverzSearch newInstance(String query) {
        MikuAnimeLoverzSearch fragment = new MikuAnimeLoverzSearch();
        Bundle args = new Bundle();
        args.putString(ARG_QUERY, query == null ? "" : query);
        fragment.setArguments(args);
        return fragment;
    }

    @Override protected String screenTitle() { return "Animeloverz"; }
    @Override protected String initialQuery() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString(ARG_QUERY, "");
    }
}
