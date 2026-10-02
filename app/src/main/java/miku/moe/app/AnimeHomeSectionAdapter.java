package miku.moe.app;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;

public class AnimeHomeSectionAdapter extends RecyclerView.Adapter<AnimeHomeSectionAdapter.Holder> {
    public interface ActionListener {
        void onViewAll(AnimeHomeFragment.SourceSection section);
        void onAnimeClick(AnimeHomeFragment.SourceSection section, AnimePost post);
    }

    private final Context context;
    private final ArrayList<AnimeHomeFragment.SourceSection> sections;
    private final ActionListener listener;

    public AnimeHomeSectionAdapter(Context context, ArrayList<AnimeHomeFragment.SourceSection> sections, ActionListener listener) {
        this.context = context;
        this.sections = sections;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) { return sections.get(position).getSourceId().hashCode(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.anime_home_source_section, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        AnimeHomeFragment.SourceSection section = sections.get(position);
        holder.title.setText(section.getSourceLabel());
        holder.title.setTextColor(fetchTextColor());
        holder.viewAll.setVisibility(View.VISIBLE);
        holder.viewAll.setOnClickListener(v -> { if (listener != null) listener.onViewAll(section); });
        holder.empty.setVisibility(section.getItems().isEmpty() && !section.getLoading() ? View.VISIBLE : View.GONE);
        holder.list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        holder.list.setPadding(dp(12), 0, dp(12), 0);
        holder.list.setClipToPadding(false);
        holder.cardAdapter = new AnimeHomeCardAdapter(context, section.getItems(), post -> {
            if (listener != null) listener.onAnimeClick(section, post);
        });
        holder.list.setAdapter(holder.cardAdapter);
    }

    @Override public int getItemCount() { return sections.size(); }

    private int fetchTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, value, true)) return value.data;
        return Color.BLACK;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView title;
        View viewAll;
        TextView empty;
        RecyclerView list;
        AnimeHomeCardAdapter cardAdapter;
        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.sourceTitleTextView);
            viewAll = itemView.findViewById(R.id.viewAllTextView);
            empty = itemView.findViewById(R.id.emptyTextView);
            list = itemView.findViewById(R.id.animeRecyclerView);
        }
    }
}
