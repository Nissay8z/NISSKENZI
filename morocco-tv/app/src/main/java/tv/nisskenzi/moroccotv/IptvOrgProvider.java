package tv.nisskenzi.moroccotv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** IPTV-org channel metadata parser with an embedded-M3U fast path. */
public final class IptvOrgProvider {
    public interface CountryResolver {
        String resolve(String rawCountry);
    }

    public interface ProgressListener {
        /** Called on the loader thread with an independent snapshot, not the mutating working list. */
        void onChannelsUpdated(List<Channel> snapshot);
    }

    private static final String PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.country.m3u";
    private static final String BUNDLED_ASSET = "iptv_org_catalog.m3u";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int PUBLISH_INTERVAL = 1_500;
    private static final int MIN_VALID_BUNDLED_CHANNELS = 1_000;

    private IptvOrgProvider() {}

    /** Reads the preloaded M3U only; returns empty for a missing or partial asset. */
    public static ArrayList<Channel> loadBundledChannels(Context context, int maximum,
            CountryResolver countryResolver) {
        if (context == null) return new ArrayList<>();
        try (InputStream input = context.getAssets().open(BUNDLED_ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"), 64 * 1024)) {
            ArrayList<Channel> bundled = parsePlaylist(reader, maximum, countryResolver, null);
            return bundled.size() >= MIN_VALID_BUNDLED_CHANNELS ? bundled : new ArrayList<>();
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
        if (!forceNetwork && context != null) {
            try (InputStream input = context.getAssets().open(BUNDLED_ASSET);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"), 64 * 1024)) {
                ArrayList<Channel> bundled = parsePlaylist(reader, maximum, countryResolver, null);
                if (bundled.size() >= MIN_VALID_BUNDLED_CHANNELS) return bundled;
            } catch (IOException ignored) {
                // Older or incomplete APK: safely fall back to the original online playlist.
            }
        }
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
                String normalized = countryResolver == null ? "" : countryResolver.resolve(rawCountry);
                if ((normalized == null || normalized.isEmpty()) && countryResolver != null) {
                    normalized = countryResolver.resolve(group);
                }
                country = normalized == null || normalized.isEmpty()
                        ? "Pays non précisé" : normalized;
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
        int startAt = line.indexOf(key + "=");
        if (startAt < 0) return "";
        int start = startAt + key.length() + 1;
        if (start < line.length() && line.charAt(start) == '"') {
            int end = line.indexOf('"', start + 1);
            return end > start ? line.substring(start + 1, end).trim() : "";
        }
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start).trim() : line.substring(start, end).trim();
    }
}
