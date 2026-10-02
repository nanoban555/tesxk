package miku.moe.app;

import android.os.Bundle;

public class MikuAllFragment extends BaseMangaGridFragment {
    private static final String ARG_SOURCE_ID = "source_id";
    private static final String ARG_SOURCE_LABEL = "source_label";

    public static MikuAllFragment newInstance(String sourceId, String sourceLabel) {
        MikuAllFragment fragment = new MikuAllFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SOURCE_ID, sourceId);
        args.putString(ARG_SOURCE_LABEL, sourceLabel);
        fragment.setArguments(args);
        return fragment;
    }

    @Override protected String title() {
        Bundle args = getArguments();
        String label = args == null ? "Manga" : args.getString(ARG_SOURCE_LABEL, "Manga");
        return label == null || label.trim().isEmpty() ? "Manga" : label;
    }

    @Override protected String forcedSourceId() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString(ARG_SOURCE_ID, "");
    }

    @Override protected boolean showHomeSourceTabs() { return false; }
    @Override protected boolean showTitleHeader() { return false; }

    @Override protected void onPostClick(MangaPost post) {
        if (isAdded() && getActivity() instanceof MikuAll) ((MikuAll) getActivity()).openMangaDetail(post);
    }
}
