package com.mohan.mylifemanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        long now = System.currentTimeMillis();
        for (String raw : ReminderStore.all(context)) {
            try {
                JSONObject payload = new JSONObject(raw);
                if (payload.optLong("at", 0L) <= now) payload.put("at", now + 5_000L);
                ReminderScheduler.schedule(context, payload.toString());
            } catch (Exception ignored) {}
        }
    }
}
