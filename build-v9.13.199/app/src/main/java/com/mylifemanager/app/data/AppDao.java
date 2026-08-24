package com.mylifemanager.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) void put(KeyValueEntity value);
    @Query("SELECT * FROM key_value ORDER BY key") List<KeyValueEntity> allValues();
    @Query("SELECT * FROM key_value WHERE `key` = :key LIMIT 1") KeyValueEntity value(String key);
    @Query("DELETE FROM key_value WHERE `key` = :key") void removeValue(String key);
    @Query("DELETE FROM key_value") void clearValues();

    @Insert(onConflict = OnConflictStrategy.REPLACE) void putReminder(ReminderEntity reminder);
    @Query("SELECT * FROM reminder WHERE id = :id LIMIT 1") ReminderEntity reminder(String id);
    @Query("SELECT * FROM reminder WHERE enabled = 1 ORDER BY triggerAt") List<ReminderEntity> enabledReminders();
    @Query("SELECT * FROM reminder ORDER BY triggerAt") List<ReminderEntity> allReminders();
    @Query("UPDATE reminder SET enabled = 0, updatedAt = :updatedAt WHERE id = :id") void disableReminder(String id, long updatedAt);
    @Query("DELETE FROM reminder WHERE id = :id") void deleteReminder(String id);
    @Query("DELETE FROM reminder") void clearReminders();

    @Insert(onConflict = OnConflictStrategy.REPLACE) void enqueue(SyncQueueEntity item);
    @Query("SELECT * FROM sync_queue ORDER BY createdAt LIMIT :limit") List<SyncQueueEntity> nextSyncItems(int limit);
    @Query("DELETE FROM sync_queue WHERE id = :id") void completeSync(String id);
    @Query("UPDATE sync_queue SET attempts = attempts + 1 WHERE id = :id") void failSync(String id);
    @Query("DELETE FROM sync_queue") void clearSyncQueue();

    @Insert(onConflict = OnConflictStrategy.REPLACE) void putCredential(CredentialEntity credential);
    @Query("SELECT * FROM credential WHERE scope = :scope LIMIT 1") CredentialEntity credential(String scope);
    @Query("DELETE FROM credential WHERE scope = :scope") void deleteCredential(String scope);
}
