package tv.nisskenzi.moroccotv;

import androidx.activity.ComponentActivity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.InputType;
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
import androidx.lifecycle.ViewModelProvider;

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
public class MainActivity extends ComponentActivity {
    private static final int PANEL = 0xCC151D28;
    private static final int LINE = 0x665D6D82;
    private static final int CYAN = 0xFF4CC9F0;
    private static final int PURPLE = 0xFFB39DFF;
    private static final int MUTED = 0xFFB8C4D2;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GREEN = 0xFF4CC9F0;
    private static final int YELLOW = 0xFFFFFF00;
    // Bound M3U parsing for unusually large or malformed remote playlists.
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
    private TextView settingsTab;
    private TextView previewTitle;
    private TextView previewStatus;
    private TextView previewDetail;

    private NativeLivePlayerView playerView;
    private ChannelViewModel channelViewModel;
    private ExoPlayer player;
    private FrameLayout playerStage;
    private boolean fullscreenPlayback = false;
    private ViewGroup playerStageParent;
    private View playerStagePlaceholder;
    private ViewGroup.LayoutParams playerStageOriginalLayoutParams;
    private int playerStageOriginalIndex = -1;
    private Channel currentChannel;
    private volatile String activePage = "Live TV";
    private volatile String catalogStatus = "Catalogue en veille · sélectionne VAVOO/IPTV-ORG ou Actualiser pour le charger";
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
    private String countryStatsSourceFilter = "";
    private final Map<String, Integer> sourceCountCache = new HashMap<>();
    private final Map<String, Integer> countryCountCache = new HashMap<>();
    private final Map<String, String> countryCodeCache = new HashMap<>();
    private final Set<String> channelUrlCache = new HashSet<>();
    private int channelUrlSetVersion = -1;
    private int favoriteCountCache = 0;
    private int favoriteCountCatalogVersion = -1;
    private long favoriteCountStoreRevision = -1L;
    private String favoriteCountSourceFilter = "";
    private int recentCountCache = 0;
    private int recentCountCatalogVersion = -1;
    private long recentCountStoreRevision = -1L;
    private String recentCountSourceFilter = "";

    private String activeCountry = "Tous les pays";
    private String sourceFilter = "all";
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
        channelViewModel = new ViewModelProvider(this).get(ChannelViewModel.class);
        channelViewModel.getChannels().observe(this, loaded -> {
            if (loaded == null || activityDestroyed) return;
            channels.clear();
            channels.addAll(loaded);
            markCatalogChanged();
            lastRenderedFilterKey = "";
            if (fullscreenPlayback) {
                renderCountryChoices();
                updateChannelResults();
            } else if (!"Paramètres".equals(activePage)) {
                if (countryChoices == null || channelResults == null) renderPage();
                else {
                    renderCountryChoices();
                    updateChannelResults();
                }
            }
        });
        channelViewModel.getStatus().observe(this, status -> {
            if (status == null || activityDestroyed) return;
            catalogStatus = status;
            String lower = status.toLowerCase(Locale.ROOT);
            loadingCatalog = lower.contains("arrière-plan") || lower.contains("vérification");
            updateStatus();
        });
        channelViewModel.initialize();
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
                boolean active = b == liveTab ? ("Live TV".equals(activePage) && "all".equals(sourceFilter))
                        : b == vavooTab ? ("Live TV".equals(activePage) && "vavoo".equals(sourceFilter))
                        : b == iptvTab ? ("Live TV".equals(activePage) && "public".equals(sourceFilter))
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

        liveTab = buttonText("Live TV", true);
        vavooTab = buttonText("VAVOO", "vavoo".equals(sourceFilter));
        iptvTab = buttonText("M3U publics", "public".equals(sourceFilter));
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

        liveTab.setOnClickListener(v -> switchSource("ALL"));
        vavooTab.setOnClickListener(v -> switchSource("VAVOO"));
        iptvTab.setOnClickListener(v -> switchSource("public"));

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
        setTabState(liveTab, "Live TV".equals(activePage) && "all".equals(sourceFilter));
        setTabState(vavooTab, "Live TV".equals(activePage) && "vavoo".equals(sourceFilter));
        setTabState(iptvTab, "Live TV".equals(activePage) && "public".equals(sourceFilter));
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
        TextView sourceTitle = label(ChannelRepository.displayName(this, sourceFilter), isTelevision() ? 24 : 18, Color.WHITE, true);
        middleHeader.addView(sourceTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        countView = label(channels.size() + " chaînes", isTelevision() ? 13 : 10, Color.WHITE, true);
        countView.setBackground(rounded(0xFF212B38, 15));
        countView.setPadding(dp(11), dp(6), dp(11), dp(6));
        middleHeader.addView(countView);
        middle.addView(middleHeader, lp(-1, -2));

        TextView listSubtitle = label(
                "Catalogue SQLite · " + ("all".equals(sourceFilter) ? "toutes les sources M3U" : "source M3U sélectionnée"),
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
        channelAdapter.setSelectedChannel(currentChannel);
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
            if (channelAdapter != null) channelAdapter.refreshDecorations();
        });
        stop.setOnClickListener(v -> stopPreview());

        previewDetail = label(currentChannel == null
                ? "La lecture s'intègre directement ici."
                : ("SOURCE  " + ChannelRepository.displayName(this, currentChannel.catalogSource)
                        + "     ·     PAYS  " + safeCountry(currentChannel.country)),
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

    private boolean matchesCurrentSource(Channel channel) {
        return channel != null && ("all".equals(sourceFilter)
                || ("public".equals(sourceFilter) && !"vavoo".equals(channel.catalogSource))
                || sourceFilter.equals(channel.catalogSource));
    }

    private int countVisibleChannels() {
        if ("all".equals(sourceFilter)) return channels.size();
        int count = 0;
        for (Channel channel : channels) if (matchesCurrentSource(channel)) count++;
        return count;
    }

    private int countPublicChannelsFromIndex() {
        int total = 0;
        for (Map.Entry<String, Integer> item : sourceCountCache.entrySet()) {
            if (!"vavoo".equals(item.getKey())) total += item.getValue();
        }
        return total;
    }

    private void rebuildCategoryCountsIfNeeded() {
        if (countryStatsVersion == catalogVersion
                && sourceFilter.equals(countryStatsSourceFilter)) return;

        countryCountCache.clear();
        countryCodeCache.clear();
        sourceCountCache.clear();
        channelUrlCache.clear();
        HashMap<String, String> normalizedCountries = new HashMap<>();

        // This is run only when Room publishes a new snapshot or the selected
        // source changes, never during a search keystroke or channel playback.
        for (Channel ch : channels) {
            if (ch == null) continue;
            String source = safe(ch.catalogSource);
            Integer oldSourceCount = sourceCountCache.get(source);
            sourceCountCache.put(source, oldSourceCount == null ? 1 : oldSourceCount + 1);

            if (!matchesCurrentSource(ch)) continue;
            if (!safe(ch.url).isEmpty()) channelUrlCache.add(ch.url);

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
        countryStatsSourceFilter = sourceFilter;
    }

    private void renderCountryChoices() {
        if (countryChoices == null) return;
        rebuildCategoryCountsIfNeeded();
        countryChoices.removeAllViews();

        addCategoryRow("Toutes les chaînes", countVisibleChannels(),
                "all".equals(sourceFilter) && !favoritesOnly && !recentOnly
                        && "Tous les pays".equals(activeCountry),
                v -> switchSource("ALL"));
        addCategoryRow("M3U publics", countPublicChannelsFromIndex(),
                "public".equals(sourceFilter) && !favoritesOnly && !recentOnly
                        && "Tous les pays".equals(activeCountry),
                v -> switchSource("public"));
        addCategoryRow("Récemment vus", countRecentChannels(), recentOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = false;
                    recentOnly = true;
                    updateChannelResults();
                    renderCountryChoices();
                });
        addCategoryRow("Favoris", countFavoriteChannels(), favoritesOnly,
                v -> {
                    activeCountry = "Tous les pays";
                    favoritesOnly = true;
                    recentOnly = false;
                    updateChannelResults();
                    renderCountryChoices();
                });

        TextView sourcesHeading = label("SOURCES M3U", isTelevision() ? 10 : 8, CYAN, true);
        sourcesHeading.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams headingParams = lp(-1, -2);
        headingParams.topMargin = dp(10);
        headingParams.bottomMargin = dp(6);
        countryChoices.addView(sourcesHeading, headingParams);

        for (ChannelRepository.Source source : ChannelRepository.getInstance(this).getSources()) {
            int count = sourceCountCache.containsKey(source.id)
                    ? sourceCountCache.get(source.id) : 0;
            addCategoryRow(source.name, count, source.id.equals(sourceFilter)
                    && !favoritesOnly && !recentOnly,
                    v -> switchSource(source.id));
        }

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams divP = lp(-1, 1);
        divP.topMargin = dp(8);
        divP.bottomMargin = dp(8);
        countryChoices.addView(divider, divP);

        TextView countryHeading = label("PAYS", isTelevision() ? 10 : 8, CYAN, true);
        countryHeading.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams countryHeadingParams = lp(-1, -2);
        countryHeadingParams.bottomMargin = dp(6);
        countryChoices.addView(countryHeading, countryHeadingParams);

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
                        updateChannelResults();
                        renderCountryChoices();
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
        if (favoriteCountCatalogVersion != catalogVersion
                || favoriteCountStoreRevision != revision
                || !sourceFilter.equals(favoriteCountSourceFilter)) {
            int count = 0;
            if (favoritesStore != null) {
                Set<String> favorites = favoritesStore.snapshotUrls();
                for (Channel ch : channels) {
                    if (matchesCurrentSource(ch) && favorites.contains(ch.url)) count++;
                }
            }
            favoriteCountCache = count;
            favoriteCountCatalogVersion = catalogVersion;
            favoriteCountStoreRevision = revision;
            favoriteCountSourceFilter = sourceFilter;
        }
        return favoriteCountCache;
    }

    private int countRecentChannels() {
        long revision = recentStore == null ? 0L : recentStore.revision();
        if (recentCountCatalogVersion != catalogVersion
                || recentCountStoreRevision != revision
                || !sourceFilter.equals(recentCountSourceFilter)) {
            int count = 0;
            if (recentStore != null) {
                Set<String> recent = recentStore.snapshotUrls();
                if (channelUrlSetVersion == catalogVersion
                        && sourceFilter.equals(countryStatsSourceFilter)) {
                    for (String url : recent) if (channelUrlCache.contains(url)) count++;
                } else {
                    for (Channel ch : channels) {
                        if (matchesCurrentSource(ch) && recent.contains(ch.url)) count++;
                    }
                }
            }
            recentCountCache = count;
            recentCountCatalogVersion = catalogVersion;
            recentCountStoreRevision = revision;
            recentCountSourceFilter = sourceFilter;
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
                targetAdapter.setSelectedChannel(currentChannel);
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

        TextView playerInfo = label("LECTEUR NATIF ANDROID", 9, MUTED, true);
        page.addView(playerInfo);
        TextView playerInfoDetail = label(
                "Un seul lecteur Media3 avec décodage matériel Android quand il est disponible.",
                11, MUTED, false);
        LinearLayout.LayoutParams playerInfoParams = lp(-1, -2);
        playerInfoParams.topMargin = dp(8);
        playerInfoParams.bottomMargin = dp(18);
        page.addView(playerInfoDetail, playerInfoParams);

        page.addView(sourceCard("VAVOO", "V", "M3U local + résolution à la demande",
                "Le catalogue VAVOO provient du M3U intégré. Le résolveur VAVOO ne sert qu'au clic sur une chaîne concernée.", CYAN));
        LinearLayout.LayoutParams gap = lp(-1, -2);
        gap.topMargin = dp(12);
        page.addView(sourceCard("IPTV-org France", "FR", "Playlist publique",
                "Chaînes françaises à URLs directes, chargées dans la base SQLite.", PURPLE), gap);
        LinearLayout.LayoutParams cardGap = lp(-1, -2);
        cardGap.topMargin = dp(12);
        page.addView(sourceCard("Pluto TV France", "P", "FAST • M3U public",
                "Bouquet Pluto TV France via une playlist communautaire maintenue publiquement.", CYAN), cardGap);
        LinearLayout.LayoutParams samsungGap = lp(-1, -2);
        samsungGap.topMargin = dp(12);
        page.addView(sourceCard("Samsung TV Plus France", "S+", "FAST • M3U public",
                "Bouquet Samsung TV Plus France via une playlist communautaire maintenue publiquement.", PURPLE), samsungGap);
        LinearLayout.LayoutParams worldGap = lp(-1, -2);
        worldGap.topMargin = dp(12);
        page.addView(sourceCard("Free-TV International", "🌐", "Chaînes internationales gratuites",
                "Playlist internationale de chaînes gratuites, distincte des autres bouquets.", CYAN), worldGap);

        SharedPreferences m3uPrefs = getSharedPreferences("m3u_sources", MODE_PRIVATE);
        TextView m3uTitle = label("CONFIGURATION DES PLAYLISTS M3U", 10, CYAN, true);
        LinearLayout.LayoutParams m3uTitleParams = lp(-1, -2);
        m3uTitleParams.topMargin = dp(20);
        m3uTitleParams.bottomMargin = dp(8);
        page.addView(m3uTitle, m3uTitleParams);
        page.addView(label("Laisse un champ vide pour garder sa source par défaut. VAVOO utilise le M3U intégré si son champ est vide. URL acceptées : https://…, asset://nom.m3u ou file://…", 10, MUTED, false), lp(-1, -2));

        EditText vavooM3uUrl = m3uUrlField(m3uPrefs.getString("vavoo_url", ""), "URL M3U VAVOO (facultatif)");
        LinearLayout.LayoutParams vavooUrlParams = lp(-1, 46);
        vavooUrlParams.topMargin = dp(8);
        page.addView(vavooM3uUrl, vavooUrlParams);
        EditText iptvM3uUrl = m3uUrlField(m3uPrefs.getString("iptv_org_url", ""), "URL M3U IPTV-org France (facultatif)");
        LinearLayout.LayoutParams iptvUrlParams = lp(-1, 46);
        iptvUrlParams.topMargin = dp(8);
        page.addView(iptvM3uUrl, iptvUrlParams);
        EditText plutoM3uUrl = m3uUrlField(m3uPrefs.getString("pluto_url", ""), "URL M3U Pluto TV France (facultatif)");
        LinearLayout.LayoutParams plutoUrlParams = lp(-1, 46);
        plutoUrlParams.topMargin = dp(8);
        page.addView(plutoM3uUrl, plutoUrlParams);
        EditText samsungM3uUrl = m3uUrlField(m3uPrefs.getString("samsung_url", ""), "URL M3U Samsung TV Plus France (facultatif)");
        LinearLayout.LayoutParams samsungUrlParams = lp(-1, 46);
        samsungUrlParams.topMargin = dp(8);
        page.addView(samsungM3uUrl, samsungUrlParams);
        EditText freeTvM3uUrl = m3uUrlField(m3uPrefs.getString("free_tv_url", ""), "URL M3U Free-TV International (facultatif)");
        LinearLayout.LayoutParams freeTvUrlParams = lp(-1, 46);
        freeTvUrlParams.topMargin = dp(8);
        page.addView(freeTvM3uUrl, freeTvUrlParams);

        TextView saveM3uUrls = buttonText("Enregistrer les playlists M3U", true);
        LinearLayout.LayoutParams saveM3uParams = lp(-1, 46);
        saveM3uParams.topMargin = dp(10);
        page.addView(saveM3uUrls, saveM3uParams);
        saveM3uUrls.setOnClickListener(v -> {
            m3uPrefs.edit()
                    .putString("vavoo_url", safe(vavooM3uUrl.getText() == null ? "" : vavooM3uUrl.getText().toString()))
                    .putString("iptv_org_url", safe(iptvM3uUrl.getText() == null ? "" : iptvM3uUrl.getText().toString()))
                    .putString("pluto_url", safe(plutoM3uUrl.getText() == null ? "" : plutoM3uUrl.getText().toString()))
                    .putString("samsung_url", safe(samsungM3uUrl.getText() == null ? "" : samsungM3uUrl.getText().toString()))
                    .putString("free_tv_url", safe(freeTvM3uUrl.getText() == null ? "" : freeTvM3uUrl.getText().toString()))
                    .apply();
            M3UParser.clearMemoryCache();
            Toast.makeText(this, "Playlists enregistrées : actualisation en arrière-plan.", Toast.LENGTH_LONG).show();
            loadChannels(true);
        });

        LinearLayout statusCard = vertical();
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusCard.setBackground(outlined(PANEL, 14, LINE));
        LinearLayout.LayoutParams sp = lp(-1,-2); sp.topMargin = dp(16); page.addView(statusCard, sp);
        
        statusCard.addView(label("ÉTAT DU CATALOGUE", 9, MUTED, true));
        TextView details = label(catalogStatus, 11, TEXT, false);
        details.setTextIsSelectable(true);
        details.setPadding(0, dp(10), 0, dp(4));
        statusCard.addView(details);

        TextView refresh = buttonText(loadingCatalog ? "Actualisation en cours…" : "↻  Actualiser toutes les sources M3U", true);
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
                M3UParser.clearMemoryCache();
                activeCountry = "Tous les pays";
                favoritesOnly = false;
                recentOnly = false;
                lastRenderedFilterKey = "";
                if (channelViewModel != null) channelViewModel.clearLocalDatabase();
                Toast.makeText(this, "Base SQLite effacée. Les playlists seront rechargées en arrière-plan.", Toast.LENGTH_LONG).show();
            }).show());

        TextView note = label("Le catalogue est lu depuis une playlist M3U locale ou distante. Les références VAVOO sont résolues uniquement au clic sur une chaîne.", 10, MUTED, false);
        note.setPadding(dp(2), dp(14), dp(2), 0);
        page.addView(note);
    }

    private EditText m3uUrlField(String initialValue, String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setTextSize(isTelevision() ? 14 : 11);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xFF8997A8);
        field.setHint(hint);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(outlined(0xFF171F2A, 12, 0xFF39485B));
        field.setText(initialValue == null ? "" : initialValue);
        field.setSelectAllOnFocus(true);
        return field;
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
        if (loadingCatalog) return "actualisation en arrière-plan";
        String id = ChannelRepository.canonicalSourceId(source);
        int count = 0;
        for (Channel channel : channels) {
            if (channel == null) continue;
            if ("public".equals(id) ? !"vavoo".equals(channel.catalogSource)
                    : id.equals(channel.catalogSource)) count++;
        }
        if (count > 0) return count + " chaînes enregistrées dans SQLite";
        if ("vavoo".equals(id)) return "Catalogue via VavooProvider natif";
        return "Chargé depuis playlist/master.m3u";
    }

    private void switchSource(String source) {
        String nextSource = ChannelRepository.canonicalSourceId(source);
        boolean changed = !nextSource.equals(sourceFilter);
        if (!changed && "Live TV".equals(activePage) && channels.size() > 0) {
            renderCountryChoices();
            updateChannelResults();
            return;
        }

        releaseMediaEngine();
        currentChannel = null;
        playerStage = null;
        activePage = "Live TV";
        sourceFilter = nextSource;

        if (changed) {
            activeCountry = "Tous les pays";
            favoritesOnly = false;
            recentOnly = false;
            categorySearch = "";
            countryStatsSourceFilter = "";
        }
        updateHeaderTabs();
        renderPage();
        // Switching source only filters the already cached Room rows. It never
        // starts a catalogue download. The ViewModel owns refresh scheduling.
    }

    private void loadChannels() {
        loadChannels(false);
    }

    private void loadChannels(boolean forceRefresh) {
        if (channelViewModel == null) return;
        loadingCatalog = true;
        catalogStatus = forceRefresh
                ? "Actualisation des playlists M3U en arrière-plan…"
                : "Vérification du cache local et des playlists…";
        updateStatus();
        if (forceRefresh) channelViewModel.refreshAllSources();
        else channelViewModel.refreshIfNeeded();
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

        // Back leaves fullscreen and returns directly to the existing native catalogue.
        releaseMediaEngine();
        currentChannel = null;
        if (channelAdapter != null) channelAdapter.setSelectedChannel(null);
        playerStage = null;
        renderPage();
    }

    private void play(Channel ch) {
        if (ch == null) return;
        currentChannel = ch;
        if (channelAdapter != null) channelAdapter.setSelectedChannel(ch);
        rememberRecentlyViewed(ch);
        if (previewTitle != null) previewTitle.setText(safe(ch.name));
        if (previewStatus != null) previewStatus.setText("Ouverture du direct…");
        if (previewDetail != null) previewDetail.setText(
                "SOURCE  " + ChannelRepository.displayName(this, ch.catalogSource)
                        + "     ·     PAYS  " + safeCountry(ch.country));

        // Don't rebuild the country pane every time a channel is chosen. The
        // full catalogue can contain ten thousand rows and this was causing stutter.
        enterFullscreenPlayback();
        startPlayback(ch);
    }

    /** Create the native video surface before resolving the one channel the user chose. */
    private void startPlayback(Channel ch) {
        releaseMediaEngine();
        if (playerStage == null || currentChannel != ch) return;
        playerStage.removeAllViews();

        final NativeLivePlayerView nativeView = new NativeLivePlayerView(this);
        nativeView.setChannelName(ch.name);
        nativeView.setSourceLabel(ChannelRepository.displayName(this, ch.catalogSource));
        nativeView.setBackAction(nativeView::hideControls);
        nativeView.setDrawerChannels(channels, channelCatalogToken, ch.url, this::play,
                this::loadEpgForCurrentChannel);
        nativeView.setPlaybackState(Player.STATE_BUFFERING);
        playerView = nativeView;
        playerStage.addView(nativeView, new FrameLayout.LayoutParams(-1, -1));
        nativeView.setFocusable(true);
        if (fullscreenPlayback) nativeView.post(() -> {
            if (fullscreenPlayback && playerView == nativeView) nativeView.focusControls();
        });

        // A normal HTTP(S) URL from the M3U goes straight to Media3, regardless
        // of which catalogue supplied it. Only a VAVOO reference needs resolving.
        boolean vavooReference = safe(ch.vavooSourceUrl).startsWith("vavoo://")
                || safe(ch.url).startsWith("vavoo://")
                || safe(ch.url).startsWith("vavoo-catalog://")
                || !safe(ch.vavooSourceUrl).isEmpty();
        if (!vavooReference) {
            startMedia3Playback(ch, ch.url, nativeView);
            return;
        }

        String catalogReference = safe(ch.vavooSourceUrl);
        if (catalogReference.isEmpty()) catalogReference = safe(ch.url);
        final String selectedCatalogReference = catalogReference;
        String cachedStream = ResolvedStreamCache.get(selectedCatalogReference);
        if (cachedStream != null) {
            startMedia3Playback(ch, cachedStream, nativeView);
            return;
        }

        // Resolve this selected VAVOO entry only. The native spinner and controls
        // are visible while the request runs; no neighbouring channels are prefetched.
        io.execute(() -> {
            try {
                String resolved = VavooProvider.resolveStream(selectedCatalogReference);
                ResolvedStreamCache.put(selectedCatalogReference, resolved);
                runOnUiThread(() -> {
                    if (activityDestroyed || currentChannel != ch || !fullscreenPlayback
                            || playerView != nativeView || playerStage == null) return;
                    startMedia3Playback(ch, resolved, nativeView);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (activityDestroyed || currentChannel != ch || playerView != nativeView) return;
                    nativeView.setErrorMessage("Flux VAVOO indisponible. Réessaie cette chaîne plus tard.");
                    if (previewStatus != null) previewStatus.setText("Flux indisponible");
                });
            }
        });
    }

    private void startMedia3Playback(Channel ch, String streamUrl, NativeLivePlayerView nativeView) {
        if (playerStage == null || currentChannel != ch || playerView != nativeView) return;
        try {
            ExoPlayer active = LivePlaybackFactory.create(this,
                    ch.isVavoo ? "VAVOO/2.6" : "NISSKENZI+/2.6");
            player = active;
            nativeView.setPlayer(active);

            active.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (currentChannel != ch || playerView != nativeView || previewStatus == null) return;
                    nativeView.setPlaybackState(playbackState);
                    if (playbackState == Player.STATE_READY) {
                        previewStatus.setText("● EN DIRECT");
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        previewStatus.setText("Ouverture du direct…");
                    } else {
                        previewStatus.setText("Prêt à lire");
                    }
                }

                @Override public void onPlayerError(PlaybackException error) {
                    if (currentChannel != ch || playerView != nativeView) return;
                    if (!safe(ch.vavooSourceUrl).isEmpty()) ResolvedStreamCache.invalidate(ch.vavooSourceUrl);
                    nativeView.setErrorMessage("Flux indisponible. Essaie une autre chaîne.");
                    if (previewStatus != null) previewStatus.setText("Flux indisponible");
                    // This application intentionally uses one player only; errors are
                    // shown inline and never trigger an alternate-player pop-up.
                }
            });

            active.setMediaItem(LivePlaybackFactory.mediaItem(streamUrl, ch.name));
            active.prepare();
            active.setPlayWhenReady(true);
        } catch (Exception error) {
            nativeView.setErrorMessage("Impossible de démarrer ce flux.");
            if (previewStatus != null) previewStatus.setText("Démarrage impossible");
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
                if (activityDestroyed || currentChannel != requested || playerView != target
                        || !target.isChannelDrawerOpen()) return;
                target.setEpgInfo(result);
            });
        });
    }

    private void releaseMediaEngine() {
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (playerView != null) {
            try {
                playerView.setPlayer(null);
                if (playerStage != null) playerStage.removeView(playerView);
            } catch (Exception ignored) {
            }
            playerView = null;
        }
    }

    private void stopPreview() {
        releaseMediaEngine();
        currentChannel = null;
        if (channelAdapter != null) channelAdapter.setSelectedChannel(null);
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
        } else if ("Paramètres".equals(activePage)) {
            setPage("Live TV");
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
        super.onDestroy();
    }
}