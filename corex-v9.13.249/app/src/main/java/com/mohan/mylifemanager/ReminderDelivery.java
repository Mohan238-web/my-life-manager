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
                if (ReminderOverlayService.show(context, payload.toString())) {
                    ReminderStore.remove(context, id);
                    return true;
                }
                ReminderStore.removeActive(context, id);
            }
            if (NotificationPublisher.show(context, payload.toString())) {
                ReminderStore.remove(context, id);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
