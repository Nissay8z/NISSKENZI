package tv.nisskenzi.moroccotv;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ChannelEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract ChannelDao channelDao();

    public static AppDatabase getInstance(@NonNull Context context) {
        AppDatabase db = INSTANCE;
        if (db == null) {
            synchronized (AppDatabase.class) {
                db = INSTANCE;
                if (db == null) {
                    db = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "nisskenzi_catalog.db")
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .build();
                    INSTANCE = db;
                }
            }
        }
        return db;
    }
}
