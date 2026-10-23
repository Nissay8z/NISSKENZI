package tv.nisskenzi.moroccotv;

import java.text.Normalizer;
import java.util.Locale;

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
    private final String searchIndex;

    public Channel(String name, String url, String logo, String group,
                   boolean isVavoo, String sourceUrl, String country) {
        this.name = safe(name);
        this.url = safe(url);
        this.logo = safe(logo);
        this.group = safe(group);
        this.isVavoo = isVavoo;
        this.vavooSourceUrl = safe(sourceUrl);
        this.country = safe(country).isEmpty() ? "Pays non précisé" : safe(country);
        this.searchIndex = normalizeSearch(this.name + " " + this.group + " " + this.country);
    }

    public String searchIndex() {
        return searchIndex;
    }

    public static String normalizeSearch(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
