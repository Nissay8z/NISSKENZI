package tv.nisskenzi.moroccotv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Fast, allocation-light filtering used from a worker thread for large playlists. */
public final class ChannelFilter {
    private ChannelFilter() {}

    public static ArrayList<Channel> filter(
            List<Channel> sourceChannels,
            String sourceFilter,
            String activeCountry,
            String searchText,
            boolean favoritesOnly,
            boolean recentOnly,
            Set<String> favoriteUrls,
            Set<String> recentUrls) {

        String query = Channel.normalizeSearch(searchText);
        String countryFilter = safe(activeCountry);
        boolean allCountries = countryFilter.isEmpty() || "Tous les pays".equals(countryFilter);
        boolean useFavorites = favoritesOnly && favoriteUrls != null;
        boolean useRecent = recentOnly && recentUrls != null;
        ArrayList<Channel> result = new ArrayList<>();
        if (sourceChannels == null || sourceChannels.isEmpty()) return result;

        SearchMatcher matcher = query.isEmpty() ? null : new SearchMatcher(query);
        ArrayList<RankedChannel> ranked = matcher == null ? null : new ArrayList<>();
        int position = 0;
        for (Channel channel : sourceChannels) {
            int sourcePosition = position++;
            if ((sourcePosition & 127) == 0 && Thread.currentThread().isInterrupted()) return result;
            if (channel == null) continue;
            String source = channel.isVavoo ? "VAVOO" : "IPTV-ORG";
            if (!safe(sourceFilter).equals(source)) continue;
            if (!allCountries && !countryFilter.equals(safeCountry(channel.country))) continue;

            boolean favorite = favoriteUrls != null && favoriteUrls.contains(channel.url);
            boolean recent = recentUrls != null && recentUrls.contains(channel.url);
            if (useFavorites && !favorite) continue;
            if (useRecent && !recent) continue;

            if (matcher == null) {
                result.add(channel);
                continue;
            }

            int score = matcher.score(channel);
            if (score < 0) continue;
            // Usage history gently breaks ties after text relevance.
            score += favorite ? 20 : 0;
            score += recent ? 10 : 0;
            ranked.add(new RankedChannel(channel, score, sourcePosition));
        }

        if (ranked != null) {
            Collections.sort(ranked, (left, right) -> {
                int byRelevance = Integer.compare(right.score, left.score);
                return byRelevance != 0 ? byRelevance
                        : Integer.compare(left.sourcePosition, right.sourcePosition);
            });
            for (RankedChannel match : ranked) result.add(match.channel);
        }
        return result;
    }

    private static final class RankedChannel {
        final Channel channel;
        final int score;
        final int sourcePosition;

        RankedChannel(Channel channel, int score, int sourcePosition) {
            this.channel = channel;
            this.score = score;
            this.sourcePosition = sourcePosition;
        }
    }

    /** Ranks exact titles and prefixes above metadata and typo-tolerant matches. */
    private static final class SearchMatcher {
        private final String query;
        private final String[] terms;
        private int[] previous;
        private int[] current;

        SearchMatcher(String query) {
            this.query = query;
            this.terms = query.split(" ");
            int longest = 0;
            for (String term : terms) longest = Math.max(longest, term.length());
            this.previous = new int[longest + 1];
            this.current = new int[longest + 1];
        }

        int score(Channel channel) {
            String title = channel.searchTitle();
            if (title.equals(query)) return 100_000;
            if (title.startsWith(query)) return 90_000 - Math.min(999, title.length() - query.length());

            int titlePosition = title.indexOf(query);
            if (titlePosition >= 0) return 80_000 - Math.min(999, titlePosition);

            String index = channel.searchIndex();
            int indexPosition = index.indexOf(query);
            if (indexPosition >= 0) return 70_000 - Math.min(999, indexPosition);

            int totalDistance = 0;
            for (String term : terms) {
                int allowance = maxDistance(term.length());
                int bestDistance = allowance + 1;
                int start = 0;
                while (start < index.length()) {
                    int end = index.indexOf(' ', start);
                    if (end < 0) end = index.length();
                    if (Math.abs((end - start) - term.length()) <= allowance) {
                        int distance = distance(index, start, end, term, allowance);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            if (bestDistance == 0) break;
                        }
                    }
                    start = end + 1;
                }
                if (bestDistance > allowance) return -1;
                totalDistance += bestDistance;
            }
            return 60_000 - totalDistance * 400;
        }

        private int maxDistance(int length) {
            if (length < 4) return 0;
            return length < 7 ? 1 : 2;
        }

        /** Bounded Levenshtein distance reuses two small arrays for the whole filter pass. */
        private int distance(String text, int start, int end, String term, int limit) {
            int candidateLength = end - start;
            int termLength = term.length();
            if (Math.abs(candidateLength - termLength) > limit) return limit + 1;

            for (int j = 0; j <= termLength; j++) previous[j] = j;
            for (int i = 1; i <= candidateLength; i++) {
                current[0] = i;
                int rowMinimum = current[0];
                for (int j = 1; j <= termLength; j++) {
                    int substitution = previous[j - 1]
                            + (text.charAt(start + i - 1) == term.charAt(j - 1) ? 0 : 1);
                    current[j] = Math.min(Math.min(previous[j] + 1,
                            current[j - 1] + 1), substitution);
                    rowMinimum = Math.min(rowMinimum, current[j]);
                }
                if (rowMinimum > limit) return limit + 1;
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            return previous[termLength];
        }
    }

    private static String safeCountry(String value) {
        String country = safe(value);
        return country.isEmpty() || "default".equalsIgnoreCase(country)
                ? "Pays non précisé" : country;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
