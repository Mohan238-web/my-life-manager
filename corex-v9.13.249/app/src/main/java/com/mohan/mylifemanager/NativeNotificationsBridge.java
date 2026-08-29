package com.mohan.mylifemanager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

final class NativeNotificationsBridge {
    private final MainActivity activity;

    NativeNotificationsBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String schedule(String payload) {
        activity.ensureReminderPermissionsForSchedule();
        String result = ReminderScheduler.schedule(activity, payload);
        if (result.startsWith("scheduled")) activity.mirrorScheduledReminder(payload);
        return result;
    }

    @JavascriptInterface
    public String cancel(String payload) {
        String id = activity.extractReminderId(payload);
        String result = ReminderScheduler.cancel(activity, payload);
        if (result.startsWith("cancelled")) activity.mirrorCancelledReminder(id);
        return result;
    }

    @JavascriptInterface
    public String permissionStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "granted";
        return activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? "granted" : "denied";
    }

    @JavascriptInterface
    public String requestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "granted";
        activity.runOnUiThread(() -> activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, MainActivity.REQUEST_NOTIFICATIONS));
        return "requested";
    }

    @JavascriptInterface
    public String exactAlarmPermissionStatus() {
        return ReminderScheduler.canScheduleExact(activity) ? "granted" : "denied";
    }

    @JavascriptInterface
    public String requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "granted";
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        });
        return "requested";
    }

    @JavascriptInterface
    public String overlayPermissionStatus() {
        return Settings.canDrawOverlays(activity) ? "granted" : "denied";
    }

    @JavascriptInterface
    public String deliveryStatus() {
        try {
            JSONObject status = new JSONObject(ReminderStore.status(activity));
            status.put("notifications", permissionStatus());
            status.put("exactAlarm", exactAlarmPermissionStatus());
            status.put("overlay", overlayPermissionStatus());
            status.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            return status.toString();
        } catch (Exception ignored) {
            return "{\"event\":\"unknown\"}";
        }
    }

    @JavascriptInterface
    public String requestOverlayPermission() {
        activity.requestOverlayPermission();
        return "requested";
    }
}
