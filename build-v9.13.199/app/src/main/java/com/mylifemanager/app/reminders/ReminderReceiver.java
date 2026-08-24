package com.mylifemanager.app.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.data.ReminderEntity;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("reminderId");
        if (id == null || id.trim().isEmpty()) return;
        PendingResult pending = goAsync();
        MyLifeManagerApp app = (MyLifeManagerApp) context.getApplicationContext();
        app.executors().disk.execute(() -> {
            try {
                ReminderEntity reminder = app.database().dao().reminder(id);
                if (reminder != null && reminder.enabled) {
                    ReminderNotifier.notify(context, reminder);
                    app.database().dao().disableReminder(id, System.currentTimeMillis());
                }
            } finally { pending.finish(); }
        });
    }
}
