package tv.nisskenzi.moroccotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.util.VLCVideoLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Android Live TV app. The UI is built with Android Views; no WebView or HTML UI.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF080D16;
    private static final int PANEL = 0xFF0E1726;
    private static final int PANEL2 = 0xFF121F32;
    private static final int LINE = 0xFF20334C;
    private static final int CYAN = 0xFF38D5FF;
    private static final int PURPLE = 0xFF9166FF;
    private static final int MUTED = 0xFF8EA3BF;
    private static final int TEXT = 0xFFF3F6FD;
    private static final int GREEN = 0xFF40D79D;
    private static final int YELLOW = 0xFFF5CE6B;
    private static final int CHANNELS_PER_BATCH = 72;
    private static final int MAX_CACHED_CHANNELS = 1200;
    private static final int MAX_CATALOG_CHANNELS = 12000;
    private static final int MAX_LOGO_CACHE = 160;

    private LinearLayout root;
    private FrameLayout contentHost;
    private TextView statusView;
    private TextView countView;
    private LinearLayout countryChoices;
    private LinearLayout channelResults;
    private EditText searchInput;
    private TextView liveTab;
    private TextView settingsTab;

    private PlayerView playerView;
    private ExoPlayer player;
    private FrameLayout playerStage;
    private LibVLC libVLC;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private VLCVideoLayout vlcVideoLayout;
    private Channel currentChannel;
    private volatile String activePage = "Accueil";
    private volatile String catalogStatus = "Chargement des catalogues VAVOO + IPTV-ORG…";
    private boolean loadingCatalog = false;
    private volatile boolean activityDestroyed = false;
    private int visibleChannelLimit = CHANNELS_PER_BATCH;
    private int focusChannelIndex = -1;
    private String activeCountry = "Tous les pays";
    private String sourceFilter = "Toutes";
    private String searchText = "";

    private final ArrayList<Channel> channels = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService logoIo = Executors.newFixedThreadPool(2);
    private final Map<String, Bitmap> logoCache = Collections.synchronizedMap(
        new LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                return size() > MAX_LOGO_CACHE;
            }
        });

    private static class Channel {
        String name, url, logo, group, vavooSourceUrl, country;
        boolean isVavoo;
        Channel(String n, String u, String l, String g, boolean v, String source, String countryName) {
            name=n; url=u; logo=l; group=g; isVavoo=v; vavooSourceUrl=source; country=countryName;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildHome();
        loadCachedChannels();
        loadChannels();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isTelevision() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable outlined(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = rounded(color, radiusDp);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView buttonText(String text, boolean selected) {
        TextView b = label(text, isTelevision() ? 15 : 12, selected ? Color.WHITE : MUTED, true);
        b.setGravity(Gravity.CENTER);
        int padH = isTelevision() ? 17 : 13;
        int padV = isTelevision() ? 13 : 10;
        b.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        b.setBackground(rounded(selected ? 0xFF25204A : PANEL2, 10));
        b.setFocusable(true);
        b.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                b.setBackground(outlined(0xFF19324B, 10, CYAN));
                b.setTextColor(Color.WHITE);
                b.setScaleX(1.04f);
                b.setScaleY(1.04f);
            } else {
                boolean active = b == liveTab ? "Live TV".equals(activePage)
                        : b == settingsTab ? "Paramètres".equals(activePage) : selected;
                b.setBackground(rounded(active ? 0xFF25204A : PANEL2, 10));
                b.setTextColor(active ? Color.WHITE : MUTED);
                b.setScaleX(1f);
                b.setScaleY(1f);
            }
        });
        return b;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private void buildHome() {
        if (player != null || vlcPlayer != null) releasePlayer();
        root = vertical();
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout header = horizontal();
        int headerPad = isTelevision() ? 22 : 15;
        header.setPadding(dp(headerPad), dp(isTelevision() ? 16 : 12), dp(headerPad), dp(isTelevision() ? 16 : 12));
        header.setBackgroundColor(0xFF070C15);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        root.addView(header, hp);

        TextView mark = label("N", isTelevision() ? 29 : 24, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(0xFF246FEE, 12));
        header.addView(mark, lp(isTelevision() ? 54 : 42, isTelevision() ? 54 : 42));

        LinearLayout brand = vertical();
        brand.setPadding(dp(10), 0, dp(12), 0);
        brand.addView(label("NISSKENZI+", isTelevision() ? 21 : 17, TEXT, true));
        TextView tagline = label("LIVE TV · MONDE ENTIER", isTelevision() ? 10 : 8, MUTED, true);
        tagline.setLetterSpacing(0.12f);
        brand.addView(tagline);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        liveTab = buttonText("▶  Live TV", "Live TV".equals(activePage));
        settingsTab = buttonText("⚙  Paramètres", "Paramètres".equals(activePage));
        boolean onHome = "Accueil".equals(activePage);
        liveTab.setVisibility(onHome ? View.GONE : View.VISIBLE);
        settingsTab.setVisibility(onHome ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams navp = lp(-2, isTelevision() ? 50 : 42);
        navp.leftMargin = dp(5);
        header.addView(liveTab, navp);
        header.addView(settingsTab, navp);
        liveTab.setOnClickListener(v -> setPage("Live TV"));
        settingsTab.setOnClickListener(v -> setPage("Paramètres"));

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));

        statusView = label(catalogStatus, 10, MUTED, false);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(dp(15), dp(7), dp(15), dp(7));
        statusView.setBackgroundColor(0xFF070C15);
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        renderPage();
    }

    private boolean homePlayerExists() {
        return root != null && playerView != null;
    }

    private void setPage(String p) {
        activePage = p;
        if (liveTab != null) {
            boolean live = "Live TV".equals(p);
            boolean settings = "Paramètres".equals(p);
            boolean home = "Accueil".equals(p);
            liveTab.setVisibility(home ? View.GONE : View.VISIBLE);
            settingsTab.setVisibility(home ? View.GONE : View.VISIBLE);
            liveTab.setTextColor(live ? Color.WHITE : MUTED);
            liveTab.setBackground(rounded(live ? 0xFF25204A : PANEL2, 10));
            settingsTab.setTextColor(settings ? Color.WHITE : MUTED);
            settingsTab.setBackground(rounded(settings ? 0xFF25204A : PANEL2, 10));
        }
        renderPage();
    }

    private void renderPage() {
        if (contentHost == null) return;
        contentHost.removeAllViews();
        if ("Accueil".equals(activePage)) renderHomeMenu();
        else if ("Paramètres".equals(activePage)) renderSettings();
        else renderLivePage();
    }

    private void renderHomeMenu() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = vertical();
        int homeInset = isTelevision() ? 44 : 18;
        page.setPadding(dp(homeInset), dp(isTelevision() ? 28 : 20), dp(homeInset), dp(isTelevision() ? 28 : 22));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView eyebrow = label("NISSKENZI+  /  LIVE TV", 9, CYAN, true);
        eyebrow.setLetterSpacing(0.16f);
        page.addView(eyebrow);

        TextView title = label("Tout le direct,\nau même endroit.", isTelevision() ? 38 : 26, TEXT, true);
        title.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams tp = lp(-1, -2); tp.topMargin = dp(8);
        page.addView(title, tp);

        TextView subtitle = label("Choisis une section pour commencer.", isTelevision() ? 16 : 12, MUTED, false);
        LinearLayout.LayoutParams stp = lp(-1, -2); stp.topMargin = dp(7); stp.bottomMargin = dp(isTelevision() ? 30 : 20);
        page.addView(subtitle, stp);

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        tiles.setGravity(Gravity.CENTER);
        page.addView(tiles, lp(-1, -2));

        View live = homeTile("LIVE TV", "Chaînes classées par pays", "▣",
            new int[]{0xFF124E78, 0xFF0B182B}, CYAN, v -> setPage("Live TV"));
        View settings = homeTile("PARAMÈTRES", "Lecteurs vidéo et connexions", "⚙",
            new int[]{0xFF3D2B75, 0xFF12162A}, 0xFFC7AEFF, v -> setPage("Paramètres"));

        LinearLayout.LayoutParams livep;
        LinearLayout.LayoutParams settingsp;
        if (landscape) {
            livep = new LinearLayout.LayoutParams(0, dp(isTelevision() ? 300 : 242), 1f);
            settingsp = new LinearLayout.LayoutParams(0, dp(isTelevision() ? 300 : 242), 1f);
            livep.rightMargin = dp(10); settingsp.leftMargin = dp(10);
        } else {
            livep = lp(-1, 185); settingsp = lp(-1, 185);
            livep.bottomMargin = dp(12);
        }
        tiles.addView(live, livep);
        tiles.addView(settings, settingsp);
        if (isTelevision()) live.requestFocus();

        TextView footer = label("VAVOO  ·  IPTV-ORG  ·  LECTEUR MEDIA3 / VLC", isTelevision() ? 11 : 9, MUTED, true);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = lp(-1, -2); fp.topMargin = dp(18);
        page.addView(footer, fp);
    }

    private View homeTile(String title, String description, String symbol, int[] colors,
                          int accent, View.OnClickListener click) {
        LinearLayout card = vertical();
        card.setPadding(dp(20), dp(17), dp(20), dp(16));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xFF29445F);
        card.setBackground(background);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(click);
        card.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable state = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
            state.setCornerRadius(dp(18));
            state.setStroke(dp(focused ? 3 : 1), focused ? accent : 0xFF29445F);
            card.setBackground(state);
            card.setScaleX(focused ? 1.025f : 1f);
            card.setScaleY(focused ? 1.025f : 1f);
        });

        TextView top = label("NISSKENZI+", isTelevision() ? 11 : 9, 0xFFC8D8EF, true);
        top.setLetterSpacing(0.12f);
        card.addView(top);

        TextView icon = label(symbol, isTelevision() ? 50 : 42, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0x332F8EFF, 14));
        LinearLayout.LayoutParams iconp = lp(isTelevision() ? 82 : 66, isTelevision() ? 82 : 66); iconp.topMargin = dp(isTelevision() ? 20 : 16);
        card.addView(icon, iconp);

        TextView heading = label(title, isTelevision() ? 24 : 19, TEXT, true);
        LinearLayout.LayoutParams hdp = lp(-1, -2); hdp.topMargin = dp(11);
        card.addView(heading, hdp);

        TextView details = label(description, isTelevision() ? 14 : 11, 0xFFB5C5DC, false);
        LinearLayout.LayoutParams ddp = lp(-1, -2); ddp.topMargin = dp(5);
        card.addView(details, ddp);

        TextView open = label("OUVRIR  →", isTelevision() ? 11 : 9, accent, true);
        open.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams op = lp(-1, -2); op.topMargin = dp(14);
        card.addView(open, op);
        return card;
    }

    private void renderLivePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout page = vertical();
        int liveInset = isTelevision() ? 32 : 16;
        page.setPadding(dp(liveInset), dp(isTelevision() ? 24 : 15), dp(liveInset), dp(isTelevision() ? 28 : 18));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout titleRow = horizontal();
        TextView title = label("Télévision en direct", isTelevision() ? 30 : 22, TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        countView = label(channels.size() + " chaînes", isTelevision() ? 13 : 10, MUTED, true);
        countView.setPadding(dp(10), dp(8), dp(10), dp(8));
        countView.setBackground(rounded(PANEL, 9));
        titleRow.addView(countView);
        page.addView(titleRow);
        TextView subtitle = label("Catalogue mondial · VAVOO + IPTV-ORG", isTelevision() ? 14 : 11, MUTED, false);
        LinearLayout.LayoutParams subp = lp(-1, -2); subp.topMargin = dp(4);
        page.addView(subtitle, subp);

        LinearLayout searchRow = horizontal();
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srp = lp(-1, isTelevision() ? 56 : 45); srp.topMargin = dp(isTelevision() ? 20 : 14); srp.bottomMargin = dp(12);
        page.addView(searchRow, srp);
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(TEXT);
        searchInput.setHintTextColor(MUTED);
        searchInput.setTextSize(isTelevision() ? 16 : 12);
        searchInput.setHint("⌕  Rechercher une chaîne ou un pays…");
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        searchInput.setBackground(outlined(PANEL, 10, LINE));
        searchInput.setText(searchText);
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, -1, 1));
        TextView refresh = buttonText("↻", false);
        LinearLayout.LayoutParams rfp = lp(isTelevision() ? 54 : 44, isTelevision() ? 54 : 44); rfp.leftMargin = dp(8);
        searchRow.addView(refresh, rfp);
        refresh.setOnClickListener(v -> loadChannels());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int st,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int st,int before,int count) {
                searchText = s == null ? "" : s.toString();
                visibleChannelLimit = CHANNELS_PER_BATCH;
                focusChannelIndex = -1;
                updateChannelResults();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        LinearLayout countryTitle = horizontal();
        countryTitle.addView(label("PAYS DISPONIBLES", 9, MUTED, true));
        page.addView(countryTitle);
        HorizontalScrollView countriesScroll = new HorizontalScrollView(this);
        countriesScroll.setHorizontalScrollBarEnabled(false);
        countryChoices = horizontal();
        countryChoices.setPadding(0, dp(8), 0, dp(8));
        countriesScroll.addView(countryChoices);
        LinearLayout.LayoutParams csp = lp(-1, isTelevision() ? 64 : 48);
        page.addView(countriesScroll, csp);
        renderCountryChoices();

        LinearLayout sourceTitle = horizontal();
        sourceTitle.addView(label("SOURCE", 9, MUTED, true));
        page.addView(sourceTitle);
        HorizontalScrollView sourceScroll = new HorizontalScrollView(this);
        sourceScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sources = horizontal();
        sources.setPadding(0, dp(7), 0, dp(10));
        for (String source : new String[]{"Toutes", "VAVOO", "IPTV-ORG"}) {
            TextView chip = buttonText(source.equals("Toutes") ? "Toutes les sources" : source, source.equals(sourceFilter));
            LinearLayout.LayoutParams cp = lp(-2, isTelevision() ? 44 : 36); cp.rightMargin = dp(7);
            sources.addView(chip, cp);
            chip.setOnClickListener(v -> { sourceFilter = source; visibleChannelLimit = CHANNELS_PER_BATCH; focusChannelIndex = -1; renderPage(); });
        }
        sourceScroll.addView(sources);
        page.addView(sourceScroll, lp(-1, isTelevision() ? 62 : 45));

        channelResults = vertical();
        page.addView(channelResults, lp(-1, -2));
        updateChannelResults();
    }

    private void renderCountryChoices() {
        if (countryChoices == null) return;
        countryChoices.removeAllViews();
        TextView all = buttonText("🌐 Tous les pays  " + channels.size(), "Tous les pays".equals(activeCountry));
        LinearLayout.LayoutParams allp = lp(-2, isTelevision() ? 44 : 36); allp.rightMargin = dp(7);
        countryChoices.addView(all, allp);
        all.setOnClickListener(v -> { activeCountry = "Tous les pays"; visibleChannelLimit = CHANNELS_PER_BATCH; focusChannelIndex = -1; renderPage(); });

        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        for (Channel ch : channels) {
            String country = safeCountry(ch.country);
            counts.put(country, counts.containsKey(country) ? counts.get(country) + 1 : 1);
            if (!codes.containsKey(country) || codes.get(country).isEmpty()) codes.put(country, countryCode(country));
        }
        ArrayList<String> names = new ArrayList<>(counts.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (String country : names) {
            String text = flag(country, codes.get(country)) + " " + country + "  " + counts.get(country);
            TextView chip = buttonText(text, country.equals(activeCountry));
            LinearLayout.LayoutParams cp = lp(-2, isTelevision() ? 44 : 36); cp.rightMargin = dp(7);
            countryChoices.addView(chip, cp);
            chip.setOnClickListener(v -> { activeCountry = country; visibleChannelLimit = CHANNELS_PER_BATCH; focusChannelIndex = -1; renderPage(); });
        }
    }

    private ArrayList<Channel> filteredChannels() {
        String q = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel ch : channels) {
            String country = safeCountry(ch.country);
            if (!"Tous les pays".equals(activeCountry) && !activeCountry.equals(country)) continue;
            String src = ch.isVavoo ? "VAVOO" : "IPTV-ORG";
            if (!"Toutes".equals(sourceFilter) && !sourceFilter.equals(src)) continue;
            if (!q.isEmpty() && !(safe(ch.name) + " " + safe(ch.group) + " " + country).toLowerCase(Locale.ROOT).contains(q)) continue;
            result.add(ch);
        }
        return result;
    }

    private void updateChannelResults() {
        if (channelResults == null || !("Live TV".equals(activePage))) return;
        ArrayList<Channel> filtered = filteredChannels();
        if (countView != null) countView.setText(filtered.size() + " chaînes");
        channelResults.removeAllViews();
        if (filtered.isEmpty()) {
            LinearLayout empty = vertical();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(28), dp(20), dp(28));
            empty.setBackground(outlined(PANEL, 14, LINE));
            TextView symbol = label("▣", 30, PURPLE, true); symbol.setGravity(Gravity.CENTER);
            empty.addView(symbol);
            TextView heading = label(channels.isEmpty() ? "Connexion aux catalogues…" : "Aucune chaîne trouvée",
                    isTelevision() ? 21 : 16, TEXT, true);
            heading.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ehp=lp(-1,-2);ehp.topMargin=dp(9);empty.addView(heading,ehp);
            TextView msg = label(channels.isEmpty()
                    ? "VAVOO et IPTV-ORG se chargent automatiquement. Vérifie ta connexion ou actualise."
                    : "Essaie un autre pays, une autre source ou une recherche différente.",
                    isTelevision() ? 14 : 11, MUTED, false);
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0,dp(6),0,dp(12));
            empty.addView(msg);
            TextView tryAgain = buttonText("↻ Actualiser les catalogues", true);
            empty.addView(tryAgain, lp(-2, isTelevision() ? 52 : 40));
            tryAgain.setOnClickListener(v -> loadChannels());
            channelResults.addView(empty, lp(-1,-2));
            return;
        }

        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int columns;
        if (isTelevision()) columns = widthDp >= 1500 ? 4 : 3;
        else if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
            columns = widthDp >= 1250 ? 3 : 2;
        else columns = 1;

        final int shown = Math.min(filtered.size(), visibleChannelLimit);
        for (int i=0; i<shown; i++) {
            if (i % columns == 0) {
                LinearLayout row = horizontal();
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rp = lp(-1,-2); rp.bottomMargin=dp(9);
                channelResults.addView(row,rp);
            }
            LinearLayout row = (LinearLayout) channelResults.getChildAt(channelResults.getChildCount()-1);
            Channel ch = filtered.get(i);
            LinearLayout card = channelCard(ch);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                    dp(isTelevision() ? 104 : 82), 1);
            cp.rightMargin = i % columns == columns - 1 ? 0 : dp(isTelevision() ? 13 : 9);
            row.addView(card, cp);
            if (i == focusChannelIndex) {
                focusChannelIndex = -1;
                if (isTelevision()) card.post(() -> card.requestFocus());
            }
        }

        if (shown < filtered.size()) {
            int addCount = Math.min(CHANNELS_PER_BATCH, filtered.size() - shown);
            String moreText = "Afficher " + addCount + " chaîne" + (addCount > 1 ? "s" : "")
                    + " supplémentaires  ·  " + shown + " / " + filtered.size();
            TextView more = buttonText(moreText, false);
            more.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams morep = lp(-1, isTelevision() ? 54 : 44);
            morep.topMargin = dp(4);
            morep.bottomMargin = dp(12);
            channelResults.addView(more, morep);
            more.setOnClickListener(v -> {
                focusChannelIndex = shown;
                visibleChannelLimit = Math.min(filtered.size(), visibleChannelLimit + CHANNELS_PER_BATCH);
                updateChannelResults();
            });
        }
    }

    private LinearLayout channelCard(Channel ch) {
        LinearLayout card = horizontal();
        card.setPadding(dp(isTelevision() ? 14 : 10), dp(isTelevision() ? 12 : 8), dp(8), dp(isTelevision() ? 12 : 8));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(outlined(PANEL, 12, LINE));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((view, focused) -> {
            card.setBackground(outlined(focused ? 0xFF142D45 : PANEL, 12, focused ? CYAN : LINE));
            card.setScaleX(focused ? 1.025f : 1f);
            card.setScaleY(focused ? 1.025f : 1f);
        });

        FrameLayout logoFrame = new FrameLayout(this);
        logoFrame.setBackground(rounded(0xFFEAF2FC, 8));
        TextView initials = label(initials(ch.name), 12, 0xFF15253D, true);
        initials.setGravity(Gravity.CENTER);
        logoFrame.addView(initials, new FrameLayout.LayoutParams(-1,-1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(3),dp(3),dp(3),dp(3));
        logoFrame.addView(image, new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams logop = lp(isTelevision() ? 60 : 48, isTelevision() ? 60 : 48);
        logop.rightMargin = dp(10);
        card.addView(logoFrame, logop);
        loadLogo(ch.logo, image, initials);

        LinearLayout info = vertical();
        info.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0,-1,1));
        TextView name = label(safe(ch.name), isTelevision() ? 15 : 12, TEXT, true);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        TextView meta = label((ch.isVavoo ? "VAVOO" : "IPTV-ORG") + " · " + safeCountry(ch.country), isTelevision() ? 11 : 9, MUTED, false);
        meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp=lp(-1,-2);mp.topMargin=dp(5);info.addView(meta,mp);

        TextView star = label(isFavorite(ch) ? "★" : "☆", isTelevision() ? 26 : 20, YELLOW, true);
        star.setGravity(Gravity.CENTER);
        star.setFocusable(false);
        card.addView(star, lp(isTelevision() ? 34 : 26, isTelevision() ? 52 : 42));
        star.setOnClickListener(v -> {
            toggleFavorite(ch);
            star.setText(isFavorite(ch) ? "★" : "☆");
        });
        card.setOnClickListener(v -> play(ch));
        return card;
    }

    private void loadLogo(String address, ImageView image, TextView initials) {
        if (address == null || address.trim().isEmpty()) { image.setVisibility(View.GONE); return; }
        image.setTag(address);
        Bitmap cached = logoCache.get(address);
        if (cached != null) { image.setImageBitmap(cached); initials.setVisibility(View.GONE); return; }
        logoIo.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(address).openConnection();
                conn.setConnectTimeout(7000); conn.setReadTimeout(9000);
                conn.setRequestProperty("User-Agent", "NISSKENZI+/1.9.3");
                try (InputStream in = conn.getInputStream()) {
                    Bitmap bitmap = decodeLogo(in);
                    if (bitmap != null) {
                        logoCache.put(address, bitmap);
                        image.post(() -> {
                            if (address.equals(image.getTag())) {
                                image.setImageBitmap(bitmap);
                                image.setVisibility(View.VISIBLE);
                                initials.setVisibility(View.GONE);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
            } finally { if (conn != null) conn.disconnect(); }
        });
    }

    private Bitmap decodeLogo(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > 2 * 1024 * 1024) return null;
            output.write(buffer, 0, read);
        }
        byte[] data = output.toByteArray();
        if (data.length == 0) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / sample > 192 || bounds.outHeight / sample > 192) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(18), dp(16), dp(22));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1,-1));

        page.addView(label("Paramètres", 24, TEXT, true));
        TextView intro = label("Gère les catalogues utilisés par le Live TV.", 11, MUTED, false);
        LinearLayout.LayoutParams ip=lp(-1,-2);ip.topMargin=dp(5);ip.bottomMargin=dp(18);page.addView(intro,ip);

        TextView playerHeading=label("LECTEUR VIDÉO",9,MUTED,true);
        page.addView(playerHeading);
        TextView playerMode=buttonText("Lecteur préféré : "+preferredPlayerLabel(),true);
        LinearLayout.LayoutParams pmp=lp(-1,46);pmp.topMargin=dp(8);page.addView(playerMode,pmp);
        TextView playerHelp=label("Media3 est léger. VLC apporte un moteur alternatif pour davantage de formats et de protocoles.",10,MUTED,false);
        LinearLayout.LayoutParams php=lp(-1,-2);php.topMargin=dp(6);php.bottomMargin=dp(16);page.addView(playerHelp,php);
        playerMode.setOnClickListener(v->{setVlcPreference(!useVlcPlayer());renderSettings();Toast.makeText(this,"Lecteur préféré : "+preferredPlayerLabel(),Toast.LENGTH_SHORT).show();});

        page.addView(sourceCard("VAVOO", "V", "Catalogue IPTV dynamique", "Les chaînes et leurs métadonnées sont récupérées directement par l’application.", CYAN));
        LinearLayout.LayoutParams gap=lp(-1,-2);gap.topMargin=dp(12);
        page.addView(sourceCard("IPTV-ORG", "◉", "Playlist publique internationale", "Charge la playlist organisée par pays et lit les flux via le lecteur Android.", PURPLE),gap);

        LinearLayout statusCard = vertical();
        statusCard.setPadding(dp(16),dp(14),dp(16),dp(14));
        statusCard.setBackground(outlined(PANEL,13,LINE));
        LinearLayout.LayoutParams sp=lp(-1,-2);sp.topMargin=dp(14);page.addView(statusCard,sp);
        statusCard.addView(label("ÉTAT DU CATALOGUE", 9, MUTED, true));
        TextView details=label(catalogStatus,11,TEXT,false);
        details.setTextIsSelectable(true);
        details.setPadding(0,dp(9),0,dp(4));
        statusCard.addView(details);

        TextView refresh = buttonText(loadingCatalog ? "Actualisation en cours…" : "↻  Actualiser les deux catalogues", true);
        LinearLayout.LayoutParams rp=lp(-1,46);rp.topMargin=dp(14);page.addView(refresh,rp);
        refresh.setOnClickListener(v -> loadChannels());
        if (loadingCatalog) refresh.setAlpha(0.65f);

        TextView cache = buttonText("Effacer le catalogue enregistré", false);
        LinearLayout.LayoutParams cp=lp(-1,44);cp.topMargin=dp(9);page.addView(cache,cp);
        cache.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Effacer le cache ?")
            .setMessage("Les chaînes enregistrées sur cet appareil seront supprimées. Les favoris restent conservés.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Effacer",(dialog,which)->{
                getSharedPreferences("catalog_cache",MODE_PRIVATE).edit().clear().apply();
                Toast.makeText(this,"Cache local effacé.",Toast.LENGTH_SHORT).show();
                channels.clear();activeCountry="Tous les pays";renderPage();
            }).show());

        TextView note = label("NISSKENZI utilise uniquement VAVOO et IPTV-ORG pour ce catalogue. La disponibilité des chaînes dépend des sources.",10,MUTED,false);
        note.setPadding(dp(2),dp(13),dp(2),0);
        page.addView(note);
    }

    private View sourceCard(String title, String icon, String tagline, String description, int accent) {
        LinearLayout card=vertical();
        card.setPadding(dp(15),dp(14),dp(15),dp(14));
        card.setBackground(outlined(PANEL,13,LINE));
        LinearLayout head=horizontal();
        TextView mark=label(icon,21,accent,true);mark.setGravity(Gravity.CENTER);mark.setBackground(rounded(0xFF182840,10));
        head.addView(mark,lp(42,42));
        LinearLayout names=vertical();names.setPadding(dp(11),0,0,0);
        names.addView(label(title,15,TEXT,true));
        names.addView(label(tagline,10,accent,false));
        head.addView(names);
        card.addView(head);
        TextView desc=label(description,11,MUTED,false);
        desc.setLineSpacing(dp(3),1f);
        LinearLayout.LayoutParams dp=lp(-1,-2);dp.topMargin=dp(11);card.addView(desc,dp);
        TextView state=label("État : " + sourceStatus(title),10,GREEN,true);
        LinearLayout.LayoutParams stp=lp(-1,-2);stp.topMargin=dp(8);card.addView(state,stp);
        return card;
    }

    private String sourceStatus(String source) {
        if (loadingCatalog) return "actualisation en cours";
        if (catalogStatus.toLowerCase(Locale.ROOT).contains(source.toLowerCase(Locale.ROOT) + " : indisponible")) return "indisponible";
        if (channels.isEmpty()) return "en attente de chaînes";
        for (Channel ch : channels) if (source.equals(ch.isVavoo ? "VAVOO" : "IPTV-ORG")) return "catalogue chargé";
        return "aucune chaîne reçue";
    }

    private void loadCachedChannels() {
        try {
            File cacheFile = new File(getApplicationInfo().dataDir, "shared_prefs/catalog_cache.xml");
            if (cacheFile.exists() && cacheFile.length() > 1_500_000L) cacheFile.delete();
            String raw=getSharedPreferences("catalog_cache",MODE_PRIVATE).getString("merged_channels","");
            if (raw.isEmpty()) return;
            if (raw.length() > 1_500_000) {
                getSharedPreferences("catalog_cache",MODE_PRIVATE).edit().remove("merged_channels").apply();
                return;
            }
            JSONArray arr=new JSONArray(raw);
            ArrayList<Channel> cached=new ArrayList<>();
            for(int i=0;i<arr.length();i++) {
                JSONObject o=arr.optJSONObject(i); if(o==null) continue;
                if (cached.size() >= MAX_CACHED_CHANNELS) break;
                String url=o.optString("url","");
                if(url.isEmpty()) continue;
                boolean v=o.optBoolean("isVavoo",false);
                String group=o.optString("group","Direct");
                if(!group.startsWith("VAVOO · ")&&!group.startsWith("IPTV-ORG · ")) continue;
                String country=normalizeCountry(o.optString("country",""));
                if(country.isEmpty()||"Pays non précisé".equalsIgnoreCase(country)||"default".equalsIgnoreCase(country)){
                    String prefix=v?"VAVOO · ":"IPTV-ORG · ";
                    country=normalizeCountry(group.startsWith(prefix)?group.substring(prefix.length()):group);
                }
                if(country.isEmpty()||"default".equalsIgnoreCase(country)) country="Pays non précisé";
                cached.add(new Channel(o.optString("name","Chaîne"),url,o.optString("logo",""),group,v,o.optString("sourceUrl",""),country));
            }
            if(!cached.isEmpty()){
                channels.clear();channels.addAll(cached);
                catalogStatus=cached.size()+" chaînes disponibles en cache · actualisation en arrière-plan…";
                updateStatus();
                renderPage();
            }
        } catch(Exception ignored) {}
    }

    private void saveCachedChannels(ArrayList<Channel> items) {
        try {
            JSONArray arr=new JSONArray();
            for(Channel ch:items){
                JSONObject o=new JSONObject();
                o.put("name",safe(ch.name));o.put("url",safe(ch.url));o.put("logo",safe(ch.logo));
                o.put("group",safe(ch.group));o.put("isVavoo",ch.isVavoo);
                o.put("sourceUrl",safe(ch.vavooSourceUrl));o.put("country",safe(ch.country));
                arr.put(o);
            }
            getSharedPreferences("catalog_cache",MODE_PRIVATE).edit()
                .putString("merged_channels",arr.toString())
                .putLong("updated_at",System.currentTimeMillis()).apply();
        } catch(Exception ignored) {}
    }

    private ArrayList<Channel> cacheableChannels(ArrayList<Channel> items) {
        int vavooCount = 0;
        int iptvCount = 0;
        for (Channel ch : items) { if (ch.isVavoo) vavooCount++; else iptvCount++; }
        int vavooLimit = Math.min(vavooCount, MAX_CACHED_CHANNELS / 2);
        int iptvLimit = Math.min(iptvCount, MAX_CACHED_CHANNELS / 2);
        int remaining = MAX_CACHED_CHANNELS - vavooLimit - iptvLimit;
        int extraVavoo = Math.min(remaining, vavooCount - vavooLimit);
        vavooLimit += extraVavoo;
        remaining -= extraVavoo;
        iptvLimit += Math.min(remaining, iptvCount - iptvLimit);

        ArrayList<Channel> cached = new ArrayList<>(Math.min(items.size(), MAX_CACHED_CHANNELS));
        int vavooAdded = 0;
        int iptvAdded = 0;
        for (Channel ch : items) {
            if (ch.isVavoo) {
                if (vavooAdded >= vavooLimit) continue;
                vavooAdded++;
            } else {
                if (iptvAdded >= iptvLimit) continue;
                iptvAdded++;
            }
            cached.add(ch);
            if (cached.size() >= MAX_CACHED_CHANNELS) break;
        }
        return cached;
    }

    private void loadChannels() {
        if (loadingCatalog) return;
        visibleChannelLimit = CHANNELS_PER_BATCH;
        focusChannelIndex = -1;
        loadingCatalog=true;
        catalogStatus="Actualisation des catalogues VAVOO + IPTV-ORG…";
        updateStatus();
        if ("Paramètres".equals(activePage)) renderSettings();
        io.execute(() -> {
            ArrayList<Channel> merged=new ArrayList<>();
            int vavooCount=0, iptvCount=0;
            String vavooError="", iptvError="";
            try {
                for(VavooProvider.VavooChannel item:VavooProvider.loadChannels()){
                    String country=normalizeCountry(item.country);
                    if(country.isEmpty()||"default".equalsIgnoreCase(country)) country=normalizeCountry(item.group);
                    if(country.isEmpty()||"default".equalsIgnoreCase(country)) country="Pays non précisé";
                    merged.add(new Channel(item.name,"vavoo://"+item.id,item.logo,
                        "VAVOO · "+(item.group==null||item.group.trim().isEmpty()?"Direct":item.group),
                        true,item.sourceUrl,country));
                    vavooCount++;
                }
            } catch(Exception e){vavooError=e.getMessage()==null?"catalogue VAVOO indisponible":e.getMessage();}

            HttpURLConnection conn=null;
            try {
                URL u=new URL("https://iptv-org.github.io/iptv/index.country.m3u");
                conn=(HttpURLConnection)u.openConnection();
                conn.setConnectTimeout(15000);conn.setReadTimeout(35000);
                conn.setRequestProperty("User-Agent","NISSKENZI+/1.8");
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"))){
                    String line,name="Chaîne",logo="",group="Pays non précisé",country="Pays non précisé";
                    while((line=reader.readLine())!=null){
                        if(line.startsWith("#EXTINF:")){
                            int comma=line.lastIndexOf(',');
                            name=comma>=0?line.substring(comma+1).trim():"Chaîne";
                            logo="";group="Pays non précisé";country="Pays non précisé";
                            String lg=attribute(line,"tvg-logo");if(!lg.isEmpty())logo=lg;
                            String grouped=attribute(line,"group-title");
                            if(!grouped.isEmpty())group=grouped;
                            // Country metadata is authoritative; group-title often describes genres/categories.
                            String rawCountry=attribute(line,"tvg-country");
                            if(rawCountry.isEmpty()) rawCountry=attribute(line,"country");
                            String cn=normalizeCountry(rawCountry);
                            if(cn.isEmpty() || "Pays non précisé".equalsIgnoreCase(cn)) cn=normalizeCountry(group);
                            country=cn.isEmpty() || "default".equalsIgnoreCase(cn) ? "Pays non précisé" : cn;
                        } else if(line.startsWith("http://")||line.startsWith("https://")){
                            if (merged.size() < MAX_CATALOG_CHANNELS) {
                                merged.add(new Channel(name,line.trim(),logo,"IPTV-ORG · "+group,false,null,country));
                                iptvCount++;
                            }
                        }
                    }
                }
            } catch(Exception e){iptvError=e.getMessage()==null?"playlist IPTV-ORG indisponible":e.getMessage();}
            finally {if(conn!=null)conn.disconnect();}

            final ArrayList<Channel> all=merged;
            final ArrayList<Channel> cacheSnapshot=cacheableChannels(all);
            if (!cacheSnapshot.isEmpty()) saveCachedChannels(cacheSnapshot);
            final String ve=vavooError, ie=iptvError;
            final int vc=vavooCount, ic=iptvCount;
            runOnUiThread(() -> {
                if (activityDestroyed) return;
                loadingCatalog=false;
                if(!all.isEmpty()){
                    channels.clear();channels.addAll(all);
                    if(!activeCountry.equals("Tous les pays")){
                        boolean found=false;for(Channel ch:channels)if(activeCountry.equals(safeCountry(ch.country))){found=true;break;}
                        if(!found)activeCountry="Tous les pays";
                    }
                    catalogStatus=all.size()+" chaînes chargées\nVAVOO : "+(ve.isEmpty()?"OK · "+vc+" chaînes":"indisponible · "+ve)+"\nIPTV-ORG : "+(ie.isEmpty()?"OK · "+ic+" chaînes":"indisponible · "+ie);
                } else {
                    catalogStatus="Aucune chaîne chargée\nVAVOO : "+(ve.isEmpty()?"aucune chaîne reçue":ve)+"\nIPTV-ORG : "+(ie.isEmpty()?"aucune chaîne reçue":ie);
                }
                updateStatus();
                renderPage();
                if(all.isEmpty()) Toast.makeText(this,"Aucun catalogue chargé. Vérifie la connexion puis réessaie.",Toast.LENGTH_LONG).show();
            });
        });
    }

    private void updateStatus() {
        if (statusView != null) statusView.setText(catalogStatus);
    }

    private String attribute(String line,String key) {
        int a=line.indexOf(key+"=");
        if(a<0) return "";
        int start=a+key.length()+1;
        if(start<line.length()&&line.charAt(start)=='"')start++;
        int end=line.indexOf('"',start);
        if(end<0) end=line.indexOf(' ',start);
        return end>start?line.substring(start,end<0?line.length():end):"";
    }

    private static String countryKey(String value) {
        if(value==null)return "";
        String normalized=Normalizer.normalize(value.trim(),Normalizer.Form.NFD);
        normalized=normalized.replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+"," ").trim();
    }

    private static final Map<String,String> COUNTRY_ALIASES=buildCountryAliases();
    private static Map<String,String> buildCountryAliases() {
        HashMap<String,String> map=new HashMap<>();
        for(String code:Locale.getISOCountries()){
            Locale loc=new Locale("",code);
            String fr=loc.getDisplayCountry(Locale.FRENCH), en=loc.getDisplayCountry(Locale.ENGLISH);
            if(fr==null||fr.trim().isEmpty())continue;
            map.put(countryKey(code),fr);map.put(countryKey(fr),fr);map.put(countryKey(en),fr);
        }
        map.put(countryKey("UK"),"Royaume-Uni");map.put(countryKey("GB"),"Royaume-Uni");map.put(countryKey("Great Britain"),"Royaume-Uni");
        map.put(countryKey("USA"),"États-Unis");map.put(countryKey("US"),"États-Unis");map.put(countryKey("United States of America"),"États-Unis");
        map.put(countryKey("South Korea"),"Corée du Sud");map.put(countryKey("Republic of Korea"),"Corée du Sud");
        map.put(countryKey("North Korea"),"Corée du Nord");map.put(countryKey("Ivory Coast"),"Côte d’Ivoire");
        map.put(countryKey("Czech Republic"),"Tchéquie");map.put(countryKey("Russia"),"Russie");
        map.put(countryKey("Moldova"),"Moldavie");map.put(countryKey("Vietnam"),"Viêt Nam");
        map.put(countryKey("Turkey"),"Turquie");map.put(countryKey("Türkiye"),"Turquie");
        map.put(countryKey("Taiwan"),"Taïwan");map.put(countryKey("Vatican City"),"Vatican");
        map.put(countryKey("Palestine"),"Palestine");map.put(countryKey("Kosovo"),"Kosovo");
        return map;
    }

    private static final Map<String,String> COUNTRY_CODES=buildCountryCodes();
    private static Map<String,String> buildCountryCodes() {
        HashMap<String,String> map=new HashMap<>();
        for(String code:Locale.getISOCountries()){
            Locale loc=new Locale("",code);
            map.put(countryKey(code),code);
            map.put(countryKey(loc.getDisplayCountry(Locale.FRENCH)),code);
            map.put(countryKey(loc.getDisplayCountry(Locale.ENGLISH)),code);
        }
        map.put(countryKey("UK"),"GB");map.put(countryKey("Great Britain"),"GB");
        map.put(countryKey("USA"),"US");map.put(countryKey("United States of America"),"US");
        map.put(countryKey("South Korea"),"KR");map.put(countryKey("Republic of Korea"),"KR");
        map.put(countryKey("North Korea"),"KP");map.put(countryKey("Ivory Coast"),"CI");
        map.put(countryKey("Czech Republic"),"CZ");map.put(countryKey("Russia"),"RU");
        map.put(countryKey("Moldova"),"MD");map.put(countryKey("Vietnam"),"VN");
        map.put(countryKey("Turkey"),"TR");map.put(countryKey("Türkiye"),"TR");
        map.put(countryKey("Taiwan"),"TW");map.put(countryKey("Vatican City"),"VA");
        map.put(countryKey("Palestine"),"PS");map.put(countryKey("Kosovo"),"XK");
        return map;
    }

    private String countryCode(String country) {
        String code=COUNTRY_CODES.get(countryKey(country));
        if(code==null)code=COUNTRY_CODES.get(countryKey(normalizeCountry(country)));
        return code==null?"":code;
    }

    private String normalizeCountry(String raw) {
        if(raw==null)return "";
        String first=raw.trim().split("[,;]")[0].trim();
        if(first.isEmpty())return "";
        String value=COUNTRY_ALIASES.get(countryKey(first));
        return value==null?first:value;
    }

    private String safeCountry(String value) {
        String country=normalizeCountry(value);
        return country.isEmpty()||"default".equalsIgnoreCase(country)?"Pays non précisé":country;
    }

    private String flag(String country,String code) {
        String cc=code==null?"":code.toUpperCase(Locale.ROOT);
        if(cc.matches("[A-Z]{2}")){
            int a=0x1F1E6+cc.charAt(0)-'A', b=0x1F1E6+cc.charAt(1)-'A';
            return new String(Character.toChars(a))+new String(Character.toChars(b));
        }
        String k=countryKey(country);
        if(k.contains("france"))return "🇫🇷";
        if(k.contains("maroc")||k.contains("morocco"))return "🇲🇦";
        return "🌐";
    }

    private String initials(String name) {
        String n=safe(name).trim();
        if(n.isEmpty())return "TV";
        String[] parts=n.split("\\s+");
        if(parts.length>1)return (parts[0].substring(0,1)+parts[1].substring(0,1)).toUpperCase(Locale.ROOT);
        return n.substring(0,Math.min(3,n.length())).toUpperCase(Locale.ROOT);
    }

    private String safe(String value) {return value==null?"":value.trim();}

    private boolean isFavorite(Channel ch) {
        return getSharedPreferences("favorites",MODE_PRIVATE).getBoolean(ch.url,false);
    }

    private void toggleFavorite(Channel ch) {
        SharedPreferences p=getSharedPreferences("favorites",MODE_PRIVATE);
        p.edit().putBoolean(ch.url,!p.getBoolean(ch.url,false)).apply();
    }

    private boolean useVlcPlayer() {
        return getSharedPreferences("settings",MODE_PRIVATE).getBoolean("use_vlc_player",false);
    }

    private void setVlcPreference(boolean enabled) {
        getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("use_vlc_player",enabled).apply();
    }

    private String preferredPlayerLabel() {
        return useVlcPlayer() ? "VLC (compatibilité étendue)" : "Media3 (léger)";
    }

    private void play(Channel ch) {
        currentChannel=ch;
        releaseMediaEngine();
        root.removeAllViews();
        root.setPadding(0,0,0,0);

        LinearLayout top=horizontal();
        top.setPadding(dp(14),dp(10),dp(14),dp(10));
        top.setBackgroundColor(0xFF070C15);
        TextView back=buttonText("‹  RETOUR",false);
        top.addView(back,lp(-2,40));
        back.setOnClickListener(v -> returnHome());
        TextView title=label(safe(ch.name),16,TEXT,true);
        title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12),0,dp(10),0);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView star=buttonText(isFavorite(ch)?"★":"☆",isFavorite(ch));
        top.addView(star,lp(44,40));
        star.setOnClickListener(v->{toggleFavorite(ch);star.setText(isFavorite(ch)?"★":"☆");});
        root.addView(top,lp(-1,-2));

        playerStage=new FrameLayout(this);
        playerStage.setBackgroundColor(Color.BLACK);
        root.addView(playerStage,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout footer=horizontal();
        footer.setPadding(dp(10),dp(8),dp(10),dp(8));
        footer.setBackgroundColor(0xFF070C15);
        TextView live=label("●  EN DIRECT",10,0xFFFF4D5A,true);
        footer.addView(live,new LinearLayout.LayoutParams(0,-2,1));
        TextView engine=buttonText(useVlcPlayer()?"VLC ✓":"VLC",false);
        footer.addView(engine,lp(-2,40));
        engine.setOnClickListener(v->{setVlcPreference(!useVlcPlayer());engine.setText(useVlcPlayer()?"VLC ✓":"VLC");startPlayback(ch);});
        TextView retry=buttonText("↻ RELANCER",false);
        LinearLayout.LayoutParams rep=lp(-2,40);rep.leftMargin=dp(6);footer.addView(retry,rep);
        retry.setOnClickListener(v->startPlayback(ch));
        TextView info=buttonText("ⓘ",false);
        LinearLayout.LayoutParams infop=lp(44,40);infop.leftMargin=dp(6);footer.addView(info,infop);
        info.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(ch.name)
            .setMessage("Groupe : "+ch.group+"\n\nSource : "+(ch.isVavoo?"VAVOO":"IPTV-ORG")+"\n\nLecteur : "+preferredPlayerLabel()+"\n\nLa disponibilité dépend de la source et de ses conditions d’accès.")
            .setPositiveButton("OK",null).show());
        root.addView(footer,lp(-1,-2));
        startPlayback(ch);
    }

    private void startPlayback(Channel ch) {
        releaseMediaEngine();
        if (playerStage != null) playerStage.removeAllViews();
        if(ch.isVavoo){
            Toast.makeText(this,"Résolution du flux VAVOO…",Toast.LENGTH_SHORT).show();
            io.execute(()->{
                try{
                    String resolved=VavooProvider.resolveStream(ch.vavooSourceUrl);
                    runOnUiThread(()->{
                        if(currentChannel!=ch||playerStage==null)return;
                        startResolvedPlayback(ch,resolved);
                    });
                }catch(Exception e){
                    String message=e.getMessage()==null?"Résolution VAVOO impossible":e.getMessage();
                    runOnUiThread(()->{if(currentChannel==ch)Toast.makeText(MainActivity.this,"VAVOO : "+message,Toast.LENGTH_LONG).show();});
                }
            });
        }else{
            startResolvedPlayback(ch,ch.url);
        }
    }

    private void startResolvedPlayback(Channel ch,String streamUrl) {
        if(playerStage==null||currentChannel!=ch)return;
        if(useVlcPlayer()) startVlcPlayback(ch,streamUrl);
        else startMedia3Playback(ch,streamUrl);
    }

    private void startMedia3Playback(Channel ch,String streamUrl) {
        try {
            DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                .setUserAgent(ch.isVavoo?"VAVOO/2.6":"NISSKENZI+/1.9")
                .setAllowCrossProtocolRedirects(true);
            DefaultDataSource.Factory data=new DefaultDataSource.Factory(this,http);
            ExoPlayer active=new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
            player=active;
            playerView=new PlayerView(this);
            playerView.setUseController(true);
            playerView.setKeepContentOnPlayerReset(true);
            playerView.setShutterBackgroundColor(Color.BLACK);
            playerStage.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
            playerView.setPlayer(active);
            active.addListener(new Player.Listener(){
                @Override public void onPlayerError(PlaybackException e){
                    Toast.makeText(MainActivity.this,"Media3 : lecture impossible ("+e.getErrorCodeName()+").",Toast.LENGTH_LONG).show();
                    if(!useVlcPlayer()){
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Essayer un autre lecteur ?")
                            .setMessage("Ce flux peut nécessiter un autre moteur vidéo. VLC est disponible en option.")
                            .setNegativeButton("Pas maintenant",null)
                            .setPositiveButton("Essayer VLC",(dialog,which)->{
                                setVlcPreference(true);
                                startPlayback(ch);
                            }).show();
                    }
                }
            });
            active.setMediaItem(MediaItem.fromUri(streamUrl));
            active.prepare();
            active.setPlayWhenReady(true);
        } catch(Exception e) {
            Toast.makeText(this,"Impossible de démarrer Media3 : "+safe(e.getMessage()),Toast.LENGTH_LONG).show();
        }
    }

    private void startVlcPlayback(Channel ch,String streamUrl) {
        try {
            ArrayList<String> options=new ArrayList<>();
            options.add("--network-caching=1500");
            options.add("--no-drop-late-frames");
            options.add("--no-skip-frames");
            libVLC=new LibVLC(this,options);
            org.videolan.libvlc.MediaPlayer active=new org.videolan.libvlc.MediaPlayer(libVLC);
            vlcPlayer=active;
            vlcVideoLayout=new VLCVideoLayout(this);
            playerStage.addView(vlcVideoLayout,new FrameLayout.LayoutParams(-1,-1));
            active.attachViews(vlcVideoLayout,null,false,false);
            active.setEventListener(event->{
                if(event.type==org.videolan.libvlc.MediaPlayer.Event.EncounteredError){
                    runOnUiThread(()->{
                        if(vlcPlayer!=active||currentChannel!=ch)return;
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("VLC n’a pas pu lire le flux")
                            .setMessage("Le flux est peut-être indisponible ou incompatible. Tu peux tenter le lecteur Media3.")
                            .setNegativeButton("Fermer",null)
                            .setPositiveButton("Essayer Media3",(dialog,which)->{
                                setVlcPreference(false);
                                startPlayback(ch);
                            }).show();
                    });
                }
            });
            Media media=new Media(libVLC,Uri.parse(streamUrl));
            media.setHWDecoderEnabled(true,false);
            media.addOption(":network-caching=1500");
            active.setMedia(media);
            media.release();
            active.play();
        } catch(Exception e) {
            releaseMediaEngine();
            Toast.makeText(this,"Impossible de démarrer VLC : "+safe(e.getMessage()),Toast.LENGTH_LONG).show();
            if(currentChannel==ch&&playerStage!=null){
                setVlcPreference(false);
                startMedia3Playback(ch,streamUrl);
            }
        }
    }

    private void releaseMediaEngine() {
        if(player!=null){
            try{player.stop();player.clearMediaItems();player.release();}catch(Exception ignored){}
            player=null;
        }
        if(playerView!=null){
            try{playerView.setPlayer(null);if(playerStage!=null)playerStage.removeView(playerView);}catch(Exception ignored){}
            playerView=null;
        }
        if(vlcPlayer!=null){
            try{vlcPlayer.stop();vlcPlayer.detachViews();vlcPlayer.release();}catch(Exception ignored){}
            vlcPlayer=null;
        }
        if(libVLC!=null){
            try{libVLC.release();}catch(Exception ignored){}
            libVLC=null;
        }
        if(vlcVideoLayout!=null){
            try{if(playerStage!=null)playerStage.removeView(vlcVideoLayout);}catch(Exception ignored){}
            vlcVideoLayout=null;
        }
    }

    private void returnHome() {
        releasePlayer();
        currentChannel=null;
        buildHome();
        renderPage();
    }

    private void releasePlayer() {
        releaseMediaEngine();
        playerStage=null;
    }

    @Override public void onBackPressed() {
        if(player!=null||vlcPlayer!=null){returnHome();}
        else if("Paramètres".equals(activePage)||"Live TV".equals(activePage)){setPage("Accueil");}
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        releasePlayer();
        io.shutdownNow();
        logoIo.shutdownNow();
        super.onDestroy();
    }
}