package tv.nisskenzi.moroccotv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
            String displayName = displayTitle(preferred.name);
            Channel display = displayName.equals(preferred.name) ? preferred
                    : new Channel(displayName, preferred.url, preferred.logo, preferred.group,
                            preferred.isVavoo, preferred.vavooSourceUrl,
                            preferred.country, preferred.epgId);
            Channel grouped = Channel.grouped(display, options, "channel:" + entry.getKey());
            result.add(grouped);
            countryKeys.put(grouped, countryKey(grouped.country));
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
            String key = countryKey(channel.country) + "|" + title;
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

    /**
     * Strips feed labels and broadcast quality markers before comparing names.
     * Punctuation normalization deliberately makes HD and HD+ the same marker.
     */
    public static String canonicalTitle(String name) {
        String value = cleanFeedSuffix(name);
        value = value.replaceAll("(?i)\\b(?:not\\s*24\\s*/?\\s*7|not\\s+24/7)\\b", " ");
        value = value.replaceAll("(?i)\\b(?:full\\s+hd|high\\s+definition|h\\s*[.-]?\\s*264|h\\s*[.-]?\\s*265)\\b", " ");
        value = Channel.normalizeSearch(value)
                .replaceAll("\\bnot\\s+24\\s+7\\b", " ")
                .replaceAll("\\b(?:8k|4k|uhd|fhd|qhd|hd|sd|hdtv|hevc|x264|x265|av1|"
                        + "2160p?|1440p?|1080p?|720p?|576p?|480p?|360p?|h264|h265|"
                        + "backup|bkp|test|alternate|alt|copy)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return value;
    }

    /** Removes only trailing presentation metadata, keeping the channel's original spelling. */
    private static String displayTitle(String name) {
        String value = cleanFeedSuffix(name);
        value = value.replaceAll("(?i)\\s*\\(\\s*(?:\\d{3,4}\\s*p?|8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                + "backup|test|not\\s+24/7)[^)]*\\)\\s*$", "");
        value = value.replaceAll("(?i)\\s*\\[\\s*(?:\\d{3,4}\\s*p?|8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                + "backup|test|not\\s+24/7)[^\\]]*\\]\\s*$", "");
        value = value.replaceAll("(?i)\\s+(?:8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                + "2160p?|1440p?|1080p?|720p?|576p?|480p?|360p?|backup|bkp|test|copy)\\s*$", "");
        value = value.replaceAll("[\\s._-]+$", "").trim();
        return value.isEmpty() ? (name == null ? "" : name.trim()) : value;
    }

    private static String cleanFeedSuffix(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("(?i)\\s*[._-]\\s*[sbc]\\s*$", " ")
                .replaceAll("(?i)\\s*\\(\\s*(?:\\d{3,4}\\s*p?|8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                        + "backup|test|not\\s+24/7)\\s*\\)\\s*$", " ")
                .replaceAll("(?i)\\s*\\[\\s*(?:\\d{3,4}\\s*p?|8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                        + "backup|test|not\\s+24/7)\\s*\\]\\s*$", " ")
                .replaceAll("(?i)\\s*[._-]\\s*[sbc]\\s*$", " ");
    }

    private static String countryKey(String country) {
        String code = ChannelCatalogPolicy.countryCode(country);
        return code.isEmpty() ? Channel.normalizeSearch(country) : code;
    }
}
