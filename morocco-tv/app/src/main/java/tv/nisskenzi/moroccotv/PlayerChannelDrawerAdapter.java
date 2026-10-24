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
                        String url = channels.get(i).identity();
                        if (!url.isEmpty() && !built.containsKey(url)) built.put(url, i);
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
        int position = positionOf(selectedUrl);
        return position < 0 ? 0 : position;
    }

    public void setSelectedUrl(String url) {
        String next = safe(url);
        if (TextUtils.equals(selectedUrl, next)) return;
        int oldPosition = positionOf(selectedUrl);
        selectedUrl = next;
        int newPosition = positionOf(selectedUrl);
        if (oldPosition >= 0) notifyItemChanged(oldPosition);
        if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition);
    }

    private int positionOf(String url) {
        if (url == null || url.isEmpty()) return -1;
        Integer position = positionByUrl.get(url);
        return position == null ? -1 : position;
    }

    @Override public long getItemId(int position) {
        return channels.get(position).identity().hashCode();
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
        meta.setTextColor(0xFFB6B7C0);
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
        boolean selected = channel.identity().equals(selectedUrl);
        holder.title.setText(channel.name);
        String provider = channel.isVavoo ? "VAVOO" : "IPTV-ORG";
        holder.meta.setText(safe(channel.country) + "  ·  " + provider + " recommandé"
                + (channel.sourceCount() > 1 ? "  ·  " + channel.sourceCount() + " flux" : ""));
        holder.row.setBackground(background(selected ? 0xFF3B2027 : 0xFF202126,
                selected ? 0xFFEA5265 : 0x003C4654, holder.row.getContext()));
        holder.title.setTextColor(selected ? 0xFFFFE8EA : Color.WHITE);
        holder.row.setOnFocusChangeListener((v, focused) -> {
            boolean active = focused || channel.identity().equals(selectedUrl);
            holder.row.setBackground(background(active ? 0xFF3B2027 : 0xFF202126,
                    focused ? 0xFFEA5265 : (active ? 0xFFEA5265 : 0x003C4654),
                    holder.row.getContext()));
            holder.title.setTextColor(active ? 0xFFFFE8EA : Color.WHITE);
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
