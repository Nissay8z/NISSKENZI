package tv.nisskenzi.moroccotv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Country and genre rules for the NISSKENZI+ catalogues. */
public final class ChannelCatalogPolicy {
    private static final Set<String> ARAB_CODES;
    private static final Set<String> MAGHREB_CODES;
    private static final Set<String> ALLOWED_VAVOO_CODES;
    private static final Map<String, String> COUNTRY_CODES;
    private static final List<String> GENRES;

    static {
        HashSet<String> arab = new HashSet<>();
        Collections.addAll(arab, "DZ", "BH", "KM", "DJ", "EG", "IQ", "JO", "KW",
                "LB", "LY", "MR", "MA", "OM", "PS", "QA", "SA", "SO", "SD",
                "SY", "TN", "AE", "YE");
        ARAB_CODES = Collections.unmodifiableSet(arab);

        HashSet<String> maghreb = new HashSet<>();
        Collections.addAll(maghreb, "DZ", "LY", "MR", "MA", "TN", "EH");
        MAGHREB_CODES = Collections.unmodifiableSet(maghreb);

        HashSet<String> allowed = new HashSet<>(arab);
        allowed.add("FR");
        allowed.add("EH");
        ALLOWED_VAVOO_CODES = Collections.unmodifiableSet(allowed);

        HashMap<String, String> countryCodes = new HashMap<>();
        // Resolve every ISO country so an explicitly disallowed country cannot slip
        // through just because its channel title happens to contain "France" or "Maroc".
        for (String code : Locale.getISOCountries()) {
            countryCodes.put(normalize(code), code);
            Locale locale = new Locale("", code);
            putAlias(countryCodes, locale.getDisplayCountry(Locale.FRENCH), code);
            putAlias(countryCodes, locale.getDisplayCountry(Locale.ENGLISH), code);
        }
        alias(countryCodes, "France", "FR");
        alias(countryCodes, "French", "FR");
        alias(countryCodes, "Maroc", "MA");
        alias(countryCodes, "Morocco", "MA");
        alias(countryCodes, "Algerie", "DZ");
        alias(countryCodes, "Algeria", "DZ");
        alias(countryCodes, "Tunisie", "TN");
        alias(countryCodes, "Tunisia", "TN");
        alias(countryCodes, "Libye", "LY");
        alias(countryCodes, "Libya", "LY");
        alias(countryCodes, "Mauritanie", "MR");
        alias(countryCodes, "Mauritania", "MR");
        alias(countryCodes, "Sahara occidental", "EH");
        alias(countryCodes, "Western Sahara", "EH");
        alias(countryCodes, "Sahrawi Arab Democratic Republic", "EH");
        alias(countryCodes, "EH", "EH");
        alias(countryCodes, "KSA", "SA");
        alias(countryCodes, "Saudi", "SA");
        alias(countryCodes, "Kingdom of Saudi Arabia", "SA");
        alias(countryCodes, "UAE", "AE");
        alias(countryCodes, "Emirates", "AE");
        alias(countryCodes, "State of Palestine", "PS");
        COUNTRY_CODES = Collections.unmodifiableMap(countryCodes);

        ArrayList<String> genres = new ArrayList<>();
        Collections.addAll(genres, "Sport", "Information", "Films & séries", "Jeunesse",
                "Documentaires", "Musique", "Divertissement", "Général");
        GENRES = Collections.unmodifiableList(genres);
    }

    private ChannelCatalogPolicy() {}

    private static void putAlias(Map<String, String> map, String name, String code) {
        if (name != null && !name.trim().isEmpty()) map.put(normalize(name), code);
    }

    private static void alias(Map<String, String> map, String name, String code) {
        map.put(normalize(name), code);
    }

    public static String countryCode(String country) {
        if (country == null) return "";
        String key = normalize(country);
        String direct = COUNTRY_CODES.get(key);
        if (direct != null) return direct;
        // Some VAVOO rows append a region marker to the country name.
        for (Map.Entry<String, String> entry : COUNTRY_CODES.entrySet()) {
            String name = entry.getKey();
            if (name.length() < 4) continue;
            if (containsPhrase(key, name)) return entry.getValue();
        }
        return "";
    }

    public static boolean isVavooCountryAllowed(Channel channel) {
        if (channel == null || !channel.isVavoo) return false;
        String code = countryCode(channel.country);
        if (!code.isEmpty()) return ALLOWED_VAVOO_CODES.contains(code);

        String metadata = normalize(safe(channel.country) + " " + safe(channel.group)
                + " " + safe(channel.name));
        if (metadata.contains("maghreb") || metadata.contains("arabia")
                || metadata.contains("arabic") || containsPhrase(metadata, "arab")
                || containsPhrase(metadata, "france") || containsPhrase(metadata, "francais")
                || containsPhrase(metadata, "francaise")) return true;
        for (Map.Entry<String, String> entry : COUNTRY_CODES.entrySet()) {
            if (entry.getKey().length() >= 4 && ALLOWED_VAVOO_CODES.contains(entry.getValue())
                    && containsPhrase(metadata, entry.getKey())) return true;
        }
        return false;
    }

    public static boolean isIptvOrgMorocco(Channel channel) {
        if (channel == null || channel.isVavoo) return false;
        String code = countryCode(channel.country);
        if (!code.isEmpty()) return "MA".equals(code);
        String metadata = normalize(safe(channel.country) + " " + safe(channel.group)
                + " " + safe(channel.name));
        return containsPhrase(metadata, "maroc") || containsPhrase(metadata, "morocco");
    }

    public static ArrayList<Channel> filterVavoo(List<Channel> channels) {
        return filter(channels, true);
    }

    public static ArrayList<Channel> filterIptvOrg(List<Channel> channels) {
        return filter(channels, false);
    }

    private static ArrayList<Channel> filter(List<Channel> channels, boolean vavoo) {
        ArrayList<Channel> result = new ArrayList<>();
        if (channels == null) return result;
        for (Channel channel : channels) {
            if (channel == null) continue;
            if (vavoo ? isVavooCountryAllowed(channel) : isIptvOrgMorocco(channel)) {
                result.add(channel);
            }
        }
        return result;
    }

    public static List<String> genres() {
        return GENRES;
    }

    public static String genreName(Channel channel) {
        if (channel == null) return "Général";
        // Channel.searchIndex() is pre-normalized when the model is created, so
        // counting genres in the guide does not normalize thousands of rows on the UI thread.
        String text = channel.searchIndex();
        if (containsAny(text, "sport", "sports", "bein", "espn", "eurosport")) return "Sport";
        if (containsAny(text, "news", "information", "journal", "actualite", "info"))
            return "Information";
        if (containsAny(text, "kids", "kid", "enfant", "jeunesse", "cartoon", "animation"))
            return "Jeunesse";
        if (containsAny(text, "documentaire", "documentary", "documentaries")) return "Documentaires";
        if (containsAny(text, "film", "films", "movie", "movies", "cinema", "serie", "series", "drama"))
            return "Films & séries";
        if (containsAny(text, "music", "musique", "musical")) return "Musique";
        if (containsAny(text, "entertainment", "divertissement", "variete", "variety", "show"))
            return "Divertissement";
        return "Général";
    }

    public static boolean matchesGenre(Channel channel, String requestedGenre) {
        String requested = safe(requestedGenre);
        return requested.isEmpty() || "Tous les genres".equalsIgnoreCase(requested)
                || genreName(channel).equalsIgnoreCase(requested);
    }

    public static boolean isMaghrebCode(String code) {
        return code != null && MAGHREB_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isArabCode(String code) {
        return code != null && ARAB_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (containsPhrase(text, word)) return true;
        return false;
    }

    private static boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null || phrase.isEmpty()) return false;
        int from = 0;
        while (true) {
            int at = text.indexOf(phrase, from);
            if (at < 0) return false;
            int end = at + phrase.length();
            boolean left = at == 0 || text.charAt(at - 1) == ' ';
            boolean right = end == text.length() || text.charAt(end) == ' ';
            if (left && right) return true;
            from = at + 1;
        }
    }

    private static String normalize(String value) {
        return Channel.normalizeSearch(value).trim().replaceAll("\\s+", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
