package tv.nisskenzi.moroccotv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads the one source manifest shared by the Android app and the build-time
 * M3U assembler. Validates URLs and resource limits before any network request.
 */
public final class WorldPlaylistRegistry {
    public static final String MANIFEST_ASSET = "world_playlist_sources.json";
    public static final String MERGED_M3U_ASSET = "iptv_org_catalog.m3u";

    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private final List<WorldPlaylistSource> sources;
    private final Set<String> blockedTerms;
    public final int maxChannels;
    public final long maxPlaylistBytes;
    public final int minimumMergedChannels;

    private WorldPlaylistRegistry(List<WorldPlaylistSource> sources, Set<String> blockedTerms,
                                  int maxChannels, long maxPlaylistBytes,
                                  int minimumMergedChannels) {
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        this.blockedTerms = Collections.unmodifiableSet(new HashSet<>(blockedTerms));
        this.maxChannels = clamp(maxChannels, 1000, 30000);
        this.maxPlaylistBytes = Math.max(1024L * 1024L,
                Math.min(maxPlaylistBytes, 64L * 1024L * 1024L));
        this.minimumMergedChannels = clamp(minimumMergedChannels, 100, this.maxChannels);
    }

    public static WorldPlaylistRegistry load(Context context) throws IOException {
        if (context == null) throw new IOException("Context Android absent.");
        byte[] bytes;
        try (InputStream input = context.getAssets().open(MANIFEST_ASSET);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_MANIFEST_BYTES) {
                    throw new IOException("Le manifeste des playlists est trop volumineux.");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }

        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray sourceArray = root.optJSONArray("sources");
            if (sourceArray == null || sourceArray.length() == 0 || sourceArray.length() > 8) {
                throw new IOException("Le manifeste doit contenir entre 1 et 8 sources.");
            }

            ArrayList<WorldPlaylistSource> sources = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < sourceArray.length(); i++) {
                JSONObject object = sourceArray.optJSONObject(i);
                if (object == null || !object.optBoolean("enabled", true)) continue;
                String id = object.optString("id", "").trim().toLowerCase(Locale.ROOT);
                String name = object.optString("name", id).trim();
                String url = object.optString("url", "").trim();
                if (!id.matches("[a-z0-9][a-z0-9-]{0,47}")) {
                    throw new IOException("Identifiant de source invalide dans le manifeste.");
                }
                if (!ids.add(id)) throw new IOException("Identifiant de source en double : " + id);
                if (!url.startsWith("https://") || url.length() > 2048) {
                    throw new IOException("Chaque playlist doit utiliser une URL HTTPS valide.");
                }
                sources.add(new WorldPlaylistSource(
                        id, name, url, true, object.optBoolean("required", false),
                        object.optInt("priority", 0),
                        object.optInt("minimumChannels", 1),
                        object.optBoolean("countryFromGroup", false)));
            }
            if (sources.isEmpty()) throw new IOException("Aucune source M3U activée.");

            JSONArray terms = root.optJSONArray("blockedTerms");
            Set<String> blocked = new HashSet<>();
            if (terms != null) {
                for (int i = 0; i < terms.length(); i++) {
                    String term = Channel.normalizeSearch(terms.optString(i, ""));
                    if (!term.isEmpty()) blocked.add(term);
                }
            }

            return new WorldPlaylistRegistry(
                    sources, blocked,
                    root.optInt("maxChannels", 20000),
                    root.optLong("maxPlaylistBytes", 40L * 1024L * 1024L),
                    root.optInt("minimumMergedChannels", 1000));
        } catch (org.json.JSONException error) {
            throw new IOException("Le manifeste des playlists est invalide.", error);
        }
    }

    public List<WorldPlaylistSource> sources() {
        return sources;
    }

    public WorldPlaylistSource sourceById(String id) {
        if (id == null) return null;
        for (WorldPlaylistSource source : sources) {
            if (source.id.equalsIgnoreCase(id.trim())) return source;
        }
        return null;
    }

    public boolean isBlocked(String name, String group, String url) {
        String haystack = Channel.normalizeSearch(
                safe(name) + " " + safe(group) + " " + safe(url));
        for (String blocked : blockedTerms) {
            if (!blocked.isEmpty() && haystack.contains(blocked)) return true;
        }
        return false;
    }

    public static String sourceTag(String extinf) {
        return WorldPlaylistParser.attribute(extinf, "nisskenzi-source");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
