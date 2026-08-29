package com.mohan.mylifemanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

public final class ReminderDeliveryWatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String raw = intent == null ? null : intent.getStringExtra(ReminderScheduler.EXTRA_PAYLOAD);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject(raw);
            String id = payload.optString("id", "");
            if (id.isEmpty() || !ReminderStore.contains(context, id)) return;
            if (NotificationPublisher.show(context, payload.toString())) {
                ReminderStore.remove(context, id);
                ReminderStore.removeActive(context, id);
                ReminderStore.recordStatus(context, "notification-watchdog", id,
                        "Bottom card was not confirmed; notification fallback shown");
            } else {
                ReminderStore.recordStatus(context, "delivery-blocked", id,
                        "Bottom card and notification fallback are both blocked");
            }
        } catch (Exception ignored) {}
    }
}
