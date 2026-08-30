package com.mohan.mylifemanager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class CorexSyncWorker extends Worker {
    public CorexSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!CorexConnectionStore.isPaired(getApplicationContext())) return Result.success();
        try {
            CorexConnectionStore.exchange(getApplicationContext(),
                    CorexConnectionStore.storedSnapshot(getApplicationContext()), false);
            return Result.success();
        } catch (Exception error) {
            CorexConnectionStore.recordError(getApplicationContext(), error);
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }
}
