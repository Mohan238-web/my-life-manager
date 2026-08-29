package com.mohan.mylifemanager;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

final class ReminderDelivery {
    private ReminderDelivery() {}

    static boolean deliver(Context context, String rawPayload) {
        try {
            JSONObject payload = new JSONObject(rawPayload);
            String id = payload.optString("id", "");
            if (id.isEmpty()) return false;
            if (Settings.canDrawOverlays(context)) {
                ReminderStore.putActive(context, payload);
                ReminderStore.recordStatus(context, "overlay-starting", id,
                        "Waiting for the bottom card to confirm visibility");
                if (ReminderOverlayService.show(context, payload.toString())) {
                    return true;
                }
                ReminderStore.removeActive(context, id);
                ReminderStore.recordStatus(context, "overlay-start-failed", id,
                        "Android rejected the overlay service start");
            }
            if (NotificationPublisher.show(context, payload.toString())) {
                ReminderStore.remove(context, id);
                ReminderStore.recordStatus(context, "notification-visible", id,
                        "Bottom overlay unavailable; notification fallback shown");
                return true;
            }
            ReminderStore.recordStatus(context, "delivery-blocked", id,
                    "Overlay and notification delivery are both unavailable");
        } catch (Exception ignored) {}
        return false;
    }
}
