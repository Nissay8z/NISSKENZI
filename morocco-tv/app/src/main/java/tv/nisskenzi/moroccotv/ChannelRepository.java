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
    public static final long REFRESH_INTERVAL_HOURS = 12L;
    private static final int MAX_CHANNELS_PER_SOURCE = 20_000;
    private static final String PREFS = "m3u_sources";
    private static final String DB_PREFS = "catalog_database_state";
    private static final String PERIODIC_WORK_NAME = "nisskenzi_m3u_refresh";

    public static final class Source {
        public final String id;
        public final String name;
        public final String defaultUrl;
        public final String assetName;
        public final String urlPreferenceKey;
        public final boolean bundledAsset;

        Source(String id, String name, String defaultUrl, String assetName,
               String urlPreferenceKey, boolean bundledAsset) {
            this.id = id;
            this.name = name;
            this.defaultUrl = defaultUrl;
            this.assetName = assetName;
            this.urlPreferenceKey = urlPreferenceKey;
            this.bundledAsset = bundledAsset;
        }
    }

    private static final List<Source> SOURCES;
    static {
        ArrayList<Source> sources = new ArrayList<>();
        sources.add(new Source("vavoo", "VAVOO", "",
                "vavoo_catalog.m3u", "vavoo_url", true));
        sources.add(new Source("iptv-org-fr", "IPTV-org France",
                "https://iptv-org.github.io/iptv/countries/fr.m3u",
                "", "iptv_org_url", false));
        sources.add(new Source("pluto-tv-fr", "Pluto TV France",
                "https://i.mjh.nz/PlutoTV/fr.m3u8",
                "", "pluto_url", false));
        sources.add(new Source("samsung-tv-fr", "Samsung TV Plus France",
                "https://i.mjh.nz/SamsungTVPlus/fr.m3u8",
                "", "samsung_url", false));
        sources.add(new Source("free-tv-world", "Free-TV International",
                "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
                "", "free_tv_url", false));
        SOURCES = Collections.unmodifiableList(sources);
    }

    private static volatile ChannelRepository INSTANCE;

    private final Context appContext;
    private final AppDatabase database;
    private final ChannelDao dao;
    private final SharedPreferences urlPreferences;
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
        urlPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        if ("pluto".equals(id) || "pluto tv".equals(id)
                || "pluto-tv-fr".equals(id) || "pluto tv france".equals(id)) return "pluto-tv-fr";
        if ("samsung".equals(id) || "samsung tv".equals(id)
                || "samsung-tv-plus".equals(id) || "samsung-tv-fr".equals(id)
                || "samsung tv plus france".equals(id)) return "samsung-tv-fr";
        if ("free-tv".equals(id) || "free-tv-world".equals(id)
                || "free-tv international".equals(id)) return "free-tv-world";
        return id;
    }

    /** Called by the ViewModel as soon as the home screen is created. */
    public void initialize() {
        schedulePeriodicRefresh();
        if (!initialized.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            seedLocalCatalogueIfEmpty();
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
            seedLocalCatalogueIfEmpty();
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
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private void seedLocalCatalogueIfEmpty() {
        for (Source source : SOURCES) {
            if (!source.bundledAsset) continue;
            try {
                if (dao.countForSource(source.id) == 0) {
                    refreshOneSynchronously(source, true);
                }
            } catch (Exception ignored) {
                status.postValue("M3U local indisponible · " + source.name);
            }
        }
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
        String configured = configuredUrl(source);
        boolean hasRemoteOverride = source.bundledAsset && !configured.isEmpty();
        boolean isRemote = !source.defaultUrl.isEmpty() || hasRemoteOverride;
        if (!isRemote) return count == 0;

        long last = statePreferences.getLong(lastRefreshKey(source), 0L);
        long age = System.currentTimeMillis() - last;
        return count == 0 || last == 0L || age >= TimeUnit.HOURS.toMillis(REFRESH_INTERVAL_HOURS);
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
        String configuredUrl = configuredUrl(source);
        try {
            String address = configuredUrl.isEmpty() ? source.defaultUrl : configuredUrl;
            ArrayList<Channel> parsed = M3UParser.load(appContext, source.id, source.name,
                    source.assetName, address, MAX_CHANNELS_PER_SOURCE, this::resolveCountry, force);
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

    private String configuredUrl(Source source) {
        return urlPreferences.getString(source.urlPreferenceKey, "").trim();
    }

    private static String lastRefreshKey(Source source) {
        return "last_refresh_" + source.id;
    }

    private String resolveCountry(String rawCountry) {
        String raw = rawCountry == null ? "" : rawCountry.trim();
        if (raw.isEmpty()) return "";
        if (raw.length() == 2 && Character.isLetter(raw.charAt(0))
                && Character.isLetter(raw.charAt(1))) {
            String code = raw.toUpperCase(java.util.Locale.ROOT);
            try {
                java.util.Locale locale = new java.util.Locale("", code);
                String french = locale.getDisplayCountry(java.util.Locale.FRENCH);
                if (french != null && !french.trim().isEmpty() && !french.equals(code)) return french;
            } catch (Exception ignored) {}
        }
        return raw;
    }

    /** Worker tests need not own UI observers; process lifetime owns this singleton. */
    public void shutdownForTests() {
        ioExecutor.shutdownNow();
        mappingExecutor.shutdownNow();
    }
}
