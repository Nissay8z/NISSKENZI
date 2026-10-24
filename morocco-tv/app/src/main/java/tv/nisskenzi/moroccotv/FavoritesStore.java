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
        boolean nowFavorite;
        if (favoriteUrls.remove(url)) {
            nowFavorite = false;
            preferences.edit().remove(url).apply();
        } else {
            favoriteUrls.add(url);
            nowFavorite = true;
            preferences.edit().putBoolean(url, true).apply();
        }
        revision++;
        return nowFavorite;
    }

    public synchronized Set<String> snapshotUrls() {
        return new HashSet<>(favoriteUrls);
    }

    public synchronized long revision() { return revision; }

    public synchronized int countIn(List<Channel> channels) {
        int count = 0;
        if (channels == null) return count;
        for (Channel channel : channels) {
            if (channel != null && (favoriteUrls.contains(channel.url) || channel.matchesAnyUrl(favoriteUrls))) count++;
        }
        return count;
    }
}
