package com.mylifemanager.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "key_value")
public class KeyValueEntity {
    @PrimaryKey @NonNull public String key;
    @NonNull public String value;
    @NonNull public String checksum;
    public long updatedAt;
    public boolean dirty;

    public KeyValueEntity(@NonNull String key, @NonNull String value, @NonNull String checksum, long updatedAt, boolean dirty) {
        this.key = key;
        this.value = value;
        this.checksum = checksum;
        this.updatedAt = updatedAt;
        this.dirty = dirty;
    }
}
