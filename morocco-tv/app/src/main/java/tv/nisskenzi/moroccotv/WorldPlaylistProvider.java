package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.util.Log;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refreshes all configured public playlists off the UI thread.
 *
 * The required IPTV-org feed guarantees a broad world catalogue. Optional feeds
 * enrich it; if one is unavailable, its last-known-good rows from the packaged
 * merged M3U are reused. No cache is replaced by a tiny or invalid response.
 */
public final class WorldPlaylistProvider {
    private static final String TAG = "WorldPlaylistProvider";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 18_000;
    private static final String USER_AGENT = "NISSKENZI-Android-TV/2.5.1";

    private WorldPlaylistProvider() {}

    public static synchronized ArrayList<Channel> load(
            Context context,
            int requestedMaximum,
            WorldPlaylistParser.CountryResolver countryResolver,
            IptvOrgProvider.ProgressListener listener) throws IOException {
        if (context == null) throw new IOException("Context Android absent.");
        WorldPlaylistRegistry registry = WorldPlaylistRegistry.load(context);
        final int maximum = Math.max(1000, Math.min(requestedMaximum, registry.maxChannels));

        LinkedHashMap<String, WorldPlaylistParser.Entry> merged = new LinkedHashMap<>();
        Map<String, String> ownerByUrl = new HashMap<>();
        int requiredSourceCount = 0;

        // The list is intentionally sequential: it caps network and parser memory
        // while every request is already running on the app's background executor.
        for (WorldPlaylistSource source : registry.sources()) {
            ArrayList<WorldPlaylistParser.Entry> entries = new ArrayList<>();
            IOException sourceError = null;
            try {
                entries = fetchAndParse(context, source, registry, maximum, countryResolver);
                if (entries.size() < source.minimumChannels) {
                    throw new IOException(source.name + " : seulement " + entries.size()
                            + " chaînes valides (minimum " + source.minimumChannels + ").");
                }
            } catch (IOException error) {
                sourceError = error;
                Log.w(TAG, source.name + " indisponible, tentative du dernier catalogue intégré.", error);
                if (source.required) {
                    throw new IOException("La source mondiale obligatoire " + source.name
                            + " n'a pas pu être validée; le catalogue déjà affiché reste intact.",
                            sourceError);
                }
                entries = readLastKnownGoodSource(context, registry, source, maximum, countryResolver);
                if (entries.size() < source.minimumChannels) {
                    Log.w(TAG, source.name + " ignorée: aucune version intégrée suffisamment complète.");
                    continue;
                }
            }

            if (source.required) requiredSourceCount++;
            mergeEntries(entries, merged, ownerByUrl, maximum);
        }

        ArrayList<Channel> result = new ArrayList<>(merged.size());
        for (WorldPlaylistParser.Entry entry : merged.values()) {
            result.add(entry.channel);
        }

        if (requiredSourceCount == 0 || result.size() < registry.minimumMergedChannels) {
            throw new IOException("Catalogue mondial incomplet (" + result.size()
                    + " chaînes). Le catalogue déjà affiché est conservé.");
        }

        // The binary cache is keyed to the packaged M3U revision. Updating it only
        // after full validation makes the next app launch instant and offline-safe.
        FastCatalogCache.write(context, "IPTV-ORG", WorldPlaylistRegistry.MERGED_M3U_ASSET,
                result, maximum, registry.minimumMergedChannels);

        if (listener != null) {
            try {
                listener.onChannelsUpdated(Collections.unmodifiableList(new ArrayList<>(result)));
            } catch (RuntimeException ignored) {
                // UI callbacks must not invalidate a successful catalogue refresh.
            }
        }
        return result;
    }

    private static ArrayList<WorldPlaylistParser.Entry> fetchAndParse(
            Context context, WorldPlaylistSource source, WorldPlaylistRegistry registry,
            int maximum, WorldPlaylistParser.CountryResolver countryResolver) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(source.url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept",
                    "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*");
            connection.setRequestProperty("Connection", "close");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException(source.name + " répond HTTP " + status + ".");
            }
            long length = connection.getContentLengthLong();
            if (length > registry.maxPlaylistBytes) {
                throw new IOException(source.name + " dépasse la limite de taille autorisée.");
            }
            InputStream input = new LimitedInputStream(
                    connection.getInputStream(), registry.maxPlaylistBytes);
            return WorldPlaylistParser.parse(input, registry, source, null, maximum, countryResolver);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Lecture de " + source.name + " impossible.", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static ArrayList<WorldPlaylistParser.Entry> readLastKnownGoodSource(
            Context context, WorldPlaylistRegistry registry, WorldPlaylistSource source,
            int maximum, WorldPlaylistParser.CountryResolver countryResolver) {
        try (InputStream input = context.getAssets().open(WorldPlaylistRegistry.MERGED_M3U_ASSET)) {
            return WorldPlaylistParser.parse(input, registry, null, source.id, maximum, countryResolver);
        } catch (Exception error) {
            Log.w(TAG, "Impossible de restaurer " + source.name + " depuis l'APK.", error);
            return new ArrayList<>();
        }
    }

    private static void mergeEntries(List<WorldPlaylistParser.Entry> entries,
            LinkedHashMap<String, WorldPlaylistParser.Entry> merged,
            Map<String, String> ownerByUrl, int maximum) {
        for (WorldPlaylistParser.Entry candidate : entries) {
            if (candidate == null || candidate.channel == null
                    || candidate.channel.url.isEmpty()) continue;
            String key = candidate.identityKey;
            String url = candidate.channel.url;
            WorldPlaylistParser.Entry existing = merged.get(key);
            String urlOwner = ownerByUrl.get(url);

            if (existing == null) {
                if (merged.size() >= maximum || urlOwner != null) continue;
                merged.put(key, candidate);
                ownerByUrl.put(url, key);
                continue;
            }

            // Prefer curated Free-TV entries, then health-checked links, then the
            // broad IPTV-org fallback. Keep one row per channel instead of showing
            // source duplicates in the RecyclerView.
            if (candidate.priority <= existing.priority) continue;
            if (urlOwner != null && !urlOwner.equals(key)) continue;

            ownerByUrl.remove(existing.channel.url);
            merged.put(key, candidate);
            ownerByUrl.put(url, key);
        }
    }

    /** Limits responses even when the server omits or lies about Content-Length. */
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream input, long maximumBytes) {
            super(input);
            remaining = maximumBytes;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) {
                if (super.read() == -1) return -1;
                throw new IOException("La playlist dépasse la limite de taille autorisée.");
            }
            int value = super.read();
            if (value != -1) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) {
                int extra = super.read();
                if (extra == -1) return -1;
                throw new IOException("La playlist dépasse la limite de taille autorisée.");
            }
            int allowed = (int) Math.min((long) length, remaining);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
