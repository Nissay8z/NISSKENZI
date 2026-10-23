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

import java.util.List;

/**
 * Native fullscreen live-TV surface. It provides a custom TV controller, a
 * D-pad driven left channel drawer, and a programme-guide summary without
 * changing the underlying Media3 or VAVOO stream resolution logic.
 */
public final class NativeLivePlayerView extends FrameLayout {
    private static final int CYAN = 0xFF4CC9F0;
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = 0xFFD0D9E4;

    private final PlayerView videoView;
    private final ProgressBar loading;
    private final FrameLayout controlsLayer;
    private final FrameLayout drawerLayer;
    private final RecyclerView drawerRecycler;
    private final PlayerChannelDrawerAdapter drawerAdapter;
    private final TextView titleView;
    private final TextView sourceView;
    private final TextView stateView;
    private final TextView playPauseButton;
    private final TextView drawerNow;
    private final TextView drawerNext;
    private final TextView drawerEpgStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Runnable hideControlsTask;
    private Player player;
    private Runnable backAction;
    private Runnable drawerOpenAction;
    private PlayerChannelDrawerAdapter.OnChannelSelected channelSelectionAction;
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

        TextView backButton = button("‹  MASQUER", false);
        backButton.setContentDescription("Masquer les commandes du lecteur");
        topBar.addView(backButton, new LinearLayout.LayoutParams(-2, dp(42)));
        backButton.setOnClickListener(v -> {
            if (backAction != null) backAction.run();
            else hideControls();
        });

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
        livePill.setBackground(pill(0xCC8B1E32));
        topBar.addView(livePill, new LinearLayout.LayoutParams(-2, dp(38)));

        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(22), dp(28), dp(22), dp(17));
        bottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE610141B, 0x0010141B}));
        controlsLayer.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        stateView = new TextView(context);
        stateView.setText("Connexion au flux…");
        stateView.setTextColor(MUTED);
        stateView.setTextSize(13);
        bottomBar.addView(stateView, new LinearLayout.LayoutParams(0, -2, 1f));

        playPauseButton = button("Ⅱ  PAUSE", true);
        playPauseButton.setContentDescription("Mettre en pause ou reprendre la lecture");
        bottomBar.addView(playPauseButton, new LinearLayout.LayoutParams(-2, dp(48)));
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
        int drawerWidth = Math.min(dp(430), Math.max(dp(280),
                getResources().getDisplayMetrics().widthPixels - dp(48)));
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
        drawerSubtitle.setText("Sélectionne une chaîne · Gauche pour ouvrir");
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
        divider.setBackgroundColor(0x665D6D82);
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

        drawerEpgStatus = epgLine("Le guide se charge à l'ouverture du tiroir.", 0xFF8292A4);
        drawerEpgStatus.setTextSize(9);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(4);
        statusParams.bottomMargin = dp(10);
        drawerPanel.addView(drawerEpgStatus, statusParams);

        View listDivider = new View(context);
        listDivider.setBackgroundColor(0x443E4D5E);
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
        drawerPanel.addView(drawerRecycler, new LinearLayout.LayoutParams(-1, 0, 1f));

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
    public void setDrawerChannels(List<Channel> channels, int catalogVersion, String selectedUrl,
                                  PlayerChannelDrawerAdapter.OnChannelSelected onSelected,
                                  Runnable onOpen) {
        channelSelectionAction = onSelected;
        drawerOpenAction = onOpen;
        drawerAdapter.setChannels(channels, selectedUrl, catalogVersion);
    }

    public void setBackAction(Runnable action) {
        backAction = action;
    }

    public void setEpgInfo(EpgProvider.Result result) {
        if (result == null) {
            drawerNow.setText("En cours  ·  —");
            drawerNext.setText("Ensuite  ·  —");
            drawerEpgStatus.setText("Guide indisponible");
            return;
        }
        drawerNow.setText(result.now.isEmpty() ? "En cours  ·  Aucun programme trouvé"
                : "EN COURS  ·  " + result.now);
        drawerNext.setText(result.next.isEmpty() ? "ENSUITE  ·  —"
                : "ENSUITE  ·  " + result.next);
        drawerEpgStatus.setText(result.status.isEmpty() ? "Guide XMLTV" : result.status);
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

        drawerRecycler.post(() -> {
            if (!channelDrawerOpen) return;
            LinearLayoutManager manager = (LinearLayoutManager) drawerRecycler.getLayoutManager();
            int position = drawerAdapter.selectedPosition();
            if (manager != null) manager.scrollToPositionWithOffset(position, dp(8));
            drawerRecycler.post(() -> {
                if (!channelDrawerOpen) return;
                RecyclerView.ViewHolder holder = drawerRecycler.findViewHolderForAdapterPosition(position);
                if (holder != null) holder.itemView.requestFocus();
                else drawerRecycler.requestFocus();
            });
        });
    }

    public void closeChannelDrawer() {
        if (!channelDrawerOpen) return;
        channelDrawerOpen = false;
        drawerLayer.animate().cancel();
        drawerLayer.setVisibility(GONE);
        drawerLayer.setAlpha(1f);
        if (controlsVisibleBeforeDrawer) {
            showControls();
            playPauseButton.requestFocus();
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
        if (isFocused() && !controlsLayer.hasFocus()) playPauseButton.requestFocus();
        scheduleAutoHide();
    }

    /** Give the TV remote a predictable starting focus when fullscreen playback opens. */
    public void focusControls() {
        showControls();
        playPauseButton.requestFocus();
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
            if (channelDrawerOpen) {
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    closeChannelDrawer();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return true;
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
        button.setBackground(pill(primary ? 0xFF16495E : 0xCC202A36));
        button.setOnFocusChangeListener((view, focused) -> {
            button.setBackground(pill(focused ? 0xFF165D76 : (primary ? 0xFF16495E : 0xCC202A36)));
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
        drawable.setStroke(dp(1), 0x554CC9F0);
        return drawable;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xF6131B26);
        drawable.setCornerRadius(dp(15));
        drawable.setStroke(dp(1), 0x664CC9F0);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
