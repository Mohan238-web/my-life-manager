package com.mylifemanager.app.backup;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BackupEnvelopeTest {
    @Test public void validBackupPassesChecksumAndVersionChecks() throws Exception {
        JSONObject payload = new JSONObject().put("keyValues", new org.json.JSONArray()).put("reminders", new org.json.JSONArray());
        JSONObject backup = BackupEnvelope.wrap(payload, "test", 1L);
        assertEquals(payload.toString(), BackupEnvelope.validateAndRead(backup.toString()).toString());
    }

    @Test public void newerDatabaseVersionIsRejected() throws Exception {
        JSONObject payload = new JSONObject().put("keyValues", new org.json.JSONArray());
        JSONObject backup = BackupEnvelope.wrap(payload, "test", 1L).put("databaseSchema", 999);
        assertThrows(IllegalArgumentException.class, () -> BackupEnvelope.validateAndRead(backup.toString()));
    }

    @Test public void changedPayloadIsRejectedByChecksum() throws Exception {
        JSONObject backup = BackupEnvelope.wrap(new JSONObject().put("value", 1), "test", 1L);
        backup.getJSONObject("payload").put("value", 2);
        assertThrows(IllegalArgumentException.class, () -> BackupEnvelope.validateAndRead(backup.toString()));
    }
}
