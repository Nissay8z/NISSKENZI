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
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native Android Live TV app optimized for Android TV and native UI design.
 */
public class MainActivity extends Activity {
    private static final int PANEL = 0x44FFFFFF;
    private static final int LINE = 0x66FFFFFF;
    private static final int CYAN = 0xFF0A8258;
    private static final int PURPLE = 0xFF16A978;
    private static final int MUTED = 0xFFE8FFF6;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GREEN = 0xFF0E714A;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int CHANNELS_PER_BATCH = 28;
    private static final int MAX_CACHED_CHANNELS = 400;
    private static final int MAX_CATALOG_CHANNELS = 9000;
    private static final int MAX_LOGO_CACHE = 72;

    private LinearLayout root;
    private FrameLayout contentHost;
    private TextView statusView;
    private TextView countView;
    private LinearLayout countryChoices;
    private LinearLayout channelResults;
    private EditText searchInput;
    private EditText categorySearchInput;
    private TextView liveTab;
    private TextView vavooTab;
    private TextView iptvTab;
    private TextView settingsTab;
    private TextView previewTitle;
    private TextView previewStatus;
    private TextView previewDetail;

    private PlayerView playerView;
    private ExoPlayer player;
    private FrameLayout playerStage;
    private LibVLC libVLC;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private VLCVideoLayout vlcVideoLayout;
    private Channel currentChannel;
    private volatile String activePage = "Live TV";
    private volatile String catalogStatus = "Préparation du catalogue…";
    private volatile boolean loadingCatalog = false;
    private volatile boolean loadingVavoo = false;
    private volatile boolean loadingIptv = false;
    private volatile boolean activityDestroyed = false;

    private final AtomicInteger loadGeneration = new AtomicInteger(0);

    private int visibleChannelLimit = CHANNELS_PER_BATCH;
    private int focusChannelIndex = -1;
    private String activeCountry = "Tous les pays";
    private String sourceFilter = "VAVOO";
    private String searchText = "";
    private String categorySearch = "";
    private boolean favoritesOnly = false;
    private boolean recentOnly = false;

    private final ArrayList<Channel> channels = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2);
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
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
        TextView b = label(text, isTelevision() ? 15 : 12, selected ? YELLOW : Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        int padH = isTelevision() ? 18 : 13;
        int padV = isTelevision() ? 11 : 8;
        b.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        b.setBackground(rounded(selected ? 0xAA08653F : 0x44FFFFFF, 22));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                b.setBackground(outlined(0xAA087449, 22, 0xDDFFFFFF));
                b.setTextColor(YELLOW);
                b.setScaleX(1.035f);
                b.setScaleY(1.035f);
            } else {
                boolean active = b == liveTab ? "Live TV".equals(activePage)
                        : b == vavooTab ? ("Live TV".equals(activePage) && "VAVOO".equals(sourceFilter))
                        : b == iptvTab ? ("Live TV".equals(activePage) && "IPTV-ORG".equals(sourceFilter))
                        : b == settingsTab ? "Paramètres".equals(activePage) : selected;
                b.setBackground(rounded(active ? 0xAA08653F : 0x44FFFFFF, 22));
                b.setTextColor(active ? YELLOW : Color.WHITE);
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
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF24C982, 0xFF44CDA5, 0xFF65C7D1});
        root.setBackground(backdrop);
        setContentView(root);

        // Barre supérieure large et native Android TV
        LinearLayout header = horizontal();
        int headerPad = isTelevision() ? 26 : 14;
        header.setPadding(dp(headerPad), dp(isTelevision() ? 10 : 8),
                dp(headerPad), dp(isTelevision() ? 10 : 8));
        header.setBackgroundColor(0x332B805E);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView back = buttonText("‹", false);
        back.setContentDescription("Retour");
        header.addView(back, lp(isTelevision() ? 44 : 38, isTelevision() ? 48 : 40));
        back.setOnClickListener(v -> onBackPressed());

        liveTab = buttonText("Live TV", true);
        vavooTab = buttonText("VAVOO", "VAVOO".equals(sourceFilter));
        iptvTab = buttonText("IPTV-ORG", "IPTV-ORG".equals(sourceFilter));
        settingsTab = buttonText("⚙", "Paramètres".equals(activePage));

        LinearLayout.LayoutParams navp = lp(-2, isTelevision() ? 48 : 40);
        navp.leftMargin = dp(8);
        header.addView(liveTab, navp);

        LinearLayout.LayoutParams sourceP = lp(-2, isTelevision() ? 48 : 40);
        sourceP.leftMargin = dp(8);
        header.addView(vavooTab, sourceP);

        LinearLayout.LayoutParams iptvP = lp(-2, isTelevision() ? 48 : 40);
        iptvP.leftMargin = dp(8);
        header.addView(iptvTab, iptvP);

        liveTab.setOnClickListener(v -> setPage("Live TV"));
        vavooTab.setOnClickListener(v -> switchSource("VAVOO"));
        iptvTab.setOnClickListener(v -> switchSource("IPTV-ORG"));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(0xEEFFFFFF);
        searchInput.setTextSize(isTelevision() ? 16 : 12);
        searchInput.setHint("⌕  Rechercher une chaîne…");
        searchInput.setPadding(dp(18), 0, dp(18), 0);
        searchInput.setBackground(outlined(0x33FFFFFF, 24, 0x44FFFFFF));
        searchInput.setText(searchText);
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(0, dp(isTelevision() ? 48 : 40), 1f);
        searchP.leftMargin = dp(isTelevision() ? 20 : 10);
        searchP.rightMargin = dp(isTelevision() ? 20 : 10);
        header.addView(searchInput, searchP);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchText = s == null ? "" : s.toString();
                visibleChannelLimit = CHANNELS_PER_BATCH;
                focusChannelIndex = -1;
                updateChannelResults();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        header.addView(settingsTab, lp(isTelevision() ? 46 : 40, isTelevision() ? 48 : 40));
        settingsTab.setOnClickListener(v -> setPage("Paramètres"));

        TextView mark = label("N+", isTelevision() ? 24 : 20, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(0x44FFFFFF, 16));
        LinearLayout.LayoutParams markP = lp(isTelevision() ? 54 : 44, isTelevision() ? 48 : 40);
        markP.leftMargin = dp(10);
        header.addView(mark, markP);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));

        statusView = label(catalogStatus, isTelevision() ? 11 : 9, Color.WHITE, false);
        statusView.setMaxLines(1);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(dp(headerPad), dp(6), dp(headerPad), dp(6));
        statusView.setBackgroundColor(0x2240655D);
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        updateHeaderTabs();
        renderPage();
    }

    private void setPage(String p) {
        activePage = "Accueil".equals(p) ? "Live TV" : p;
        if ("Paramètres".equals(activePage)) {
            releaseMediaEngine();
            currentChannel = null;
        }
        updateHeaderTabs();
        renderPage();
    }

    private void updateHeaderTabs() {
        setTabState(liveTab, "Live TV".equals(activePage));
        setTabState(vavooTab, "Live TV".equals(activePage) && "VAVOO".equals(sourceFilter));
        setTabState(iptvTab, "Live TV".equals(activePage) && "IPTV-ORG".equals(sourceFilter));
        setTabState(settingsTab, "Paramètres".equals(activePage));
    }

    private void setTabState(TextView tab, boolean selected) {
        if (tab == null) return;
        tab.setBackground(rounded(selected ? 0xAA08653F : 0x44FFFFFF, 22));
        tab.setTextColor(selected ? YELLOW : Color.WHITE);
    }

    private void renderPage() {
        if (contentHost == null) return;
        contentHost.removeAllViews();
        if ("Paramètres".equals(activePage)) renderSettings();
        else renderLivePage();
    }

    private void renderLivePage() {
        LinearLayout body = horizontal();
        body.setGravity(Gravity.FILL);
        contentHost.addView(body, new FrameLayout.LayoutParams(-1, -1));

        // --- COLONNE GAUCHE : Catégories, Récemment vus, Favoris et Filtres pays ---
        LinearLayout left = vertical();
        left.setPadding(dp(18), dp(16), dp(14), dp(12));
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 3.0f));

        View dividerOne = new View(this);
        dividerOne.setBackgroundColor(LINE);
        body.addView(dividerOne, new LinearLayout.LayoutParams(dp(1), -1));

        // --- COLONNE DU MILIEU : Liste compacte de chaînes avec index, logo et nom ---
        LinearLayout middle = vertical();
        middle.setPadding(dp(16), dp(16), dp(16), dp(12));
        body.addView(middle, new LinearLayout.LayoutParams(0, -1, 3.25f));

        View dividerTwo = new View(this);
        dividerTwo.setBackgroundColor(LINE);
        body.addView(dividerTwo, new LinearLayout.LayoutParams(dp(1), -1));

        // --- COLONNE DROITE : Lecteur/Aperçu noir au-dessus et actions Favori en dessous ---
        LinearLayout right = vertical();
        right.setPadding(dp(18), dp(18), dp(20), dp(14));
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 3.75f));

        TextView catTitle = label("CATEGORIES & PAYS", isTelevision() ? 11 : 9, Color.WHITE, true);
        catTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams ctp = lp(-1, -2);
        ctp.bottomMargin = dp(8);
        left.addView(catTitle, ctp);

        categorySearchInput = new EditText(this);
        categorySearchInput.setSingleLine(true);
        categorySearchInput.setTextSize(isTelevision() ? 14 : 11);
        categorySearchInput.setTextColor(Color.WHITE);
        categorySearchInput.setHintTextColor(0xEEFFFFFF);
        categorySearchInput.setHint("Filtrer les pays…");
        categorySearchInput.setPadding(dp(14), 0, dp(14), 0);
        categorySearchInput.setBackground(outlined(0x33FFFFFF, 18, 0x44FFFFFF));
        categorySearchInput.setText(categorySearch);
        left.addView(categorySearchInput, lp(-1, isTelevision() ? 46 : 40));
        categorySearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                categorySearch = s == null ? "" : s.toString();
                renderCountryChoices();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        ScrollView categoryScroll = new ScrollView(this);
        categoryScroll.setVerticalScrollBarEnabled(false);
        countryChoices = vertical();
        countryChoices.setPadding(0, dp(8), 0, dp(8));
        categoryScroll.addView(countryChoices);
        LinearLayout.LayoutParams catScrollP = new LinearLayout.LayoutParams(-1, 0, 1f);
        catScrollP.topMargin = dp(6);
        left.addView(categoryScroll, catScrollP);

        // En-tête de la liste centrale
        LinearLayout middleHeader = horizontal();
        TextView sourceTitle = label(sourceFilter, isTelevision() ? 24 : 18, Color.WHITE, true);
        middleHeader.addView(sourceTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        countView = label(channels.size() + " chaînes", isTelevision() ? 13 : 10, Color.WHITE, true);
        countView.setBackground(rounded(0x33FFFFFF, 15));
        countView.setPadding(dp(11), dp(6), dp(11), dp(6));
        middleHeader.addView(countView);
        middle.addView(middleHeader, lp(-1, -2));

        TextView listSubtitle = label(
                "Flux direct · " + ("VAVOO".equals(sourceFilter) ? "VAVOO Server" : "IPTV-ORG Playlist"),
                isTelevision() ? 12 : 9, 0xEEFFFFFF, false);
        LinearLayout.LayoutParams lsp = lp(-1, -2);
        lsp.topMargin = dp(4);
        lsp.bottomMargin = dp(10);
        middle.addView(listSubtitle, lsp);

        ScrollView channelScroll = new ScrollView(this);
        channelScroll.setVerticalScrollBarEnabled(true);
        channelScroll.setFillViewport(false);
        channelResults = vertical();
        channelScroll.addView(channelResults);
        middle.addView(channelScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Colonne de droite : Aperçu & Actions
        previewTitle = label(currentChannel == null ? "Aperçu du direct" : safe(currentChannel.name),
                isTelevision() ? 21 : 16, Color.WHITE, true);
        previewTitle.setMaxLines(2);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(previewTitle, lp(-1, -2));

        previewStatus = label(currentChannel == null ? "Sélectionnez une chaîne pour démarrer" : "Connexion au flux…",
                isTelevision() ? 12 : 10, 0xEEFFFFFF, false);
        LinearLayout.LayoutParams psp = lp(-1, -2);
        psp.topMargin = dp(5);
        psp.bottomMargin = dp(12);
        right.addView(previewStatus, psp);

        playerStage = new FrameLayout(this);
        playerStage.setBackgroundColor(Color.BLACK);
        playerStage.setFocusable(false);
        LinearLayout.LayoutParams stageP = lp(-1, isTelevision() ? 280 : 200);
        right.addView(playerStage, stageP);

        LinearLayout actions = horizontal();
        LinearLayout.LayoutParams actionsP = lp(-1, isTelevision() ? 48 : 40);
        actionsP.topMargin = dp(14);
        right.addView(actions, actionsP);

        TextView favorite = buttonText(currentChannel != null && isFavorite(currentChannel) ? "★ Favori" : "♡ Favori",
                currentChannel != null && isFavorite(currentChannel));
        actions.addView(favorite, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView stop = buttonText("■ Arrêter", false);
        LinearLayout.LayoutParams stopP = lp(-2, -1);
        stopP.leftMargin = dp(8);
        actions.addView(stop, stopP);

        favorite.setOnClickListener(v -> {
            if (currentChannel == null) {
                Toast.makeText(this, "Sélectionnez une chaîne d’abord.", Toast.LENGTH_SHORT).show();
                return;
            }
            toggleFavorite(currentChannel);
            favorite.setText(isFavorite(currentChannel) ? "★ Favori" : "♡ Favori");
            favorite.setTextColor(isFavorite(currentChannel) ? YELLOW : Color.WHITE);
            renderCountryChoices();
            updateChannelResults();
        });
        stop.setOnClickListener(v -> stopPreview());

        previewDetail = label(currentChannel == null
                ? "La lecture s'intègre directement ici."
                : ("SOURCE  " + sourceFilter + "     ·     PAYS  " + safeCountry(currentChannel.country)),
                isTelevision() ? 13 : 10, Color.WHITE, false);
        previewDetail.setPadding(0, dp(12), 0, 0);
        right.addView(previewDetail, lp(-1, -2));

        if (currentChannel == null) {
            TextView placeholder = label("▶\n\nPrêt pour la lecture", isTelevision() ? 17 : 13, 0xCCFFFFFF, true);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackgroundColor(Color.BLACK);
            playerStage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        }

        renderCountryChoices();
        updateChannelResults();
    }

    private void renderCountryChoices() {
        if (countryChoices == null) return;
        countryChoices.removeAllViews();

        addCategoryRow("Tous", channels.size(),
                !favoritesOnly && !recentOnly && "Tous les pays".equals(activeCountry),
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = false;
                    recentOnly = false;
                    visibleChannelLimit = CHANNELS_PER_BATCH;
                    focusChannelIndex = -1;
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Récemment vus", countRecentChannels(), recentOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = false;
                    recentOnly = true;
                    visibleChannelLimit = CHANNELS_PER_BATCH;
                    focusChannelIndex = -1;
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Favoris", countFavoriteChannels(), favoritesOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = true;
                    recentOnly = false;
                    visibleChannelLimit = CHANNELS_PER_BATCH;
                    focusChannelIndex = -1;
                    renderCountryChoices();
                    updateChannelResults();
                });

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams divP = lp(-1, 1);
        divP.topMargin = dp(8);
        divP.bottomMargin = dp(8);
        countryChoices.addView(divider, divP);

        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        for (Channel ch : channels) {
            String country = safeCountry(ch.country);
            counts.put(country, counts.containsKey(country) ? counts.get(country) + 1 : 1);
            if (!codes.containsKey(country) || codes.get(country).isEmpty()) codes.put(country, countryCode(country));
        }
        ArrayList<String> names = new ArrayList<>(counts.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        String query = categorySearch == null ? "" : categorySearch.trim().toLowerCase(Locale.ROOT);
        for (String country : names) {
            if (!query.isEmpty() && !country.toLowerCase(Locale.ROOT).contains(query)) continue;
            String title = flag(country, codes.get(country)) + "  " + country;
            addCategoryRow(title, counts.get(country),
                    country.equals(activeCountry) && !favoritesOnly && !recentOnly,
                    v -> {
                        activeCountry = country;
                        favoritesOnly = false;
                        recentOnly = false;
                        visibleChannelLimit = CHANNELS_PER_BATCH;
                        focusChannelIndex = -1;
                        renderCountryChoices();
                        updateChannelResults();
                    });
        }
    }

    private void addCategoryRow(String title, int count, boolean selected, View.OnClickListener click) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(8), dp(12), dp(8));
        row.setBackground(rounded(selected ? 0xAA087449 : 0x33FFFFFF, 8));
        row.setFocusable(true);
        row.setClickable(true);
        TextView name = label(title, isTelevision() ? 14 : 11, selected ? YELLOW : Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView number = label(String.valueOf(count), isTelevision() ? 14 : 11,
                selected ? YELLOW : Color.WHITE, true);
        number.setGravity(Gravity.CENTER);
        row.addView(number, lp(30, -2));
        LinearLayout.LayoutParams rowP = lp(-1, isTelevision() ? 45 : 38);
        rowP.bottomMargin = dp(6);
        countryChoices.addView(row, rowP);
        row.setOnFocusChangeListener((view, focused) -> {
            row.setBackground(outlined(focused || selected ? 0xAA087449 : 0x33FFFFFF,
                    8, focused ? 0xDDFFFFFF : 0x00FFFFFF));
            name.setTextColor(focused || selected ? YELLOW : Color.WHITE);
            number.setTextColor(focused || selected ? YELLOW : Color.WHITE);
            row.setScaleX(focused ? 1.025f : 1f);
            row.setScaleY(focused ? 1.025f : 1f);
        });
        row.setOnClickListener(click);
    }

    private ArrayList<String> readRecentUrls() {
        ArrayList<String> result = new ArrayList<>();
        String raw = getSharedPreferences("recent", MODE_PRIVATE).getString("urls", "");
        if (!raw.isEmpty()) {
            for (String item : raw.split("\n")) {
                String url = item.trim();
                if (!url.isEmpty() && !result.contains(url)) result.add(url);
            }
        }
        return result;
    }

    private void rememberRecentlyViewed(Channel ch) {
        if (ch == null || safe(ch.url).isEmpty()) return;
        ArrayList<String> recent = readRecentUrls();
        recent.remove(ch.url);
        recent.add(0, ch.url);
        while (recent.size() > 100) recent.remove(recent.size() - 1);
        getSharedPreferences("recent", MODE_PRIVATE).edit()
                .putString("urls", TextUtils.join("\n", recent)).apply();
    }

    private int countFavoriteChannels() {
        int count = 0;
        for (Channel ch : channels) if (isFavorite(ch)) count++;
        return count;
    }

    private int countRecentChannels() {
        ArrayList<String> recent = readRecentUrls();
        int count = 0;
        for (Channel ch : channels) if (recent.contains(ch.url)) count++;
        return count;
    }

    private ArrayList<Channel> filteredChannels() {
        String q = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        ArrayList<String> recent = recentOnly ? readRecentUrls() : new ArrayList<>();
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel ch : channels) {
            String country = safeCountry(ch.country);
            String src = ch.isVavoo ? "VAVOO" : "IPTV-ORG";
            if (!sourceFilter.equals(src)) continue;
            if (!"Tous les pays".equals(activeCountry) && !activeCountry.equals(country)) continue;
            if (favoritesOnly && !isFavorite(ch)) continue;
            if (recentOnly && !recent.contains(ch.url)) continue;
            if (!q.isEmpty() && !(safe(ch.name) + " " + safe(ch.group) + " " + country)
                    .toLowerCase(Locale.ROOT).contains(q)) continue;
            result.add(ch);
        }
        return result;
    }

    private void updateChannelResults() {
        if (channelResults == null || !"Live TV".equals(activePage)) return;
        ArrayList<Channel> filtered = filteredChannels();
        if (countView != null) countView.setText(filtered.size() + " chaînes");
        channelResults.removeAllViews();

        if (filtered.isEmpty()) {
            LinearLayout empty = vertical();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(22), dp(14), dp(22));
            empty.setBackground(rounded(0x33FFFFFF, 12));
            String source = sourceFilter;
            TextView heading = label(channels.isEmpty()
                    ? (loadingCatalog ? "Connexion au catalogue…" : "Catalogue prêt à charger")
                    : "Aucune chaîne trouvée", isTelevision() ? 18 : 14, Color.WHITE, true);
            heading.setGravity(Gravity.CENTER);
            empty.addView(heading, lp(-1, -2));
            TextView msg = label(channels.isEmpty()
                    ? source + " se charge en arrière-plan. Vous pouvez naviguer ou actualiser."
                    : "Essayez une autre catégorie ou modifiez votre recherche.",
                    isTelevision() ? 12 : 10, 0xEEFFFFFF, false);
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, dp(8), 0, dp(12));
            empty.addView(msg, lp(-1, -2));
            TextView retry = buttonText(loadingCatalog ? "Chargement…" : "↻ Actualiser " + source, true);
            empty.addView(retry, lp(-2, isTelevision() ? 46 : 38));
            retry.setOnClickListener(v -> loadChannels());
            channelResults.addView(empty, lp(-1, -2));
            return;
        }

        int shown = Math.min(filtered.size(), visibleChannelLimit);
        for (int i = 0; i < shown; i++) {
            Channel ch = filtered.get(i);
            LinearLayout card = channelCard(ch, i);
            LinearLayout.LayoutParams cp = lp(-1, isTelevision() ? 63 : 54);
            cp.bottomMargin = dp(5);
            channelResults.addView(card, cp);
            if (i == focusChannelIndex) {
                focusChannelIndex = -1;
                if (isTelevision()) card.post(() -> card.requestFocus());
            }
        }

        if (shown < filtered.size()) {
            int addCount = Math.min(CHANNELS_PER_BATCH, filtered.size() - shown);
            TextView more = buttonText("Afficher " + addCount + " de plus  ·  " + shown + " / " + filtered.size(), false);
            more.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams moreP = lp(-1, isTelevision() ? 48 : 40);
            moreP.topMargin = dp(6);
            moreP.bottomMargin = dp(10);
            channelResults.addView(more, moreP);
            more.setOnClickListener(v -> {
                focusChannelIndex = shown;
                visibleChannelLimit = Math.min(filtered.size(), visibleChannelLimit + CHANNELS_PER_BATCH);
                updateChannelResults();
            });
        }
    }

    private LinearLayout channelCard(Channel ch, int index) {
        boolean selected = ch == currentChannel;
        LinearLayout card = horizontal();
        card.setPadding(dp(isTelevision() ? 11 : 8), dp(6), dp(9), dp(6));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(outlined(selected ? 0xAA087449 : 0x33FFFFFF, 8,
                selected ? 0xBBFFFFFF : 0x00FFFFFF));
        card.setFocusable(true);
        card.setClickable(true);

        TextView number = label(String.valueOf(index + 1), isTelevision() ? 15 : 12,
                selected ? YELLOW : Color.WHITE, true);
        number.setGravity(Gravity.CENTER);
        card.addView(number, lp(isTelevision() ? 30 : 26, -1));

        FrameLayout logoFrame = new FrameLayout(this);
        logoFrame.setBackground(rounded(0xEEFFFFFF, 7));
        TextView initials = label(initials(ch.name), 10, 0xFF176647, true);
        initials.setGravity(Gravity.CENTER);
        logoFrame.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(3), dp(3), dp(3), dp(3));
        logoFrame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams logoP = lp(isTelevision() ? 44 : 38, isTelevision() ? 44 : 38);
        logoP.rightMargin = dp(10);
        card.addView(logoFrame, logoP);
        loadLogo(ch.logo, image, initials);

        LinearLayout info = vertical();
        info.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView name = label(safe(ch.name), isTelevision() ? 14 : 11,
                selected ? YELLOW : Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name, lp(-1, -2));

        String group = safe(ch.group).replace("VAVOO · ", "").replace("IPTV-ORG · ", "");
        TextView meta = label(flag(ch.country, countryCode(ch.country)) + "  " + safeCountry(ch.country)
                + (group.isEmpty() ? "" : "  ·  " + group),
                isTelevision() ? 9 : 8, 0xEEFFFFFF, false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaP = lp(-1, -2);
        metaP.topMargin = dp(2);
        info.addView(meta, metaP);

        if (isFavorite(ch)) {
            TextView star = label("★", isTelevision() ? 15 : 12, YELLOW, true);
            card.addView(star, lp(22, -2));
        }
        card.setOnFocusChangeListener((view, focused) -> {
            card.setBackground(outlined(focused || selected ? 0xAA087449 : 0x33FFFFFF, 8,
                    focused ? 0xDDFFFFFF : (selected ? 0xBBFFFFFF : 0x00FFFFFF)));
            name.setTextColor(focused || selected ? YELLOW : Color.WHITE);
            number.setTextColor(focused || selected ? YELLOW : Color.WHITE);
            card.setScaleX(focused ? 1.02f : 1f);
            card.setScaleY(focused ? 1.02f : 1f);
        });
        card.setOnClickListener(v -> {
            card.setBackground(outlined(0xAA087449, 8, 0xBBFFFFFF));
            play(ch);
        });
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
                conn.setConnectTimeout(5000); conn.setReadTimeout(7000);
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
        page.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1,-1));

        page.addView(label("Paramètres", 26, TEXT, true));
        TextView intro = label("Gestion des moteurs de catalogues et des lecteurs multimédias.", 11, MUTED, false);
        LinearLayout.LayoutParams ip = lp(-1,-2); ip.topMargin=dp(6); ip.bottomMargin=dp(20); page.addView(intro,ip);

        TextView playerHeading = label("LECTEUR VIDÉO", 9, MUTED, true);
        page.addView(playerHeading);

        TextView playerMode = buttonText("Lecteur préféré : " + preferredPlayerLabel(), true);
        LinearLayout.LayoutParams pmp = lp(-1, 48); pmp.topMargin = dp(8); page.addView(playerMode, pmp);
        
        TextView playerHelp = label("Media3 est léger. VLC apporte un moteur alternatif pour les flux complexes.", 10, MUTED, false);
        LinearLayout.LayoutParams php = lp(-1,-2); php.topMargin = dp(6); php.bottomMargin = dp(18); page.addView(playerHelp, php);
        playerMode.setOnClickListener(v -> {
            setVlcPreference(!useVlcPlayer());
            renderSettings();
            Toast.makeText(this, "Lecteur préféré : " + preferredPlayerLabel(), Toast.LENGTH_SHORT).show();
        });

        page.addView(sourceCard("VAVOO", "V", "Catalogue IPTV dynamique", "Récupération directe des flux et des métadonnées.", CYAN));
        LinearLayout.LayoutParams gap = lp(-1,-2); gap.topMargin = dp(14);
        page.addView(sourceCard("IPTV-ORG", "◉", "Playlist publique internationale", "Catalogue mondial structuré par pays.", PURPLE), gap);

        LinearLayout statusCard = vertical();
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusCard.setBackground(outlined(PANEL, 14, LINE));
        LinearLayout.LayoutParams sp = lp(-1,-2); sp.topMargin = dp(16); page.addView(statusCard, sp);
        
        statusCard.addView(label("ÉTAT DU CATALOGUE", 9, MUTED, true));
        TextView details = label(catalogStatus, 11, TEXT, false);
        details.setTextIsSelectable(true);
        details.setPadding(0, dp(10), 0, dp(4));
        statusCard.addView(details);

        TextView refresh = buttonText(loadingCatalog ? "Actualisation en cours…" : "↻  Actualiser " + sourceFilter, true);
        LinearLayout.LayoutParams rp = lp(-1, 48); rp.topMargin = dp(16); page.addView(refresh, rp);
        refresh.setOnClickListener(v -> loadChannels());
        if (loadingCatalog) refresh.setAlpha(0.65f);

        TextView cache = buttonText("Effacer le cache local", false);
        LinearLayout.LayoutParams cp = lp(-1, 46); cp.topMargin = dp(10); page.addView(cache, cp);
        cache.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Effacer le cache ?")
            .setMessage("Les chaînes enregistrées sur cet appareil seront supprimées. Vos favoris sont conservés.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Effacer", (dialog, which) -> {
                getSharedPreferences("catalog_cache", MODE_PRIVATE).edit().clear().apply();
                Toast.makeText(this, "Cache local effacé.", Toast.LENGTH_SHORT).show();
                channels.clear(); activeCountry = "Tous les pays"; renderPage();
            }).show());

        TextView note = label("NISSKENZI+ utilise exclusivement VAVOO et IPTV-ORG. La disponibilité des flux dépend des sources externes.", 10, MUTED, false);
        note.setPadding(dp(2), dp(14), dp(2), 0);
        page.addView(note);
    }

    private View sourceCard(String title, String icon, String tagline, String description, int accent) {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(outlined(PANEL, 14, LINE));
        LinearLayout head = horizontal();
        TextView mark = label(icon, 21, accent, true); mark.setGravity(Gravity.CENTER); mark.setBackground(rounded(0xFF182840, 10));
        head.addView(mark, lp(44, 44));
        LinearLayout names = vertical(); names.setPadding(dp(12), 0, 0, 0);
        names.addView(label(title, 15, TEXT, true));
        names.addView(label(tagline, 10, accent, false));
        head.addView(names);
        card.addView(head);
        TextView desc = label(description, 11, MUTED, false);
        desc.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams dp = lp(-1,-2); dp.topMargin = dp(12); card.addView(desc, dp);
        TextView state = label("État : " + sourceStatus(title), 10, GREEN, true);
        LinearLayout.LayoutParams stp = lp(-1,-2); stp.topMargin = dp(8); card.addView(state, stp);
        return card;
    }

    private String sourceStatus(String source) {
        boolean loading = "VAVOO".equals(source) ? loadingVavoo : loadingIptv;
        if (loading) return "chargement en cours";
        if (source.equals(sourceFilter) && !channels.isEmpty()) return "catalogue chargé · " + channels.size();
        String key = "VAVOO".equals(source) ? "vavoo_channels" : "iptv_org_channels";
        if (!getSharedPreferences("catalog_cache", MODE_PRIVATE).getString(key, "").isEmpty()) {
            return "cache disponible";
        }
        return "pas encore chargé";
    }

    private void loadCachedChannels() {
        try {
            SharedPreferences prefs = getSharedPreferences("catalog_cache", MODE_PRIVATE);
            String key = "VAVOO".equals(sourceFilter) ? "vavoo_channels" : "iptv_org_channels";
            String raw = prefs.getString(key, "");
            if (raw.isEmpty()) return;
            if (raw.length() > 1_500_000) {
                prefs.edit().remove(key).apply();
                return;
            }
            JSONArray arr = new JSONArray(raw);
            ArrayList<Channel> cached = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || cached.size() >= MAX_CACHED_CHANNELS) continue;
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                boolean isVavoo = o.optBoolean("isVavoo", false);
                if (isVavoo != "VAVOO".equals(sourceFilter)) continue;
                String group = o.optString("group", "Direct");
                String country = knownCountry(o.optString("country", ""));
                if (country.isEmpty()) {
                    String prefix = isVavoo ? "VAVOO · " : "IPTV-ORG · ";
                    country = knownCountry(group.startsWith(prefix) ? group.substring(prefix.length()) : group);
                }
                if (country.isEmpty()) country = "Pays non précisé";
                cached.add(new Channel(o.optString("name", "Chaîne"), url,
                        o.optString("logo", ""), group, isVavoo,
                        o.optString("sourceUrl", ""), country));
            }
            if (!cached.isEmpty()) {
                channels.clear();
                channels.addAll(cached);
                catalogStatus = sourceFilter + " · " + cached.size() + " chaînes en cache, actualisation…";
                updateStatus();
                renderPage();
            }
        } catch (Exception ignored) {}
    }

    private void saveCachedChannels(ArrayList<Channel> items, String source) {
        try {
            JSONArray arr = new JSONArray();
            int limit = 0;
            for (Channel ch : items) {
                if (limit++ >= MAX_CACHED_CHANNELS) break;
                JSONObject o = new JSONObject();
                o.put("name", safe(ch.name));
                o.put("url", safe(ch.url));
                o.put("logo", safe(ch.logo));
                o.put("group", safe(ch.group));
                o.put("isVavoo", ch.isVavoo);
                o.put("sourceUrl", safe(ch.vavooSourceUrl));
                o.put("country", safe(ch.country));
                arr.put(o);
            }
            String key = "VAVOO".equals(source) ? "vavoo_channels" : "iptv_org_channels";
            getSharedPreferences("catalog_cache", MODE_PRIVATE).edit()
                    .putString(key, arr.toString())
                    .putLong(key + "_updated_at", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    private ArrayList<Channel> cacheableChannels(ArrayList<Channel> items) {
        ArrayList<Channel> cached = new ArrayList<>(Math.min(items.size(), MAX_CACHED_CHANNELS));
        for (Channel ch : items) {
            if (cached.size() >= MAX_CACHED_CHANNELS) break;
            cached.add(ch);
        }
        return cached;
    }

    private void switchSource(String source) {
        boolean changed = !source.equals(sourceFilter);
        if (!changed && "Live TV".equals(activePage) && !channels.isEmpty()) return;
        loadGeneration.incrementAndGet();
        releaseMediaEngine();
        currentChannel = null;
        playerStage = null;
        activePage = "Live TV";
        sourceFilter = source;
        if (changed) {
            channels.clear();
            activeCountry = "Tous les pays";
            favoritesOnly = false;
            recentOnly = false;
            categorySearch = "";
            searchText = "";
            visibleChannelLimit = CHANNELS_PER_BATCH;
            focusChannelIndex = -1;
            if (searchInput != null) searchInput.setText("");
        }
        updateHeaderTabs();
        if (changed) loadCachedChannels();
        renderPage();
        if (changed || channels.isEmpty()) loadChannels();
    }

    private void loadChannels() {
        final String requestedSource = "IPTV-ORG".equals(sourceFilter) ? "IPTV-ORG" : "VAVOO";
        final boolean requestVavoo = "VAVOO".equals(requestedSource);
        if (requestVavoo ? loadingVavoo : loadingIptv) return;

        if (requestVavoo) loadingVavoo = true; else loadingIptv = true;
        loadingCatalog = loadingVavoo || loadingIptv;
        catalogStatus = requestedSource + " · chargement en arrière-plan…";
        updateStatus();

        if ("Live TV".equals(activePage) && channels.isEmpty()) updateChannelResults();
        if ("Paramètres".equals(activePage)) renderSettings();

        final int currentGen = loadGeneration.incrementAndGet();

        io.execute(() -> {
            ArrayList<Channel> loaded = new ArrayList<>();
            String error = "";
            if (requestVavoo) {
                try {
                    for (VavooProvider.VavooChannel item : VavooProvider.loadChannels()) {
                        if (loaded.size() >= MAX_CATALOG_CHANNELS) break;
                        String country = knownCountry(item.country);
                        if (country.isEmpty()) country = knownCountry(item.group);
                        if (country.isEmpty()) country = "Pays non précisé";
                        loaded.add(new Channel(item.name, "vavoo://" + item.id, item.logo,
                                "VAVOO · " + (item.group == null || item.group.trim().isEmpty()
                                ? "Direct" : item.group), true, item.sourceUrl, country));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? "catalogue VAVOO indisponible" : e.getMessage();
                }
            } else {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL("https://iptv-org.github.io/iptv/index.country.m3u");
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "NISSKENZI+/2.0 (Android TV)");
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        String name = "Chaîne", logo = "", group = "Pays non précisé", country = "Pays non précisé";
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("#EXTINF:")) {
                                int comma = line.lastIndexOf(',');
                                name = comma >= 0 ? line.substring(comma + 1).trim() : "Chaîne";
                                logo = "";
                                group = "Pays non précisé";
                                country = "Pays non précisé";
                                String lg = attribute(line, "tvg-logo");
                                if (!lg.isEmpty()) logo = lg;
                                String grouped = attribute(line, "group-title");
                                if (!grouped.isEmpty()) group = grouped;
                                String rawCountry = attribute(line, "tvg-country");
                                if (rawCountry.isEmpty()) rawCountry = attribute(line, "country");
                                String resolvedCountry = knownCountry(rawCountry);
                                if (resolvedCountry.isEmpty()) resolvedCountry = knownCountry(group);
                                country = resolvedCountry.isEmpty() ? "Pays non précisé" : resolvedCountry;
                            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                                if (loaded.size() >= MAX_CATALOG_CHANNELS) break;
                                loaded.add(new Channel(name, line.trim(), logo,
                                        "IPTV-ORG · " + group, false, null, country));
                            }
                        }
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? "playlist IPTV-ORG indisponible" : e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            final ArrayList<Channel> result = loaded;
            final String loadError = error;
            final ArrayList<Channel> cacheSnapshot = cacheableChannels(result);
            if (!cacheSnapshot.isEmpty()) saveCachedChannels(cacheSnapshot, requestedSource);

            runOnUiThread(() -> {
                if (requestVavoo) loadingVavoo = false; else loadingIptv = false;
                loadingCatalog = loadingVavoo || loadingIptv;

                if (activityDestroyed) return;
                if (currentGen != loadGeneration.get()) {
                    // If the user returned to this source while its previous request was
                    // still running, retry now that the old request has released its loading flag.
                    if (requestedSource.equals(sourceFilter) && !isFinishing()) loadChannels();
                    return;
                }

                if (requestedSource.equals(sourceFilter)) {
                    if (!result.isEmpty()) {
                        channels.clear();
                        channels.addAll(result);
                        catalogStatus = requestedSource + " · " + result.size() + " chaînes chargées";
                    } else if (!channels.isEmpty()) {
                        catalogStatus = requestedSource + " · connexion impossible, cache conservé";
                    } else {
                        catalogStatus = requestedSource + " · aucun flux reçu"
                                + (loadError.isEmpty() ? "" : " · " + loadError);
                    }
                    updateStatus();
                    updateHeaderTabs();
                    renderPage();
                    if (result.isEmpty() && channels.isEmpty()) {
                        Toast.makeText(MainActivity.this,
                                requestedSource + " : chargement impossible. Vérifiez votre connexion.",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    updateStatus();
                }
            });
        });
    }

    private void updateStatus() {
        if (statusView != null) statusView.setText(catalogStatus);
    }

    private String attribute(String line, String key) {
        int a = line.indexOf(key + "=");
        if (a < 0) return "";
        int start = a + key.length() + 1;
        if (start < line.length() && line.charAt(start) == '"') start++;
        int end = line.indexOf('"', start);
        if (end < 0) end = line.indexOf(' ', start);
        return end > start ? line.substring(start, end < 0 ? line.length() : end) : "";
    }

    private static String countryKey(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static final Map<String, String> COUNTRY_ALIASES = buildCountryAliases();
    private static Map<String, String> buildCountryAliases() {
        HashMap<String, String> map = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            Locale loc = new Locale("", code);
            String fr = loc.getDisplayCountry(Locale.FRENCH), en = loc.getDisplayCountry(Locale.ENGLISH);
            if (fr == null || fr.trim().isEmpty()) continue;
            map.put(countryKey(code), fr); map.put(countryKey(fr), fr); map.put(countryKey(en), fr);
        }
        map.put(countryKey("UK"), "Royaume-Uni"); map.put(countryKey("GB"), "Royaume-Uni"); map.put(countryKey("Great Britain"), "Royaume-Uni");
        map.put(countryKey("USA"), "États-Unis"); map.put(countryKey("US"), "États-Unis"); map.put(countryKey("United States of America"), "États-Unis");
        map.put(countryKey("South Korea"), "Corée du Sud"); map.put(countryKey("Republic of Korea"), "Corée du Sud");
        map.put(countryKey("North Korea"), "Corée du Nord"); map.put(countryKey("Ivory Coast"), "Côte d’Ivoire");
        map.put(countryKey("Czech Republic"), "Tchéquie"); map.put(countryKey("Russia"), "Russie");
        map.put(countryKey("Moldova"), "Moldavie"); map.put(countryKey("Vietnam"), "Viêt Nam");
        map.put(countryKey("Turkey"), "Turquie"); map.put(countryKey("Türkiye"), "Turquie");
        map.put(countryKey("Taiwan"), "Taïwan"); map.put(countryKey("Vatican City"), "Vatican");
        map.put(countryKey("Palestine"), "Palestine"); map.put(countryKey("Kosovo"), "Kosovo");
        return map;
    }

    private static final Map<String, String> COUNTRY_CODES = buildCountryCodes();
    private static Map<String, String> buildCountryCodes() {
        HashMap<String, String> map = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            Locale loc = new Locale("", code);
            map.put(countryKey(code), code);
            map.put(countryKey(loc.getDisplayCountry(Locale.FRENCH)), code);
            map.put(countryKey(loc.getDisplayCountry(Locale.ENGLISH)), code);
        }
        map.put(countryKey("UK"), "GB"); map.put(countryKey("Great Britain"), "GB");
        map.put(countryKey("USA"), "US"); map.put(countryKey("United States of America"), "US");
        map.put(countryKey("South Korea"), "KR"); map.put(countryKey("Republic of Korea"), "KR");
        map.put(countryKey("North Korea"), "KP"); map.put(countryKey("Ivory Coast"), "CI");
        map.put(countryKey("Czech Republic"), "CZ"); map.put(countryKey("Russia"), "RU");
        map.put(countryKey("Moldova"), "MD"); map.put(countryKey("Vietnam"), "VN");
        map.put(countryKey("Turkey"), "TR"); map.put(countryKey("Türkiye"), "TR");
        map.put(countryKey("Taiwan"), "TW"); map.put(countryKey("Vatican City"), "VA");
        map.put(countryKey("Palestine"), "PS"); map.put(countryKey("Kosovo"), "XK");
        return map;
    }

    private String countryCode(String country) {
        String code = COUNTRY_CODES.get(countryKey(country));
        if (code == null) code = COUNTRY_CODES.get(countryKey(normalizeCountry(country)));
        return code == null ? "" : code;
    }

    private String normalizeCountry(String raw) {
        if (raw == null) return "";
        String first = raw.trim().split("[,;]")[0].trim();
        if (first.isEmpty()) return "";
        String value = COUNTRY_ALIASES.get(countryKey(first));
        return value == null ? first : value;
    }

    private String knownCountry(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String first = raw.trim().split("[,;]")[0].trim();
        String key = countryKey(first);
        if (COUNTRY_ALIASES.containsKey(key) || COUNTRY_CODES.containsKey(key)) {
            return normalizeCountry(first);
        }
        return "";
    }

    private String safeCountry(String value) {
        String country = normalizeCountry(value);
        return country.isEmpty() || "default".equalsIgnoreCase(country) ? "Pays non précisé" : country;
    }

    private String flag(String country, String code) {
        String cc = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (cc.matches("[A-Z]{2}")) {
            int a = 0x1F1E6 + cc.charAt(0) - 'A', b = 0x1F1E6 + cc.charAt(1) - 'A';
            return new String(Character.toChars(a)) + new String(Character.toChars(b));
        }
        String k = countryKey(country);
        if (k.contains("france")) return "🇫🇷";
        if (k.contains("maroc") || k.contains("morocco")) return "🇲🇦";
        return "🌐";
    }

    private String initials(String name) {
        String n = safe(name).trim();
        if (n.isEmpty()) return "TV";
        String[] parts = n.split("\\s+");
        if (parts.length > 1) return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
        return n.substring(0, Math.min(3, n.length())).toUpperCase(Locale.ROOT);
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    private boolean isFavorite(Channel ch) {
        return getSharedPreferences("favorites", MODE_PRIVATE).getBoolean(ch.url, false);
    }

    private void toggleFavorite(Channel ch) {
        SharedPreferences p = getSharedPreferences("favorites", MODE_PRIVATE);
        p.edit().putBoolean(ch.url, !p.getBoolean(ch.url, false)).apply();
    }

    private boolean useVlcPlayer() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("use_vlc_player", false);
    }

    private void setVlcPreference(boolean enabled) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("use_vlc_player", enabled).apply();
    }

    private String preferredPlayerLabel() {
        return useVlcPlayer() ? "VLC (compatibilité étendue)" : "Media3 (léger)";
    }

    private void play(Channel ch) {
        if (ch == null) return;
        currentChannel = ch;
        rememberRecentlyViewed(ch);
        if (previewTitle != null) previewTitle.setText(safe(ch.name));
        if (previewStatus != null) previewStatus.setText("Connexion au flux " + sourceFilter + "…");
        if (previewDetail != null) previewDetail.setText(
                "SOURCE  " + sourceFilter + "     ·     PAYS  " + safeCountry(ch.country));
        renderCountryChoices();
        startPlayback(ch);
    }

    private void startPlayback(Channel ch) {
        releaseMediaEngine();
        if (playerStage != null) playerStage.removeAllViews();
        if (ch.isVavoo) {
            Toast.makeText(this, "Résolution du flux VAVOO…", Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                try {
                    String resolved = VavooProvider.resolveStream(ch.vavooSourceUrl);
                    runOnUiThread(() -> {
                        if (currentChannel != ch || playerStage == null) return;
                        startResolvedPlayback(ch, resolved);
                    });
                } catch (Exception e) {
                    String message = e.getMessage() == null ? "Résolution VAVOO impossible" : e.getMessage();
                    runOnUiThread(() -> { if (currentChannel == ch) Toast.makeText(MainActivity.this, "VAVOO : " + message, Toast.LENGTH_LONG).show(); });
                }
            });
        } else {
            startResolvedPlayback(ch, ch.url);
        }
    }

    private void startResolvedPlayback(Channel ch, String streamUrl) {
        if (playerStage == null || currentChannel != ch) return;
        if (useVlcPlayer()) startVlcPlayback(ch, streamUrl);
        else startMedia3Playback(ch, streamUrl);
    }

    private void startMedia3Playback(Channel ch, String streamUrl) {
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ch.isVavoo ? "VAVOO/2.6" : "NISSKENZI+/1.9")
                .setAllowCrossProtocolRedirects(true);
            DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);
            ExoPlayer active = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
            player = active;
            playerView = new PlayerView(this);
            playerView.setUseController(true);
            playerView.setKeepContentOnPlayerReset(true);
            playerView.setShutterBackgroundColor(Color.BLACK);
            playerStage.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
            playerView.setPlayer(active);
            active.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (currentChannel != ch || previewStatus == null) return;
                    if (playbackState == Player.STATE_READY) {
                        previewStatus.setText("● EN DIRECT");
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        previewStatus.setText("Mise en mémoire tampon…");
                    } else {
                        previewStatus.setText("Connexion au flux…");
                    }
                }

                @Override public void onPlayerError(PlaybackException e) {
                    if (currentChannel == ch && previewStatus != null) {
                        previewStatus.setText("Flux indisponible · essayez un autre lecteur");
                    }
                    Toast.makeText(MainActivity.this, "Media3 : lecture impossible (" + e.getErrorCodeName() + ").", Toast.LENGTH_LONG).show();
                    if (!useVlcPlayer()) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Essayer un autre lecteur ?")
                            .setMessage("Ce flux nécessite un autre moteur vidéo. VLC est disponible en option.")
                            .setNegativeButton("Pas maintenant", null)
                            .setPositiveButton("Essayer VLC", (dialog, which) -> {
                                setVlcPreference(true);
                                startPlayback(ch);
                            }).show();
                    }
                }
            });
            active.setMediaItem(MediaItem.fromUri(streamUrl));
            active.prepare();
            active.setPlayWhenReady(true);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de démarrer Media3 : " + safe(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void startVlcPlayback(Channel ch, String streamUrl) {
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--network-caching=1500");
            options.add("--no-drop-late-frames");
            options.add("--no-skip-frames");
            libVLC = new LibVLC(this, options);
            org.videolan.libvlc.MediaPlayer active = new org.videolan.libvlc.MediaPlayer(libVLC);
            vlcPlayer = active;
            vlcVideoLayout = new VLCVideoLayout(this);
            playerStage.addView(vlcVideoLayout, new FrameLayout.LayoutParams(-1, -1));
            active.attachViews(vlcVideoLayout, null, false, false);
            active.setEventListener(event -> {
                if (event.type == org.videolan.libvlc.MediaPlayer.Event.EncounteredError) {
                    runOnUiThread(() -> {
                        if (vlcPlayer != active || currentChannel != ch) return;
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("VLC n’a pas pu lire le flux")
                            .setMessage("Le flux est peut-être indisponible. Souhaitez-vous tenter le lecteur Media3 ?")
                            .setNegativeButton("Fermer", null)
                            .setPositiveButton("Essayer Media3", (dialog, which) -> {
                                setVlcPreference(false);
                                startPlayback(ch);
                            }).show();
                    });
                }
            });
            Media media = new Media(libVLC, Uri.parse(streamUrl));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=1500");
            active.setMedia(media);
            media.release();
            active.play();
        } catch (Exception e) {
            releaseMediaEngine();
            Toast.makeText(this, "Impossible de démarrer VLC : " + safe(e.getMessage()), Toast.LENGTH_LONG).show();
            if (currentChannel == ch && playerStage != null) {
                setVlcPreference(false);
                startMedia3Playback(ch, streamUrl);
            }
        }
    }

    private void releaseMediaEngine() {
        if (player != null) {
            try { player.stop(); player.clearMediaItems(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (playerView != null) {
            try { playerView.setPlayer(null); if (playerStage != null) playerStage.removeView(playerView); } catch (Exception ignored) {}
            playerView = null;
        }
        if (vlcPlayer != null) {
            try { vlcPlayer.stop(); vlcPlayer.detachViews(); vlcPlayer.release(); } catch (Exception ignored) {}
            vlcPlayer = null;
        }
        if (libVLC != null) {
            try { libVLC.release(); } catch (Exception ignored) {}
            libVLC = null;
        }
        if (vlcVideoLayout != null) {
            try { if (playerStage != null) playerStage.removeView(vlcVideoLayout); } catch (Exception ignored) {}
            vlcVideoLayout = null;
        }
    }

    private void stopPreview() {
        releaseMediaEngine();
        currentChannel = null;
        if (playerStage != null) {
            playerStage.removeAllViews();
            TextView placeholder = label("▶\n\nPrêt pour la lecture", isTelevision() ? 17 : 13, 0xCCFFFFFF, true);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackgroundColor(Color.BLACK);
            playerStage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        }
        if (previewTitle != null) previewTitle.setText("Aperçu du direct");
        if (previewStatus != null) previewStatus.setText("Sélectionnez une chaîne pour démarrer");
        if (previewDetail != null) previewDetail.setText("La lecture s'intègre directement ici.");
        updateChannelResults();
        renderCountryChoices();
    }

    private void releasePlayer() {
        releaseMediaEngine();
        playerStage = null;
    }

    @Override public void onBackPressed() {
        if ("Paramètres".equals(activePage)) {
            setPage("Live TV");
        } else if (player != null || vlcPlayer != null) {
            stopPreview();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        loadGeneration.incrementAndGet();
        releasePlayer();
        io.shutdownNow();
        logoIo.shutdownNow();
        super.onDestroy();
    }
}