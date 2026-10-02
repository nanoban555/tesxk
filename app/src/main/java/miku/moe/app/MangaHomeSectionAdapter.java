package miku.moe.app;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import java.util.ArrayList;

public class MangaHomeSectionAdapter extends RecyclerView.Adapter<MangaHomeSectionAdapter.Holder> {
    public interface ActionListener {
        void onViewAll(MangaHomeFragment.SourceSection section);
        void onResolveCloudflare(MangaHomeFragment.SourceSection section);
        void onMangaClick(MangaPost post);
        void onChapterClick(MangaPost post);
    }

    private static final float DPI_600 = 600f;
    private final Context context;
    private final ArrayList<MangaHomeFragment.SourceSection> sections;
    private final ActionListener listener;

    public MangaHomeSectionAdapter(Context context, ArrayList<MangaHomeFragment.SourceSection> sections, ActionListener listener) {
        this.context = context;
        this.sections = sections;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) { return sections.get(position).sourceId.hashCode(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.manga_home_source_section, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        applyDpi600Section(holder);
        MangaHomeFragment.SourceSection section = sections.get(position);
        if (section.cloudflareRequired) {
            holder.title.setText("🆘 Harap selesaikan Cloudflare pada Source Manga " + section.sourceLabel + " 🆘");
            holder.title.setTextColor(Color.RED);
            holder.title.setClickable(listener != null);
            holder.title.setOnClickListener(v -> { if (listener != null) listener.onResolveCloudflare(section); });
            holder.viewAll.setVisibility(View.GONE);
        } else {
            holder.title.setText(section.sourceLabel);
            holder.title.setTextColor(fetchTextColor());
            holder.title.setClickable(false);
            holder.title.setOnClickListener(null);
            holder.viewAll.setVisibility(section.loading || section.items.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (holder.sourceLoadingProgress != null) holder.sourceLoadingProgress.setVisibility(section.loading && !section.cloudflareRequired ? View.VISIBLE : View.GONE);
        String emptyMessage = section.errorMessage == null || section.errorMessage.trim().isEmpty() ? "Data belum tersedia" : section.errorMessage.trim();
        holder.empty.setText(emptyMessage);
        holder.empty.setVisibility(section.items.isEmpty() && !section.loading && !section.cloudflareRequired ? View.VISIBLE : View.GONE);
        holder.list.setVisibility(section.items.isEmpty() ? View.GONE : View.VISIBLE);
        holder.viewAll.setOnClickListener(v -> { if (listener != null) listener.onViewAll(section); });
        holder.list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        holder.list.setItemAnimator(null);
        holder.list.setItemViewCacheSize(12);
        holder.cardAdapter = new MangaHomeCardAdapter(context, section.items, new MangaHomeCardAdapter.Listener() {
            @Override public void onClick(MangaPost post) { if (listener != null) listener.onMangaClick(post); }
            @Override public void onChapterClick(MangaPost post) { if (listener != null) listener.onChapterClick(post); }
        });
        holder.list.setAdapter(holder.cardAdapter);
    }

    @Override public int getItemCount() { return sections.size(); }

    private void applyDpi600Section(Holder holder) {
        if (holder.itemView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams itemParams = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
            itemParams.setMargins(dpi600(18), dpi600(10), dpi600(18), dpi600(10));
            holder.itemView.setLayoutParams(itemParams);
        }
        if (holder.itemView instanceof MaterialCardView) ((MaterialCardView) holder.itemView).setRadius(dpi600(72));
        if (holder.content != null) holder.content.setPadding(0, dpi600(22), 0, dpi600(18));
        if (holder.header != null) holder.header.setPadding(dpi600(28), 0, dpi600(26), 0);
        holder.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dpi600(50));
        holder.title.setMaxLines(2);
        if (holder.viewAll.getLayoutParams() != null) {
            ViewGroup.LayoutParams viewAllParams = holder.viewAll.getLayoutParams();
            viewAllParams.width = dpi600(118);
            viewAllParams.height = dpi600(118);
            holder.viewAll.setLayoutParams(viewAllParams);
        }
        holder.viewAll.setPadding(dpi600(18), dpi600(18), dpi600(18), dpi600(18));
        if (holder.list.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams listParams = (ViewGroup.MarginLayoutParams) holder.list.getLayoutParams();
            listParams.topMargin = dpi600(14);
            holder.list.setLayoutParams(listParams);
        }
        holder.list.setPadding(dpi600(12), 0, dpi600(12), 0);
        holder.empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, dpi600(42));
        holder.empty.setPadding(dpi600(48), dpi600(30), dpi600(48), 0);
        if (holder.sourceLoadingProgress != null && holder.sourceLoadingProgress.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams loadingParams = (ViewGroup.MarginLayoutParams) holder.sourceLoadingProgress.getLayoutParams();
            loadingParams.width = dpi600(84);
            loadingParams.height = dpi600(84);
            loadingParams.topMargin = dpi600(42);
            loadingParams.bottomMargin = dpi600(24);
            holder.sourceLoadingProgress.setLayoutParams(loadingParams);
        }
    }

    private int dpi600(int value) {
        int densityDpi = context == null || context.getResources() == null ? 600 : context.getResources().getDisplayMetrics().densityDpi;
        return Math.max(1, Math.round(value * densityDpi / DPI_600));
    }

    private int fetchTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)) return value.data;
        return Color.BLACK;
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView title;
        View viewAll;
        TextView empty;
        RecyclerView list;
        ViewGroup content;
        ViewGroup header;
        ProgressBar sourceLoadingProgress;
        MangaHomeCardAdapter cardAdapter;
        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.sourceTitleTextView);
            viewAll = itemView.findViewById(R.id.viewAllTextView);
            empty = itemView.findViewById(R.id.emptyTextView);
            list = itemView.findViewById(R.id.mangaRecyclerView);
            sourceLoadingProgress = itemView.findViewById(R.id.sourceLoadingProgress);
            content = itemView instanceof ViewGroup && ((ViewGroup) itemView).getChildCount() > 0 && ((ViewGroup) itemView).getChildAt(0) instanceof ViewGroup ? (ViewGroup) ((ViewGroup) itemView).getChildAt(0) : null;
            header = title != null && title.getParent() instanceof ViewGroup ? (ViewGroup) title.getParent() : null;
        }
    }
}
