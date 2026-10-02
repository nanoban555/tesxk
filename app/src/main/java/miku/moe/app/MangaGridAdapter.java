package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import androidx.core.content.ContextCompat;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;

public class MangaGridAdapter extends BaseAdapter {
    public interface Listener { void onClick(MangaPost post); }
    public interface ChapterListener { void onChapterClick(MangaPost post); }
    private final Context context; private final ArrayList<MangaPost> data; private final Listener listener;
    private final ChapterListener chapterListener;
    private final boolean respectLabelSettings;
    private final boolean preferTotalChapterLabel;
    private boolean showSourceLabel = false;
    private boolean ikiruStyle = false;
    private boolean stripChapterPrefix = false;
    private boolean chapterInsideCover = false;
    private boolean searchCompact = false;
    private boolean cachedCoverOnly = false;
    private static final int MAX_PENDING_ANIMATIONS = 18;
    private static final float DPI_600 = 600f;
    private final HashSet<String> pendingAnimations = new HashSet<>();
    private final HashSet<String> pendingTypeHydration = new HashSet<>();
    public MangaGridAdapter(Context c, ArrayList<MangaPost> d, Listener l) { this(c, d, l, false, null, true); }
    public MangaGridAdapter(Context c, ArrayList<MangaPost> d, Listener l, boolean preferTotalChapterLabel) { this(c, d, l, preferTotalChapterLabel, null, true); }
    public MangaGridAdapter(Context c, ArrayList<MangaPost> d, Listener l, boolean preferTotalChapterLabel, ChapterListener chapterListener) { this(c, d, l, preferTotalChapterLabel, chapterListener, true); }
    public MangaGridAdapter(Context c, ArrayList<MangaPost> d, Listener l, boolean preferTotalChapterLabel, ChapterListener chapterListener, boolean respectLabelSettings) { context=c; data=d; listener=l; this.preferTotalChapterLabel=preferTotalChapterLabel; this.chapterListener=chapterListener; this.respectLabelSettings=respectLabelSettings; }
    public void setShowSourceLabel(boolean showSourceLabel) { this.showSourceLabel = showSourceLabel; }
    public void setIkiruStyle(boolean ikiruStyle) { this.ikiruStyle = ikiruStyle; }
    public void setStripChapterPrefix(boolean stripChapterPrefix) { this.stripChapterPrefix = stripChapterPrefix; }
    public void setChapterInsideCover(boolean chapterInsideCover) { this.chapterInsideCover = chapterInsideCover; }
    public void setSearchCompact(boolean searchCompact) { this.searchCompact = searchCompact; }
    public void setCachedCoverOnly(boolean cachedCoverOnly) { this.cachedCoverOnly = cachedCoverOnly; }
    @Override public int getCount(){ return data.size(); }
    @Override public Object getItem(int i){ return data.get(i); }
    @Override public long getItemId(int i){
        MangaPost p = data.get(i);
        String key = itemKey(p);
        return key == null || key.isEmpty() ? i : key.hashCode();
    }
    @Override public boolean hasStableIds(){ return true; }

    public void animateItems(ArrayList<MangaPost> items) {
        pendingAnimations.clear();
    }

    private String itemKey(MangaPost p) {
        if (p == null) return "";
        if (p.slug != null && !p.slug.isEmpty()) return p.slug;
        return p.title == null ? "" : p.title;
    }

    @Override public View getView(int pos, View v, ViewGroup parent) {
        Holder h;
        if (v == null) {
            v = LayoutInflater.from(context).inflate(ikiruStyle ? R.layout.ikiru_manga_grid_item : R.layout.manga_grid_item, parent, false);
            h = new Holder();
            h.image = v.findViewById(R.id.imageView);
            h.title = v.findViewById(R.id.textView);
            h.coverTitle = v.findViewById(R.id.coverTitleTextView);
            h.sub = v.findViewById(R.id.episodeTitleTextView);
            h.meta = v.findViewById(R.id.metaTextView);
            h.res = v.findViewById(R.id.resolutionTextView);
            h.status = v.findViewById(R.id.statusTextView);
            h.date = v.findViewById(R.id.dateTextView);
            h.views = v.findViewById(R.id.viewsTextView);
            h.cardLoadingProgress = v.findViewById(R.id.cardLoadingProgress);
            h.typeFlag = v.findViewById(R.id.typeFlagImageView);
            h.coverChapter = v.findViewById(R.id.coverChapterTextView);
            v.setClickable(true);
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);
            v.setTag(h);
        } else h = (Holder) v.getTag();

        if (searchCompact) applySearchCompact(h, v);

        MangaPost p = data.get(pos);
        String animationKey = itemKey(p);
        String sourceId = p.getSourceId();
        String coverUrl = p.coverImage == null ? "" : p.coverImage.trim();
        String coverKey = animationKey + "|" + coverUrl + "|" + sourceId;
        boolean sameCover = coverKey.equals(h.boundCoverKey);
        pendingAnimations.remove(animationKey);
        h.boundAnimationKey = animationKey;
        boolean coverLoaded = !coverUrl.isEmpty() && MangaImageLoader.isLoaded(h.image, coverUrl, sourceId);
        boolean imageMissing = h.image.getDrawable() == null || !coverLoaded;
        if (coverUrl.isEmpty()) {
            h.boundCoverKey = coverKey;
            MangaImageLoader.clear(h.image);
            h.image.setBackgroundResource(R.drawable.cover_placeholder);
            setCoverLoading(h, false);
        } else if (!sameCover || imageMissing) {
            h.boundCoverKey = coverKey;
            h.image.animate().cancel();
            h.image.setAlpha(1f);
            if (!sameCover) h.image.setImageDrawable(null);
            h.image.setBackgroundResource(R.drawable.cover_placeholder);
            setCoverLoading(h, !MangaCoverCache.isSaved(context, coverUrl));
            MangaImageLoader.Callback callback = new MangaImageLoader.Callback() {
                @Override public void onSuccess() { postUpdateCoverLoading(h, coverKey, coverUrl, sourceId); }
                @Override public void onError() { postUpdateCoverLoading(h, coverKey, coverUrl, sourceId); }
            };
            if (cachedCoverOnly) MangaImageLoader.loadCoverForSourceCachedOnly(h.image, coverUrl, sourceId, callback); else MangaImageLoader.loadForSource(h.image, coverUrl, sourceId, true, callback);
        } else {
            h.image.animate().cancel();
            h.image.setAlpha(1f);
            setCoverLoading(h, false);
        }
        String title = p.title == null ? "" : p.title;
        boolean compactDataUi = MangaSettingsManager.isCompactMangaDataUiEnabled(context);
        h.title.setText(title);
        MangaTitleStyle.apply(h.title, context);
        if (ikiruStyle) h.title.getPaint().setFakeBoldText(false);
        if (h.coverTitle != null) {
            h.coverTitle.setText(title);
            MangaTitleStyle.apply(h.coverTitle, context);
            if (ikiruStyle) h.coverTitle.getPaint().setFakeBoldText(false);
            h.coverTitle.setVisibility(compactDataUi ? View.VISIBLE : View.GONE);
        }
        h.title.setVisibility(compactDataUi ? View.GONE : View.VISIBLE);

        if (ikiruStyle) {
            if (h.res != null) h.res.setVisibility(View.GONE);
            bindTypeFlag(h.typeFlag, p);
        } else {
            MangaLabelUtils.bindType(h.res, p, context, respectLabelSettings);
            h.res.setTextColor(0xFFFFFFFF);
            h.res.setBackgroundResource(R.drawable.badge_dark_background);
        }

        CharSequence chapter;
        boolean hasChapter;
        if (p.favoriteChapterBase > 0 && p.favoriteChapterAdded > 0) {
            chapter = MangaLabelUtils.favoriteChapterIncreaseLabel(p.favoriteChapterBase, p.favoriteChapterAdded);
            hasChapter = true;
        } else {
            String latestChapter = p.latestChapter == null ? "" : p.latestChapter.trim();
            if (!latestChapter.isEmpty()) {
                chapter = latestChapter;
                hasChapter = true;
            } else if (preferTotalChapterLabel && p.totalChapters > 0) {
                chapter = p.totalChapters + " Chapter";
                hasChapter = true;
            } else {
                chapter = "";
                hasChapter = false;
            }
        }
        if (hasChapter && stripChapterPrefix && chapter instanceof String) chapter = compactChapterText(chapter.toString());
        boolean useChapterInsideCover = compactDataUi || chapterInsideCover;
        TextView chapterView = useChapterInsideCover && h.coverChapter != null ? h.coverChapter : h.status;
        TextView otherChapterView = useChapterInsideCover && h.coverChapter != null ? h.status : h.coverChapter;
        if (otherChapterView != null) {
            otherChapterView.setVisibility(View.GONE);
            otherChapterView.setOnClickListener(null);
        }
        if (chapterView != null) {
            if (hasChapter) {
                chapterView.setVisibility(View.VISIBLE);
                MangaLabelUtils.bindChapter(chapterView, chapter, context, respectLabelSettings);
                boolean plainCompactChapter = compactDataUi && !chapterInsideCover;
                if (plainCompactChapter) {
                    chapterView.setTextColor(0xFFFFFFFF);
                    chapterView.setBackgroundColor(0x00000000);
                    chapterView.setMinWidth(0);
                    chapterView.setPadding(0, 0, 0, 0);
                } else if (useChapterInsideCover) {
                    chapterView.setTextColor(0xFFFFFFFF);
                    chapterView.setBackgroundResource(R.drawable.badge_dark_background);
                    chapterView.setMinWidth(0);
                    chapterView.setPadding(dp(7), dp(3), dp(7), dp(3));
                } else if (ikiruStyle) {
                    chapterView.setTextColor(com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)));
                    chapterView.setBackgroundColor(0x00000000);
                    chapterView.setMinWidth(0);
                    chapterView.setPadding(0, 0, 0, 0);
                } else {
                    chapterView.setTextColor(0xFFFFFFFF);
                    chapterView.setBackgroundResource(R.drawable.badge_dark_background);
                    chapterView.setMinWidth(dp(38));
                    chapterView.setPadding(dp(7), dp(3), dp(7), dp(3));
                }
                chapterView.setClickable(chapterListener != null);
                chapterView.setFocusable(false);
                chapterView.setOnClickListener(chapterListener == null ? null : x -> chapterListener.onChapterClick(p));
            } else {
                chapterView.setText("");
                chapterView.setVisibility(View.GONE);
                chapterView.setClickable(false);
                chapterView.setFocusable(false);
                chapterView.setOnClickListener(null);
            }
        }
        if (searchCompact) applySearchCompactText(h);

        h.sub.setVisibility(View.GONE);
        h.meta.setVisibility(View.GONE);
        h.date.setVisibility(View.GONE);
        h.views.setVisibility(View.GONE);

        MangaItemWaveAnimator.reset(v);

        v.setOnClickListener(x -> { if (listener != null) listener.onClick(p); });
        return v;
    }

    private void setCoverLoading(Holder h, boolean loading) {
        if (h == null || h.cardLoadingProgress == null) return;
        h.cardLoadingProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void postUpdateCoverLoading(Holder h, String coverKey, String coverUrl, String sourceId) {
        if (h == null || h.image == null) return;
        h.image.post(() -> updateCoverLoading(h, coverKey, coverUrl, sourceId));
    }

    private void updateCoverLoading(Holder h, String coverKey, String coverUrl, String sourceId) {
        if (h == null || h.cardLoadingProgress == null) return;
        if (!coverKey.equals(h.boundCoverKey)) return;
        boolean loaded = MangaImageLoader.isLoaded(h.image, coverUrl, sourceId);
        h.cardLoadingProgress.setVisibility(loaded ? View.GONE : View.VISIBLE);
    }

    private void bindTypeFlag(ImageView view, MangaPost post) {
        MangaLabelUtils.bindTypeFlag(view, post, context, respectLabelSettings);
        if (MangaSettingsManager.shouldLoadTypeLabel(context) && MangaLabelUtils.typeFlagResource(MangaLabelUtils.typeLabel(post)) == 0) hydrateTypeFlag(post);
    }

    private void hydrateTypeFlag(MangaPost post) {
        if (post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        String key = post.getSourceId() + "|" + post.slug.trim();
        if (pendingTypeHydration.contains(key)) return;
        pendingTypeHydration.add(key);
        MangaRepository.INSTANCE.detailFuture(post.getSourceId(), post.slug.trim()).whenComplete((fresh, error) -> {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> {
                pendingTypeHydration.remove(key);
                if (fresh == null) return;
                String resolved = MangaLabelUtils.normalizeStoredType(fresh.typeLabel, safe(fresh.genre) + " " + safe(fresh.status) + " " + safe(fresh.synopsis) + " " + safe(fresh.info));
                if (resolved.isEmpty()) resolved = MangaLabelUtils.typeLabel(fresh);
                if (resolved.isEmpty()) return;
                post.typeLabel = resolved;
                if (!safe(fresh.title).isEmpty()) post.title = fresh.title;
                if (!safe(fresh.coverImage).isEmpty()) post.coverImage = fresh.coverImage;
                if (!safe(fresh.author).isEmpty()) post.author = fresh.author;
                if (!safe(fresh.status).isEmpty()) post.status = fresh.status;
                if (!safe(fresh.synopsis).isEmpty()) post.synopsis = fresh.synopsis;
                if (!safe(fresh.genre).isEmpty()) post.genre = fresh.genre;
                if (!safe(fresh.info).isEmpty()) post.info = fresh.info;
                MangaFavoriteManager.updateExistingFavorite(context, post);
                notifyDataSetChanged();
            });
        });
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String compactChapterText(String value) {
        if (value == null) return "";
        String text = value.trim();
        text = text.replaceFirst("(?i)^chapter\\s+", "");
        text = text.replaceFirst("(?i)^ch\\.?\\s*", "");
        return text.trim();
    }

    private void applySearchCompact(Holder h, View itemView) {
        itemView.setPadding(dpi600(12), dpi600(12), dpi600(12), dpi600(12));
        if (h.title != null) {
            h.title.setMinHeight(dpi600(70));
            h.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(40));
            h.title.setLineSpacing(dpi600(4), 1f);
            if (h.title.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) h.title.getLayoutParams();
                params.setMargins(dpi600(12), dpi600(10), dpi600(12), 0);
                h.title.setLayoutParams(params);
            }
        }
        if (h.coverTitle != null) h.coverTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(38));
        if (h.res != null) {
            h.res.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(30));
            h.res.setPadding(dpi600(16), dpi600(8), dpi600(16), dpi600(8));
            if (h.res.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) h.res.getLayoutParams();
                params.setMargins(dpi600(14), dpi600(14), dpi600(14), dpi600(14));
                h.res.setLayoutParams(params);
            }
        }
        if (h.status != null) h.status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(34));
        if (h.coverChapter != null) h.coverChapter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(34));
        if (h.cardLoadingProgress != null) {
            ViewGroup.LayoutParams params = h.cardLoadingProgress.getLayoutParams();
            if (params != null) {
                params.width = dpi600(72);
                params.height = dpi600(72);
                h.cardLoadingProgress.setLayoutParams(params);
            }
        }
    }

    private void applySearchCompactText(Holder h) {
        if (h.title != null) h.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(40));
        if (h.coverTitle != null) h.coverTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(38));
        if (h.status != null) h.status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(34));
        if (h.coverChapter != null) h.coverChapter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dpi600(34));
    }

    private boolean hasReadyChapter(MangaPost p) {
        if (p == null) return false;
        if (preferTotalChapterLabel && p.totalChapters > 0) return true;
        return p.latestChapter != null && !p.latestChapter.trim().isEmpty();
    }

    private int dp(int v){ return Math.round(v * context.getResources().getDisplayMetrics().density); }
    private int dpi600(int v){ int densityDpi = context == null || context.getResources() == null ? 600 : context.getResources().getDisplayMetrics().densityDpi; return Math.max(1, Math.round(v * densityDpi / DPI_600)); }

    static class Holder { ImageView image, typeFlag; ProgressBar cardLoadingProgress; TextView title, coverTitle, sub, meta, res, status, date, views, coverChapter; String boundCoverKey; String boundAnimationKey = ""; }
}
