package tv.nisskenzi.moroccotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;


import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native Android Live TV app optimized for Android TV and native UI design.
 */
public class MainActivity extends Activity {
    private static final int PANEL = 0xCC151D28;
    private static final int LINE = 0x665D6D82;
    private static final int CYAN = 0xFF4CC9F0;
    private static final int PURPLE = 0xFFB39DFF;
    private static final int MUTED = 0xFFB8C4D2;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GREEN = 0xFF4CC9F0;
    private static final int YELLOW = 0xFFFFFF00;
    private static final int MAX_CACHED_CHANNELS = 400;
    // The VAVOO catalogue is preloaded from an all-country snapshot and refreshed off-thread.
    private static final int MAX_CATALOG_CHANNELS = 20000;

    private LinearLayout root;
    private View headerView;
    private FrameLayout contentHost;
    private TextView statusView;
    private TextView countView;
    private LinearLayout countryChoices;
    private RecyclerView channelResults;
    private FrameLayout channelListHost;
    private LinearLayout channelEmptyView;
    private ChannelAdapter channelAdapter;
    private EditText searchInput;
    private EditText categorySearchInput;
    private TextView liveTab;
    private TextView vavooTab;
    private TextView iptvTab;
    private TextView recentTab;
    private TextView settingsTab;
    private TextView previewTitle;
    private TextView previewStatus;
    private TextView previewDetail;
    private TextView sourceOptionsButton;
    private ChannelLogoLoader homeLogoLoader;

    private NativeLivePlayerView playerView;
    private ExoPlayer player;
    private FrameLayout playerStage;
    private boolean fullscreenPlayback = false;
    private ViewGroup playerStageParent;
    private View playerStagePlaceholder;
    private ViewGroup.LayoutParams playerStageOriginalLayoutParams;
    private View playbackReturnFocus;
    private int playerStageOriginalIndex = -1;
    private Channel currentChannel;
    private Channel activePlaybackSource;
    private ArrayList<Channel> playbackCandidates = new ArrayList<>();
    private int playbackCandidateIndex = -1;
    private boolean activeSourceSucceeded = false;
    private boolean preserveManualSourceOrder = false;
    private volatile String activePage = "Accueil";
    private volatile String catalogStatus = "Préparation du catalogue…";
    private volatile boolean loadingCatalog = false;
    private volatile boolean loadingVavoo = false;
    private volatile boolean loadingIptv = false;
    private volatile boolean activityDestroyed = false;

    private final AtomicInteger loadGeneration = new AtomicInteger(0);
    private final AtomicInteger filterGeneration = new AtomicInteger(0);
    private final ThreadPoolExecutor filterExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            Executors.defaultThreadFactory(), new ThreadPoolExecutor.DiscardOldestPolicy());
    private Future<?> filterJob;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearchFilter;
    private FavoritesStore favoritesStore;
    private RecentChannelsStore recentStore;
    private SharedPreferences sourceHealthPreferences;
    private int catalogVersion = 0;
    // Identity token shared by each player instance for the same loaded catalogue.
    private Object channelCatalogToken = new Object();
    private int countryStatsVersion = -1;
    private final Map<String, Integer> countryCountCache = new HashMap<>();
    private final Map<String, String> countryCodeCache = new HashMap<>();
    private final Map<String, Integer> genreCountCache = new HashMap<>();
    private final Set<String> channelUrlCache = new HashSet<>();
    private final Map<String, Channel> channelBySavedId = new HashMap<>();
    private int channelUrlSetVersion = -1;
    private int favoriteCountCache = 0;
    private int favoriteCountCatalogVersion = -1;
    private long favoriteCountStoreRevision = -1L;
    private int recentCountCache = 0;
    private int recentCountCatalogVersion = -1;
    private long recentCountStoreRevision = -1L;

    private String activeCountry = "Tous les pays";
    private String activeGenre = "Tous les genres";
    private String sourceFilter = "TOUS";
    private String searchText = "";
    private String categorySearch = "";
    private String lastRenderedFilterKey = "";
    private boolean favoritesOnly = false;
    private boolean recentOnly = false;

    private final ArrayList<Channel> channels = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(3);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        favoritesStore = new FavoritesStore(getSharedPreferences("favorites", MODE_PRIVATE));
        recentStore = new RecentChannelsStore(getSharedPreferences("recent", MODE_PRIVATE));
        sourceHealthPreferences = getSharedPreferences("source_health", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildHome();
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
        b.setBackground(rounded(selected ? 0xFF173D52 : 0xFF242E3B, 22));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                b.setBackground(outlined(0xFF164A60, 22, 0xFF4CC9F0));
                b.setTextColor(YELLOW);
                b.setScaleX(1.035f);
                b.setScaleY(1.035f);
            } else {
                boolean active = b == liveTab ? "Accueil".equals(activePage)
                        : b == vavooTab ? ("Live TV".equals(activePage) && !favoritesOnly && !recentOnly)
                        : b == iptvTab ? ("Live TV".equals(activePage) && favoritesOnly)
                        : b == recentTab ? ("Live TV".equals(activePage) && recentOnly)
                        : b == settingsTab ? "Paramètres".equals(activePage) : selected;
                b.setBackground(rounded(active ? 0xFF173D52 : 0xFF242E3B, 22));
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
        if (player != null) releasePlayer();
        root = vertical();
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF080B10, 0xFF111A26, 0xFF0A131D});
        root.setBackground(backdrop);
        setContentView(root);

        LinearLayout header = horizontal();
        int headerPad = isTelevision() ? 30 : 14;
        header.setPadding(dp(headerPad), dp(isTelevision() ? 10 : 8),
                dp(headerPad), dp(isTelevision() ? 10 : 8));
        header.setBackgroundColor(0xEE101721);
        headerView = header;
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView brand = buttonText("NISSKENZI+", false);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setContentDescription("Accueil NISSKENZI+");
        header.addView(brand, lp(isTelevision() ? 142 : 108, isTelevision() ? 50 : 40));
        brand.setOnClickListener(v -> setPage("Accueil"));

        liveTab = buttonText("Accueil", "Accueil".equals(activePage));
        vavooTab = buttonText("Guide TV", "Live TV".equals(activePage) && !favoritesOnly && !recentOnly);
        iptvTab = buttonText("Favoris", favoritesOnly);
        recentTab = buttonText("Récents", recentOnly);

        LinearLayout.LayoutParams navp = lp(-2, isTelevision() ? 50 : 40);
        navp.leftMargin = dp(6);
        header.addView(liveTab, navp);
        LinearLayout.LayoutParams navp2 = lp(-2, isTelevision() ? 50 : 40);
        navp2.leftMargin = dp(6);
        header.addView(vavooTab, navp2);
        LinearLayout.LayoutParams navp3 = lp(-2, isTelevision() ? 50 : 40);
        navp3.leftMargin = dp(6);
        header.addView(iptvTab, navp3);
        LinearLayout.LayoutParams navp4 = lp(-2, isTelevision() ? 50 : 40);
        navp4.leftMargin = dp(6);
        header.addView(recentTab, navp4);

        liveTab.setOnClickListener(v -> setPage("Accueil"));
        vavooTab.setOnClickListener(v -> showChannelList(false, false));
        iptvTab.setOnClickListener(v -> showChannelList(true, false));
        recentTab.setOnClickListener(v -> showChannelList(false, true));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(0xFFB8C4D2);
        searchInput.setTextSize(isTelevision() ? 16 : 12);
        searchInput.setHint("⌕  Rechercher une chaîne");
        searchInput.setPadding(dp(18), 0, dp(18), 0);
        searchInput.setBackground(outlined(0xFF171F2A, 24, 0xFF39485B));
        searchInput.setText(searchText);
        searchInput.setFocusable(true);
        searchInput.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(0, dp(isTelevision() ? 50 : 40), 1f);
        searchP.leftMargin = dp(isTelevision() ? 16 : 10);
        searchP.rightMargin = dp(isTelevision() ? 12 : 8);
        header.addView(searchInput, searchP);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchText = s == null ? "" : s.toString();
                if ("Accueil".equals(activePage) && !safe(searchText).isEmpty()) {
                    showChannelList(false, false);
                } else {
                    scheduleSearchFilter();
                }
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        settingsTab = buttonText("Paramètres", "Paramètres".equals(activePage));
        header.addView(settingsTab, lp(-2, isTelevision() ? 50 : 40));
        settingsTab.setOnClickListener(v -> setPage("Paramètres"));

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));

        statusView = label(catalogStatus, isTelevision() ? 11 : 9, Color.WHITE, false);
        statusView.setMaxLines(1);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(dp(headerPad), dp(6), dp(headerPad), dp(6));
        statusView.setBackgroundColor(0xFF0C121A);
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        updateHeaderTabs();
        renderPage();
    }

    private void setPage(String p) {
        String next = p == null ? "Accueil" : p;
        if (!next.equals(activePage) && (player != null)) {
            releaseMediaEngine();
            currentChannel = null;
            activePlaybackSource = null;
            playbackCandidates.clear();
        }
        activePage = next;
        if ("Paramètres".equals(activePage)) {
            releaseMediaEngine();
            currentChannel = null;
            activePlaybackSource = null;
            playbackCandidates.clear();
        }
        updateHeaderTabs();
        renderPage();
    }

    private void showChannelList(boolean favorites, boolean recent) {
        if (player != null) {
            releaseMediaEngine();
            currentChannel = null;
            activePlaybackSource = null;
            playbackCandidates.clear();
        }
        activePage = "Live TV";
        favoritesOnly = favorites;
        recentOnly = recent;
        activeCountry = "Tous les pays";
        activeGenre = "Tous les genres";
        categorySearch = "";
        updateHeaderTabs();
        renderPage();
        if (channelResults != null && safe(searchText).isEmpty()) {
            channelResults.post(() -> {
                if (!"Live TV".equals(activePage) || channelResults == null) return;
                View first = channelResults.getChildAt(0);
                if (first != null) first.requestFocus();
                else if (categorySearchInput != null) categorySearchInput.requestFocus();
            });
        }
    }

    private void updateHeaderTabs() {
        setTabState(liveTab, "Accueil".equals(activePage));
        setTabState(vavooTab, "Live TV".equals(activePage) && !favoritesOnly && !recentOnly);
        setTabState(iptvTab, "Live TV".equals(activePage) && favoritesOnly);
        setTabState(recentTab, "Live TV".equals(activePage) && recentOnly);
        setTabState(settingsTab, "Paramètres".equals(activePage));
    }

    private void setTabState(TextView tab, boolean selected) {
        if (tab == null) return;
        tab.setBackground(rounded(selected ? 0xFF173D52 : 0xFF242E3B, 22));
        tab.setTextColor(selected ? YELLOW : Color.WHITE);
    }

    private void renderPage() {
        if (contentHost == null) return;
        filterGeneration.incrementAndGet();
        if (filterJob != null) {
            filterJob.cancel(true);
            filterJob = null;
            filterExecutor.purge();
        }
        // Keep the live layout and its active player stage mounted while video is fullscreen.
        if (fullscreenPlayback) {
            renderCountryChoices();
            updateChannelResults();
            return;
        }
        if (channelAdapter != null) {
            channelAdapter.shutdown();
            channelAdapter = null;
        }
        if (!"Accueil".equals(activePage) && homeLogoLoader != null) {
            homeLogoLoader.close();
            homeLogoLoader = null;
        }
        channelResults = null;
        channelListHost = null;
        channelEmptyView = null;
        countryChoices = null;
        categorySearchInput = null;
        previewTitle = null;
        previewStatus = null;
        previewDetail = null;
        sourceOptionsButton = null;
        playerStage = null;
        contentHost.removeAllViews();
        if ("Paramètres".equals(activePage)) renderSettings();
        else if ("Accueil".equals(activePage)) renderHomePage();
        else renderLivePage();
    }

    private void renderHomePage() {
        if (homeLogoLoader != null) homeLogoLoader.close();
        homeLogoLoader = new ChannelLogoLoader();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = vertical();
        int sidePad = isTelevision() ? 46 : 18;
        page.setPadding(dp(sidePad), dp(isTelevision() ? 22 : 16), dp(sidePad), dp(34));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout welcome = horizontal();
        welcome.setGravity(Gravity.BOTTOM);
        page.addView(welcome, lp(-1, -2));
        LinearLayout welcomeText = vertical();
        welcomeText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        welcome.addView(welcomeText);
        TextView brandLine = label("NISSKENZI+", isTelevision() ? 13 : 10, CYAN, true);
        brandLine.setLetterSpacing(0.12f);
        welcomeText.addView(brandLine);
        TextView heading = label("La télé, à ta façon.", isTelevision() ? 32 : 25, TEXT, true);
        LinearLayout.LayoutParams headingP = lp(-1, -2);
        headingP.topMargin = dp(4);
        welcomeText.addView(heading, headingP);
        TextView intro = label("Un guide clair, des chaînes rangées par pays et genre, et le meilleur flux en premier.",
                isTelevision() ? 14 : 11, MUTED, false);
        LinearLayout.LayoutParams introP = lp(-1, -2);
        introP.topMargin = dp(5);
        introP.bottomMargin = dp(18);
        welcomeText.addView(intro, introP);

        ArrayList<Channel> recent = recentStore == null
                ? new ArrayList<>() : channelsForSaved(recentStore.orderedUrls(), 1);
        ArrayList<Channel> favorites = favoritesStore == null
                ? new ArrayList<>() : channelsForSaved(new ArrayList<>(favoritesStore.snapshotUrls()), 1);
        Channel featured = !recent.isEmpty() ? recent.get(0)
                : (!favorites.isEmpty() ? favorites.get(0) : (channels.isEmpty() ? null : channels.get(0)));

        LinearLayout hero = horizontal();
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(isTelevision() ? 34 : 20), dp(isTelevision() ? 28 : 20),
                dp(isTelevision() ? 30 : 18), dp(isTelevision() ? 28 : 20));
        GradientDrawable heroBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF183C52, 0xFF263251, 0xFF121B29});
        heroBackground.setCornerRadius(dp(22));
        hero.setBackground(heroBackground);
        LinearLayout.LayoutParams heroP = lp(-1, isTelevision() ? 316 : 246);
        heroP.bottomMargin = dp(isTelevision() ? 30 : 20);
        page.addView(hero, heroP);

        LinearLayout heroInfo = vertical();
        heroInfo.setGravity(Gravity.CENTER_VERTICAL);
        heroInfo.setPadding(0, 0, dp(18), 0);
        hero.addView(heroInfo, new LinearLayout.LayoutParams(0, -1, 1.2f));
        TextView eyebrow = label(featured == null ? "TON GUIDE TV"
                : (!recent.isEmpty() ? "REPRENDRE LE DIRECT" : "À LA UNE"),
                isTelevision() ? 12 : 9, CYAN, true);
        eyebrow.setLetterSpacing(0.08f);
        heroInfo.addView(eyebrow);
        previewTitle = label(featured == null ? "Prêt pour ton premier direct"
                : featured.name, isTelevision() ? 36 : 25, TEXT, true);
        TextView heroTitle = previewTitle;
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams heroTitleP = lp(-1, -2);
        heroTitleP.topMargin = dp(12);
        heroInfo.addView(heroTitle, heroTitleP);

        String providerName = featured == null ? "VAVOO et IPTV-org Maroc"
                : (featured.isVavoo ? "VAVOO recommandé" : "IPTV-org recommandé");
        previewStatus = label(featured == null
                ? "France · Maghreb · pays arabes · Maroc"
                : flag(featured.country, countryCode(featured.country)) + "  "
                        + safeCountry(featured.country) + "  ·  " + providerName,
                isTelevision() ? 14 : 11, 0xFFD5E1EE, false);
        TextView heroText = previewStatus;
        LinearLayout.LayoutParams heroTextP = lp(-1, -2);
        heroTextP.topMargin = dp(9);
        heroInfo.addView(heroText, heroTextP);
        previewDetail = label(featured == null
                ? "Les chaînes identiques sont réunies. L’app essaie automatiquement un autre flux si besoin."
                : (featured.sourceCount() > 1
                    ? featured.sourceCount() + " flux réunis · sélection automatique selon leur fiabilité"
                    : "Un flux prêt à regarder · qualité adaptée à la disponibilité"),
                isTelevision() ? 12 : 9, MUTED, false);
        LinearLayout.LayoutParams previewDetailP = lp(-1, -2);
        previewDetailP.topMargin = dp(7);
        heroInfo.addView(previewDetail, previewDetailP);

        LinearLayout heroActions = horizontal();
        LinearLayout.LayoutParams heroActionsP = lp(-1, -2);
        heroActionsP.topMargin = dp(20);
        heroInfo.addView(heroActions, heroActionsP);
        TextView watch = buttonText(featured == null ? "▶  Explorer les chaînes" : "▶  Regarder",
                true);
        heroActions.addView(watch, lp(-2, isTelevision() ? 52 : 42));
        watch.setEnabled(featured != null);
        watch.setAlpha(featured == null ? 0.72f : 1f);
        watch.setOnClickListener(v -> {
            if (featured != null) play(featured);
            else showChannelList(false, false);
        });
        TextView explore = buttonText("⌕  Guide TV", false);
        LinearLayout.LayoutParams exploreP = lp(-2, isTelevision() ? 52 : 42);
        exploreP.leftMargin = dp(10);
        heroActions.addView(explore, exploreP);
        explore.setOnClickListener(v -> showChannelList(false, false));

        playerStage = new FrameLayout(this);
        playerStage.setBackground(rounded(0xFF080D14, 18));
        playerStage.setFocusable(false);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(0, -1, 0.8f);
        stageParams.leftMargin = dp(10);
        hero.addView(playerStage, stageParams);

        GradientDrawable stageBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF172B3B, 0xFF101722});
        stageBackground.setCornerRadius(dp(18));
        playerStage.setBackground(stageBackground);
        TextView stageInitials = label(featured == null ? "N+" : initials(featured.name),
                isTelevision() ? 52 : 38, 0xFFDDF7FF, true);
        stageInitials.setGravity(Gravity.CENTER);
        playerStage.addView(stageInitials, new FrameLayout.LayoutParams(-1, -1));

        if (featured != null && !safe(featured.logo).isEmpty()) {
            ImageView logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding(dp(22), dp(26), dp(22), dp(26));
            logo.setTag(safe(featured.logo));
            playerStage.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            bindHomeLogo(logo, stageInitials, featured.logo);
        }
        TextView liveBadge = label(featured == null ? "GUIDE TV" : "●  EN DIRECT",
                isTelevision() ? 11 : 9, featured == null ? CYAN : 0xFFFFD36B, true);
        liveBadge.setGravity(Gravity.CENTER);
        liveBadge.setPadding(dp(10), dp(7), dp(10), dp(7));
        liveBadge.setBackground(rounded(0xCC101721, 16));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.END);
        badgeParams.setMargins(0, dp(12), dp(12), 0);
        playerStage.addView(liveBadge, badgeParams);

        addHomeRail(page, "Reprendre", recentChannels(12));
        addHomeRail(page, "Mes favoris", favoriteChannels(12));
        addHomeRail(page, "Pour toi", recommendedChannels(14));
        addHomeRail(page, "France", channelsForRegion("FRANCE", 14));
        addHomeRail(page, "Maghreb", channelsForRegion("MAGHREB", 14));
        addHomeRail(page, "Pays arabes", channelsForRegion("ARAB", 14));
        addHomeRail(page, "Maroc · IPTV-org", channelsForIptvMorocco(14));
        addHomeRail(page, "Sport", channelsForGenre("Sport", 12));
        addHomeRail(page, "Films & séries", channelsForGenre("Films & séries", 12));
        addHomeRail(page, "Jeunesse", channelsForGenre("Jeunesse", 12));
        addHomeRail(page, "Toutes les chaînes", firstChannels(14));

        View initialFocus = featured == null ? explore : watch;
        initialFocus.post(() -> {
            if ("Accueil".equals(activePage) && initialFocus.isAttachedToWindow()) initialFocus.requestFocus();
        });
    }

    private void addHomeRail(LinearLayout page, String title, List<Channel> items) {
        if (items == null || items.isEmpty()) return;
        TextView heading = label(title, isTelevision() ? 22 : 16, TEXT, true);
        LinearLayout.LayoutParams headingP = lp(-1, -2);
        headingP.topMargin = dp(4);
        headingP.bottomMargin = dp(9);
        page.addView(heading, headingP);

        HorizontalScrollView horizontalScroll = new HorizontalScrollView(this);
        horizontalScroll.setHorizontalScrollBarEnabled(false);
        horizontalScroll.setClipToPadding(false);
        LinearLayout cards = horizontal();
        cards.setGravity(Gravity.TOP);
        horizontalScroll.addView(cards);
        LinearLayout.LayoutParams scrollP = lp(-1, isTelevision() ? 184 : 156);
        scrollP.bottomMargin = dp(isTelevision() ? 22 : 15);
        page.addView(horizontalScroll, scrollP);

        int limit = Math.min(items.size(), 14);
        for (int i = 0; i < limit; i++) {
            Channel channel = items.get(i);
            LinearLayout card = vertical();
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(15), dp(9), dp(15), dp(9));
            card.setFocusable(true);
            card.setFocusableInTouchMode(false);
            card.setClickable(true);
            card.setBackground(outlined(0xFF17212D, 16, 0x003C4654));

            FrameLayout logoTile = new FrameLayout(this);
            logoTile.setBackground(rounded(0xFF203E53, 12));
            int logoSize = isTelevision() ? 56 : 44;
            TextView mark = label(initials(channel.name), isTelevision() ? 18 : 14, CYAN, true);
            mark.setGravity(Gravity.CENTER);
            logoTile.addView(mark, new FrameLayout.LayoutParams(-1, -1));
            if (!safe(channel.logo).isEmpty()) {
                ImageView logo = new ImageView(this);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                logo.setPadding(dp(5), dp(5), dp(5), dp(5));
                logo.setTag(safe(channel.logo));
                logoTile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
                bindHomeLogo(logo, mark, channel.logo);
            }
            card.addView(logoTile, lp(logoSize, logoSize));

            String preferred = channel.isVavoo ? "VAVOO" : "IPTV-ORG";
            TextView source = label(channel.sourceCount() > 1
                    ? "●  " + preferred + " · +" + (channel.sourceCount() - 1) + " flux"
                    : "●  " + preferred + " recommandé",
                    isTelevision() ? 10 : 8, CYAN, true);
            source.setSingleLine(true);
            source.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sourceP = lp(-1, -2);
            sourceP.topMargin = dp(5);
            card.addView(source, sourceP);

            TextView name = label(channel.name, isTelevision() ? 17 : 13, TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameP = lp(-1, -2);
            nameP.topMargin = dp(4);
            card.addView(name, nameP);
            TextView country = label(flag(channel.country, countryCode(channel.country))
                    + "  " + safeCountry(channel.country),
                    isTelevision() ? 11 : 9, MUTED, false);
            country.setSingleLine(true);
            country.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams countryP = lp(-1, -2);
            countryP.topMargin = dp(2);
            card.addView(country, countryP);

            LinearLayout.LayoutParams cardP = lp(isTelevision() ? 246 : 194,
                    isTelevision() ? 168 : 140);
            cardP.rightMargin = dp(12);
            cards.addView(card, cardP);
            card.setContentDescription(channel.name + ", " + safeCountry(channel.country)
                    + ", source recommandée " + preferred
                    + (channel.sourceCount() > 1 ? ", " + channel.sourceCount() + " flux au total" : ""));
            card.setOnFocusChangeListener((view, focused) -> {
                card.setBackground(outlined(focused ? 0xFF1B3549 : 0xFF17212D, 16,
                        focused ? CYAN : 0x003C4654));
                card.setScaleX(focused ? 1.035f : 1f);
                card.setScaleY(focused ? 1.035f : 1f);
            });
            card.setOnClickListener(v -> play(channel));
            card.setOnLongClickListener(v -> {
                if (channel.sourceCount() < 2) return false;
                showSourceChoices(channel);
                return true;
            });
        }
    }

    private void bindHomeLogo(ImageView image, TextView initialsView, String logoUrl) {
        ChannelLogoLoader loader = homeLogoLoader;
        String url = safe(logoUrl);
        if (loader == null || url.isEmpty()) return;
        Bitmap cached = loader.getCached(url);
        if (cached != null) {
            image.setImageBitmap(cached);
            initialsView.setVisibility(View.GONE);
            return;
        }
        loader.load(url, bitmap -> image.post(() -> {
            if (homeLogoLoader != loader || loader.isClosed()
                    || !url.equals(image.getTag()) || bitmap == null) return;
            image.setImageBitmap(bitmap);
            initialsView.setVisibility(View.GONE);
        }));
    }

    private ArrayList<Channel> channelsForRegion(String region, int limit) {
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel channel : channels) {
            String code = ChannelCatalogPolicy.countryCode(channel.country);
            String rawCountry = Channel.normalizeSearch(channel.country);
            boolean genericFrance = code.isEmpty()
                    && (rawCountry.contains("france") || rawCountry.contains("francais")
                    || rawCountry.contains("francaise"));
            boolean genericMaghreb = code.isEmpty() && rawCountry.contains("maghreb");
            boolean genericArab = code.isEmpty()
                    && (rawCountry.contains("arab") || rawCountry.contains("arabia"));
            boolean match = "FRANCE".equals(region) ? ("FR".equals(code) || genericFrance)
                    : ("MAGHREB".equals(region)
                        ? (ChannelCatalogPolicy.isMaghrebCode(code) || genericMaghreb)
                        : ((ChannelCatalogPolicy.isArabCode(code)
                                && !ChannelCatalogPolicy.isMaghrebCode(code)) || genericArab));
            if (!match) continue;
            result.add(channel);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private ArrayList<Channel> channelsForIptvMorocco(int limit) {
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel channel : channels) {
            boolean hasMoroccanIptvOrg = false;
            for (Channel option : channel.playbackOptions()) {
                if (ChannelCatalogPolicy.isIptvOrgMorocco(option)) {
                    hasMoroccanIptvOrg = true;
                    break;
                }
            }
            if (!hasMoroccanIptvOrg) continue;
            result.add(channel);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private ArrayList<Channel> channelsForGenre(String genre, int limit) {
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel channel : channels) {
            if (!ChannelCatalogPolicy.matchesGenre(channel, genre)) continue;
            result.add(channel);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private ArrayList<Channel> channelsForSaved(List<String> savedIds, int limit) {
        ArrayList<Channel> result = new ArrayList<>();
        if (savedIds == null || savedIds.isEmpty()) return result;
        Set<String> added = new LinkedHashSet<>();
        for (String savedId : savedIds) {
            Channel channel = channelBySavedId.get(savedId);
            if (channel != null && added.add(channel.identity())) {
                result.add(channel);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    private ArrayList<Channel> recentChannels(int limit) {
        return recentStore == null ? new ArrayList<>()
                : channelsForSaved(recentStore.orderedUrls(), limit);
    }

    private ArrayList<Channel> favoriteChannels(int limit) {
        return favoritesStore == null ? new ArrayList<>()
                : channelsForSaved(new ArrayList<>(favoritesStore.snapshotUrls()), limit);
    }

    private ArrayList<Channel> firstChannels(int limit) {
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel channel : channels) {
            result.add(channel);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private ArrayList<Channel> recommendedChannels(int limit) {
        Set<String> saved = new HashSet<>();
        Set<String> preferredCountries = new HashSet<>();
        if (recentStore != null) saved.addAll(recentStore.snapshotUrls());
        if (favoritesStore != null) saved.addAll(favoritesStore.snapshotUrls());
        for (String id : saved) {
            Channel watched = channelBySavedId.get(id);
            if (watched != null) preferredCountries.add(Channel.normalizeSearch(watched.country));
        }

        ArrayList<Channel> candidates = new ArrayList<>();
        for (Channel channel : channels) {
            if (channel.matchesSavedIds(saved)) continue;
            candidates.add(channel);
            if (candidates.size() >= 500) break;
        }
        Collections.sort(candidates, (left, right) -> {
            int leftScore = recommendationScore(left, preferredCountries);
            int rightScore = recommendationScore(right, preferredCountries);
            int byInterest = Integer.compare(rightScore, leftScore);
            return byInterest != 0 ? byInterest : left.searchTitle().compareTo(right.searchTitle());
        });
        if (candidates.size() > limit) candidates.subList(limit, candidates.size()).clear();
        return candidates;
    }

    private int recommendationScore(Channel channel, Set<String> preferredCountries) {
        int score = sourceReliabilityScore(channel.playbackOptions().get(0));
        if (preferredCountries.contains(Channel.normalizeSearch(channel.country))) score += 200;
        return score;
    }

    private void renderLivePage() {
        LinearLayout body = horizontal();
        body.setGravity(Gravity.FILL);
        contentHost.addView(body, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = vertical();
        left.setPadding(dp(24), dp(22), dp(18), dp(16));
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 2.6f));

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        body.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));

        LinearLayout right = vertical();
        right.setPadding(dp(24), dp(22), dp(30), dp(16));
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 7.4f));

        TextView categoryTitle = label("PARCOURIR", isTelevision() ? 12 : 9, MUTED, true);
        categoryTitle.setLetterSpacing(0.09f);
        LinearLayout.LayoutParams categoryTitleP = lp(-1, -2);
        categoryTitleP.bottomMargin = dp(10);
        left.addView(categoryTitle, categoryTitleP);

        categorySearchInput = new EditText(this);
        categorySearchInput.setSingleLine(true);
        categorySearchInput.setTextSize(isTelevision() ? 14 : 11);
        categorySearchInput.setTextColor(Color.WHITE);
        categorySearchInput.setHintTextColor(MUTED);
        categorySearchInput.setHint("Filtrer pays et genres");
        categorySearchInput.setPadding(dp(14), 0, dp(14), 0);
        categorySearchInput.setBackground(outlined(0xFF171F2A, 18, 0xFF39485B));
        categorySearchInput.setText(categorySearch);
        left.addView(categorySearchInput, lp(-1, isTelevision() ? 48 : 40));
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
        countryChoices.setPadding(0, dp(10), 0, dp(10));
        categoryScroll.addView(countryChoices);
        LinearLayout.LayoutParams categoryScrollP = new LinearLayout.LayoutParams(-1, 0, 1f);
        categoryScrollP.topMargin = dp(8);
        left.addView(categoryScroll, categoryScrollP);

        LinearLayout titleRow = horizontal();
        String title = favoritesOnly ? "Favoris" : recentOnly ? "Récemment regardés"
                : (!"Tous les pays".equals(activeCountry) ? activeCountry
                : (!"Tous les genres".equals(activeGenre) ? activeGenre : "Toutes les chaînes"));
        TextView pageTitle = label(title, isTelevision() ? 26 : 19, TEXT, true);
        titleRow.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        countView = label(channels.size() + " chaînes", isTelevision() ? 13 : 10, MUTED, true);
        countView.setBackground(rounded(0xFF202A37, 16));
        countView.setPadding(dp(12), dp(7), dp(12), dp(7));
        titleRow.addView(countView);
        right.addView(titleRow, lp(-1, -2));

        TextView listSubtitle = label("VAVOO + IPTV-ORG  ·  les doublons sont regroupés et la source la plus fiable est choisie automatiquement",
                isTelevision() ? 12 : 9, MUTED, false);
        LinearLayout.LayoutParams subtitleP = lp(-1, -2);
        subtitleP.topMargin = dp(5);
        subtitleP.bottomMargin = dp(12);
        right.addView(listSubtitle, subtitleP);

        LinearLayout preview = horizontal();
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        preview.setBackground(rounded(0xFF111A25, 14));
        right.addView(preview, lp(-1, isTelevision() ? 190 : 150));

        LinearLayout previewInfo = vertical();
        previewInfo.setGravity(Gravity.CENTER_VERTICAL);
        preview.addView(previewInfo, new LinearLayout.LayoutParams(0, -1, 1f));
        previewTitle = label(currentChannel == null ? "Sélectionne une chaîne"
                : safe(currentChannel.name), isTelevision() ? 20 : 15, TEXT, true);
        previewTitle.setMaxLines(2);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        previewInfo.addView(previewTitle);
        previewStatus = label(currentChannel == null ? "Choisis une carte pour lancer le direct"
                : "Source recommandée · " + (currentChannel.isVavoo ? "VAVOO" : "IPTV-ORG")
                        + " · " + currentChannel.sourceCount() + " flux",
                isTelevision() ? 12 : 10, MUTED, false);
        LinearLayout.LayoutParams previewStatusP = lp(-1, -2);
        previewStatusP.topMargin = dp(6);
        previewInfo.addView(previewStatus, previewStatusP);
        previewDetail = label(currentChannel == null
                ? "La lecture bascule vers une autre source si nécessaire."
                : safeCountry(currentChannel.country) + "  ·  " + currentChannel.sourceCount() + " sources",
                isTelevision() ? 11 : 9, MUTED, false);
        LinearLayout.LayoutParams previewDetailP = lp(-1, -2);
        previewDetailP.topMargin = dp(5);
        previewInfo.addView(previewDetail, previewDetailP);

        playerStage = new FrameLayout(this);
        playerStage.setBackground(rounded(0xFF05080D, 12));
        playerStage.setFocusable(false);
        LinearLayout.LayoutParams stageP = lp(isTelevision() ? 320 : 210,
                isTelevision() ? 166 : 120);
        stageP.leftMargin = dp(18);
        preview.addView(playerStage, stageP);

        LinearLayout actions = horizontal();
        LinearLayout.LayoutParams actionsP = lp(-1, isTelevision() ? 46 : 38);
        actionsP.topMargin = dp(10);
        actionsP.bottomMargin = dp(10);
        right.addView(actions, actionsP);
        TextView favorite = buttonText(currentChannel != null && isFavorite(currentChannel)
                ? "★  Favori" : "♡  Ajouter aux favoris",
                currentChannel != null && isFavorite(currentChannel));
        actions.addView(favorite, lp(-2, -1));
        TextView stop = buttonText("■  Arrêter", false);
        LinearLayout.LayoutParams stopP = lp(-2, -1);
        stopP.leftMargin = dp(8);
        actions.addView(stop, stopP);
        favorite.setOnClickListener(v -> {
            if (currentChannel == null) {
                Toast.makeText(this, "Sélectionne d’abord une chaîne.", Toast.LENGTH_SHORT).show();
                return;
            }
            toggleFavorite(currentChannel);
            favorite.setText(isFavorite(currentChannel) ? "★  Favori" : "♡  Ajouter aux favoris");
            favorite.setTextColor(isFavorite(currentChannel) ? YELLOW : Color.WHITE);
            renderCountryChoices();
            updateChannelResults();
            if (channelAdapter != null) channelAdapter.refreshDecorations();
        });
        sourceOptionsButton = buttonText("Sources", false);
        sourceOptionsButton.setEnabled(currentChannel != null && currentChannel.sourceCount() > 1);
        sourceOptionsButton.setAlpha(sourceOptionsButton.isEnabled() ? 1f : 0.55f);
        LinearLayout.LayoutParams sourceButtonParams = lp(-2, -1);
        sourceButtonParams.leftMargin = dp(8);
        actions.addView(sourceOptionsButton, sourceButtonParams);
        sourceOptionsButton.setOnClickListener(v -> showSourceChoices(currentChannel));
        stop.setOnClickListener(v -> stopPreview());

        channelListHost = new FrameLayout(this);
        channelResults = new RecyclerView(this);
        channelResults.setLayoutManager(new LinearLayoutManager(this));
        channelResults.setHasFixedSize(true);
        channelResults.setItemViewCacheSize(10);
        channelResults.setItemAnimator(null);
        channelAdapter = new ChannelAdapter(this::play, this::isFavorite, this::showSourceChoices);
        channelAdapter.setSelectedUrl(currentChannel == null ? null : currentChannel.identity());
        channelResults.setAdapter(channelAdapter);
        channelListHost.addView(channelResults, new FrameLayout.LayoutParams(-1, -1));

        channelEmptyView = vertical();
        channelEmptyView.setGravity(Gravity.CENTER);
        channelEmptyView.setVisibility(View.GONE);
        channelListHost.addView(channelEmptyView, new FrameLayout.LayoutParams(-1, -1));
        right.addView(channelListHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (currentChannel == null) {
            TextView placeholder = label("NISSKENZI+\n\nPrêt pour le direct",
                    isTelevision() ? 14 : 11, 0xFFE5EDF7, true);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(rounded(0xFF080B10, 12));
            playerStage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView placeholder = label("▶\n\n" + safeCountry(currentChannel.country)
                    + " · " + currentChannel.sourceCount() + " sources",
                    isTelevision() ? 14 : 11, 0xFFE5EDF7, true);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(rounded(0xFF080B10, 12));
            playerStage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        }

        renderCountryChoices();
        updateChannelResults();
    }

    private void renderCountryChoices() {
        if (countryChoices == null) return;
        countryChoices.removeAllViews();

        addCategoryRow("Tous les pays", channels.size(),
                !favoritesOnly && !recentOnly && "Tous les pays".equals(activeCountry),
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = false;
                    recentOnly = false;
                    activeGenre = "Tous les genres";
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Récemment vus", countRecentChannels(), recentOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    activeGenre = "Tous les genres";
                    favoritesOnly = false;
                    recentOnly = true;
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Favoris", countFavoriteChannels(), favoritesOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    activeGenre = "Tous les genres";
                    favoritesOnly = true;
                    recentOnly = false;
                    renderCountryChoices();
                    updateChannelResults();
                });

        addCategorySection("PAYS");
        if (countryStatsVersion != catalogVersion) {
            countryCountCache.clear();
            countryCodeCache.clear();
            genreCountCache.clear();
            HashMap<String, String> normalizedCountries = new HashMap<>();
            for (Channel ch : channels) {
                String rawCountry = safe(ch.country);
                String country = normalizedCountries.get(rawCountry);
                if (country == null) {
                    country = safeCountry(rawCountry);
                    normalizedCountries.put(rawCountry, country);
                }
                Integer oldCount = countryCountCache.get(country);
                countryCountCache.put(country, oldCount == null ? 1 : oldCount + 1);
                String genre = ChannelCatalogPolicy.genreName(ch);
                Integer oldGenreCount = genreCountCache.get(genre);
                genreCountCache.put(genre, oldGenreCount == null ? 1 : oldGenreCount + 1);
            }
            for (String country : countryCountCache.keySet()) {
                countryCodeCache.put(country, countryCode(country));
            }
            countryStatsVersion = catalogVersion;
        }

        String query = Channel.normalizeSearch(categorySearch);
        ArrayList<String> names = new ArrayList<>(countryCountCache.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (String country : names) {
            if (!query.isEmpty() && !Channel.normalizeSearch(country).contains(query)) continue;
            String title = flag(country, countryCodeCache.get(country)) + "  " + country;
            addCategoryRow(title, countryCountCache.get(country),
                    country.equals(activeCountry) && !favoritesOnly && !recentOnly,
                    v -> {
                        activeCountry = country;
                        favoritesOnly = false;
                        recentOnly = false;
                        renderCountryChoices();
                        updateChannelResults();
                    });
        }

        addCategorySection("GENRES");
        addCategoryRow("Tous les genres", channels.size(),
                "Tous les genres".equals(activeGenre),
                v -> {
                    activeGenre = "Tous les genres";
                    renderCountryChoices();
                    updateChannelResults();
                });
        for (String genre : ChannelCatalogPolicy.genres()) {
            int count = genreCountCache.containsKey(genre) ? genreCountCache.get(genre) : 0;
            if (!query.isEmpty() && !Channel.normalizeSearch(genre).contains(query)) continue;
            addCategoryRow(genre, count, genre.equals(activeGenre),
                    v -> {
                        activeGenre = genre;
                        favoritesOnly = false;
                        recentOnly = false;
                        renderCountryChoices();
                        updateChannelResults();
                    });
        }
    }

    private void addCategorySection(String title) {
        TextView heading = label(title, isTelevision() ? 11 : 9, MUTED, true);
        heading.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.topMargin = dp(12);
        params.bottomMargin = dp(7);
        countryChoices.addView(heading, params);
    }

    private void markCatalogChanged() {
        catalogVersion++;
        channelCatalogToken = new Object();
        countryStatsVersion = -1;
        rebuildChannelLookup();
    }

    private void rebuildChannelLookup() {
        channelUrlCache.clear();
        channelBySavedId.clear();
        for (Channel channel : channels) {
            addChannelLookup(channel, channel);
            for (Channel option : channel.playbackOptions()) addChannelLookup(option, channel);
        }
        channelUrlSetVersion = catalogVersion;
    }

    private void addChannelLookup(Channel key, Channel logical) {
        if (key == null || logical == null) return;
        String id = key.identity();
        String url = safe(key.url);
        if (!id.isEmpty()) {
            channelUrlCache.add(id);
            channelBySavedId.put(id, logical);
        }
        if (!url.isEmpty()) {
            channelUrlCache.add(url);
            channelBySavedId.put(url, logical);
        }
    }

    private void addCategoryRow(String title, int count, boolean selected, View.OnClickListener click) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(8), dp(12), dp(8));
        row.setBackground(rounded(selected ? 0xFF164A60 : 0xFF1D2632, 8));
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
            row.setBackground(outlined(focused || selected ? 0xFF164A60 : 0xFF1D2632,
                    8, focused ? 0xFF4CC9F0 : (selected ? 0xFF4CC9F0 : 0x00FFFFFF)));
            name.setTextColor(focused || selected ? YELLOW : Color.WHITE);
            number.setTextColor(focused || selected ? YELLOW : Color.WHITE);
            row.setScaleX(focused ? 1.025f : 1f);
            row.setScaleY(focused ? 1.025f : 1f);
        });
        row.setOnClickListener(click);
    }

    private ArrayList<String> readRecentUrls() {
        return recentStore == null ? new ArrayList<>() : new ArrayList<>(recentStore.orderedUrls());
    }

    private void rememberRecentlyViewed(Channel ch) {
        if (ch != null && recentStore != null) recentStore.remember(ch.identity());
    }

    private int countFavoriteChannels() {
        long revision = favoritesStore == null ? 0L : favoritesStore.revision();
        if (favoriteCountCatalogVersion != catalogVersion || favoriteCountStoreRevision != revision) {
            favoriteCountCache = favoritesStore == null ? 0 : favoritesStore.countIn(channels);
            favoriteCountCatalogVersion = catalogVersion;
            favoriteCountStoreRevision = revision;
        }
        return favoriteCountCache;
    }

    private int countRecentChannels() {
        long revision = recentStore == null ? 0L : recentStore.revision();
        if (recentCountCatalogVersion != catalogVersion || recentCountStoreRevision != revision) {
            int count = 0;
            if (recentStore != null) {
                Set<String> recent = recentStore.snapshotUrls(); // At most 100 URLs.
                if (channelUrlSetVersion == catalogVersion) {
                    for (String url : recent) if (channelUrlCache.contains(url)) count++;
                } else {
                    for (Channel ch : channels) if (ch.matchesSavedIds(recent)) count++;
                }
            }
            recentCountCache = count;
            recentCountCatalogVersion = catalogVersion;
            recentCountStoreRevision = revision;
        }
        return recentCountCache;
    }

    private void scheduleSearchFilter() {
        if (pendingSearchFilter != null) uiHandler.removeCallbacks(pendingSearchFilter);
        pendingSearchFilter = () -> {
            pendingSearchFilter = null;
            updateChannelResults();
        };
        uiHandler.postDelayed(pendingSearchFilter, 120L);
    }

    private void updateChannelResults() {
        if (channelResults == null || channelAdapter == null || !"Live TV".equals(activePage)) return;

        final int requestId = filterGeneration.incrementAndGet();
        if (filterJob != null) filterJob.cancel(true);
        filterExecutor.purge();

        final RecyclerView targetRecycler = channelResults;
        final ChannelAdapter targetAdapter = channelAdapter;
        final String requestedSource = sourceFilter;
        final String requestedCountry = activeCountry;
        final String requestedGenre = activeGenre;
        final String requestedSearch = searchText;
        final boolean requestedFavorites = favoritesOnly;
        final boolean requestedRecent = recentOnly;
        final String filterKey = requestedSource + "|" + requestedCountry + "|" + requestedGenre
                + "|" + safe(requestedSearch) + "|" + requestedFavorites + "|" + requestedRecent;
        final boolean filtersChanged = !TextUtils.equals(filterKey, lastRenderedFilterKey);
        lastRenderedFilterKey = filterKey;

        final ArrayList<Channel> channelSnapshot = new ArrayList<>(channels);
        final Set<String> favoriteSnapshot = favoritesStore == null
                ? Collections.emptySet() : favoritesStore.snapshotUrls();
        final Set<String> recentSnapshot = recentStore == null
                ? Collections.emptySet() : recentStore.snapshotUrls();

        filterJob = filterExecutor.submit(() -> {
            ArrayList<Channel> filtered = ChannelFilter.filter(channelSnapshot, requestedSource,
                    requestedCountry, requestedGenre, requestedSearch, requestedFavorites,
                    requestedRecent, favoriteSnapshot, recentSnapshot);

            runOnUiThread(() -> {
                if (activityDestroyed || requestId != filterGeneration.get()
                        || targetRecycler != channelResults || targetAdapter != channelAdapter
                        || !requestedSource.equals(sourceFilter)
                        || (!"Live TV".equals(activePage) && !"Accueil".equals(activePage))) return;

                if (countView != null) countView.setText(filtered.size() + " chaînes");
                if (filtered.isEmpty()) {
                    targetAdapter.submitList(Collections.emptyList());
                    targetRecycler.setVisibility(View.GONE);
                    channelEmptyView.setVisibility(View.VISIBLE);
                    channelEmptyView.removeAllViews();

                    LinearLayout empty = vertical();
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(dp(14), dp(22), dp(14), dp(22));
                    empty.setBackground(rounded(0xFF151D28, 12));

                    TextView heading = label(channelSnapshot.isEmpty()
                            ? (loadingCatalog ? "Connexion au catalogue…" : "Catalogue prêt à charger")
                            : "Aucune chaîne trouvée", isTelevision() ? 18 : 14, Color.WHITE, true);
                    heading.setGravity(Gravity.CENTER);
                    empty.addView(heading, lp(-1, -2));

                    TextView msg = label(channelSnapshot.isEmpty()
                            ? "Le catalogue se charge en arrière-plan. Tu peux parcourir les autres rubriques pendant ce temps."
                            : "Modifie le pays, le genre ou la recherche pour élargir le résultat.",
                            isTelevision() ? 12 : 10, 0xEEFFFFFF, false);
                    msg.setGravity(Gravity.CENTER);
                    msg.setPadding(0, dp(8), 0, dp(12));
                    empty.addView(msg, lp(-1, -2));

                    TextView retry = buttonText(loadingCatalog ? "Chargement…" : "↻ Actualiser les catalogues", true);
                    empty.addView(retry, lp(-2, isTelevision() ? 46 : 38));
                    retry.setOnClickListener(v -> loadChannels());
                    channelEmptyView.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                    return;
                }

                channelEmptyView.setVisibility(View.GONE);
                targetRecycler.setVisibility(View.VISIBLE);
                targetAdapter.setSelectedUrl(currentChannel == null ? null : currentChannel.identity());
                targetAdapter.submitList(filtered, () -> {
                    if (requestId != filterGeneration.get()
                            || targetRecycler != channelResults || targetAdapter != channelAdapter) return;
                    if (filtersChanged && targetAdapter.getItemCount() > 0) {
                        targetRecycler.scrollToPosition(0);
                    }
                });
            });
        });
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1,-1));

        page.addView(label("Paramètres", 26, TEXT, true));
        TextView intro = label("Catalogues réunis, doublons regroupés et source choisie automatiquement selon la fiabilité.", 11, MUTED, false);
        LinearLayout.LayoutParams ip = lp(-1,-2); ip.topMargin=dp(6); ip.bottomMargin=dp(20); page.addView(intro,ip);

        TextView playerHeading = label("LECTEUR DE DIRECT", 9, MUTED, true);
        page.addView(playerHeading);
        TextView playerHelp = label("Un seul lecteur natif Media3, avec commandes et navigation conçues pour la télécommande.", 10, MUTED, false);
        LinearLayout.LayoutParams php = lp(-1,-2); php.topMargin = dp(8); php.bottomMargin = dp(18); page.addView(playerHelp, php);

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

        TextView refresh = buttonText(loadingCatalog ? "Actualisation en cours…" : "↻  Actualiser les catalogues", true);
        LinearLayout.LayoutParams rp = lp(-1, 48); rp.topMargin = dp(16); page.addView(refresh, rp);
        refresh.setOnClickListener(v -> loadChannels(true));
        if (loadingCatalog) refresh.setAlpha(0.65f);

        TextView cache = buttonText("Effacer le cache local", false);
        LinearLayout.LayoutParams cp = lp(-1, 46); cp.topMargin = dp(10); page.addView(cache, cp);
        cache.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Effacer le cache ?")
            .setMessage("Les chaînes enregistrées sur cet appareil seront supprimées. Vos favoris sont conservés.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Effacer", (dialog, which) -> {
                CatalogCache.clear(MainActivity.this);
                CombinedCatalogCache.clear(MainActivity.this);
                Toast.makeText(this, "Cache local effacé.", Toast.LENGTH_SHORT).show();
                channels.clear();
                markCatalogChanged();
                activeCountry = "Tous les pays";
                lastRenderedFilterKey = "";
                renderPage();
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
            markCatalogChanged();
            activeCountry = "Tous les pays";
            favoritesOnly = false;
            recentOnly = false;
            categorySearch = "";
            searchText = "";
            if (searchInput != null) searchInput.setText("");
        }
        updateHeaderTabs();
        renderPage();
        if (changed || channels.isEmpty()) loadChannels();
    }

    private void loadChannels() { loadChannels(false); }

    private int countSources(List<Channel> list) {
        int count = 0;
        if (list != null) for (Channel channel : list) count += channel.sourceCount();
        return count;
    }

    private String combinedSummary(List<Channel> list, int sourceEntries, boolean cached) {
        int channelCount = list == null ? 0 : list.size();
        int duplicates = Math.max(0, sourceEntries - channelCount);
        int sourceCount = countSources(list);
        String summary = channelCount + " chaînes · " + duplicates + " doublons regroupés · "
                + sourceCount + " flux disponibles";
        return cached ? summary + " · affichage immédiat, vérification en arrière-plan" : summary;
    }

    private ArrayList<Channel> loadVavooSnapshot(boolean forceRefresh, String[] errors) {
        ArrayList<Channel> vavoo = VavooSnapshotProvider.readBundled(
                MainActivity.this, MAX_CATALOG_CHANNELS, MainActivity.this::knownCountry);
        if (forceRefresh) {
            try {
                VavooProvider.invalidateCatalogCache();
                List<VavooProvider.VavooChannel> online = VavooProvider.loadChannels();
                if (online != null && !online.isEmpty()) vavoo = convertVavooChannels(online);
                else errors[0] = "VAVOO indisponible";
            } catch (Exception e) {
                errors[0] = "VAVOO indisponible";
            }
        }
        if (vavoo == null || vavoo.isEmpty()) {
            vavoo = CatalogCache.readFull(MainActivity.this, "VAVOO", MAX_CATALOG_CHANNELS);
            if (vavoo.isEmpty()) vavoo = CatalogCache.readQuick(
                    MainActivity.this, "VAVOO", MAX_CACHED_CHANNELS);
        }
        vavoo = filterSourceCatalog(vavoo, "VAVOO");
        if (!vavoo.isEmpty()) {
            // The quick recovery snapshot is intentionally bounded; the binary M3U cache
            // already holds the full catalogue without serializing thousands of JSON rows.
            CatalogCache.saveQuick(MainActivity.this, vavoo, "VAVOO", MAX_CACHED_CHANNELS);
            if (forceRefresh) CatalogCache.saveFull(
                    MainActivity.this, vavoo, "VAVOO", MAX_CATALOG_CHANNELS);
        }
        return filterSourceCatalog(vavoo, "VAVOO");
    }

    private ArrayList<Channel> loadIptvSnapshot(boolean forceRefresh, String[] errors) {
        ArrayList<Channel> iptv = IptvOrgProvider.loadBundledChannels(
                MainActivity.this, MAX_CATALOG_CHANNELS, MainActivity.this::knownCountry);
        if (forceRefresh) {
            try {
                ArrayList<Channel> online = IptvOrgProvider.loadChannels(
                        MainActivity.this, MAX_CATALOG_CHANNELS, MainActivity.this::knownCountry,
                        null, true);
                if (online != null && !online.isEmpty()) iptv = online;
                else errors[1] = "IPTV-ORG indisponible";
            } catch (Exception e) {
                errors[1] = "IPTV-ORG indisponible";
            }
        }
        if (iptv == null || iptv.isEmpty()) {
            iptv = CatalogCache.readFull(MainActivity.this, "IPTV-ORG", MAX_CATALOG_CHANNELS);
            if (iptv.isEmpty()) iptv = CatalogCache.readQuick(
                    MainActivity.this, "IPTV-ORG", MAX_CACHED_CHANNELS);
        }
        iptv = filterSourceCatalog(iptv, "IPTV-ORG");
        if (!iptv.isEmpty()) {
            CatalogCache.saveQuick(MainActivity.this, iptv, "IPTV-ORG", MAX_CACHED_CHANNELS);
            if (forceRefresh) CatalogCache.saveFull(
                    MainActivity.this, iptv, "IPTV-ORG", MAX_CATALOG_CHANNELS);
        }
        return filterSourceCatalog(iptv, "IPTV-ORG");
    }

    private void loadCombinedChannels(boolean forceRefresh) {
        if (loadingCatalog) return;
        loadingVavoo = true;
        loadingIptv = true;
        loadingCatalog = true;
        catalogStatus = forceRefresh ? "Actualisation des catalogues…"
                : "Ouverture du catalogue…";
        updateStatus();
        if ("Paramètres".equals(activePage)) renderSettings();

        final int generation = loadGeneration.incrementAndGet();
        final String revision = CombinedCatalogCache.assetRevision(MainActivity.this);

        // Reuse the already grouped binary catalogue immediately on repeat launches.
        // The bundled sources are still checked and re-ranked in the background.
        if (!forceRefresh) {
            io.execute(() -> {
                ArrayList<Channel> cached = CombinedCatalogCache.read(
                        MainActivity.this, revision, MAX_CATALOG_CHANNELS,
                        this::sourceReliabilityScore);
                if (cached.isEmpty()) return;
                int cachedSourceCount = countSources(cached);
                runOnUiThread(() -> {
                    if (activityDestroyed || generation != loadGeneration.get()) return;
                    channels.clear();
                    channels.addAll(cached);
                    markCatalogChanged();
                    catalogStatus = combinedSummary(cached, cachedSourceCount, true);
                    updateStatus();
                    updateHeaderTabs();
                    if ("Paramètres".equals(activePage)) renderSettings();
                    if (fullscreenPlayback) updateChannelResults();
                    else renderPage();
                });
            });
        }

        final String[] refreshErrors = {"", ""};
        final Future<ArrayList<Channel>> vavooTask = io.submit(
                () -> loadVavooSnapshot(forceRefresh, refreshErrors));
        final Future<ArrayList<Channel>> iptvTask = io.submit(
                () -> loadIptvSnapshot(forceRefresh, refreshErrors));

        io.execute(() -> {
            ArrayList<Channel> vavoo;
            ArrayList<Channel> iptv;
            try {
                vavoo = vavooTask.get();
            } catch (Exception e) {
                vavoo = new ArrayList<>();
                refreshErrors[0] = "VAVOO indisponible";
            }
            try {
                iptv = iptvTask.get();
            } catch (Exception e) {
                iptv = new ArrayList<>();
                refreshErrors[1] = "IPTV-ORG indisponible";
            }

            int sourceEntryCount = vavoo.size() + iptv.size();
            ArrayList<Channel> merged = ChannelDeduplicator.merge(
                    vavoo, iptv, this::sourceReliabilityScore);
            final ArrayList<Channel> result = merged;
            final int sourceCount = sourceEntryCount;
            String refreshNote = "";
            if (!refreshErrors[0].isEmpty()) refreshNote = refreshErrors[0];
            if (!refreshErrors[1].isEmpty()) {
                refreshNote += (refreshNote.isEmpty() ? "" : " · ") + refreshErrors[1];
            }
            final String note = refreshNote;

            runOnUiThread(() -> {
                loadingVavoo = false;
                loadingIptv = false;
                loadingCatalog = false;
                if (activityDestroyed) return;
                if (generation != loadGeneration.get()) {
                    updateStatus();
                    return;
                }
                if (!result.isEmpty()) {
                    channels.clear();
                    channels.addAll(result);
                    markCatalogChanged();
                    catalogStatus = combinedSummary(result, sourceCount, false);
                    if (!note.isEmpty()) {
                        catalogStatus += " · " + note + " (catalogue intégré conservé)";
                    }
                } else if (!channels.isEmpty()) {
                    catalogStatus = combinedSummary(channels, countSources(channels), true)
                            + " · actualisation impossible, catalogue enregistré conservé";
                } else {
                    catalogStatus = "Aucun catalogue disponible";
                    if (!note.isEmpty()) catalogStatus += " · " + note;
                }
                updateStatus();
                updateHeaderTabs();
                if ("Paramètres".equals(activePage)) renderSettings();
                if (fullscreenPlayback) updateChannelResults();
                else renderPage();
                if (channels.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "Catalogue indisponible. Vérifie ta connexion ou réessaie plus tard.",
                            Toast.LENGTH_LONG).show();
                }
            });

            if (!result.isEmpty()) {
                CombinedCatalogCache.write(MainActivity.this, revision, result, MAX_CATALOG_CHANNELS);
            }
        });
    }

    private void loadChannels(boolean forceRefresh) {
        if ("TOUS".equals(sourceFilter)) {
            loadCombinedChannels(forceRefresh);
            return;
        }
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

        final AtomicInteger lastVavooPublished = new AtomicInteger(channels.size());
        io.execute(() -> {
            // VAVOO is shipped as a compact, streaming-parsed M3U snapshot. Use it
            // as the startup catalogue and skip the 35+ page network crawl entirely.
            // We keep the stream resolver unchanged; only metadata comes from this file.
            if (requestVavoo && !forceRefresh) {
                ArrayList<Channel> bundled = VavooSnapshotProvider.readBundled(
                        MainActivity.this, MAX_CATALOG_CHANNELS, MainActivity.this::knownCountry);
                if (bundled.size() >= 500) {
                    CatalogCache.saveQuick(MainActivity.this, bundled, requestedSource, MAX_CACHED_CHANNELS);
                    final ArrayList<Channel> ready = bundled;
                    runOnUiThread(() -> {
                        loadingVavoo = false;
                        loadingCatalog = loadingVavoo || loadingIptv;
                        if (activityDestroyed) return;
                        if (currentGen != loadGeneration.get()) {
                            if (requestedSource.equals(sourceFilter) && !isFinishing()) loadChannels();
                            return;
                        }
                        if (!requestedSource.equals(sourceFilter)) {
                            updateStatus();
                            return;
                        }
                        channels.clear();
                        channels.addAll(ready);
                        markCatalogChanged();
                        catalogStatus = requestedSource + " · " + ready.size()
                                + " chaînes · catalogue M3U préchargé";
                        updateStatus();
                        updateHeaderTabs();
                        if (fullscreenPlayback) {
                            renderCountryChoices();
                            updateChannelResults();
                        } else {
                            renderPage();
                        }
                    });
                    return;
                }
            }

            // IPTV-org also ships as its own original M3U playlist. Parse it off-thread
            // and publish one complete snapshot instead of rendering hundreds of partial
            // lists while downloading/parsing the remote playlist on every launch.
            if (!requestVavoo && !forceRefresh) {
                ArrayList<Channel> bundled = IptvOrgProvider.loadBundledChannels(
                        MainActivity.this, MAX_CATALOG_CHANNELS, MainActivity.this::knownCountry);
                if (!bundled.isEmpty()) {
                    final ArrayList<Channel> ready = bundled;
                    runOnUiThread(() -> {
                        loadingIptv = false;
                        loadingCatalog = loadingVavoo || loadingIptv;
                        if (activityDestroyed) return;
                        if (currentGen != loadGeneration.get()) {
                            if (requestedSource.equals(sourceFilter) && !isFinishing()) loadChannels();
                            return;
                        }
                        if (!requestedSource.equals(sourceFilter)) {
                            updateStatus();
                            return;
                        }
                        channels.clear();
                        channels.addAll(ready);
                        markCatalogChanged();
                        catalogStatus = requestedSource + " · " + ready.size()
                                + " chaînes · catalogue M3U préchargé";
                        updateStatus();
                        updateHeaderTabs();
                        if (fullscreenPlayback) {
                            renderCountryChoices();
                            updateChannelResults();
                        } else {
                            renderPage();
                        }
                    });
                    return;
                }
            }

            // Only the fallback/manual-refresh path reads the legacy disk cache and
            // performs a remote catalogue request. The normal VAVOO start above returns early.
            ArrayList<Channel> quickCached = filterSourceCatalog(CatalogCache.readQuick(
                    MainActivity.this, requestedSource, MAX_CACHED_CHANNELS), requestedSource);
            showCachedSnapshot(quickCached, requestedSource, currentGen);

            ArrayList<Channel> diskCached = filterSourceCatalog(CatalogCache.readFull(
                    MainActivity.this, requestedSource, MAX_CATALOG_CHANNELS), requestedSource);

            if (!forceRefresh && !diskCached.isEmpty()
                    && CatalogCache.isFresh(MainActivity.this, requestedSource, 12L * 60L * 60L * 1000L)) {
                final ArrayList<Channel> restored = diskCached;
                runOnUiThread(() -> {
                    if (requestVavoo) loadingVavoo = false; else loadingIptv = false;
                    loadingCatalog = loadingVavoo || loadingIptv;
                    if (activityDestroyed) return;
                    if (currentGen != loadGeneration.get()) {
                        if (requestedSource.equals(sourceFilter) && !isFinishing()) loadChannels();
                        return;
                    }
                    if (!requestedSource.equals(sourceFilter)) {
                        updateStatus();
                        return;
                    }
                    channels.clear();
                    channels.addAll(restored);
                    markCatalogChanged();
                    catalogStatus = requestedSource + " · " + restored.size()
                            + " chaînes · catalogue local prêt";
                    updateStatus();
                    updateHeaderTabs();
                    if (fullscreenPlayback) {
                        renderCountryChoices();
                        updateChannelResults();
                    } else {
                        renderPage();
                    }
                });
                return;
            }

            showCachedSnapshot(diskCached, requestedSource, currentGen);

            ArrayList<Channel> loaded = new ArrayList<>();
            String error = "";
            if (requestVavoo) {
                try {
                    List<VavooProvider.VavooChannel> sourceChannels = VavooProvider.loadChannels(partial -> {
                        int size = partial == null ? 0 : partial.size();
                        int previous = lastVavooPublished.get();
                        if (size <= 0 || (previous > 0 && size - previous < 1500)
                                || !lastVavooPublished.compareAndSet(previous, size)) return;
                        publishCatalogSnapshot(convertVavooChannels(partial), requestedSource,
                                currentGen);
                    });

                    for (VavooProvider.VavooChannel item : sourceChannels) {
                        if (loaded.size() >= MAX_CATALOG_CHANNELS) break;
                        loaded.add(toAppChannel(item));
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? "catalogue VAVOO indisponible" : e.getMessage();
                }
            } else {
                try {
                    loaded.addAll(IptvOrgProvider.loadChannels(MainActivity.this, MAX_CATALOG_CHANNELS,
                            this::knownCountry,
                            partial -> publishCatalogSnapshot(new ArrayList<>(partial),
                                    requestedSource, currentGen),
                            forceRefresh));
                } catch (Exception e) {
                    error = e.getMessage() == null
                            ? "playlist IPTV-ORG indisponible" : e.getMessage();
                }
            }

            final ArrayList<Channel> result = filterSourceCatalog(loaded, requestedSource);
            final String loadError = error;
            if (!result.isEmpty()) {
                CatalogCache.saveQuick(MainActivity.this, result, requestedSource, MAX_CACHED_CHANNELS);
            }
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
                        markCatalogChanged();
                        catalogStatus = requestedSource + " · " + result.size() + " chaînes chargées";
                    } else if (!channels.isEmpty()) {
                        catalogStatus = requestedSource + " · connexion impossible, cache/aperçu conservé";
                    } else {
                        catalogStatus = requestedSource + " · aucun flux reçu"
                                + (loadError.isEmpty() ? "" : " · " + loadError);
                    }
                    updateStatus();
                    updateHeaderTabs();
                    if (fullscreenPlayback) {
                        renderCountryChoices();
                        updateChannelResults();
                    } else {
                        renderPage();
                    }
                    if (result.isEmpty() && channels.isEmpty()) {
                        Toast.makeText(MainActivity.this,
                                requestedSource + " : chargement impossible. Vérifiez votre connexion.",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    updateStatus();
                }
            });

            // Saving the complete catalogue happens off the UI thread; subsequent launches
            // can restore all countries/channels without waiting for remote pagination.
            // IPTV-org remains cached locally. VAVOO uses the bundled M3U as the
            // canonical startup snapshot, so rebuilding a duplicate JSON catalogue is wasted work.
            if (!result.isEmpty() && !requestVavoo && forceRefresh) {
                CatalogCache.saveFull(MainActivity.this, result, requestedSource, MAX_CATALOG_CHANNELS);
            }
        });
    }

    private ArrayList<Channel> filterSourceCatalog(List<Channel> sourceChannels, String source) {
        return "IPTV-ORG".equalsIgnoreCase(safe(source))
                ? ChannelCatalogPolicy.filterIptvOrg(sourceChannels)
                : ChannelCatalogPolicy.filterVavoo(sourceChannels);
    }

    private Channel toAppChannel(VavooProvider.VavooChannel item) {
        String country = knownCountry(item.country);
        if (country.isEmpty()) country = knownCountry(item.group);
        if (country.isEmpty() && item.country != null && !item.country.trim().isEmpty()) {
            country = item.country.trim();
        }
        if (country.isEmpty()) {
            String metadata = Channel.normalizeSearch(item.group);
            country = metadata.contains("arab") ? "Pays arabes"
                    : (metadata.contains("maghreb") ? "Maghreb" : "Pays non précisé");
        }
        return new Channel(item.name, "vavoo://" + item.id, item.logo,
                "VAVOO · " + (item.group == null || item.group.trim().isEmpty()
                ? "Direct" : item.group), true, item.sourceUrl, country);
    }

    private ArrayList<Channel> convertVavooChannels(List<VavooProvider.VavooChannel> items) {
        ArrayList<Channel> converted = new ArrayList<>(Math.min(items.size(), MAX_CATALOG_CHANNELS));
        for (VavooProvider.VavooChannel item : items) {
            if (converted.size() >= MAX_CATALOG_CHANNELS) break;
            converted.add(toAppChannel(item));
        }
        return ChannelCatalogPolicy.filterVavoo(converted);
    }

    private void publishCatalogSnapshot(ArrayList<Channel> snapshot, String requestedSource,
                                        int generation) {
        if (snapshot == null || snapshot.isEmpty()) return;
        final ArrayList<Channel> copy = filterSourceCatalog(snapshot, requestedSource);
        runOnUiThread(() -> {
            if (activityDestroyed || generation != loadGeneration.get()
                    || !requestedSource.equals(sourceFilter)
                    || copy.isEmpty() || copy.size() <= channels.size()) return;
            channels.clear();
            channels.addAll(copy);
            markCatalogChanged();
            catalogStatus = requestedSource + " · " + copy.size()
                    + " chaînes reçues, catalogue en chargement…";
            updateStatus();
            updateHeaderTabs();
            renderCountryChoices();
            updateChannelResults();
        });
    }

    private void showCachedSnapshot(ArrayList<Channel> cacheSnapshot, String requestedSource,
                                    int generation) {
        if (cacheSnapshot == null || cacheSnapshot.isEmpty()) return;
        final ArrayList<Channel> snapshot = filterSourceCatalog(cacheSnapshot, requestedSource);
        runOnUiThread(() -> {
            if (activityDestroyed || generation != loadGeneration.get()
                    || !requestedSource.equals(sourceFilter) || snapshot.isEmpty()
                    || snapshot.size() <= channels.size()) return;
            channels.clear();
            channels.addAll(snapshot);
            markCatalogChanged();
            catalogStatus = requestedSource + " · " + snapshot.size()
                    + " chaînes en cache, actualisation…";
            updateStatus();
            updateHeaderTabs();
            renderCountryChoices();
            updateChannelResults();
        });
    }

    private void updateStatus() {
        if (statusView != null) statusView.setText(catalogStatus);
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
        if (ch == null) return false;
        if (favoritesStore != null) return ch.matchesSavedIds(favoritesStore.snapshotUrls());
        SharedPreferences preferences = getSharedPreferences("favorites", MODE_PRIVATE);
        if (preferences.getBoolean(ch.identity(), false) || preferences.getBoolean(ch.url, false)) return true;
        for (Channel option : ch.playbackOptions()) {
            if (preferences.getBoolean(option.url, false)) return true;
        }
        return false;
    }

    private void toggleFavorite(Channel ch) {
        if (ch == null) return;
        boolean nowFavorite = !isFavorite(ch);
        if (favoritesStore != null) {
            favoritesStore.setFavorite(ch.identity(), nowFavorite);
            if (!nowFavorite) {
                for (Channel option : ch.playbackOptions()) {
                    favoritesStore.setFavorite(option.url, false);
                }
            }
        } else {
            SharedPreferences preferences = getSharedPreferences("favorites", MODE_PRIVATE);
            SharedPreferences.Editor editor = preferences.edit().putBoolean(ch.identity(), nowFavorite);
            if (!nowFavorite) for (Channel option : ch.playbackOptions()) editor.remove(option.url);
            editor.apply();
        }
    }

    private void enterFullscreenPlayback() {
        if (fullscreenPlayback || playerStage == null || contentHost == null
                || (!"Live TV".equals(activePage) && !"Accueil".equals(activePage))) return;
        if (!(playerStage.getParent() instanceof ViewGroup)) return;

        playerStageParent = (ViewGroup) playerStage.getParent();
        playerStageOriginalIndex = playerStageParent.indexOfChild(playerStage);
        playerStageOriginalLayoutParams = playerStage.getLayoutParams();
        if (getCurrentFocus() != null) playbackReturnFocus = getCurrentFocus();

        View placeholder = new FrameLayout(this);
        placeholder.setBackgroundColor(Color.BLACK);
        playerStageParent.removeView(playerStage);
        playerStageParent.addView(placeholder, playerStageOriginalIndex, playerStageOriginalLayoutParams);
        playerStagePlaceholder = placeholder;

        contentHost.addView(playerStage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenPlayback = true;
        if (headerView != null) headerView.setVisibility(View.GONE);
        if (statusView != null) statusView.setVisibility(View.GONE);
        playerStage.setFocusable(true);
        playerStage.setFocusableInTouchMode(true);
        playerStage.bringToFront();
        playerStage.requestFocus();
        if (playerView != null) {
            playerView.setFocusable(true);
            playerView.requestFocus();
        }
    }

    private void exitFullscreenPlayback() {
        if (!fullscreenPlayback) return;
        fullscreenPlayback = false;

        if (playerStage != null && playerStage.getParent() instanceof ViewGroup) {
            ((ViewGroup) playerStage.getParent()).removeView(playerStage);
        }
        if (playerStageParent != null) {
            if (playerStagePlaceholder != null) playerStageParent.removeView(playerStagePlaceholder);
            int index = Math.max(0, Math.min(playerStageOriginalIndex, playerStageParent.getChildCount()));
            if (playerStage != null) {
                if (playerStageOriginalLayoutParams != null) {
                    playerStageParent.addView(playerStage, index, playerStageOriginalLayoutParams);
                } else {
                    playerStageParent.addView(playerStage, index);
                }
                playerStage.setFocusable(false);
                playerStage.setFocusableInTouchMode(false);
            }
        }

        playerStageParent = null;
        playerStagePlaceholder = null;
        playerStageOriginalLayoutParams = null;
        playerStageOriginalIndex = -1;
        if (headerView != null) headerView.setVisibility(View.VISIBLE);
        if (statusView != null) statusView.setVisibility(View.VISIBLE);
        if (playbackReturnFocus != null && playbackReturnFocus.isAttachedToWindow()) {
            playbackReturnFocus.requestFocus();
        } else if (channelResults != null) {
            channelResults.requestFocus();
        } else if (liveTab != null) {
            liveTab.requestFocus();
        }
    }

    private void showSourceChoices(Channel channel) {
        if (channel == null) {
            Toast.makeText(this, "Sélectionne une chaîne pour voir ses flux.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Channel> options = channel.playbackOptions();
        if (options.size() < 2) {
            Toast.makeText(this, "Cette chaîne n’a qu’un seul flux disponible.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            Channel option = options.get(i);
            labels[i] = (option.isVavoo ? "VAVOO" : "IPTV-ORG")
                    + "  ·  " + option.name + (i == 0 ? "  ·  Recommandée" : "  ·  Autre flux");
        }
        new AlertDialog.Builder(this)
                .setTitle("Flux disponibles · " + channel.name)
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= options.size()) return;
                    ArrayList<Channel> remaining = new ArrayList<>(options.subList(which, options.size()));
                    Channel selected = Channel.grouped(remaining.get(0), remaining, channel.identity());
                    preserveManualSourceOrder = true;
                    play(selected);
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void play(Channel ch) {
        if (ch == null) return;
        playbackReturnFocus = getCurrentFocus();
        currentChannel = ch;
        if (sourceOptionsButton != null) {
            sourceOptionsButton.setText("Sources (" + ch.sourceCount() + ")");
            sourceOptionsButton.setEnabled(ch.sourceCount() > 1);
            sourceOptionsButton.setAlpha(sourceOptionsButton.isEnabled() ? 1f : 0.55f);
        }
        if (channelAdapter != null) channelAdapter.setSelectedUrl(ch.identity());
        rememberRecentlyViewed(ch);
        if (previewTitle != null) previewTitle.setText(safe(ch.name));
        if (previewStatus != null) previewStatus.setText(
                "Source " + (ch.isVavoo ? "VAVOO" : "IPTV-ORG")
                        + (ch.sourceCount() > 1
                            ? " · " + ch.sourceCount() + " flux, secours automatique prêt…"
                            : " · connexion au direct…"));
        if (previewDetail != null) previewDetail.setText(
                "AUTO  ·  " + safeCountry(ch.country) + "  ·  " + ch.sourceCount() + " sources");
        renderCountryChoices();
        enterFullscreenPlayback();
        startPlayback(ch);
    }

    private void zapChannel(Channel current, int direction) {
        if (current == null || channels.isEmpty()) return;
        int currentIndex = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (TextUtils.equals(channels.get(i).identity(), current.identity())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) return;
        int nextIndex = (currentIndex + direction + channels.size()) % channels.size();
        play(channels.get(nextIndex));
    }

    private int sourceReliabilityScore(Channel source) {
        if (source == null) return Integer.MIN_VALUE;
        SharedPreferences preferences = sourceHealthPreferences == null
                ? getSharedPreferences("source_health", MODE_PRIVATE) : sourceHealthPreferences;
        String key = "source_" + Integer.toHexString(source.url.hashCode());
        int successes = preferences.getInt(key + "_ok", 0);
        int failures = preferences.getInt(key + "_fail", 0);
        int score = (int) (((long) (successes + 1) * 1000L) / (successes + failures + 2L));
        if (successes == 0 && failures == 0) {
            boolean morocco = "MA".equals(ChannelCatalogPolicy.countryCode(source.country));
            score += (source.isVavoo == morocco) ? 6 : 18;
        }
        if (!source.logo.isEmpty()) score += 3;
        long lastSuccess = preferences.getLong(key + "_last_ok", 0L);
        if (lastSuccess > 0L) {
            long hours = Math.max(0L, (System.currentTimeMillis() - lastSuccess) / 3_600_000L);
            score += (int) Math.max(0L, 40L - Math.min(40L, hours));
        }
        return score;
    }

    private void recordSourceOutcome(Channel source, boolean success) {
        if (source == null) return;
        SharedPreferences preferences = sourceHealthPreferences == null
                ? getSharedPreferences("source_health", MODE_PRIVATE) : sourceHealthPreferences;
        String key = "source_" + Integer.toHexString(source.url.hashCode());
        SharedPreferences.Editor edit = preferences.edit();
        if (success) {
            edit.putInt(key + "_ok", preferences.getInt(key + "_ok", 0) + 1);
            edit.putLong(key + "_last_ok", System.currentTimeMillis());
        } else {
            edit.putInt(key + "_fail", preferences.getInt(key + "_fail", 0) + 1);
        }
        edit.apply();
    }

    private void startPlayback(Channel ch) {
        playbackCandidates = new ArrayList<>(ch.playbackOptions());
        if (!preserveManualSourceOrder) {
            Collections.sort(playbackCandidates, (left, right) ->
                    Integer.compare(sourceReliabilityScore(right), sourceReliabilityScore(left)));
        }
        preserveManualSourceOrder = false;
        playbackCandidateIndex = -1;
        activePlaybackSource = null;
        if (!attemptNextSource(ch)) {
            if (previewStatus != null) previewStatus.setText("Aucune source disponible");
            Toast.makeText(this, "Aucun flux disponible pour cette chaîne.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean attemptNextSource(Channel logicalChannel) {
        if (currentChannel != logicalChannel) return false;
        int next = playbackCandidateIndex + 1;
        if (next >= playbackCandidates.size()) return false;
        playbackCandidateIndex = next;
        Channel candidate = playbackCandidates.get(next);
        activePlaybackSource = candidate;
        activeSourceSucceeded = false;
        releaseMediaEngine();
        if (playerStage == null) return false;
        playerStage.removeAllViews();
        if (previewStatus != null) previewStatus.setText(
                "Essai de la source " + (next + 1) + " sur " + playbackCandidates.size() + "…");
        if (previewDetail != null) previewDetail.setText(
                (candidate.isVavoo ? "VAVOO" : "IPTV-ORG") + "  ·  " + safeCountry(logicalChannel.country));

        if (candidate.isVavoo) {
            io.execute(() -> {
                try {
                    String resolved = VavooProvider.resolveStream(candidate.vavooSourceUrl);
                    runOnUiThread(() -> {
                        if (currentChannel != logicalChannel || activePlaybackSource != candidate) return;
                        startResolvedPlayback(logicalChannel, candidate, resolved);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (currentChannel != logicalChannel || activePlaybackSource != candidate) return;
                        recordSourceOutcome(candidate, false);
                        if (!attemptNextSource(logicalChannel)) showNoWorkingSource(logicalChannel);
                    });
                }
            });
        } else {
            startResolvedPlayback(logicalChannel, candidate, candidate.url);
        }
        return true;
    }

    private void showNoWorkingSource(Channel logicalChannel) {
        if (previewStatus != null) previewStatus.setText("Aucune source ne répond");
        if (playerView != null) playerView.setErrorMessage("Toutes les sources sont indisponibles");
        Toast.makeText(this, "Toutes les sources de cette chaîne sont indisponibles.", Toast.LENGTH_LONG).show();
    }

    private void startResolvedPlayback(Channel logicalChannel, Channel candidate, String streamUrl) {
        if (playerStage == null || currentChannel != logicalChannel || activePlaybackSource != candidate) return;
        startMedia3Playback(logicalChannel, candidate, streamUrl);
    }

    private void startMedia3Playback(Channel ch, Channel candidate, String streamUrl) {
        NativeLivePlayerView nativeView = new NativeLivePlayerView(this);
        try {
            ExoPlayer active = LivePlaybackFactory.create(this,
                    candidate.isVavoo ? "VAVOO/2.6" : "NISSKENZI+/2.1");
            nativeView.setChannelName(ch.name);
            nativeView.setSourceLabel((candidate.isVavoo ? "VAVOO" : "IPTV-ORG")
                    + " · source " + (playbackCandidateIndex + 1) + "/" + playbackCandidates.size());
            // The visible Back button dismisses the on-screen bars. Physical Back
            // closes the drawer, then hides controls, then exits fullscreen.
            nativeView.setDrawerChannels(channels, channelCatalogToken, ch.identity(), this::play,
                    this::loadEpgForCurrentChannel);
            nativeView.setChannelZapActions(
                    () -> zapChannel(ch, -1), () -> zapChannel(ch, 1));
            nativeView.setPlaybackState(Player.STATE_BUFFERING);

            player = active;
            playerView = nativeView;
            playerStage.addView(nativeView, new FrameLayout.LayoutParams(-1, -1));
            nativeView.setPlayer(active);
            nativeView.setFocusable(true);
            if (fullscreenPlayback) nativeView.post(() -> {
                if (fullscreenPlayback && playerView == nativeView) nativeView.focusControls();
            });

            active.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (currentChannel != ch || activePlaybackSource != candidate
                            || playerView != nativeView || previewStatus == null) return;
                    nativeView.setPlaybackState(playbackState);
                    if (playbackState == Player.STATE_READY) {
                        if (!activeSourceSucceeded) {
                            recordSourceOutcome(candidate, true);
                            activeSourceSucceeded = true;
                        }
                        previewStatus.setText("● EN DIRECT · " + (candidate.isVavoo ? "VAVOO" : "IPTV-ORG"));
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        previewStatus.setText("Connexion · mise en mémoire tampon…");
                    } else {
                        previewStatus.setText("Connexion au flux…");
                    }
                }

                @Override public void onPlayerError(PlaybackException e) {
                    if (currentChannel == ch && activePlaybackSource == candidate && playerView == nativeView) {
                        recordSourceOutcome(candidate, false);
                        if (attemptNextSource(ch)) return;
                        nativeView.setErrorMessage("Toutes les sources sont indisponibles");
                        if (previewStatus != null) {
                            previewStatus.setText("Flux indisponible · toutes les sources ont échoué");
                        }
                    }
                    Toast.makeText(MainActivity.this,
                            "Lecture impossible (" + e.getErrorCodeName() + ").",
                            Toast.LENGTH_SHORT).show();
                }
            });
            active.setMediaItem(LivePlaybackFactory.mediaItem(streamUrl, ch.name));
            active.prepare();
            active.setPlayWhenReady(true);
        } catch (Exception e) {
            recordSourceOutcome(candidate, false);
            if (!attemptNextSource(ch)) {
                if (nativeView != null) nativeView.setErrorMessage("Démarrage impossible");
                Toast.makeText(this, "Impossible de démarrer le flux : " + safe(e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadEpgForCurrentChannel() {
        final Channel requested = currentChannel;
        final NativeLivePlayerView target = playerView;
        if (requested == null || target == null) return;
        target.setEpgInfo(EpgProvider.Result.loading());
        io.execute(() -> {
            EpgProvider.Result result = EpgProvider.lookup(MainActivity.this, requested);
            runOnUiThread(() -> {
                if (activityDestroyed || currentChannel != requested || playerView != target) return;
                target.setEpgInfo(result);
            });
        });
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
    }

    private void stopPreview() {
        releaseMediaEngine();
        currentChannel = null;
        if (sourceOptionsButton != null) {
            sourceOptionsButton.setText("Sources");
            sourceOptionsButton.setEnabled(false);
            sourceOptionsButton.setAlpha(0.55f);
        }
        activePlaybackSource = null;
        playbackCandidates.clear();
        playbackCandidateIndex = -1;
        if (channelAdapter != null) channelAdapter.setSelectedUrl(null);
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
        if (fullscreenPlayback) {
            if (playerView != null && playerView.handleBackPressed()) return;
            exitFullscreenPlayback();
        } else if ("Paramètres".equals(activePage) || "Live TV".equals(activePage)) {
            setPage("Accueil");
        } else if (player != null) {
            stopPreview();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        loadGeneration.incrementAndGet();
        filterGeneration.incrementAndGet();
        if (filterJob != null) filterJob.cancel(true);
        filterExecutor.shutdownNow();
        if (pendingSearchFilter != null) uiHandler.removeCallbacks(pendingSearchFilter);
        releasePlayer();
        io.shutdownNow();
        if (channelAdapter != null) channelAdapter.shutdown();
        if (homeLogoLoader != null) homeLogoLoader.close();
        super.onDestroy();
    }
}