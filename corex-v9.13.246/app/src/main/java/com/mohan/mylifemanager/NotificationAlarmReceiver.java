package com.mohan.mylifemanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

public final class NotificationAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String payload = intent.getStringExtra(ReminderScheduler.EXTRA_PAYLOAD);
        if (payload == null || payload.isEmpty()) return;
        if (NotificationPublisher.show(context, payload)) {
            try { ReminderStore.remove(context, new JSONObject(payload).optString("id", "")); }
            catch (Exception ignored) {}
        }
    }
}
