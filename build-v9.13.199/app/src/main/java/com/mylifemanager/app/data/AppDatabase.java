package com.mylifemanager.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {KeyValueEntity.class, ReminderEntity.class, SyncQueueEntity.class, CredentialEntity.class},
        version = AppDatabase.SCHEMA_VERSION,
        exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {
    public static final int SCHEMA_VERSION = 1;
    private static volatile AppDatabase instance;

    public abstract AppDao dao();

    public static AppDatabase get(Context context) {
        AppDatabase result = instance;
        if (result != null) return result;
        synchronized (AppDatabase.class) {
            if (instance == null) {
                instance = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "my_life_manager.db")
                        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                        .build();
            }
            return instance;
        }
    }
}
