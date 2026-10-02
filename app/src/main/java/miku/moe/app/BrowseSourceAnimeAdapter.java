package miku.moe.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.List;

public class BrowseSourceAnimeAdapter extends BaseAdapter {
    public interface Listener { void onClick(AnimePost post); }
    public interface EpisodeListener { void onEpisodeClick(AnimePost post); }

    public static final int MODE_COMPACT_GRID = 0;
    public static final int MODE_COMFORTABLE_GRID = 1;
    public static final int MODE_LIST = 2;

    private final Context context;
    private final List<AnimePost> data;
    private final Listener listener;
    private final EpisodeListener episodeListener;
    private int mode = MODE_COMPACT_GRID;
    private boolean showSourceLabel;
    private boolean showEpisodeLabel = true;
    private boolean boldTitle;
    private int gridColumns = 3;

    public BrowseSourceAnimeAdapter(Context context, List<AnimePost> data, Listener listener, EpisodeListener episodeListener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        this.episodeListener = episodeListener;
    }

    public void bindFlags(boolean showSourceLabel, boolean showEpisodeLabel, boolean boldTitle) {
        this.showSourceLabel = showSourceLabel;
        this.showEpisodeLabel = showEpisodeLabel;
        this.boldTitle = boldTitle;
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public void setGridColumns(int gridColumns) {
        this.gridColumns = Math.max(1, gridColumns);
    }

    @Override public int getCount() { return data == null ? 0 : data.size(); }
    @Override public Object getItem(int position) { return data.get(position); }
    @Override public long getItemId(int position) {
        AnimePost post = data.get(position);
        String key = itemKey(post);
        return key.isEmpty() ? position : key.hashCode();
    }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return mode == MODE_LIST ? 1 : 0; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        AnimePost post = data.get(position);
        if (mode == MODE_LIST) return getListView(post, convertView, parent);
        return getGridView(post, convertView, parent);
    }

    private View getGridView(AnimePost post, View convertView, ViewGroup parent) {
        GridHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof GridHolder)) {
            convertView = LayoutInflater.from(context).inflate(R.layout.ikiru_manga_grid_item, parent, false);
            holder = new GridHolder();
            holder.image = convertView.findViewById(R.id.imageView);
            holder.typeFlag = convertView.findViewById(R.id.typeFlagImageView);
            holder.title = convertView.findViewById(R.id.textView);
            holder.imageShadow = convertView.findViewById(R.id.imageShadowView);
            holder.coverTitle = convertView.findViewById(R.id.coverTitleTextView);
            holder.coverEpisode = convertView.findViewById(R.id.coverChapterTextView);
            holder.status = convertView.findViewById(R.id.statusTextView);
            holder.progress = convertView.findViewById(R.id.cardLoadingProgress);
            convertView.setTag(holder);
        } else {
            holder = (GridHolder) convertView.getTag();
        }
        applyCoverSize(convertView, holder.image, parent);
        bindCover(holder.image, post, holder);
        bindSourceFlag(holder.typeFlag, post);
        String title = clean(post == null ? "" : post.categoryName);
        bindTitle(holder.title, title);
        bindTitle(holder.coverTitle, title);
        if (holder.title != null) holder.title.setVisibility(View.VISIBLE);
        if (holder.imageShadow != null) holder.imageShadow.setVisibility(View.GONE);
        if (holder.coverTitle != null) holder.coverTitle.setVisibility(View.GONE);
        if (holder.status != null) holder.status.setVisibility(View.GONE);
        if (holder.progress != null) holder.progress.setVisibility(View.GONE);
        bindEpisode(holder.coverEpisode, post);
        convertView.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
        return convertView;
    }

    private View getListView(AnimePost post, View convertView, ViewGroup parent) {
        ListHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof ListHolder)) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(16), dp(8), dp(16), dp(8));
            root.setMinimumHeight(dp(64));
            root.setClickable(true);
            root.setForeground(resolveSelectableItemBackground());

            FrameLayout coverFrame = new FrameLayout(context);
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            coverFrame.setLayoutParams(coverParams);

            ShapeableImageView image = new ShapeableImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverFrame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView flag = new ImageView(context);
            flag.setScaleType(ImageView.ScaleType.FIT_XY);
            FrameLayout.LayoutParams flagParams = new FrameLayout.LayoutParams(dp(18), dp(13), Gravity.START | Gravity.TOP);
            coverFrame.addView(flag, flagParams);

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp(16));
            textColumn.setLayoutParams(textParams);

            TextView title = new TextView(context);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextColor(com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, ContextCompat.getColor(context, R.color.md_theme_onSurface)));
            title.setTextSize(14);

            TextView episode = new TextView(context);
            episode.setSingleLine(true);
            episode.setEllipsize(android.text.TextUtils.TruncateAt.END);
            episode.setTextColor(com.google.android.material.color.MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(context, R.color.md_theme_primary)));
            episode.setTextSize(12);

            TextView meta = new TextView(context);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            meta.setTextColor(com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)));
            meta.setTextSize(12);

            textColumn.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            textColumn.addView(episode, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            textColumn.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(coverFrame);
            root.addView(textColumn);

            holder = new ListHolder();
            holder.root = root;
            holder.image = image;
            holder.typeFlag = flag;
            holder.title = title;
            holder.episode = episode;
            holder.meta = meta;
            root.setTag(holder);
            convertView = root;
        } else {
            holder = (ListHolder) convertView.getTag();
        }
        bindCover(holder.image, post, holder);
        bindSourceFlag(holder.typeFlag, post);
        bindTitle(holder.title, clean(post == null ? "" : post.categoryName));
        bindEpisode(holder.episode, post);
        bindMeta(holder.meta, post);
        holder.root.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
        return convertView;
    }

    private void applyCoverSize(View itemView, ImageView image, ViewGroup parent) {
        if (itemView == null || image == null) return;
        int parentWidth = parent == null ? 0 : parent.getWidth();
        int parentPadding = parent == null ? 0 : parent.getPaddingLeft() + parent.getPaddingRight();
        int spacing = parent instanceof GridView ? ((GridView) parent).getHorizontalSpacing() : dp(4);
        if (parentWidth <= 0) parentWidth = context.getResources().getDisplayMetrics().widthPixels;
        int totalSpacing = Math.max(0, gridColumns - 1) * spacing;
        int cellWidth = (parentWidth - parentPadding - totalSpacing) / Math.max(1, gridColumns);
        int contentWidth = cellWidth - itemView.getPaddingLeft() - itemView.getPaddingRight();
        if (contentWidth <= 0) return;
        int coverHeight = Math.round(contentWidth * 1.5f);
        ViewGroup.LayoutParams params = image.getLayoutParams();
        if (params != null && params.height != coverHeight) {
            params.height = coverHeight;
            image.setLayoutParams(params);
        }
    }

    private void bindCover(ImageView image, AnimePost post, Object holder) {
        if (image == null) return;
        String cover = post == null || post.imgUrl == null ? "" : post.imgUrl;
        String key = itemKey(post) + "|" + cover + "|" + (post == null ? "" : post.sourceId);
        String current = holder instanceof GridHolder ? ((GridHolder) holder).coverKey : ((ListHolder) holder).coverKey;
        if (!key.equals(current) || image.getDrawable() == null) {
            if (holder instanceof GridHolder) ((GridHolder) holder).coverKey = key;
            else ((ListHolder) holder).coverKey = key;
            image.animate().cancel();
            image.setAlpha(1f);
            image.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            Glide.with(context.getApplicationContext()).load(cover).centerCrop().into(image);
        } else {
            image.animate().cancel();
            image.setAlpha(1f);
        }
    }

    private void bindTitle(TextView view, String title) {
        if (view == null) return;
        view.setText(title == null ? "" : title);
        view.setTypeface(Typeface.DEFAULT, boldTitle ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void bindSourceFlag(ImageView view, AnimePost post) {
        if (view == null) return;
        if (!showSourceLabel) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setImageResource(R.drawable.ic_play);
    }

    private void bindEpisode(TextView view, AnimePost post) {
        if (view == null) return;
        String episode = episodeText(post);
        if (!showEpisodeLabel || episode.isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
            view.setOnClickListener(null);
            return;
        }
        view.setText(episode);
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(episodeListener == null ? null : v -> episodeListener.onEpisodeClick(post));
    }

    private void bindMeta(TextView view, AnimePost post) {
        if (view == null) return;
        String value = metaText(post);
        if (value.isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
        } else {
            view.setText(value);
            view.setVisibility(View.VISIBLE);
        }
    }

    private String episodeText(AnimePost post) {
        return AnimeEpisodeLabelUtils.latestLabel(post);
    }

    private String metaText(AnimePost post) {
        if (post == null || !showSourceLabel) return "";
        StringBuilder builder = new StringBuilder();
        appendPart(builder, AnimeSettingsManager.labelForSourceId(post.sourceId));
        appendPart(builder, firstUseful(post.statusVideo, post.ongoing ? "Ongoing" : ""));
        appendPart(builder, post.year > 0 ? String.valueOf(post.year) : "");
        appendPart(builder, firstUseful(post.rating == null || post.rating.trim().isEmpty() ? "" : "★ " + post.rating.trim(), ""));
        return builder.toString();
    }

    private void appendPart(StringBuilder builder, String value) {
        if (builder == null || value == null || value.trim().isEmpty()) return;
        if (builder.length() > 0) builder.append(" • ");
        builder.append(value.trim());
    }

    private android.graphics.drawable.Drawable resolveSelectableItemBackground() {
        android.util.TypedValue outValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return ContextCompat.getDrawable(context, outValue.resourceId);
    }

    private String itemKey(AnimePost post) {
        if (post == null) return "";
        if (post.slug != null && !post.slug.trim().isEmpty()) return post.slug.trim();
        String source = post.sourceId == null ? "" : post.sourceId;
        return source + ":" + post.categoryId + ":" + post.channelId + ":" + clean(post.categoryName);
    }

    private String firstUseful(String first, String second) {
        String a = first == null ? "" : first.trim();
        if (!a.isEmpty() && !"null".equalsIgnoreCase(a)) return a;
        String b = second == null ? "" : second.trim();
        return "null".equalsIgnoreCase(b) ? "" : b;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static final class GridHolder {
        ImageView image;
        ImageView typeFlag;
        TextView title;
        View imageShadow;
        TextView coverTitle;
        TextView coverEpisode;
        TextView status;
        View progress;
        String coverKey;
    }

    private static final class ListHolder {
        LinearLayout root;
        ImageView image;
        ImageView typeFlag;
        TextView title;
        TextView episode;
        TextView meta;
        String coverKey;
    }
}
