package com.mylifemanager.app.reminders;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.data.ReminderEntity;

public final class ReminderWorker extends Worker {
    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString("reminderId");
        if (id == null) return Result.failure();
        MyLifeManagerApp app = (MyLifeManagerApp) getApplicationContext();
        ReminderEntity reminder = app.database().dao().reminder(id);
        if (reminder == null || !reminder.enabled) return Result.success();
        ReminderNotifier.notify(getApplicationContext(), reminder);
        app.database().dao().disableReminder(id, System.currentTimeMillis());
        return Result.success();
    }
}
