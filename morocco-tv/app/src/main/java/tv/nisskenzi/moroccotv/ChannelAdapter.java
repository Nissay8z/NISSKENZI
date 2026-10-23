package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.collection.LruCache;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RecyclerView adapter for the live-channel list.
 *
 * Reuses row views, loads logos away from the UI thread, bounds the logo cache,
 * and keeps selection/focus styling compatible with an Android TV D-pad.
 */
public final class ChannelAdapter extends ListAdapter<MainActivity.Channel, ChannelAdapter.ChannelViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(MainActivity.Channel channel);
    }

    public interface FavoriteChecker {
        boolean isFavorite(MainActivity.Channel channel);
    }

    private static final int LOGO_CACHE_KB = 8 * 1024;
    private static final int MAX_LOGO_BYTES = 2 * 1024 * 1024;
    private static final int LOGO_MAX_EDGE = 192;

    private static final DiffUtil.ItemCallback<MainActivity.Channel> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<MainActivity.Channel>() {
                @Override
                public boolean areItemsTheSame(@NonNull MainActivity.Channel oldItem,
                                               @NonNull MainActivity.Channel newItem) {
                    return TextUtils.equals(safe(oldItem.url), safe(newItem.url));
                }

                @Override
                public boolean areContentsTheSame(@NonNull MainActivity.Channel oldItem,
                                                  @NonNull MainActivity.Channel newItem) {
                    return TextUtils.equals(safe(oldItem.name), safe(newItem.name))
                            && TextUtils.equals(safe(oldItem.logo), safe(newItem.logo))
                            && TextUtils.equals(safe(oldItem.group), safe(newItem.group))
                            && TextUtils.equals(safe(oldItem.country), safe(newItem.country))
                            && oldItem.isVavoo == newItem.isVavoo
                            && TextUtils.equals(safe(oldItem.vavooSourceUrl), safe(newItem.vavooSourceUrl));
                }
            };

    private static final Map<String, String> COUNTRY_CODES = buildCountryCodes();

    private final OnChannelClickListener listener;
    private final FavoriteChecker favoriteChecker;
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Nisskenzi-logo");
        thread.setDaemon(true);
        return thread;
    });
    private final LruCache<String, Bitmap> logoCache = new LruCache<String, Bitmap>(LOGO_CACHE_KB) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private String selectedUrl = "";
    private volatile boolean shutDown;

    public ChannelAdapter(@NonNull OnChannelClickListener listener,
                          @NonNull FavoriteChecker favoriteChecker) {
        super(DIFF_CALLBACK);
        this.listener = listener;
        this.favoriteChecker = favoriteChecker;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        String url = safe(getItem(position).url);
        return url.hashCode();
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

    /** Refreshes favorite stars after the preference value changes. */
    public void refreshDecorations() {
        if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount(), "decoration");
    }

    private int findPosition(String url) {
        if (url == null || url.isEmpty()) return -1;
        for (int i = 0; i < getCurrentList().size(); i++) {
            if (TextUtils.equals(url, safe(getItem(i).url))) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        boolean television = isTelevision(context);
        int rowHeight = dp(context, television ? 63 : 54);
        int bottomMargin = dp(context, 5);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setClickable(true);
        card.setPadding(dp(context, television ? 11 : 8), dp(context, 6),
                dp(context, 9), dp(context, 6));
        card.setBackground(roundedDrawable(0x33FFFFFF, 8, 0x00FFFFFF, context));
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
        initials.setTextColor(0xFF176647);
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

        TextView name = new TextView(context);
        name.setTextSize(television ? 14 : 11);
        name.setTextColor(Color.WHITE);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        return new ChannelViewHolder(card, number, image, initials, name, meta, favorite);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        MainActivity.Channel channel = getItem(position);
        holder.bind(channel, position, TextUtils.equals(selectedUrl, safe(channel.url)),
                listener, favoriteChecker, logoCache, imageExecutor, this);
    }

    @Override
    public void onViewRecycled(@NonNull ChannelViewHolder holder) {
        holder.reset();
        super.onViewRecycled(holder);
    }

    public void shutdown() {
        shutDown = true;
        imageExecutor.shutdownNow();
    }

    private void loadLogo(String address, ImageView image, TextView initials, String channelName) {
        if (address == null || address.trim().isEmpty() || shutDown) return;

        Bitmap cached = logoCache.get(address);
        if (cached != null) {
            image.setImageBitmap(cached);
            image.setVisibility(View.VISIBLE);
            initials.setVisibility(View.GONE);
            return;
        }

        try {
            imageExecutor.execute(() -> {
                Bitmap bitmap = downloadBitmap(address);
                if (bitmap == null || shutDown) return;
                logoCache.put(address, bitmap);
                image.post(() -> {
                    if (shutDown || !address.equals(image.getTag())) return;
                    image.setImageBitmap(bitmap);
                    image.setVisibility(View.VISIBLE);
                    initials.setVisibility(View.GONE);
                });
            });
        } catch (RuntimeException ignored) {
            // The executor may already be stopping while the page is replaced.
        }
    }

    @Nullable
    private Bitmap downloadBitmap(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NISSKENZI+/2.0 (Android TV)");

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_LOGO_BYTES) return null;
                    output.write(buffer, 0, read);
                }

                byte[] bytes = output.toByteArray();
                if (bytes.length == 0) return null;

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

                int sample = 1;
                while (bounds.outWidth / sample > LOGO_MAX_EDGE
                        || bounds.outHeight / sample > LOGO_MAX_EDGE) {
                    sample *= 2;
                }

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static final class ChannelViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView number;
        final ImageView image;
        final TextView initials;
        final TextView name;
        final TextView meta;
        final TextView favorite;
        private String boundUrl = "";

        ChannelViewHolder(LinearLayout card, TextView number, ImageView image,
                          TextView initials, TextView name, TextView meta, TextView favorite) {
            super(card);
            this.card = card;
            this.number = number;
            this.image = image;
            this.initials = initials;
            this.name = name;
            this.meta = meta;
            this.favorite = favorite;
        }

        void bind(MainActivity.Channel channel, int index, boolean selected,
                  OnChannelClickListener listener, FavoriteChecker favoriteChecker,
                  LruCache<String, Bitmap> cache, ExecutorService executor, ChannelAdapter owner) {
            boundUrl = safe(channel.url);
            number.setText(String.valueOf(index + 1));
            name.setText(emptyFallback(channel.name, "Chaîne"));

            String country = emptyFallback(channel.country, "Pays non précisé");
            String group = safe(channel.group)
                    .replace("VAVOO · ", "")
                    .replace("IPTV-ORG · ", "");
            String metaText = countryFlag(country) + "  " + country
                    + (group.isEmpty() ? "" : "  ·  " + group);
            meta.setText(metaText);

            boolean isFavorite = favoriteChecker != null && favoriteChecker.isFavorite(channel);
            favorite.setVisibility(isFavorite ? View.VISIBLE : View.GONE);

            image.setTag(safe(channel.logo));
            image.setImageDrawable(null);
            image.setVisibility(View.VISIBLE);
            initials.setText(makeInitials(channel.name));
            initials.setVisibility(View.VISIBLE);

            Bitmap cached = cache.get(safe(channel.logo));
            if (channel.logo != null && !channel.logo.trim().isEmpty() && cached != null) {
                image.setImageBitmap(cached);
                initials.setVisibility(View.GONE);
            } else {
                image.setImageDrawable(null);
                owner.loadLogo(channel.logo, image, initials, channel.name);
            }

            card.setOnFocusChangeListener((view, hasFocus) -> applyState(hasFocus, owner.isSelected(boundUrl)));
            card.setOnClickListener(view -> {
                if (listener != null) listener.onChannelClick(channel);
            });
            applyState(card.hasFocus(), selected);
        }

        void reset() {
            boundUrl = "";
            image.setTag(null);
            image.setImageDrawable(null);
            initials.setVisibility(View.VISIBLE);
            card.setOnFocusChangeListener(null);
            card.setOnClickListener(null);
        }

        private void applyState(boolean focused, boolean selected) {
            Context context = itemView.getContext();
            boolean active = focused || selected;
            card.setBackground(roundedDrawable(active ? 0xAA087449 : 0x33FFFFFF,
                    8, active ? 0xDDFFFFFF : 0x00FFFFFF, context));
            card.setScaleX(focused ? 1.02f : 1f);
            card.setScaleY(focused ? 1.02f : 1f);
            name.setTextColor(active ? 0xFFFFFF00 : Color.WHITE);
            number.setTextColor(active ? 0xFFFFFF00 : Color.WHITE);
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
