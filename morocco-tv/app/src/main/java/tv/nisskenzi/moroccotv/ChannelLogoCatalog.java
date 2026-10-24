package tv.nisskenzi.moroccotv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small, cached fallback index for VAVOO rows whose M3U has no usable channel logo. */
public final class ChannelLogoCatalog {
    private static final String CHANNELS_URL = "https://iptv-org.github.io/api/channels.json";
    private static final String LOGOS_URL = "https://iptv-org.github.io/api/logos.json";
    private static final String CACHE_NAME = "channel-logo-index-v2.json";
    private static final long CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    private ChannelLogoCatalog() {}

    /** Loads a fresh on-disk index when available, otherwise refreshes it off the UI thread. */
    public static Map<String, String> load(Context context) {
        if (context == null) return Collections.emptyMap();
        File cacheFile = new File(context.getCacheDir(), CACHE_NAME);
        Map<String, String> cached = readCache(cacheFile);
        if (!cached.isEmpty() && cacheFile.lastModified() > 0L
                && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS) {
            return cached;
        }
        try {
            Map<String, String> fresh = fetchIndex();
            if (!fresh.isEmpty()) writeCache(cacheFile, fresh);
            return fresh.isEmpty() ? cached : fresh;
        } catch (Exception ignored) {
            return cached;
        }
    }

    /** Looks up a normalized name within its country, then uses only globally unique names. */
    public static String find(Map<String, String> index, String name, String country) {
        if (index == null || index.isEmpty()) return "";
        String code = ChannelCatalogPolicy.countryCode(country);
        List<String> titles = nameVariants(name);
        if (!code.isEmpty()) {
            for (String title : titles) {
                String match = index.get(code + "|" + title);
                if (match != null && !match.isEmpty()) return match;
            }
        }
        for (String title : titles) {
            String match = index.get("*|" + title);
            if (match != null && !match.isEmpty()) return match;
        }
        return "";
    }

    /** Tries exact titles and common VAVOO suffixes without guessing unrelated channels. */
    private static List<String> nameVariants(String name) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String canonical = ChannelDeduplicator.canonicalTitle(name);
        if (!canonical.isEmpty()) variants.add(canonical);

        String normalized = Channel.normalizeSearch(name);
        if (!normalized.isEmpty()) variants.add(ChannelDeduplicator.canonicalTitle(normalized));

        String withoutRegion = normalized.replaceAll(
                "\\s+(?:maroc|morocco|maghreb|arabic|arab|international|intl|france|french|live|inter|premium|fr|ma)$", "")
                .trim();
        if (!withoutRegion.isEmpty()) variants.add(ChannelDeduplicator.canonicalTitle(withoutRegion));

        String withoutCountryMarker = normalized.replaceAll(
                "\\b(?:morocco|maroc|maghreb|arabic|international|intl|france|french|inter)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        if (!withoutCountryMarker.isEmpty()) {
            variants.add(ChannelDeduplicator.canonicalTitle(withoutCountryMarker));
        }
        variants.remove("");
        return new ArrayList<>(variants);
    }

    private static Map<String, String> fetchIndex() throws Exception {
        JSONArray channels = new JSONArray(download(CHANNELS_URL));
        JSONArray logos = new JSONArray(download(LOGOS_URL));
        Map<String, ChannelMetadata> metadataById = new HashMap<>();

        for (int i = 0; i < channels.length(); i++) {
            JSONObject row = channels.optJSONObject(i);
            if (row == null) continue;
            String id = safe(row.optString("id", ""));
            String name = safe(row.optString("name", ""));
            String code = safe(row.optString("country", "")).toUpperCase(Locale.ROOT);
            if (id.isEmpty() || name.isEmpty() || !isSupportedCountry(code)) continue;

            ArrayList<String> names = new ArrayList<>();
            names.add(name);
            JSONArray aliases = row.optJSONArray("alt_names");
            if (aliases != null) {
                for (int j = 0; j < aliases.length(); j++) {
                    String alias = safe(aliases.optString(j, ""));
                    if (!alias.isEmpty()) names.add(alias);
                }
            }
            metadataById.put(id, new ChannelMetadata(code, names));
        }

        Map<String, LogoCandidate> bestLogoByChannel = new HashMap<>();
        for (int i = 0; i < logos.length(); i++) {
            JSONObject row = logos.optJSONObject(i);
            if (row == null) continue;
            String id = safe(row.optString("channel", ""));
            if (!metadataById.containsKey(id)) continue;
            String url = safe(row.optString("url", ""));
            String format = safe(row.optString("format", "")).toUpperCase(Locale.ROOT);
            int formatScore = formatScore(format, url);
            if (formatScore < 0 || !url.startsWith("https://")) continue;

            int score = (row.optBoolean("in_use", false) ? 1000 : 0) + formatScore;
            JSONArray tags = row.optJSONArray("tags");
            if (tags != null) {
                for (int j = 0; j < tags.length(); j++) {
                    String tag = safe(tags.optString(j, "")).toLowerCase(Locale.ROOT);
                    if ("square".equals(tag)) score += 25;
                    else if ("horizontal".equals(tag)) score += 10;
                    else if ("white".equals(tag)) score -= 5;
                }
            }
            LogoCandidate previous = bestLogoByChannel.get(id);
            if (previous == null || score > previous.score) {
                bestLogoByChannel.put(id, new LogoCandidate(url, score));
            }
        }

        Map<String, String> index = new HashMap<>();
        Map<String, String> global = new HashMap<>();
        Set<String> ambiguousGlobal = new HashSet<>();
        for (Map.Entry<String, ChannelMetadata> entry : metadataById.entrySet()) {
            LogoCandidate candidate = bestLogoByChannel.get(entry.getKey());
            if (candidate == null) continue;
            ChannelMetadata metadata = entry.getValue();
            for (String rawName : metadata.names) {
                String title = ChannelDeduplicator.canonicalTitle(rawName);
                if (title.isEmpty()) continue;
                String exactKey = metadata.country + "|" + title;
                if (!index.containsKey(exactKey)) index.put(exactKey, candidate.url);

                String old = global.get(title);
                if (old == null) global.put(title, candidate.url);
                if (old != null && !old.equals(candidate.url)) ambiguousGlobal.add(title);
            }
        }
        for (String ambiguous : ambiguousGlobal) global.remove(ambiguous);
        for (Map.Entry<String, String> entry : global.entrySet()) {
            index.put("*|" + entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(index);
    }

    private static int formatScore(String format, String url) {
        String address = safe(url).toLowerCase(Locale.ROOT);
        int queryAt = address.indexOf('?');
        if (queryAt >= 0) address = address.substring(0, queryAt);
        if ("PNG".equals(format) || address.endsWith(".png")) return 80;
        if ("WEBP".equals(format) || address.endsWith(".webp")) return 70;
        if ("JPEG".equals(format) || address.endsWith(".jpg") || address.endsWith(".jpeg")) return 60;
        if ("GIF".equals(format) || address.endsWith(".gif")) return 50;
        if ("SVG".equals(format) || address.endsWith(".svg")) return 45;
        return -1;
    }

    private static boolean isSupportedCountry(String code) {
        return "FR".equals(code) || ChannelCatalogPolicy.isArabCode(code)
                || ChannelCatalogPolicy.isMaghrebCode(code);
    }

    private static String download(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(8_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "NISSKENZI+/2.1 (Android TV)");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status);

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_DOCUMENT_BYTES) {
                        throw new java.io.IOException("Logo index is too large.");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Map<String, String> readCache(File file) {
        if (!file.isFile() || file.length() <= 0L || file.length() > MAX_DOCUMENT_BYTES) {
            return Collections.emptyMap();
        }
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject root = new JSONObject(output.toString("UTF-8"));
            JSONObject values = root.optJSONObject("logos");
            if (values == null) return Collections.emptyMap();
            Map<String, String> result = new HashMap<>();
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String url = safe(values.optString(key, ""));
                if (!key.isEmpty() && url.startsWith("https://")) result.put(key, url);
            }
            return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private static void writeCache(File file, Map<String, String> index) {
        try {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, String> entry : index.entrySet()) {
                values.put(entry.getKey(), entry.getValue());
            }
            JSONObject root = new JSONObject();
            root.put("logos", values);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class ChannelMetadata {
        final String country;
        final List<String> names;

        ChannelMetadata(String country, List<String> names) {
            this.country = country;
            this.names = names;
        }
    }

    private static final class LogoCandidate {
        final String url;
        final int score;

        LogoCandidate(String url, int score) {
            this.url = url;
            this.score = score;
        }
    }
}
