package tv.nisskenzi.moroccotv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** IPTV-org channel metadata parser with an embedded-M3U fast path. */
public final class IptvOrgProvider {
    public interface CountryResolver {
        String resolve(String rawCountry);
    }

    public interface ProgressListener {
        /** Called on the loader thread with an independent snapshot, not the mutating working list. */
        void onChannelsUpdated(List<Channel> snapshot);
    }

    private static final String PLAYLIST_URL = "https://raw.githubusercontent.com/Nissay8z/KENZINISS/main/playlist/master.m3u";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int PUBLISH_INTERVAL = 1_500;

    private IptvOrgProvider() {}

    /**
     * Backwards-compatible name for callers from older versions. There is no
     * packaged playlist anymore; the Room repository owns the local cache.
     */
    @Deprecated
    public static ArrayList<Channel> loadBundledChannels(Context context, int maximum,
            CountryResolver countryResolver) {
        try {
            return loadChannels(maximum, countryResolver, null);
        } catch (IOException ignored) {
            return new ArrayList<>();
        }
    }

    /**
     * Normal startup uses the packaged M3U, parsed off the UI thread, and
     * returns the complete list in one snapshot to avoid repeated big UI diffs.
     * Explicit refresh bypasses the asset and uses the original public playlist.
     */
    public static ArrayList<Channel> loadChannels(Context context, int maximum,
            CountryResolver countryResolver, ProgressListener listener, boolean forceNetwork)
            throws IOException {
        // The Room-backed repository owns local caching. This provider always
        // fetches the same GitHub master as the other IPTV-org code path.
        return loadChannels(maximum, countryResolver, listener);
    }

    /** Explicit live refresh; keeps the original IPTV-org source URL unchanged. */
    public static ArrayList<Channel> loadChannels(int maximum, CountryResolver countryResolver,
                                                   ProgressListener listener) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(PLAYLIST_URL).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NISSKENZI+/2.4 (Android TV)");

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"), 64 * 1024)) {
                return parsePlaylist(reader, maximum, countryResolver, listener);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static ArrayList<Channel> parsePlaylist(BufferedReader reader, int maximum,
            CountryResolver countryResolver, ProgressListener listener) throws IOException {
        ArrayList<Channel> loaded = new ArrayList<>();
        String line;
        String name = "Chaîne";
        String logo = "";
        String group = "Pays non précisé";
        String country = "Pays non précisé";
        String epgId = "";
        int lastPublished = 0;
        Map<String, String> resolvedCountryCache = new HashMap<>();

        while ((line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Chargement IPTV-org annulé.");
            if (line.startsWith("#EXTINF:")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 ? line.substring(comma + 1).trim() : "Chaîne";
                logo = "";
                group = "Pays non précisé";
                country = "Pays non précisé";
                epgId = attribute(line, "tvg-id");

                String logoValue = attribute(line, "tvg-logo");
                if (!logoValue.isEmpty()) logo = logoValue;
                String groupValue = attribute(line, "group-title");
                if (!groupValue.isEmpty()) group = groupValue;

                String rawCountry = attribute(line, "tvg-country");
                if (rawCountry.isEmpty()) rawCountry = attribute(line, "country");
                String cacheKey = rawCountry.isEmpty() ? "group:" + group : "country:" + rawCountry;
                String normalized = resolvedCountryCache.get(cacheKey);
                if (normalized == null) {
                    normalized = countryResolver == null ? "" : countryResolver.resolve(rawCountry);
                    if ((normalized == null || normalized.isEmpty()) && countryResolver != null) {
                        normalized = countryResolver.resolve(group);
                    }
                    normalized = normalized == null || normalized.isEmpty()
                            ? "Pays non précisé" : normalized;
                    resolvedCountryCache.put(cacheKey, normalized);
                }
                country = normalized;
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                if (loaded.size() >= maximum) break;
                // Do not rewrite the stream URL or add a resolver: this is the original IPTV-org entry.
                loaded.add(new Channel(name, line.trim(), logo,
                        "IPTV-ORG · " + group, false, null, country, epgId));

                if (listener != null && loaded.size() - lastPublished >= PUBLISH_INTERVAL) {
                    lastPublished = loaded.size();
                    try {
                        listener.onChannelsUpdated(new ArrayList<>(loaded));
                    } catch (RuntimeException ignored) {
                        // A progress listener must not break playlist parsing.
                    }
                }
            }
        }
        return loaded;
    }

    private static String attribute(String line, String key) {
        if (line == null || key == null || key.isEmpty()) return "";
        int from = 0;
        while (from < line.length()) {
            int at = indexOfIgnoreCase(line, key, from);
            if (at < 0) return "";
            boolean boundaryBefore = at == 0 || Character.isWhitespace(line.charAt(at - 1));
            int equals = at + key.length();
            boolean boundaryAfter = equals >= line.length()
                    || Character.isWhitespace(line.charAt(equals))
                    || line.charAt(equals) == '=';
            if (boundaryBefore && boundaryAfter) {
                while (equals < line.length() && Character.isWhitespace(line.charAt(equals))) equals++;
                if (equals >= line.length() || line.charAt(equals) != '=') {
                    from = at + key.length();
                    continue;
                }
                int start = equals + 1;
                while (start < line.length() && Character.isWhitespace(line.charAt(start))) start++;
                if (start >= line.length()) return "";
                char quote = line.charAt(start);
                if (quote == '"' || quote == '\'') {
                    StringBuilder value = new StringBuilder();
                    boolean escaped = false;
                    for (int i = start + 1; i < line.length(); i++) {
                        char ch = line.charAt(i);
                        if (escaped) { value.append(ch); escaped = false; }
                        else if (ch == '\\') escaped = true;
                        else if (ch == quote) return value.toString().trim();
                        else value.append(ch);
                    }
                    return value.toString().trim();
                }
                int end = start;
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))
                        && line.charAt(end) != ',') end++;
                return line.substring(start, end).trim();
            }
            from = at + key.length();
        }
        return "";
    }

    private static int indexOfIgnoreCase(String text, String search, int from) {
        int last = text.length() - search.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, search, 0, search.length())) return i;
        }
        return -1;
    }
}
