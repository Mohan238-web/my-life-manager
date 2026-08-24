package com.mylifemanager.app.backup;

import com.mylifemanager.app.data.AppDatabase;
import com.mylifemanager.app.util.Checksums;

import org.json.JSONObject;

public final class BackupEnvelope {
    public static final String FORMAT = "MyLifeManagerAndroidBackup";
    public static final int BACKUP_SCHEMA = 1;

    private BackupEnvelope() {}

    public static JSONObject wrap(JSONObject payload, String appVersion, long exportedAt) throws Exception {
        return new JSONObject()
                .put("format", FORMAT)
                .put("backupSchema", BACKUP_SCHEMA)
                .put("databaseSchema", AppDatabase.SCHEMA_VERSION)
                .put("appVersion", appVersion)
                .put("exportedAt", exportedAt)
                .put("payload", payload)
                .put("checksum", Checksums.sha256(payload.toString()));
    }

    public static JSONObject validateAndRead(String json) throws Exception {
        JSONObject envelope = new JSONObject(json);
        if (!FORMAT.equals(envelope.optString("format"))) throw new IllegalArgumentException("This is not a My Life Manager Android backup.");
        int backupSchema = envelope.optInt("backupSchema", -1);
        int databaseSchema = envelope.optInt("databaseSchema", -1);
        if (backupSchema < 1 || backupSchema > BACKUP_SCHEMA) throw new IllegalArgumentException("Backup format is newer than this app.");
        if (databaseSchema < 1 || databaseSchema > AppDatabase.SCHEMA_VERSION) throw new IllegalArgumentException("Database backup version is newer than this app.");
        JSONObject payload = envelope.getJSONObject("payload");
        String expected = envelope.getString("checksum");
        if (!Checksums.sha256(payload.toString()).equals(expected)) throw new IllegalArgumentException("Backup checksum does not match; the file may be damaged.");
        return payload;
    }
}
