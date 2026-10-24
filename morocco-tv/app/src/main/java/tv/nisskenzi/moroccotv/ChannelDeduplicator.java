package tv.nisskenzi.moroccotv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Folds equivalent stream entries into one channel while retaining every source. */
public final class ChannelDeduplicator {
    public interface SourceRanker {
        int score(Channel source);
    }

    private ChannelDeduplicator() {}

    public static ArrayList<Channel> merge(List<Channel> first, List<Channel> second,
                                           SourceRanker ranker) {
        Map<String, ArrayList<Channel>> groups = new LinkedHashMap<>();
        append(groups, first);
        append(groups, second);

        ArrayList<Channel> result = new ArrayList<>(groups.size());
        Map<Channel, Integer> scores = new IdentityHashMap<>();
        Map<Channel, String> countryKeys = new IdentityHashMap<>();
        for (Map.Entry<String, ArrayList<Channel>> entry : groups.entrySet()) {
            ArrayList<Channel> options = entry.getValue();
            for (Channel option : options) {
                if (!scores.containsKey(option)) {
                    scores.put(option, ranker == null ? 0 : ranker.score(option));
                }
            }
            Collections.sort(options, (left, right) -> {
                int byReliability = Integer.compare(scores.get(right), scores.get(left));
                if (byReliability != 0) return byReliability;
                int byLogo = Boolean.compare(!left.logo.isEmpty(), !right.logo.isEmpty());
                if (byLogo != 0) return byLogo;
                if (left.isVavoo != right.isVavoo) return left.isVavoo ? 1 : -1;
                return left.name.compareToIgnoreCase(right.name);
            });
            Channel preferred = options.get(0);
            Channel grouped = Channel.grouped(preferred, options, "channel:" + entry.getKey());
            result.add(grouped);
            countryKeys.put(grouped, Channel.normalizeSearch(grouped.country));
        }

        Collections.sort(result, (left, right) -> {
            int byCountry = countryKeys.get(left).compareTo(countryKeys.get(right));
            return byCountry != 0 ? byCountry : left.searchTitle().compareTo(right.searchTitle());
        });
        return result;
    }

    private static void append(Map<String, ArrayList<Channel>> groups, List<Channel> channels) {
        if (channels == null) return;
        for (Channel channel : channels) {
            if (channel == null || channel.url.isEmpty()) continue;
            String title = canonicalTitle(channel.name);
            if (title.isEmpty()) title = channel.searchTitle();
            if (title.isEmpty()) continue;
            String key = Channel.normalizeSearch(channel.country) + "|" + title;
            ArrayList<Channel> options = groups.get(key);
            if (options == null) {
                options = new ArrayList<>();
                groups.put(key, options);
            }
            boolean duplicateUrl = false;
            for (Channel existing : options) {
                if (existing.url.equals(channel.url)) {
                    duplicateUrl = true;
                    break;
                }
            }
            if (!duplicateUrl) options.add(channel);
        }
    }

    /** Removes quality and VAVOO variant suffixes before grouping duplicate streams. */
    public static String canonicalTitle(String name) {
        String value = name == null ? "" : name.trim()
                .replaceAll("(?i)\\s*\\.\\s*[sbc]$", "");
        return Channel.normalizeSearch(value)
                .replaceAll("\\b(\\d+)\\s+(eme|er|e)\\b", "$1$2")
                .replaceAll("\\b(8k|4k|uhd|fhd|hd|sd|1080p|720p|576p|480p|1080|720|576|480|backup|test)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
