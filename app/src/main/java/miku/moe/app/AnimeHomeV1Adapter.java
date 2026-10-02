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

import com.bumptech.glide.Glide;

import java.util.ArrayList;

public class AnimeHomeV1Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onAnimeClick(AnimePost post);
        void onExplore(String kind);
        void onBrowseSource();
        void onChangeSource();
        void onPopularNearEnd();
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
    private final ArrayList<AnimePost> popular;
    private final ArrayList<AnimePost> latest;
    private final Listener listener;
    private final AnimeStyleV1CardAdapter latestBinder;
    private HistoryItem continueEntry;
    private boolean loadingInitial;
    private boolean loadingPopularMore;
    private boolean loadingMore;
    private String errorMessage = "";

    public AnimeHomeV1Adapter(Context context, String sourceLabel, ArrayList<AnimePost> popular, ArrayList<AnimePost> latest, Listener listener) {
        this.context = context;
        this.sourceLabel = sourceLabel;
        this.popular = popular;
        this.latest = latest;
        this.listener = listener;
        this.latestBinder = new AnimeStyleV1CardAdapter(context, latest, AnimeStyleV1CardAdapter.MODE_GRID, post -> {
            if (listener != null) listener.onAnimeClick(post);
        });
        setHasStableIds(true);
    }

    public void updateState(HistoryItem continueEntry, boolean loadingInitial, boolean loadingPopularMore, boolean loadingMore, String errorMessage) {
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

    @Override public long getItemId(int position) {
        if (getItemViewType(position) == TYPE_LATEST_ITEM) {
            AnimePost post = latest.get(position - latestStartPosition());
            return 0x200000000L + itemKey(post).hashCode();
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
        else if (type == TYPE_POPULAR_HEADER) bindSection((SectionHolder) holder, "PILIHAN PENONTON", "popular");
        else if (type == TYPE_POPULAR_RAIL) bindRail((RailHolder) holder);
        else if (type == TYPE_LATEST_HEADER) bindSection((SectionHolder) holder, "UPDATE ANIME", "latest");
        else if (type == TYPE_LATEST_ITEM) latestBinder.onBindViewHolder((AnimeStyleV1CardAdapter.Holder) holder, position - latestStartPosition());
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
            holder.adapter = new AnimeHomeV1HeroAdapter(context, latest, post -> {
                if (listener != null) listener.onAnimeClick(post);
            });
            holder.recycler.setAdapter(holder.adapter);
            new PagerSnapHelper().attachToRecyclerView(holder.recycler);
        } else {
            holder.adapter.notifyDataSetChanged();
        }
        holder.progress.setVisibility(loadingInitial && latest.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void bindContinue(ContinueHolder holder) {
        if (continueEntry == null) return;
        String animeTitle = clean(continueEntry.categoryName);
        if (animeTitle.isEmpty()) animeTitle = clean(continueEntry.title);
        holder.action.setText("LANJUTKAN MENONTON");
        holder.title.setText(animeTitle);
        String episode = AnimeEpisodeLabelUtils.historyLabel(continueEntry.title);
        if (episode.isEmpty()) episode = progressText(continueEntry.position, continueEntry.duration);
        holder.chapter.setText(episode.isEmpty() ? "Lanjutkan episode terakhir" : "Terakhir di " + episode);
        String cover = clean(continueEntry.imageUrl);
        Glide.with(context.getApplicationContext()).clear(holder.image);
        if (!cover.isEmpty()) Glide.with(context.getApplicationContext()).load(cover).centerCrop().into(holder.image);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onAnimeClick(toAnimePost(continueEntry));
        });
    }

    private AnimePost toAnimePost(HistoryItem item) {
        String title = clean(item.categoryName);
        if (title.isEmpty()) title = clean(item.title);
        AnimePost post = new AnimePost(clean(item.imageUrl), title, item.categoryId, item.channelId);
        post.sourceId = AnimeSettingsManager.isValidSource(item.sourceId) ? item.sourceId : AnimeSettingsManager.SOURCE_DEFAULT;
        post.slug = clean(item.slug);
        String episode = AnimeEpisodeLabelUtils.normalize(item.title);
        post.channelName = episode;
        post.episodeCount = episode;
        return post;
    }

    private String progressText(long position, long duration) {
        if (position <= 0 || duration <= 0) return "";
        long percent = Math.min(100L, Math.max(1L, position * 100L / duration));
        return percent + "% ditonton";
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
            holder.adapter = new AnimeStyleV1CardAdapter(context, popular, AnimeStyleV1CardAdapter.MODE_HORIZONTAL, post -> {
                if (listener != null) listener.onAnimeClick(post);
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
        return continueEntry != null;
    }

    private int latestStartPosition() {
        return hasContinue() ? 6 : 5;
    }

    private String itemKey(AnimePost post) {
        if (post == null) return "";
        String source = clean(post.sourceId);
        String slug = clean(post.slug);
        if (!slug.isEmpty()) return source + ":slug:" + slug;
        if (post.categoryId > 0) return source + ":category:" + post.categoryId + ":" + post.channelId;
        return source + ":title:" + clean(post.categoryName);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    @Override public int getItemCount() {
        return latestStartPosition() + latest.size() + 1;
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof AnimeStyleV1CardAdapter.Holder) latestBinder.onViewRecycled((AnimeStyleV1CardAdapter.Holder) holder);
        if (holder instanceof ContinueHolder) Glide.with(context.getApplicationContext()).clear(((ContinueHolder) holder).image);
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
        AnimeHomeV1HeroAdapter adapter;

        HeroHolder(@NonNull View itemView) {
            super(itemView);
            recycler = itemView.findViewById(R.id.heroRecyclerView);
            progress = itemView.findViewById(R.id.heroProgressBar);
        }
    }

    static class ContinueHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView action;
        final TextView title;
        final TextView chapter;

        ContinueHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.continueImageView);
            action = itemView.findViewById(R.id.continueActionTextView);
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
        AnimeStyleV1CardAdapter adapter;

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
