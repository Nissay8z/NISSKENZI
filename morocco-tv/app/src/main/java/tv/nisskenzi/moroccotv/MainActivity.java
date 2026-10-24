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
    private static final int PANEL = 0xEE1A1B20;
    private static final int LINE = 0x44FFFFFF;
    private static final int CYAN = 0xFFEA5265;
    private static final int PURPLE = 0xFFFF8999;
    private static final int MUTED = 0xFFB6B7C0;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GREEN = 0xFF6DD49A;
    private static final int YELLOW = 0xFFFFE8EA;
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
    private String activeGuideCountry = "Tous";
    private String homePreviewIdentity = "";
    private final Map<String, TextView[]> guideProgramViews = new HashMap<>();
    private final Map<String, EpgProvider.Result> guideResults = new HashMap<>();
    private final Set<String> guidePending = new HashSet<>();
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
    private boolean groupedOnly = false;

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
        TextView b = label(text, isTelevision() ? 15 : 12, selected ? Color.WHITE : 0xFFE6E6EB, true);
        b.setGravity(Gravity.CENTER);
        int padH = isTelevision() ? 18 : 12;
        b.setPadding(dp(padH), dp(isTelevision() ? 11 : 8), dp(padH), dp(isTelevision() ? 11 : 8));
        b.setBackground(rounded(selected ? 0xFF3B2027 : 0x001A1B20, 20));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                b.setBackground(outlined(0xFF402229, 20, CYAN));
                b.setTextColor(Color.WHITE);
                b.setScaleX(1.04f);
                b.setScaleY(1.04f);
            } else {
                boolean active = b == liveTab ? "Accueil".equals(activePage)
                        : b == vavooTab ? ("Live TV".equals(activePage) && !favoritesOnly)
                        : b == iptvTab ? "Guide TV".equals(activePage)
                        : b == recentTab ? ("Live TV".equals(activePage) && favoritesOnly)
                        : b == settingsTab ? "Paramètres".equals(activePage) : selected;
                b.setBackground(rounded(active ? 0xFF3B2027 : 0x001A1B20, 20));
                b.setTextColor(active ? Color.WHITE : 0xFFE6E6EB);
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
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF101115, 0xFF17171C, 0xFF111216}));
        setContentView(root);

        LinearLayout header = horizontal();
        int headerPad = isTelevision() ? 34 : 14;
        header.setPadding(dp(headerPad), dp(isTelevision() ? 10 : 8),
                dp(headerPad), dp(isTelevision() ? 10 : 8));
        header.setBackgroundColor(0xF218191E);
        headerView = header;
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout brand = horizontal();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView brandMark = label("N", isTelevision() ? 19 : 15, Color.WHITE, true);
        brandMark.setGravity(Gravity.CENTER);
        brandMark.setBackground(rounded(CYAN, 12));
        brand.addView(brandMark, lp(isTelevision() ? 38 : 30, isTelevision() ? 38 : 30));
        TextView brandName = label("NISSKENZI+", isTelevision() ? 19 : 14, Color.WHITE, true);
        LinearLayout.LayoutParams brandNameP = lp(-2, -2);
        brandNameP.leftMargin = dp(10);
        brand.addView(brandName, brandNameP);
        brand.setContentDescription("Accueil NISSKENZI+");
        brand.setFocusable(true);
        brand.setClickable(true);
        brand.setBackground(rounded(0x001A1B20, 12));
        brand.setOnFocusChangeListener((view, focused) ->
                brand.setBackground(outlined(focused ? 0xFF30252A : 0x001A1B20,
                        12, focused ? CYAN : 0x001A1B20)));
        header.addView(brand, lp(isTelevision() ? 178 : 132, isTelevision() ? 54 : 42));
        brand.setOnClickListener(v -> setPage("Accueil"));

        liveTab = buttonText("Pour vous", "Accueil".equals(activePage));
        vavooTab = buttonText("Chaînes", "Live TV".equals(activePage));
        iptvTab = buttonText("Guide TV", "Guide TV".equals(activePage));
        recentTab = buttonText("Favoris", "Live TV".equals(activePage) && favoritesOnly);

        LinearLayout.LayoutParams nav = lp(-2, isTelevision() ? 50 : 40);
        nav.leftMargin = dp(3);
        header.addView(liveTab, nav);
        LinearLayout.LayoutParams nav2 = lp(-2, isTelevision() ? 50 : 40);
        nav2.leftMargin = dp(2);
        header.addView(vavooTab, nav2);
        LinearLayout.LayoutParams nav3 = lp(-2, isTelevision() ? 50 : 40);
        nav3.leftMargin = dp(2);
        header.addView(iptvTab, nav3);
        LinearLayout.LayoutParams nav4 = lp(-2, isTelevision() ? 50 : 40);
        nav4.leftMargin = dp(2);
        header.addView(recentTab, nav4);

        liveTab.setOnClickListener(v -> setPage("Accueil"));
        vavooTab.setOnClickListener(v -> showChannelList(false, false));
        iptvTab.setOnClickListener(v -> setPage("Guide TV"));
        recentTab.setOnClickListener(v -> showChannelList(true, false));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(0xFF9A9AA3);
        searchInput.setTextSize(isTelevision() ? 15 : 11);
        searchInput.setHint("⌕  Rechercher");
        searchInput.setPadding(dp(16), 0, dp(16), 0);
        searchInput.setBackground(outlined(0xFF24252B, 22, 0xFF3A3B42));
        searchInput.setText(searchText);
        searchInput.setFocusable(true);
        searchInput.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(0, dp(isTelevision() ? 48 : 38), 1f);
        searchP.leftMargin = dp(isTelevision() ? 14 : 8);
        searchP.rightMargin = dp(isTelevision() ? 10 : 6);
        header.addView(searchInput, searchP);
        searchInput.setOnFocusChangeListener((view, focused) ->
                searchInput.setBackground(outlined(focused ? 0xFF30252A : 0xFF24252B,
                        22, focused ? CYAN : 0xFF3A3B42)));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchText = s == null ? "" : s.toString();
                if (("Accueil".equals(activePage) || "Guide TV".equals(activePage))
                        && !safe(searchText).isEmpty()) {
                    showChannelList(false, false);
                } else {
                    scheduleSearchFilter();
                }
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        settingsTab = buttonText("⚙", "Paramètres".equals(activePage));
        settingsTab.setContentDescription("Paramètres");
        header.addView(settingsTab, lp(isTelevision() ? 52 : 42, isTelevision() ? 48 : 38));
        settingsTab.setOnClickListener(v -> setPage("Paramètres"));

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));

        statusView = label(catalogStatus, isTelevision() ? 10 : 8, 0xFF9E9EA7, false);
        statusView.setMaxLines(1);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(dp(headerPad), dp(7), dp(headerPad), dp(7));
        statusView.setBackgroundColor(0xFF17181D);
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
        groupedOnly = false;
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
        setTabState(vavooTab, "Live TV".equals(activePage) && !favoritesOnly);
        setTabState(iptvTab, "Guide TV".equals(activePage));
        setTabState(recentTab, "Live TV".equals(activePage) && favoritesOnly);
        setTabState(settingsTab, "Paramètres".equals(activePage));
    }

    private void setTabState(TextView tab, boolean selected) {
        if (tab == null) return;
        tab.setBackground(rounded(selected ? 0xFF3B2027 : 0x001A1B20, 20));
        tab.setTextColor(selected ? Color.WHITE : 0xFFE6E6EB);
    }

    private void renderPage() {
        if (contentHost == null) return;
        filterGeneration.incrementAndGet();
        if (filterJob != null) {
            filterJob.cancel(true);
            filterJob = null;
            filterExecutor.purge();
        }
        if (fullscreenPlayback) {
            renderCountryChoices();
            updateChannelResults();
            return;
        }
        if (channelAdapter != null) {
            channelAdapter.shutdown();
            channelAdapter = null;
        }
        if (!"Accueil".equals(activePage) && !"Guide TV".equals(activePage)
                && homeLogoLoader != null) {
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
        guideProgramViews.clear();
        contentHost.removeAllViews();
        if ("Paramètres".equals(activePage)) renderSettings();
        else if ("Accueil".equals(activePage)) renderHomePage();
        else if ("Guide TV".equals(activePage)) renderGuidePage();
        else renderLivePage();
    }

    private void renderHomePage() {
        if (homeLogoLoader != null) homeLogoLoader.close();
        homeLogoLoader = new ChannelLogoLoader();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = vertical();
        int sidePad = isTelevision() ? 48 : 18;
        page.setPadding(dp(sidePad), dp(isTelevision() ? 24 : 16), dp(sidePad), dp(28));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(titleRow, lp(-1, -2));
        LinearLayout headingBlock = vertical();
        titleRow.addView(headingBlock, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView eyebrow = label("POUR VOUS", isTelevision() ? 11 : 9, CYAN, true);
        eyebrow.setLetterSpacing(0.13f);
        headingBlock.addView(eyebrow);
        TextView heading = label("Qu’est-ce qu’on regarde ?", isTelevision() ? 30 : 23, TEXT, true);
        LinearLayout.LayoutParams headingP = lp(-1, -2);
        headingP.topMargin = dp(4);
        headingBlock.addView(heading, headingP);
        TextView intro = label("Le direct, vos favoris et les chaînes du moment, réunis au même endroit.",
                isTelevision() ? 14 : 10, MUTED, false);
        LinearLayout.LayoutParams introP = lp(-1, -2);
        introP.topMargin = dp(4);
        introP.bottomMargin = dp(isTelevision() ? 18 : 12);
        headingBlock.addView(intro, introP);

        ArrayList<Channel> recent = recentStore == null
                ? new ArrayList<>() : channelsForSaved(recentStore.orderedUrls(), 1);
        ArrayList<Channel> favorites = favoritesStore == null
                ? new ArrayList<>() : channelsForSaved(new ArrayList<>(favoritesStore.snapshotUrls()), 1);
        Channel featured = !recent.isEmpty() ? recent.get(0)
                : (!favorites.isEmpty() ? favorites.get(0) : (channels.isEmpty() ? null : channels.get(0)));

        LinearLayout hero = horizontal();
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(isTelevision() ? 30 : 18), dp(isTelevision() ? 22 : 16),
                dp(isTelevision() ? 24 : 14), dp(isTelevision() ? 22 : 16));
        GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF342128, 0xFF242126, 0xFF1B1B20});
        heroBg.setCornerRadius(dp(22));
        hero.setBackground(heroBg);
        LinearLayout.LayoutParams heroP = lp(-1, isTelevision() ? 244 : 210);
        heroP.bottomMargin = dp(isTelevision() ? 22 : 16);
        page.addView(hero, heroP);

        LinearLayout heroInfo = vertical();
        heroInfo.setGravity(Gravity.CENTER_VERTICAL);
        heroInfo.setPadding(0, 0, dp(18), 0);
        hero.addView(heroInfo, new LinearLayout.LayoutParams(0, -1, 1.15f));
        TextView badge = label(featured == null ? "VOTRE ESPACE TV"
                : (!recent.isEmpty() ? "À REPRENDRE" : "SÉLECTION DU MOMENT"),
                isTelevision() ? 11 : 9, CYAN, true);
        badge.setLetterSpacing(0.08f);
        heroInfo.addView(badge);
        previewTitle = label(featured == null ? "Le direct vous attend" : featured.name,
                isTelevision() ? 32 : 23, TEXT, true);
        previewTitle.setMaxLines(2);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams heroTitleP = lp(-1, -2);
        heroTitleP.topMargin = dp(9);
        heroInfo.addView(previewTitle, heroTitleP);
        String where = featured == null ? "France · Maghreb · pays arabes · Maroc"
                : flag(featured.country, countryCode(featured.country)) + "  " + safeCountry(featured.country)
                    + "  ·  " + (featured.isVavoo ? "VAVOO" : "IPTV-ORG");
        previewStatus = label(where, isTelevision() ? 14 : 10, 0xFFE7DDE0, false);
        previewStatus.setMaxLines(1);
        previewStatus.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusP = lp(-1, -2);
        statusP.topMargin = dp(7);
        heroInfo.addView(previewStatus, statusP);
        previewDetail = label(featured == null
                ? "Les chaînes en double sont réunies et le meilleur flux est choisi automatiquement."
                : featured.sourceCount() + " flux disponibles · bascule automatique en cas de problème",
                isTelevision() ? 12 : 9, MUTED, false);
        previewDetail.setMaxLines(1);
        previewDetail.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailP = lp(-1, -2);
        detailP.topMargin = dp(5);
        heroInfo.addView(previewDetail, detailP);

        LinearLayout heroActions = horizontal();
        LinearLayout.LayoutParams actionsP = lp(-1, -2);
        actionsP.topMargin = dp(14);
        heroInfo.addView(heroActions, actionsP);
        TextView watch = buttonText(featured == null ? "Explorer" : "▶  Regarder",
                true);
        heroActions.addView(watch, lp(-2, isTelevision() ? 48 : 40));
        watch.setEnabled(featured != null || !channels.isEmpty());
        watch.setOnClickListener(v -> {
            if (featured != null) play(featured);
            else showChannelList(false, false);
        });
        TextView guide = buttonText("Voir le guide", false);
        LinearLayout.LayoutParams guideP = lp(-2, isTelevision() ? 48 : 40);
        guideP.leftMargin = dp(9);
        heroActions.addView(guide, guideP);
        guide.setOnClickListener(v -> setPage("Guide TV"));

        playerStage = new FrameLayout(this);
        playerStage.setFocusable(false);
        GradientDrawable stageBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF482A33, 0xFF242229, 0xFF191A1F});
        stageBg.setCornerRadius(dp(18));
        playerStage.setBackground(stageBg);
        LinearLayout.LayoutParams stageP = new LinearLayout.LayoutParams(0, -1, 0.78f);
        stageP.leftMargin = dp(8);
        hero.addView(playerStage, stageP);
        TextView stageMark = label(featured == null ? "N+" : initials(featured.name),
                isTelevision() ? 46 : 32, 0xFFFCE9EC, true);
        stageMark.setGravity(Gravity.CENTER);
        playerStage.addView(stageMark, new FrameLayout.LayoutParams(-1, -1));
        if (featured != null && !safe(featured.logo).isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(24), dp(25), dp(24), dp(25));
            image.setTag(safe(featured.logo));
            playerStage.addView(image, new FrameLayout.LayoutParams(-1, -1));
            bindHomeLogo(image, stageMark, featured.logo);
        }
        TextView liveBadge = label(featured == null ? "TV" : "●  DIRECT",
                isTelevision() ? 10 : 8, 0xFFFFEDF0, true);
        liveBadge.setGravity(Gravity.CENTER);
        liveBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        liveBadge.setBackground(rounded(0xCC17181D, 14));
        FrameLayout.LayoutParams liveP = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.END);
        liveP.setMargins(0, dp(12), dp(12), 0);
        playerStage.addView(liveBadge, liveP);

        addHomeRail(page, "Reprendre", recentChannels(12));
        addHomeRail(page, "Mes favoris", favoriteChannels(12));
        addHomeRail(page, "Sources réunies", groupedChannels(14));
        addHomeRail(page, "Pour vous", recommendedChannels(14));
        addHomeRail(page, "France", channelsForRegion("FRANCE", 14));
        addHomeRail(page, "Maroc", channelsForIptvMorocco(14));
        addHomeRail(page, "Maghreb", channelsForRegion("MAGHREB", 14));
        addHomeRail(page, "Pays arabes", channelsForRegion("ARAB", 14));
        addHomeRail(page, "Sport", channelsForGenre("Sport", 12));
        addHomeRail(page, "Films & séries", channelsForGenre("Films & séries", 12));
        addHomeRail(page, "Jeunesse", channelsForGenre("Jeunesse", 12));
        addHomeRail(page, "Toutes les chaînes", firstChannels(14));
        homePreviewIdentity = featured == null ? "" : featured.identity();
        if (featured != null && isGuideEligible(featured)) {
            EpgProvider.Result cachedGuide = guideResults.get(featured.identity());
            if (cachedGuide != null) applyHomeGuideResult(featured, cachedGuide);
            else requestGuidePrograms(Collections.singletonList(featured));
        }

        View firstFocus = featured == null ? guide : watch;
        firstFocus.post(() -> {
            if ("Accueil".equals(activePage) && firstFocus.isAttachedToWindow()) firstFocus.requestFocus();
        });
    }

    private void addHomeRail(LinearLayout page, String title, List<Channel> items) {
        if (items == null || items.isEmpty()) return;
        LinearLayout headingRow = horizontal();
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingP = lp(-1, -2);
        headingP.topMargin = dp(2);
        headingP.bottomMargin = dp(9);
        page.addView(headingRow, headingP);
        TextView heading = label(title, isTelevision() ? 20 : 15, TEXT, true);
        headingRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = label(String.valueOf(items.size()), isTelevision() ? 11 : 9, MUTED, true);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(9), dp(4), dp(9), dp(4));
        count.setBackground(rounded(0xFF28292F, 14));
        headingRow.addView(count);

        HorizontalScrollView horizontalScroll = new HorizontalScrollView(this);
        horizontalScroll.setHorizontalScrollBarEnabled(false);
        horizontalScroll.setClipToPadding(false);
        LinearLayout cards = horizontal();
        cards.setGravity(Gravity.TOP);
        horizontalScroll.addView(cards);
        LinearLayout.LayoutParams scrollP = lp(-1, isTelevision() ? 174 : 145);
        scrollP.bottomMargin = dp(isTelevision() ? 19 : 13);
        page.addView(horizontalScroll, scrollP);

        int limit = Math.min(items.size(), 14);
        for (int i = 0; i < limit; i++) {
            Channel channel = items.get(i);
            LinearLayout card = vertical();
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(11), dp(14), dp(10));
            card.setFocusable(true);
            card.setFocusableInTouchMode(false);
            card.setClickable(true);
            card.setBackground(outlined(0xFF202126, 15, 0x00202020));

            LinearLayout top = horizontal();
            top.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(top, lp(-1, -2));
            FrameLayout logoTile = new FrameLayout(this);
            logoTile.setBackground(rounded(0xFFF1EEF0, 12));
            int logoSize = isTelevision() ? 54 : 42;
            TextView mark = label(initials(channel.name), isTelevision() ? 16 : 12, 0xFF9D3445, true);
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
            top.addView(logoTile, lp(logoSize, logoSize));
            if (channel.sourceCount() > 1) {
                TextView folder = label("▤ " + channel.sourceCount(),
                        isTelevision() ? 8 : 7, Color.WHITE, true);
                folder.setGravity(Gravity.CENTER);
                folder.setPadding(dp(5), dp(3), dp(5), dp(3));
                folder.setBackground(rounded(CYAN, 10));
                folder.setContentDescription("Ouvrir le dossier des " + channel.sourceCount() + " flux");
                folder.setFocusable(true);
                folder.setClickable(true);
                FrameLayout.LayoutParams folderP = new FrameLayout.LayoutParams(-2, -2,
                        Gravity.TOP | Gravity.END);
                folderP.setMargins(0, dp(4), dp(4), 0);
                logoTile.addView(folder, folderP);
                folder.setOnClickListener(v -> showSourceChoices(channel));
            }
            TextView ordinal = label(String.format(Locale.ROOT, "%02d", i + 1),
                    isTelevision() ? 10 : 8, MUTED, true);
            ordinal.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ordinalP = new LinearLayout.LayoutParams(0, -2, 1f);
            ordinalP.leftMargin = dp(8);
            ordinal.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            top.addView(ordinal, ordinalP);

            TextView name = label(channel.name, isTelevision() ? 15 : 12, TEXT, true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameP = lp(-1, -2);
            nameP.topMargin = dp(8);
            card.addView(name, nameP);
            TextView country = label(flag(channel.country, countryCode(channel.country))
                    + "  " + safeCountry(channel.country),
                    isTelevision() ? 10 : 8, MUTED, false);
            country.setSingleLine(true);
            country.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams countryP = lp(-1, -2);
            countryP.topMargin = dp(3);
            card.addView(country, countryP);
            TextView provider = label(channel.sourceCount() > 1
                    ? "▤ DOSSIER · " + channel.sourceCount() + " flux"
                    : (channel.isVavoo ? "VAVOO" : "IPTV-ORG") + " recommandé",
                    isTelevision() ? 9 : 7, CYAN, true);
            provider.setSingleLine(true);
            provider.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sourceP = lp(-1, -2);
            sourceP.topMargin = dp(5);
            card.addView(provider, sourceP);

            LinearLayout.LayoutParams cardP = lp(isTelevision() ? 228 : 184,
                    isTelevision() ? 154 : 128);
            cardP.rightMargin = dp(11);
            cards.addView(card, cardP);
            card.setContentDescription(channel.name + ", " + safeCountry(channel.country)
                    + ", " + (channel.isVavoo ? "VAVOO" : "IPTV-ORG") + " recommandé"
                    + (channel.sourceCount() > 1 ? ", " + channel.sourceCount() + " flux au total" : ""));
            card.setOnFocusChangeListener((view, focused) -> {
                card.setBackground(outlined(focused ? 0xFF38232A : 0xFF202126, 15,
                        focused ? CYAN : 0x00202020));
                card.setScaleX(focused ? 1.045f : 1f);
                card.setScaleY(focused ? 1.045f : 1f);
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

    private ArrayList<Channel> groupedChannels(int limit) {
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel channel : channels) {
            if (channel.sourceCount() > 1) result.add(channel);
        }
        Collections.sort(result, (left, right) -> {
            int bySourceCount = Integer.compare(right.sourceCount(), left.sourceCount());
            return bySourceCount != 0 ? bySourceCount
                    : left.searchTitle().compareTo(right.searchTitle());
        });
        if (result.size() > limit) result.subList(limit, result.size()).clear();
        return result;
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
        left.setPadding(dp(isTelevision() ? 26 : 12), dp(isTelevision() ? 20 : 12),
                dp(isTelevision() ? 16 : 8), dp(12));
        body.addView(left, new LinearLayout.LayoutParams(dp(isTelevision() ? 254 : 142), -1));

        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        body.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));

        LinearLayout right = vertical();
        right.setPadding(dp(isTelevision() ? 26 : 13), dp(isTelevision() ? 18 : 10),
                dp(isTelevision() ? 36 : 15), dp(12));
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView categoryTitle = label("PARCOURIR", isTelevision() ? 11 : 8, MUTED, true);
        categoryTitle.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams categoryTitleP = lp(-1, -2);
        categoryTitleP.bottomMargin = dp(9);
        left.addView(categoryTitle, categoryTitleP);

        categorySearchInput = new EditText(this);
        categorySearchInput.setSingleLine(true);
        categorySearchInput.setTextSize(isTelevision() ? 13 : 9);
        categorySearchInput.setTextColor(Color.WHITE);
        categorySearchInput.setHintTextColor(0xFF9999A2);
        categorySearchInput.setHint("Pays, genre…");
        categorySearchInput.setPadding(dp(12), 0, dp(10), 0);
        categorySearchInput.setBackground(outlined(0xFF23242A, 13, 0xFF383941));
        categorySearchInput.setText(categorySearch);
        left.addView(categorySearchInput, lp(-1, isTelevision() ? 44 : 36));
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
        countryChoices.setPadding(0, dp(9), 0, dp(9));
        categoryScroll.addView(countryChoices);
        LinearLayout.LayoutParams categoryScrollP = new LinearLayout.LayoutParams(-1, 0, 1f);
        categoryScrollP.topMargin = dp(5);
        left.addView(categoryScroll, categoryScrollP);

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingP = lp(-1, -2);
        headingP.bottomMargin = dp(9);
        right.addView(heading, headingP);
        LinearLayout headingInfo = vertical();
        heading.addView(headingInfo, new LinearLayout.LayoutParams(0, -2, 1f));
        String title = favoritesOnly ? "Mes favoris" : recentOnly ? "Récemment regardés"
                : groupedOnly ? "Sources réunies"
                : (!"Tous les pays".equals(activeCountry) ? activeCountry
                : (!"Tous les genres".equals(activeGenre) ? activeGenre : "Chaînes"));
        TextView pageTitle = label(title, isTelevision() ? 27 : 19, TEXT, true);
        headingInfo.addView(pageTitle);
        TextView subtitle = label("Un seul résultat par chaîne · meilleure source sélectionnée automatiquement",
                isTelevision() ? 11 : 8, MUTED, false);
        LinearLayout.LayoutParams subtitleP = lp(-1, -2);
        subtitleP.topMargin = dp(3);
        headingInfo.addView(subtitle, subtitleP);
        countView = label(channels.size() + " chaînes", isTelevision() ? 12 : 9, 0xFFE7E7EB, true);
        countView.setGravity(Gravity.CENTER);
        countView.setPadding(dp(12), dp(7), dp(12), dp(7));
        countView.setBackground(rounded(0xFF292A30, 16));
        heading.addView(countView);

        LinearLayout preview = horizontal();
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(isTelevision() ? 16 : 10), dp(isTelevision() ? 11 : 8),
                dp(isTelevision() ? 16 : 10), dp(isTelevision() ? 11 : 8));
        preview.setBackground(outlined(0xFF1D1E23, 16, 0x22FFFFFF));
        right.addView(preview, lp(-1, isTelevision() ? 128 : 92));

        LinearLayout previewInfo = vertical();
        previewInfo.setGravity(Gravity.CENTER_VERTICAL);
        preview.addView(previewInfo, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView liveLabel = label(currentChannel == null ? "À L’ÉCRAN" : "●  EN DIRECT",
                isTelevision() ? 9 : 7, CYAN, true);
        liveLabel.setLetterSpacing(0.07f);
        previewInfo.addView(liveLabel);
        previewTitle = label(currentChannel == null ? "Choisissez une chaîne"
                : safe(currentChannel.name), isTelevision() ? 17 : 12, TEXT, true);
        previewTitle.setMaxLines(1);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams previewTitleP = lp(-1, -2);
        previewTitleP.topMargin = dp(4);
        previewInfo.addView(previewTitle, previewTitleP);
        previewStatus = label(currentChannel == null ? "Appuyez sur OK pour lancer le direct"
                : (currentChannel.isVavoo ? "VAVOO" : "IPTV-ORG") + " recommandé"
                        + (currentChannel.sourceCount() > 1 ? " · " + currentChannel.sourceCount() + " flux" : ""),
                isTelevision() ? 10 : 8, 0xFFD0D0D7, false);
        LinearLayout.LayoutParams previewStatusP = lp(-1, -2);
        previewStatusP.topMargin = dp(3);
        previewInfo.addView(previewStatus, previewStatusP);
        previewDetail = label(currentChannel == null
                ? "Le lecteur bascule seul sur un flux de secours si besoin."
                : safeCountry(currentChannel.country),
                isTelevision() ? 9 : 7, MUTED, false);
        LinearLayout.LayoutParams previewDetailP = lp(-1, -2);
        previewDetailP.topMargin = dp(2);
        previewInfo.addView(previewDetail, previewDetailP);

        playerStage = new FrameLayout(this);
        playerStage.setBackground(rounded(0xFF101115, 12));
        playerStage.setFocusable(false);
        LinearLayout.LayoutParams stageP = lp(isTelevision() ? 206 : 126,
                isTelevision() ? 106 : 72);
        stageP.leftMargin = dp(isTelevision() ? 14 : 8);
        preview.addView(playerStage, stageP);
        TextView placeholder = label(currentChannel == null ? "NISSKENZI+"
                : "▶  " + initials(currentChannel.name),
                isTelevision() ? 17 : 11, 0xFFECECEF, true);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setBackground(rounded(0xFF16171C, 10));
        playerStage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout actions = horizontal();
        LinearLayout.LayoutParams actionsP = lp(-1, isTelevision() ? 42 : 34);
        actionsP.topMargin = dp(7);
        actionsP.bottomMargin = dp(7);
        right.addView(actions, actionsP);
        TextView favorite = buttonText(currentChannel != null && isFavorite(currentChannel)
                ? "★  Favori" : "♡  Ajouter aux favoris",
                currentChannel != null && isFavorite(currentChannel));
        actions.addView(favorite, lp(-2, -1));
        TextView stop = buttonText("■  Arrêter", false);
        LinearLayout.LayoutParams stopP = lp(-2, -1);
        stopP.leftMargin = dp(7);
        actions.addView(stop, stopP);
        favorite.setOnClickListener(v -> {
            if (currentChannel == null) {
                Toast.makeText(this, "Sélectionne d’abord une chaîne.", Toast.LENGTH_SHORT).show();
                return;
            }
            toggleFavorite(currentChannel);
            favorite.setText(isFavorite(currentChannel) ? "★  Favori" : "♡  Ajouter aux favoris");
            favorite.setTextColor(isFavorite(currentChannel) ? CYAN : Color.WHITE);
            renderCountryChoices();
            updateChannelResults();
            if (channelAdapter != null) channelAdapter.refreshDecorations();
        });
        sourceOptionsButton = buttonText("▤ Dossier", false);
        sourceOptionsButton.setEnabled(currentChannel != null && currentChannel.sourceCount() > 1);
        sourceOptionsButton.setAlpha(sourceOptionsButton.isEnabled() ? 1f : 0.55f);
        LinearLayout.LayoutParams sourceButtonParams = lp(-2, -1);
        sourceButtonParams.leftMargin = dp(7);
        actions.addView(sourceOptionsButton, sourceButtonParams);
        sourceOptionsButton.setOnClickListener(v -> showSourceChoices(currentChannel));
        stop.setOnClickListener(v -> stopPreview());

        TextView listLabel = label("TOUTES LES CHAÎNES", isTelevision() ? 10 : 8, MUTED, true);
        listLabel.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams listLabelP = lp(-1, -2);
        listLabelP.topMargin = dp(3);
        listLabelP.bottomMargin = dp(6);
        right.addView(listLabel, listLabelP);

        channelListHost = new FrameLayout(this);
        channelResults = new RecyclerView(this);
        channelResults.setLayoutManager(new LinearLayoutManager(this));
        channelResults.setHasFixedSize(true);
        channelResults.setItemViewCacheSize(12);
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

        renderCountryChoices();
        updateChannelResults();
    }

    private void renderCountryChoices() {
        if (countryChoices == null) return;
        countryChoices.removeAllViews();

        addCategoryRow("Tous les pays", channels.size(),
                !favoritesOnly && !recentOnly && !groupedOnly && "Tous les pays".equals(activeCountry),
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = false;
                    recentOnly = false;
                    groupedOnly = false;
                    activeGenre = "Tous les genres";
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Sources réunies", countGroupedChannels(), groupedOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    activeGenre = "Tous les genres";
                    favoritesOnly = false;
                    recentOnly = false;
                    groupedOnly = true;
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Récemment vus", countRecentChannels(), recentOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    activeGenre = "Tous les genres";
                    favoritesOnly = false;
                    recentOnly = true;
                    groupedOnly = false;
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Favoris", countFavoriteChannels(), favoritesOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    activeGenre = "Tous les genres";
                    favoritesOnly = true;
                    recentOnly = false;
                    groupedOnly = false;
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
                    country.equals(activeCountry) && !favoritesOnly && !recentOnly && !groupedOnly,
                    v -> {
                        activeCountry = country;
                        favoritesOnly = false;
                        recentOnly = false;
                        groupedOnly = false;
                        renderCountryChoices();
                        updateChannelResults();
                    });
        }

        addCategorySection("GENRES");
        addCategoryRow("Tous les genres", channels.size(),
                "Tous les genres".equals(activeGenre) && !groupedOnly,
                v -> {
                    activeGenre = "Tous les genres";
                    groupedOnly = false;
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
                        groupedOnly = false;
                        renderCountryChoices();
                        updateChannelResults();
                    });
        }
    }

    private void addCategorySection(String title) {
        TextView heading = label(title, isTelevision() ? 10 : 8, 0xFF9798A1, true);
        heading.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.topMargin = dp(12);
        params.bottomMargin = dp(6);
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
        row.setPadding(dp(10), dp(7), dp(8), dp(7));
        row.setBackground(rounded(selected ? 0xFF3B2027 : 0xFF222329, 11));
        row.setFocusable(true);
        row.setClickable(true);
        TextView name = label(title, isTelevision() ? 13 : 9,
                selected ? Color.WHITE : 0xFFE2E2E6, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView number = label(String.valueOf(count), isTelevision() ? 11 : 8,
                selected ? 0xFFFFA1AD : MUTED, true);
        number.setGravity(Gravity.CENTER);
        row.addView(number, lp(27, -2));
        LinearLayout.LayoutParams rowP = lp(-1, isTelevision() ? 42 : 34);
        rowP.bottomMargin = dp(5);
        countryChoices.addView(row, rowP);
        row.setOnFocusChangeListener((view, focused) -> {
            row.setBackground(outlined(focused || selected
                    ? (focused ? 0xFF3B2027 : 0xFF332126) : 0xFF222329,
                    11, focused || selected ? CYAN : 0x00202020));
            row.setScaleX(focused ? 1.035f : 1f);
            row.setScaleY(focused ? 1.035f : 1f);
            name.setTextColor(focused || selected ? Color.WHITE : 0xFFE2E2E6);
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

    private int countGroupedChannels() {
        int count = 0;
        for (Channel channel : channels) if (channel.sourceCount() > 1) count++;
        return count;
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
        final boolean requestedGrouped = groupedOnly;
        final String filterKey = requestedSource + "|" + requestedCountry + "|" + requestedGenre
                + "|" + safe(requestedSearch) + "|" + requestedFavorites + "|" + requestedRecent
                + "|" + requestedGrouped;
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
            if (requestedGrouped) {
                ArrayList<Channel> grouped = new ArrayList<>();
                for (Channel channel : filtered) if (channel.sourceCount() > 1) grouped.add(channel);
                filtered = grouped;
            }
            final ArrayList<Channel> resultList = filtered;

            runOnUiThread(() -> {
                if (activityDestroyed || requestId != filterGeneration.get()
                        || targetRecycler != channelResults || targetAdapter != channelAdapter
                        || !requestedSource.equals(sourceFilter)
                        || (!"Live TV".equals(activePage) && !"Accueil".equals(activePage))) return;

                if (countView != null) countView.setText(resultList.size() + " chaînes");
                if (resultList.isEmpty()) {
                    targetAdapter.submitList(Collections.emptyList());
                    targetRecycler.setVisibility(View.GONE);
                    channelEmptyView.setVisibility(View.VISIBLE);
                    channelEmptyView.removeAllViews();

                    LinearLayout empty = vertical();
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(dp(14), dp(22), dp(14), dp(22));
                    empty.setBackground(rounded(0xFF222329, 12));

                    TextView heading = label(channelSnapshot.isEmpty()
                            ? (loadingCatalog ? "Connexion au catalogue…" : "Catalogue prêt à charger")
                            : (requestedGrouped ? "Aucun dossier de sources" : "Aucune chaîne trouvée"), isTelevision() ? 18 : 14, Color.WHITE, true);
                    heading.setGravity(Gravity.CENTER);
                    empty.addView(heading, lp(-1, -2));

                    TextView msg = label(channelSnapshot.isEmpty()
                            ? "Le catalogue se charge en arrière-plan. Tu peux parcourir les autres rubriques pendant ce temps."
                            : (requestedGrouped
                                ? "Aucune chaîne avec plusieurs flux pour les filtres actuels."
                                : "Modifie le pays, le genre ou la recherche pour élargir le résultat."),
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
                targetAdapter.submitList(resultList, () -> {
                    if (requestId != filterGeneration.get()
                            || targetRecycler != channelResults || targetAdapter != channelAdapter) return;
                    if (filtersChanged && targetAdapter.getItemCount() > 0) {
                        targetRecycler.scrollToPosition(0);
                    }
                });
            });
        });
    }

    private void renderGuidePage() {
        if (homeLogoLoader != null) homeLogoLoader.close();
        homeLogoLoader = new ChannelLogoLoader();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = vertical();
        int side = isTelevision() ? 46 : 16;
        page.setPadding(dp(side), dp(isTelevision() ? 22 : 14), dp(side), dp(26));
        scroll.addView(page);
        contentHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(heading, lp(-1, -2));
        LinearLayout headingText = vertical();
        heading.addView(headingText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView eyebrow = label("LE DIRECT", isTelevision() ? 10 : 8, CYAN, true);
        eyebrow.setLetterSpacing(0.12f);
        headingText.addView(eyebrow);
        TextView title = label("Guide TV", isTelevision() ? 30 : 22, TEXT, true);
        LinearLayout.LayoutParams titleP = lp(-1, -2);
        titleP.topMargin = dp(3);
        headingText.addView(title, titleP);
        TextView subtitle = label("Ce qui passe maintenant, puis le prochain programme.",
                isTelevision() ? 13 : 9, MUTED, false);
        LinearLayout.LayoutParams subtitleP = lp(-1, -2);
        subtitleP.topMargin = dp(4);
        subtitleP.bottomMargin = dp(isTelevision() ? 15 : 10);
        headingText.addView(subtitle, subtitleP);
        TextView channelAction = buttonText("Toutes les chaînes", false);
        heading.addView(channelAction, lp(-2, isTelevision() ? 46 : 38));
        channelAction.setOnClickListener(v -> showChannelList(false, false));

        ArrayList<Channel> allGuideRows = guideChannels("Tous");
        ArrayList<Channel> rows = guideChannels(activeGuideCountry);
        LinearLayout filterRow = horizontal();
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams filterP = lp(-1, -2);
        filterP.bottomMargin = dp(isTelevision() ? 14 : 9);
        page.addView(filterRow, filterP);
        addGuideFilter(filterRow, "Toutes", "Tous", allGuideRows.size());
        addGuideFilter(filterRow, "France", "France", guideCountryCount(allGuideRows, "FR"));
        addGuideFilter(filterRow, "Maroc", "Maroc", guideCountryCount(allGuideRows, "MA"));

        LinearLayout focusPanel = horizontal();
        focusPanel.setGravity(Gravity.CENTER_VERTICAL);
        focusPanel.setPadding(dp(isTelevision() ? 16 : 10), dp(isTelevision() ? 10 : 7),
                dp(isTelevision() ? 16 : 10), dp(isTelevision() ? 10 : 7));
        focusPanel.setBackground(outlined(0xFF202126, 16, 0x22FFFFFF));
        LinearLayout.LayoutParams focusP = lp(-1, isTelevision() ? 118 : 90);
        focusP.bottomMargin = dp(isTelevision() ? 18 : 12);
        page.addView(focusPanel, focusP);

        LinearLayout focusInfo = vertical();
        focusInfo.setGravity(Gravity.CENTER_VERTICAL);
        focusPanel.addView(focusInfo, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView focusBadge = label("LE MEILLEUR DU DIRECT", isTelevision() ? 9 : 7, CYAN, true);
        focusBadge.setLetterSpacing(0.07f);
        focusInfo.addView(focusBadge);
        previewTitle = label(currentChannel == null ? "Choisissez une chaîne"
                : safe(currentChannel.name), isTelevision() ? 17 : 12, TEXT, true);
        previewTitle.setMaxLines(1);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams focusTitleP = lp(-1, -2);
        focusTitleP.topMargin = dp(4);
        focusInfo.addView(previewTitle, focusTitleP);
        previewStatus = label(currentChannel == null
                ? "Sélectionnez une chaîne pour lancer la lecture"
                : (currentChannel.isVavoo ? "VAVOO" : "IPTV-ORG") + " · " + currentChannel.sourceCount() + " flux",
                isTelevision() ? 10 : 8, 0xFFD3D3D9, false);
        LinearLayout.LayoutParams focusStatusP = lp(-1, -2);
        focusStatusP.topMargin = dp(3);
        focusInfo.addView(previewStatus, focusStatusP);
        previewDetail = label("France et Maroc · le programme se charge en arrière-plan",
                isTelevision() ? 9 : 7, MUTED, false);
        LinearLayout.LayoutParams focusDetailP = lp(-1, -2);
        focusDetailP.topMargin = dp(2);
        focusInfo.addView(previewDetail, focusDetailP);
        playerStage = new FrameLayout(this);
        playerStage.setFocusable(false);
        playerStage.setBackground(rounded(0xFF111216, 12));
        LinearLayout.LayoutParams stageP = lp(isTelevision() ? 210 : 126,
                isTelevision() ? 94 : 66);
        stageP.leftMargin = dp(12);
        focusPanel.addView(playerStage, stageP);
        TextView stageMark = label(currentChannel == null ? "NISSKENZI+"
                : "▶  " + initials(currentChannel.name),
                isTelevision() ? 16 : 10, 0xFFF0E9EA, true);
        stageMark.setGravity(Gravity.CENTER);
        stageMark.setBackground(rounded(0xFF17181D, 10));
        playerStage.addView(stageMark, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout columnHeader = horizontal();
        columnHeader.setGravity(Gravity.CENTER_VERTICAL);
        columnHeader.setPadding(dp(12), dp(4), dp(12), dp(6));
        page.addView(columnHeader, lp(-1, -2));
        TextView channelCol = label("CHAÎNE", isTelevision() ? 9 : 7, MUTED, true);
        columnHeader.addView(channelCol, new LinearLayout.LayoutParams(0, -2, 2.05f));
        TextView nowCol = label("EN CE MOMENT", isTelevision() ? 9 : 7, MUTED, true);
        columnHeader.addView(nowCol, new LinearLayout.LayoutParams(0, -2, 2.5f));
        TextView nextCol = label("ENSUITE", isTelevision() ? 9 : 7, MUTED, true);
        columnHeader.addView(nextCol, new LinearLayout.LayoutParams(0, -2, 2.1f));

        if (rows.isEmpty()) {
            LinearLayout empty = vertical();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(24), dp(18), dp(24));
            empty.setBackground(rounded(0xFF202126, 16));
            TextView emptyTitle = label(channels.isEmpty() ? "Le guide arrive" : "Aucun programme à afficher",
                    isTelevision() ? 19 : 14, TEXT, true);
            emptyTitle.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle, lp(-1, -2));
            TextView emptyText = label(channels.isEmpty()
                    ? "Les chaînes apparaîtront ici dès que le catalogue sera prêt."
                    : "Ajoutez des favoris ou choisissez une autre rubrique.",
                    isTelevision() ? 12 : 9, MUTED, false);
            emptyText.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams emptyP = lp(-1, -2);
            emptyP.topMargin = dp(7);
            empty.addView(emptyText, emptyP);
            page.addView(empty, lp(-1, isTelevision() ? 115 : 90));
            return;
        }

        ArrayList<Channel> toLoad = new ArrayList<>();
        int limit = Math.min(rows.size(), 12);
        for (int i = 0; i < limit; i++) {
            Channel channel = rows.get(i);
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(outlined(0xFF202126, 13, 0x00202020));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout.LayoutParams rowP = lp(-1, isTelevision() ? 82 : 62);
            rowP.bottomMargin = dp(5);
            page.addView(row, rowP);

            LinearLayout channelInfo = horizontal();
            channelInfo.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(channelInfo, new LinearLayout.LayoutParams(0, -1, 2.05f));
            FrameLayout logoTile = new FrameLayout(this);
            logoTile.setBackground(rounded(0xFFF1EEF0, 10));
            TextView mark = label(initials(channel.name), isTelevision() ? 13 : 10, 0xFF9D3445, true);
            mark.setGravity(Gravity.CENTER);
            logoTile.addView(mark, new FrameLayout.LayoutParams(-1, -1));
            if (!safe(channel.logo).isEmpty()) {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setPadding(dp(4), dp(4), dp(4), dp(4));
                image.setTag(safe(channel.logo));
                logoTile.addView(image, new FrameLayout.LayoutParams(-1, -1));
                bindHomeLogo(image, mark, channel.logo);
            }
            channelInfo.addView(logoTile, lp(isTelevision() ? 52 : 38, isTelevision() ? 52 : 38));
            LinearLayout channelNames = vertical();
            channelNames.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams namesP = new LinearLayout.LayoutParams(0, -2, 1f);
            namesP.leftMargin = dp(10);
            channelInfo.addView(channelNames, namesP);
            TextView name = label(channel.name, isTelevision() ? 14 : 10, TEXT, true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            channelNames.addView(name);
            TextView country = label(flag(channel.country, countryCode(channel.country))
                    + "  " + safeCountry(channel.country), isTelevision() ? 9 : 7, MUTED, false);
            country.setSingleLine(true);
            country.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams countryP = lp(-1, -2);
            countryP.topMargin = dp(2);
            channelNames.addView(country, countryP);
            if (channel.sourceCount() > 1) {
                TextView dossier = label("▤  " + channel.sourceCount() + " flux",
                        isTelevision() ? 8 : 6, 0xFFFFA1AD, true);
                dossier.setFocusable(true);
                dossier.setClickable(true);
                dossier.setGravity(Gravity.CENTER_VERTICAL);
                dossier.setPadding(dp(7), dp(2), dp(7), dp(2));
                dossier.setBackground(rounded(0xFF38232A, 9));
                dossier.setContentDescription("Ouvrir le mini dossier des flux de " + channel.name);
                dossier.setOnFocusChangeListener((view, focused) ->
                        dossier.setBackground(outlined(focused ? 0xFF542A34 : 0xFF38232A,
                                9, focused ? CYAN : 0x003B2027)));
                dossier.setOnClickListener(v -> showSourceChoices(channel));
                LinearLayout.LayoutParams dossierP = lp(-1, -2);
                dossierP.topMargin = dp(2);
                channelNames.addView(dossier, dossierP);
            }

            TextView now = label("Chargement du programme…",
                    isTelevision() ? 12 : 9, 0xFFECECEF, true);
            now.setMaxLines(2);
            now.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(now, new LinearLayout.LayoutParams(0, -2, 2.5f));
            TextView next = label("Ensuite",
                    isTelevision() ? 11 : 8, 0xFFB7B7C0, false);
            next.setMaxLines(2);
            next.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nextP = new LinearLayout.LayoutParams(0, -2, 2.1f);
            nextP.leftMargin = dp(9);
            row.addView(next, nextP);

            guideProgramViews.put(channel.identity(), new TextView[]{now, next});
            EpgProvider.Result cached = guideResults.get(channel.identity());
            if (cached != null) applyGuideResult(channel.identity(), cached);
            else if (guidePending.add(channel.identity())) toLoad.add(channel);

            row.setContentDescription(channel.name + ", " + safeCountry(channel.country)
                    + ", appuyez pour regarder, maintenir pour choisir un flux");
            row.setOnFocusChangeListener((view, focused) -> {
                row.setBackground(outlined(focused ? 0xFF3A2228 : 0xFF202126, 13,
                        focused ? CYAN : 0x00202020));
                row.setScaleX(focused ? 1.02f : 1f);
                row.setScaleY(focused ? 1.02f : 1f);
            });
            row.setOnClickListener(v -> play(channel));
            row.setOnLongClickListener(v -> {
                if (channel.sourceCount() < 2) return false;
                showSourceChoices(channel);
                return true;
            });
        }
        if (!toLoad.isEmpty()) requestGuidePrograms(toLoad);
    }

    private void addGuideFilter(LinearLayout parent, String title, String key, int count) {
        boolean selected = key.equals(activeGuideCountry);
        TextView chip = label(title + "  " + count, isTelevision() ? 12 : 9,
                selected ? Color.WHITE : 0xFFD6D6DC, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(isTelevision() ? 15 : 11), dp(isTelevision() ? 9 : 7),
                dp(isTelevision() ? 15 : 11), dp(isTelevision() ? 9 : 7));
        chip.setBackground(rounded(selected ? 0xFF3B2027 : 0xFF24252B, 18));
        chip.setFocusable(true);
        chip.setClickable(true);
        LinearLayout.LayoutParams chipP = lp(-2, isTelevision() ? 38 : 32);
        chipP.rightMargin = dp(7);
        parent.addView(chip, chipP);
        chip.setOnFocusChangeListener((view, focused) -> {
            chip.setBackground(outlined(focused || selected
                    ? (focused ? 0xFF3B2027 : 0xFF332126) : 0xFF24252B,
                    18, focused || selected ? CYAN : 0x00202020));
            chip.setScaleX(focused ? 1.04f : 1f);
            chip.setScaleY(focused ? 1.04f : 1f);
        });
        chip.setOnClickListener(v -> {
            if (key.equals(activeGuideCountry)) return;
            activeGuideCountry = key;
            renderPage();
        });
    }

    private int guideCountryCount(List<Channel> rows, String code) {
        int count = 0;
        Set<String> seen = new HashSet<>();
        for (Channel channel : channels) {
            if (code.equals(ChannelCatalogPolicy.countryCode(channel.country))
                    && seen.add(channel.identity())) count++;
        }
        return count;
    }

    private boolean isGuideEligible(Channel channel) {
        String code = channel == null ? "" : ChannelCatalogPolicy.countryCode(channel.country);
        return "FR".equals(code) || "MA".equals(code);
    }

    private void applyHomeGuideResult(Channel channel, EpgProvider.Result result) {
        if (channel == null || result == null || !TextUtils.equals(
                homePreviewIdentity, channel.identity()) || previewStatus == null
                || previewDetail == null || !"Accueil".equals(activePage)
                || fullscreenPlayback || player != null) return;
        String programme = result.now.isEmpty() ? "Programme en cours indisponible" : result.now;
        previewStatus.setText("●  EN CE MOMENT  ·  " + programme);
        String next = result.next.isEmpty() ? "Aucun programme suivant renseigné" : result.next;
        previewDetail.setText("ENSUITE  ·  " + next + "  ·  "
                + channel.sourceCount() + " flux · "
                + (channel.isVavoo ? "VAVOO" : "IPTV-ORG") + " recommandé");
    }

    private ArrayList<Channel> guideChannels(String countryFilter) {
        ArrayList<Channel> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (favoritesStore != null) {
            appendGuideChannels(channelsForSaved(new ArrayList<>(favoritesStore.snapshotUrls()), 30),
                    ordered, seen);
        }
        if (recentStore != null) {
            appendGuideChannels(channelsForSaved(recentStore.orderedUrls(), 30), ordered, seen);
        }
        appendGuideChannels(channels, ordered, seen);
        ArrayList<Channel> result = new ArrayList<>();
        for (Channel channel : ordered) {
            String code = ChannelCatalogPolicy.countryCode(channel.country);
            if (!"FR".equals(code) && !"MA".equals(code)) continue;
            if ("France".equals(countryFilter) && !"FR".equals(code)) continue;
            if ("Maroc".equals(countryFilter) && !"MA".equals(code)) continue;
            result.add(channel);
            if (result.size() >= 12) break;
        }
        return result;
    }

    private void appendGuideChannels(List<Channel> source, List<Channel> result, Set<String> seen) {
        if (source == null) return;
        for (Channel channel : source) {
            if (channel == null || !seen.add(channel.identity())) continue;
            String code = ChannelCatalogPolicy.countryCode(channel.country);
            if ("FR".equals(code) || "MA".equals(code)) result.add(channel);
        }
    }

    private void requestGuidePrograms(List<Channel> requested) {
        ArrayList<Channel> copy = new ArrayList<>(requested);
        io.execute(() -> {
            for (Channel channel : copy) {
                EpgProvider.Result result = EpgProvider.lookup(MainActivity.this, channel);
                runOnUiThread(() -> {
                    guidePending.remove(channel.identity());
                    if (activityDestroyed) return;
                    guideResults.put(channel.identity(), result);
                    applyGuideResult(channel.identity(), result);
                    applyHomeGuideResult(channel, result);
                });
            }
        });
    }

    private void applyGuideResult(String identity, EpgProvider.Result result) {
        TextView[] views = guideProgramViews.get(identity);
        if (views == null || result == null) return;
        views[0].setText(result.now.isEmpty()
                ? (result.status.isEmpty() ? "Programme non disponible" : result.status)
                : result.now);
        views[1].setText(result.next.isEmpty() ? "Aucun programme ensuite" : result.next);
    }

    private void renderSettings() {
        FrameLayout frame = new FrameLayout(this);
        contentHost.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams scrollP = new FrameLayout.LayoutParams(
                isTelevision() ? dp(1080) : -1, -1, Gravity.CENTER);
        frame.addView(scroll, scrollP);
        LinearLayout page = vertical();
        page.setPadding(dp(isTelevision() ? 20 : 16), dp(isTelevision() ? 22 : 14),
                dp(isTelevision() ? 20 : 16), dp(24));
        scroll.addView(page);

        page.addView(label("Paramètres", isTelevision() ? 29 : 22, TEXT, true));
        TextView intro = label("Une expérience TV simple, avec une seule lecture et des flux de secours automatiques.",
                isTelevision() ? 13 : 9, MUTED, false);
        LinearLayout.LayoutParams introP = lp(-1, -2);
        introP.topMargin = dp(5);
        introP.bottomMargin = dp(isTelevision() ? 18 : 12);
        page.addView(intro, introP);

        LinearLayout playerCard = horizontal();
        playerCard.setGravity(Gravity.CENTER_VERTICAL);
        playerCard.setPadding(dp(16), dp(isTelevision() ? 14 : 11),
                dp(16), dp(isTelevision() ? 14 : 11));
        playerCard.setBackground(outlined(0xFF222329, 15, 0x22FFFFFF));
        page.addView(playerCard, lp(-1, -2));
        TextView playerMark = label("▶", isTelevision() ? 20 : 15, Color.WHITE, true);
        playerMark.setGravity(Gravity.CENTER);
        playerMark.setBackground(rounded(CYAN, 12));
        playerCard.addView(playerMark, lp(isTelevision() ? 44 : 36, isTelevision() ? 44 : 36));
        LinearLayout playerInfo = vertical();
        LinearLayout.LayoutParams playerInfoP = new LinearLayout.LayoutParams(0, -2, 1f);
        playerInfoP.leftMargin = dp(12);
        playerCard.addView(playerInfo, playerInfoP);
        playerInfo.addView(label("LECTEUR NATIF", isTelevision() ? 12 : 9, TEXT, true));
        TextView playerHelp = label("Media3 · lecture plein écran · commandes au pavé directionnel",
                isTelevision() ? 10 : 8, MUTED, false);
        LinearLayout.LayoutParams playerHelpP = lp(-1, -2);
        playerHelpP.topMargin = dp(4);
        playerInfo.addView(playerHelp, playerHelpP);
        TextView playerState = label("ACTIF", isTelevision() ? 9 : 7, GREEN, true);
        playerState.setPadding(dp(9), dp(5), dp(9), dp(5));
        playerState.setBackground(rounded(0xFF203229, 12));
        playerCard.addView(playerState);

        TextView sourceHeading = label("VOS SOURCES", isTelevision() ? 10 : 8, MUTED, true);
        sourceHeading.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams sourceHeadingP = lp(-1, -2);
        sourceHeadingP.topMargin = dp(isTelevision() ? 21 : 15);
        sourceHeadingP.bottomMargin = dp(8);
        page.addView(sourceHeading, sourceHeadingP);
        if (isTelevision()) {
            LinearLayout sources = horizontal();
            page.addView(sources, lp(-1, -2));
            LinearLayout.LayoutParams cardP = new LinearLayout.LayoutParams(0, -2, 1f);
            cardP.rightMargin = dp(7);
            sources.addView(sourceCard("VAVOO", "V", "France · Maghreb · pays arabes",
                    "Chaînes regroupées · meilleur flux recommandé.", CYAN), cardP);
            LinearLayout.LayoutParams cardP2 = new LinearLayout.LayoutParams(0, -2, 1f);
            cardP2.leftMargin = dp(7);
            sources.addView(sourceCard("IPTV-ORG", "◉", "Maroc uniquement",
                    "Catalogue public filtré sur les chaînes marocaines.", PURPLE), cardP2);
        } else {
            page.addView(sourceCard("VAVOO", "V", "France · Maghreb · pays arabes",
                    "Chaînes regroupées · meilleur flux recommandé.", CYAN));
            LinearLayout.LayoutParams cardP = lp(-1, -2);
            cardP.topMargin = dp(9);
            page.addView(sourceCard("IPTV-ORG", "◉", "Maroc uniquement",
                    "Catalogue public filtré sur les chaînes marocaines.", PURPLE), cardP);
        }

        LinearLayout statusCard = vertical();
        statusCard.setPadding(dp(16), dp(13), dp(16), dp(13));
        statusCard.setBackground(outlined(0xFF222329, 15, 0x22FFFFFF));
        LinearLayout.LayoutParams statusP = lp(-1, -2);
        statusP.topMargin = dp(isTelevision() ? 14 : 10);
        page.addView(statusCard, statusP);
        LinearLayout statusHeader = horizontal();
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.addView(statusHeader);
        statusHeader.addView(label("ÉTAT DU CATALOGUE", isTelevision() ? 10 : 8, MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView statusDot = label(loadingCatalog ? "● CHARGEMENT" : "● PRÊT",
                isTelevision() ? 9 : 7, loadingCatalog ? CYAN : GREEN, true);
        statusHeader.addView(statusDot);
        TextView details = label(catalogStatus, isTelevision() ? 12 : 9, TEXT, false);
        details.setTextIsSelectable(true);
        details.setMaxLines(3);
        details.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailsP = lp(-1, -2);
        detailsP.topMargin = dp(8);
        statusCard.addView(details, detailsP);

        LinearLayout actions = horizontal();
        LinearLayout.LayoutParams actionsP = lp(-1, -2);
        actionsP.topMargin = dp(isTelevision() ? 13 : 10);
        page.addView(actions, actionsP);
        TextView refresh = buttonText(loadingCatalog ? "Actualisation…" : "↻  Actualiser", true);
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(isTelevision() ? 46 : 40), 1f));
        refresh.setOnClickListener(v -> loadChannels(true));
        if (loadingCatalog) refresh.setAlpha(0.65f);
        TextView cache = buttonText("Effacer le cache", false);
        LinearLayout.LayoutParams cacheP = new LinearLayout.LayoutParams(0, dp(isTelevision() ? 46 : 40), 1f);
        cacheP.leftMargin = dp(8);
        actions.addView(cache, cacheP);
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
        TextView note = label("NISSKENZI+ · direct TV",
                isTelevision() ? 10 : 8, MUTED, false);
        LinearLayout.LayoutParams noteP = lp(-1, -2);
        noteP.topMargin = dp(15);
        page.addView(note, noteP);
    }

    private View sourceCard(String title, String icon, String tagline,
                             String description, int accent) {
        LinearLayout card = vertical();
        card.setPadding(dp(isTelevision() ? 17 : 14), dp(isTelevision() ? 15 : 12),
                dp(isTelevision() ? 17 : 14), dp(isTelevision() ? 15 : 12));
        card.setBackground(outlined(0xFF222329, 15, 0x22FFFFFF));
        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label(icon, isTelevision() ? 20 : 16, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(accent, 11));
        head.addView(mark, lp(isTelevision() ? 44 : 36, isTelevision() ? 44 : 36));
        LinearLayout names = vertical();
        names.setPadding(dp(11), 0, 0, 0);
        names.addView(label(title, isTelevision() ? 16 : 12, TEXT, true));
        TextView region = label(tagline, isTelevision() ? 10 : 8, accent, true);
        region.setMaxLines(1);
        region.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams regionP = lp(-1, -2);
        regionP.topMargin = dp(3);
        names.addView(region, regionP);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(head);
        TextView desc = label(description, isTelevision() ? 10 : 8, MUTED, false);
        desc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams descP = lp(-1, -2);
        descP.topMargin = dp(10);
        card.addView(desc, descP);
        TextView state = label("●  " + sourceStatus(title), isTelevision() ? 9 : 7, GREEN, true);
        LinearLayout.LayoutParams stateP = lp(-1, -2);
        stateP.topMargin = dp(7);
        card.addView(state, stateP);
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
            groupedOnly = false;
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
                || (!"Live TV".equals(activePage) && !"Accueil".equals(activePage)
                    && !"Guide TV".equals(activePage))) return;
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
        ArrayList<Channel> options = new ArrayList<>(channel.playbackOptions());
        Collections.sort(options, (left, right) ->
                Integer.compare(sourceReliabilityScore(right), sourceReliabilityScore(left)));
        if (options.size() < 2) {
            Toast.makeText(this, "Cette chaîne n’a qu’un seul flux disponible.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout dossier = vertical();
        dossier.setPadding(dp(isTelevision() ? 24 : 16), dp(isTelevision() ? 22 : 14),
                dp(isTelevision() ? 24 : 16), dp(isTelevision() ? 18 : 12));
        dossier.setBackground(outlined(0xFF1B1C21, 20, 0x22FFFFFF));

        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        dossier.addView(titleRow, lp(-1, -2));
        TextView folderIcon = label("▤", isTelevision() ? 25 : 19, Color.WHITE, true);
        folderIcon.setGravity(Gravity.CENTER);
        folderIcon.setBackground(rounded(CYAN, 12));
        titleRow.addView(folderIcon, lp(isTelevision() ? 50 : 40, isTelevision() ? 50 : 40));
        LinearLayout heading = vertical();
        LinearLayout.LayoutParams headingP = new LinearLayout.LayoutParams(0, -2, 1f);
        headingP.leftMargin = dp(12);
        titleRow.addView(heading, headingP);
        TextView eyebrow = label("DOSSIER DE CHAÎNE", isTelevision() ? 9 : 7, CYAN, true);
        eyebrow.setLetterSpacing(0.1f);
        heading.addView(eyebrow);
        TextView title = label(channel.name, isTelevision() ? 22 : 16, TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleP = lp(-1, -2);
        titleP.topMargin = dp(3);
        heading.addView(title, titleP);
        TextView summary = label(options.size() + " flux réunis · la source la plus fiable d’abord",
                isTelevision() ? 11 : 8, MUTED, false);
        LinearLayout.LayoutParams summaryP = lp(-1, -2);
        summaryP.topMargin = dp(3);
        heading.addView(summary, summaryP);

        LinearLayout recommended = horizontal();
        recommended.setGravity(Gravity.CENTER_VERTICAL);
        recommended.setPadding(dp(14), dp(11), dp(12), dp(11));
        recommended.setBackground(outlined(0xFF352127, 15, CYAN));
        LinearLayout.LayoutParams recommendedP = lp(-1, isTelevision() ? 78 : 64);
        recommendedP.topMargin = dp(isTelevision() ? 18 : 12);
        dossier.addView(recommended, recommendedP);
        LinearLayout recommendedInfo = vertical();
        recommendedInfo.setGravity(Gravity.CENTER_VERTICAL);
        recommended.addView(recommendedInfo, new LinearLayout.LayoutParams(0, -1, 1f));
        recommendedInfo.addView(label("✦  MEILLEUR FLUX", isTelevision() ? 9 : 7, CYAN, true));
        Channel best = options.get(0);
        TextView bestSource = label((best.isVavoo ? "VAVOO" : "IPTV-ORG")
                + "  ·  " + safeCountry(best.country), isTelevision() ? 14 : 10, TEXT, true);
        LinearLayout.LayoutParams bestSourceP = lp(-1, -2);
        bestSourceP.topMargin = dp(4);
        recommendedInfo.addView(bestSource, bestSourceP);
        TextView watchBest = buttonText("▶  Regarder", true);
        recommended.addView(watchBest, lp(-2, isTelevision() ? 46 : 38));
        recommended.setFocusable(true);
        recommended.setClickable(true);
        recommended.setOnClickListener(v -> {
            sourceDialog.dismiss();
            play(channel);
        });
        watchBest.setOnClickListener(v -> recommended.performClick());

        TextView alternativesTitle = label("AUTRES SOURCES", isTelevision() ? 9 : 7, MUTED, true);
        alternativesTitle.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams alternativesTitleP = lp(-1, -2);
        alternativesTitleP.topMargin = dp(isTelevision() ? 17 : 12);
        alternativesTitleP.bottomMargin = dp(6);
        dossier.addView(alternativesTitle, alternativesTitleP);

        ScrollView alternativesScroll = new ScrollView(this);
        alternativesScroll.setVerticalScrollBarEnabled(false);
        LinearLayout alternatives = vertical();
        alternativesScroll.addView(alternatives);
        dossier.addView(alternativesScroll, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 1; i < options.size(); i++) {
            final int sourceIndex = i;
            Channel option = options.get(i);
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            row.setBackground(outlined(0xFF26272D, 12, 0x00202020));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout.LayoutParams rowP = lp(-1, isTelevision() ? 58 : 48);
            rowP.bottomMargin = dp(5);
            alternatives.addView(row, rowP);
            TextView number = label(String.format(Locale.ROOT, "%02d", i + 1),
                    isTelevision() ? 12 : 9, MUTED, true);
            row.addView(number, lp(34, -2));
            LinearLayout sourceInfo = vertical();
            LinearLayout.LayoutParams sourceInfoP = new LinearLayout.LayoutParams(0, -2, 1f);
            sourceInfoP.leftMargin = dp(8);
            row.addView(sourceInfo, sourceInfoP);
            TextView provider = label(option.isVavoo ? "VAVOO" : "IPTV-ORG",
                    isTelevision() ? 12 : 9, TEXT, true);
            provider.setSingleLine(true);
            provider.setEllipsize(TextUtils.TruncateAt.END);
            sourceInfo.addView(provider);
            TextView country = label(flag(option.country, countryCode(option.country))
                    + "  " + safeCountry(option.country), isTelevision() ? 9 : 7, MUTED, false);
            sourceInfo.addView(country);
            TextView choose = label("Choisir", isTelevision() ? 10 : 8, 0xFFFFB1BA, true);
            row.addView(choose);
            row.setOnFocusChangeListener((view, focused) -> {
                row.setBackground(outlined(focused ? 0xFF39232A : 0xFF26272D,
                        12, focused ? CYAN : 0x00202020));
                row.setScaleX(focused ? 1.02f : 1f);
                row.setScaleY(focused ? 1.02f : 1f);
            });
            row.setOnClickListener(v -> {
                sourceDialog.dismiss();
                playSourceChoice(channel, options, sourceIndex);
            });
        }

        sourceDialog = new AlertDialog.Builder(this)
                .setView(dossier)
                .setNegativeButton("Fermer", null)
                .create();
        sourceDialog.setOnShowListener(dialog -> {
            if (sourceDialog.getWindow() != null) {
                sourceDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                sourceDialog.getWindow().setLayout(
                        isTelevision()
                                ? Math.min(dp(670), getResources().getDisplayMetrics().widthPixels - dp(64))
                                : getResources().getDisplayMetrics().widthPixels - dp(32),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            recommended.requestFocus();
        });
        sourceDialog.show();
    }

    private AlertDialog sourceDialog;

    private void playSourceChoice(Channel channel, List<Channel> options, int selectedIndex) {
        if (channel == null || options == null || selectedIndex < 0 || selectedIndex >= options.size()) return;
        ArrayList<Channel> remaining = new ArrayList<>(options.subList(selectedIndex, options.size()));
        Channel selected = Channel.grouped(remaining.get(0), remaining, channel.identity());
        preserveManualSourceOrder = true;
        play(selected);
    }

    private void play(Channel ch) {
        if (ch == null) return;
        playbackReturnFocus = getCurrentFocus();
        currentChannel = ch;
        if (sourceOptionsButton != null) {
            sourceOptionsButton.setText("▤ Dossier (" + ch.sourceCount() + ")");
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
            sourceOptionsButton.setText("▤ Dossier");
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
        } else if ("Paramètres".equals(activePage) || "Live TV".equals(activePage)
                || "Guide TV".equals(activePage)) {
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