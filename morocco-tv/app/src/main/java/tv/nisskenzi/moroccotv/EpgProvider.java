package tv.nisskenzi.moroccotv;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * On-demand XMLTV guide lookup for both VAVOO and IPTV-org channels.
 *
 * IPTV-org channels use their tvg-id first. VAVOO metadata does not expose a
 * reliable XMLTV id, so the provider falls back to normalized channel names.
 * XMLTV is streamed from compressed country guides and never parsed on the UI
 * thread. The live streams and the two catalogue providers are not modified.
 */
public final class EpgProvider {
    private static final String GUIDE_BASE = "https://iptv-epg.org/files/epg-";
    private static final long CACHE_TTL_MS = 12L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long MAX_DOWNLOAD_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_PROGRAMMES_PER_CHANNEL = 8;
    private static final long LOOKAHEAD_MS = 72L * 60L * 60L * 1000L;

    private static final ConcurrentHashMap<String, GuideIndex> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> COUNTRY_CODES = buildCountryCodes();

    private EpgProvider() {}

    public static final class Result {
        public final String now;
        public final String next;
        public final String status;
        public final String guideCountry;

        Result(String now, String next, String status, String guideCountry) {
            this.now = safe(now);
            this.next = safe(next);
            this.status = safe(status);
            this.guideCountry = safe(guideCountry);
        }

        public static Result loading() {
            return new Result("", "", "Chargement du guide…", "");
        }
    }

    private static final class Programme {
        final long startMs;
        final long stopMs;
        final String title;

        Programme(long startMs, long stopMs, String title) {
            this.startMs = startMs;
            this.stopMs = stopMs;
            this.title = safe(title);
        }
    }

    private static final class GuideIndex {
        final String countryCode;
        final Map<String, List<Programme>> programmesById;
        final Map<String, List<Programme>> programmesByName;

        GuideIndex(String countryCode, Map<String, List<Programme>> byId,
                   Map<String, List<Programme>> byName) {
            this.countryCode = countryCode;
            this.programmesById = byId;
            this.programmesByName = byName;
        }
    }

    public static Result lookup(Context context, Channel channel) {
        if (channel == null) return new Result("", "", "Aucune chaîne sélectionnée", "");
        long now = System.currentTimeMillis();
        ArrayList<String> candidates = guideCandidates(channel.country);
        if (candidates.isEmpty()) candidates.add("fr");

        String lastFailure = "";
        for (String code : candidates) {
            try {
                GuideIndex guide = getGuide(context.getApplicationContext(), code, now);
                List<Programme> schedule = findSchedule(guide, channel);
                if (schedule == null || schedule.isEmpty()) {
                    lastFailure = "Aucun programme correspondant dans le guide " + code.toUpperCase(Locale.ROOT);
                    continue;
                }

                Programme current = null;
                Programme next = null;
                for (Programme p : schedule) {
                    if (p.startMs <= now && p.stopMs > now) {
                        if (current == null || p.stopMs < current.stopMs) current = p;
                    } else if (p.startMs > now && (next == null || p.startMs < next.startMs)) {
                        next = p;
                    }
                }

                String nowText = current == null ? "" : formatTime(current.startMs)
                        + "  " + current.title;
                String nextText = next == null ? "" : formatTime(next.startMs)
                        + "  " + next.title;
                String label = code.toUpperCase(Locale.ROOT);
                if (current == null && next == null) {
                    lastFailure = "Aucun programme à venir dans le guide " + label;
                    continue;
                }
                return new Result(nowText, nextText,
                        "Guide XMLTV · " + label, label);
            } catch (Exception e) {
                lastFailure = e.getMessage() == null ? "Guide indisponible" : e.getMessage();
            }
        }
        if (lastFailure.isEmpty()) lastFailure = "Aucun guide public correspondant à cette chaîne";
        return new Result("", "", lastFailure, "");
    }

    private static GuideIndex getGuide(Context context, String code, long now) throws Exception {
        String key = code.toLowerCase(Locale.ROOT);
        GuideIndex inMemory = MEMORY_CACHE.get(key);
        if (inMemory != null) return inMemory;

        File directory = new File(context.getFilesDir(), "epg");
        if (!directory.exists() && !directory.mkdirs()) throw new Exception("Impossible de créer le cache EPG");
        File gzipFile = new File(directory, "epg-" + key + ".xml.gz");
        File xmlFile = new File(directory, "epg-" + key + ".xml");
        File local = isFresh(gzipFile, now) ? gzipFile : (isFresh(xmlFile, now) ? xmlFile : null);

        if (local == null) {
            Exception downloadError = null;
            try {
                download(GUIDE_BASE + key + ".xml.gz", gzipFile);
                local = gzipFile;
            } catch (Exception e) {
                downloadError = e;
                try {
                    download(GUIDE_BASE + key + ".xml", xmlFile);
                    local = xmlFile;
                } catch (Exception fallback) {
                    if (downloadError != null) fallback.addSuppressed(downloadError);
                    throw fallback;
                }
            }
        }

        GuideIndex parsed = parseGuide(local, key, now);
        if (parsed.programmesById.isEmpty() && parsed.programmesByName.isEmpty()) {
            throw new Exception("Le guide " + key.toUpperCase(Locale.ROOT) + " ne contient aucun programme exploitable");
        }
        GuideIndex existing = MEMORY_CACHE.putIfAbsent(key, parsed);
        return existing == null ? parsed : existing;
    }

    private static boolean isFresh(File file, long now) {
        return file.isFile() && file.length() > 0
                && now - file.lastModified() >= 0
                && now - file.lastModified() < CACHE_TTL_MS;
    }

    private static void download(String address, File target) throws Exception {
        HttpURLConnection connection = null;
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NISSKENZI+/2.3 (Android TV EPG)");
            connection.setRequestProperty("Accept", "application/xml, application/gzip, */*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new Exception("EPG HTTP " + status);

            long reportedLength = connection.getContentLengthLong();
            if (reportedLength > MAX_DOWNLOAD_BYTES) throw new Exception("Fichier EPG trop volumineux");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_DOWNLOAD_BYTES) throw new Exception("Le guide EPG dépasse la limite de taille");
                    output.write(buffer, 0, count);
                }
            }
            if (!temporary.isFile() || temporary.length() == 0) throw new Exception("Le guide EPG téléchargé est vide");
            if (target.exists() && !target.delete()) throw new Exception("Impossible de remplacer le cache EPG");
            if (!temporary.renameTo(target)) throw new Exception("Impossible d'enregistrer le cache EPG");
        } finally {
            temporary.delete();
            if (connection != null) connection.disconnect();
        }
    }

    private static GuideIndex parseGuide(File file, String code, long now) throws Exception {
        HashMap<String, List<Programme>> byId = new HashMap<>();
        HashMap<String, List<String>> channelNames = new HashMap<>();
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file));
             InputStream input = maybeGunzip(raw)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(input, "UTF-8");

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String element = parser.getName();
                    if ("channel".equals(element)) {
                        String id = safe(parser.getAttributeValue(null, "id"));
                        List<String> names = readChannelNames(parser);
                        if (!id.isEmpty() && !names.isEmpty()) channelNames.put(id, names);
                        event = parser.next();
                        continue;
                    } else if ("programme".equals(element)) {
                        String id = safe(parser.getAttributeValue(null, "channel"));
                        long start = parseXmltvDate(parser.getAttributeValue(null, "start"));
                        long stop = parseXmltvDate(parser.getAttributeValue(null, "stop"));
                        String title = readProgrammeTitle(parser);
                        if (!id.isEmpty() && !title.isEmpty() && start > 0
                                && stop > start && stop > now && start < now + LOOKAHEAD_MS) {
                            List<Programme> list = byId.computeIfAbsent(id, ignored -> new ArrayList<>());
                            if (list.size() < MAX_PROGRAMMES_PER_CHANNEL) {
                                list.add(new Programme(start, stop, title));
                            } else {
                                insertProgramme(list, new Programme(start, stop, title));
                            }
                        }
                        event = parser.next();
                        continue;
                    }
                }
                event = parser.next();
            }
        }

        Comparator<Programme> chronological = Comparator.comparingLong(p -> p.startMs);
        for (List<Programme> list : byId.values()) Collections.sort(list, chronological);

        HashMap<String, List<Programme>> byName = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : channelNames.entrySet()) {
            List<Programme> schedule = byId.get(entry.getKey());
            if (schedule == null || schedule.isEmpty()) continue;
            for (String displayName : entry.getValue()) {
                for (String match : nameVariants(displayName)) {
                    if (!match.isEmpty() && !byName.containsKey(match)) byName.put(match, schedule);
                }
            }
        }
        return new GuideIndex(code, byId, byName);
    }

    private static void insertProgramme(List<Programme> list, Programme item) {
        int farthest = 0;
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).startMs > list.get(farthest).startMs) farthest = i;
        }
        if (item.startMs < list.get(farthest).startMs) list.set(farthest, item);
    }

    private static List<String> readChannelNames(XmlPullParser parser) throws Exception {
        ArrayList<String> names = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.END_TAG && "channel".equals(parser.getName())) break;
            if (parser.getEventType() == XmlPullParser.START_TAG && "display-name".equals(parser.getName())) {
                String value = safe(parser.nextText());
                if (!value.isEmpty()) names.add(value);
            }
        }
        return names;
    }

    private static String readProgrammeTitle(XmlPullParser parser) throws Exception {
        String title = "";
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.END_TAG && "programme".equals(parser.getName())) break;
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && ("title".equals(parser.getName()) || ("".equals(title) && "sub-title".equals(parser.getName())))) {
                String value = safe(parser.nextText());
                if (!value.isEmpty() && title.isEmpty()) title = value;
            }
        }
        return title;
    }

    private static InputStream maybeGunzip(InputStream raw) throws Exception {
        BufferedInputStream buffered = raw instanceof BufferedInputStream
                ? (BufferedInputStream) raw : new BufferedInputStream(raw);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        if (first == 0x1f && second == 0x8b) return new GZIPInputStream(buffered, 32 * 1024);
        return buffered;
    }

    private static List<Programme> findSchedule(GuideIndex guide, Channel channel) {
        if (!safe(channel.epgId).isEmpty()) {
            List<Programme> exact = guide.programmesById.get(channel.epgId);
            if (exact == null) exact = guide.programmesById.get(channel.epgId.trim());
            if (exact != null && !exact.isEmpty()) return exact;
        }
        for (String key : nameVariants(channel.name)) {
            List<Programme> matched = guide.programmesByName.get(key);
            if (matched != null && !matched.isEmpty()) return matched;
        }
        return null;
    }

    private static ArrayList<String> nameVariants(String value) {
        ArrayList<String> out = new ArrayList<>();
        String normalized = normalize(value);
        if (normalized.isEmpty()) return out;
        out.add(normalized);
        String withoutQuality = normalized.replaceAll("\\b(hd|sd|fhd|uhd|4k|8k|hevc|1080p|720p|50fps|60fps)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        if (!withoutQuality.isEmpty() && !out.contains(withoutQuality)) out.add(withoutQuality);
        String withoutPrefix = withoutQuality.replaceFirst("^(fr|ma|us|uk|gb|es|it|de|pt|be|ch) ", "").trim();
        if (!withoutPrefix.isEmpty() && !out.contains(withoutPrefix)) out.add(withoutPrefix);
        String withoutSuffix = withoutPrefix.replaceAll("\\b(france|premium|international|live)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        if (!withoutSuffix.isEmpty() && !out.contains(withoutSuffix)) out.add(withoutSuffix);
        return out;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static long parseXmltvDate(String value) {
        String raw = safe(value);
        if (raw.length() < 14) return 0L;
        String head = raw.substring(0, 14);
        String zone = raw.length() > 14 ? raw.substring(14).trim() : "";
        try {
            SimpleDateFormat format;
            if (zone.matches("[+-]\\d{4}")) {
                format = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
                return format.parse(head + " " + zone).getTime();
            }
            format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(head).getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }

    private static String formatTime(long epochMs) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(epochMs));
    }

    private static ArrayList<String> guideCandidates(String country) {
        ArrayList<String> result = new ArrayList<>();
        String code = COUNTRY_CODES.get(countryKey(country));
        if (code != null) result.add(code.toLowerCase(Locale.ROOT));
        // France has broad coverage for many international channels; use it only
        // as a fallback when the channel's own country guide has no matching entry.
        if (!result.contains("fr")) result.add("fr");
        return result;
    }

    private static Map<String, String> buildCountryCodes() {
        HashMap<String, String> map = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            Locale local = new Locale("", code);
            map.put(countryKey(code), code);
            map.put(countryKey(local.getDisplayCountry(Locale.FRENCH)), code);
            map.put(countryKey(local.getDisplayCountry(Locale.ENGLISH)), code);
        }
        map.put(countryKey("UK"), "GB");
        map.put(countryKey("United Kingdom"), "GB");
        map.put(countryKey("USA"), "US");
        map.put(countryKey("United States"), "US");
        map.put(countryKey("États-Unis"), "US");
        map.put(countryKey("Royaume-Uni"), "GB");
        map.put(countryKey("Corée du Sud"), "KR");
        map.put(countryKey("Côte d’Ivoire"), "CI");
        map.put(countryKey("Maroc"), "MA");
        map.put(countryKey("Morocco"), "MA");
        return map;
    }

    private static String countryKey(String value) {
        return normalize(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
