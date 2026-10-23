package tv.nisskenzi.moroccotv;

import java.util.ArrayList;
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

        for (Channel channel : sourceChannels) {
            if (channel == null) continue;
            String source = channel.isVavoo ? "VAVOO" : "IPTV-ORG";
            if (!safe(sourceFilter).equals(source)) continue;
            if (!allCountries && !countryFilter.equals(safeCountry(channel.country))) continue;
            if (useFavorites && !favoriteUrls.contains(channel.url)) continue;
            if (useRecent && !recentUrls.contains(channel.url)) continue;
            if (!query.isEmpty() && !channel.searchIndex().contains(query)) continue;
            result.add(channel);
        }
        return result;
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
