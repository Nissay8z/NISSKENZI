package tv.nisskenzi.moroccotv;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.List;

/** Lifecycle-safe bridge between MainActivity and the local M3U catalogue repository. */
public final class ChannelViewModel extends AndroidViewModel {
    private final ChannelRepository repository;

    public ChannelViewModel(@NonNull Application application) {
        super(application);
        repository = ChannelRepository.getInstance(application);
    }

    public LiveData<List<Channel>> getChannels() {
        return repository.observeChannels();
    }

    public LiveData<String> getStatus() {
        return repository.observeStatus();
    }

    public void initialize() {
        repository.initialize();
    }

    public void refreshAllSources() {
        repository.refreshAllAsync(true);
    }

    public void refreshIfNeeded() {
        repository.refreshAllAsync(false);
    }

    public void clearLocalDatabase() {
        repository.clearDatabaseAsync();
    }
}
