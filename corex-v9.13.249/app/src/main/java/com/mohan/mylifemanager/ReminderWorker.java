package com.mohan.mylifemanager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class ReminderWorker extends Worker {
    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String payload = getInputData().getString(ReminderScheduler.EXTRA_PAYLOAD);
        if (payload == null || payload.isEmpty()) return Result.failure();
        return ReminderDelivery.deliver(getApplicationContext(), payload) ? Result.success() : Result.retry();
    }
}
