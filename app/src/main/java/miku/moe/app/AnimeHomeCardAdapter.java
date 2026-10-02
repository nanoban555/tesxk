package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;

public class AnimeHomeCardAdapter extends RecyclerView.Adapter<AnimeHomeCardAdapter.Holder> {
    public interface Listener { void onClick(AnimePost post); }
    private final Context context;
    private final ArrayList<AnimePost> data;
    private final Listener listener;

    public AnimeHomeCardAdapter(Context context, ArrayList<AnimePost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) {
        AnimePost post = data.get(position);
        String key = post == null ? "" : post.sourceId + ":" + post.categoryId + ":" + post.channelId + ":" + post.categoryName;
        return key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.anime_home_card_item, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        AnimePost post = data.get(position);
        holder.title.setText(clean(post.categoryName));
        holder.episode.setVisibility(View.GONE);
        holder.episode.setText("");
        Glide.with(context.getApplicationContext()).load(post.imgUrl).centerCrop().into(holder.image);
        holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
    }

    @Override public int getItemCount() { return data.size(); }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    static class Holder extends RecyclerView.ViewHolder {
        ShapeableImageView image;
        TextView title;
        TextView episode;
        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imageView);
            title = itemView.findViewById(R.id.titleTextView);
            episode = itemView.findViewById(R.id.episodeTextView);
        }
    }
}
