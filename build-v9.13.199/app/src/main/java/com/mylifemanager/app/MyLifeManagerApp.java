package com.mylifemanager.app;

import android.app.Application;

import com.mylifemanager.app.data.AppDatabase;
import com.mylifemanager.app.reminders.ReminderNotifier;
import com.mylifemanager.app.sync.SyncScheduler;
import com.mylifemanager.app.util.AppExecutors;

public class MyLifeManagerApp extends Application {
    private final AppExecutors executors = new AppExecutors();
    private AppDatabase database;

    @Override public void onCreate() {
        super.onCreate();
        database = AppDatabase.get(this);
        ReminderNotifier.createChannel(this);
        SyncScheduler.enqueue(this);
    }

    public AppDatabase database() { return database; }
    public AppExecutors executors() { return executors; }
}
