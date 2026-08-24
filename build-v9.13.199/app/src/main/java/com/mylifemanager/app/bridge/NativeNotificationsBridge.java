package com.mylifemanager.app.bridge;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.core.content.ContextCompat;

import com.mylifemanager.app.MainActivity;
import com.mylifemanager.app.data.ReminderEntity;
import com.mylifemanager.app.reminders.ReminderScheduler;

import org.json.JSONObject;

public final class NativeNotificationsBridge {
    private final MainActivity activity;
    private final ReminderScheduler scheduler;

    public NativeNotificationsBridge(MainActivity activity) {
        this.activity = activity;
        this.scheduler = new ReminderScheduler(activity);
    }

    @JavascriptInterface public String schedule(String json) {
        try {
            JSONObject item = new JSONObject(json);
            String id = item.getString("id").trim();
            long at = item.getLong("at");
            if (id.isEmpty() || id.length() > 180 || at <= 0) return "error:invalid-reminder";
            ReminderEntity reminder = new ReminderEntity(id, item.optString("title", "My Life Manager"),
                    item.optString("body", "Reminder"), item.optString("source", "global"), at,
                    item.optBoolean("sound", true), item.optBoolean("vibration", true),
                    Math.max(1, item.optInt("snoozeMinutes", 10)), item.optBoolean("urgent", false), true, System.currentTimeMillis());
            return scheduler.schedule(reminder);
        } catch (Exception error) { return "error:" + error.getClass().getSimpleName(); }
    }

    @JavascriptInterface public String cancel(String json) {
        try {
            String id = new JSONObject(json).getString("id");
            scheduler.cancel(id);
            return "cancelled";
        } catch (Exception error) { return "error:invalid-id"; }
    }

    @JavascriptInterface public String permissionStatus() {
        if (Build.VERSION.SDK_INT < 33) return "granted";
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "granted" : "required";
    }

    @JavascriptInterface public String requestPermission() {
        activity.requestNotificationPermission();
        return "requested";
    }

    @JavascriptInterface public String exactAlarmPermissionStatus() { return scheduler.canScheduleExact() ? "granted" : "required"; }

    @JavascriptInterface public String requestExactAlarmPermission() {
        activity.requestExactAlarmPermission();
        return "requested";
    }
}
