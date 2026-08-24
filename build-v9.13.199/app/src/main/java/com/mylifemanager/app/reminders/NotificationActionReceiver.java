package com.mylifemanager.app.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.data.ReminderEntity;

public final class NotificationActionReceiver extends BroadcastReceiver {
    public static final String ACTION_SNOOZE = "com.mylifemanager.app.SNOOZE";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_SNOOZE.equals(intent.getAction())) return;
        String id = intent.getStringExtra("reminderId");
        if (id == null) return;
        PendingResult pending = goAsync();
        MyLifeManagerApp app = (MyLifeManagerApp) context.getApplicationContext();
        app.executors().disk.execute(() -> {
            try {
                ReminderEntity reminder = app.database().dao().reminder(id);
                if (reminder != null) {
                    reminder.triggerAt = System.currentTimeMillis() + Math.max(1, reminder.snoozeMinutes) * 60_000L;
                    reminder.enabled = true;
                    new ReminderScheduler(context).schedule(reminder);
                    NotificationManagerCompat.from(context).cancel(id.hashCode());
                }
            } finally { pending.finish(); }
        });
    }
}
