package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight streaming M3U parser used to build the native channel catalogue.
 *
 * Local assets are cached in FastCatalogCache after their first parse. A remote
 * URL is downloaded only when the chosen source is opened/refreshed and is
 * cached in memory briefly to avoid repeated network requests while navigating.
 * VAVOO's bundled catalogue may use vavoo-catalog://<base64-url>; this is
 * converted to a normal vavoo:// identifier while preserving the resolver URL.
 */
public final class M3UParser {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_PLAYLIST_BYTES = 32 * 1024 * 1024;
    private static final long REMOTE_CACHE_TTL_MS = 5 * 60 * 1000L;

    public interface CountryResolver {
        String resolve(String value);
    }

    private static final class Snapshot {
        final long savedAt;
        final List<Channel> channels;

        Snapshot(List<Channel> channels) {
            this.savedAt = System.currentTimeMillis();
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        }
    }

    private static final Map<String, Snapshot> REMOTE_CACHE = new ConcurrentHashMap<>();

    private M3UParser() {}

    /**
     * Loads either a configured HTTP(S)/file URL or a packaged Android asset.
     * source must be "VAVOO" or "IPTV-ORG" and is kept separate from the stream
     * transport so direct HTTP streams inside either playlist remain direct.
     */
    public static ArrayList<Channel> load(Context context, String source, String assetName,
                                          String playlistUrl, int maximum,
                                          CountryResolver countryResolver,
                                          boolean forceRefresh) throws IOException {
        if (context == null) throw new IllegalArgumentException("Context obligatoire");
        if (maximum < 1) return new ArrayList<>();

        final String sourceName = "VAVOO".equalsIgnoreCase(source) ? "VAVOO" : "IPTV-ORG";
        final String remoteUrl = safe(playlistUrl);
        if (!remoteUrl.isEmpty()) {
            String key = sourceName + "|" + remoteUrl;
            Snapshot cached = REMOTE_CACHE.get(key);
            if (!forceRefresh && cached != null
                    && System.currentTimeMillis() - cached.savedAt < REMOTE_CACHE_TTL_MS) {
                return new ArrayList<>(cached.channels.subList(0,
                        Math.min(maximum, cached.channels.size())));
            }

            ArrayList<Channel> parsed;
            try (InputStream input = openUrl(context, remoteUrl)) {
                parsed = parse(input, remoteUrl, sourceName, maximum, countryResolver);
            }
            if (!parsed.isEmpty()) REMOTE_CACHE.put(key, new Snapshot(parsed));
            return parsed;
        }

        if (!forceRefresh) {
            ArrayList<Channel> cached = FastCatalogCache.read(context, sourceName,
                    assetName, maximum, 1);
            if (!cached.isEmpty()) return cached;
        }

        ArrayList<Channel> parsed;
        try (InputStream input = context.getAssets().open(assetName)) {
            parsed = parse(input, null, sourceName, maximum, countryResolver);
        }
        if (!parsed.isEmpty()) {
            FastCatalogCache.write(context, sourceName, assetName, parsed,
                    maximum, 1);
        }
        return parsed;
    }

    /** Clears only in-memory remote playlist snapshots. */
    public static void clearMemoryCache() {
        REMOTE_CACHE.clear();
    }

    private static InputStream openUrl(Context context, String address) throws IOException {
        if (address.startsWith("asset://")) {
            String asset = address.substring("asset://".length()).trim();
            if (asset.isEmpty() || asset.contains("..")) {
                throw new IOException("Nom d’asset M3U invalide.");
            }
            return context.getAssets().open(asset);
        }
        if (address.startsWith("file://")) {
            try {
                File file = new File(new URL(address).toURI());
                if (!file.isFile()) throw new IOException("Fichier M3U introuvable.");
                return new FileInputStream(file);
            } catch (java.net.URISyntaxException e) {
                throw new IOException("URL de fichier M3U invalide.", e);
            }
        }
        URL url = new URL(address);
        if (!("http".equalsIgnoreCase(url.getProtocol())
                || "https".equalsIgnoreCase(url.getProtocol()))) {
            throw new IOException("La playlist distante doit utiliser HTTP(S), asset:// ou file://.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept",
                "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*");
        connection.setRequestProperty("User-Agent", "NISSKENZI+/2.8 (Android TV M3U)");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " pour la playlist M3U.");
        }
        return new ConnectionInputStream(connection);
    }

    /** Disconnects HTTP after the buffered reader closes the response stream. */
    private static final class ConnectionInputStream extends java.io.FilterInputStream {
        private final HttpURLConnection connection;

        ConnectionInputStream(HttpURLConnection connection) throws IOException {
            super(new BufferedInputStream(connection.getInputStream(), 64 * 1024));
            this.connection = connection;
        }

        @Override public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    private static ArrayList<Channel> parse(InputStream rawInput, String playlistUrl,
                                             String source, int maximum,
                                             CountryResolver countryResolver) throws IOException {
        ArrayList<Channel> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(rawInput, StandardCharsets.UTF_8), 64 * 1024)) {
            String line;
            String name = "";
            String logo = "";
            String group = "";
            String country = "";
            String epgId = "";
            long characterCount = 0L;

            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Chargement de la playlist M3U annulé.");
                }
                characterCount += line.length() + 1L;
                if (characterCount > MAX_PLAYLIST_BYTES) {
                    throw new IOException("La playlist M3U dépasse la taille autorisée.");
                }
                line = line.trim();
                if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTINF:")) {
                    int comma = line.lastIndexOf(',');
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                    String tvgName = attribute(line, "tvg-name");
                    if (!tvgName.isEmpty()) name = tvgName;
                    if (name.isEmpty()) name = "Chaîne";
                    logo = attribute(line, "tvg-logo");
                    group = attribute(line, "group-title");
                    country = attribute(line, "tvg-country");
                    if (country.isEmpty()) country = attribute(line, "country");
                    epgId = attribute(line, "tvg-id");
                    continue;
                }

                // Ignore M3U directives and comments; the next non-comment line is the URI.
                if (line.startsWith("#")) continue;
                if (result.size() >= maximum) break;

                String streamLine = resolveRelativeUrl(playlistUrl, line);
                boolean vavooSource = "VAVOO".equals(source);
                String channelUrl = streamLine;
                String resolverUrl = "";

                if (line.startsWith("vavoo-catalog://")) {
                    String encoded = line.substring("vavoo-catalog://".length()).trim();
                    try {
                        byte[] decoded = Base64.decode(encoded,
                                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                        resolverUrl = new String(decoded, StandardCharsets.UTF_8).trim();
                    } catch (IllegalArgumentException ignored) {
                        resolverUrl = "";
                    }
                    if (resolverUrl.isEmpty()) {
                        name = logo = group = country = epgId = "";
                        continue;
                    }
                    String id = epgId.isEmpty() ? stableId(name, resolverUrl) : epgId;
                    channelUrl = "vavoo://" + id;
                } else if (line.startsWith("vavoo://")) {
                    // A standard M3U can store VAVOO references directly as the URI.
                    resolverUrl = line;
                }

                String rawCountry = country;
                if (rawCountry.isEmpty()) rawCountry = group;
                String resolvedCountry = countryResolver == null
                        ? "" : safe(countryResolver.resolve(rawCountry));
                if (resolvedCountry.isEmpty() && countryResolver != null && !group.isEmpty()) {
                    resolvedCountry = safe(countryResolver.resolve(group));
                }
                if (resolvedCountry.isEmpty()) resolvedCountry = rawCountry;
                if (resolvedCountry.isEmpty()) resolvedCountry = "Pays non précisé";

                String finalGroup = group.isEmpty() ? "Direct" : group;
                String prefix = vavooSource ? "VAVOO · " : "IPTV-ORG · ";
                if (!finalGroup.startsWith("VAVOO · ")
                        && !finalGroup.startsWith("IPTV-ORG · ")) {
                    finalGroup = prefix + finalGroup;
                }

                // The bundled VAVOO tvg-id is a catalogue key, not an XMLTV id.
                String xmltvId = vavooSource ? "" : epgId;
                result.add(new Channel(name, channelUrl, logo, finalGroup,
                        vavooSource, resolverUrl, resolvedCountry, xmltvId));

                name = logo = group = country = epgId = "";
            }
        }
        return result;
    }

    private static String resolveRelativeUrl(String playlistUrl, String line) {
        if (playlistUrl == null || playlistUrl.isEmpty()
                || line.startsWith("vavoo://") || line.startsWith("vavoo-catalog://")
                || line.startsWith("http://") || line.startsWith("https://")
                || line.startsWith("file://") || line.startsWith("rtmp://")) return line;
        try {
            return new URL(new URL(playlistUrl), line).toExternalForm();
        } catch (Exception ignored) {
            return line;
        }
    }

    private static String attribute(String line, String key) {
        String marker = key + "=";
        int searchFrom = 0;
        while (true) {
            int at = line.indexOf(marker, searchFrom);
            if (at < 0) return "";
            if (at == 0 || Character.isWhitespace(line.charAt(at - 1))) {
                int start = at + marker.length();
                if (start >= line.length()) return "";
                char quote = line.charAt(start);
                if (quote == '"' || quote == '\'') {
                    StringBuilder value = new StringBuilder();
                    boolean escaped = false;
                    for (int i = start + 1; i < line.length(); i++) {
                        char ch = line.charAt(i);
                        if (escaped) {
                            value.append(ch);
                            escaped = false;
                        } else if (ch == '\\') {
                            escaped = true;
                        } else if (ch == quote) {
                            break;
                        } else {
                            value.append(ch);
                        }
                    }
                    return value.toString().trim();
                }
                int end = start;
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
                return line.substring(start, end).trim();
            }
            searchFrom = at + marker.length();
        }
    }

    private static String stableId(String name, String url) {
        return "m3u-" + Integer.toHexString((safe(name) + "|" + safe(url)).hashCode());
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
