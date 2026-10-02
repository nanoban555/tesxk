package miku.moe.app;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

public class HistoryAdapter extends BaseAdapter {
    public interface OnHistoryClickListener { void onHistoryClick(HistoryItem item); }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final Context context;
    private final ArrayList<Object> items;
    private final OnHistoryClickListener detailListener;
    private final OnHistoryClickListener continueListener;

    public HistoryAdapter(Context context, ArrayList<Object> items, OnHistoryClickListener detailListener, OnHistoryClickListener continueListener) {
        this.context = context;
        this.items = items;
        this.detailListener = detailListener;
        this.continueListener = continueListener;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return items.get(position) instanceof HistoryHeader ? TYPE_HEADER : TYPE_ITEM; }
    @Override public boolean isEnabled(int position) { return getItemViewType(position) == TYPE_ITEM; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Object row = items.get(position);
        if (row instanceof HistoryHeader) {
            if (convertView == null) convertView = LayoutInflater.from(context).inflate(R.layout.history_day_header, parent, false);
            TextView dayHeaderTextView = convertView.findViewById(R.id.dayHeaderTextView);
            dayHeaderTextView.setText(((HistoryHeader) row).title);
            return convertView;
        }

        if (convertView == null) convertView = LayoutInflater.from(context).inflate(R.layout.anime_history_item, parent, false);
        bindHistoryItem(context, convertView, (HistoryItem) row, detailListener, continueListener);
        return convertView;
    }

    public static void bindHistoryItem(Context context, View itemView, HistoryItem item,
                                       OnHistoryClickListener detailListener,
                                       OnHistoryClickListener continueListener) {
        ImageView imageView = itemView.findViewById(R.id.imageView);
        TextView titleTextView = itemView.findViewById(R.id.titleTextView);
        TextView metaTextView = itemView.findViewById(R.id.metaTextView);
        TextView sourceBadgeTextView = itemView.findViewById(R.id.sourceBadgeTextView);
        TextView historyBadgeTextView = itemView.findViewById(R.id.historyBadgeTextView);
        ProgressBar watchProgress = itemView.findViewById(R.id.watchProgress);
        View detailClickArea = itemView.findViewById(R.id.detailClickArea);
        MaterialButton detailButton = itemView.findViewById(R.id.detailButton);
        MaterialButton continueButton = itemView.findViewById(R.id.continueButton);

        Glide.with(context.getApplicationContext()).load(item.imageUrl).centerCrop().into(imageView);

        String displayTitle = item.categoryName != null && !item.categoryName.trim().isEmpty() ? item.categoryName : item.title;
        titleTextView.setText(displayTitle == null || displayTitle.isEmpty() ? "Anime" : displayTitle);

        String time = item.lastWatched > 0
                ? DateUtils.getRelativeTimeSpanString(item.lastWatched, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                : "Baru ditonton";
        int progress = 0;
        if (item.duration > 0) progress = (int) Math.max(0, Math.min(100, (item.position * 100L) / item.duration));
        String episodeInfo = item.title != null && !item.title.trim().isEmpty()
                ? item.title.trim() + " • "
                : "";
        metaTextView.setText(episodeInfo + "Terakhir: " + time + " • " + progress + "% ditonton");
        if (sourceBadgeTextView != null) sourceBadgeTextView.setVisibility(View.GONE);
        if (historyBadgeTextView != null) historyBadgeTextView.setVisibility(View.GONE);
        watchProgress.setProgress(progress);

        View.OnClickListener openDetail = v -> { if (detailListener != null) detailListener.onHistoryClick(item); };
        View.OnClickListener continueWatch = v -> { if (continueListener != null) continueListener.onHistoryClick(item); };
        itemView.setOnClickListener(openDetail);
        detailClickArea.setOnClickListener(openDetail);
        detailButton.setOnClickListener(openDetail);
        continueButton.setOnClickListener(continueWatch);
        continueButton.setEnabled(item.videoUrl != null && !item.videoUrl.trim().isEmpty());
    }

    public static class HistoryHeader {
        public final String title;
        public HistoryHeader(String title) { this.title = title; }
    }

    public static String makeDayKey(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        return cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.DAY_OF_YEAR);
    }

    public static String makeDayLabel(long timestamp) {
        if (timestamp <= 0) return "Ditonton hari ini";
        Calendar watched = Calendar.getInstance();
        watched.setTimeInMillis(timestamp);

        Calendar startToday = Calendar.getInstance();
        startToday.set(Calendar.HOUR_OF_DAY, 0);
        startToday.set(Calendar.MINUTE, 0);
        startToday.set(Calendar.SECOND, 0);
        startToday.set(Calendar.MILLISECOND, 0);

        Calendar startWatched = Calendar.getInstance();
        startWatched.setTimeInMillis(timestamp);
        startWatched.set(Calendar.HOUR_OF_DAY, 0);
        startWatched.set(Calendar.MINUTE, 0);
        startWatched.set(Calendar.SECOND, 0);
        startWatched.set(Calendar.MILLISECOND, 0);

        long diffDays = (startToday.getTimeInMillis() - startWatched.getTimeInMillis()) / DateUtils.DAY_IN_MILLIS;
        if (diffDays <= 0) return "Ditonton hari ini";
        if (diffDays == 1) return "Ditonton kemarin";
        if (diffDays < 5) return "Ditonton " + diffDays + " hari yang lalu";

        SimpleDateFormat formatter = new SimpleDateFormat("dd MMMM yyyy", new Locale("id", "ID"));
        return "Terakhir ditonton tanggal " + formatter.format(watched.getTime());
    }
}
