package tv.nisskenzi.moroccotv;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps favorite membership in memory instead of querying SharedPreferences for
 * every channel while building category counts or filtering a large catalogue. */
public final class FavoritesStore {
    private final SharedPreferences preferences;
    private final HashSet<String> favoriteUrls = new HashSet<>();
    private long revision = 0L;

    public FavoritesStore(SharedPreferences preferences) {
        this.preferences = preferences;
        reload();
    }

    public synchronized void reload() {
        favoriteUrls.clear();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) favoriteUrls.add(entry.getKey());
        }
        revision++;
    }

    public synchronized boolean contains(String url) {
        return url != null && favoriteUrls.contains(url);
    }

    public synchronized boolean toggle(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        boolean nowFavorite = !favoriteUrls.contains(url);
        setFavorite(url, nowFavorite);
        return nowFavorite;
    }

    /** Adds or removes an ID without toggling legacy source IDs by accident. */
    public synchronized void setFavorite(String url, boolean favorite) {
        if (url == null || url.trim().isEmpty()) return;
        boolean changed = favorite ? favoriteUrls.add(url) : favoriteUrls.remove(url);
        if (!changed) return;
        if (favorite) preferences.edit().putBoolean(url, true).apply();
        else preferences.edit().remove(url).apply();
        revision++;
    }

    public synchronized Set<String> snapshotUrls() {
        return new HashSet<>(favoriteUrls);
    }

    public synchronized long revision() { return revision; }

    public synchronized int countIn(List<Channel> channels) {
        int count = 0;
        if (channels == null) return count;
        for (Channel channel : channels) {
            if (channel != null && channel.matchesSavedIds(favoriteUrls)) count++;
        }
        return count;
    }
}
