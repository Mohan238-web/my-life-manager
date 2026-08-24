package com.mylifemanager.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "reminder")
public class ReminderEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String title;
    @NonNull public String body;
    @NonNull public String source;
    public long triggerAt;
    public boolean sound;
    public boolean vibration;
    public int snoozeMinutes;
    public boolean urgent;
    public boolean enabled;
    public long updatedAt;

    public ReminderEntity(@NonNull String id, @NonNull String title, @NonNull String body, @NonNull String source,
                          long triggerAt, boolean sound, boolean vibration, int snoozeMinutes,
                          boolean urgent, boolean enabled, long updatedAt) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.source = source;
        this.triggerAt = triggerAt;
        this.sound = sound;
        this.vibration = vibration;
        this.snoozeMinutes = snoozeMinutes;
        this.urgent = urgent;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
    }
}
