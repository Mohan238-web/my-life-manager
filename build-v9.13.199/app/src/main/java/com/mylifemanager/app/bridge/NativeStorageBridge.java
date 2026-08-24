package com.mylifemanager.app.bridge;

import android.webkit.JavascriptInterface;

import com.mylifemanager.app.MainActivity;
import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.data.KeyValueEntity;
import com.mylifemanager.app.data.SyncQueueEntity;
import com.mylifemanager.app.sync.SyncScheduler;
import com.mylifemanager.app.util.Checksums;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

public final class NativeStorageBridge {
    private final MainActivity activity;
    private final MyLifeManagerApp app;

    public NativeStorageBridge(MainActivity activity) {
        this.activity = activity;
        this.app = activity.app();
    }

    @JavascriptInterface public void put(String key, String value, long updatedAt) {
        if (!managed(key) || value == null || value.length() > 8_000_000) return;
        app.executors().disk.execute(() -> {
            long when = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
            app.database().runInTransaction(() -> {
                app.database().dao().put(new KeyValueEntity(key, value, Checksums.sha256(value), when, true));
                app.database().dao().enqueue(new SyncQueueEntity(UUID.randomUUID().toString(), key, "put", value, when, 0));
            });
            SyncScheduler.enqueue(app);
        });
    }

    @JavascriptInterface public void remove(String key, long updatedAt) {
        if (!managed(key)) return;
        app.executors().disk.execute(() -> {
            long when = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
            app.database().runInTransaction(() -> {
                app.database().dao().removeValue(key);
                app.database().dao().enqueue(new SyncQueueEntity(UUID.randomUUID().toString(), key, "delete", "", when, 0));
            });
            SyncScheduler.enqueue(app);
        });
    }

    @JavascriptInterface public String requestHydration() {
        app.executors().disk.execute(() -> {
            try {
                JSONArray rows = new JSONArray();
                for (KeyValueEntity value : app.database().dao().allValues()) rows.put(new JSONObject()
                        .put("key", value.key).put("value", value.value).put("updatedAt", value.updatedAt));
                activity.evaluateJs("window.MLMNativeHydrate&&window.MLMNativeHydrate(" + JSONObject.quote(rows.toString()) + ")");
            } catch (Exception error) {
                activity.showRecoverableError("Room data could not be loaded", error.getMessage());
            }
        });
        return "loading";
    }

    @JavascriptInterface public String flush() {
        app.executors().disk.execute(() -> {
            try { app.database().getOpenHelper().getWritableDatabase().query("PRAGMA wal_checkpoint(PASSIVE)").close(); }
            catch (Exception ignored) {}
        });
        return "queued";
    }

    @JavascriptInterface public String exportBackup() { activity.startBackupExport(); return "opened"; }
    @JavascriptInterface public String importBackup() { activity.startBackupImport(); return "opened"; }

    private boolean managed(String key) {
        if (key == null || key.length() > 240) return false;
        return key.startsWith("focus-ledger") || key.startsWith("focusLedger") || key.startsWith("smartExpenseManager")
                || key.startsWith("expenseManagerData") || key.startsWith("marketMakerJournal")
                || key.startsWith("powerNotes") || key.startsWith("mileage-") || key.startsWith("madurai-")
                || key.startsWith("myLifeManager.workspace");
    }
}
