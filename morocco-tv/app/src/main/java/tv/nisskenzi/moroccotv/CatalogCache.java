package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/** Persistence for per-source channel catalogues. All file parsing/writing should be
 * called from a worker thread; the quick cache is kept small for fast recovery. */
public final class CatalogCache {
    private static final String PREFS = "catalog_cache";
    private static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_QUICK_BYTES = 1_500_000;

    private CatalogCache() {}

    public static ArrayList<Channel> readQuick(Context context, String source, int maximum) {
        ArrayList<Channel> result = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(prefKey(source), "");
            if (raw == null || raw.isEmpty()) return result;
            if (raw.length() > MAX_QUICK_BYTES) {
                prefs.edit().remove(prefKey(source)).apply();
                return result;
            }
            JSONArray array = new JSONArray(raw);
            boolean wantVavoo = "VAVOO".equals(source);
            for (int i = 0; i < array.length() && result.size() < maximum; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String url = safe(object.optString("url", ""));
                if (url.isEmpty()) continue;
                boolean isVavoo = object.optBoolean("isVavoo", false);
                if (isVavoo != wantVavoo) continue;
                String group = safe(object.optString("group", "Direct"));
                String country = safe(object.optString("country", ""));
                if (country.isEmpty()) country = groupCountry(group, isVavoo);
                result.add(new Channel(object.optString("name", "Chaîne"), url,
                        object.optString("logo", ""), group, isVavoo,
                        object.optString("sourceUrl", ""), country,
                        object.optString("epgId", "")));
            }
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    /** True while the last successful network catalogue refresh is still within its TTL. */
    public static boolean isFresh(Context context, String source, long maxAgeMillis) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long updatedAt = prefs.getLong(prefKey(source) + "_updated_at", 0L);
            long age = System.currentTimeMillis() - updatedAt;
            return updatedAt > 0L && age >= 0L && age <= maxAgeMillis;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void saveQuick(Context context, List<Channel> items, String source, int maximum) {
        if (items == null || items.isEmpty()) return;
        try {
            JSONArray array = new JSONArray();
            int count = 0;
            for (Channel channel : items) {
                if (count++ >= maximum) break;
                array.put(toJson(channel));
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(prefKey(source), array.toString())
                    .putLong(prefKey(source) + "_updated_at", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    public static ArrayList<Channel> readFull(Context context, String source, int maximum) {
        ArrayList<Channel> result = new ArrayList<>();
        File file = fullFile(context, source);
        if (!file.isFile() || file.length() <= 0) return result;
        if (file.length() > MAX_FILE_BYTES) {
            file.delete();
            return result;
        }

        StringBuilder raw = new StringBuilder((int) Math.min(file.length(), 1024 * 1024));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (raw.length() + count > MAX_FILE_BYTES) return new ArrayList<>();
                raw.append(buffer, 0, count);
            }
            JSONArray array = new JSONArray(raw.toString());
            boolean wantVavoo = "VAVOO".equals(source);
            for (int i = 0; i < array.length() && result.size() < maximum; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String url = safe(object.optString("url", ""));
                if (url.isEmpty()) continue;
                boolean isVavoo = object.optBoolean("isVavoo", false);
                if (isVavoo != wantVavoo) continue;
                String group = safe(object.optString("group", "Direct"));
                String country = safe(object.optString("country", ""));
                if (country.isEmpty()) country = groupCountry(group, isVavoo);
                result.add(new Channel(object.optString("name", "Chaîne"), url,
                        object.optString("logo", ""), group, isVavoo,
                        object.optString("sourceUrl", ""), country,
                        object.optString("epgId", "")));
            }
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    public static void saveFull(Context context, List<Channel> items, String source, int maximum) {
        if (items == null || items.isEmpty()) return;
        File target = fullFile(context, source);
        File temporary = new File(context.getFilesDir(), target.getName() + ".tmp");
        File backup = new File(context.getFilesDir(), target.getName() + ".bak");
        try {
            JSONArray array = new JSONArray();
            int count = 0;
            for (Channel channel : items) {
                if (count++ >= maximum) break;
                array.put(toJson(channel));
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(temporary), "UTF-8")) {
                writer.write(array.toString());
                writer.flush();
            }

            if (backup.exists()) backup.delete();
            if (target.exists() && !target.renameTo(backup)) {
                temporary.delete();
                return;
            }
            if (temporary.renameTo(target)) {
                backup.delete();
            } else {
                if (backup.exists()) backup.renameTo(target);
                temporary.delete();
            }
        } catch (Exception ignored) {
            temporary.delete();
            if (!target.exists() && backup.exists()) backup.renameTo(target);
        }
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        for (String source : new String[]{"VAVOO", "IPTV-ORG"}) {
            File full = fullFile(context, source);
            File temporary = new File(context.getFilesDir(), full.getName() + ".tmp");
            File backup = new File(context.getFilesDir(), full.getName() + ".bak");
            if (full.exists()) full.delete();
            if (temporary.exists()) temporary.delete();
            if (backup.exists()) backup.delete();
        }
        FastCatalogCache.clearMemory();
        VavooProvider.invalidateCatalogCache();
    }

    private static JSONObject toJson(Channel channel) throws Exception {
        JSONObject object = new JSONObject();
        object.put("name", safe(channel.name));
        object.put("url", safe(channel.url));
        object.put("logo", safe(channel.logo));
        object.put("group", safe(channel.group));
        object.put("isVavoo", channel.isVavoo);
        object.put("sourceUrl", safe(channel.vavooSourceUrl));
        object.put("country", safe(channel.country));
        object.put("epgId", safe(channel.epgId));
        return object;
    }

    private static File fullFile(Context context, String source) {
        String suffix = "VAVOO".equals(source) ? "vavoo" : "iptv_org";
        return new File(context.getFilesDir(), "nisskenzi_catalog_" + suffix + "_v1.json");
    }

    private static String prefKey(String source) {
        return "VAVOO".equals(source) ? "vavoo_channels" : "iptv_org_channels";
    }

    private static String groupCountry(String group, boolean vavoo) {
        String prefix = vavoo ? "VAVOO · " : "IPTV-ORG · ";
        String country = safe(group);
        if (country.startsWith(prefix)) country = country.substring(prefix.length()).trim();
        return country.isEmpty() ? "Pays non précisé" : country;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
