package tv.nisskenzi.moroccotv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable data model for a live channel or a logical channel with several sources. */
public final class Channel {
    public final String name;
    /** Playback URL for the currently preferred source. */
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
    private final String searchTitle;
    private final String channelIdentity;
    /** Original stream entries in reliability order; empty for raw source records. */
    public final List<Channel> sourceOptions;

    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, "");
    }

    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country, String epgId) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, epgId, null);
    }

    /** Package-private constructor used by FastCatalogCache to reuse a stored search index. */
    Channel(String name, String url, String logo, String group, boolean isVavoo,
            String sourceUrl, String country, String epgId, String cachedSearchIndex) {
        this(name, url, logo, group, isVavoo, sourceUrl, country, epgId,
                cachedSearchIndex, url, Collections.emptyList());
    }

    private Channel(String name, String url, String logo, String group, boolean isVavoo,
                    String sourceUrl, String country, String epgId, String cachedSearchIndex,
                    String identity, List<Channel> options) {
        this.name = safe(name);
        this.url = safe(url);
        this.logo = safe(logo);
        this.group = safe(group);
        this.isVavoo = isVavoo;
        this.epgId = safe(epgId);
        this.vavooSourceUrl = safe(sourceUrl);
        String cleanCountry = safe(country);
        this.country = cleanCountry.isEmpty() ? "Pays non précisé" : cleanCountry;
        this.channelIdentity = safe(identity).isEmpty() ? this.url : safe(identity);
        this.sourceOptions = options == null || options.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(options));
        this.searchTitle = normalizeSearch(this.name);
        this.searchIndex = cachedSearchIndex == null
                ? normalizeSearch(this.name + " " + this.group + " " + this.country)
                : cachedSearchIndex;
    }

    static Channel grouped(Channel preferred, List<Channel> options, String identity) {
        String displayGroup = safe(preferred.group);
        if (options != null && options.size() > 1) {
            displayGroup = (displayGroup.isEmpty() ? "Direct" : displayGroup)
                    + " · " + options.size() + " sources";
        }
        String displayLogo = safe(preferred.logo);
        if (displayLogo.isEmpty() && options != null) {
            for (Channel option : options) {
                if (option != null && !safe(option.logo).isEmpty()) {
                    displayLogo = option.logo;
                    break;
                }
            }
        }
        return new Channel(preferred.name, preferred.url, displayLogo, displayGroup,
                preferred.isVavoo, preferred.vavooSourceUrl, preferred.country, preferred.epgId,
                null, identity, options);
    }

    /** Copies this channel with a fallback logo while keeping its logical identity and sources. */
    public Channel withLogo(String nextLogo) {
        String resolved = safe(nextLogo);
        if (resolved.equals(logo)) return this;
        return new Channel(name, url, resolved, group, isVavoo, vavooSourceUrl, country, epgId,
                null, channelIdentity, sourceOptions);
    }

    public String identity() {
        return channelIdentity;
    }

    public int sourceCount() {
        return sourceOptions.isEmpty() ? 1 : sourceOptions.size();
    }

    public List<Channel> playbackOptions() {
        return sourceOptions.isEmpty() ? Collections.singletonList(this) : sourceOptions;
    }

    /** Matches both new logical IDs and older saved stream URLs. */
    public boolean matchesSavedIds(Set<String> savedIds) {
        if (savedIds == null || savedIds.isEmpty()) return false;
        if (savedIds.contains(channelIdentity) || savedIds.contains(url)) return true;
        for (Channel option : sourceOptions) {
            if (savedIds.contains(option.identity()) || savedIds.contains(option.url)) return true;
        }
        return false;
    }

    public String searchIndex() {
        return searchIndex;
    }

    /** Precomputed normalized title used to rank direct channel-name matches. */
    public String searchTitle() {
        return searchTitle;
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
