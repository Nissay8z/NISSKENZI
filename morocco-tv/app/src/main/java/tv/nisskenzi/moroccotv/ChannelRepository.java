package tv.nisskenzi.moroccotv;

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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Room-backed catalogue repository.
 *
 * Public channels come from one GitHub master.m3u, tagged per entry with
 * x-source-id / x-source-name. VAVOO is deliberately independent: only this
 * repository calls VavooProvider.loadChannels(), and the player resolves a
 * VAVOO stream after the user selects it.
 */
public final class ChannelRepository {
    public static final long REFRESH_INTERVAL_HOURS = 6L;
    public static final String DEFAULT_MASTER_PLAYLIST_URL =
            "https://raw.githubusercontent.com/Nissay8z/KENZINISS/main/playlist/master.m3u";

    private static final int MAX_MASTER_CHANNELS = 30_000;
    private static final String URL_PREFS = "m3u_sources";
    private static final String STATE_PREFS = "catalog_database_state";
    private static final String MASTER_URL_KEY = "master_url";
    private static final String VAVOO_REFRESH_KEY = "last_refresh_vavoo";
    private static final String MASTER_REFRESH_KEY = "last_refresh_public_master";
    private static final String PERIODIC_WORK_NAME = "nisskenzi_m3u_refresh";

    /** UI source/category row; only VAVOO and the single public master are network jobs. */
    public static final class Source {
        public final String id;
        public final String name;
        public final String defaultUrl;
        public final String assetName;
        public final String urlPreferenceKey;
        public final boolean bundledAsset;

        Source(String id, String name) {
            this.id = id;
            this.name = name;
            this.defaultUrl = "";
            this.assetName = "";
            this.urlPreferenceKey = "";
            this.bundledAsset = false;
        }
    }

    private static final List<Source> SOURCES;
    static {
        ArrayList<Source> rows = new ArrayList<>();
        rows.add(new Source("vavoo", "VAVOO"));
        rows.add(new Source("iptv-org-fr", "IPTV-org France"));
        rows.add(new Source("iptv-org-arab", "IPTV-org Monde arabe"));
        rows.add(new Source("iptv-org-africa", "IPTV-org Afrique"));
        rows.add(new Source("pluto-tv-fr", "Pluto TV France"));
        rows.add(new Source("samsung-tv-fr", "Samsung TV Plus France"));
        rows.add(new Source("free-tv-world", "Free-TV International"));
        SOURCES = Collections.unmodifiableList(rows);
    }

    private static volatile ChannelRepository INSTANCE;

    private final Context appContext;
    private final AppDatabase database;
    private final ChannelDao dao;
    private final SharedPreferences urlPreferences;
    private final SharedPreferences statePreferences;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService mappingExecutor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<Channel>> channels =
            new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> status = new MutableLiveData<>(
            "Catalogue SQLite prêt · vérification en arrière-plan");
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private ChannelRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        database = AppDatabase.getInstance(appContext);
        dao = database.channelDao();
        urlPreferences = appContext.getSharedPreferences(URL_PREFS, Context.MODE_PRIVATE);
        statePreferences = appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);

        // Room's LiveData query executes away from the UI thread. Convert rows on a
        // dedicated worker and publish the immutable channel snapshot to the ViewModel.
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
        ChannelRepository repository = INSTANCE;
        if (repository == null) {
            synchronized (ChannelRepository.class) {
                repository = INSTANCE;
                if (repository == null) {
                    repository = new ChannelRepository(context);
                    INSTANCE = repository;
                }
            }
        }
        return repository;
    }

    public LiveData<List<Channel>> observeChannels() {
        return channels;
    }

    public LiveData<String> observeStatus() {
        return status;
    }

    /** Source categories are read from the merged master M3U; this list names the UI filters. */
    public List<Source> getSources() {
        return SOURCES;
    }

    public static String displayName(Context context, String sourceId) {
        String id = canonicalSourceId(sourceId);
        if ("all".equals(id)) return "Toutes les sources";
        if ("public".equals(id)) return "M3U publics";
        for (Source source : SOURCES) {
            if (source.id.equals(id)) return source.name;
        }
        return id.isEmpty() ? "Toutes les sources" : id;
    }

    public static String canonicalSourceId(String sourceId) {
        if (sourceId == null) return "all";
        String id = sourceId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || "all".equals(id) || "toutes".equals(id)
                || "toutes les sources".equals(id) || "live tv".equals(id)) return "all";
        if ("public".equals(id) || "m3u".equals(id) || "m3u publics".equals(id)
                || "iptv-org".equals(id) || "iptv org".equals(id)) return "public";
        if ("vavoo".equals(id)) return "vavoo";
        if ("iptv-org-fr".equals(id) || "iptv-org france".equals(id)) return "iptv-org-fr";
        if ("iptv-org-arab".equals(id) || "iptv-org monde arabe".equals(id)) return "iptv-org-arab";
        if ("iptv-org-africa".equals(id) || "iptv-org afrique".equals(id)) return "iptv-org-africa";
        if ("pluto".equals(id) || "pluto tv".equals(id) || "pluto-tv-fr".equals(id)
                || "pluto tv france".equals(id)) return "pluto-tv-fr";
        if ("samsung".equals(id) || "samsung tv".equals(id) || "samsung-tv-plus".equals(id)
                || "samsung-tv-fr".equals(id) || "samsung tv plus france".equals(id)) return "samsung-tv-fr";
        if ("free-tv".equals(id) || "free-tv-world".equals(id)
                || "free-tv international".equals(id)) return "free-tv-world";
        return id;
    }

    /** The existing Room snapshot is observed immediately; network refresh never blocks this call. */
    public void initialize() {
        schedulePeriodicRefresh();
        if (!initialized.compareAndSet(false, true)) return;
        refreshAllAsync(false);
    }

    /** Refreshes VAVOO through its native provider and public channels through exactly one master M3U. */
    public void refreshAllAsync(boolean force) {
        status.postValue(force
                ? "Actualisation de VAVOO et du master M3U en arrière-plan…"
                : "Vérification du cache local et des sources en arrière-plan…");
        final AtomicInteger pending = new AtomicInteger(2);
        final AtomicBoolean everySourceOkay = new AtomicBoolean(true);

        ioExecutor.execute(() -> {
            if (!refreshVavooIfNeeded(force)) everySourceOkay.set(false);
            finishRefresh(pending, everySourceOkay);
        });
        ioExecutor.execute(() -> {
            if (!refreshMasterIfNeeded(force)) everySourceOkay.set(false);
            finishRefresh(pending, everySourceOkay);
        });
    }

    private void finishRefresh(AtomicInteger pending, AtomicBoolean okay) {
        if (pending.decrementAndGet() == 0) {
            status.postValue(okay.get()
                    ? "Catalogue synchronisé · SQLite prêt"
                    : "Actualisation partielle · les anciennes chaînes en cache restent disponibles");
        }
    }

    /** WorkManager entry point; invoked on a background thread. */
    public boolean refreshStaleSourcesBlocking() {
        boolean vavooOkay = refreshVavooIfNeeded(false);
        boolean masterOkay = refreshMasterIfNeeded(false);
        return vavooOkay && masterOkay;
    }

    public void clearDatabaseAsync() {
        ioExecutor.execute(() -> {
            database.runInTransaction(dao::deleteAll);
            statePreferences.edit().clear().apply();
            refreshAllAsync(true);
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
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private boolean refreshVavooIfNeeded(boolean force) {
        try {
            if (!shouldRefreshVavoo(force)) return true;
            List<VavooProvider.VavooChannel> providerChannels = VavooProvider.loadChannels();
            ArrayList<ChannelEntity> rows = new ArrayList<>(providerChannels.size());
            for (VavooProvider.VavooChannel item : providerChannels) {
                if (item == null) continue;
                String name = safe(item.name);
                String id = safe(item.id);
                String catalogReference = safe(item.sourceUrl);
                if (name.isEmpty() || id.isEmpty() || catalogReference.isEmpty()) continue;

                String group = safe(item.group);
                if (group.isEmpty()) group = "VAVOO Live";
                if (!group.startsWith("VAVOO · ")) group = "VAVOO · " + group;
                Channel channel = new Channel(name, "vavoo://" + id, safe(item.logo),
                        group, true, catalogReference, resolveCountry(item.country),
                        "", "vavoo", null);
                rows.add(ChannelEntity.fromChannel("vavoo", "VAVOO", channel));
            }
            if (rows.isEmpty()) return dao.countForSource("vavoo") > 0;

            database.runInTransaction(() -> {
                dao.deleteSource("vavoo");
                dao.insertAll(rows);
            });
            statePreferences.edit().putLong(VAVOO_REFRESH_KEY, System.currentTimeMillis()).apply();
            return true;
        } catch (Exception error) {
            // Do not erase cached VAVOO channels when the provider is offline.
            return dao.countForSource("vavoo") > 0;
        }
    }

    private boolean refreshMasterIfNeeded(boolean force) {
        try {
            if (!shouldRefreshMaster(force)) return true;
            String address = configuredMasterUrl();
            ArrayList<Channel> parsed = M3UParser.load(appContext,
                    "public-master", "M3U publics", "", address,
                    MAX_MASTER_CHANNELS, this::resolveCountry, force);
            if (parsed.isEmpty()) return countPublicChannels() > 0;

            ArrayList<ChannelEntity> rows = new ArrayList<>(parsed.size());
            for (Channel channel : parsed) {
                if (channel == null || safe(channel.url).isEmpty()) continue;
                // VAVOO must never be imported from the generated public master file.
                if (channel.isVavoo || "vavoo".equals(canonicalSourceId(channel.catalogSource))) continue;
                String sourceId = safe(channel.catalogSource);
                if (sourceId.isEmpty() || "public-master".equals(sourceId)) sourceId = "iptv-org-fr";
                Channel normalized = new Channel(channel.name, channel.url, channel.logo, channel.group,
                        false, "", channel.country, channel.epgId, sourceId, null);
                rows.add(ChannelEntity.fromChannel(sourceId, displayName(appContext, sourceId), normalized));
            }
            if (rows.isEmpty()) return countPublicChannels() > 0;

            database.runInTransaction(() -> {
                dao.deleteAllPublicSources();
                dao.insertAll(rows);
            });
            statePreferences.edit().putLong(MASTER_REFRESH_KEY, System.currentTimeMillis()).apply();
            return true;
        } catch (Exception error) {
            // An unavailable master leaves the last successful public catalogue intact.
            return countPublicChannels() > 0;
        }
    }

    private boolean shouldRefreshVavoo(boolean force) {
        if (force || dao.countForSource("vavoo") == 0) return true;
        long last = statePreferences.getLong(VAVOO_REFRESH_KEY, 0L);
        return last == 0L || System.currentTimeMillis() - last
                >= TimeUnit.HOURS.toMillis(REFRESH_INTERVAL_HOURS);
    }

    private boolean shouldRefreshMaster(boolean force) {
        if (force || countPublicChannels() == 0) return true;
        long last = statePreferences.getLong(MASTER_REFRESH_KEY, 0L);
        return last == 0L || System.currentTimeMillis() - last
                >= TimeUnit.HOURS.toMillis(REFRESH_INTERVAL_HOURS);
    }

    private int countPublicChannels() {
        int count = 0;
        for (Source source : SOURCES) {
            if (!"vavoo".equals(source.id)) count += dao.countForSource(source.id);
        }
        return count;
    }

    private String configuredMasterUrl() {
        String configured = urlPreferences.getString(MASTER_URL_KEY, DEFAULT_MASTER_PLAYLIST_URL);
        if (configured == null || configured.trim().isEmpty()) return DEFAULT_MASTER_PLAYLIST_URL;
        return configured.trim();
    }

    private String resolveCountry(String rawCountry) {
        String raw = safe(rawCountry);
        if (raw.isEmpty()) return "";
        if (raw.length() == 2 && Character.isLetter(raw.charAt(0))
                && Character.isLetter(raw.charAt(1))) {
            String code = raw.toUpperCase(Locale.ROOT);
            try {
                Locale countryLocale = new Locale("", code);
                String french = countryLocale.getDisplayCountry(Locale.FRENCH);
                if (french != null && !french.trim().isEmpty() && !french.equals(code)) return french;
            } catch (Exception ignored) {}
        }
        return raw;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public void shutdownForTests() {
        ioExecutor.shutdownNow();
        mappingExecutor.shutdownNow();
    }
}
