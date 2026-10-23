package tv.nisskenzi.moroccotv;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Very short-lived cache for VAVOO URLs that were already resolved by the user
 * selecting a channel. It does not pre-resolve or download any other channel.
 * The short TTL limits the risk of reusing signed/temporary stream URLs.
 */
public final class ResolvedStreamCache {
    private static final long TTL_MS = 15_000L;
    private static final int MAX_ENTRIES = 16;
    private static final LinkedHashMap<String, Entry> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

    private static final class Entry {
        final String streamUrl;
        final long expiresAt;
        Entry(String streamUrl, long expiresAt) {
            this.streamUrl = streamUrl;
            this.expiresAt = expiresAt;
        }
    }

    private ResolvedStreamCache() {}

    public static synchronized String get(String catalogReference) {
        String key = safe(catalogReference);
        if (key.isEmpty()) return null;
        Entry entry = CACHE.get(key);
        if (entry == null) return null;
        if (entry.expiresAt <= System.currentTimeMillis()) {
            CACHE.remove(key);
            return null;
        }
        return entry.streamUrl;
    }

    public static synchronized void put(String catalogReference, String streamUrl) {
        String key = safe(catalogReference);
        String value = safe(streamUrl);
        if (key.isEmpty() || !(value.startsWith("https://") || value.startsWith("http://"))) return;
        CACHE.put(key, new Entry(value, System.currentTimeMillis() + TTL_MS));
        Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> next = it.next();
            if (next.getValue().expiresAt <= System.currentTimeMillis() || CACHE.size() > MAX_ENTRIES) {
                it.remove();
            }
        }
    }

    public static synchronized void invalidate(String catalogReference) {
        CACHE.remove(safe(catalogReference));
    }

    public static synchronized void clear() {
        CACHE.clear();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
