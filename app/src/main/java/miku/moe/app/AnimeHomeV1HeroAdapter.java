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

public class AnimeHomeV1HeroAdapter extends RecyclerView.Adapter<AnimeHomeV1HeroAdapter.Holder> {
    public interface Listener {
        void onClick(AnimePost post);
    }

    private final Context context;
    private final ArrayList<AnimePost> data;
    private final Listener listener;

    public AnimeHomeV1HeroAdapter(Context context, ArrayList<AnimePost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) {
        return itemKey(data.get(position)).hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_home_v1_hero_item, parent, false);
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(420), Math.max(dp(280), screenWidth - dp(48)));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, dp(218));
        params.setMargins(dp(5), 0, dp(5), 0);
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        AnimePost post = data.get(position);
        holder.title.setText(clean(post.categoryName));
        holder.genre.setText(formatGenre(post.genre));
        bindEpisode(holder.episode, post);
        bindCover(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void bindEpisode(TextView view, AnimePost post) {
        String episode = AnimeSettingsManager.shouldShowLatestEpisodeLabel(context) ? AnimeEpisodeLabelUtils.latestLabel(post) : "";
        view.setOnClickListener(null);
        if (episode.isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(episode);
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
    }

    private String formatGenre(String value) {
        String cleanValue = clean(value);
        if (cleanValue.isEmpty()) return "Anime terbaru";
        String[] parts = cleanValue.split(",");
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            String cleanPart = clean(part);
            if (cleanPart.isEmpty()) continue;
            if (result.length() > 0) result.append(" · ");
            result.append(cleanPart);
            count++;
            if (count >= 3) break;
        }
        return result.length() == 0 ? "Anime terbaru" : result.toString();
    }

    private void bindCover(Holder holder, AnimePost post) {
        String url = clean(post.imgUrl);
        holder.image.setBackgroundResource(R.drawable.hero_placeholder);
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
        return Math.min(data.size(), 8);
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.episode.setOnClickListener(null);
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
        final TextView genre;
        final TextView episode;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.heroImageView);
            progress = itemView.findViewById(R.id.heroImageProgress);
            title = itemView.findViewById(R.id.heroTitleTextView);
            genre = itemView.findViewById(R.id.heroGenreTextView);
            episode = itemView.findViewById(R.id.heroChapterTextView);
        }
    }
}
