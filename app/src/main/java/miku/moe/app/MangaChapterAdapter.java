package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;

public class MangaChapterAdapter extends ArrayAdapter<MangaChapter> {
    private final String mangaSlug;
    private final MangaPost manga;

    public MangaChapterAdapter(Context context, String mangaSlug, ArrayList<MangaChapter> chapters) {
        super(context, 0, chapters);
        this.mangaSlug = mangaSlug == null ? "" : mangaSlug;
        this.manga = null;
    }

    public MangaChapterAdapter(Context context, MangaPost manga, ArrayList<MangaChapter> chapters) {
        super(context, 0, chapters);
        this.manga = manga;
        this.mangaSlug = manga == null || manga.slug == null ? "" : manga.slug;
    }

    @Override public boolean hasStableIds() { return true; }

    @Override public long getItemId(int position) {
        MangaChapter ch = getItem(position);
        return ch == null ? position : stableId(ch);
    }

    private long stableId(MangaChapter ch) {
        String key = (ch.slug == null ? "" : ch.slug) + ":" + ch.index;
        return key.hashCode();
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        boolean fresh = convertView == null;
        Holder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.manga_detail_chapter_item, parent, false);
            holder = new Holder();
            holder.title = convertView.findViewById(R.id.channelNameTextView);
            holder.progressText = convertView.findViewById(R.id.watchProgressTextView);
            holder.bar = convertView.findViewById(R.id.episodeProgressBar);
            holder.icon = convertView.findViewById(R.id.episodeIconImageView);
            convertView.setClickable(false);
            convertView.setFocusable(false);
            convertView.setFocusableInTouchMode(false);
            convertView.setTag(holder);
        } else {
            Object tag = convertView.getTag();
            if (tag instanceof Holder) holder = (Holder) tag; else {
                holder = new Holder();
                holder.title = convertView.findViewById(R.id.channelNameTextView);
                holder.progressText = convertView.findViewById(R.id.watchProgressTextView);
                holder.bar = convertView.findViewById(R.id.episodeProgressBar);
                holder.icon = convertView.findViewById(R.id.episodeIconImageView);
                convertView.setTag(holder);
            }
            convertView.animate().cancel();
        }
        MangaChapter ch = getItem(position);
        if (holder.icon != null) holder.icon.setImageResource(R.drawable.ic_book);
        holder.title.setText(ch == null || ch.title == null || ch.title.trim().isEmpty() ? "Chapter" : ch.title);
        MangaHistoryManager.Progress p = (ch == null || manga == null) ? null : MangaHistoryManager.getProgress(getContext(), manga, ch.index);
        if (p != null && p.totalPages > 0) {
            holder.progressText.setVisibility(View.VISIBLE);
            holder.bar.setVisibility(View.VISIBLE);
            int current = Math.min(p.page + 1, p.totalPages);
            String date = (ch.date == null || ch.date.isEmpty()) ? "" : " • " + ch.date;
            holder.progressText.setText("Terakhir dibaca: halaman " + current + " / " + p.totalPages + date);
            holder.bar.setProgress(Math.max(1, Math.min(100, (current * 100) / Math.max(1, p.totalPages))));
        } else if (ch != null && ch.date != null && !ch.date.isEmpty()) {
            holder.progressText.setVisibility(View.VISIBLE);
            holder.bar.setVisibility(View.GONE);
            holder.progressText.setText(ch.date);
            holder.bar.setProgress(0);
        } else {
            holder.progressText.setVisibility(View.GONE);
            holder.bar.setVisibility(View.GONE);
            holder.bar.setProgress(0);
        }
        if (fresh && position < 18) {
            convertView.setAlpha(0f);
            convertView.setTranslationY(14f);
            convertView.animate().alpha(1f).translationY(0f).setDuration(150).setStartDelay(Math.min(position, 6) * 14L).start();
        } else if (!fresh) {
            convertView.setAlpha(1f);
            convertView.setTranslationY(0f);
        }
        return convertView;
    }

    private static class Holder {
        TextView title;
        TextView progressText;
        ProgressBar bar;
        ImageView icon;
    }
}
