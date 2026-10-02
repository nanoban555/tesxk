package miku.moe.app;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;

public class RelatedMangaAdapter extends RecyclerView.Adapter<RelatedMangaAdapter.Holder> {
    public interface Listener { void onClick(MangaPost post); }
    private final Context context;
    private final ArrayList<MangaPost> data;
    private final Listener listener;

    public RelatedMangaAdapter(Context context, ArrayList<MangaPost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) {
        MangaPost post = data.get(position);
        String key = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
        return key == null ? position : key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.related_manga_item, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MangaPost post = data.get(position);
        String itemKey = post.getSourceId() + ":" + (post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug);
        String coverKey = post.getSourceId() + "|" + (post.coverImage == null ? "" : post.coverImage);
        holder.boundAnimationKey = itemKey;
        String title = post.title == null ? "" : post.title;
        boolean compactDataUi = MangaSettingsManager.isCompactMangaDataUiEnabled(context);
        holder.title.setText(title);
        MangaTitleStyle.apply(holder.title, context);
        holder.title.setVisibility(compactDataUi ? View.GONE : View.VISIBLE);
        if (holder.coverTitle != null) {
            holder.coverTitle.setText(title);
            MangaTitleStyle.apply(holder.coverTitle, context);
            holder.coverTitle.setVisibility(compactDataUi ? View.VISIBLE : View.GONE);
        }
        if (holder.imageShadow != null) holder.imageShadow.setVisibility(compactDataUi ? View.VISIBLE : View.GONE);
        bindTypeFlag(holder.typeFlag, post);
        if (!coverKey.equals(holder.boundCoverKey) || holder.image.getDrawable() == null) {
            holder.boundCoverKey = coverKey;
            holder.image.animate().cancel();
            holder.image.setAlpha(1f);
            MangaImageLoader.loadForSource(holder.image, post.coverImage, post.getSourceId(), true, null);
            if (holder.blurImage != null) {
                holder.blurImage.animate().cancel();
                holder.blurImage.setAlpha(0.58f);
                MangaImageLoader.loadForSource(holder.blurImage, post.coverImage, post.getSourceId(), true, null);
            }
        } else {
            holder.image.animate().cancel();
            holder.image.setAlpha(1f);
            if (holder.blurImage != null) {
                holder.blurImage.animate().cancel();
                holder.blurImage.setAlpha(0.58f);
            }
        }
        holder.itemView.setOnClickListener(v -> {
            if(listener != null) listener.onClick(post);
        });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void bindTypeFlag(ImageView view, MangaPost post) {
        MangaLabelUtils.bindTypeFlag(view, post, context, true);
    }

    @Override public int getItemCount() { return data.size(); }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.image.animate().cancel();
        holder.image.setAlpha(1f);
        if (holder.blurImage != null) {
            holder.blurImage.animate().cancel();
            holder.blurImage.setAlpha(0.58f);
        }
        MangaItemWaveAnimator.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView image;
        ImageView blurImage;
        ImageView typeFlag;
        TextView title;
        TextView coverTitle;
        View imageShadow;
        String boundCoverKey;
        String boundAnimationKey = "";
        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imageView);
            blurImage = itemView.findViewById(R.id.itemBlurImageView);
            title = itemView.findViewById(R.id.titleTextView);
            coverTitle = itemView.findViewById(R.id.coverTitleTextView);
            imageShadow = itemView.findViewById(R.id.imageShadowView);
            typeFlag = itemView.findViewById(R.id.typeFlagImageView);
            if (blurImage != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurImage.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP));
            }
        }
    }
}
