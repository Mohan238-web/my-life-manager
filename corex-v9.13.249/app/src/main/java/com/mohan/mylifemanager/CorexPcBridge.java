package com.mohan.mylifemanager;

import android.webkit.JavascriptInterface;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CorexPcBridge {
    private static final String UNIQUE_SYNC = "corex-pc-sync";
    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    CorexPcBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String state() {
        return CorexConnectionStore.state(activity).toString();
    }

    @JavascriptInterface
    public void pair(String host, int port, String code, String transport, String snapshot) {
        executor.execute(() -> {
            send("working", message("Pairing securely with the PC…"));
            try {
                JSONObject result = CorexConnectionStore.pair(
                        activity, host, port, code, transport, snapshot);
                result.put("type", "paired");
                send(result);
                deliverPending();
                scheduleBackgroundSync(activity);
            } catch (Exception error) {
                CorexConnectionStore.recordError(activity, error);
                sendError(error);
            }
        });
    }

    @JavascriptInterface
    public void scanQr() {
        activity.startQrScan();
    }

    void sendScanError(String message) {
        JSONObject event = CorexConnectionStore.state(activity);
        try {
            event.put("type", "error");
            event.put("message", message == null ? "The QR code could not be read." : message);
        } catch (Exception ignored) {}
        send(event);
    }

    @JavascriptInterface
    public void syncNow(String snapshot) {
        if (snapshot != null && !snapshot.trim().isEmpty()) {
            CorexConnectionStore.queueSnapshot(activity, snapshot);
        }
        executor.execute(() -> {
            send("working", message("Synchronizing with Corex Companion…"));
            try {
                JSONObject result = CorexConnectionStore.exchange(activity, snapshot, false);
                result.put("type", "synced");
                send(result);
                deliverPending();
            } catch (Exception error) {
                CorexConnectionStore.recordError(activity, error);
                sendError(error);
            }
        });
    }

    @JavascriptInterface
    public void queueSnapshot(String snapshot) {
        CorexConnectionStore.queueSnapshot(activity, snapshot);
        if (CorexConnectionStore.isPaired(activity)) scheduleBackgroundSync(activity);
    }

    @JavascriptInterface
    public void disconnect() {
        CorexConnectionStore.disconnect(activity);
        WorkManager.getInstance(activity).cancelUniqueWork(UNIQUE_SYNC);
        JSONObject state = CorexConnectionStore.state(activity);
        try { state.put("type", "disconnected"); } catch (Exception ignored) {}
        send(state);
    }

    void deliverPending() {
        String pending = CorexConnectionStore.consumePendingSnapshot(activity);
        if (pending == null || pending.isEmpty()) return;
        JSONObject event = CorexConnectionStore.state(activity);
        try {
            event.put("type", "incoming");
            event.put("snapshot", pending);
        } catch (Exception ignored) {}
        send(event);
    }

    void sendState() {
        JSONObject state = CorexConnectionStore.state(activity);
        try { state.put("type", "state"); } catch (Exception ignored) {}
        send(state);
    }

    void shutdown() {
        executor.shutdownNow();
    }

    static void scheduleBackgroundSync(android.content.Context context) {
        if (!CorexConnectionStore.isPaired(context)) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CorexSyncWorker.class)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_SYNC, ExistingWorkPolicy.REPLACE, request);
    }

    private JSONObject message(String text) {
        JSONObject value = new JSONObject();
        try { value.put("message", text); } catch (Exception ignored) {}
        return value;
    }

    private void send(String type, JSONObject event) {
        try { event.put("type", type); } catch (Exception ignored) {}
        send(event);
    }

    private void sendError(Exception error) {
        JSONObject event = CorexConnectionStore.state(activity);
        try {
            event.put("type", "error");
            String message = error.getMessage();
            event.put("message", message == null || message.trim().isEmpty()
                    ? "The PC connection failed." : message);
        } catch (Exception ignored) {}
        send(event);
    }

    private void send(JSONObject event) {
        activity.dispatchPcEvent(event.toString());
    }
}
