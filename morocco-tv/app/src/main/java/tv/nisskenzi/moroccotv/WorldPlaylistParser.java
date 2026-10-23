package tv.nisskenzi.moroccotv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Streaming M3U parser shared by source refresh and recovery from the embedded M3U. */
public final class WorldPlaylistParser {
    public interface CountryResolver {
        String resolve(String rawCountry);
    }

    public static final class Entry {
        public final Channel channel;
        public final String sourceId;
        public final String identityKey;
        public final int priority;

        Entry(Channel channel, String sourceId, String identityKey, int priority) {
            this.channel = channel;
            this.sourceId = sourceId;
            this.identityKey = identityKey;
            this.priority = priority;
        }
    }

    private WorldPlaylistParser() {}

    /**
     * Parses a single provider feed. If sourceOverride is null, each entry must
     * carry the source marker added by the build-time merger; onlySourceId is
     * then used to recover last-known-good rows for a failed optional provider.
     */
    public static ArrayList<Entry> parse(InputStream stream,
                                         WorldPlaylistRegistry registry,
                                         WorldPlaylistSource sourceOverride,
                                         String onlySourceId,
                                         int maximum,
                                         CountryResolver countryResolver) throws IOException {
        if (stream == null) throw new IOException("Flux M3U absent.");
        ArrayList<Entry> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024)) {
            String line;
            String extinf = null;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Actualisation des playlists annulée.");
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#EXTM3U")) continue;
                if (line.startsWith("#EXTINF:")) {
                    extinf = line;
                    continue;
                }
                if (line.startsWith("#") || extinf == null) continue;
                if (!isHttpUrl(line)) {
                    extinf = null;
                    continue;
                }

                WorldPlaylistSource source = sourceOverride;
                if (source == null) {
                    source = registry.sourceById(attribute(extinf, "nisskenzi-source"));
                }
                String sourceId = source == null ? "" : source.id;
                if (source == null || (onlySourceId != null
                        && !onlySourceId.equalsIgnoreCase(sourceId))) {
                    extinf = null;
                    continue;
                }

                String name = title(extinf);
                if (name.isEmpty()) name = attribute(extinf, "tvg-name");
                if (name.isEmpty()) name = "Chaîne";
                String logo = attribute(extinf, "tvg-logo");
                String epgId = attribute(extinf, "tvg-id");
                String group = attribute(extinf, "group-title");
                String rawCountry = attribute(extinf, "tvg-country");
                if (rawCountry.isEmpty()) rawCountry = attribute(extinf, "country");
                String country = resolveCountry(rawCountry, group, source, countryResolver);
                if (group.isEmpty()) group = "Direct";
                String visibleGroup = source.name + " · " + group;

                if (!registry.isBlocked(name, visibleGroup, line)) {
                    Channel channel = new Channel(name, line, logo, visibleGroup,
                            false, null, country, epgId);
                    String identity = identityKey(channel);
                    result.add(new Entry(channel, source.id, identity, source.priority));
                    count++;
                    if (count >= maximum) break;
                }
                extinf = null;
            }
        }
        return result;
    }

    /** Finds the EXTINF title comma without splitting commas inside quoted attributes. */
    public static int titleSeparator(String line) {
        if (line == null) return -1;
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

    public static String attribute(String line, String key) {
        if (line == null || key == null || key.isEmpty()) return "";
        int from = 0;
        while (from < line.length()) {
            int at = indexOfIgnoreCase(line, key, from);
            if (at < 0) return "";
            boolean leftBoundary = at == 0 || Character.isWhitespace(line.charAt(at - 1));
            int equals = at + key.length();
            boolean rightBoundary = equals >= line.length()
                    || Character.isWhitespace(line.charAt(equals))
                    || line.charAt(equals) == '=';
            if (leftBoundary && rightBoundary) {
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

    private static String title(String extinf) {
        int comma = titleSeparator(extinf);
        return comma < 0 ? "" : extinf.substring(comma + 1).trim();
    }

    private static String resolveCountry(String rawCountry, String group,
                                         WorldPlaylistSource source,
                                         CountryResolver resolver) {
        String raw = safe(rawCountry);
        if (!raw.isEmpty()) {
            String normalized = resolver == null ? "" : safe(resolver.resolve(raw));
            return normalized.isEmpty() ? raw : normalized;
        }
        if (source.countryFromGroup && !safe(group).isEmpty() && resolver != null) {
            String normalized = safe(resolver.resolve(group));
            if (!normalized.isEmpty()) return normalized;
        }
        return "Pays non précisé";
    }

    private static String identityKey(Channel channel) {
        String id = Channel.normalizeSearch(channel.epgId);
        if (!id.isEmpty()) return "id:" + id;
        String name = Channel.normalizeSearch(channel.name);
        String country = Channel.normalizeSearch(channel.country);
        return "name:" + name + (country.isEmpty() ? "" : "|" + country);
    }

    private static boolean isHttpUrl(String value) {
        return value.regionMatches(true, 0, "https://", 0, 8)
                || value.regionMatches(true, 0, "http://", 0, 7);
    }

    private static int indexOfIgnoreCase(String text, String search, int from) {
        int last = text.length() - search.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, search, 0, search.length())) return i;
        }
        return -1;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
