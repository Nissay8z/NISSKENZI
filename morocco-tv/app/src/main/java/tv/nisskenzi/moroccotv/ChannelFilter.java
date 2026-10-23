package tv.nisskenzi.moroccotv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fast worker-thread filtering over the merged Room catalogue. */
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
        String source = canonicalSource(sourceFilter);
        boolean allSources = source.isEmpty() || "all".equals(source);
        boolean allCountries = countryFilter.isEmpty()
                || "Tous les pays".equalsIgnoreCase(countryFilter);
        boolean useFavorites = favoritesOnly && favoriteUrls != null;
        boolean useRecent = recentOnly && recentUrls != null;
        ArrayList<Channel> result = new ArrayList<>();
        if (sourceChannels == null || sourceChannels.isEmpty()) return result;

        int visited = 0;
        for (Channel channel : sourceChannels) {
            if ((visited++ & 127) == 0 && Thread.currentThread().isInterrupted()) return result;
            if (channel == null) continue;

            if (!allSources && !source.equals(canonicalSource(channel.catalogSource))) continue;
            if (!allCountries && !countryFilter.equals(safeCountry(channel.country))) continue;
            if (useFavorites && !favoriteUrls.contains(channel.url)) continue;
            if (useRecent && !recentUrls.contains(channel.url)) continue;
            if (!query.isEmpty() && !channel.searchIndex().contains(query)) continue;
            result.add(channel);
        }
        return result;
    }

    private static String canonicalSource(String value) {
        String source = safe(value).toLowerCase(Locale.ROOT);
        if ("vavoo".equals(source)) return "vavoo";
        if ("iptv-org".equals(source) || "iptv org".equals(source)
                || "iptv-org-fr".equals(source)) return "iptv-org-fr";
        if ("pluto tv".equals(source) || "pluto".equals(source)
                || "pluto-tv-fr".equals(source)) return "pluto-tv-fr";
        if ("samsung".equals(source) || "samsung tv".equals(source)
                || "samsung-tv-plus".equals(source) || "samsung-tv-fr".equals(source)) return "samsung-tv-fr";
        if ("free-tv".equals(source) || "free-tv-world".equals(source)) return "free-tv-world";
        if ("all".equals(source) || "toutes".equals(source) || "toutes les sources".equals(source)) return "all";
        return source;
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
