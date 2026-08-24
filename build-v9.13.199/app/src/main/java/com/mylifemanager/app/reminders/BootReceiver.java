package com.mylifemanager.app.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.data.ReminderEntity;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        MyLifeManagerApp app = (MyLifeManagerApp) context.getApplicationContext();
        app.executors().disk.execute(() -> {
            try {
                ReminderScheduler scheduler = new ReminderScheduler(context);
                for (ReminderEntity reminder : app.database().dao().enabledReminders()) {
                    if (reminder.triggerAt > System.currentTimeMillis()) scheduler.schedule(reminder);
                    else app.database().dao().disableReminder(reminder.id, System.currentTimeMillis());
                }
            } finally { pending.finish(); }
        });
    }
}
