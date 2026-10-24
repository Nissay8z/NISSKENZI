package tv.nisskenzi.moroccotv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable data model for a live channel.
 *
 * Supports single-source channels and smart multi-source channel folders.
 * Search text is precomputed to avoid allocating and normalizing channel strings
 * on every remote-control key press.
 */
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

    /** Titre nettoyé de la chaîne sans suffixes techniques (ex: "TF1", "CANAL+ CINEMA"). */
    public final String cleanTitle;
    /** Libellé de résolution détecté ("4K", "FHD", "HD", "SD" ou ""). */
    public final String qualityLabel;
    /** Score de qualité calculé pour trier et élire la meilleure source. */
    public final int qualityScore;
    /** Liste ordonnée des sources alternatives (la meilleure source en position 0). */
    public final List<Channel> sources;

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
        this(name, url, logo, group, isVavoo, sourceUrl, country, epgId, cachedSearchIndex, null);
    }

    /** Constructeur complet avec support de sources multiples (dossier de chaînes). */
    public Channel(String name, String url, String logo, String group, boolean isVavoo,
                   String sourceUrl, String country, String epgId, String cachedSearchIndex,
                   List<Channel> sources) {
        this.name = safe(name);
        this.url = safe(url);
        this.logo = safe(logo);
        this.group = safe(group);
        this.isVavoo = isVavoo;
        this.epgId = safe(epgId);
        this.vavooSourceUrl = safe(sourceUrl);
        String cleanCountry = safe(country);
        this.country = cleanCountry.isEmpty() ? "Pays non précisé" : cleanCountry;

        this.cleanTitle = SmartChannelGrouper.cleanChannelName(this.name);
        this.qualityLabel = SmartChannelGrouper.detectQualityLabel(this.name, this.url);
        this.qualityScore = SmartChannelGrouper.calculateQualityScore(this.name, this.url, !this.logo.isEmpty(), this.isVavoo);

        if (sources == null || sources.isEmpty()) {
            this.sources = Collections.singletonList(this);
        } else {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }

        this.searchIndex = cachedSearchIndex == null
                ? buildSearchIndex()
                : cachedSearchIndex;
    }

    private String buildSearchIndex() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.cleanTitle).append(" ").append(this.name).append(" ")
                .append(this.group).append(" ").append(this.country);
        if (sources != null) {
            for (Channel s : sources) {
                if (s != null) {
                    sb.append(" ").append(s.name);
                    if (!s.qualityLabel.isEmpty()) sb.append(" ").append(s.qualityLabel);
                }
            }
        }
        return normalizeSearch(sb.toString());
    }

    public boolean hasMultipleSources() {
        return sources != null && sources.size() > 1;
    }

    public int getSourceCount() {
        return sources == null ? 1 : sources.size();
    }

    public List<Channel> getSources() {
        return sources == null ? Collections.singletonList(this) : sources;
    }

    public Channel getBestSource() {
        return (sources == null || sources.isEmpty()) ? this : sources.get(0);
    }

    public String getBestQualityBadge() {
        if (sources != null && !sources.isEmpty()) {
            for (Channel s : sources) {
                if (s != null && "4K".equals(s.qualityLabel)) return "4K";
            }
            for (Channel s : sources) {
                if (s != null && "FHD".equals(s.qualityLabel)) return "FHD";
            }
            for (Channel s : sources) {
                if (s != null && "HD".equals(s.qualityLabel)) return "HD";
            }
            for (Channel s : sources) {
                if (s != null && "SD".equals(s.qualityLabel)) return "SD";
            }
        }
        return qualityLabel;
    }

    public boolean matchesAnyUrl(Set<String> urls) {
        if (urls == null || urls.isEmpty()) return false;
        if (urls.contains(this.url)) return true;
        if (sources != null) {
            for (Channel s : sources) {
                if (s != null && urls.contains(s.url)) return true;
            }
        }
        return false;
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
