package miku.moe.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

final class MangaReaderPageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int TYPE_PAGE = 1;
    static final int TYPE_MESSAGE = 2;
    static final int TYPE_HEADER = 3;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private String message;
    private final String sourceId;
    private int lastPreloadCenter = -1;
    private boolean released = false;

    MangaReaderPageAdapter(String sourceId) {
        this.sourceId = sourceId;
        setHasStableIds(true);
    }

    void submit(ArrayList<String> newPages) {
        released = false;
        submitChapter(newPages, 0, -1f, "Chapter");
    }

    void submitChapter(ArrayList<String> newPages, int chapterPosition, float chapterIndex, String chapterTitle) {
        released = false;
        message = null;
        entries.clear();
        addPages(entries, newPages, chapterPosition, chapterIndex, chapterTitle, false, "");
        lastPreloadCenter = -1;
        notifyDataSetChanged();
    }

    void showMessage(String text) {
        released = false;
        entries.clear();
        message = text;
        notifyDataSetChanged();
    }

    int appendChapter(ArrayList<String> pages, int chapterPosition, float chapterIndex, String chapterTitle, String headerText) {
        if (released || pages == null || pages.isEmpty() || hasChapterPosition(chapterPosition)) return 0;
        int start = entries.size();
        ArrayList<Entry> add = new ArrayList<>();
        PageInfo last = lastPageInfo();
        String resolvedHeader = last == null ? clean(headerText, chapterTitle) : chapterBoundaryText("Selesai", last.chapterTitle, "Selanjutnya", chapterTitle);
        addPages(add, pages, chapterPosition, chapterIndex, chapterTitle, true, resolvedHeader);
        entries.addAll(add);
        message = null;
        lastPreloadCenter = -1;
        notifyItemRangeInserted(start, add.size());
        return add.size();
    }

    int prependChapter(ArrayList<String> pages, int chapterPosition, float chapterIndex, String chapterTitle, String headerText) {
        if (released || pages == null || pages.isEmpty() || hasChapterPosition(chapterPosition)) return 0;
        ArrayList<Entry> add = new ArrayList<>();
        addPages(add, pages, chapterPosition, chapterIndex, chapterTitle, false, headerText);
        PageInfo first = firstPageInfo();
        if (first != null && !hasHeaderBeforeFirstPage()) add.add(Entry.header(first.chapterPosition, first.chapterIndex, first.chapterTitle, chapterBoundaryText("Sebelumnya", chapterTitle, "Saat ini", first.chapterTitle)));
        entries.addAll(0, add);
        message = null;
        lastPreloadCenter = -1;
        notifyItemRangeInserted(0, add.size());
        return add.size();
    }

    int appendNoNextChapterHeader(int chapterPosition, float chapterIndex, String chapterTitle) {
        if (released || hasNoNextChapterHeader(chapterPosition)) return 0;
        int start = entries.size();
        entries.add(Entry.header(chapterPosition, chapterIndex, chapterTitle, chapterBoundaryText("Selesai", chapterTitle, "Selanjutnya", "Tidak ada chapter selanjutnya")));
        message = null;
        notifyItemInserted(start);
        return 1;
    }

    boolean hasNoNextChapterHeader(int chapterPosition) {
        for (Entry entry : entries) {
            if (entry.type == TYPE_HEADER && entry.chapterPosition == chapterPosition && entry.headerText != null && entry.headerText.contains("Tidak ada chapter selanjutnya")) return true;
        }
        return false;
    }

    boolean hasChapterPosition(int chapterPosition) {
        for (Entry entry : entries) if (entry.chapterPosition == chapterPosition && entry.type == TYPE_PAGE) return true;
        return false;
    }

    int getPageCount() {
        if (message != null) return 0;
        int count = 0;
        for (Entry entry : entries) if (entry.type == TYPE_PAGE) count++;
        return count;
    }

    int getChapterPageCount(int chapterPosition) {
        int count = 0;
        for (Entry entry : entries) if (entry.type == TYPE_PAGE && entry.chapterPosition == chapterPosition) count++;
        return count;
    }

    int findAdapterPosition(int chapterPosition, int pageIndex) {
        int safePage = Math.max(0, pageIndex);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.type == TYPE_PAGE && entry.chapterPosition == chapterPosition && entry.pageIndex == safePage) return i;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.type == TYPE_PAGE && entry.chapterPosition == chapterPosition) return i;
        }
        return -1;
    }

    PageInfo getPageInfoAround(int adapterPosition) {
        if (entries.isEmpty()) return null;
        int safe = Math.max(0, Math.min(adapterPosition, entries.size() - 1));
        Entry entry = entries.get(safe);
        if (entry.type == TYPE_PAGE) return entry.toPageInfo();
        for (int i = safe - 1; i >= 0; i--) {
            Entry prev = entries.get(i);
            if (prev.type == TYPE_PAGE) return prev.toPageInfo();
            if (prev.type == TYPE_HEADER) break;
        }
        for (int i = safe + 1; i < entries.size(); i++) {
            Entry next = entries.get(i);
            if (next.type == TYPE_PAGE) return next.toPageInfo();
            if (next.type == TYPE_HEADER) break;
        }
        return null;
    }

    void trimToChapterWindow(int currentChapterPosition, int retainDistance, RecyclerView rv, LinearLayoutManager layoutManager) {
        if (released || currentChapterPosition < 0 || entries.isEmpty()) return;
        if (rv != null && (rv.isComputingLayout() || rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)) {
            rv.post(() -> trimToChapterWindow(currentChapterPosition, retainDistance, rv, layoutManager));
            return;
        }
        int safeDistance = Math.max(1, retainDistance);
        int firstPosition = layoutManager == null ? RecyclerView.NO_POSITION : layoutManager.findFirstVisibleItemPosition();
        int firstTop = 0;
        if (layoutManager != null && firstPosition != RecyclerView.NO_POSITION) {
            View firstView = layoutManager.findViewByPosition(firstPosition);
            if (firstView != null) firstTop = firstView.getTop();
        }
        ArrayList<int[]> ranges = new ArrayList<>();
        int rangeStart = -1;
        int rangeCount = 0;
        int removedBeforeFirst = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            boolean remove = Math.abs(entry.chapterPosition - currentChapterPosition) > safeDistance;
            if (remove) {
                if (rangeStart < 0) {
                    rangeStart = i;
                    rangeCount = 1;
                } else rangeCount++;
                if (firstPosition != RecyclerView.NO_POSITION && i < firstPosition) removedBeforeFirst++;
            } else if (rangeStart >= 0) {
                ranges.add(new int[]{rangeStart, rangeCount});
                rangeStart = -1;
                rangeCount = 0;
            }
        }
        if (rangeStart >= 0) ranges.add(new int[]{rangeStart, rangeCount});
        if (ranges.isEmpty()) return;
        lastPreloadCenter = -1;
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            entries.subList(range[0], range[0] + range[1]).clear();
            notifyItemRangeRemoved(range[0], range[1]);
        }
        if (layoutManager != null && firstPosition != RecyclerView.NO_POSITION && !entries.isEmpty() && removedBeforeFirst > 0) {
            final int target = Math.max(0, Math.min(entries.size() - 1, firstPosition - removedBeforeFirst));
            final int restoredTop = firstTop;
            layoutManager.scrollToPositionWithOffset(target, restoredTop);
        }
    }

    void preloadAround(Context context, int center) {
        if (released || context == null || entries.isEmpty()) return;
        int safeCenter = Math.max(0, Math.min(center, entries.size() - 1));
        if (Math.abs(safeCenter - lastPreloadCenter) < 2) return;
        lastPreloadCenter = safeCenter;
        int forwardEnd = Math.min(entries.size() - 1, safeCenter + 8);
        int backwardStart = Math.max(0, safeCenter - 2);
        ArrayList<String> forward = new ArrayList<>();
        ArrayList<String> backward = new ArrayList<>();
        for (int i = safeCenter; i <= forwardEnd; i++) {
            Entry entry = entries.get(i);
            if (entry.type == TYPE_PAGE && entry.url != null && !entry.url.trim().isEmpty()) forward.add(entry.url);
        }
        for (int i = safeCenter - 1; i >= backwardStart; i--) {
            Entry entry = entries.get(i);
            if (entry.type == TYPE_PAGE && entry.url != null && !entry.url.trim().isEmpty()) backward.add(entry.url);
        }
        if (forward.isEmpty() && backward.isEmpty()) return;
        Context app = context.getApplicationContext();
        MangaCoroutines.io(() -> {
            for (String url : forward) MangaImageLoader.preloadPriority(app, url, sourceId);
            for (String url : backward) MangaImageLoader.preload(app, url, sourceId);
        });
    }

    void release(RecyclerView rv) {
        released = true;
        clearImages(rv);
        MangaImageLoader.cancelPreloads();
    }

    void clearImages(RecyclerView rv) {
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            RecyclerView.ViewHolder holder = rv.getChildViewHolder(child);
            if (holder instanceof PageHolder) {
                PageHolder pageHolder = (PageHolder) holder;
                pageHolder.loading.setVisibility(View.GONE);
                MangaImageLoader.clear(pageHolder.image);
            }
        }
    }

    @Override public int getItemViewType(int position) {
        if (message != null) return TYPE_MESSAGE;
        return entries.get(position).type;
    }

    @Override public int getItemCount() {
        return message == null ? entries.size() : 1;
    }

    @Override public long getItemId(int position) {
        if (message != null) return 17L;
        if (position < 0 || position >= entries.size()) return RecyclerView.NO_ID;
        return entries.get(position).stableId();
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_MESSAGE) {
            TextView text = new TextView(parent.getContext());
            text.setGravity(Gravity.CENTER);
            text.setTextColor(Color.WHITE);
            text.setTextSize(15f);
            text.setPadding(dp(parent.getContext(), 48), dp(parent.getContext(), 220), dp(parent.getContext(), 48), dp(parent.getContext(), 220));
            text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new MessageHolder(text);
        }
        if (viewType == TYPE_HEADER) return createHeaderHolder(parent);
        FrameLayout root = new FrameLayout(parent.getContext());
        root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setBackgroundColor(Color.WHITE);
        ImageView img;
        if (MangaSettingsManager.isReaderPhotoViewZoomEnabled(parent.getContext())) {
            ZoomableMangaImageView zoomImage = new ZoomableMangaImageView(parent.getContext());
            zoomImage.setReaderScaleType(MangaSettingsManager.getReaderImageScale(parent.getContext()));
            zoomImage.setDoubleTapZoomEnabled(MangaSettingsManager.isReaderDoubleTapZoomEnabled(parent.getContext()));
            img = zoomImage;
        } else {
            MangaWebtoonImageView webtoonImage = new MangaWebtoonImageView(parent.getContext());
            webtoonImage.setCropBorderEnabled(MangaSettingsManager.isReaderCropBorderEnabled(parent.getContext()));
            webtoonImage.setReaderScaleType(MangaSettingsManager.getReaderImageScale(parent.getContext()));
            img = webtoonImage;
        }
        img.setAdjustViewBounds(false);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(Color.WHITE);
        img.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ProgressBar loading = new ProgressBar(parent.getContext());
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(parent.getContext(), 36), dp(parent.getContext(), 36), Gravity.CENTER);
        loading.setLayoutParams(loadingParams);
        loading.setIndeterminate(true);
        loading.setVisibility(View.GONE);
        root.addView(img);
        root.addView(loading);
        return new PageHolder(root, img, loading);
    }

    private void applyKnownImageSize(ImageView image, String url) {
        int[] size = MangaImageLoader.getKnownSize(url, sourceId);
        int width = size == null || size.length < 2 ? -1 : size[0];
        int height = size == null || size.length < 2 ? -1 : size[1];
        if (image instanceof MangaWebtoonImageView) ((MangaWebtoonImageView) image).setExpectedImageSize(width, height);
        else if (image instanceof ZoomableMangaImageView) ((ZoomableMangaImageView) image).setExpectedImageSize(width, height);
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof MessageHolder) ((MessageHolder) holder).text.setText(message);
        else if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind(entries.get(position).headerText);
        else if (holder instanceof PageHolder) {
            Entry entry = entries.get(position);
            PageHolder pageHolder = (PageHolder) holder;
            applyKnownImageSize(pageHolder.image, entry.url);
            boolean alreadyLoaded = MangaImageLoader.isLoaded(pageHolder.image, entry.url, sourceId);
            pageHolder.loading.setVisibility(alreadyLoaded ? View.GONE : View.VISIBLE);
            pageHolder.image.animate().cancel();
            if (alreadyLoaded) {
                pageHolder.image.setAlpha(1f);
                return;
            }
            pageHolder.image.setAlpha(0f);
            MangaImageLoader.loadForSource(pageHolder.image, entry.url, sourceId, false, new MangaImageLoader.Callback() {
                @Override public void onSuccess() {
                    if (released) return;
                    applyKnownImageSize(pageHolder.image, entry.url);
                    pageHolder.loading.setVisibility(View.GONE);
                    if (MangaSettingsManager.isReaderPageTransitionEnabled(pageHolder.image.getContext())) pageHolder.image.animate().alpha(1f).setDuration(60L).start();
                    else pageHolder.image.setAlpha(1f);
                }
                @Override public void onError() {
                    if (released) return;
                    pageHolder.loading.setVisibility(View.GONE);
                    pageHolder.image.setAlpha(1f);
                }
            });
        }
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof PageHolder) {
            PageHolder pageHolder = (PageHolder) holder;
            pageHolder.loading.setVisibility(View.GONE);
            MangaImageLoader.recycle(pageHolder.image);
        }
        super.onViewRecycled(holder);
    }

    private RecyclerView.ViewHolder createHeaderHolder(ViewGroup parent) {
        Context context = parent.getContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.rgb(31, 32, 36));
        root.setPadding(dp(context, 28), dp(context, 132), dp(context, 28), dp(context, 132));
        root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView text = new TextView(context);
        text.setGravity(Gravity.START);
        text.setTextColor(Color.rgb(232, 232, 238));
        text.setIncludeFontPadding(true);
        text.setLineSpacing(dp(context, 5), 1f);
        root.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new HeaderHolder(root, text);
    }

    private void addPages(ArrayList<Entry> target, ArrayList<String> pages, int chapterPosition, float chapterIndex, String chapterTitle, boolean header, String headerText) {
        if (header) target.add(Entry.header(chapterPosition, chapterIndex, chapterTitle, clean(headerText, chapterTitle)));
        int total = pages == null ? 0 : pages.size();
        for (int i = 0; i < total; i++) {
            String url = pages.get(i);
            if (url != null && !url.trim().isEmpty()) target.add(Entry.page(url, chapterPosition, chapterIndex, chapterTitle, i, total));
        }
    }

    private PageInfo firstPageInfo() {
        for (Entry entry : entries) if (entry.type == TYPE_PAGE) return entry.toPageInfo();
        return null;
    }

    private PageInfo lastPageInfo() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.type == TYPE_PAGE) return entry.toPageInfo();
        }
        return null;
    }

    private String chapterBoundaryText(String firstLabel, String firstTitle, String secondLabel, String secondTitle) {
        return firstLabel + ":\n" + clean(firstTitle, "Chapter") + "\n\n" + secondLabel + ":\n" + clean(secondTitle, "Chapter");
    }

    private boolean hasHeaderBeforeFirstPage() {
        boolean seenHeader = false;
        for (Entry entry : entries) {
            if (entry.type == TYPE_HEADER) seenHeader = true;
            if (entry.type == TYPE_PAGE) return seenHeader;
        }
        return false;
    }

    private String clean(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) text = fallback == null || fallback.trim().isEmpty() ? "Chapter" : fallback.trim();
        return text;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class PageInfo {
        final int chapterPosition;
        final float chapterIndex;
        final String chapterTitle;
        final int pageIndex;
        final int totalPages;

        PageInfo(int chapterPosition, float chapterIndex, String chapterTitle, int pageIndex, int totalPages) {
            this.chapterPosition = chapterPosition;
            this.chapterIndex = chapterIndex;
            this.chapterTitle = chapterTitle == null || chapterTitle.trim().isEmpty() ? "Chapter" : chapterTitle.trim();
            this.pageIndex = pageIndex;
            this.totalPages = totalPages;
        }
    }

    private static final class Entry {
        final int type;
        final String url;
        final int chapterPosition;
        final float chapterIndex;
        final String chapterTitle;
        final String headerText;
        final int pageIndex;
        final int totalPages;

        private Entry(int type, String url, int chapterPosition, float chapterIndex, String chapterTitle, String headerText, int pageIndex, int totalPages) {
            this.type = type;
            this.url = url;
            this.chapterPosition = chapterPosition;
            this.chapterIndex = chapterIndex;
            this.chapterTitle = chapterTitle == null || chapterTitle.trim().isEmpty() ? "Chapter" : chapterTitle.trim();
            this.headerText = headerText;
            this.pageIndex = pageIndex;
            this.totalPages = totalPages;
        }

        static Entry page(String url, int chapterPosition, float chapterIndex, String chapterTitle, int pageIndex, int totalPages) {
            return new Entry(TYPE_PAGE, url, chapterPosition, chapterIndex, chapterTitle, null, pageIndex, totalPages);
        }

        static Entry header(int chapterPosition, float chapterIndex, String chapterTitle, String headerText) {
            return new Entry(TYPE_HEADER, null, chapterPosition, chapterIndex, chapterTitle, headerText, 0, 0);
        }

        long stableId() {
            long hash = 1125899906842597L;
            hash = 31L * hash + type;
            hash = 31L * hash + chapterPosition;
            hash = 31L * hash + Float.floatToIntBits(chapterIndex);
            hash = 31L * hash + pageIndex;
            hash = 31L * hash + (url == null ? 0 : url.hashCode());
            hash = 31L * hash + (headerText == null ? 0 : headerText.hashCode());
            return hash;
        }

        PageInfo toPageInfo() {
            return new PageInfo(chapterPosition, chapterIndex, chapterTitle, pageIndex, totalPages);
        }
    }

    private static class PageHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ProgressBar loading;
        PageHolder(View itemView, ImageView image, ProgressBar loading) {
            super(itemView);
            this.image = image;
            this.loading = loading;
        }
    }

    private static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderHolder(View itemView, TextView text) {
            super(itemView);
            this.text = text;
        }

        void bind(String value) {
            String textValue = value == null ? "" : value.trim();
            if (textValue.isEmpty()) textValue = "Chapter";
            SpannableString span = new SpannableString(textValue);
            int start = 0;
            while (start <= textValue.length()) {
                int end = textValue.indexOf('\n', start);
                if (end < 0) end = textValue.length();
                String line = textValue.substring(start, end).trim();
                if (!line.isEmpty()) {
                    boolean label = line.endsWith(":");
                    span.setSpan(new AbsoluteSizeSpan(label ? 18 : 23, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (end == textValue.length()) break;
                start = end + 1;
            }
            text.setText(span);
        }
    }

    private static class MessageHolder extends RecyclerView.ViewHolder {
        final TextView text;
        MessageHolder(TextView text) {
            super(text);
            this.text = text;
        }
    }
}
