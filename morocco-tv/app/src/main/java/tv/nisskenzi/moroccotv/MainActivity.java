package tv.nisskenzi.moroccotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String WORLD_REFRESH_PREFS = "world_playlist_refresh";
    private static final String WORLD_REFRESH_LAST_SUCCESS = "last_success";
    private static final String WORLD_REFRESH_LAST_ATTEMPT = "last_attempt";
    private static final long WORLD_REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final long WORLD_REFRESH_RETRY_MS = 30L * 60L * 1000L;

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
    private TextView settingsTab;
    private TextView previewTitle;
    private TextView previewStatus;
    private TextView previewDetail;
    private TextView liveTimeView;
    private TextView liveDateView;
    private TextView epgStatusView;
    private TextView epgNowView;
    private TextView epgNextView;
    private TextView fullscreenButton;
    private final Handler headerClockHandler = new Handler(Looper.getMainLooper());
    private final Runnable headerClockRunnable = new Runnable() {
        @Override public void run() {
            updateHeaderClock();
            headerClockHandler.postDelayed(this, 1000L);
        }
    };

    private NativeLivePlayerView playerView;
    private ExoPlayer player;
    private FrameLayout playerStage;
    private boolean fullscreenPlayback = false;
    private ViewGroup playerStageParent;
    private View playerStagePlaceholder;
    private ViewGroup.LayoutParams playerStageOriginalLayoutParams;
    private int playerStageOriginalIndex = -1;
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
    private final AtomicInteger filterGeneration = new AtomicInteger(0);
    private final ThreadPoolExecutor filterExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            Executors.defaultThreadFactory(), new ThreadPoolExecutor.DiscardOldestPolicy());
    private Future<?> filterJob;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearchFilter;
    private FavoritesStore favoritesStore;
    private RecentChannelsStore recentStore;
    private int catalogVersion = 0;
    // Identity token shared by each player instance for the same loaded catalogue.
    private Object channelCatalogToken = new Object();
    private int countryStatsVersion = -1;
    private final Map<String, Integer> countryCountCache = new HashMap<>();
    private final Map<String, String> countryCodeCache = new HashMap<>();
    private final Set<String> channelUrlCache = new HashSet<>();
    private int channelUrlSetVersion = -1;
    private int favoriteCountCache = 0;
    private int favoriteCountCatalogVersion = -1;
    private long favoriteCountStoreRevision = -1L;
    private int recentCountCache = 0;
    private int recentCountCatalogVersion = -1;
    private long recentCountStoreRevision = -1L;

    private String activeCountry = "Tous les pays";
    private String sourceFilter = "VAVOO";
    private String searchText = "";
    private String categorySearch = "";
    private String lastRenderedFilterKey = "";
    private boolean favoritesOnly = false;
    private boolean recentOnly = false;

    private final ArrayList<Channel> channels = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        favoritesStore = new FavoritesStore(getSharedPreferences("favorites", MODE_PRIVATE));
        recentStore = new RecentChannelsStore(getSharedPreferences("recent", MODE_PRIVATE));
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
                boolean active = b == liveTab ? "Live TV".equals(activePage)
                        : b == vavooTab ? ("Live TV".equals(activePage) && "VAVOO".equals(sourceFilter))
                        : b == iptvTab ? ("Live TV".equals(activePage) && "IPTV-ORG".equals(sourceFilter))
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
        if (player != null || vlcPlayer != null) releasePlayer();
        root = vertical();
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF080B10, 0xFF111A26, 0xFF0A131D});
        root.setBackground(backdrop);
        setContentView(root);

        // Barre supérieure large et native Android TV
        LinearLayout header = horizontal();
        int headerPad = isTelevision() ? 26 : 14;
        header.setPadding(dp(headerPad), dp(isTelevision() ? 10 : 8),
                dp(headerPad), dp(isTelevision() ? 10 : 8));
        header.setBackgroundColor(0xDD101721);
        headerView = header;
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView back = buttonText("‹", false);
        back.setContentDescription("Retour");
        header.addView(back, lp(isTelevision() ? 44 : 38, isTelevision() ? 48 : 40));
        back.setOnClickListener(v -> onBackPressed());

        // Native Android branding inspired by the supplied IPTV mock-up.
        // Everything is a real Android View, not a WebView or an HTML page.
        if (isTelevision()) {
            LinearLayout brand = horizontal();
            brand.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams brandP = lp(-2, 48);
            brandP.leftMargin = dp(10);
            brandP.rightMargin = dp(12);
            header.addView(brand, brandP);

            TextView tvIcon = label("TV", 17, Color.WHITE, true);
            tvIcon.setGravity(Gravity.CENTER);
            GradientDrawable iconGradient = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2563EB, 0xFF4F46E5});
            iconGradient.setCornerRadius(dp(12));
            tvIcon.setBackground(iconGradient);
            brand.addView(tvIcon, lp(42, 42));

            LinearLayout brandText = vertical();
            brandText.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams brandTextP = lp(-2, -2);
            brandTextP.leftMargin = dp(9);
            brand.addView(brandText, brandTextP);
            TextView brandTitle = label("NISSKENZI+", 16, Color.WHITE, true);
            brandTitle.setLetterSpacing(0.05f);
            brandText.addView(brandTitle, lp(-2, -2));
            TextView brandSubtitle = label("IPTV PLAYER", 9, CYAN, true);
            brandSubtitle.setLetterSpacing(0.15f);
            brandText.addView(brandSubtitle, lp(-2, -2));
        }

        liveTab = buttonText("TV en direct", true);
        vavooTab = buttonText("VAVOO", "VAVOO".equals(sourceFilter));
        iptvTab = buttonText("MONDE IPTV", "IPTV-ORG".equals(sourceFilter));
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
        searchInput.setBackground(outlined(0xFF171F2A, 24, 0xFF39485B));
        searchInput.setText(searchText);
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(0, dp(isTelevision() ? 48 : 40), 1f);
        searchP.leftMargin = dp(isTelevision() ? 20 : 10);
        searchP.rightMargin = dp(isTelevision() ? 20 : 10);
        header.addView(searchInput, searchP);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchText = s == null ? "" : s.toString();
                scheduleSearchFilter();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        if (isTelevision()) {
            LinearLayout clockBlock = vertical();
            clockBlock.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams clockP = lp(112, -2);
            clockP.rightMargin = dp(12);
            header.addView(clockBlock, clockP);
            liveTimeView = label("--:--:--", 15, 0xFFE2E8F0, true);
            liveTimeView.setGravity(Gravity.RIGHT);
            clockBlock.addView(liveTimeView, lp(-1, -2));
            liveDateView = label("Chargement de la date…", 9, MUTED, false);
            liveDateView.setGravity(Gravity.RIGHT);
            clockBlock.addView(liveDateView, lp(-1, -2));
            updateHeaderClock();
            headerClockHandler.removeCallbacks(headerClockRunnable);
            headerClockHandler.postDelayed(headerClockRunnable, 1000L);
        }

        header.addView(settingsTab, lp(isTelevision() ? 46 : 40, isTelevision() ? 48 : 40));
        settingsTab.setOnClickListener(v -> setPage("Paramètres"));

        TextView mark = label("N+", isTelevision() ? 24 : 20, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(outlined(0xFF1B2938, 16, 0xFF4CC9F0));
        LinearLayout.LayoutParams markP = lp(isTelevision() ? 54 : 44, isTelevision() ? 48 : 40);
        markP.leftMargin = dp(10);
        header.addView(mark, markP);

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
        channelResults = null;
        channelListHost = null;
        channelEmptyView = null;
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
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 2.5f));

        View dividerOne = new View(this);
        dividerOne.setBackgroundColor(LINE);
        body.addView(dividerOne, new LinearLayout.LayoutParams(dp(1), -1));

        // --- COLONNE DU MILIEU : Liste compacte de chaînes avec index, logo et nom ---
        LinearLayout middle = vertical();
        middle.setPadding(dp(16), dp(16), dp(16), dp(12));
        body.addView(middle, new LinearLayout.LayoutParams(0, -1, 3.1f));

        View dividerTwo = new View(this);
        dividerTwo.setBackgroundColor(LINE);
        body.addView(dividerTwo, new LinearLayout.LayoutParams(dp(1), -1));

        // --- COLONNE DROITE : Lecteur/Aperçu noir au-dessus et actions Favori en dessous ---
        LinearLayout right = vertical();
        right.setPadding(dp(18), dp(18), dp(20), dp(14));
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 4.4f));

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
        categorySearchInput.setBackground(outlined(0xFF171F2A, 18, 0xFF39485B));
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
        String visibleSourceTitle = "IPTV-ORG".equals(sourceFilter) ? "MONDE IPTV" : sourceFilter;
        TextView sourceTitle = label(visibleSourceTitle, isTelevision() ? 24 : 18, Color.WHITE, true);
        middleHeader.addView(sourceTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        countView = label(channels.size() + " chaînes", isTelevision() ? 13 : 10, Color.WHITE, true);
        countView.setBackground(rounded(0xFF212B38, 15));
        countView.setPadding(dp(11), dp(6), dp(11), dp(6));
        middleHeader.addView(countView);
        middle.addView(middleHeader, lp(-1, -2));

        TextView listSubtitle = label(
                "Flux direct · " + ("VAVOO".equals(sourceFilter)
                        ? "VAVOO Server" : "IPTV-org + Free-TV + flux validés"),
                isTelevision() ? 12 : 9, 0xEEFFFFFF, false);
        LinearLayout.LayoutParams lsp = lp(-1, -2);
        lsp.topMargin = dp(4);
        lsp.bottomMargin = dp(10);
        middle.addView(listSubtitle, lsp);

        // RecyclerView reuses visible rows instead of inflating a view for every channel.
        channelListHost = new FrameLayout(this);
        channelResults = new RecyclerView(this);
        channelResults.setLayoutManager(new LinearLayoutManager(this));
        channelResults.setHasFixedSize(true);
        channelResults.setItemViewCacheSize(8);
        channelResults.setItemAnimator(null);
        channelAdapter = new ChannelAdapter(this::play, this::isFavorite);
        channelAdapter.setSelectedUrl(currentChannel == null ? null : currentChannel.url);
        channelResults.setAdapter(channelAdapter);
        channelListHost.addView(channelResults, new FrameLayout.LayoutParams(-1, -1));

        channelEmptyView = vertical();
        channelEmptyView.setGravity(Gravity.CENTER);
        channelEmptyView.setVisibility(View.GONE);
        channelListHost.addView(channelEmptyView, new FrameLayout.LayoutParams(-1, -1));
        middle.addView(channelListHost, new LinearLayout.LayoutParams(-1, 0, 1f));

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

        fullscreenButton = buttonText("⛶ Plein écran", false);
        fullscreenButton.setEnabled(currentChannel != null);
        fullscreenButton.setAlpha(currentChannel == null ? 0.55f : 1f);
        LinearLayout.LayoutParams fullscreenP = new LinearLayout.LayoutParams(0, -1, 1.2f);
        fullscreenP.leftMargin = dp(6);
        actions.addView(fullscreenButton, fullscreenP);
        fullscreenButton.setOnClickListener(v -> {
            if (currentChannel == null) {
                Toast.makeText(this, "Sélectionnez une chaîne d’abord.", Toast.LENGTH_SHORT).show();
            } else {
                enterFullscreenPlayback();
            }
        });

        TextView stop = buttonText("■ Arrêter", false);
        LinearLayout.LayoutParams stopP = lp(-2, -1);
        stopP.leftMargin = dp(6);
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
            if (channelAdapter != null) channelAdapter.refreshDecorations();
        });
        stop.setOnClickListener(v -> stopPreview());

        previewDetail = label(currentChannel == null
                ? "La lecture s'intègre directement ici."
                : ("SOURCE  " + sourceFilter + "     ·     PAYS  " + safeCountry(currentChannel.country)),
                isTelevision() ? 13 : 10, Color.WHITE, false);
        previewDetail.setPadding(0, dp(10), 0, 0);
        previewDetail.setMaxLines(2);
        previewDetail.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(previewDetail, lp(-1, -2));

        // Real XMLTV data, fetched by EpgProvider; no mock programmes or demo streams.
        LinearLayout epgBox = vertical();
        epgBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        epgBox.setBackground(outlined(0xCC111925, 14, 0x663B4B61));
        LinearLayout.LayoutParams epgBoxP = lp(-1, -2);
        epgBoxP.topMargin = dp(14);
        right.addView(epgBox, epgBoxP);

        TextView epgHeading = label("GUIDE DES PROGRAMMES · EPG", isTelevision() ? 11 : 9, CYAN, true);
        epgHeading.setLetterSpacing(0.07f);
        epgBox.addView(epgHeading, lp(-1, -2));

        epgStatusView = label(currentChannel == null
                ? "Sélectionnez une chaîne pour rechercher son programme"
                : "Chargement du guide…", 10, MUTED, false);
        epgStatusView.setMaxLines(2);
        epgStatusView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams epgStatusP = lp(-1, -2);
        epgStatusP.topMargin = dp(6);
        epgBox.addView(epgStatusView, epgStatusP);

        epgNowView = label("EN COURS  ·  —", isTelevision() ? 12 : 10, Color.WHITE, true);
        epgNowView.setMaxLines(2);
        epgNowView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams epgNowP = lp(-1, -2);
        epgNowP.topMargin = dp(9);
        epgBox.addView(epgNowView, epgNowP);

        epgNextView = label("À SUIVRE  ·  —", isTelevision() ? 11 : 9, MUTED, false);
        epgNextView.setMaxLines(2);
        epgNextView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams epgNextP = lp(-1, -2);
        epgNextP.topMargin = dp(7);
        epgBox.addView(epgNextView, epgNextP);

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
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Récemment vus", countRecentChannels(), recentOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = false;
                    recentOnly = true;
                    renderCountryChoices();
                    updateChannelResults();
                });
        addCategoryRow("Favoris", countFavoriteChannels(), favoritesOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = true;
                    recentOnly = false;
                    renderCountryChoices();
                    updateChannelResults();
                });

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams divP = lp(-1, 1);
        divP.topMargin = dp(8);
        divP.bottomMargin = dp(8);
        countryChoices.addView(divider, divP);

        if (countryStatsVersion != catalogVersion) {
            countryCountCache.clear();
            countryCodeCache.clear();
            // Thousands of channels share the same handful of countries. Normalize
            // each distinct country only once; do not run Unicode normalization/regex
            // for every row on the UI thread whenever the category panel is built.
            HashMap<String, String> normalizedCountries = new HashMap<>();
            channelUrlCache.clear();
            for (Channel ch : channels) {
                if (ch != null && !safe(ch.url).isEmpty()) channelUrlCache.add(ch.url);
                String rawCountry = safe(ch.country);
                String country = normalizedCountries.get(rawCountry);
                if (country == null) {
                    country = safeCountry(rawCountry);
                    normalizedCountries.put(rawCountry, country);
                }
                Integer oldCount = countryCountCache.get(country);
                countryCountCache.put(country, oldCount == null ? 1 : oldCount + 1);
            }
            for (String country : countryCountCache.keySet()) {
                countryCodeCache.put(country, countryCode(country));
            }
            channelUrlSetVersion = catalogVersion;
            countryStatsVersion = catalogVersion;
        }
        ArrayList<String> names = new ArrayList<>(countryCountCache.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        String query = categorySearch == null ? "" : categorySearch.trim().toLowerCase(Locale.ROOT);
        for (String country : names) {
            if (!query.isEmpty() && !country.toLowerCase(Locale.ROOT).contains(query)) continue;
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
    }

    private void markCatalogChanged() {
        catalogVersion++;
        channelCatalogToken = new Object();
        countryStatsVersion = -1;
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
        if (ch != null && recentStore != null) recentStore.remember(ch.url);
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
                    for (Channel ch : channels) if (recent.contains(ch.url)) count++;
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
        final String requestedSearch = searchText;
        final boolean requestedFavorites = favoritesOnly;
        final boolean requestedRecent = recentOnly;
        final String filterKey = requestedSource + "|" + requestedCountry + "|" + safe(requestedSearch)
                + "|" + requestedFavorites + "|" + requestedRecent;
        final boolean filtersChanged = !TextUtils.equals(filterKey, lastRenderedFilterKey);
        lastRenderedFilterKey = filterKey;

        // Only copy the stable inputs on the UI thread. The full scan and search matching
        // now happen on a worker and stale requests are cancelled when the D-pad/search changes.
        final ArrayList<Channel> channelSnapshot = new ArrayList<>(channels);
        final Set<String> favoriteSnapshot = favoritesStore == null
                ? Collections.emptySet() : favoritesStore.snapshotUrls();
        final Set<String> recentSnapshot = recentStore == null
                ? Collections.emptySet() : recentStore.snapshotUrls();

        filterJob = filterExecutor.submit(() -> {
            ArrayList<Channel> filtered = ChannelFilter.filter(channelSnapshot, requestedSource,
                    requestedCountry, requestedSearch, requestedFavorites, requestedRecent,
                    favoriteSnapshot, recentSnapshot);

            runOnUiThread(() -> {
                if (activityDestroyed || requestId != filterGeneration.get()
                        || targetRecycler != channelResults || targetAdapter != channelAdapter
                        || !requestedSource.equals(sourceFilter)
                        || !"Live TV".equals(activePage)) return;

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
                            ? requestedSource + " se charge en arrière-plan. Vous pouvez naviguer ou actualiser."
                            : "Essayez une autre catégorie ou modifiez votre recherche.",
                            isTelevision() ? 12 : 10, 0xEEFFFFFF, false);
                    msg.setGravity(Gravity.CENTER);
                    msg.setPadding(0, dp(8), 0, dp(12));
                    empty.addView(msg, lp(-1, -2));

                    TextView retry = buttonText(loadingCatalog ? "Chargement…" : "↻ Actualiser " + requestedSource, true);
                    empty.addView(retry, lp(-2, isTelevision() ? 46 : 38));
                    retry.setOnClickListener(v -> loadChannels());
                    channelEmptyView.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                    return;
                }

                channelEmptyView.setVisibility(View.GONE);
                targetRecycler.setVisibility(View.VISIBLE);
                targetAdapter.setSelectedUrl(currentChannel == null ? null : currentChannel.url);
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
        page.addView(sourceCard("IPTV-ORG + PARTENAIRES", "🌐", "Catalogue mondial intégré",
                "IPTV-org (monde entier), Free-TV sélectionné et flux passés au contrôle en ligne. Les listes sont fusionnées et dédupliquées dans le M3U intégré à l’APK.",
                PURPLE), gap);

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
                Toast.makeText(this, "Cache local effacé.", Toast.LENGTH_SHORT).show();
                channels.clear();
                markCatalogChanged();
                activeCountry = "Tous les pays";
                lastRenderedFilterKey = "";
                renderPage();
            }).show());

        TextView note = label("VAVOO garde son résolveur natif. MONDE IPTV réunit IPTV-org, Free-TV et les flux validés. Pluto TV et Samsung TV Plus sont exclus.", 10, MUTED, false);
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

    private void loadChannels(boolean forceRefresh) {
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
                        catalogStatus = "IPTV-ORG".equals(requestedSource)
                                ? "MONDE IPTV · " + ready.size() + " chaînes · catalogue intégré prêt"
                                : requestedSource + " · " + ready.size() + " chaînes · catalogue M3U préchargé";
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
                        catalogStatus = "IPTV-ORG".equals(requestedSource)
                                ? "MONDE IPTV · " + ready.size() + " chaînes · catalogue intégré prêt"
                                : requestedSource + " · " + ready.size() + " chaînes · catalogue M3U préchargé";
                        updateStatus();
                        updateHeaderTabs();
                        if (fullscreenPlayback) {
                            renderCountryChoices();
                            updateChannelResults();
                        } else {
                            renderPage();
                        }
                    });
                    // Local catalogue appears first; networking never blocks startup.
                    if (!requestVavoo) maybeRefreshWorldPlaylistInBackground();
                    return;
                }
            }

            // Only the fallback/manual-refresh path reads the legacy disk cache and
            // performs a remote catalogue request. The normal VAVOO start above returns early.
            ArrayList<Channel> quickCached = CatalogCache.readQuick(
                    MainActivity.this, requestedSource, MAX_CACHED_CHANNELS);
            showCachedSnapshot(quickCached, requestedSource, currentGen);

            ArrayList<Channel> diskCached = CatalogCache.readFull(
                    MainActivity.this, requestedSource, MAX_CATALOG_CHANNELS);

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

            final ArrayList<Channel> result = loaded;
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
                        catalogStatus = "IPTV-ORG".equals(requestedSource)
                             ? "MONDE IPTV · " + result.size() + " chaînes · sources actualisées"
                             : requestedSource + " · " + result.size() + " chaînes chargées";
                    } else if (!channels.isEmpty()) {
                        catalogStatus = "IPTV-ORG".equals(requestedSource)
                                ? "MONDE IPTV · catalogue intégré prêt, actualisation reportée"
                                : requestedSource + " · actualisation reportée, catalogue disponible";
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

    /**
     * Keeps startup local-first. Public world-playlist sources are refreshed in the
     * background at most every 12 hours; failures leave the last good catalogue intact.
     */
    private void maybeRefreshWorldPlaylistInBackground() {
        SharedPreferences prefs = getSharedPreferences(WORLD_REFRESH_PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastSuccess = prefs.getLong(WORLD_REFRESH_LAST_SUCCESS, 0L);
        long lastAttempt = prefs.getLong(WORLD_REFRESH_LAST_ATTEMPT, 0L);
        if (now - lastSuccess < WORLD_REFRESH_INTERVAL_MS
                || now - lastAttempt < WORLD_REFRESH_RETRY_MS) {
            return;
        }
        prefs.edit().putLong(WORLD_REFRESH_LAST_ATTEMPT, now).apply();

        io.execute(() -> {
            final ArrayList<Channel> refreshed;
            try {
                refreshed = IptvOrgProvider.loadChannels(MainActivity.this,
                        MAX_CATALOG_CHANNELS, MainActivity.this::knownCountry, null, true);
                if (refreshed == null || refreshed.size() < 1000) {
                    throw new IllegalStateException("Catalogue mondial incomplet.");
                }
                long refreshedAt = System.currentTimeMillis();
                getSharedPreferences(WORLD_REFRESH_PREFS, MODE_PRIVATE).edit()
                        .putLong(WORLD_REFRESH_LAST_SUCCESS, refreshedAt)
                        .putLong(WORLD_REFRESH_LAST_ATTEMPT, refreshedAt)
                        .apply();
                CatalogCache.saveQuick(MainActivity.this, refreshed, "IPTV-ORG", MAX_CACHED_CHANNELS);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (activityDestroyed) return;
                    if ("IPTV-ORG".equals(sourceFilter) && !channels.isEmpty()) {
                        catalogStatus = "MONDE IPTV · catalogue intégré conservé; actualisation reportée";
                        updateStatus();
                    }
                });
                return;
            }

            runOnUiThread(() -> {
                if (activityDestroyed) return;
                catalogStatus = "MONDE IPTV · " + refreshed.size()
                        + " chaînes · sources mondiales actualisées";
                if (!"IPTV-ORG".equals(sourceFilter) || !"Live TV".equals(activePage)) {
                    updateStatus();
                    return;
                }
                channels.clear();
                channels.addAll(refreshed);
                markCatalogChanged();
                updateStatus();
                updateHeaderTabs();
                if (fullscreenPlayback) {
                    renderCountryChoices();
                    updateChannelResults();
                } else {
                    renderPage();
                }
            });
        });
    }

    private Channel toAppChannel(VavooProvider.VavooChannel item) {
        String country = knownCountry(item.country);
        if (country.isEmpty()) country = knownCountry(item.group);
        if (country.isEmpty()) country = "Pays non précisé";
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
        return converted;
    }

    private void publishCatalogSnapshot(ArrayList<Channel> snapshot, String requestedSource,
                                        int generation) {
        if (snapshot == null || snapshot.isEmpty()) return;
        final ArrayList<Channel> copy = new ArrayList<>(snapshot);
        runOnUiThread(() -> {
            if (activityDestroyed || generation != loadGeneration.get()
                    || !requestedSource.equals(sourceFilter)
                    || copy.size() <= channels.size()) return;
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
        final ArrayList<Channel> snapshot = new ArrayList<>(cacheSnapshot);
        runOnUiThread(() -> {
            if (activityDestroyed || generation != loadGeneration.get()
                    || !requestedSource.equals(sourceFilter) || snapshot.size() <= channels.size()) return;
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
        return ch != null && (favoritesStore != null
                ? favoritesStore.contains(ch.url)
                : getSharedPreferences("favorites", MODE_PRIVATE).getBoolean(ch.url, false));
    }

    private void toggleFavorite(Channel ch) {
        if (ch == null) return;
        if (favoritesStore != null) {
            favoritesStore.toggle(ch.url);
        } else {
            SharedPreferences p = getSharedPreferences("favorites", MODE_PRIVATE);
            p.edit().putBoolean(ch.url, !p.getBoolean(ch.url, false)).apply();
        }
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

    private void enterFullscreenPlayback() {
        if (fullscreenPlayback || playerStage == null || contentHost == null
                || !"Live TV".equals(activePage)) return;
        if (!(playerStage.getParent() instanceof ViewGroup)) return;

        playerStageParent = (ViewGroup) playerStage.getParent();
        playerStageOriginalIndex = playerStageParent.indexOfChild(playerStage);
        playerStageOriginalLayoutParams = playerStage.getLayoutParams();

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
        if (channelResults != null) channelResults.requestFocus();
    }

    private void play(Channel ch) {
        if (ch == null) return;
        currentChannel = ch;
        if (channelAdapter != null) channelAdapter.setSelectedUrl(ch.url);
        rememberRecentlyViewed(ch);
        if (previewTitle != null) previewTitle.setText(safe(ch.name));
        if (previewStatus != null) previewStatus.setText("Connexion au flux " + sourceFilter + "…");
        if (previewDetail != null) previewDetail.setText(
                "SOURCE  " + sourceFilter + "     ·     PAYS  " + safeCountry(ch.country));
        renderCountryChoices();
        if (fullscreenButton != null) {
            fullscreenButton.setEnabled(true);
            fullscreenButton.setAlpha(1f);
        }
        // Keep the three-column TV interface visible while the native player
        // runs inside the right-hand panel. Fullscreen stays an explicit action.
        startPlayback(ch);
        loadEpgForCurrentChannel();
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
        NativeLivePlayerView nativeView = new NativeLivePlayerView(this);
        try {
            ExoPlayer active = LivePlaybackFactory.create(this,
                    ch.isVavoo ? "VAVOO/2.6" : "NISSKENZI+/2.1");
            nativeView.setChannelName(ch.name);
            nativeView.setSourceLabel(ch.isVavoo ? "VAVOO" : "IPTV-ORG");
            // The visible Back button dismisses the on-screen bars. Physical Back
            // closes the drawer, then hides controls, then exits fullscreen.
            nativeView.setBackAction(nativeView::hideControls);
            nativeView.setDrawerChannels(channels, channelCatalogToken, ch.url, this::play,
                    this::loadEpgForCurrentChannel);
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
                    if (currentChannel != ch || playerView != nativeView || previewStatus == null) return;
                    nativeView.setPlaybackState(playbackState);
                    if (playbackState == Player.STATE_READY) {
                        previewStatus.setText("● EN DIRECT");
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        previewStatus.setText("Connexion · mise en mémoire tampon…");
                    } else {
                        previewStatus.setText("Connexion au flux…");
                    }
                }

                @Override public void onPlayerError(PlaybackException e) {
                    if (currentChannel == ch && playerView == nativeView) {
                        nativeView.setErrorMessage("Flux indisponible · un autre lecteur peut être nécessaire");
                        if (previewStatus != null) {
                            previewStatus.setText("Flux indisponible · essayez l’autre lecteur");
                        }
                    }
                    Toast.makeText(MainActivity.this,
                            "Media3 : lecture impossible (" + e.getErrorCodeName() + ").",
                            Toast.LENGTH_LONG).show();
                    if (!useVlcPlayer()) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Essayer un autre lecteur ?")
                            .setMessage("Ce flux semble nécessiter un moteur vidéo plus compatible. VLC est disponible en option.")
                            .setNegativeButton("Pas maintenant", null)
                            .setPositiveButton("Essayer VLC", (dialog, which) -> {
                                setVlcPreference(true);
                                startPlayback(ch);
                            }).show();
                    }
                }
            });
            active.setMediaItem(LivePlaybackFactory.mediaItem(streamUrl, ch.name));
            active.prepare();
            active.setPlayWhenReady(true);
        } catch (Exception e) {
            if (nativeView != null) {
                nativeView.setErrorMessage("Démarrage impossible");
            }
            Toast.makeText(this, "Impossible de démarrer Media3 : " + safe(e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void loadEpgForCurrentChannel() {
        final Channel requested = currentChannel;
        final NativeLivePlayerView target = playerView;
        if (requested == null) {
            updateInlineEpg(null);
            return;
        }
        EpgProvider.Result loading = EpgProvider.Result.loading();
        updateInlineEpg(loading);
        if (target != null) target.setEpgInfo(loading);
        io.execute(() -> {
            EpgProvider.Result result = EpgProvider.lookup(MainActivity.this, requested);
            runOnUiThread(() -> {
                if (activityDestroyed || currentChannel != requested) return;
                updateInlineEpg(result);
                if (target != null && playerView == target && target.isChannelDrawerOpen()) {
                    target.setEpgInfo(result);
                }
            });
        });
    }

    private void updateInlineEpg(EpgProvider.Result result) {
        if (epgStatusView == null || epgNowView == null || epgNextView == null) return;
        if (result == null) {
            epgStatusView.setText("Sélectionnez une chaîne pour rechercher son programme");
            epgNowView.setText("EN COURS  ·  —");
            epgNextView.setText("À SUIVRE  ·  —");
            return;
        }
        epgStatusView.setText(result.status.isEmpty() ? "Guide XMLTV" : result.status);
        epgNowView.setText("EN COURS  ·  " + (result.now.isEmpty() ? "Aucun programme trouvé" : result.now));
        epgNextView.setText("À SUIVRE  ·  " + (result.next.isEmpty() ? "Programme non renseigné" : result.next));
    }

    private void updateHeaderClock() {
        if (liveTimeView == null || liveDateView == null) return;
        Date now = new Date();
        liveTimeView.setText(new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(now));
        liveDateView.setText(new SimpleDateFormat("EEEE d MMM", Locale.FRANCE).format(now));
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
        if (previewDetail != null) previewDetail.setText("Le lecteur natif est prêt pour la prochaine chaîne.");
        if (fullscreenButton != null) {
            fullscreenButton.setEnabled(false);
            fullscreenButton.setAlpha(0.55f);
        }
        updateInlineEpg(null);
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
        } else if ("Paramètres".equals(activePage)) {
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
        filterGeneration.incrementAndGet();
        if (filterJob != null) filterJob.cancel(true);
        filterExecutor.shutdownNow();
        if (pendingSearchFilter != null) uiHandler.removeCallbacks(pendingSearchFilter);
        headerClockHandler.removeCallbacks(headerClockRunnable);
        releasePlayer();
        io.shutdownNow();
        if (channelAdapter != null) channelAdapter.shutdown();
        super.onDestroy();
    }
}