package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Locale;

/** Lightweight, virtualized list used only by the fullscreen left-side channel drawer. */
public final class PlayerChannelDrawerAdapter extends RecyclerView.Adapter<PlayerChannelDrawerAdapter.Holder> {
    public interface OnChannelSelected {
        void onChannelSelected(Channel channel);
    }

    private static final Map<Object, Map<String, Integer>> INDEX_CACHE = new WeakHashMap<>();

    private List<Channel> channels = Collections.emptyList();
    private Map<String, Integer> positionByUrl = Collections.emptyMap();
    private Object indexedCatalogToken;
    private String selectedUrl = "";
    private OnChannelSelected listener;

    public PlayerChannelDrawerAdapter(OnChannelSelected listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setChannels(List<Channel> next, String selected, Object catalogToken) {
        List<Channel> resolved = next == null ? Collections.emptyList() : next;
        if (channels != resolved || indexedCatalogToken != catalogToken) {
            channels = resolved;
            synchronized (INDEX_CACHE) {
                Map<String, Integer> cached = INDEX_CACHE.get(catalogToken);
                if (cached == null) {
                    HashMap<String, Integer> built = new HashMap<>(
                            Math.max(16, channels.size() * 4 / 3));
                    for (int i = 0; i < channels.size(); i++) {
                        Channel ch = channels.get(i);
                        String url = safe(ch.url);
                        if (!url.isEmpty() && !built.containsKey(url)) built.put(url, i);
                        if (ch.sources != null) {
                            for (Channel s : ch.sources) {
                                String sUrl = safe(s.url);
                                if (!sUrl.isEmpty() && !built.containsKey(sUrl)) built.put(sUrl, i);
                            }
                        }
                    }
                    cached = Collections.unmodifiableMap(built);
                    if (catalogToken != null) INDEX_CACHE.put(catalogToken, cached);
                }
                positionByUrl = cached;
            }
            indexedCatalogToken = catalogToken;
        } else {
            channels = resolved;
        }
        selectedUrl = safe(selected);
        notifyDataSetChanged();
    }

    /** O(1) lookup: opening the left drawer must not scan thousands of rows. */
    public int selectedPosition() {
        Integer position = positionByUrl.get(selectedUrl);
        return position == null ? 0 : position;
    }

    public void setSelectedUrl(String url) {
        selectedUrl = safe(url);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) {
        return safe(channels.get(position).url).hashCode();
    }

    @Override public int getItemCount() {
        return channels == null ? 0 : channels.size();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        int pad = dp(context, 12);
        row.setPadding(pad, dp(context, 6), pad, dp(context, 6));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 58));
        params.bottomMargin = dp(context, 4);
        row.setLayoutParams(params);

        TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(isTelevision(context) ? 15 : 13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(context);
        meta.setTextColor(0xFFB8C4D2);
        meta.setTextSize(isTelevision(context) ? 10 : 9);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.topMargin = dp(context, 2);
        row.addView(meta, metaParams);
        return new Holder(row, title, meta);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Channel channel = channels.get(position);
        boolean selected = channel.matchesAnyUrl(Collections.singleton(selectedUrl));
        holder.title.setText(!channel.cleanTitle.isEmpty() ? channel.cleanTitle : channel.name);
        String group = safe(channel.group).replace("VAVOO · ", "").replace("IPTV-ORG · ", "");
        String metaText;
        if (channel.hasMultipleSources()) {
            String bestQuality = channel.getBestQualityBadge();
            metaText = safe(channel.country) + "  ·  📁 " + channel.getSourceCount() + " flux"
                    + (bestQuality.isEmpty() ? "" : " · " + bestQuality);
        } else {
            metaText = safe(channel.country) + (group.isEmpty() ? "" : "  ·  " + group);
        }
        holder.meta.setText(metaText);
        holder.row.setBackground(background(selected ? 0xFF164A60 : 0xFF1A222D,
                selected ? 0xFF4CC9F0 : 0x003C4654, holder.row.getContext()));
        holder.title.setTextColor(selected ? 0xFFFFFF00 : Color.WHITE);
        holder.row.setOnFocusChangeListener((v, focused) -> {
            boolean active = focused || channel.matchesAnyUrl(Collections.singleton(selectedUrl));
            holder.row.setBackground(background(active ? 0xFF164A60 : 0xFF1A222D,
                    focused ? 0xFF4CC9F0 : (active ? 0xFF4CC9F0 : 0x003C4654),
                    holder.row.getContext()));
            holder.title.setTextColor(active ? 0xFFFFFF00 : Color.WHITE);
            holder.row.setScaleX(focused ? 1.02f : 1f);
            holder.row.setScaleY(focused ? 1.02f : 1f);
        });
        holder.row.setOnClickListener(v -> {
            if (listener != null) listener.onChannelSelected(channel);
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout row;
        final TextView title;
        final TextView meta;
        Holder(LinearLayout row, TextView title, TextView meta) {
            super(row);
            this.row = row;
            this.title = title;
            this.meta = meta;
        }
    }

    private static GradientDrawable background(int color, int stroke, Context context) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, 8));
        d.setStroke(dp(context, 1), stroke);
        return d;
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private static boolean isTelevision(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
