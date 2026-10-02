package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;

public class AnimeStyleV1CardAdapter extends RecyclerView.Adapter<AnimeStyleV1CardAdapter.Holder> {
    public interface Listener {
        void onClick(AnimePost post);
    }

    public static final int MODE_GRID = 0;
    public static final int MODE_HORIZONTAL = 1;
    public static final int MODE_FAVORITE = 2;
    private final Context context;
    private final ArrayList<AnimePost> data;
    private final int mode;
    private final Listener listener;

    public AnimeStyleV1CardAdapter(Context context, ArrayList<AnimePost> data, int mode, Listener listener) {
        this.context = context;
        this.data = data;
        this.mode = mode;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) {
        return itemKey(data.get(position)).hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_style_v1_card_item, parent, false);
        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (mode == MODE_HORIZONTAL) {
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            width = Math.max(dp(104), screenWidth / 3 - dp(10));
        }
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (mode == MODE_HORIZONTAL) params.setMargins(dp(5), 0, dp(5), 0);
        else if (mode == MODE_FAVORITE) params.setMargins(dp(5), 0, dp(5), dp(8));
        else params.setMargins(dp(5), 0, dp(5), dp(8));
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        AnimePost post = data.get(position);
        holder.title.setText(clean(post.categoryName));
        holder.title.getPaint().setFakeBoldText(false);
        bindEpisode(holder.episode, post);
        bindCover(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void bindEpisode(TextView view, AnimePost post) {
        String episode = AnimeSettingsManager.shouldShowLatestEpisodeLabel(context) ? AnimeEpisodeLabelUtils.latestLabel(post) : "";
        if (episode.isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(episode);
        view.setVisibility(View.VISIBLE);
    }

    private void bindCover(Holder holder, AnimePost post) {
        String url = clean(post.imgUrl);
        holder.image.setBackgroundResource(R.drawable.cover_placeholder);
        Glide.with(context.getApplicationContext()).clear(holder.image);
        holder.progress.setVisibility(View.GONE);
        if (!url.isEmpty()) Glide.with(context.getApplicationContext()).load(url).centerCrop().into(holder.image);
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
        return data.size();
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.itemView.setOnClickListener(null);
        holder.progress.setVisibility(View.GONE);
        holder.image.animate().cancel();
        Glide.with(context.getApplicationContext()).clear(holder.image);
        MangaItemWaveAnimator.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final ProgressBar progress;
        final TextView title;
        final TextView episode;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.styleV1ImageView);
            progress = itemView.findViewById(R.id.styleV1ImageProgress);
            title = itemView.findViewById(R.id.styleV1TitleTextView);
            episode = itemView.findViewById(R.id.styleV1ChapterTextView);
        }
    }
}
