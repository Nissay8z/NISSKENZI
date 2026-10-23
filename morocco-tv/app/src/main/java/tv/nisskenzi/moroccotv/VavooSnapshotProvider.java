package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Reads the prebuilt all-country VAVOO metadata catalogue.
 *
 * The M3U is parsed as a stream, line by line, to avoid building a giant
 * JSONObject tree. The stream itself is NOT included in this file: each row
 * carries the original VAVOO catalogue reference, which the existing
 * VavooProvider.resolveStream() still resolves after a user selects a channel.
 */
public final class VavooSnapshotProvider {
    public interface CountryResolver {
        String resolve(String rawCountry);
    }

    private static final String M3U_ASSET_NAME = "vavoo_catalog.m3u";
    private static final String LEGACY_JSON_ASSET_NAME = "vavoo_catalog.json";
    private static final int MAX_ASSET_BYTES = 32 * 1024 * 1024;

    private VavooSnapshotProvider() {}

    public static ArrayList<Channel> readBundled(Context context, int maximum,
                                                  CountryResolver countryResolver) {
        ArrayList<Channel> m3u = readM3u(context, maximum, countryResolver);
        if (m3u.size() >= 500) return m3u;

        // Transitional fallback for old APKs/builds that contain only the JSON snapshot.
        ArrayList<Channel> legacy = readLegacyJson(context, maximum, countryResolver);
        return legacy.size() > m3u.size() ? legacy : m3u;
    }

    private static ArrayList<Channel> readM3u(Context context, int maximum,
                                               CountryResolver countryResolver) {
        ArrayList<Channel> result = new ArrayList<>();
        try (InputStream input = context.getAssets().open(M3U_ASSET_NAME);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"), 64 * 1024)) {
            String line;
            String id = "";
            String name = "";
            String logo = "";
            String group = "VAVOO Live";
            String country = "";
            long bytes = 0L;

            while ((line = reader.readLine()) != null) {
                bytes += line.length() + 1L;
                if (bytes > MAX_ASSET_BYTES) return new ArrayList<>();
                if (Thread.currentThread().isInterrupted()) return new ArrayList<>();

                if (line.startsWith("#EXTINF:")) {
                    id = attribute(line, "tvg-id");
                    name = attribute(line, "tvg-name");
                    if (name.isEmpty()) {
                        int comma = line.lastIndexOf(',');
                        name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                    }
                    logo = attribute(line, "tvg-logo");
                    group = attribute(line, "group-title");
                    if (group.isEmpty()) group = "VAVOO Live";
                    country = attribute(line, "tvg-country");
                    continue;
                }

                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.startsWith("vavoo-catalog://")) {
                    id = name = logo = "";
                    group = "VAVOO Live";
                    country = "";
                    continue;
                }

                String token = line.substring("vavoo-catalog://".length()).trim();
                String sourceUrl;
                try {
                    byte[] decoded = Base64.decode(token,
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    sourceUrl = new String(decoded, StandardCharsets.UTF_8).trim();
                } catch (Exception invalidToken) {
                    sourceUrl = "";
                }

                if (!sourceUrl.isEmpty() && !name.trim().isEmpty()) {
                    String resolvedCountry = resolveCountry(country, group, countryResolver);
                    String stable = id.trim().isEmpty() ? stableId(name, resolvedCountry) : id.trim();
                    String finalGroup = group.startsWith("VAVOO · ") ? group : "VAVOO · " + group;
                    result.add(new Channel(name, "vavoo://" + stable, logo, finalGroup,
                            true, sourceUrl, resolvedCountry));
                    if (result.size() >= maximum) break;
                }

                id = name = logo = "";
                group = "VAVOO Live";
                country = "";
            }
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    private static ArrayList<Channel> readLegacyJson(Context context, int maximum,
                                                      CountryResolver countryResolver) {
        ArrayList<Channel> result = new ArrayList<>();
        try (InputStream input = context.getAssets().open(LEGACY_JSON_ASSET_NAME);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_ASSET_BYTES) return result;
                output.write(buffer, 0, count);
            }

            JSONObject root = new JSONObject(output.toString("UTF-8"));
            JSONArray items = root.optJSONArray("channels");
            if (items == null) return result;

            for (int i = 0; i < items.length() && result.size() < maximum; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String name = safe(item.optString("name", "Chaîne VAVOO"));
                String sourceUrl = safe(item.optString("url", ""));
                if (sourceUrl.isEmpty()) continue;
                String group = safe(item.optString("group", "VAVOO Live"));
                String rawCountry = safe(item.optString("country", ""));
                String country = resolveCountry(rawCountry, group, countryResolver);
                String id = safe(item.optString("id", ""));
                if (id.isEmpty()) id = stableId(name, rawCountry.isEmpty() ? country : rawCountry);
                if (!group.startsWith("VAVOO · ")) group = "VAVOO · " + group;
                result.add(new Channel(name, "vavoo://" + id, safe(item.optString("logo", "")),
                        group, true, sourceUrl, country));
            }
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    private static String resolveCountry(String rawCountry, String group, CountryResolver resolver) {
        String country = safe(rawCountry);
        if (resolver != null) {
            String known = resolver.resolve(country);
            if (known == null || known.trim().isEmpty()) known = resolver.resolve(group);
            if (known != null && !known.trim().isEmpty()) return known.trim();
        }
        return country.isEmpty() ? "Pays non précisé" : country;
    }

    private static String attribute(String line, String key) {
        String marker = key + "=";
        int at = line.indexOf(marker);
        if (at < 0) return "";
        int start = at + marker.length();
        if (start >= line.length()) return "";
        if (line.charAt(start) != '"') {
            int end = line.indexOf(' ', start);
            if (end < 0) end = line.length();
            return line.substring(start, end).trim();
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (escaped) {
                value.append(ch);
                escaped = false;
            } else if (ch == '\\\\') {
                escaped = true;
            } else if (ch == '"') {
                break;
            } else {
                value.append(ch);
            }
        }
        return value.toString().trim();
    }

    private static String stableId(String name, String country) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest((safe(country).toLowerCase() + "|" + safe(name).toLowerCase())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < bytes.length && i < 11; i++) {
                out.append(String.format("%02x", bytes[i] & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return safe(country) + "-" + safe(name);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
