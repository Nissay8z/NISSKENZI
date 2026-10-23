package tv.nisskenzi.moroccotv;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable data model for a live channel. Search text is precomputed to avoid
 * allocating and normalizing channel strings on every remote-control key press. */
public final class Channel {
    public final String name;
    public final String url;
    public final String logo;
    public final String group;
    public final String vavooSourceUrl;
    public final String country;
    public final boolean isVavoo;
    /** XMLTV id from tvg-id, empty for sources that do not expose a guide identifier. */
    public final String epgId;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern PUNCTUATION_AND_SPACE = Pattern.compile("[\\p{Punct}\\s]+");
    private final String searchIndex;

    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, "");
    }

    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country, String epgId) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, epgId, null);
    }

    /** Package-private constructor used by FastCatalogCache to reuse the stored search index. */
    Channel(String name, String url, String logo, String group, boolean isVavoo,
            String sourceUrl, String country, String epgId, String cachedSearchIndex) {
        this.name = safe(name);
        this.url = safe(url);
        this.logo = safe(logo);
        this.group = safe(group);
        this.isVavoo = isVavoo;
        this.epgId = safe(epgId);
        this.vavooSourceUrl = safe(sourceUrl);
        String cleanCountry = safe(country);
        this.country = cleanCountry.isEmpty() ? "Pays non précisé" : cleanCountry;
        this.searchIndex = cachedSearchIndex == null
                ? normalizeSearch(this.name + " " + this.group + " " + this.country)
                : cachedSearchIndex;
    }

    public String searchIndex() {
        return searchIndex;
    }

    public static String normalizeSearch(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = MARKS.matcher(
                Normalizer.normalize(value.trim(), Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
        return PUNCTUATION_AND_SPACE.matcher(normalized).replaceAll(" ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
