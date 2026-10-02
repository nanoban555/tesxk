package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;

public class MangaHomeV1Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onMangaClick(MangaPost post);
        void onExplore(String kind);
        void onBrowseSource();
        void onChangeSource();
        void onPopularNearEnd();
        void onNeedChapter(MangaPost post);
        void onChapterClick(MangaPost post);
    }

    private static final int TYPE_HEADER = 1;
    private static final int TYPE_HERO = 2;
    private static final int TYPE_CONTINUE = 3;
    private static final int TYPE_POPULAR_HEADER = 4;
    private static final int TYPE_POPULAR_RAIL = 5;
    private static final int TYPE_LATEST_HEADER = 6;
    private static final int TYPE_LATEST_ITEM = 7;
    private static final int TYPE_LOADING = 8;
    private final Context context;
    private final String sourceLabel;
    private final ArrayList<MangaPost> popular;
    private final ArrayList<MangaPost> latest;
    private final Listener listener;
    private final MangaStyleV1CardAdapter latestBinder;
    private MangaHistoryManager.Entry continueEntry;
    private boolean loadingInitial;
    private boolean loadingPopularMore;
    private boolean loadingMore;
    private String errorMessage = "";

    public MangaHomeV1Adapter(Context context, String sourceLabel, ArrayList<MangaPost> popular, ArrayList<MangaPost> latest, Listener listener) {
        this.context = context;
        this.sourceLabel = sourceLabel;
        this.popular = popular;
        this.latest = latest;
        this.listener = listener;
        this.latestBinder = new MangaStyleV1CardAdapter(context, latest, MangaStyleV1CardAdapter.MODE_GRID, post -> {
            if (listener != null) listener.onMangaClick(post);
        }, post -> {
            if (listener != null) listener.onNeedChapter(post);
        }, post -> {
            if (listener != null) listener.onChapterClick(post);
        });
        setHasStableIds(true);
    }

    public void updateState(MangaHistoryManager.Entry continueEntry, boolean loadingInitial, boolean loadingPopularMore, boolean loadingMore, String errorMessage) {
        this.continueEntry = continueEntry;
        this.loadingInitial = loadingInitial;
        this.loadingPopularMore = loadingPopularMore;
        this.loadingMore = loadingMore;
        this.errorMessage = errorMessage == null ? "" : errorMessage.trim();
        notifyDataSetChanged();
    }

    public int getSpanSize(int position) {
        return getItemViewType(position) == TYPE_LATEST_ITEM ? 1 : 3;
    }

    public void notifyChapterChanged(MangaPost post) {
        if (getItemCount() > 1) notifyItemChanged(1);
        int railPosition = hasContinue() ? 4 : 3;
        if (railPosition < getItemCount()) notifyItemChanged(railPosition);
        String key = itemKey(post);
        for (int i = 0; i < latest.size(); i++) {
            if (key.equals(itemKey(latest.get(i)))) notifyItemChanged(latestStartPosition() + i);
        }
    }

    private String itemKey(MangaPost post) {
        if (post == null) return "";
        String value = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
        return post.getSourceId() + "|" + (value == null ? "" : value.trim());
    }

    @Override public long getItemId(int position) {
        if (getItemViewType(position) == TYPE_LATEST_ITEM) {
            MangaPost post = latest.get(position - latestStartPosition());
            String value = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
            return 0x100000000L + (post.getSourceId() + ":" + value).hashCode();
        }
        return Long.MIN_VALUE + getItemViewType(position);
    }

    @Override public int getItemViewType(int position) {
        if (position == 0) return TYPE_HEADER;
        if (position == 1) return TYPE_HERO;
        int cursor = 2;
        if (hasContinue()) {
            if (position == cursor) return TYPE_CONTINUE;
            cursor++;
        }
        if (position == cursor++) return TYPE_POPULAR_HEADER;
        if (position == cursor++) return TYPE_POPULAR_RAIL;
        if (position == cursor++) return TYPE_LATEST_HEADER;
        if (position < cursor + latest.size()) return TYPE_LATEST_ITEM;
        return TYPE_LOADING;
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == TYPE_HEADER) return new HeaderHolder(inflater.inflate(R.layout.manga_home_v1_header, parent, false));
        if (viewType == TYPE_HERO) return new HeroHolder(inflater.inflate(R.layout.manga_home_v1_hero_section, parent, false));
        if (viewType == TYPE_CONTINUE) return new ContinueHolder(inflater.inflate(R.layout.manga_home_v1_continue, parent, false));
        if (viewType == TYPE_POPULAR_HEADER || viewType == TYPE_LATEST_HEADER) return new SectionHolder(inflater.inflate(R.layout.manga_home_v1_section_header, parent, false));
        if (viewType == TYPE_POPULAR_RAIL) return new RailHolder(inflater.inflate(R.layout.manga_home_v1_rail, parent, false));
        if (viewType == TYPE_LATEST_ITEM) return latestBinder.onCreateViewHolder(parent, viewType);
        return new LoadingHolder(inflater.inflate(R.layout.manga_home_v1_loading, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == TYPE_HEADER) bindHeader((HeaderHolder) holder);
        else if (type == TYPE_HERO) bindHero((HeroHolder) holder);
        else if (type == TYPE_CONTINUE) bindContinue((ContinueHolder) holder);
        else if (type == TYPE_POPULAR_HEADER) bindSection((SectionHolder) holder, "PILIHAN PEMBACA", "popular");
        else if (type == TYPE_POPULAR_RAIL) bindRail((RailHolder) holder);
        else if (type == TYPE_LATEST_HEADER) bindSection((SectionHolder) holder, "UPDATE HARIAN", "latest");
        else if (type == TYPE_LATEST_ITEM) latestBinder.onBindViewHolder((MangaStyleV1CardAdapter.Holder) holder, position - latestStartPosition());
        else ((LoadingHolder) holder).progress.setVisibility(loadingMore ? View.VISIBLE : View.GONE);
    }

    private void bindHeader(HeaderHolder holder) {
        holder.sourceLabel.setText(sourceLabel);
        holder.sourceCard.setOnClickListener(v -> {
            if (listener != null) listener.onBrowseSource();
        });
        holder.sourceSwitch.setOnClickListener(v -> {
            if (listener != null) listener.onChangeSource();
        });
        if (errorMessage.isEmpty()) {
            holder.status.setText("");
            holder.status.setVisibility(View.GONE);
        } else {
            holder.status.setText(errorMessage);
            holder.status.setVisibility(View.VISIBLE);
        }
    }

    private void bindHero(HeroHolder holder) {
        if (holder.adapter == null) {
            LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            holder.recycler.setLayoutManager(manager);
            holder.recycler.setItemAnimator(null);
            holder.recycler.setItemViewCacheSize(3);
            holder.adapter = new MangaHomeV1HeroAdapter(context, latest, new MangaHomeV1HeroAdapter.Listener() {
                @Override public void onClick(MangaPost post) {
                    if (listener != null) listener.onMangaClick(post);
                }

                @Override public void onNeedChapter(MangaPost post) {
                    if (listener != null) listener.onNeedChapter(post);
                }

                @Override public void onChapterClick(MangaPost post) {
                    if (listener != null) listener.onChapterClick(post);
                }
            });
            holder.recycler.setAdapter(holder.adapter);
            new PagerSnapHelper().attachToRecyclerView(holder.recycler);
        } else {
            holder.adapter.notifyDataSetChanged();
        }
        holder.progress.setVisibility(loadingInitial && latest.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void bindContinue(ContinueHolder holder) {
        if (continueEntry == null || continueEntry.manga == null) return;
        MangaPost manga = continueEntry.manga;
        holder.title.setText(manga.title == null ? "" : manga.title);
        MangaTitleStyle.apply(holder.title, context);
        String chapter = continueEntry.chapterTitle == null ? "" : continueEntry.chapterTitle.trim();
        if (chapter.isEmpty()) chapter = "Chapter " + formatChapter(continueEntry.chapterIndex);
        holder.chapter.setText("Terakhir di " + chapter);
        String cover = manga.coverImage == null ? "" : manga.coverImage.trim();
        if (cover.isEmpty()) MangaImageLoader.clear(holder.image);
        else MangaImageLoader.loadForSource(holder.image, cover, manga.getSourceId(), true, null);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMangaClick(manga);
        });
    }

    private void bindSection(SectionHolder holder, String eyebrow, String kind) {
        holder.eyebrow.setText(eyebrow);
        holder.icon.setImageResource("popular".equals(kind) ? R.drawable.ic_local_fire : R.drawable.ic_new_releases);
        holder.action.setOnClickListener(v -> {
            if (listener != null) listener.onExplore(kind);
        });
    }

    private void bindRail(RailHolder holder) {
        if (holder.adapter == null) {
            LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            holder.recycler.setLayoutManager(manager);
            holder.recycler.setItemAnimator(null);
            holder.recycler.setItemViewCacheSize(4);
            holder.adapter = new MangaStyleV1CardAdapter(context, popular, MangaStyleV1CardAdapter.MODE_HORIZONTAL, post -> {
                if (listener != null) listener.onMangaClick(post);
            }, post -> {
                if (listener != null) listener.onNeedChapter(post);
            }, post -> {
                if (listener != null) listener.onChapterClick(post);
            });
            holder.recycler.setAdapter(holder.adapter);
            holder.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (dx <= 0) return;
                    int last = manager.findLastVisibleItemPosition();
                    if (last >= Math.max(0, popular.size() - 3) && listener != null) listener.onPopularNearEnd();
                }
            });
        } else {
            holder.adapter.notifyDataSetChanged();
        }
        holder.progress.setVisibility((loadingInitial || loadingPopularMore) && popular.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean hasContinue() {
        return continueEntry != null && continueEntry.manga != null;
    }

    private int latestStartPosition() {
        return hasContinue() ? 6 : 5;
    }

    private String formatChapter(float value) {
        if (value < 0f) return "-";
        if (value == (int) value) return String.valueOf((int) value);
        String text = String.valueOf(value);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    @Override public int getItemCount() {
        return latestStartPosition() + latest.size() + 1;
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof MangaStyleV1CardAdapter.Holder) latestBinder.onViewRecycled((MangaStyleV1CardAdapter.Holder) holder);
        super.onViewRecycled(holder);
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final View sourceCard;
        final View sourceSwitch;
        final TextView sourceLabel;
        final TextView status;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            sourceCard = itemView.findViewById(R.id.sourceCard);
            sourceSwitch = itemView.findViewById(R.id.homeSourceSwitchButton);
            sourceLabel = itemView.findViewById(R.id.sourceLabelTextView);
            status = itemView.findViewById(R.id.homeStatusTextView);
        }
    }

    static class HeroHolder extends RecyclerView.ViewHolder {
        final RecyclerView recycler;
        final ProgressBar progress;
        MangaHomeV1HeroAdapter adapter;

        HeroHolder(@NonNull View itemView) {
            super(itemView);
            recycler = itemView.findViewById(R.id.heroRecyclerView);
            progress = itemView.findViewById(R.id.heroProgressBar);
        }
    }

    static class ContinueHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView chapter;

        ContinueHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.continueImageView);
            title = itemView.findViewById(R.id.continueTitleTextView);
            chapter = itemView.findViewById(R.id.continueChapterTextView);
        }
    }

    static class SectionHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView eyebrow;
        final TextView action;

        SectionHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.sectionIconImageView);
            eyebrow = itemView.findViewById(R.id.sectionEyebrowTextView);
            action = itemView.findViewById(R.id.sectionActionTextView);
        }
    }

    static class RailHolder extends RecyclerView.ViewHolder {
        final RecyclerView recycler;
        final ProgressBar progress;
        MangaStyleV1CardAdapter adapter;

        RailHolder(@NonNull View itemView) {
            super(itemView);
            recycler = itemView.findViewById(R.id.popularRecyclerView);
            progress = itemView.findViewById(R.id.popularProgressBar);
        }
    }

    static class LoadingHolder extends RecyclerView.ViewHolder {
        final ProgressBar progress;

        LoadingHolder(@NonNull View itemView) {
            super(itemView);
            progress = itemView.findViewById(R.id.paginationProgressBar);
        }
    }
}
