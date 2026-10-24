package tv.nisskenzi.moroccotv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Disk snapshot of the already grouped VAVOO + IPTV-org catalogue.
 *
 * Source M3U files are parsed and normalized once; repeat launches can render
 * this snapshot immediately while the packaged catalogues are checked again.
 */
public final class CombinedCatalogCache {
    private static final int MAGIC = 0x4E534B47; // "NSKG"
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_CHANNELS = 30_000;
    private static final int MAX_OPTIONS_PER_CHANNEL = 64;
    private static final String FILE_NAME = "nisskenzi_combined_catalog.bin";

    private CombinedCatalogCache() {}

    public static String assetRevision(Context context) {
        return FastCatalogCache.assetRevision(context, "vavoo_catalog.m3u")
                + "\n" + FastCatalogCache.assetRevision(context, "iptv_org_catalog.m3u");
    }

    public static ArrayList<Channel> read(Context context, String expectedRevision, int maximum,
            ChannelDeduplicator.SourceRanker ranker) {
        ArrayList<Channel> result = new ArrayList<>();
        File file = cacheFile(context);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_CACHE_BYTES) {
            return result;
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION
                    || !safe(expectedRevision).equals(in.readUTF())) {
                return result;
            }
            int count = in.readInt();
            if (count < 1 || count > MAX_CHANNELS || count > maximum) return result;

            for (int i = 0; i < count; i++) {
                String identity = in.readUTF();
                int sourceCount = in.readInt();
                if (sourceCount < 1 || sourceCount > MAX_OPTIONS_PER_CHANNEL) {
                    result.clear();
                    return result;
                }
                ArrayList<Channel> options = new ArrayList<>(sourceCount);
                for (int j = 0; j < sourceCount; j++) {
                    String name = in.readUTF();
                    String url = in.readUTF();
                    String logo = in.readUTF();
                    String group = in.readUTF();
                    boolean isVavoo = in.readBoolean();
                    String sourceUrl = in.readUTF();
                    String country = in.readUTF();
                    String epgId = in.readUTF();
                    String searchIndex = in.readUTF();
                    if (url.isEmpty()) {
                        result.clear();
                        return result;
                    }
                    options.add(new Channel(name, url, logo, group, isVavoo,
                            sourceUrl, country, epgId, searchIndex));
                }
                Collections.sort(options, (left, right) -> {
                    int leftScore = ranker == null ? 0 : ranker.score(left);
                    int rightScore = ranker == null ? 0 : ranker.score(right);
                    int byReliability = Integer.compare(rightScore, leftScore);
                    if (byReliability != 0) return byReliability;
                    int byLogo = Boolean.compare(!left.logo.isEmpty(), !right.logo.isEmpty());
                    if (byLogo != 0) return byLogo;
                    if (left.isVavoo != right.isVavoo) return left.isVavoo ? 1 : -1;
                    return left.name.compareToIgnoreCase(right.name);
                });
                result.add(Channel.grouped(options.get(0), options, identity));
            }
            if (result.size() != count) result.clear();
        } catch (Exception ignored) {
            result.clear();
            file.delete();
        }
        return result;
    }

    public static void write(Context context, String revision, List<Channel> channels, int maximum) {
        if (channels == null || channels.isEmpty()) return;
        int count = Math.min(Math.min(channels.size(), maximum), MAX_CHANNELS);
        if (count < 1) return;

        File target = cacheFile(context);
        File temporary = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        File backup = new File(context.getFilesDir(), FILE_NAME + ".bak");
        try {
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temporary), 64 * 1024))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(safe(revision));
                out.writeInt(count);
                for (int i = 0; i < count; i++) {
                    Channel channel = channels.get(i);
                    out.writeUTF(safe(channel.identity()));
                    List<Channel> options = channel.playbackOptions();
                    int optionCount = Math.min(options.size(), MAX_OPTIONS_PER_CHANNEL);
                    out.writeInt(optionCount);
                    for (int j = 0; j < optionCount; j++) {
                        Channel option = options.get(j);
                        out.writeUTF(safe(option.name));
                        out.writeUTF(safe(option.url));
                        out.writeUTF(safe(option.logo));
                        out.writeUTF(safe(option.group));
                        out.writeBoolean(option.isVavoo);
                        out.writeUTF(safe(option.vavooSourceUrl));
                        out.writeUTF(safe(option.country));
                        out.writeUTF(safe(option.epgId));
                        out.writeUTF(safe(option.searchIndex()));
                    }
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

    public static void clear(Context context) {
        File file = cacheFile(context);
        File temporary = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        File backup = new File(context.getFilesDir(), FILE_NAME + ".bak");
        if (file.exists()) file.delete();
        if (temporary.exists()) temporary.delete();
        if (backup.exists()) backup.delete();
    }

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
