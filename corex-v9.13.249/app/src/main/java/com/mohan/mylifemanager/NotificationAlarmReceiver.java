package com.mohan.mylifemanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NotificationAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String payload = intent.getStringExtra(ReminderScheduler.EXTRA_PAYLOAD);
        if (payload == null || payload.isEmpty()) return;
        try {
            org.json.JSONObject reminder = new org.json.JSONObject(payload);
            ReminderStore.recordStatus(context, "alarm-received",
                    reminder.optString("id", ""), "Android exact alarm receiver started");
        } catch (Exception ignored) {}
        ReminderDelivery.deliver(context, payload);
    }
}
