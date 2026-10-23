package tv.nisskenzi.moroccotv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast access to the bundled worldwide M3U. All public repositories are merged
 * into this one asset at build/refresh time; VAVOO remains a separate native source.
 */
public final class IptvOrgProvider {
    public interface CountryResolver {
        String resolve(String rawCountry);
    }

    public interface ProgressListener {
        /** Called with a final immutable snapshot on the loader thread. */
        void onChannelsUpdated(List<Channel> snapshot);
    }

    private static final String BUNDLED_ASSET = WorldPlaylistRegistry.MERGED_M3U_ASSET;
    private static final int MIN_VALID_BUNDLED_CHANNELS = 1_000;

    private IptvOrgProvider() {}

    /** Return the already-parsed binary snapshot, or parse the embedded M3U once. */
    public static ArrayList<Channel> loadBundledChannels(Context context, int maximum,
            CountryResolver countryResolver) {
        if (context == null || maximum < 1) return new ArrayList<>();

        ArrayList<Channel> cached = FastCatalogCache.read(
                context, "IPTV-ORG", BUNDLED_ASSET, maximum, MIN_VALID_BUNDLED_CHANNELS);
        if (cached.size() >= MIN_VALID_BUNDLED_CHANNELS) return cached;

        try {
            WorldPlaylistRegistry registry = WorldPlaylistRegistry.load(context);
            ArrayList<Channel> bundled = readBundledM3u(
                    context, maximum, countryResolver, registry);
            if (bundled.size() >= MIN_VALID_BUNDLED_CHANNELS) {
                FastCatalogCache.write(context, "IPTV-ORG", BUNDLED_ASSET,
                        bundled, maximum, MIN_VALID_BUNDLED_CHANNELS);
                return bundled;
            }
        } catch (IOException ignored) {
            // A missing/partial asset falls back to the guarded network refresh below.
        }
        return new ArrayList<>();
    }

    /**
     * Normal startup is local-first. An explicit refresh fetches all configured
     * worldwide sources and only updates the binary cache after validation passes.
     */
    public static ArrayList<Channel> loadChannels(Context context, int maximum,
            CountryResolver countryResolver, ProgressListener listener, boolean forceNetwork)
            throws IOException {
        if (context == null) throw new IOException("Context Android requis.");

        if (!forceNetwork) {
            ArrayList<Channel> bundled = loadBundledChannels(context, maximum, countryResolver);
            if (bundled.size() >= MIN_VALID_BUNDLED_CHANNELS) return bundled;
        }
        WorldPlaylistParser.CountryResolver resolver = countryResolver == null
                ? null : countryResolver::resolve;
        return WorldPlaylistProvider.load(context, maximum, resolver, listener);
    }

    /**
     * Kept only for source compatibility with older callers. The modular source
     * registry is an Android asset, so a Context is required for network refresh.
     */
    @Deprecated
    public static ArrayList<Channel> loadChannels(int maximum, CountryResolver countryResolver,
                                                   ProgressListener listener) throws IOException {
        throw new IOException(
                "Un Context Android est requis pour charger les sources mondiales configurées.");
    }

    private static ArrayList<Channel> readBundledM3u(Context context, int maximum,
            CountryResolver countryResolver, WorldPlaylistRegistry registry) throws IOException {
        ArrayList<Channel> loaded = new ArrayList<>();
        try (InputStream input = context.getAssets().open(BUNDLED_ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), 32 * 1024)) {
            String line;
            String name = "Chaîne";
            String logo = "";
            String epgId = "";
            String group = "Direct";
            String country = "Pays non précisé";
            WorldPlaylistSource source = registry.sourceById("iptv-org");

            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Chargement du catalogue intégré annulé.");
                }
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    int comma = WorldPlaylistParser.titleSeparator(line);
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                    if (name.isEmpty()) name = WorldPlaylistParser.attribute(line, "tvg-name");
                    if (name.isEmpty()) name = "Chaîne";
                    logo = WorldPlaylistParser.attribute(line, "tvg-logo");
                    epgId = WorldPlaylistParser.attribute(line, "tvg-id");
                    group = WorldPlaylistParser.attribute(line, "group-title");
                    if (group.isEmpty()) group = "Direct";

                    String sourceTag = WorldPlaylistParser.attribute(line, "nisskenzi-source");
                    WorldPlaylistSource tagged = registry.sourceById(sourceTag);
                    if (tagged != null) source = tagged;
                    else source = registry.sourceById("iptv-org");

                    String rawCountry = WorldPlaylistParser.attribute(line, "tvg-country");
                    if (rawCountry.isEmpty()) {
                        rawCountry = WorldPlaylistParser.attribute(line, "country");
                    }
                    country = resolveCountry(rawCountry, group, source, countryResolver);
                    continue;
                }

                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!(line.regionMatches(true, 0, "http://", 0, 7)
                        || line.regionMatches(true, 0, "https://", 0, 8))) continue;
                if (loaded.size() >= maximum) break;

                String visibleGroup = (source == null ? "IPTV-org mondial" : source.name)
                        + " · " + group;
                if (registry.isBlocked(name, visibleGroup, line)) continue;

                // This is the exact public stream URL; it is not sent through VAVOO.
                loaded.add(new Channel(name, line, logo, visibleGroup,
                        false, null, country, epgId));
                name = "Chaîne";
                logo = "";
                epgId = "";
                group = "Direct";
                country = "Pays non précisé";
            }
        }
        return loaded;
    }

    private static String resolveCountry(String rawCountry, String group,
            WorldPlaylistSource source, CountryResolver resolver) {
        String raw = safe(rawCountry);
        if (!raw.isEmpty()) {
            String mapped = resolver == null ? "" : safe(resolver.resolve(raw));
            return mapped.isEmpty() ? raw : mapped;
        }
        if (source != null && source.countryFromGroup && !group.isEmpty() && resolver != null) {
            String mapped = safe(resolver.resolve(group));
            if (!mapped.isEmpty()) return mapped;
        }
        return "Pays non précisé";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
