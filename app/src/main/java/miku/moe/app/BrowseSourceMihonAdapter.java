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
import com.google.android.material.imageview.ShapeableImageView;
import java.util.List;
import java.util.Locale;

public class BrowseSourceMihonAdapter extends BaseAdapter {
    public interface Listener { void onClick(MangaPost post); }
    public interface ChapterListener { void onChapterClick(MangaPost post); }

    public static final int MODE_COMPACT_GRID = 0;
    public static final int MODE_COMFORTABLE_GRID = 1;
    public static final int MODE_LIST = 2;

    private final Context context;
    private final List<MangaPost> data;
    private final Listener listener;
    private final ChapterListener chapterListener;
    private int mode = MODE_COMPACT_GRID;
    private boolean showTypeLabel;
    private boolean showLatestChapterLabel;
    private boolean boldTitle;
    private int gridColumns = 3;

    public BrowseSourceMihonAdapter(Context context, List<MangaPost> data, Listener listener, ChapterListener chapterListener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        this.chapterListener = chapterListener;
    }

    public void bindFlags(boolean showTypeLabel, boolean showLatestChapterLabel, boolean boldTitle) {
        this.showTypeLabel = showTypeLabel;
        this.showLatestChapterLabel = showLatestChapterLabel;
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
        MangaPost post = data.get(position);
        String key = itemKey(post);
        return key.isEmpty() ? position : key.hashCode();
    }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return mode == MODE_LIST ? 1 : 0; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        MangaPost post = data.get(position);
        if (mode == MODE_LIST) return getListView(post, convertView, parent);
        return getGridView(post, convertView, parent);
    }

    private View getGridView(MangaPost post, View convertView, ViewGroup parent) {
        GridHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof GridHolder)) {
            convertView = LayoutInflater.from(context).inflate(R.layout.ikiru_manga_grid_item, parent, false);
            holder = new GridHolder();
            holder.image = convertView.findViewById(R.id.imageView);
            holder.typeFlag = convertView.findViewById(R.id.typeFlagImageView);
            holder.title = convertView.findViewById(R.id.textView);
            holder.imageShadow = convertView.findViewById(R.id.imageShadowView);
            holder.coverTitle = convertView.findViewById(R.id.coverTitleTextView);
            holder.coverChapter = convertView.findViewById(R.id.coverChapterTextView);
            holder.status = convertView.findViewById(R.id.statusTextView);
            holder.progress = convertView.findViewById(R.id.cardLoadingProgress);
            convertView.setTag(holder);
        } else {
            holder = (GridHolder) convertView.getTag();
        }
        applyMihonCoverSize(convertView, holder.image, parent);
        bindCover(holder.image, post, holder);
        bindTypeFlag(holder.typeFlag, post);
        String title = post.title == null ? "" : post.title;
        bindTitle(holder.title, title);
        bindTitle(holder.coverTitle, title);
        if (holder.title != null) holder.title.setVisibility(View.VISIBLE);
        if (holder.imageShadow != null) holder.imageShadow.setVisibility(View.GONE);
        if (holder.coverTitle != null) holder.coverTitle.setVisibility(View.GONE);
        if (holder.status != null) holder.status.setVisibility(View.GONE);
        bindChapter(holder.coverChapter, post);
        convertView.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
        return convertView;
    }

    private View getListView(MangaPost post, View convertView, ViewGroup parent) {
        ListHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof ListHolder)) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(16), dp(8), dp(16), dp(8));
            root.setMinimumHeight(dp(56));
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

            android.widget.ProgressBar progress = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
            progress.setIndeterminate(true);
            progress.setVisibility(View.GONE);
            try { progress.getIndeterminateDrawable().setTint(0xFFFFFFFF); } catch (Exception ignored) { }
            FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
            coverFrame.addView(progress, progressParams);

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

            TextView chapter = new TextView(context);
            chapter.setSingleLine(true);
            chapter.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chapter.setTextColor(com.google.android.material.color.MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(context, R.color.md_theme_primary)));
            chapter.setTextSize(12);

            textColumn.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            textColumn.addView(chapter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(coverFrame);
            root.addView(textColumn);

            holder = new ListHolder();
            holder.root = root;
            holder.image = image;
            holder.typeFlag = flag;
            holder.title = title;
            holder.chapter = chapter;
            holder.progress = progress;
            root.setTag(holder);
            convertView = root;
        } else {
            holder = (ListHolder) convertView.getTag();
        }
        bindCover(holder.image, post, holder);
        bindTypeFlag(holder.typeFlag, post);
        bindTitle(holder.title, post.title == null ? "" : post.title);
        bindChapter(holder.chapter, post);
        holder.root.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
        return convertView;
    }

    private void applyMihonCoverSize(View itemView, ImageView image, ViewGroup parent) {
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

    private void bindCover(ImageView image, MangaPost post, Object holder) {
        if (image == null || post == null || holder == null) return;
        String cover = post.coverImage == null ? "" : post.coverImage.trim();
        String sourceId = post.getSourceId();
        String key = itemKey(post) + "|" + cover + "|" + sourceId;
        String current = getCoverKey(holder);
        boolean sameCover = key.equals(current);
        boolean loaded = MangaImageLoader.isLoaded(image, cover, sourceId);
        if (cover.isEmpty()) {
            setCoverKey(holder, key);
            MangaImageLoader.clear(image);
            image.setBackgroundResource(R.drawable.cover_placeholder);
            setCoverLoading(holder, false);
            return;
        }
        if (!sameCover || !loaded) {
            setCoverKey(holder, key);
            image.animate().cancel();
            image.setAlpha(1f);
            if (!sameCover) image.setImageDrawable(null);
            image.setBackgroundResource(R.drawable.cover_placeholder);
            setCoverLoading(holder, true);
            MangaImageLoader.Callback callback = new MangaImageLoader.Callback() {
                @Override public void onSuccess() { postUpdateCoverLoading(image, holder, key, cover, sourceId); }
                @Override public void onError() { postUpdateCoverLoading(image, holder, key, cover, sourceId); }
            };
            MangaImageLoader.loadForSource(image, cover, sourceId, true, callback);
        } else {
            image.animate().cancel();
            image.setAlpha(1f);
            setCoverLoading(holder, false);
        }
    }

    private void postUpdateCoverLoading(ImageView image, Object holder, String key, String cover, String sourceId) {
        if (image == null) return;
        image.post(() -> updateCoverLoading(image, holder, key, cover, sourceId));
    }

    private void updateCoverLoading(ImageView image, Object holder, String key, String cover, String sourceId) {
        if (holder == null || !key.equals(getCoverKey(holder))) return;
        boolean loaded = MangaImageLoader.isLoaded(image, cover, sourceId);
        setCoverLoading(holder, !loaded);
    }

    private String getCoverKey(Object holder) {
        if (holder instanceof GridHolder) return ((GridHolder) holder).coverKey;
        if (holder instanceof ListHolder) return ((ListHolder) holder).coverKey;
        return "";
    }

    private void setCoverKey(Object holder, String key) {
        if (holder instanceof GridHolder) ((GridHolder) holder).coverKey = key;
        else if (holder instanceof ListHolder) ((ListHolder) holder).coverKey = key;
    }

    private void setCoverLoading(Object holder, boolean loading) {
        View progress = null;
        if (holder instanceof GridHolder) progress = ((GridHolder) holder).progress;
        else if (holder instanceof ListHolder) progress = ((ListHolder) holder).progress;
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void bindTitle(TextView view, String title) {
        if (view == null) return;
        view.setText(title == null ? "" : title);
        view.setTypeface(Typeface.DEFAULT, boldTitle ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void bindTypeFlag(ImageView view, MangaPost post) {
        if (view == null) return;
        if (!showTypeLabel) {
            view.setVisibility(View.GONE);
            return;
        }
        String type = displayTypeLabel(post);
        int res = typeFlagResource(type);
        if (res == 0) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setImageResource(res);
    }

    private int typeFlagResource(String type) {
        if ("MANHUA".equals(type)) return R.drawable.ic_flag_china;
        if ("MANHWA".equals(type)) return R.drawable.ic_flag_korea;
        if ("MANGA".equals(type) || "DOUJINSHI".equals(type) || "DOUJIN".equals(type) || "ONESHOT".equals(type) || "IMAGE-SET".equals(type)) return R.drawable.ic_flag_japan;
        return 0;
    }

    private String displayTypeLabel(MangaPost post) {
        if (post == null) return "";
        String raw = safe(post.typeLabel).trim();
        String support = safe(post.genre) + " " + safe(post.status) + " " + safe(post.info);
        String supportType = normalizeBrowseType(support, false);
        String rawType = normalizeBrowseType(raw, true);
        if (rawType.length() == 0) return supportType;
        return rawType;
    }

    private String normalizeBrowseType(String raw, boolean trustManga) {
        String padded = " " + safe(raw).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        if (padded.trim().isEmpty()) return "";
        if (padded.contains(" manhwa ") || padded.contains(" korea ") || padded.contains(" korean ")) return "MANHWA";
        if (padded.contains(" manhua ") || padded.contains(" china ") || padded.contains(" chinese ")) return "MANHUA";
        if (padded.contains(" webtoon ") || padded.contains(" web toon ")) return "WEBTOON";
        if (padded.contains(" image set ") || padded.contains(" imageset ")) return "IMAGE-SET";
        if (padded.contains(" doujinshi ")) return "DOUJINSHI";
        if (padded.contains(" doujin ")) return "DOUJIN";
        if (padded.contains(" comic ")) return "COMIC";
        if (padded.contains(" novel ")) return "NOVEL";
        if (padded.contains(" oneshot ") || padded.contains(" one shot ")) return "ONESHOT";
        if (padded.contains(" manga ") || padded.contains(" japan ") || padded.contains(" japanese ")) return trustManga ? "MANGA" : "";
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void bindChapter(TextView view, MangaPost post) {
        if (view == null) return;
        String chapter = post.latestChapter == null ? "" : post.latestChapter.trim();
        if (!showLatestChapterLabel || chapter.isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
            view.setOnClickListener(null);
            return;
        }
        view.setText(chapter);
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(chapterListener == null ? null : v -> chapterListener.onChapterClick(post));
    }

    private android.graphics.drawable.Drawable resolveSelectableItemBackground() {
        android.util.TypedValue outValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return ContextCompat.getDrawable(context, outValue.resourceId);
    }

    private String itemKey(MangaPost post) {
        if (post == null) return "";
        if (post.slug != null && !post.slug.isEmpty()) return post.slug;
        return post.title == null ? "" : post.title;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static final class GridHolder {
        ImageView image;
        ImageView typeFlag;
        TextView title;
        View imageShadow;
        TextView coverTitle;
        TextView coverChapter;
        TextView status;
        View progress;
        String coverKey;
    }

    private static final class ListHolder {
        LinearLayout root;
        ImageView image;
        ImageView typeFlag;
        TextView title;
        TextView chapter;
        View progress;
        String coverKey;
    }
}
