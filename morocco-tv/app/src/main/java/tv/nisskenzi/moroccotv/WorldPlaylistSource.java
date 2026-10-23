package tv.nisskenzi.moroccotv;

/** Immutable definition for one public M3U catalogue. */
public final class WorldPlaylistSource {
    public final String id;
    public final String name;
    public final String url;
    public final boolean enabled;
    public final boolean required;
    public final int priority;
    public final int minimumChannels;
    public final boolean countryFromGroup;

    WorldPlaylistSource(String id, String name, String url, boolean enabled,
                        boolean required, int priority, int minimumChannels,
                        boolean countryFromGroup) {
        this.id = id == null ? "" : id.trim();
        this.name = name == null ? this.id : name.trim();
        this.url = url == null ? "" : url.trim();
        this.enabled = enabled;
        this.required = required;
        this.priority = priority;
        this.minimumChannels = Math.max(0, minimumChannels);
        this.countryFromGroup = countryFromGroup;
    }
}
