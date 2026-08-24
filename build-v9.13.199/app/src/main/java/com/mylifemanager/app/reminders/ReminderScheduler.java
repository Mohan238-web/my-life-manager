package com.mylifemanager.app.reminders;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.data.ReminderEntity;

import java.util.concurrent.TimeUnit;

public final class ReminderScheduler {
    private final Context context;
    private final MyLifeManagerApp app;
    private final AlarmManager alarms;

    public ReminderScheduler(Context context) {
        this.context = context.getApplicationContext();
        this.app = (MyLifeManagerApp) this.context;
        this.alarms = (AlarmManager) this.context.getSystemService(Context.ALARM_SERVICE);
    }

    public String schedule(ReminderEntity reminder) {
        reminder.enabled = true;
        reminder.updatedAt = System.currentTimeMillis();
        app.executors().disk.execute(() -> app.database().dao().putReminder(reminder));
        long trigger = Math.max(System.currentTimeMillis() + 1_000L, reminder.triggerAt);
        cancelPlatformOnly(reminder.id);
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, alarmIntent(reminder.id));
            return "scheduled-exact";
        }
        long delay = Math.max(0L, trigger - System.currentTimeMillis());
        Data data = new Data.Builder().putString("reminderId", reminder.id).build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(workName(reminder.id), ExistingWorkPolicy.REPLACE, work);
        return "scheduled-work";
    }

    public void cancel(String id) {
        cancelPlatformOnly(id);
        app.executors().disk.execute(() -> app.database().dao().deleteReminder(id));
    }

    public boolean canScheduleExact() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms();
    }

    private void cancelPlatformOnly(String id) {
        alarms.cancel(alarmIntent(id));
        WorkManager.getInstance(context).cancelUniqueWork(workName(id));
    }

    private PendingIntent alarmIntent(String id) {
        Intent intent = new Intent(context, ReminderReceiver.class).putExtra("reminderId", id);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private String workName(String id) { return "mlm-reminder-" + Integer.toHexString(id.hashCode()); }
}
