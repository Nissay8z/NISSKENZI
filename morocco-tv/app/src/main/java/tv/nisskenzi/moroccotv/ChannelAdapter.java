package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RecyclerView adapter for the live-channel list.
 *
 * Supports smart channel folders with multiple sources, resolution badges,
 * asynchronous logo loading, and D-pad navigation on Android TV.
 */
public final class ChannelAdapter extends ListAdapter<Channel, ChannelAdapter.ChannelViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(Channel channel);
    }

    public interface FavoriteChecker {
        boolean isFavorite(Channel channel);
    }

    private static final DiffUtil.ItemCallback<Channel> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Channel>() {
                @Override
                public boolean areItemsTheSame(@NonNull Channel oldItem,
                                               @NonNull Channel newItem) {
                    return TextUtils.equals(safe(oldItem.url), safe(newItem.url));
                }

                @Override
                public boolean areContentsTheSame(@NonNull Channel oldItem,
                                                  @NonNull Channel newItem) {
                    return TextUtils.equals(safe(oldItem.name), safe(newItem.name))
                            && TextUtils.equals(safe(oldItem.cleanTitle), safe(newItem.cleanTitle))
                            && TextUtils.equals(safe(oldItem.logo), safe(newItem.logo))
                            && TextUtils.equals(safe(oldItem.group), safe(newItem.group))
                            && TextUtils.equals(safe(oldItem.country), safe(newItem.country))
                            && oldItem.isVavoo == newItem.isVavoo
                            && oldItem.getSourceCount() == newItem.getSourceCount()
                            && TextUtils.equals(safe(oldItem.vavooSourceUrl), safe(newItem.vavooSourceUrl));
                }
            };

    private static final Map<String, String> COUNTRY_CODES = buildCountryCodes();

    private final OnChannelClickListener listener;
    private final FavoriteChecker favoriteChecker;
    private final ChannelLogoLoader logoLoader = new ChannelLogoLoader();
    private String selectedUrl = "";
    private volatile boolean shutDown;

    public ChannelAdapter(@NonNull OnChannelClickListener listener,
                          @NonNull FavoriteChecker favoriteChecker) {
        super(DIFF_CALLBACK);
        this.listener = listener;
        this.favoriteChecker = favoriteChecker;
    }

    /** Updates the highlighted channel without rebinding the entire catalogue. */
    public void setSelectedUrl(@Nullable String url) {
        String next = safe(url);
        if (TextUtils.equals(selectedUrl, next)) return;

        int oldPosition = findPosition(selectedUrl);
        selectedUrl = next;
        int newPosition = findPosition(selectedUrl);

        if (oldPosition >= 0) notifyItemChanged(oldPosition);
        if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition);
    }

    /** The favorite toggle is attached to the selected channel; refresh only that row. */
    public void refreshDecorations() {
        int position = findPosition(selectedUrl);
        if (position >= 0) notifyItemChanged(position, "decoration");
    }

    private int findPosition(String url) {
        if (url == null || url.isEmpty()) return -1;
        Set<String> set = Collections.singleton(url);
        for (int i = 0; i < getCurrentList().size(); i++) {
            Channel ch = getItem(i);
            if (ch != null && ch.matchesAnyUrl(set)) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        boolean television = isTelevision(context);
        int rowHeight = dp(context, television ? 66 : 56);
        int bottomMargin = dp(context, 5);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setClickable(true);
        card.setPadding(dp(context, television ? 11 : 8), dp(context, 6),
                dp(context, 9), dp(context, 6));
        card.setBackground(roundedDrawable(0xFF1D2632, 8, 0x00FFFFFF, context));
        RecyclerView.LayoutParams cardParams =
                new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
        cardParams.bottomMargin = bottomMargin;
        card.setLayoutParams(cardParams);

        TextView number = new TextView(context);
        number.setTextSize(television ? 15 : 12);
        number.setTextColor(Color.WHITE);
        number.setGravity(Gravity.CENTER);
        number.setIncludeFontPadding(false);
        card.addView(number, new LinearLayout.LayoutParams(dp(context, television ? 30 : 26),
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout logoFrame = new FrameLayout(context);
        logoFrame.setBackground(roundedDrawable(0xEEFFFFFF, 7, 0x00FFFFFF, context));
        TextView initials = new TextView(context);
        initials.setTextSize(10);
        initials.setTextColor(0xFF168AAD);
        initials.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        initials.setGravity(Gravity.CENTER);
        logoFrame.addView(initials, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
        logoFrame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                dp(context, television ? 44 : 38), dp(context, television ? 44 : 38));
        logoParams.rightMargin = dp(context, 10);
        card.addView(logoFrame, logoParams);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(context);
        name.setTextSize(television ? 14 : 11);
        name.setTextColor(Color.WHITE);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView qualityBadge = new TextView(context);
        qualityBadge.setTextSize(television ? 9 : 8);
        qualityBadge.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        qualityBadge.setGravity(Gravity.CENTER);
        qualityBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams qbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qbParams.leftMargin = dp(context, 6);
        titleRow.addView(qualityBadge, qbParams);

        TextView folderBadge = new TextView(context);
        folderBadge.setTextSize(television ? 9 : 8);
        folderBadge.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        folderBadge.setGravity(Gravity.CENTER);
        folderBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams fbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fbParams.leftMargin = dp(context, 5);
        titleRow.addView(folderBadge, fbParams);

        TextView meta = new TextView(context);
        meta.setTextSize(television ? 9 : 8);
        meta.setTextColor(0xEEFFFFFF);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(context, 2);
        info.addView(meta, metaParams);

        TextView favorite = new TextView(context);
        favorite.setText("★");
        favorite.setTextSize(television ? 15 : 12);
        favorite.setTextColor(0xFFFFFF00);
        favorite.setGravity(Gravity.CENTER);
        favorite.setVisibility(View.GONE);
        card.addView(favorite, new LinearLayout.LayoutParams(dp(context, 22),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return new ChannelViewHolder(card, number, image, initials, name, qualityBadge, folderBadge, meta, favorite);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        Channel channel = getItem(position);
        boolean isSelected = isChannelSelected(channel);
        holder.bind(channel, position, isSelected, listener, favoriteChecker, logoLoader, this);
    }

    public boolean isChannelSelected(Channel channel) {
        if (channel == null || selectedUrl == null || selectedUrl.isEmpty()) return false;
        return channel.matchesAnyUrl(Collections.singleton(selectedUrl));
    }

    @Override
    public void onViewRecycled(@NonNull ChannelViewHolder holder) {
        holder.reset();
        super.onViewRecycled(holder);
    }

    public void shutdown() {
        shutDown = true;
        logoLoader.close();
    }

    static final class ChannelViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView number;
        final ImageView image;
        final TextView initials;
        final TextView name;
        final TextView qualityBadge;
        final TextView folderBadge;
        final TextView meta;
        final TextView favorite;
        private Channel boundChannel;
        private ChannelLogoLoader.Request logoRequest;

        ChannelViewHolder(LinearLayout card, TextView number, ImageView image,
                          TextView initials, TextView name, TextView qualityBadge,
                          TextView folderBadge, TextView meta, TextView favorite) {
            super(card);
            this.card = card;
            this.number = number;
            this.image = image;
            this.initials = initials;
            this.name = name;
            this.qualityBadge = qualityBadge;
            this.folderBadge = folderBadge;
            this.meta = meta;
            this.favorite = favorite;
        }

        void bind(Channel channel, int index, boolean selected,
                  OnChannelClickListener listener, FavoriteChecker favoriteChecker,
                  ChannelLogoLoader loader, ChannelAdapter owner) {
            if (logoRequest != null) {
                logoRequest.cancel();
                logoRequest = null;
            }
            boundChannel = channel;
            Context context = card.getContext();
            number.setText(String.valueOf(index + 1));

            // Titre propre et élégant (ex: "TF1", "CANAL+ CINEMA")
            String displayName = !channel.cleanTitle.isEmpty() ? channel.cleanTitle : channel.name;
            name.setText(emptyFallback(displayName, "Chaîne"));

            // Badge de qualité (4K, FHD, HD)
            String bestQuality = channel.getBestQualityBadge();
            if (!bestQuality.isEmpty()) {
                qualityBadge.setVisibility(View.VISIBLE);
                qualityBadge.setText(bestQuality);
                if ("4K".equals(bestQuality)) {
                    qualityBadge.setBackground(roundedDrawable(0xFF7B2CBF, 4, 0xFF9D4EDD, context));
                    qualityBadge.setTextColor(0xFFFFFFFF);
                } else if ("FHD".equals(bestQuality)) {
                    qualityBadge.setBackground(roundedDrawable(0xFF0F4C5C, 4, 0xFF4CC9F0, context));
                    qualityBadge.setTextColor(0xFF4CC9F0);
                } else if ("HD".equals(bestQuality)) {
                    qualityBadge.setBackground(roundedDrawable(0xFF1E293B, 4, 0xFF38BDF8, context));
                    qualityBadge.setTextColor(0xFF38BDF8);
                } else {
                    qualityBadge.setBackground(roundedDrawable(0xFF1E293B, 4, 0x00FFFFFF, context));
                    qualityBadge.setTextColor(0xFFB0BEC5);
                }
                qualityBadge.setPadding(dp(context, 5), dp(context, 1), dp(context, 5), dp(context, 1));
            } else {
                qualityBadge.setVisibility(View.GONE);
            }

            // Dossier de chaîne : badge de flux multiples
            if (channel.hasMultipleSources()) {
                folderBadge.setVisibility(View.VISIBLE);
                folderBadge.setText("📁 " + channel.getSourceCount() + " flux");
                folderBadge.setBackground(roundedDrawable(0xFF143042, 4, 0xFF2A6B8A, context));
                folderBadge.setTextColor(0xFF4CC9F0);
                folderBadge.setPadding(dp(context, 6), dp(context, 1), dp(context, 6), dp(context, 1));
            } else {
                folderBadge.setVisibility(View.GONE);
            }

            String country = emptyFallback(channel.country, "Pays non précisé");
            String group = safe(channel.group)
                    .replace("VAVOO · ", "")
                    .replace("IPTV-ORG · ", "");

            String metaText;
            if (channel.hasMultipleSources()) {
                metaText = countryFlag(country) + "  " + country
                        + "  ·  📁 " + channel.getSourceCount() + " sources (Meilleure: "
                        + (bestQuality.isEmpty() ? "Auto" : bestQuality) + ")";
            } else {
                metaText = countryFlag(country) + "  " + country
                        + (group.isEmpty() ? "" : "  ·  " + group);
            }
            meta.setText(metaText);

            boolean isFavorite = favoriteChecker != null && favoriteChecker.isFavorite(channel);
            favorite.setVisibility(isFavorite ? View.VISIBLE : View.GONE);

            image.setTag(safe(channel.logo));
            image.setImageDrawable(null);
            image.setVisibility(View.VISIBLE);
            initials.setText(makeInitials(displayName));
            initials.setVisibility(View.VISIBLE);

            String logoUrl = safe(channel.logo);
            Bitmap cached = loader.getCached(logoUrl);
            if (!logoUrl.isEmpty() && cached != null) {
                image.setImageBitmap(cached);
                initials.setVisibility(View.GONE);
            } else if (!logoUrl.isEmpty()) {
                logoRequest = loader.load(logoUrl, bitmap -> image.post(() -> {
                    if (loader.isClosed() || !logoUrl.equals(image.getTag())) return;
                    image.setImageBitmap(bitmap);
                    image.setVisibility(View.VISIBLE);
                    initials.setVisibility(View.GONE);
                }));
            } else {
                image.setImageDrawable(null);
            }

            card.setOnFocusChangeListener((view, hasFocus) ->
                    applyState(hasFocus, owner.isChannelSelected(boundChannel)));
            card.setOnClickListener(view -> {
                if (listener != null) listener.onChannelClick(channel);
            });
            applyState(card.hasFocus(), selected);
        }

        void reset() {
            if (logoRequest != null) {
                logoRequest.cancel();
                logoRequest = null;
            }
            boundChannel = null;
            image.setTag(null);
            image.setImageDrawable(null);
            initials.setVisibility(View.VISIBLE);
            card.setOnFocusChangeListener(null);
            card.setOnClickListener(null);
        }

        private void applyState(boolean focused, boolean selected) {
            Context context = itemView.getContext();
            boolean active = focused || selected;
            card.setBackground(roundedDrawable(active ? 0xFF164A60 : 0xFF1D2632,
                    8, active ? 0xFF4CC9F0 : 0x00FFFFFF, context));
            card.setScaleX(focused ? 1.02f : 1f);
            card.setScaleY(focused ? 1.02f : 1f);
            name.setTextColor(active ? 0xFFFFFF00 : Color.WHITE);
            number.setTextColor(active ? 0xFFFFFF00 : Color.WHITE);
        }
    }

    private static GradientDrawable roundedDrawable(int color, int radiusDp, int strokeColor,
                                                    Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static boolean isTelevision(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private static String makeInitials(String name) {
        String value = safe(name).trim();
        if (value.isEmpty()) return "TV";
        String[] words = value.split("\\s+");
        if (words.length > 1 && !words[0].isEmpty() && !words[1].isEmpty()) {
            return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        return value.substring(0, Math.min(3, value.length())).toUpperCase(Locale.ROOT);
    }

    private static String countryFlag(String country) {
        String code = COUNTRY_CODES.get(countryKey(country));
        if (code == null || code.length() != 2) return "🌐";
        int first = 0x1F1E6 + code.charAt(0) - 'A';
        int second = 0x1F1E6 + code.charAt(1) - 'A';
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    private static Map<String, String> buildCountryCodes() {
        HashMap<String, String> result = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            Locale locale = new Locale("", code);
            result.put(countryKey(code), code);
            result.put(countryKey(locale.getDisplayCountry(Locale.FRENCH)), code);
            result.put(countryKey(locale.getDisplayCountry(Locale.ENGLISH)), code);
        }
        result.put(countryKey("UK"), "GB");
        result.put(countryKey("USA"), "US");
        result.put(countryKey("États-Unis"), "US");
        result.put(countryKey("Royaume-Uni"), "GB");
        result.put(countryKey("Côte d’Ivoire"), "CI");
        result.put(countryKey("Corée du Sud"), "KR");
        result.put(countryKey("Corée du Nord"), "KP");
        result.put(countryKey("Viêt Nam"), "VN");
        result.put(countryKey("Tchéquie"), "CZ");
        result.put(countryKey("Russie"), "RU");
        result.put(countryKey("Taïwan"), "TW");
        result.put(countryKey("Kosovo"), "XK");
        result.put(countryKey("Palestine"), "PS");
        return result;
    }

    private static String countryKey(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String emptyFallback(String value, String fallback) {
        String clean = safe(value);
        return clean.isEmpty() ? fallback : clean;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
