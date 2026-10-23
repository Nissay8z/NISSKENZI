package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.Locale;

/** One place for low-latency live playback defaults and stream-type detection. */
public final class LivePlaybackFactory {
    private LivePlaybackFactory() {}

    public static ExoPlayer create(Context context, String userAgent) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent == null ? "NISSKENZI+/2.1" : userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(15_000);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(
                context.getApplicationContext(), http);

        // A modest live buffer starts promptly without the long safety buffer used
        // for long-form video; streams with unstable networks may still rebuffer.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 30_000, 1_000, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        return new ExoPlayer.Builder(context.getApplicationContext())
                .setLoadControl(loadControl)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .build();
    }

    public static MediaItem mediaItem(String streamUrl, String title) {
        Uri uri = Uri.parse(streamUrl);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title == null ? "TV en direct" : title)
                        .build());

        if (path.endsWith(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (path.endsWith(".mpd")) {
            item.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        return item.build();
    }
}
