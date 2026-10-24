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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RecyclerView adapter for the live-channel list.
 *
 * Reuses row views, loads logos away from the UI thread, bounds the logo cache,
 * and keeps selection/focus styling compatible with an Android TV D-pad.
 */
public final class ChannelAdapter extends ListAdapter<Channel, ChannelAdapter.ChannelViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(Channel channel);
    }

    public interface FavoriteChecker {
        boolean isFavorite(Channel channel);
    }

    public interface OnChannelFocusListener {
        void onChannelFocus(Channel channel);
    }

    private static final DiffUtil.ItemCallback<Channel> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Channel>() {
                @Override
                public boolean areItemsTheSame(@NonNull Channel oldItem,
                                               @NonNull Channel newItem) {
                    return TextUtils.equals(oldItem.identity(), newItem.identity());
                }

                @Override
                public boolean areContentsTheSame(@NonNull Channel oldItem,
                                                  @NonNull Channel newItem) {
                    return TextUtils.equals(safe(oldItem.name), safe(newItem.name))
                            && TextUtils.equals(safe(oldItem.logo), safe(newItem.logo))
                            && TextUtils.equals(safe(oldItem.group), safe(newItem.group))
                            && TextUtils.equals(safe(oldItem.country), safe(newItem.country))
                            && oldItem.isVavoo == newItem.isVavoo
                            && TextUtils.equals(safe(oldItem.vavooSourceUrl), safe(newItem.vavooSourceUrl))
                            && TextUtils.equals(safe(oldItem.url), safe(newItem.url))
                            && oldItem.sourceCount() == newItem.sourceCount();
                }
            };

    private static final Map<String, String> COUNTRY_CODES = buildCountryCodes();

    private final OnChannelClickListener listener;
    private final OnChannelClickListener sourceChoiceListener;
    private final FavoriteChecker favoriteChecker;
    private OnChannelFocusListener focusListener;
    private final ChannelLogoLoader logoLoader = new ChannelLogoLoader();
    private String selectedUrl = "";
    private final Map<String, Integer> positionByUrl = new HashMap<>();
    private volatile boolean shutDown;

    public ChannelAdapter(@NonNull OnChannelClickListener listener,
                          @NonNull FavoriteChecker favoriteChecker) {
        this(listener, favoriteChecker, null);
    }

    public ChannelAdapter(@NonNull OnChannelClickListener listener,
                          @NonNull FavoriteChecker favoriteChecker,
                          @Nullable OnChannelClickListener sourceChoiceListener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
        this.favoriteChecker = favoriteChecker;
        this.sourceChoiceListener = sourceChoiceListener;
    }

    public void setFocusListener(@Nullable OnChannelFocusListener listener) {
        focusListener = listener;
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
        Integer position = positionByUrl.get(url);
        return position == null ? -1 : position;
    }

    @Override
    public void onCurrentListChanged(@NonNull List<Channel> previousList,
                                        @NonNull List<Channel> currentList) {
        positionByUrl.clear();
        for (int i = 0; i < currentList.size(); i++) {
            String url = currentList.get(i).identity();
            if (!url.isEmpty() && !positionByUrl.containsKey(url)) positionByUrl.put(url, i);
        }
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        boolean television = isTelevision(context);
        int rowHeight = dp(context, television ? 82 : 66);
        int bottomMargin = dp(context, television ? 7 : 5);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setClickable(true);
        card.setPadding(dp(context, television ? 14 : 9), dp(context, 7),
                dp(context, television ? 13 : 9), dp(context, 7));
        card.setBackground(roundedDrawable(0xFF1D2028, 13, 0x18FFFFFF, context));
        RecyclerView.LayoutParams cardParams =
                new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
        cardParams.bottomMargin = bottomMargin;
        card.setLayoutParams(cardParams);

        TextView number = new TextView(context);
        number.setTextSize(television ? 15 : 12);
        number.setTextColor(0xFFB8BBC5);
        number.setGravity(Gravity.CENTER);
        number.setIncludeFontPadding(false);
        card.addView(number, new LinearLayout.LayoutParams(dp(context, television ? 34 : 27),
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout logoFrame = new FrameLayout(context);
        logoFrame.setBackground(roundedDrawable(0xEEFFFFFF, 7, 0x00FFFFFF, context));
        TextView initials = new TextView(context);
        initials.setTextSize(10);
        initials.setTextColor(0xFF9D3445);
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
                dp(context, television ? 50 : 40), dp(context, television ? 50 : 40));
        logoParams.rightMargin = dp(context, 13);
        card.addView(logoFrame, logoParams);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView name = new TextView(context);
        name.setTextSize(television ? 15 : 11);
        name.setTextColor(Color.WHITE);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(context);
        meta.setTextSize(television ? 9 : 8);
        meta.setTextColor(0xFFB9BBC5);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(context, 2);
        info.addView(meta, metaParams);

        TextView dossier = new TextView(context);
        dossier.setTextSize(television ? 9 : 7);
        dossier.setTextColor(Color.WHITE);
        dossier.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        dossier.setGravity(Gravity.CENTER);
        dossier.setBackground(roundedDrawable(0xFF37232D, 12, 0x66EA5265, context));
        dossier.setContentDescription("Ouvrir le dossier des flux réunis");
        dossier.setFocusable(true);
        dossier.setVisibility(View.GONE);
        LinearLayout.LayoutParams dossierParams = new LinearLayout.LayoutParams(
                dp(context, television ? 106 : 78), dp(context, television ? 38 : 30));
        dossierParams.rightMargin = dp(context, 8);
        card.addView(dossier, dossierParams);

        TextView favorite = new TextView(context);
        favorite.setText("★");
        favorite.setTextSize(television ? 15 : 12);
        favorite.setTextColor(0xFFFFE8EA);
        favorite.setGravity(Gravity.CENTER);
        favorite.setVisibility(View.GONE);
        card.addView(favorite, new LinearLayout.LayoutParams(dp(context, 22),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return new ChannelViewHolder(card, number, image, initials, name, meta, favorite, dossier);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        Channel channel = getItem(position);
        holder.bind(channel, position, TextUtils.equals(selectedUrl, channel.identity()),
                listener, sourceChoiceListener, favoriteChecker, logoLoader, this);
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
        final TextView meta;
        final TextView favorite;
        final TextView dossier;
        private String boundUrl = "";
        private ChannelLogoLoader.Request logoRequest;

        ChannelViewHolder(LinearLayout card, TextView number, ImageView image,
                          TextView initials, TextView name, TextView meta,
                          TextView favorite, TextView dossier) {
            super(card);
            this.card = card;
            this.number = number;
            this.image = image;
            this.initials = initials;
            this.name = name;
            this.meta = meta;
            this.favorite = favorite;
            this.dossier = dossier;
        }

        void bind(Channel channel, int index, boolean selected,
                  OnChannelClickListener listener, OnChannelClickListener sourceChoiceListener,
                  FavoriteChecker favoriteChecker, ChannelLogoLoader loader, ChannelAdapter owner) {
            if (logoRequest != null) {
                logoRequest.cancel();
                logoRequest = null;
            }
            boundUrl = channel.identity();
            number.setText(String.valueOf(index + 1));
            name.setText(emptyFallback(channel.name, "Chaîne"));

            String country = emptyFallback(channel.country, "Pays non précisé");
            String provider = channel.isVavoo ? "VAVOO" : "IPTV-ORG";
            String metaText = countryFlag(country) + "  " + country
                    + "  ·  " + provider + " recommandé"
                    + (channel.sourceCount() > 1
                        ? "  ·  " + channel.sourceCount() + " flux" : "");
            meta.setText(metaText);

            boolean isFavorite = favoriteChecker != null && favoriteChecker.isFavorite(channel);
            favorite.setVisibility(isFavorite ? View.VISIBLE : View.GONE);
            dossier.setVisibility(channel.sourceCount() > 1 ? View.VISIBLE : View.GONE);
            dossier.setText("▤  Dossier (" + channel.sourceCount() + ")");
            dossier.setContentDescription("Ouvrir le mini dossier des " + channel.sourceCount()
                    + " flux de " + channel.name);
            dossier.setOnClickListener(view -> {
                if (sourceChoiceListener != null && channel.sourceCount() > 1) {
                    sourceChoiceListener.onChannelClick(channel);
                }
            });
            dossier.setOnFocusChangeListener((view, focused) -> {
                dossier.setBackground(roundedDrawable(focused ? 0xFF542A34 : 0xFF37232D,
                        12, focused ? 0xFFFFA1AD : 0x66EA5265, dossier.getContext()));
                applyState(focused || card.hasFocus(), selected);
            });

            image.setTag(safe(channel.logo));
            image.setImageDrawable(null);
            image.setVisibility(View.VISIBLE);
            initials.setText(makeInitials(channel.name));
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

            card.setOnFocusChangeListener((view, hasFocus) -> {
                applyState(hasFocus, owner.isSelected(boundUrl));
                if (hasFocus && owner.focusListener != null) {
                    owner.focusListener.onChannelFocus(channel);
                }
            });
            card.setOnClickListener(view -> {
                if (listener != null) listener.onChannelClick(channel);
            });
            card.setContentDescription(channel.name + ", " + country
                    + ", source recommandée " + provider
                    + (channel.sourceCount() > 1
                        ? ", " + channel.sourceCount() + " flux, maintenir pour choisir" : ""));
            card.setOnLongClickListener(view -> {
                if (sourceChoiceListener == null || channel.sourceCount() < 2) return false;
                sourceChoiceListener.onChannelClick(channel);
                return true;
            });
            applyState(card.hasFocus(), selected);
        }

        void reset() {
            if (logoRequest != null) {
                logoRequest.cancel();
                logoRequest = null;
            }
            boundUrl = "";
            image.setTag(null);
            image.setImageDrawable(null);
            initials.setVisibility(View.VISIBLE);
            card.setOnFocusChangeListener(null);
            card.setOnClickListener(null);
            card.setOnLongClickListener(null);
            dossier.setOnClickListener(null);
            dossier.setOnFocusChangeListener(null);
            dossier.setVisibility(View.GONE);
        }

        private void applyState(boolean focused, boolean selected) {
            Context context = itemView.getContext();
            boolean active = focused || selected;
            card.setBackground(roundedDrawable(active ? 0xFF382431 : 0xFF1D2028,
                    13, active ? 0xFFEA5265 : 0x18FFFFFF, context));
            card.setScaleX(focused ? 1.025f : 1f);
            card.setScaleY(focused ? 1.025f : 1f);
            name.setTextColor(active ? 0xFFFFE8EA : Color.WHITE);
            number.setTextColor(active ? 0xFFFFE8EA : Color.WHITE);
        }
    }

    private boolean isSelected(String url) {
        return !url.isEmpty() && TextUtils.equals(selectedUrl, url);
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
