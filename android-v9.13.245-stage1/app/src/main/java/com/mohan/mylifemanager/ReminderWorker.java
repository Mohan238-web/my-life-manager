package com.mohan.mylifemanager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

public final class ReminderWorker extends Worker {
    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String payload = getInputData().getString(ReminderScheduler.EXTRA_PAYLOAD);
        if (payload == null || payload.isEmpty()) return Result.failure();
        if (!NotificationPublisher.show(getApplicationContext(), payload)) return Result.retry();
        try { ReminderStore.remove(getApplicationContext(), new JSONObject(payload).optString("id", "")); }
        catch (Exception ignored) {}
        return Result.success();
    }
}
