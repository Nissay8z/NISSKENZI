package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native fullscreen live-TV surface. It provides a custom TV controller, a
 * D-pad driven left channel drawer, and a programme-guide summary without
 * changing the underlying Media3 or VAVOO stream resolution logic.
 */
public final class NativeLivePlayerView extends FrameLayout {
    private static final int CYAN = 0xFFEA5265;
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = 0xFFE0DDE0;

    private final PlayerView videoView;
    private final ProgressBar loading;
    private final FrameLayout controlsLayer;
    private final FrameLayout drawerLayer;
    private final RecyclerView drawerRecycler;
    private final PlayerChannelDrawerAdapter drawerAdapter;
    private final LinearLayout drawerCountryColumn;
    private final LinearLayout drawerCountryList;
    private final Map<String, CountryButton> drawerCountryButtons = new LinkedHashMap<>();
    private final Map<String, Object> drawerIndexTokens = new HashMap<>();
    private final ArrayList<Channel> allDrawerChannels = new ArrayList<>();
    private final TextView titleView;
    private final TextView sourceView;
    private final TextView stateView;
    private final TextView playPauseButton;
    private final TextView channelListButton;
    private final TextView channelDownButton;
    private final TextView channelUpButton;
    private final TextView dossierButton;
    private final TextView currentProgramView;
    private final TextView nextProgramView;
    private final TextView drawerNow;
    private final TextView drawerNext;
    private final TextView drawerEpgStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Runnable hideControlsTask;
    private Player player;
    private Runnable drawerOpenAction;
    private Runnable previousChannelAction;
    private Runnable nextChannelAction;
    private PlayerChannelDrawerAdapter.OnChannelSelected channelSelectionAction;
    private Runnable sourceFolderAction;
    private Object drawerCatalogToken;
    private String drawerSelectedUrl = "";
    private String activeDrawerCountry = "";
    private boolean controlsVisible = true;
    private boolean controlsVisibleBeforeDrawer = true;
    private boolean channelDrawerOpen = false;
    private boolean playing = false;

    public NativeLivePlayerView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClipChildren(false);
        setClipToPadding(false);

        videoView = new PlayerView(context);
        videoView.setUseController(false);
        videoView.setKeepContentOnPlayerReset(true);
        videoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoView.setShutterBackgroundColor(Color.BLACK);
        videoView.setFocusable(false);
        videoView.setFocusableInTouchMode(false);
        addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(context);
        loading.setIndeterminate(true);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(CYAN));
        addView(loading, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));

        controlsLayer = new FrameLayout(context);
        addView(controlsLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(22), dp(16), dp(22), dp(25));
        topBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE610141B, 0x0010141B}));
        controlsLayer.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        TextView channelButton = button("☰  CHAÎNES", false);
        channelButton.setContentDescription("Ouvrir le guide et la liste des chaînes");
        topBar.addView(channelButton, new LinearLayout.LayoutParams(-2, dp(42)));
        channelButton.setOnClickListener(v -> openChannelDrawer());

        LinearLayout titleColumn = new LinearLayout(context);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(16), 0, dp(10), 0);
        topBar.addView(titleColumn, new LinearLayout.LayoutParams(0, -2, 1f));

        titleView = new TextView(context);
        titleView.setText("Lecture en direct");
        titleView.setTextColor(WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleColumn.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        sourceView = new TextView(context);
        sourceView.setText("NISSKENZI+  •  TV EN DIRECT");
        sourceView.setTextColor(MUTED);
        sourceView.setTextSize(11);
        sourceView.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(-1, -2);
        sourceParams.topMargin = dp(3);
        titleColumn.addView(sourceView, sourceParams);

        TextView livePill = new TextView(context);
        livePill.setText("●  LIVE");
        livePill.setTextColor(WHITE);
        livePill.setTextSize(12);
        livePill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        livePill.setGravity(Gravity.CENTER);
        livePill.setPadding(dp(13), dp(8), dp(13), dp(8));
        livePill.setBackground(pill(0xCCE5445A));
        topBar.addView(livePill, new LinearLayout.LayoutParams(-2, dp(38)));

        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(30), dp(36), dp(30), dp(18));
        bottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF010141B, 0x0010141B}));
        controlsLayer.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        currentProgramView = new TextView(context);
        currentProgramView.setText("GUIDE TV  ·  Ouvrez « Chaînes » pour afficher le programme");
        currentProgramView.setTextColor(WHITE);
        currentProgramView.setTextSize(16);
        currentProgramView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        currentProgramView.setSingleLine(true);
        currentProgramView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottomBar.addView(currentProgramView, new LinearLayout.LayoutParams(-1, -2));

        nextProgramView = new TextView(context);
        nextProgramView.setText("En cours  ·  Guide disponible dans la liste TV");
        nextProgramView.setTextColor(MUTED);
        nextProgramView.setTextSize(12);
        nextProgramView.setSingleLine(true);
        nextProgramView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nextProgramParams = new LinearLayout.LayoutParams(-1, -2);
        nextProgramParams.topMargin = dp(5);
        nextProgramParams.bottomMargin = dp(12);
        bottomBar.addView(nextProgramView, nextProgramParams);

        LinearLayout controlRow = new LinearLayout(context);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.addView(controlRow, new LinearLayout.LayoutParams(-1, -2));

        stateView = new TextView(context);
        stateView.setText("Connexion au flux…");
        stateView.setTextColor(MUTED);
        stateView.setTextSize(13);
        controlRow.addView(stateView, new LinearLayout.LayoutParams(0, -2, 1f));

        channelListButton = button("☰  GUIDE & CHAÎNES", false);
        channelListButton.setContentDescription("Ouvrir le guide et la liste des chaînes");
        LinearLayout.LayoutParams listButtonParams = new LinearLayout.LayoutParams(-2, dp(48));
        listButtonParams.rightMargin = dp(10);
        controlRow.addView(channelListButton, listButtonParams);
        channelListButton.setOnClickListener(v -> openChannelDrawer());

        dossierButton = button("▤  FLUX", false);
        dossierButton.setContentDescription("Dossier des sources de la chaîne");
        dossierButton.setVisibility(GONE);
        LinearLayout.LayoutParams dossierParams = new LinearLayout.LayoutParams(-2, dp(48));
        dossierParams.rightMargin = dp(7);
        controlRow.addView(dossierButton, dossierParams);
        dossierButton.setOnClickListener(v -> {
            if (sourceFolderAction != null) sourceFolderAction.run();
        });

        channelDownButton = button("CH −", false);
        channelDownButton.setContentDescription("Chaîne précédente");
        LinearLayout.LayoutParams channelDownParams = new LinearLayout.LayoutParams(-2, dp(48));
        channelDownParams.rightMargin = dp(7);
        controlRow.addView(channelDownButton, channelDownParams);
        channelDownButton.setOnClickListener(v -> {
            if (previousChannelAction != null) previousChannelAction.run();
            showControls();
        });

        channelUpButton = button("CH +", false);
        channelUpButton.setContentDescription("Chaîne suivante");
        LinearLayout.LayoutParams channelUpParams = new LinearLayout.LayoutParams(-2, dp(48));
        channelUpParams.rightMargin = dp(10);
        controlRow.addView(channelUpButton, channelUpParams);
        channelUpButton.setOnClickListener(v -> {
            if (nextChannelAction != null) nextChannelAction.run();
            showControls();
        });

        playPauseButton = button("Ⅱ  PAUSE", true);
        playPauseButton.setContentDescription("Mettre en pause ou reprendre la lecture");
        controlRow.addView(playPauseButton, new LinearLayout.LayoutParams(-2, dp(48)));
        playPauseButton.setOnClickListener(v -> togglePlayback());

        drawerLayer = new FrameLayout(context);
        drawerLayer.setVisibility(GONE);
        drawerLayer.setElevation(dp(18));
        addView(drawerLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(context);
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> closeChannelDrawer());
        drawerLayer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout drawerPanel = new LinearLayout(context);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(17), dp(16), dp(13), dp(13));
        drawerPanel.setBackground(panelBackground());
        drawerPanel.setClickable(true);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int drawerWidth = Math.min(dp(760), Math.max(dp(540), screenWidth * 3 / 5));
        drawerWidth = Math.min(drawerWidth, Math.max(dp(280), screenWidth - dp(24)));
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        drawerLayer.addView(drawerPanel, drawerParams);

        LinearLayout drawerTitleRow = new LinearLayout(context);
        drawerTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        drawerTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        drawerPanel.addView(drawerTitleRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout drawerTitles = new LinearLayout(context);
        drawerTitles.setOrientation(LinearLayout.VERTICAL);
        drawerTitleRow.addView(drawerTitles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView drawerTitle = new TextView(context);
        drawerTitle.setText("CHAÎNES EN DIRECT");
        drawerTitle.setTextColor(WHITE);
        drawerTitle.setTextSize(16);
        drawerTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        drawerTitles.addView(drawerTitle, new LinearLayout.LayoutParams(-1, -2));

        TextView drawerSubtitle = new TextView(context);
        drawerSubtitle.setText("Choisis un pays à gauche, puis une chaîne");
        drawerSubtitle.setTextColor(MUTED);
        drawerSubtitle.setTextSize(10);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(4);
        drawerTitles.addView(drawerSubtitle, subParams);

        TextView closeButton = button("✕", false);
        closeButton.setContentDescription("Fermer le tiroir des chaînes");
        drawerTitleRow.addView(closeButton, new LinearLayout.LayoutParams(dp(44), dp(40)));
        closeButton.setOnClickListener(v -> closeChannelDrawer());

        View divider = new View(context);
        divider.setBackgroundColor(0x44FFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(12);
        dividerParams.bottomMargin = dp(10);
        drawerPanel.addView(divider, dividerParams);

        TextView epgHeading = new TextView(context);
        epgHeading.setText("PROGRAMME TV");
        epgHeading.setTextColor(CYAN);
        epgHeading.setTextSize(10);
        epgHeading.setLetterSpacing(0.1f);
        epgHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        drawerPanel.addView(epgHeading, new LinearLayout.LayoutParams(-1, -2));

        drawerNow = epgLine("En cours  ·  —", WHITE);
        LinearLayout.LayoutParams nowParams = new LinearLayout.LayoutParams(-1, -2);
        nowParams.topMargin = dp(5);
        drawerPanel.addView(drawerNow, nowParams);

        drawerNext = epgLine("Ensuite  ·  —", MUTED);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(-1, -2);
        nextParams.topMargin = dp(4);
        drawerPanel.addView(drawerNext, nextParams);

        drawerEpgStatus = epgLine("Le guide se charge à l'ouverture du tiroir.", 0xFF9A9AA3);
        drawerEpgStatus.setTextSize(9);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(4);
        statusParams.bottomMargin = dp(10);
        drawerPanel.addView(drawerEpgStatus, statusParams);

        View listDivider = new View(context);
        listDivider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams listDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        listDividerParams.bottomMargin = dp(8);
        drawerPanel.addView(listDivider, listDividerParams);

        drawerRecycler = new RecyclerView(context);
        drawerRecycler.setLayoutManager(new LinearLayoutManager(context));
        drawerRecycler.setItemAnimator(null);
        drawerRecycler.setHasFixedSize(true);
        drawerRecycler.setItemViewCacheSize(5);
        drawerAdapter = new PlayerChannelDrawerAdapter(channel -> {
            closeChannelDrawer();
            if (channelSelectionAction != null) channelSelectionAction.onChannelSelected(channel);
        });
        drawerRecycler.setAdapter(drawerAdapter);

        LinearLayout drawerBrowser = new LinearLayout(context);
        drawerBrowser.setOrientation(LinearLayout.HORIZONTAL);
        drawerBrowser.setGravity(Gravity.FILL);
        drawerPanel.addView(drawerBrowser, new LinearLayout.LayoutParams(-1, 0, 1f));

        drawerCountryColumn = new LinearLayout(context);
        drawerCountryColumn.setOrientation(LinearLayout.VERTICAL);
        drawerCountryColumn.setPadding(0, 0, dp(8), 0);
        drawerBrowser.addView(drawerCountryColumn,
                new LinearLayout.LayoutParams(dp(148), -1));

        TextView countryHeading = new TextView(context);
        countryHeading.setText("PAYS");
        countryHeading.setTextColor(CYAN);
        countryHeading.setTextSize(10);
        countryHeading.setLetterSpacing(0.1f);
        countryHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams countryHeadingParams = new LinearLayout.LayoutParams(-1, -2);
        countryHeadingParams.bottomMargin = dp(6);
        drawerCountryColumn.addView(countryHeading, countryHeadingParams);

        ScrollView countryScroll = new ScrollView(context);
        countryScroll.setVerticalScrollBarEnabled(false);
        drawerCountryList = new LinearLayout(context);
        drawerCountryList.setOrientation(LinearLayout.VERTICAL);
        drawerCountryList.setClipChildren(false);
        drawerCountryList.setClipToPadding(false);
        countryScroll.addView(drawerCountryList);
        drawerCountryColumn.addView(countryScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        View browserDivider = new View(context);
        browserDivider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams browserDividerParams =
                new LinearLayout.LayoutParams(dp(1), -1);
        browserDividerParams.rightMargin = dp(9);
        drawerBrowser.addView(browserDivider, browserDividerParams);

        drawerBrowser.addView(drawerRecycler, new LinearLayout.LayoutParams(0, -1, 1f));

        setOnClickListener(v -> showControls());
        scheduleAutoHide();
    }

    public void setPlayer(Player nextPlayer) {
        player = nextPlayer;
        videoView.setPlayer(nextPlayer);
        updatePlayPauseLabel();
    }

    public void setChannelName(String name) {
        titleView.setText(name == null || name.trim().isEmpty() ? "Lecture en direct" : name.trim());
        titleView.setContentDescription(titleView.getText());
    }

    public void setSourceLabel(String source) {
        sourceView.setText("NISSKENZI+  •  " + (source == null ? "TV EN DIRECT" : source));
    }

    /** The player drawer uses the existing source catalogue and only virtualizes visible rows. */
    public void setDrawerChannels(List<Channel> channels, Object catalogToken, String selectedUrl,
                                  PlayerChannelDrawerAdapter.OnChannelSelected onSelected,
                                  Runnable onOpen) {
        channelSelectionAction = onSelected;
        drawerOpenAction = onOpen;
        if (drawerCatalogToken != catalogToken) {
            drawerCatalogToken = catalogToken;
            drawerIndexTokens.clear();
        }
        allDrawerChannels.clear();
        if (channels != null) allDrawerChannels.addAll(channels);
        drawerSelectedUrl = safe(selectedUrl);
        if (!activeDrawerCountry.isEmpty() && !containsDrawerCountry(activeDrawerCountry)) {
            activeDrawerCountry = "";
        }
        renderDrawerCountries();
        applyDrawerCountryFilter();
    }

    public void setCurrentChannel(Channel channel) {
        int count = channel == null ? 1 : channel.sourceCount();
        dossierButton.setVisibility(VISIBLE);
        dossierButton.setText("▤  FLUX (" + count + ")");
        dossierButton.setContentDescription("Dossier de chaîne, " + count + " flux");
        dossierButton.setEnabled(count > 1);
        dossierButton.setAlpha(count > 1 ? 1f : 0.58f);
    }

    public void setSourceFolderAction(Runnable action) {
        sourceFolderAction = action;
    }

    public void setChannelZapActions(Runnable previous, Runnable next) {
        previousChannelAction = previous;
        nextChannelAction = next;
    }

    public void setEpgInfo(EpgProvider.Result result) {
        if (result != null && result.status.contains("Chargement")) {
            drawerNow.setText("En cours  ·  Recherche du programme…");
            drawerNext.setText("Ensuite  ·  Le guide est en cours de chargement");
            drawerEpgStatus.setText(result.status);
            currentProgramView.setText("GUIDE TV  ·  recherche du programme…");
            nextProgramView.setText("Le direct continue pendant le chargement.");
            return;
        }
        if (result == null) {
            drawerNow.setText("En cours  ·  —");
            drawerNext.setText("Ensuite  ·  —");
            drawerEpgStatus.setText("Guide indisponible");
            currentProgramView.setText("GUIDE TV  ·  indisponible");
            nextProgramView.setText("Essayez une autre source pour ce programme.");
            return;
        }
        String now = result.now.isEmpty() ? "Aucun programme en cours" : result.now;
        String next = result.next.isEmpty() ? "Aucun programme ensuite" : result.next;
        drawerNow.setText(result.now.isEmpty() ? "En cours  ·  Aucun programme trouvé"
                : "EN COURS  ·  " + result.now);
        drawerNext.setText(result.next.isEmpty() ? "ENSUITE  ·  —"
                : "ENSUITE  ·  " + result.next);
        drawerEpgStatus.setText(result.status.isEmpty() ? "Guide XMLTV" : result.status);
        currentProgramView.setText("EN COURS  ·  " + now);
        nextProgramView.setText("ENSUITE  ·  " + next);
    }

    public void setPlaybackState(int playbackState) {
        if (playbackState == Player.STATE_BUFFERING) {
            loading.setVisibility(VISIBLE);
            stateView.setText("Connexion · mise en mémoire tampon…");
        } else if (playbackState == Player.STATE_READY) {
            loading.setVisibility(GONE);
            stateView.setText("Flux prêt · lecture en direct");
        } else if (playbackState == Player.STATE_ENDED) {
            loading.setVisibility(GONE);
            stateView.setText("Flux terminé");
        } else {
            loading.setVisibility(GONE);
            stateView.setText("Prêt à lire");
        }
        updatePlayPauseLabel();
    }

    public void setErrorMessage(String message) {
        loading.setVisibility(GONE);
        stateView.setText(message == null || message.trim().isEmpty()
                ? "Lecture impossible" : message);
        showControls();
    }

    public boolean isChannelDrawerOpen() {
        return channelDrawerOpen;
    }

    public boolean areControlsVisible() {
        return controlsVisible;
    }

    public void openChannelDrawer() {
        if (channelDrawerOpen) return;
        channelDrawerOpen = true;
        controlsVisibleBeforeDrawer = controlsVisible;
        drawerLayer.setVisibility(VISIBLE);
        drawerLayer.setAlpha(0f);
        drawerLayer.animate().cancel();
        drawerLayer.animate().alpha(1f).setDuration(140).start();
        if (hideControlsTask != null) handler.removeCallbacks(hideControlsTask);
        if (drawerOpenAction != null) drawerOpenAction.run();

        drawerRecycler.post(this::focusDrawerChannel);
    }

    public void closeChannelDrawer() {
        if (!channelDrawerOpen) return;
        channelDrawerOpen = false;
        drawerLayer.animate().cancel();
        drawerLayer.setVisibility(GONE);
        drawerLayer.setAlpha(1f);
        if (controlsVisibleBeforeDrawer) {
            showControls();
            channelListButton.requestFocus();
        } else {
            controlsVisible = false;
            controlsLayer.animate().cancel();
            controlsLayer.setAlpha(0f);
            controlsLayer.setVisibility(INVISIBLE);
            requestFocus();
        }
    }

    /**
     * Back hierarchy: close drawer first, hide the bars second, and only then
     * let MainActivity leave fullscreen playback.
     */
    public boolean handleBackPressed() {
        if (channelDrawerOpen) {
            closeChannelDrawer();
            return true;
        }
        if (controlsVisible) {
            hideControls();
            return true;
        }
        return false;
    }

    public void showControls() {
        if (channelDrawerOpen) return;
        controlsVisible = true;
        controlsLayer.setVisibility(VISIBLE);
        controlsLayer.animate().cancel();
        controlsLayer.setAlpha(1f);
        if (isFocused() && !controlsLayer.hasFocus()) channelListButton.requestFocus();
        scheduleAutoHide();
    }

    /** Give the TV remote a predictable starting focus when fullscreen playback opens. */
    public void focusControls() {
        showControls();
        channelListButton.requestFocus();
    }

    public void hideControls() {
        if (channelDrawerOpen) return;
        if (!controlsVisible) return;
        if (controlsLayer.hasFocus()) requestFocus();
        controlsVisible = false;
        controlsLayer.animate().cancel();
        controlsLayer.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (!controlsVisible && !channelDrawerOpen) controlsLayer.setVisibility(INVISIBLE);
        }).start();
    }

    private void scheduleAutoHide() {
        if (hideControlsTask != null) handler.removeCallbacks(hideControlsTask);
        hideControlsTask = this::hideControls;
        handler.postDelayed(hideControlsTask, 5500L);
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
        playing = player.getPlayWhenReady();
        updatePlayPauseLabel();
        showControls();
    }

    private void updatePlayPauseLabel() {
        playing = player != null && player.getPlayWhenReady();
        if (playPauseButton != null) {
            playPauseButton.setText(playing ? "Ⅱ  PAUSE" : "▶  REPRENDRE");
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !channelDrawerOpen) {
                togglePlayback();
                showControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_CHANNEL_UP && !channelDrawerOpen
                    && nextChannelAction != null) {
                nextChannelAction.run();
                showControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_CHANNEL_DOWN && !channelDrawerOpen
                    && previousChannelAction != null) {
                previousChannelAction.run();
                showControls();
                return true;
            }
            if (channelDrawerOpen) {
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (drawerCountryColumn.hasFocus()) focusDrawerChannel();
                    else closeChannelDrawer();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (drawerRecycler.hasFocus()) focusDrawerCountry();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                openChannelDrawer();
                return true;
            }

            boolean navigationKey = key == KeyEvent.KEYCODE_DPAD_UP
                    || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER;
            if (navigationKey && !controlsVisible) {
                showControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                showControls();
                togglePlayback();
                return true;
            }
            if (navigationKey) showControls();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        if (hideControlsTask != null) handler.removeCallbacks(hideControlsTask);
        super.onDetachedFromWindow();
    }

    private void renderDrawerCountries() {
        drawerCountryList.removeAllViews();
        drawerCountryButtons.clear();

        addDrawerCountryButton("", "Tous les pays", allDrawerChannels.size());
        Map<String, CountryCount> counts = new HashMap<>();
        for (Channel channel : allDrawerChannels) {
            if (channel == null) continue;
            String key = drawerCountryKey(channel.country);
            CountryCount count = counts.get(key);
            if (count == null) {
                count = new CountryCount(key, drawerCountryName(channel.country));
                counts.put(key, count);
            }
            count.count++;
        }
        ArrayList<CountryCount> sorted = new ArrayList<>(counts.values());
        Collections.sort(sorted, (left, right) ->
                left.name.compareToIgnoreCase(right.name));
        for (CountryCount country : sorted) {
            addDrawerCountryButton(country.key, country.name, country.count);
        }
        updateDrawerCountrySelection();
    }

    private void addDrawerCountryButton(String key, String name, int count) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(7), dp(6));
        row.setFocusable(true);
        row.setClickable(true);

        TextView title = new TextView(getContext());
        title.setText((key.isEmpty() ? "" : flagForCountryKey(key) + "  ") + name);
        title.setTextColor(MUTED);
        title.setTextSize(11);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView number = new TextView(getContext());
        number.setText(String.valueOf(count));
        number.setTextColor(0xFF9FA1AB);
        number.setTextSize(9);
        number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(number, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(40));
        rowParams.bottomMargin = dp(4);
        drawerCountryList.addView(row, rowParams);
        CountryButton countryButton = new CountryButton(row, title, number);
        drawerCountryButtons.put(key, countryButton);
        row.setOnFocusChangeListener((view, focused) -> {
            styleDrawerCountryButton(key, countryButton, focused);
            if (focused) showControls();
        });
        row.setOnClickListener(view -> {
            activeDrawerCountry = key;
            updateDrawerCountrySelection();
            applyDrawerCountryFilter();
        });
        styleDrawerCountryButton(key, countryButton, false);
    }

    private void styleDrawerCountryButton(String key, CountryButton button, boolean focused) {
        boolean selected = key.equals(activeDrawerCountry);
        boolean active = selected || focused;
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? 0xFF35222A : 0xFF202126);
        background.setCornerRadius(dp(9));
        background.setStroke(dp(1), focused ? CYAN : (selected ? 0xFF74414D : 0x003C4654));
        button.row.setBackground(background);
        button.row.setScaleX(focused ? 1.025f : 1f);
        button.row.setScaleY(focused ? 1.025f : 1f);
        button.title.setTextColor(active ? WHITE : MUTED);
        button.count.setTextColor(active ? 0xFFFFB3BE : 0xFF9FA1AB);
    }

    private void updateDrawerCountrySelection() {
        for (Map.Entry<String, CountryButton> entry : drawerCountryButtons.entrySet()) {
            styleDrawerCountryButton(entry.getKey(), entry.getValue(),
                    entry.getValue().row.hasFocus());
        }
    }

    private void applyDrawerCountryFilter() {
        ArrayList<Channel> filtered = new ArrayList<>();
        for (Channel channel : allDrawerChannels) {
            if (channel == null) continue;
            if (activeDrawerCountry.isEmpty()
                    || activeDrawerCountry.equals(drawerCountryKey(channel.country))) {
                filtered.add(channel);
            }
        }
        Object indexToken = drawerIndexTokens.get(activeDrawerCountry);
        if (indexToken == null) {
            indexToken = new Object();
            drawerIndexTokens.put(activeDrawerCountry, indexToken);
        }
        drawerAdapter.setChannels(filtered, drawerSelectedUrl, indexToken);
    }

    private boolean containsDrawerCountry(String key) {
        for (Channel channel : allDrawerChannels) {
            if (channel != null && key.equals(drawerCountryKey(channel.country))) return true;
        }
        return false;
    }

    private String drawerCountryKey(String country) {
        String code = ChannelCatalogPolicy.countryCode(country);
        if (!code.isEmpty()) return code;
        String normalized = Channel.normalizeSearch(country);
        return normalized.isEmpty() ? "_unknown_" : "name:" + normalized;
    }

    private String drawerCountryName(String country) {
        String code = ChannelCatalogPolicy.countryCode(country);
        if (!code.isEmpty()) {
            String localized = new java.util.Locale("", code).getDisplayCountry(java.util.Locale.FRENCH);
            if (localized != null && !localized.trim().isEmpty()) return localized;
        }
        String value = country == null ? "" : country.trim();
        return value.isEmpty() ? "Pays non précisé" : value;
    }

    private String flagForCountryKey(String key) {
        if (key == null || key.length() != 2) return "🌐";
        String upper = key.toUpperCase(java.util.Locale.ROOT);
        int first = 0x1F1E6 + upper.charAt(0) - 'A';
        int second = 0x1F1E6 + upper.charAt(1) - 'A';
        if (first < 0x1F1E6 || first > 0x1F1FF || second < 0x1F1E6 || second > 0x1F1FF) {
            return "🌐";
        }
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    private void focusDrawerCountry() {
        CountryButton selected = drawerCountryButtons.get(activeDrawerCountry);
        if (selected == null) selected = drawerCountryButtons.get("");
        if (selected != null) selected.row.requestFocus();
        else drawerCountryColumn.requestFocus();
    }

    private void focusDrawerChannel() {
        if (!channelDrawerOpen) return;
        LinearLayoutManager manager = (LinearLayoutManager) drawerRecycler.getLayoutManager();
        int position = drawerAdapter.getItemCount() == 0 ? -1 : drawerAdapter.selectedPosition();
        if (position < 0) {
            focusDrawerCountry();
            return;
        }
        if (manager != null) manager.scrollToPositionWithOffset(position, dp(8));
        drawerRecycler.post(() -> {
            if (!channelDrawerOpen) return;
            RecyclerView.ViewHolder holder = drawerRecycler.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
            else focusDrawerCountry();
        });
    }

    private static final class CountryCount {
        final String key;
        final String name;
        int count;
        CountryCount(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    private static final class CountryButton {
        final LinearLayout row;
        final TextView title;
        final TextView count;
        CountryButton(LinearLayout row, TextView title, TextView count) {
            this.row = row;
            this.title = title;
            this.count = count;
        }
    }

    private TextView epgLine(String text, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(11);
        view.setSingleLine(false);
        view.setMaxLines(2);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private TextView button(String text, boolean primary) {
        TextView button = new TextView(getContext());
        button.setText(text);
        button.setTextColor(WHITE);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(pill(primary ? 0xFF3B2027 : 0xCC202A36));
        button.setOnFocusChangeListener((view, focused) -> {
            button.setBackground(pill(focused ? 0xFF58303A : (primary ? 0xFF3B2027 : 0xCC202A36)));
            button.setScaleX(focused ? 1.04f : 1f);
            button.setScaleY(focused ? 1.04f : 1f);
            if (focused) showControls();
        });
        return button;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), 0x55EA5265);
        return drawable;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xF6191A20);
        drawable.setCornerRadius(dp(15));
        drawable.setStroke(dp(1), 0x66EA5265);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
