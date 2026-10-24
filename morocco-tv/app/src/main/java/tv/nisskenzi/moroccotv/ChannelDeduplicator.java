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
        String displayCountry = safe(preferred.country);
        if (isGenericCountry(displayCountry)) {
            for (Channel candidate : candidates) {
                if (!isGenericCountry(candidate.country)) {
                    displayCountry = safe(candidate.country);
                    break;
                }
            }
        }
        boolean copyDisplay = !displayName.equals(preferred.name)
                || !displayCountry.equals(preferred.country);
        Channel display = copyDisplay
                ? new Channel(displayName, preferred.url, preferred.logo, preferred.group,
                        preferred.isVavoo, preferred.vavooSourceUrl,
                        displayCountry, preferred.epgId)
                : preferred;
        String identityCountry = safe(candidates.get(0).country);
        String identity = "channel:" + Channel.normalizeSearch(identityCountry) + "|" + title;
        Channel grouped = Channel.grouped(display, distinct, identity);
        result.add(grouped);
        countrySortKeys.put(grouped, countryKey(grouped.country));
    }

    /**
     * Uses a conservative key: exact channel names always share a source dossier.
     * A suffix such as HD/4K is ignored only for an explicitly known channel, so
     * semantic variants (Arabic/English, Sport/News, channel numbers, etc.) stay apart.
     */
    public static String canonicalTitle(String name) {
        String normalized = Channel.normalizeSearch(cleanFeedSuffix(name))
                .replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return "";

        String knownVariant = knownChannelVariantBase(normalized);
        return knownVariant == null ? normalized : knownVariant;
    }

    private static final java.util.Set<String> WELL_KNOWN_CHANNELS;
    static {
        java.util.Set<String> known = new java.util.HashSet<>();
        Collections.addAll(known,
                // Morocco / Maghreb
                "al aoula", "2m", "arryadia", "al maghribia", "athaqafia",
                "assadissa", "tamazight", "medi1 tv", "snrt news",
                // France
                "tf1", "france 2", "france 3", "france 4", "france 5",
                "france 24", "france 24 arabic", "france 24 english",
                "m6", "w9", "tfx", "tmc", "c8", "cnews", "lci", "bfm tv",
                "arte", "tv5 monde", "gulli", "6ter", "rmc story",
                "rmc decouverte", "cherie 25", "nrj 12", "cstar",
                "l equipe", "eurosport 1", "eurosport 2", "canal",
                "canal plus", "canal sport", "canal cinema", "paris premiere",
                "public senat", "la chaine parlementaire",
                // Popular international channels. Language editions remain separate.
                "cnn", "cnn arabic", "cnn international",
                "bbc news", "bbc world news", "bbc arabic",
                "al jazeera", "al jazeera arabic", "al jazeera english",
                "al arabiya", "al arabiya english", "al hadath",
                "sky news", "sky news arabia",
                "bein sports", "bein sports 1", "bein sports 2",
                "bein sports 3", "bein sports news",
                "mbc 1", "mbc 2", "mbc 3", "mbc 4", "mbc action",
                "mbc drama", "mbc max", "rotana cinema", "rotana classic",
                "rotana music", "rotana khalijia", "dw arabic", "dw english",
                "france info", "franceinfo", "lcp", "equidia");
        WELL_KNOWN_CHANNELS = Collections.unmodifiableSet(known);
    }

    /**
     * Returns a known channel's stable key only when removing feed/quality/market
     * decoration lands on a recognised title. Language and edition qualifiers are
     * deliberately not removed: "France 24 Arabic" is not "France 24 English".
     */
    private static String knownChannelVariantBase(String normalized) {
        String candidate = normalized;
        for (int i = 0; i < 8; i++) {
            if (WELL_KNOWN_CHANNELS.contains(candidate)) return candidate;

            String next = candidate.replaceFirst(
                    "\\s+(?:8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|hdtv|hevc|x264|x265|av1|"
                            + "2160p?|1440p?|1080p?|720p?|576p?|480p?|360p?|h264|h265)$", "")
                    .trim();
            if (!next.equals(candidate)) {
                candidate = next;
                continue;
            }

            // Market/country tags are decoration only when the remaining name is known.
            next = candidate.replaceFirst(
                    "\\s+(?:maroc|morocco|france|french|francais|francaise|"
                            + "ma|fr|dz|tn|eh|sa|ae|qa|eg|lb|ly|mr|ps|iq|jo|kw|bh|om|sy|"
                            + "us|uk|gb|be|ch|es|it|de|pt)$", "").trim();
            if (!next.equals(candidate)) {
                candidate = next;
                continue;
            }

            // Some playlists prefix the market code instead of appending it.
            next = candidate.replaceFirst(
                    "^(?:fr|ma|dz|tn|eh|sa|ae|qa|eg|lb|ly|mr|ps|iq|jo|kw|bh|om|sy|"
                            + "us|uk|gb|be|ch|es|it|de|pt)\\s+", "").trim();
            if (!next.equals(candidate)) {
                candidate = next;
                continue;
            }
            break;
        }
        return WELL_KNOWN_CHANNELS.contains(candidate) ? candidate : null;
    }

    /** Keeps semantic suffixes visible; cleans decoration only for known variants. */
    private static String displayTitle(String name) {
        String value = cleanFeedSuffix(name).trim();
        String canonical = canonicalTitle(name);
        if (canonical.isEmpty() || !WELL_KNOWN_CHANNELS.contains(canonical)) {
            return value;
        }

        // Mirror the canonicalisation in the visible title, preserving the source's casing.
        for (int i = 0; i < 8; i++) {
            if (Channel.normalizeSearch(value).replaceAll("\\s+", " ").trim().equals(canonical)) {
                break;
            }
            String next = value.replaceFirst(
                    "(?i)\\s*[\\[(]?\\s*(?:8k|4k|uhd|fhd|qhd|hd\\s*\\+?|sd|hdtv|hevc|x264|x265|av1|"
                            + "2160p?|1440p?|1080p?|720p?|576p?|480p?|360p?|h264|h265)\\s*[\\])]?$", "")
                    .trim();
            if (!next.equals(value)) {
                value = next;
                continue;
            }
            next = value.replaceFirst(
                    "(?i)\\s+(?:maroc|morocco|france|french|francais|francaise|"
                            + "ma|fr|dz|tn|eh|sa|ae|qa|eg|lb|ly|mr|ps|iq|jo|kw|bh|om|sy|"
                            + "us|uk|gb|be|ch|es|it|de|pt)$", "").trim();
            if (!next.equals(value)) {
                value = next;
                continue;
            }
            next = value.replaceFirst(
                    "(?i)^(?:fr|ma|dz|tn|eh|sa|ae|qa|eg|lb|ly|mr|ps|iq|jo|kw|bh|om|sy|"
                            + "us|uk|gb|be|ch|es|it|de|pt)\\s+", "").trim();
            if (!next.equals(value)) {
                value = next;
                continue;
            }
            break;
        }
        value = value.replaceAll("[\\s._-]+$", "").trim();
        return value.isEmpty() ? name == null ? "" : name.trim() : value;
    }

    /** Feed-only marker cleanup. These suffixes are transport labels, not channel names. */
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
