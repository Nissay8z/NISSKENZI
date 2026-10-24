package tv.nisskenzi.moroccotv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Folds equivalent entries into one logical channel while retaining every distinct source. */
public final class ChannelDeduplicator {
    public interface SourceRanker {
        int score(Channel source);
    }

    private ChannelDeduplicator() {}

    public static ArrayList<Channel> merge(List<Channel> first, List<Channel> second,
                                           SourceRanker ranker) {
        // Group by canonical title first, then split by known country. Region-only and
        // unknown-country labels are folded into a country group only when that title has
        // exactly one unambiguous country; this avoids both duplicates and cross-country merges.
        Map<String, ArrayList<Channel>> byTitle = new LinkedHashMap<>();
        collect(byTitle, first);
        collect(byTitle, second);

        ArrayList<Channel> result = new ArrayList<>();
        Map<Channel, String> countrySortKeys = new IdentityHashMap<>();
        Map<Channel, Integer> scores = new IdentityHashMap<>();

        for (Map.Entry<String, ArrayList<Channel>> titleEntry : byTitle.entrySet()) {
            String title = titleEntry.getKey();
            ArrayList<Channel> titleChannels = titleEntry.getValue();
            Map<String, ArrayList<Channel>> knownCountryGroups = new LinkedHashMap<>();
            ArrayList<Channel> genericCountryChannels = new ArrayList<>();

            for (Channel channel : titleChannels) {
                String country = safe(channel.country);
                String key = countryKey(country);
                if (isGenericCountry(country)) {
                    genericCountryChannels.add(channel);
                } else {
                    knownCountryGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(channel);
                }
            }

            if (knownCountryGroups.size() == 1) {
                // Recombine in original source order so the existing logical ID remains stable.
                ArrayList<Channel> onlyCountry = knownCountryGroups.values().iterator().next();
                onlyCountry.clear();
                for (Channel channel : titleChannels) onlyCountry.add(channel);
                addGroupedResult(result, countrySortKeys, scores, onlyCountry, title, ranker);
            } else if (knownCountryGroups.isEmpty()) {
                // Region labels such as "Maghreb" and "Pays arabes" are not separate channels.
                addGroupedResult(result, countrySortKeys, scores,
                        titleChannels, title, ranker);
            } else {
                for (ArrayList<Channel> countryChannels : knownCountryGroups.values()) {
                    addGroupedResult(result, countrySortKeys, scores,
                            countryChannels, title, ranker);
                }
                if (!genericCountryChannels.isEmpty()) {
                    // There are multiple real country variants for this title; keep the
                    // ambiguous regional entries together rather than assigning them randomly.
                    addGroupedResult(result, countrySortKeys, scores,
                            genericCountryChannels, title, ranker);
                }
            }
        }

        Collections.sort(result, (left, right) -> {
            int byCountry = countrySortKeys.get(left).compareTo(countrySortKeys.get(right));
            return byCountry != 0 ? byCountry : left.searchTitle().compareTo(right.searchTitle());
        });
        return result;
    }

    private static void collect(Map<String, ArrayList<Channel>> byTitle, List<Channel> channels) {
        if (channels == null) return;
        for (Channel channel : channels) {
            if (channel == null || safe(channel.url).isEmpty()) continue;
            String title = canonicalTitle(channel.name);
            if (title.isEmpty()) title = channel.searchTitle();
            if (title.isEmpty()) continue;
            byTitle.computeIfAbsent(title, ignored -> new ArrayList<>()).add(channel);
        }
    }

    private static void addGroupedResult(ArrayList<Channel> result,
            Map<Channel, String> countrySortKeys, Map<Channel, Integer> scores,
            List<Channel> candidates, String title, SourceRanker ranker) {
        if (candidates == null || candidates.isEmpty()) return;

        ArrayList<Channel> distinct = new ArrayList<>(candidates.size());
        Map<String, Boolean> seenUrls = new LinkedHashMap<>();
        for (Channel channel : candidates) {
            String url = safe(channel.url);
            if (url.isEmpty() || seenUrls.put(url, Boolean.TRUE) != null) continue;
            distinct.add(channel);
        }
        if (distinct.isEmpty()) return;

        for (Channel option : distinct) {
            if (!scores.containsKey(option)) scores.put(option, ranker == null ? 0 : ranker.score(option));
        }
        Collections.sort(distinct, (left, right) -> {
            int byReliability = Integer.compare(scores.get(right), scores.get(left));
            if (byReliability != 0) return byReliability;
            int byLogo = Boolean.compare(!left.logo.isEmpty(), !right.logo.isEmpty());
            if (byLogo != 0) return byLogo;
            if (left.isVavoo != right.isVavoo) return left.isVavoo ? 1 : -1;
            return left.name.compareToIgnoreCase(right.name);
        });

        Channel preferred = distinct.get(0);
        String displayName = displayTitle(preferred.name);
        Channel display = displayName.equals(preferred.name) ? preferred
                : new Channel(displayName, preferred.url, preferred.logo, preferred.group,
                        preferred.isVavoo, preferred.vavooSourceUrl,
                        preferred.country, preferred.epgId);
        String identityCountry = safe(candidates.get(0).country);
        String identity = "channel:" + Channel.normalizeSearch(identityCountry) + "|" + title;
        Channel grouped = Channel.grouped(display, distinct, identity);
        result.add(grouped);
        countrySortKeys.put(grouped, countryKey(grouped.country));
    }

    /**
     * Removes quality markers and market/language suffixes that commonly differ
     * between copies of the same channel in IPTV playlists.
     */
    public static String canonicalTitle(String name) {
        String value = cleanFeedSuffix(name);
        value = value.replaceAll("(?i)\\s*[\\[(]\\s*(?:fr|ma|dz|tn|eh|sa|ae|qa|eg|lb|ly|mr|ps|iq|jo|kw|bh|om|sy|us|uk|gb|be|ch|es|it|de|pt|france|maroc|morocco)\\s*[\\])]\\s*$", " ");
        value = value.replaceAll("(?i)\\b(?:not\\s*24\\s*/?\\s*7|not\\s+24/7)\\b", " ");
        value = value.replaceAll("(?i)\\b(?:full\\s+hd|high\\s+definition|h\\s*[.-]?\\s*264|h\\s*[.-]?\\s*265)\\b", " ");

        String normalized = Channel.normalizeSearch(value)
                .replaceAll("\\bnot\\s+24\\s+7\\b", " ")
                .replaceAll("\\b(?:8k|4k|uhd|fhd|qhd|hd|sd|hdtv|hevc|x264|x265|av1|"
                        + "2160p?|1440p?|1080p?|720p?|576p?|480p?|360p?|h264|h265|"
                        + "backup|bkp|test|alternate|alt|copy)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // Some M3U feeds put the market before the real channel name.
        normalized = normalized.replaceFirst(
                "^(?:fr|ma|dz|tn|eh|sa|ae|qa|eg|lb|ly|mr|ps|iq|jo|kw|bh|om|sy|us|uk|gb|be|ch|es|it|de|pt)\\s+",
                "").trim();

        // Only strip a regional/language qualifier at the end. Do not erase these words
        // from the middle of a real channel name (for example, "Al Arabiya").
        for (int i = 0; i < 3; i++) {
            String withoutMarket = normalized.replaceAll(
                    "\\s+(?:maroc|morocco|france|french|francais|francaise|maghreb|arabic|arabe|international|intl|english|eng|spanish|espanol|portuguese|portugues|global|ma|fr|dz|tn|arab)$",
                    "").trim();
            if (withoutMarket.equals(normalized)) break;
            normalized = withoutMarket;
        }
        return normalized.replaceAll("\\s+", " ").trim();
    }

    /** Produces a readable name while removing feed-only suffixes from the displayed title. */
    private static String displayTitle(String name) {
        String value = cleanFeedSuffix(name);
        value = value.replaceAll("(?i)\\s*\\(\\s*(?:\\d{3,4}\\s*p?|8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                + "backup|test|not\\s+24/7)[^)]*\\)\\s*$", "");
        value = value.replaceAll("(?i)\\s*\\[\\s*(?:\\d{3,4}\\s*p?|8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                + "backup|test|not\\s+24/7)[^\\]]*\\]\\s*$", "");
        value = value.replaceAll("(?i)\\s+(?:8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|"
                + "2160p?|1440p?|1080p?|720p?|576p?|480p?|360p?|backup|bkp|test|copy)\\s*$", "");
        value = value.replaceAll("(?i)\\s+(?:maroc|morocco|france|french|francais|francaise|maghreb|arabic|arabe|international|intl)$", "");
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

    private static boolean isGenericCountry(String country) {
        String code = ChannelCatalogPolicy.countryCode(country);
        if (!code.isEmpty()) return false;
        String value = Channel.normalizeSearch(country);
        if (value.isEmpty()) return true;
        return value.equals("default") || value.equals("unknown") || value.equals("n a")
                || value.equals("none") || value.equals("other") || value.equals("others")
                || value.equals("pays non precise") || value.equals("pays non precis")
                || value.equals("non precise") || value.equals("non specifie")
                || value.equals("pays arabes") || value.equals("monde arabe")
                || value.equals("arab world") || value.equals("arab countries")
                || value.equals("arabic") || value.equals("arabe") || value.equals("arab")
                || value.equals("maghreb") || value.equals("middle east")
                || value.equals("moyen orient") || value.equals("north africa")
                || value.equals("afrique du nord") || value.equals("international")
                || value.equals("international channels") || value.equals("world")
                || value.equals("global") || value.equals("mena") || value.equals("pan arab");
    }

    private static String countryKey(String country) {
        String code = ChannelCatalogPolicy.countryCode(country);
        return code.isEmpty() ? Channel.normalizeSearch(country) : code;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
