package tv.nisskenzi.moroccotv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Reads the prebuilt, all-country VAVOO catalogue embedded by the scheduled
 * GitHub Actions refresh job. Entries contain catalogue references only:
 * playback still uses VavooProvider.resolveStream() as before.
 */
public final class VavooSnapshotProvider {
    public interface CountryResolver {
        String resolve(String rawCountry);
    }

    private static final String ASSET_NAME = "vavoo_catalog.json";
    private static final int MAX_ASSET_BYTES = 32 * 1024 * 1024;

    private VavooSnapshotProvider() {}

    public static ArrayList<Channel> readBundled(Context context, int maximum,
                                                  CountryResolver countryResolver) {
        ArrayList<Channel> result = new ArrayList<>();
        try (InputStream input = context.getAssets().open(ASSET_NAME);
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
                String country = countryResolver == null ? rawCountry : countryResolver.resolve(rawCountry);
                if (country == null || country.trim().isEmpty()) {
                    country = countryResolver == null ? "" : countryResolver.resolve(group);
                }
                if (country == null || country.trim().isEmpty()) country = "Pays non précisé";

                String id = safe(item.optString("id", ""));
                if (id.isEmpty()) id = stableId(name, rawCountry.isEmpty() ? country : rawCountry);

                if (!group.startsWith("VAVOO · ")) group = "VAVOO · " + group;
                result.add(new Channel(name, "vavoo://" + id,
                        safe(item.optString("logo", "")), group, true, sourceUrl, country));
            }
        } catch (Exception ignored) {
            // An absent or invalid snapshot must never prevent the live API fallback.
            result.clear();
        }
        return result;
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
