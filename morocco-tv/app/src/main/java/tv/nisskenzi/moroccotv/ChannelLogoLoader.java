package tv.nisskenzi.moroccotv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Async logo loader with a bounded queue, small memory cache and cancellable recycled-row requests. */
public final class ChannelLogoLoader {
    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    public static final class Request {
        private final ChannelLogoLoader owner;
        private final FutureTask<Bitmap> task;

        private Request(ChannelLogoLoader owner, FutureTask<Bitmap> task) {
            this.owner = owner;
            this.task = task;
        }

        public void cancel() {
            task.cancel(true);
            owner.executor.remove(task);
        }
    }

    private static final int MAX_CACHE_KB = 6 * 1024;
    private static final int MAX_BYTES = 1_500_000;
    private static final int MAX_EDGE = 160;

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(MAX_CACHE_KB) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private final ThreadPoolExecutor executor;
    private volatile boolean closed;

    public ChannelLogoLoader() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Nisskenzi-LogoLoader");
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(40), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    public Bitmap getCached(String url) {
        if (url == null || url.isEmpty() || closed) return null;
        return cache.get(url);
    }

    /** Returns null when the bounded queue is full; skipping an off-screen logo is
     * preferable to letting stale downloads queue indefinitely while the user scrolls. */
    public Request load(String url, Callback callback) {
        if (closed || url == null || url.trim().isEmpty() || callback == null) return null;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            callback.onLoaded(cached);
            return null;
        }

        FutureTask<Bitmap> task = new FutureTask<Bitmap>(() -> download(url)) {
            @Override protected void done() {
                if (isCancelled() || closed) return;
                try {
                    Bitmap bitmap = get();
                    if (bitmap == null || closed) return;
                    cache.put(url, bitmap);
                    callback.onLoaded(bitmap);
                } catch (Exception ignored) {
                }
            }
        };
        try {
            executor.execute(task);
            return new Request(this, task);
        } catch (RejectedExecutionException ignored) {
            task.cancel(true);
            return null;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closed = true;
        executor.getQueue().clear();
        executor.shutdownNow();
        cache.evictAll();
    }

    private Bitmap download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(3500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NISSKENZI+/2.1 (Android TV)");

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) return null;
                    total += count;
                    if (total > MAX_BYTES) return null;
                    output.write(buffer, 0, count);
                }

                byte[] bytes = output.toByteArray();
                if (bytes.length == 0) return null;

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

                int sample = 1;
                while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
