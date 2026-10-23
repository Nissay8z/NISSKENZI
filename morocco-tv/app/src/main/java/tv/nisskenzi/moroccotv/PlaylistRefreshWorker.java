package tv.nisskenzi.moroccotv;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** WorkManager task: refresh M3U sources periodically without occupying the UI thread. */
public final class PlaylistRefreshWorker extends Worker {
    public PlaylistRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            boolean healthy = ChannelRepository.getInstance(getApplicationContext())
                    .refreshStaleSourcesBlocking();
            return healthy ? Result.success() : Result.retry();
        } catch (Exception error) {
            return Result.retry();
        }
    }
}
