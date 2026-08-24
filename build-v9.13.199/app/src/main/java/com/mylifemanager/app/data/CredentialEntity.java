package com.mylifemanager.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "credential")
public class CredentialEntity {
    @PrimaryKey @NonNull public String scope;
    @NonNull public String derivedHash;
    @NonNull public String salt;
    public int iterations;
    public long updatedAt;

    public CredentialEntity(@NonNull String scope, @NonNull String derivedHash, @NonNull String salt, int iterations, long updatedAt) {
        this.scope = scope;
        this.derivedHash = derivedHash;
        this.salt = salt;
        this.iterations = iterations;
        this.updatedAt = updatedAt;
    }
}
