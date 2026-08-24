package com.mylifemanager.app.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mylifemanager.app.BuildConfig;
import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.auth.SecureTokenStore;
import com.mylifemanager.app.data.KeyValueEntity;
import com.mylifemanager.app.data.SyncQueueEntity;
import com.mylifemanager.app.util.Checksums;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SyncWorker extends Worker {
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

    @NonNull @Override public Result doWork() {
        String base = BuildConfig.SYNC_BASE_URL.trim();
        if (base.isEmpty()) return Result.success(); // Offline queue remains intact until a backend is configured.
        MyLifeManagerApp app = (MyLifeManagerApp) getApplicationContext();
        List<SyncQueueEntity> items = app.database().dao().nextSyncItems(50);
        if (items.isEmpty()) return Result.success();
        String token = new SecureTokenStore(getApplicationContext()).get();
        for (SyncQueueEntity item : items) {
            try {
                JSONObject request = new JSONObject()
                        .put("id", item.id)
                        .put("key", item.recordKey)
                        .put("operation", item.operation)
                        .put("payload", item.payload)
                        .put("createdAt", item.createdAt);
                NetworkResponse response = post(base + "/v1/sync", request.toString(), token);
                if (response.code >= 200 && response.code < 300) {
                    applyRemoteUpdates(app, response.body);
                    app.database().dao().completeSync(item.id);
                }
                else {
                    app.database().dao().failSync(item.id);
                    return response.code >= 400 && response.code < 500 ? Result.failure() : Result.retry();
                }
            } catch (Exception error) {
                app.database().dao().failSync(item.id);
                return Result.retry();
            }
        }
        if (!app.database().dao().nextSyncItems(1).isEmpty()) SyncScheduler.enqueue(getApplicationContext());
        return Result.success();
    }

    private NetworkResponse post(String endpoint, String body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int response = connection.getResponseCode();
        InputStream stream = response >= 200 && response < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = "";
        if (stream != null) try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192]; int read, total = 0;
            while ((read = input.read(buffer)) != -1) { total += read; if (total > 2_000_000) throw new IllegalArgumentException("Sync response too large"); output.write(buffer, 0, read); }
            responseBody = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        connection.disconnect();
        return new NetworkResponse(response, responseBody);
    }

    private void applyRemoteUpdates(MyLifeManagerApp app, String body) throws Exception {
        if (body == null || body.trim().isEmpty()) return;
        JSONArray updates = new JSONObject(body).optJSONArray("updates");
        if (updates == null) return;
        for (int index = 0; index < updates.length(); index++) {
            JSONObject update = updates.getJSONObject(index);
            String key = update.optString("key", "");
            if (!managed(key)) continue;
            long updatedAt = update.optLong("updatedAt", 0L);
            KeyValueEntity local = app.database().dao().value(key);
            if (local != null && local.updatedAt >= updatedAt) continue;
            if (update.optBoolean("deleted", false)) app.database().dao().removeValue(key);
            else {
                String value = update.getString("value");
                app.database().dao().put(new KeyValueEntity(key, value, Checksums.sha256(value), updatedAt, false));
            }
        }
    }

    private boolean managed(String key) {
        return key.startsWith("focus-ledger") || key.startsWith("focusLedger") || key.startsWith("smartExpenseManager")
                || key.startsWith("expenseManagerData") || key.startsWith("marketMakerJournal") || key.startsWith("powerNotes")
                || key.startsWith("mileage-") || key.startsWith("madurai-") || key.startsWith("myLifeManager.workspace");
    }

    private static final class NetworkResponse {
        final int code; final String body;
        NetworkResponse(int code, String body) { this.code = code; this.body = body; }
    }
}
