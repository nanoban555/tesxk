package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;

public class MangaStyleV1CardAdapter extends RecyclerView.Adapter<MangaStyleV1CardAdapter.Holder> {
    public interface Listener { void onClick(MangaPost post); }
    public interface ChapterListener { void onNeedChapter(MangaPost post); }
    public interface ChapterClickListener { void onChapterClick(MangaPost post); }

    public static final int MODE_GRID = 0;
    public static final int MODE_HORIZONTAL = 1;
    public static final int MODE_RESULT = 2;
    private final Context context;
    private final ArrayList<MangaPost> data;
    private final int mode;
    private final Listener listener;
    private final ChapterListener chapterListener;
    private final ChapterClickListener chapterClickListener;

    public MangaStyleV1CardAdapter(Context context, ArrayList<MangaPost> data, int mode, Listener listener) {
        this(context, data, mode, listener, null, null);
    }

    public MangaStyleV1CardAdapter(Context context, ArrayList<MangaPost> data, int mode, Listener listener, ChapterListener chapterListener) {
        this(context, data, mode, listener, chapterListener, null);
    }

    public MangaStyleV1CardAdapter(Context context, ArrayList<MangaPost> data, int mode, Listener listener, ChapterListener chapterListener, ChapterClickListener chapterClickListener) {
        this.context = context;
        this.data = data;
        this.mode = mode;
        this.listener = listener;
        this.chapterListener = chapterListener;
        this.chapterClickListener = chapterClickListener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) {
        MangaPost post = data.get(position);
        String key = post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug);
        return key.hashCode();
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
        else if (mode == MODE_RESULT) params.setMargins(dp(5), 0, dp(5), dp(8));
        else params.setMargins(dp(5), 0, dp(5), dp(8));
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MangaPost post = data.get(position);
        holder.title.setText(post.title == null ? "" : post.title);
        holder.title.getPaint().setFakeBoldText(false);
        bindChapter(holder, post);
        bindCover(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void bindChapter(Holder holder, MangaPost post) {
        String chapter = MangaLatestChapterResolver.normalize(post.latestChapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        if (chapter.isEmpty()) {
            MangaLabelUtils.bindChapter(holder.chapter, "", context, true);
            if (MangaSettingsManager.shouldLoadLatestChapterLabel(context) && chapterListener != null) chapterListener.onNeedChapter(post);
            return;
        }
        MangaLabelUtils.bindChapter(holder.chapter, chapter, context, true);
        if (holder.chapter.getVisibility() == View.VISIBLE && chapterClickListener != null) {
            holder.chapter.setClickable(true);
            holder.chapter.setFocusable(true);
            holder.chapter.setOnClickListener(v -> chapterClickListener.onChapterClick(post));
        }
    }

    private void bindCover(Holder holder, MangaPost post) {
        String url = post.coverImage == null ? "" : post.coverImage.trim();
        String key = post.getSourceId() + "|" + url;
        holder.boundCoverKey = key;
        holder.image.setBackgroundResource(R.drawable.cover_placeholder);
        if (url.isEmpty()) {
            MangaImageLoader.clear(holder.image);
            holder.progress.setVisibility(View.GONE);
            return;
        }
        holder.progress.setVisibility(View.VISIBLE);
        MangaImageLoader.loadForSource(holder.image, url, post.getSourceId(), true, new MangaImageLoader.Callback() {
            @Override public void onSuccess() { updateCoverProgress(holder, key, url, post.getSourceId()); }
            @Override public void onError() { updateCoverProgress(holder, key, url, post.getSourceId()); }
        });
    }

    private void updateCoverProgress(Holder holder, String key, String url, String sourceId) {
        holder.image.post(() -> {
            if (!key.equals(holder.boundCoverKey)) return;
            holder.progress.setVisibility(MangaImageLoader.isLoaded(holder.image, url, sourceId) ? View.GONE : View.VISIBLE);
        });
    }

    @Override public int getItemCount() { return data.size(); }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.boundCoverKey = "";
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        holder.progress.setVisibility(View.GONE);
        holder.image.animate().cancel();
        MangaImageLoader.clear(holder.image);
        MangaItemWaveAnimator.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final ProgressBar progress;
        final TextView title;
        final TextView chapter;
        String boundCoverKey = "";

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.styleV1ImageView);
            progress = itemView.findViewById(R.id.styleV1ImageProgress);
            title = itemView.findViewById(R.id.styleV1TitleTextView);
            chapter = itemView.findViewById(R.id.styleV1ChapterTextView);
        }
    }
}
