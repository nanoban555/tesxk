package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public class EpisodeAdapter extends ArrayAdapter<Episode> {
    public EpisodeAdapter(Context context, ArrayList<Episode> episodes) {
        super(context, 0, episodes);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Episode episode = getItem(position);

        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.episode_item, parent, false);
        }

        TextView channelNameTextView = convertView.findViewById(R.id.channelNameTextView);
        TextView watchProgressTextView = convertView.findViewById(R.id.watchProgressTextView);
        ProgressBar episodeProgressBar = convertView.findViewById(R.id.episodeProgressBar);

        channelNameTextView.setText(episode == null ? "Episode" : episode.channelName);

        HistoryItem history = episode == null ? null : HistoryManager.getByChannelId(getContext(), episode.channelId);
        if (history != null && (history.position > 0 || history.duration > 0)) {
            long duration = Math.max(0L, history.duration);
            long watchedPosition = duration > 0 ? Math.min(Math.max(history.position, 0L), duration) : Math.max(0L, history.position);
            boolean completed = duration > 0 && watchedPosition >= duration - 5000L;
            watchProgressTextView.setVisibility(View.VISIBLE);
            episodeProgressBar.setVisibility(duration > 0 ? View.VISIBLE : View.GONE);
            watchProgressTextView.setText(completed ? "Selesai ditonton" : "Terakhir ditonton: " + formatTime(watchedPosition) + (duration > 0 ? " / " + formatTime(duration) : ""));
            if (duration > 0) {
                int progress = completed ? 100 : (int) Math.max(1, Math.min(100, (watchedPosition * 100L) / duration));
                episodeProgressBar.setProgress(progress);
            }
        } else {
            watchProgressTextView.setVisibility(View.GONE);
            episodeProgressBar.setVisibility(View.GONE);
            episodeProgressBar.setProgress(0);
        }

        return convertView;
    }

    private String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }
}
