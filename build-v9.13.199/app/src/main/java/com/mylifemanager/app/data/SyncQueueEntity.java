package com.mylifemanager.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sync_queue")
public class SyncQueueEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String recordKey;
    @NonNull public String operation;
    @NonNull public String payload;
    public long createdAt;
    public int attempts;

    public SyncQueueEntity(@NonNull String id, @NonNull String recordKey, @NonNull String operation,
                           @NonNull String payload, long createdAt, int attempts) {
        this.id = id;
        this.recordKey = recordKey;
        this.operation = operation;
        this.payload = payload;
        this.createdAt = createdAt;
        this.attempts = attempts;
    }
}
