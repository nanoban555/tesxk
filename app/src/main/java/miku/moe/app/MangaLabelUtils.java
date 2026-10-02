package miku.moe.app;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

public final class MangaLabelUtils {
    private MangaLabelUtils() {}

    public static String typeLabel(MangaPost post) {
        if (post == null) return "";
        String resolved = typeForFlag(post);
        if (!resolved.isEmpty()) return resolved;
        return normalizeStoredType(post.getTypeLabel(), (post.genre == null ? "" : post.genre) + " " + (post.status == null ? "" : post.status) + " " + (post.info == null ? "" : post.info) + " " + (post.synopsis == null ? "" : post.synopsis));
    }

    public static void bindType(TextView view, MangaPost post, Context context, boolean respectSettings) {
        if (view == null) return;
        String text = typeLabel(post);
        boolean hide = respectSettings && context != null && MangaSettingsManager.shouldHideTypeLabel(context);
        if (hide || text.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(text);
        }
    }

    public static void bindChapter(TextView view, CharSequence text, Context context, boolean respectSettings) {
        if (view == null) return;
        boolean hide = respectSettings && MangaSettingsManager.shouldHideLatestChapterLabel(context);
        if (hide || text == null || text.toString().trim().isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(text instanceof String ? text.toString().trim() : text);
        }
    }

    public static CharSequence favoriteChapterIncreaseLabel(int base, int added) {
        if (base <= 0 || added <= 0) return "";
        String text = base + "+" + added;
        SpannableString spannable = new SpannableString(text);
        int start = String.valueOf(base).length();
        spannable.setSpan(new ForegroundColorSpan(0xFFFF2D2D), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    public static void bindTypeFlag(android.widget.ImageView view, MangaPost post, Context context, boolean respectSettings) {
        if (view == null) return;
        if (respectSettings && context != null && MangaSettingsManager.shouldHideTypeLabel(context)) {
            view.setVisibility(View.GONE);
            view.setImageDrawable(null);
            return;
        }
        int res = typeFlagResource(typeLabel(post));
        if (res == 0) {
            view.setVisibility(View.GONE);
            view.setImageDrawable(null);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setImageResource(res);
    }

    public static String typeForFlag(MangaPost post) {
        if (post == null) return "";
        String raw = post.typeLabel == null ? "" : post.typeLabel.trim();
        String support = (post.genre == null ? "" : post.genre) + " " + (post.status == null ? "" : post.status) + " " + (post.info == null ? "" : post.info) + " " + (post.synopsis == null ? "" : post.synopsis);
        String supportType = normalizeTypeStrict(support, false);
        String rawType = normalizeTypeStrict(raw, true);
        if ("MANHWA".equals(supportType) || "MANHUA".equals(supportType)) return supportType;
        if ("MANHWA".equals(rawType) || "MANHUA".equals(rawType)) return rawType;
        String getterType = normalizeTypeStrict(post.getTypeLabel(), true);
        if (rawType.isEmpty() && !getterType.isEmpty()) rawType = getterType;
        if (rawType.isEmpty()) return supportType;
        if ("MANGA".equals(rawType) && !supportType.isEmpty()) return supportType;
        return rawType;
    }

    public static boolean isSpecificCountryType(String type) {
        String normalized = normalizeTypeStrict(type, true);
        return "MANHWA".equals(normalized) || "MANHUA".equals(normalized);
    }

    public static String normalizeStoredType(String type, String fallbackText) {
        String direct = normalizeTypeStrict(type, true);
        String support = normalizeTypeStrict(fallbackText, false);
        if ("MANHWA".equals(support) || "MANHUA".equals(support)) return support;
        if ("MANHWA".equals(direct) || "MANHUA".equals(direct)) return direct;
        if (!direct.isEmpty()) return direct;
        return support;
    }

    public static int typeFlagResource(String type) {
        if ("MANHUA".equals(type)) return R.drawable.ic_flag_china;
        if ("MANHWA".equals(type) || "WEBTOON".equals(type)) return R.drawable.ic_flag_korea;
        if ("MANGA".equals(type) || "DOUJINSHI".equals(type) || "DOUJIN".equals(type) || "ONESHOT".equals(type) || "IMAGE-SET".equals(type)) return R.drawable.ic_flag_japan;
        return 0;
    }

    private static String normalizeTypeStrict(String raw, boolean trustManga) {
        String padded = " " + (raw == null ? "" : raw).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        if (padded.trim().isEmpty()) return "";
        if (padded.contains(" manhwa ") || padded.contains(" korean ") || padded.contains(" korea ") || padded.contains(" korea selatan ") || padded.contains(" south korea ") || padded.contains(" kr ")) return "MANHWA";
        if (padded.contains(" manhua ") || padded.contains(" chinese ") || padded.contains(" china ") || padded.contains(" cina ") || padded.contains(" tiongkok ") || padded.contains(" cn ")) return "MANHUA";
        if (padded.contains(" webtoon ") || padded.contains(" web toon ")) return "MANHWA";
        if (padded.contains(" image set ") || padded.contains(" imageset ")) return "IMAGE-SET";
        if (padded.contains(" doujinshi ")) return "DOUJINSHI";
        if (padded.contains(" doujin ")) return "DOUJIN";
        if (padded.contains(" oneshot ") || padded.contains(" one shot ")) return "ONESHOT";
        if (padded.contains(" manga ") || padded.contains(" japan ") || padded.contains(" japanese ") || padded.contains(" jepang ") || padded.contains(" jp ") || padded.contains(" doujin ")) return trustManga ? "MANGA" : "";
        return "";
    }

    public static void applyHiddenLabels(Context context, MangaPost post) {
        if (post == null) return;
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel(context)) {
            post.latestChapter = "";
            post.latestChapterDate = "";
            post.totalChapters = 0;
        }
        if (!MangaSettingsManager.shouldLoadTypeLabel(context)) post.typeLabel = "";
    }

    public static boolean shouldEnrichLabels(Context context) {
        return MangaSettingsManager.shouldLoadLatestChapterLabel(context) || MangaSettingsManager.shouldLoadTypeLabel(context);
    }
}
