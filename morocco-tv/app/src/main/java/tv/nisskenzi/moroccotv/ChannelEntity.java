package tv.nisskenzi.moroccotv;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "channels",
        primaryKeys = {"sourceId", "url"},
        indices = {@Index(value = {"sourceId"}), @Index(value = {"name"})})
public class ChannelEntity {
    @NonNull public String sourceId = "";
    @NonNull public String sourceName = "";
    @NonNull public String url = "";
    @NonNull public String name = "";
    @NonNull public String logo = "";
    @NonNull public String groupName = "";
    @NonNull public String country = "";
    @NonNull public String epgId = "";
    @NonNull public String vavooSourceUrl = "";
    @NonNull public String searchIndex = "";
    public boolean requiresVavooResolve;

    public static ChannelEntity fromChannel(String sourceId, String sourceName, Channel channel) {
        ChannelEntity row = new ChannelEntity();
        row.sourceId = safe(sourceId);
        row.sourceName = safe(sourceName);
        row.url = safe(channel.url);
        row.name = safe(channel.name);
        row.logo = safe(channel.logo);
        row.groupName = safe(channel.group);
        row.country = safe(channel.country);
        row.epgId = safe(channel.epgId);
        row.vavooSourceUrl = safe(channel.vavooSourceUrl);
        row.searchIndex = channel.searchIndex();
        row.requiresVavooResolve = channel.isVavoo;
        return row;
    }

    public Channel toChannel() {
        return new Channel(name, url, logo, groupName, requiresVavooResolve,
                vavooSourceUrl, country, epgId, sourceId, searchIndex);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
