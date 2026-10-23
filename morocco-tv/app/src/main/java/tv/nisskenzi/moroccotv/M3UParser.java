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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fast line-by-line native M3U reader. It never calls provider catalogue APIs. */
public final class M3UParser {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final long MAX_PLAYLIST_CHARS = 40L * 1024L * 1024L;
    private static final long REMOTE_CACHE_TTL_MS = 5 * 60 * 1000L;

    public interface CountryResolver {
        String resolve(String value);
    }

    private static final class Snapshot {
        final long savedAt = System.currentTimeMillis();
        final List<Channel> channels;
        Snapshot(List<Channel> input) {
            channels = Collections.unmodifiableList(new ArrayList<>(input));
        }
    }

    private static final Map<String, Snapshot> REMOTE_CACHE = new ConcurrentHashMap<>();

    private M3UParser() {}

    /** Compatibility overload for the previous NISSKENZI+ M3U settings. */
    public static ArrayList<Channel> load(Context context, String source, String assetName,
                                          String playlistUrl, int maximum,
                                          CountryResolver resolver, boolean forceRefresh)
            throws IOException {
        boolean vavoo = "VAVOO".equalsIgnoreCase(source);
        String sourceId = vavoo ? "vavoo" : "iptv-org-fr";
        String sourceName = vavoo ? "VAVOO" : "IPTV-org France";
        return load(context, sourceId, sourceName, assetName, playlistUrl, maximum,
                resolver, forceRefresh);
    }

    /**
     * Loads a configured URL (HTTP(S), asset://, file://) or a packaged asset.
     * Source metadata is stored on each Channel so lists can be merged without
     * losing their separate identities/categories in SQLite.
     */
    public static ArrayList<Channel> load(Context context, String sourceId, String sourceName,
                                          String assetName, String playlistUrl, int maximum,
                                          CountryResolver resolver, boolean forceRefresh)
            throws IOException {
        if (context == null) throw new IllegalArgumentException("Context obligatoire");
        if (maximum < 1) return new ArrayList<>();
        final String id = safe(sourceId);
        final String name = safe(sourceName).isEmpty() ? id : safe(sourceName);
        final String asset = safe(assetName);
        final String address = safe(playlistUrl);
        if (id.isEmpty()) throw new IllegalArgumentException("Identifiant de source vide.");

        if (!address.isEmpty()) {
            String key = id + "|" + address;
            Snapshot cached = REMOTE_CACHE.get(key);
            if (!forceRefresh && cached != null
                    && System.currentTimeMillis() - cached.savedAt < REMOTE_CACHE_TTL_MS) {
                int end = Math.min(maximum, cached.channels.size());
                return new ArrayList<>(cached.channels.subList(0, end));
            }
            ArrayList<Channel> channels;
            try (InputStream input = openAddress(context, address)) {
                channels = parse(input, address, id, name, maximum, resolver);
            }
            if (!channels.isEmpty()) REMOTE_CACHE.put(key, new Snapshot(channels));
            return channels;
        }

        if (asset.isEmpty()) throw new IOException("Aucune URL M3U ni aucun asset local configuré.");

        // The existing VAVOO asset has its own compact binary cache, avoiding a
        // full M3U parse on repeated app starts. The Room database is the UI cache.
        if (!forceRefresh && "vavoo".equals(id)) {
            ArrayList<Channel> cached = FastCatalogCache.read(context, "VAVOO",
                    asset, maximum, 1);
            if (!cached.isEmpty()) return cached;
        }

        ArrayList<Channel> channels;
        try (InputStream input = context.getAssets().open(asset)) {
            channels = parse(input, null, id, name, maximum, resolver);
        }
        if (!channels.isEmpty() && "vavoo".equals(id)) {
            FastCatalogCache.write(context, "VAVOO", asset, channels, maximum, 1);
        }
        return channels;
    }

    public static void clearMemoryCache() {
        REMOTE_CACHE.clear();
    }

    private static InputStream openAddress(Context context, String address) throws IOException {
        if (address.startsWith("asset://")) {
            String name = address.substring("asset://".length()).trim();
            if (name.isEmpty() || name.contains("..")) throw new IOException("Nom d’asset invalide.");
            return context.getAssets().open(name);
        }
        if (address.startsWith("file://")) {
            try {
                File file = new File(new URL(address).toURI());
                if (!file.isFile()) throw new IOException("Fichier M3U introuvable.");
                return new FileInputStream(file);
            } catch (java.net.URISyntaxException error) {
                throw new IOException("URL de fichier invalide.", error);
            }
        }

        URL url = new URL(address);
        if (!"http".equalsIgnoreCase(url.getProtocol())
                && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("La playlist doit utiliser HTTP(S), asset:// ou file://.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*");
        connection.setRequestProperty("User-Agent", "NISSKENZI+/2.9 (Android TV)");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " pour la playlist M3U.");
        }
        return new ConnectionInputStream(connection);
    }

    private static final class ConnectionInputStream extends java.io.FilterInputStream {
        private final HttpURLConnection connection;
        ConnectionInputStream(HttpURLConnection connection) throws IOException {
            super(new BufferedInputStream(connection.getInputStream(), 64 * 1024));
            this.connection = connection;
        }
        @Override public void close() throws IOException {
            try { super.close(); } finally { connection.disconnect(); }
        }
    }

    private static ArrayList<Channel> parse(InputStream rawInput, String playlistAddress,
                                            String sourceId, String sourceName, int maximum,
                                            CountryResolver resolver) throws IOException {
        ArrayList<Channel> result = new ArrayList<>();
        HashSet<String> seenUrls = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(rawInput, StandardCharsets.UTF_8), 64 * 1024)) {
            String line;
            String name = "";
            String logo = "";
            String group = "";
            String country = "";
            String epgId = "";
            long characters = 0L;

            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Lecture M3U annulée.");
                }
                characters += line.length() + 1L;
                if (characters > MAX_PLAYLIST_CHARS) {
                    throw new IOException("Playlist M3U trop volumineuse (limite 40 Mo).");
                }
                line = line.trim();
                if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTINF:")) {
                    int comma = titleSeparator(line);
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
                if (line.startsWith("#")) continue;
                if (result.size() >= maximum) break;

                String streamUrl = resolveRelativeUrl(playlistAddress, line);
                boolean requiresResolver = false;
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
                        resetPending();
                        name = logo = group = country = epgId = "";
                        continue;
                    }
                    String id = epgId.isEmpty() ? stableId(name, resolverUrl) : epgId;
                    streamUrl = "vavoo://" + id;
                    requiresResolver = true;
                } else if (line.startsWith("vavoo://")) {
                    requiresResolver = true;
                    resolverUrl = line;
                }

                if (streamUrl.isEmpty() || !seenUrls.add(streamUrl)) {
                    name = logo = group = country = epgId = "";
                    continue;
                }

                String rawCountry = country.isEmpty() ? group : country;
                String resolvedCountry = resolver == null ? "" : safe(resolver.resolve(rawCountry));
                if (resolvedCountry.isEmpty() && resolver != null && !group.isEmpty()) {
                    resolvedCountry = safe(resolver.resolve(group));
                }
                if (resolvedCountry.isEmpty()) resolvedCountry = rawCountry;
                if (resolvedCountry.isEmpty()) resolvedCountry = "Pays non précisé";

                String itemGroup = group.isEmpty() ? "Direct" : group;
                if (!itemGroup.startsWith(sourceName + " · ")) {
                    itemGroup = sourceName + " · " + itemGroup;
                }
                // A bundled VAVOO catalogue key is not an XMLTV ID.
                String xmltvId = requiresResolver && "vavoo".equals(sourceId) ? "" : epgId;
                result.add(new Channel(name, streamUrl, logo, itemGroup, requiresResolver,
                        resolverUrl, resolvedCountry, xmltvId, sourceId, null));

                name = logo = group = country = epgId = "";
            }
        }
        return result;
    }

    private static String resolveRelativeUrl(String playlistAddress, String value) {
        if (playlistAddress == null || playlistAddress.isEmpty()
                || value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("vavoo://") || value.startsWith("vavoo-catalog://")
                || value.startsWith("file://") || value.startsWith("rtmp://")) {
            return value;
        }
        try {
            return new URL(new URL(playlistAddress), value).toExternalForm();
        } catch (Exception ignored) {
            return value;
        }
    }

    /** Finds the EXTINF metadata/title comma outside quoted attributes. */
    private static int titleSeparator(String line) {
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (ch == 92) escaped = true;
                else if (ch == quote) quote = 0;
            } else if (ch == 34 || ch == 39) {
                quote = ch;
            } else if (ch == ',') {
                return i;
            }
        }
        return -1;
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
                if (quote == 34 || quote == 39) {
                    StringBuilder value = new StringBuilder();
                    boolean escaped = false;
                    for (int i = start + 1; i < line.length(); i++) {
                        char ch = line.charAt(i);
                        if (escaped) { value.append(ch); escaped = false; }
                        else if (ch == 92) escaped = true;
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

    private static String stableId(String name, String url) {
        return "m3u-" + Integer.toHexString((safe(name) + "|" + safe(url)).hashCode());
    }

    private static void resetPending() {
        // A separate method is intentionally empty: it keeps malformed vavoo
        // references as local skipped records instead of changing playlist state.
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
