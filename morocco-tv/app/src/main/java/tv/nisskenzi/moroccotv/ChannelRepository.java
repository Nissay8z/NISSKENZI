package tv.nisskenzi.moroccotv;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * Single owner of the multi-source M3U catalogue.
 *
 * Room is the UI source of truth. Network work only replaces one source's rows
 * after that playlist parsed successfully; a broken/offline feed never erases
 * its last valid local copy.
 */
public final class ChannelRepository {
    public static final long REFRESH_INTERVAL_HOURS = 6L
    private static final int MAX_CHANNELS_PER_SOURCE = 20_000;
    private static final String PREFS = "m3u_sources";
    private static final String DB_PREFS = "catalog_database_state";
    private static final String PERIODIC_WORK_NAME = "nisskenzi_m3u_refresh";

    public static final class Source {
        public final String id;
        public final String name;
        public final String defaultUrl;

        Source(String id, String name, String defaultUrl) {
            this.id = id;
            this.name = name;
            this.defaultUrl = defaultUrl;
        }
    }

    private static final String IPTV_ORG_MASTER_URL =
            "https://raw.githubusercontent.com/Nissay8z/KENZINISS/main/playlist/master.m3u";

    private static final List<Source> SOURCES;
    static {
        ArrayList<Source> sources = new ArrayList<>();
        // VAVOO is loaded from its native API and resolved only when a channel is played.
        sources.add(new Source("vavoo", "VAVOO", ""));
        // Global IPTV-org catalogue; its country metadata feeds the left-hand filters.
        sources.add(new Source("iptv-org-fr", "IPTV-org", IPTV_ORG_MASTER_URL));
        SOURCES = Collections.unmodifiableList(sources);
    }

    private static volatile ChannelRepository INSTANCE;

    private final Context appContext;
    private final AppDatabase database;
    private final ChannelDao dao;
    private final SharedPreferences statePreferences;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService mappingExecutor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<Channel>> channels = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> status = new MutableLiveData<>(
            "Catalogue local prêt · actualisation réseau en arrière-plan");
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private ChannelRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        database = AppDatabase.getInstance(appContext);
        dao = database.channelDao();
        statePreferences = appContext.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE);

        dao.observeAllChannels().observeForever(rows -> mappingExecutor.execute(() -> {
            ArrayList<Channel> mapped = new ArrayList<>(rows == null ? 0 : rows.size());
            if (rows != null) {
                for (ChannelEntity row : rows) {
                    if (row != null) mapped.add(row.toChannel());
                }
            }
            channels.postValue(Collections.unmodifiableList(mapped));
        }));
    }

    public static ChannelRepository getInstance(@NonNull Context context) {
        ChannelRepository repo = INSTANCE;
        if (repo == null) {
            synchronized (ChannelRepository.class) {
                repo = INSTANCE;
                if (repo == null) {
                    repo = new ChannelRepository(context);
                    INSTANCE = repo;
                }
            }
        }
        return repo;
    }

    public LiveData<List<Channel>> observeChannels() {
        return channels;
    }

    public LiveData<String> observeStatus() {
        return status;
    }

    public List<Source> getSources() {
        return SOURCES;
    }

    public static String displayName(Context context, String sourceId) {
        String id = canonicalSourceId(sourceId);
        if ("all".equals(id)) return "Toutes les sources";
        for (Source source : SOURCES) if (source.id.equals(id)) return source.name;
        return id.isEmpty() ? "Toutes les sources" : id;
    }

    public static String canonicalSourceId(String sourceId) {
        if (sourceId == null) return "all";
        String id = sourceId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty() || "all".equals(id) || "toutes".equals(id)
                || "toutes les sources".equals(id)) return "all";
        if ("vavoo".equals(id)) return "vavoo";
        if ("iptv-org".equals(id) || "iptv org".equals(id)
                || "iptv-org-fr".equals(id) || "iptv-org france".equals(id)) return "iptv-org-fr";
        return "all";
    }

    /** Called by the ViewModel as soon as the home screen is created. */
    public void initialize() {
        schedulePeriodicRefresh();
        if (!initialized.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            cleanupDeprecatedSources();
            refreshStaleSourcesAsync();
        });
    }

    /** Manual refresh runs entirely off the main/UI thread. */
    public void refreshAllAsync(boolean force) {
        status.postValue(force
                ? "Actualisation des playlists M3U en arrière-plan…"
                : "Vérification des playlists M3U…");
        for (Source source : SOURCES) {
            ioExecutor.execute(() -> refreshOne(source, force));
        }
    }

    /** WorkManager entry point. Called from its background worker thread. */
    public boolean refreshStaleSourcesBlocking() {
        boolean successfulOrFresh = true;
        for (Source source : SOURCES) {
            try {
                if (shouldRefresh(source, false)) {
                    boolean updated = refreshOneSynchronously(source, false);
                    successfulOrFresh = successfulOrFresh && (updated || dao.countForSource(source.id) > 0);
                }
            } catch (Exception error) {
                successfulOrFresh = false;
            }
        }
        return successfulOrFresh;
    }

    /** Clear only the Room catalogue rows; the next initialize/refresh seeds them again. */
    public void clearDatabaseAsync() {
        ioExecutor.execute(() -> {
            database.runInTransaction(dao::deleteAll);
            statePreferences.edit().clear().apply();
            cleanupDeprecatedSources();
            refreshStaleSourcesAsync();
        });
    }

    public void schedulePeriodicRefresh() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PlaylistRefreshWorker.class, REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,
                        30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request);
    }

    /** Purge legacy source rows and URL overrides saved by older versions. */
    private void cleanupDeprecatedSources() {
        database.runInTransaction(dao::deleteDeprecatedSources);
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply();
        statePreferences.edit().clear().apply();
        M3UParser.clearMemoryCache();
    }

    private void refreshStaleSourcesAsync() {
        for (Source source : SOURCES) {
            if (shouldRefreshSafely(source, false)) {
                ioExecutor.execute(() -> refreshOne(source, false));
            }
        }
    }

    private boolean shouldRefreshSafely(Source source, boolean force) {
        try {
            return shouldRefresh(source, force);
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean shouldRefresh(Source source, boolean force) {
        if (force) return true;
        int count = dao.countForSource(source.id);
        long last = statePreferences.getLong(lastRefreshKey(source), 0L);
        long age = System.currentTimeMillis() - last;
        return count == 0 || last == 0L
                || age >= TimeUnit.HOURS.toMillis(REFRESH_INTERVAL_HOURS);
    }

    private void refreshOne(Source source, boolean force) {
        try {
            if (!shouldRefresh(source, force)) return;
            refreshOneSynchronously(source, force);
        } catch (Exception error) {
            status.postValue(source.name + " · mise à jour impossible, cache local conservé");
        }
    }

    private boolean refreshOneSynchronously(Source source, boolean force) {
        try {
            ArrayList<Channel> parsed;
            if ("vavoo".equals(source.id)) {
                parsed = loadVavooNativeChannels();
            } else {
                parsed = M3UParser.load(appContext, source.id, source.name,
                        "", source.defaultUrl, MAX_CHANNELS_PER_SOURCE, this::resolveCountry, force);
            }
            if (parsed.isEmpty()) {
                status.postValue(source.name + " · playlist vide, cache local conservé");
                return false;
            }

            ArrayList<ChannelEntity> rows = new ArrayList<>(parsed.size());
            for (Channel channel : parsed) {
                if (channel != null && !channel.url.isEmpty()) {
                    rows.add(ChannelEntity.fromChannel(source.id, source.name, channel));
                }
            }
            if (rows.isEmpty()) return false;

            database.runInTransaction(() -> {
                dao.deleteSource(source.id);
                dao.insertAll(rows);
            });
            statePreferences.edit().putLong(lastRefreshKey(source), System.currentTimeMillis()).apply();
            status.postValue(source.name + " · " + rows.size() + " chaînes en cache local");
            return true;
        } catch (Exception error) {
            status.postValue(source.name + " · réseau indisponible, anciennes chaînes conservées");
            return false;
        }
    }

    private ArrayList<Channel> loadVavooNativeChannels() throws Exception {
        List<VavooProvider.VavooChannel> catalog = VavooProvider.loadChannels();
        ArrayList<Channel> parsed = new ArrayList<>(Math.min(
                catalog == null ? 0 : catalog.size(), MAX_CHANNELS_PER_SOURCE));
        if (catalog == null) return parsed;

        for (VavooProvider.VavooChannel item : catalog) {
            if (item == null || parsed.size() >= MAX_CHANNELS_PER_SOURCE) continue;
            String sourceUrl = item.sourceUrl == null ? "" : item.sourceUrl.trim();
            if (sourceUrl.isEmpty()) continue;

            String name = item.name == null || item.name.trim().isEmpty()
                    ? "Chaîne VAVOO" : item.name.trim();
            String id = item.id == null ? "" : item.id.trim();
            if (id.isEmpty()) id = Integer.toHexString((name + "|" + sourceUrl).hashCode());

            // Stable local row identity; this catalog reference is not a resolved stream.
            String rowUrl = "vavoo://" + id + "-" + Integer.toHexString(sourceUrl.hashCode());
            String group = item.group == null || item.group.trim().isEmpty()
                    ? "VAVOO Live" : item.group.trim();
            if (!group.startsWith("VAVOO · ")) group = "VAVOO · " + group;

            String country = resolveCountry(item.country);
            if (country.isEmpty()) country = "Pays non précisé";
            parsed.add(new Channel(name, rowUrl, item.logo, group, true, sourceUrl,
                    country, "", "vavoo", null));
        }
        return parsed;
    }

    private static String lastRefreshKey(Source source) {
        return "last_refresh_" + source.id;
    }

    private String resolveCountry(String rawCountry) {
        if (rawCountry == null) return "";
        String raw = rawCountry.trim().split("[,;]")[0].trim();
        if (raw.isEmpty()) return "";
        if ("UK".equalsIgnoreCase(raw)) return "Royaume-Uni";
        if ("USA".equalsIgnoreCase(raw) || "US".equalsIgnoreCase(raw)) return "États-Unis";

        if (raw.length() == 2 && Character.isLetter(raw.charAt(0))
                && Character.isLetter(raw.charAt(1))) {
            String code = raw.toUpperCase(java.util.Locale.ROOT);
            for (String isoCode : java.util.Locale.getISOCountries()) {
                if (!isoCode.equals(code)) continue;
                java.util.Locale locale = new java.util.Locale("", code);
                String french = locale.getDisplayCountry(java.util.Locale.FRENCH);
                if (french != null && !french.trim().isEmpty() && !french.equals(code)) return french;
                break;
            }
            // Invalid two-letter values fall through to the playlist group-title.
            return "";
        }
        return raw;
    }

    /** Worker tests need not own UI observers; process lifetime owns this singleton. */
    public void shutdownForTests() {
        ioExecutor.shutdownNow();
        mappingExecutor.shutdownNow();
    }
}
