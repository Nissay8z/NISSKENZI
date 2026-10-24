package tv.nisskenzi.moroccotv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact per-source binary cache for bundled M3U catalogues.
 *
 * The first launch parses the M3U and writes this cache. Later source switches
 * read the already-parsed fields and precomputed search indexes instead of
 * rebuilding thousands of Channel instances and normalizing every string again.
 * A revision line in the packaged M3U invalidates the cache when catalogue data
 * changes in a future APK. This class stores catalogue metadata only; it never
 * rewrites stream URLs or changes how either provider resolves a selected stream.
 */
public final class FastCatalogCache {
    private static final int MAGIC = 0x4E534B43; // "NSKC"
    private static final int FORMAT_VERSION = 2;
    private static final long MAX_CACHE_BYTES = 48L * 1024L * 1024L;
    private static final int MAX_STORED_CHANNELS = 30_000;

    private FastCatalogCache() {}

    public static String assetRevision(Context context, String assetName) {
        String firstLine = "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(assetName), "UTF-8"), 1024)) {
            for (int i = 0; i < 8; i++) {
                String line = reader.readLine();
                if (line == null) break;
                line = line.trim();
                if (i == 0) firstLine = line;
                if (line.startsWith("#NISSKENZI-CATALOG ")
                        || line.startsWith("#NISSKENZI-IPTV-CATALOG ")) {
                    return line;
                }
            }
        } catch (Exception ignored) {
            return "missing:" + assetName;
        }
        // Marker-less catalogues are legacy inputs. Include their first-line
        // signature so they can still be cached safely until the updater adds a marker.
        return "legacy:" + assetName + ":" + firstLine;
    }

    public static ArrayList<Channel> read(Context context, String source, String assetName,
                                          int maximum, int minimumValidChannels) {
        String revision = assetRevision(context, assetName);
        File file = cacheFile(context, source);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_CACHE_BYTES) {
            return new ArrayList<>();
        }

        ArrayList<Channel> loaded = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                file.delete();
                return loaded;
            }
            if (!source.equals(in.readUTF()) || !revision.equals(in.readUTF())) {
                return loaded;
            }

            int count = in.readInt();
            if (count < minimumValidChannels || count > MAX_STORED_CHANNELS || count > maximum) {
                return loaded;
            }

            boolean expectedVavoo = "VAVOO".equals(source);
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                String url = in.readUTF();
                String logo = in.readUTF();
                String group = in.readUTF();
                boolean isVavoo = in.readBoolean();
                String sourceUrl = in.readUTF();
                String country = in.readUTF();
                String epgId = in.readUTF();
                String searchIndex = in.readUTF();
                if (url.isEmpty() || isVavoo != expectedVavoo) {
                    file.delete();
                    return new ArrayList<>();
                }
                loaded.add(new Channel(name, url, logo, group, isVavoo,
                        sourceUrl, country, epgId, searchIndex));
            }
            if (loaded.size() < minimumValidChannels) {
                loaded.clear();
            }
        } catch (Exception ignored) {
            loaded.clear();
            file.delete();
        }
        return loaded;
    }

    public static void write(Context context, String source, String assetName,
                             List<Channel> channels, int maximum, int minimumValidChannels) {
        if (channels == null || channels.size() < minimumValidChannels) return;
        String revision = assetRevision(context, assetName);
        int count = Math.min(Math.min(channels.size(), maximum), MAX_STORED_CHANNELS);
        if (count < minimumValidChannels) return;

        File target = cacheFile(context, source);
        File temporary = new File(context.getFilesDir(), target.getName() + ".tmp");
        File backup = new File(context.getFilesDir(), target.getName() + ".bak");
        try {
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temporary), 64 * 1024))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(source);
                out.writeUTF(revision);
                out.writeInt(count);
                for (int i = 0; i < count; i++) {
                    Channel channel = channels.get(i);
                    out.writeUTF(safe(channel.name));
                    out.writeUTF(safe(channel.url));
                    out.writeUTF(safe(channel.logo));
                    out.writeUTF(safe(channel.group));
                    out.writeBoolean(channel.isVavoo);
                    out.writeUTF(safe(channel.vavooSourceUrl));
                    out.writeUTF(safe(channel.country));
                    out.writeUTF(safe(channel.epgId));
                    out.writeUTF(safe(channel.searchIndex()));
                }
                out.flush();
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

    private static File cacheFile(Context context, String source) {
        String suffix = "VAVOO".equals(source) ? "vavoo" : "iptv_org";
        return new File(context.getFilesDir(), "nisskenzi_fast_catalog_" + suffix + ".bin");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
