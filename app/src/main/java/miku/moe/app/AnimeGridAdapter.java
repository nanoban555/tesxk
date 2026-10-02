package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class AnimeGridAdapter extends BaseAdapter {
    public interface OnAnimeClickListener { void onAnimeClick(AnimePost animePost); }

    private final Context context;
    private final ArrayList<AnimePost> animePosts;
    private final OnAnimeClickListener listener;

    public AnimeGridAdapter(Context context, ArrayList<AnimePost> animePosts, OnAnimeClickListener listener) {
        this.context = context;
        this.animePosts = animePosts;
        this.listener = listener;
    }

    @Override public int getCount() { return animePosts.size(); }
    @Override public Object getItem(int position) { return animePosts.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) convertView = LayoutInflater.from(context).inflate(R.layout.grid_item, parent, false);

        ImageView imageView = convertView.findViewById(R.id.imageView);
        TextView categoryTextView = convertView.findViewById(R.id.textView);
        TextView episodeTitleTextView = convertView.findViewById(R.id.episodeTitleTextView);
        TextView metaTextView = convertView.findViewById(R.id.metaTextView);
        TextView resolutionTextView = convertView.findViewById(R.id.resolutionTextView);
        TextView statusTextView = convertView.findViewById(R.id.statusTextView);
        TextView dateTextView = convertView.findViewById(R.id.dateTextView);
        TextView viewsTextView = convertView.findViewById(R.id.viewsTextView);

        AnimePost animePost = animePosts.get(position);
        Glide.with(context.getApplicationContext()).load(animePost.imgUrl).centerCrop().into(imageView);

        categoryTextView.setText(clean(animePost.categoryName));
        episodeTitleTextView.setVisibility(View.GONE);
        episodeTitleTextView.setText("");
        metaTextView.setVisibility(View.GONE);
        metaTextView.setText("");
        resolutionTextView.setVisibility(View.GONE);
        resolutionTextView.setText("");
        statusTextView.setVisibility(View.GONE);
        statusTextView.setText("");
        dateTextView.setVisibility(View.GONE);
        dateTextView.setText("");
        viewsTextView.setVisibility(View.GONE);
        viewsTextView.setText("");

        convertView.setOnClickListener(v -> listener.onAnimeClick(animePost));
        return convertView;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String resolutionLabel(AnimePost post) {
        if (post.fhdAvailable) return "FHD 1080p";
        if (post.hdAvailable) return "HD 720p";
        return "";
    }

    private String formatViews(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        try {
            long value = Long.parseLong(raw.trim());
            return String.format(Locale.US, "%,d", value).replace(",", ".");
        } catch (Exception e) {
            return raw.trim();
        }
    }

    private String relativeTime(String created) {
        if (created == null || created.trim().isEmpty()) return "";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getDefault());
            Date date = sdf.parse(created.trim());
            if (date == null) return created;
            long diff = System.currentTimeMillis() - date.getTime();
            if (diff < 0) return "Baru saja";
            long minute = 60 * 1000L;
            long hour = 60 * minute;
            long day = 24 * hour;
            if (diff < minute) return "Baru saja";
            if (diff < hour) return (diff / minute) + " menit lalu";
            if (diff < day) return (diff / hour) + " jam lalu";
            if (diff < 30 * day) return (diff / day) + " hari lalu";
            SimpleDateFormat out = new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));
            return out.format(date);
        } catch (Exception e) {
            return created;
        }
    }

    private String dayName(int day) {
        switch (day) {
            case 1: return "Senin";
            case 2: return "Selasa";
            case 3: return "Rabu";
            case 4: return "Kamis";
            case 5: return "Jumat";
            case 6: return "Sabtu";
            case 7: return "Minggu";
            default: return "Tertunda";
        }
    }
}
