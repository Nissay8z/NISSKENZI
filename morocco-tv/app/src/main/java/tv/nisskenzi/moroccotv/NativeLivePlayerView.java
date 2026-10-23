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

/**
 * Remote-friendly native live player UI. Media3 renders the stream; this class
 * owns only the on-screen controls and keeps them separate from playback logic.
 */
public final class NativeLivePlayerView extends FrameLayout {
    private static final int CYAN = 0xFF4CC9F0;
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = 0xFFD0D9E4;
    private final PlayerView videoView;
    private final FrameLayout controlsLayer;
    private final LinearLayout topBar;
    private final LinearLayout bottomBar;
    private final TextView titleView;
    private final TextView sourceView;
    private final TextView stateView;
    private final TextView playPauseButton;
    private final ProgressBar loading;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsTask;
    private Player player;
    private Runnable backAction;
    private boolean controlsVisible = true;
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

        controlsLayer = new FrameLayout(context);
        addView(controlsLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(22), dp(16), dp(22), dp(25));
        topBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE610141B, 0x0010141B}));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        controlsLayer.addView(topBar, topParams);

        TextView backButton = button("‹  RETOUR", false);
        backButton.setContentDescription("Retour à la liste des chaînes");
        topBar.addView(backButton, new LinearLayout.LayoutParams(-2, dp(42)));
        backButton.setOnClickListener(v -> {
            if (backAction != null) backAction.run();
        });

        LinearLayout titleColumn = new LinearLayout(context);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(16), 0, dp(10), 0);
        LinearLayout.LayoutParams titleColumnParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        topBar.addView(titleColumn, titleColumnParams);

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

        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(22), dp(28), dp(22), dp(17));
        bottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE610141B, 0x0010141B}));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        controlsLayer.addView(bottomBar, bottomParams);

        stateView = new TextView(context);
        stateView.setText("Connexion au flux…");
        stateView.setTextColor(MUTED);
        stateView.setTextSize(13);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(0, -2, 1f);
        bottomBar.addView(stateView, stateParams);

        playPauseButton = button("Ⅱ  PAUSE", true);
        playPauseButton.setContentDescription("Mettre en pause ou reprendre la lecture");
        bottomBar.addView(playPauseButton, new LinearLayout.LayoutParams(-2, dp(48)));
        playPauseButton.setOnClickListener(v -> togglePlayback());

        loading = new ProgressBar(context);
        loading.setIndeterminate(true);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(CYAN));
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER);
        addView(loading, loadingParams);

        setOnClickListener(v -> showControls());
        setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                togglePlayback();
                showControls();
                return true;
            }
            if (!controlsVisible && (keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER)) {
                showControls();
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    togglePlayback();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                showControls();
                togglePlayback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                showControls();
            }
            return false;
        });
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

    public void setBackAction(Runnable action) {
        backAction = action;
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

    public void showControls() {
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
        if (controlsLayer.hasFocus()) requestFocus();
        controlsVisible = false;
        controlsLayer.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (!controlsVisible) controlsLayer.setVisibility(INVISIBLE);
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
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                togglePlayback();
                showControls();
                return true;
            }
            boolean navigationKey = key == KeyEvent.KEYCODE_DPAD_UP
                    || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER;
            if (navigationKey && !controlsVisible) {
                showControls();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        if (hideControlsTask != null) handler.removeCallbacks(hideControlsTask);
        super.onDetachedFromWindow();
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
