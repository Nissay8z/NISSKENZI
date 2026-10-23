package tv.nisskenzi.moroccotv;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable row model passed from the local catalogue into the native UI/player. */
public final class Channel {
    public final String name;
    public final String url;
    public final String logo;
    public final String group;
    public final String vavooSourceUrl;
    public final String country;
    /** True only when playback must call VavooProvider.resolveStream(). */
    public final boolean isVavoo;
    /** Original XMLTV identifier from tvg-id, when supplied by the playlist. */
    public final String epgId;
    /** Stable catalogue identity: vavoo or iptv-org-fr. */
    public final String catalogSource;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern PUNCTUATION_AND_SPACE = Pattern.compile("[\\p{Punct}\\s]+");
    private final String searchIndex;

    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, "", null);
    }

    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country, String epgId) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, epgId, null);
    }

    /** Compatibility constructor for previously cached channel rows. */
    Channel(String name, String url, String logo, String group, boolean isVavoo,
            String sourceUrl, String country, String epgId, String cachedSearchIndex) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, epgId,
                inferSourceId(isVavoo, group), cachedSearchIndex);
    }

    /** Full constructor used by M3UParser and the SQLite/Room mapping layer. */
    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country, String epgId,
                   String catalogSource, String cachedSearchIndex) {
        this.name = safe(name);
        this.url = safe(url);
        this.logo = safe(logo);
        this.group = safe(group);
        this.isVavoo = isVavoo;
        this.epgId = safe(epgId);
        this.vavooSourceUrl = safe(sourceUrl);
        String cleanCountry = safe(country);
        this.country = cleanCountry.isEmpty() ? "Pays non précisé" : cleanCountry;
        String source = safe(catalogSource);
        this.catalogSource = source.isEmpty() ? inferSourceId(isVavoo, group) : source;
        this.searchIndex = cachedSearchIndex == null
                ? normalizeSearch(this.name + " " + this.group + " " + this.country
                        + " " + this.catalogSource)
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

    private static String inferSourceId(boolean isVavoo, String group) {
        if (isVavoo) return "vavoo";
        String value = safe(group).toLowerCase(Locale.ROOT);
        if (value.startsWith("iptv-org") || value.startsWith("iptv org")) return "iptv-org-fr";
        return "iptv-org-fr";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
