package tv.nisskenzi.moroccotv;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY sourceName COLLATE NOCASE, groupName COLLATE NOCASE, name COLLATE NOCASE")
    LiveData<List<ChannelEntity>> observeAllChannels();

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    int countForSource(String sourceId);

    @Query("SELECT COUNT(*) FROM channels")
    int countAll();

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    void deleteSource(String sourceId);

    /** Replaces the public master bouquet without touching the native VAVOO catalogue. */
    @Query("DELETE FROM channels WHERE sourceId != 'vavoo'")
    void deleteAllPublicSources();


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ChannelEntity> rows);

    @Query("DELETE FROM channels")
    void deleteAll();
}
