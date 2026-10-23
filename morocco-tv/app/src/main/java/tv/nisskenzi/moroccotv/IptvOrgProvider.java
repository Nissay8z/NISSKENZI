package tv.nisskenzi.moroccotv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Network/parser boundary for the public IPTV-org country playlist. */
public final class IptvOrgProvider {
    public interface CountryResolver {
        String resolve(String rawCountry);
    }

    public interface ProgressListener {
        /** Called on the loader thread with an independent snapshot, not the mutating working list. */
        void onChannelsUpdated(List<Channel> snapshot);
    }

    private static final String PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.country.m3u";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int PUBLISH_INTERVAL = 1_500;

    private IptvOrgProvider() {}

    public static ArrayList<Channel> loadChannels(int maximum, CountryResolver countryResolver,
                                                   ProgressListener listener) throws IOException {
        ArrayList<Channel> loaded = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(PLAYLIST_URL).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NISSKENZI+/2.1 (Android TV)");

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"), 32 * 1024)) {
                String line;
                String name = "Chaîne";
                String logo = "";
                String group = "Pays non précisé";
                String country = "Pays non précisé";
                int lastPublished = 0;

                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Chargement IPTV-org annulé.");
                    if (line.startsWith("#EXTINF:")) {
                        int comma = line.lastIndexOf(',');
                        name = comma >= 0 ? line.substring(comma + 1).trim() : "Chaîne";
                        logo = "";
                        group = "Pays non précisé";
                        country = "Pays non précisé";

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
                        loaded.add(new Channel(name, line.trim(), logo,
                                "IPTV-ORG · " + group, false, null, country));

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
            }
        } finally {
            if (connection != null) connection.disconnect();
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
