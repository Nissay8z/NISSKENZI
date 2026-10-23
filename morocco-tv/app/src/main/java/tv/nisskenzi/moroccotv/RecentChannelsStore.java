package tv.nisskenzi.moroccotv;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-memory recent-history index backed by SharedPreferences. */
public final class RecentChannelsStore {
    private static final int MAX_RECENT = 100;
    private final SharedPreferences preferences;
    private final ArrayList<String> orderedUrls = new ArrayList<>();
    private final HashSet<String> urlSet = new HashSet<>();

    public RecentChannelsStore(SharedPreferences preferences) {
        this.preferences = preferences;
        reload();
    }

    public synchronized void reload() {
        orderedUrls.clear();
        urlSet.clear();
        String raw = preferences.getString("urls", "");
        if (raw == null || raw.isEmpty()) return;
        for (String item : raw.split("\\n")) {
            String url = item.trim();
            if (!url.isEmpty() && urlSet.add(url)) orderedUrls.add(url);
            if (orderedUrls.size() >= MAX_RECENT) break;
        }
    }

    public synchronized List<String> orderedUrls() {
        return new ArrayList<>(orderedUrls);
    }

    public synchronized Set<String> snapshotUrls() {
        return new HashSet<>(urlSet);
    }

    public synchronized void remember(String url) {
        if (url == null || url.trim().isEmpty()) return;
        String value = url.trim();
        orderedUrls.remove(value);
        orderedUrls.add(0, value);
        while (orderedUrls.size() > MAX_RECENT) orderedUrls.remove(orderedUrls.size() - 1);
        urlSet.clear();
        urlSet.addAll(orderedUrls);
        preferences.edit().putString("urls", joinLines(orderedUrls)).apply();
    }

    private static String joinLines(List<String> items) {
        StringBuilder result = new StringBuilder();
        for (String item : items) {
            if (result.length() > 0) result.append('\n');
            result.append(item);
        }
        return result.toString();
    }
}
