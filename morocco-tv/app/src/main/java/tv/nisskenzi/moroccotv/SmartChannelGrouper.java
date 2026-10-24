package tv.nisskenzi.moroccotv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Moteur intelligent de regroupement des chaînes et de sélection de la meilleure source.
 *
 * Détecte les flux en double et variantes de qualité (4K, UHD, FHD, HD, SD, .b, .c, .s, etc.)
 * et les rassemble dans un dossier de chaîne unique.
 * Calcule automatiquement un score de qualité pour sélectionner la meilleure source
 * tout en conservant toutes les sources alternatives pour le basculement automatique
 * et la sélection manuelle.
 */
public final class SmartChannelGrouper {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern PUNCTUATION_AND_SPACE = Pattern.compile("[\\p{Punct}\\s]+");

    // Suffixes de serveurs / mirrors spécifiques comme .b, .c, .s, .d
    private static final Pattern SERVER_SUFFIX = Pattern.compile("\\s*\\.[a-zA-Z]\\b");

    // Métadonnées entre crochets ou parenthèses : (1080p), [NOT 24/7], (backup), etc.
    private static final Pattern BRACKETED_INFO = Pattern.compile("\\s*[\\[\\(][^\\]\\)]*[\\]\\)]");

    // Mots-clés de résolutions et codecs vidéo à nettoyer du titre principal
    private static final Pattern QUALITY_TOKENS = Pattern.compile(
            "\\b(4K\\s*ULTRA|4K|UHD|2160P|FULL\\s*HD|FHD|1080P|1080I|HD\\+|HD|720P|576P|540P|480P|360P|240P|SD|HEVC|H\\.?265|RAW|50FPS|60FPS)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLEAN_SPACES = Pattern.compile("[\\-_:\\.]+|\\s+");

    private SmartChannelGrouper() {}

    /**
     * Nettoie le nom d'une chaîne pour produire un titre propre et canonique.
     * Exemple : "CANAL+ CINEMAS 4K .b" -> "CANAL+ CINEMAS", "TF1 FHD .b" -> "TF1".
     * Conserve les identifiants numériques réels comme "CANAL+ 360", "M6", "W9", "24 KITCHEN".
     */
    public static String cleanChannelName(String rawName) {
        if (rawName == null) return "Chaîne";
        String trimmed = rawName.trim();
        if (trimmed.isEmpty()) return "Chaîne";

        String cleaned = SERVER_SUFFIX.matcher(trimmed).replaceAll("");
        cleaned = BRACKETED_INFO.matcher(cleaned).replaceAll("");
        cleaned = QUALITY_TOKENS.matcher(cleaned).replaceAll("");
        cleaned = CLEAN_SPACES.matcher(cleaned).replaceAll(" ").trim();

        return cleaned.isEmpty() ? trimmed : cleaned;
    }

    /**
     * Détecte le badge de qualité d'un flux : "4K", "FHD", "HD", "SD" ou "".
     */
    public static String detectQualityLabel(String name, String url) {
        String haystack = (name == null ? "" : name) + " " + (url == null ? "" : url);
        String upper = haystack.toUpperCase(Locale.ROOT);

        if (upper.contains("4K") || upper.contains("2160P") || upper.contains("UHD")) {
            return "4K";
        }
        if (upper.contains("FHD") || upper.contains("1080P") || upper.contains("1080I")
                || upper.contains("FULL HD") || upper.contains("FULLHD")) {
            return "FHD";
        }
        if (upper.contains("HD") || upper.contains("720P")) {
            return "HD";
        }
        if (upper.contains("576P") || upper.contains("540P") || upper.contains("480P")
                || upper.contains("360P") || upper.contains("240P") || upper.contains("SD")) {
            return "SD";
        }
        return "";
    }

    /**
     * Calcule le score de qualité d'un flux pour choisir la meilleure source.
     */
    public static int calculateQualityScore(String name, String url, boolean hasLogo, boolean isVavoo) {
        int score = 150; // Score de base standard
        String quality = detectQualityLabel(name, url);
        switch (quality) {
            case "4K": score = 400; break;
            case "FHD": score = 300; break;
            case "HD": score = 200; break;
            case "SD": score = 80; break;
            default: break;
        }

        String raw = (name == null ? "" : name).toLowerCase(Locale.ROOT);

        // Sur VAVOO, les suffixes désignent des serveurs miroirs différents
        if (isVavoo) {
            if (raw.endsWith(".b")) score += 6;
            else if (raw.endsWith(".c")) score += 4;
            else if (raw.endsWith(".s")) score += 2;
        }

        if (hasLogo) score += 10;

        // Pénalités pour les flux non continus ou de secours
        if (raw.contains("not 24/7") || raw.contains("not 24h") || raw.contains("event")) {
            score -= 100;
        }
        if (raw.contains("backup")) {
            score -= 40;
        }
        if (raw.contains("geo-blocked") || raw.contains("geoblocked")) {
            score -= 50;
        }

        return score;
    }

    /**
     * Regroupe une liste brute de chaînes en dossiers de chaînes dédupliqués
     * avec la meilleure source positionnée en tête.
     */
    public static ArrayList<Channel> groupChannels(List<Channel> rawChannels) {
        if (rawChannels == null || rawChannels.isEmpty()) return new ArrayList<>();

        Map<String, List<Channel>> clusterMap = new LinkedHashMap<>();

        for (Channel ch : rawChannels) {
            if (ch == null) continue;
            String cleanName = cleanChannelName(ch.name);
            String country = ch.country == null ? "" : ch.country.trim().toLowerCase(Locale.ROOT);
            String clusterKey = country + ":::" + normalizeKey(cleanName);

            List<Channel> group = clusterMap.get(clusterKey);
            if (group == null) {
                group = new ArrayList<>();
                clusterMap.put(clusterKey, group);
            }
            group.add(ch);
        }

        ArrayList<Channel> result = new ArrayList<>(clusterMap.size());
        for (List<Channel> group : clusterMap.values()) {
            if (group.isEmpty()) continue;

            if (group.size() == 1) {
                Channel single = group.get(0);
                result.add(new Channel(
                        single.name, single.url, single.logo, single.group,
                        single.isVavoo, single.vavooSourceUrl, single.country,
                        single.epgId, single.searchIndex(), Collections.singletonList(single)));
                continue;
            }

            // Trier les sources par score de qualité décroissant (la meilleure en 1er)
            Collections.sort(group, (a, b) -> Integer.compare(b.qualityScore, a.qualityScore));

            Channel best = group.get(0);
            String cleanTitle = cleanChannelName(best.name);

            // Si la meilleure source n'a pas de logo, chercher un logo valide dans le dossier
            String bestLogo = best.logo;
            if (bestLogo.isEmpty()) {
                for (Channel variant : group) {
                    if (variant != null && !variant.logo.isEmpty()) {
                        bestLogo = variant.logo;
                        break;
                    }
                }
            }

            // Idem pour le guide EPG (tvg-id)
            String bestEpgId = best.epgId;
            if (bestEpgId.isEmpty()) {
                for (Channel variant : group) {
                    if (variant != null && !variant.epgId.isEmpty()) {
                        bestEpgId = variant.epgId;
                        break;
                    }
                }
            }

            Channel groupedChannel = new Channel(
                    cleanTitle,
                    best.url,
                    bestLogo,
                    best.group,
                    best.isVavoo,
                    best.vavooSourceUrl,
                    best.country,
                    bestEpgId,
                    null, // Laisser calculer l'index de recherche multi-source
                    group
            );
            result.add(groupedChannel);
        }

        return result;
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = MARKS.matcher(
                Normalizer.normalize(value.trim(), Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
        return PUNCTUATION_AND_SPACE.matcher(normalized).replaceAll(" ").trim();
    }
}
