package com.mohan.mylifemanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

final class ReminderScheduler {
    static final String EXTRA_PAYLOAD = "mlm_notification_payload";
    private static final String ALARM_ACTION = "com.mohan.mylifemanager.REMINDER.";
    private static final String WATCHDOG_ACTION = "com.mohan.mylifemanager.REMINDER_WATCHDOG.";

    private ReminderScheduler() {}

    static String schedule(Context context, String rawPayload) {
        try {
            JSONObject payload = new JSONObject(rawPayload == null ? "{}" : rawPayload);
            String id = payload.optString("id", "").trim();
            long at = payload.optLong("at", 0L);
            if (id.isEmpty()) return "error:missing-id";
            if (at <= System.currentTimeMillis()) return "error:past-time";

            at = applyQuietHours(payload, at);
            payload.put("at", at);
            cancelRuntime(context, id);
            ReminderStore.put(context, payload);

            if (canScheduleExact(context)) {
                scheduleExact(context, id, at, payload.toString());
                ReminderStore.recordStatus(context, "scheduled-exact", id, "Alarm accepted");
                return "scheduled:exact";
            }
            scheduleWork(context, id, at, payload.toString());
            ReminderStore.recordStatus(context, "scheduled-delayed", id,
                    "Exact alarm permission is off; WorkManager fallback may be late");
            return "scheduled:workmanager";
        } catch (Exception error) {
            return "error:" + error.getClass().getSimpleName();
        }
    }

    static String cancel(Context context, String raw) {
        try {
            String id = raw == null ? "" : raw.trim();
            if (id.startsWith("{")) id = new JSONObject(id).optString("id", "");
            if (id.isEmpty()) return "error:missing-id";
            cancelRuntime(context, id);
            ReminderStore.remove(context, id);
            return "cancelled";
        } catch (Exception error) {
            return "error:" + error.getClass().getSimpleName();
        }
    }

    static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    static int notificationId(String id) {
        int value = id == null ? 1 : id.hashCode();
        if (value == Integer.MIN_VALUE) value = 1;
        return Math.max(1, Math.abs(value));
    }

    static void scheduleDeliveryWatchdog(Context context, String rawPayload) {
        try {
            JSONObject payload = new JSONObject(rawPayload == null ? "{}" : rawPayload);
            String id = payload.optString("id", "");
            if (id.isEmpty()) return;
            Intent intent = new Intent(context, ReminderDeliveryWatchdogReceiver.class)
                    .setAction(WATCHDOG_ACTION + id)
                    .putExtra(EXTRA_PAYLOAD, payload.toString());
            PendingIntent pending = PendingIntent.getBroadcast(context,
                    notificationId(id) ^ 0x253, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) return;
            long at = System.currentTimeMillis() + 8_000L;
            if (canScheduleExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } catch (Exception ignored) {}
    }

    static void reconcileStored(Context context) {
        long now = System.currentTimeMillis();
        for (String raw : ReminderStore.all(context)) {
            try {
                JSONObject payload = new JSONObject(raw);
                String id = payload.optString("id", "");
                if (payload.optLong("at", 0L) <= now) {
                    if (ReminderStore.hasFreshActive(context, id, 30_000L)) continue;
                    ReminderDelivery.deliver(context, payload.toString());
                } else {
                    schedule(context, payload.toString());
                }
            } catch (Exception ignored) {}
        }
    }

    private static String workName(String id) {
        return "mlm-reminder-" + Integer.toUnsignedString(id.hashCode());
    }

    private static PendingIntent alarmIntent(Context context, String id, String payload, int flags) {
        Intent intent = new Intent(context, NotificationAlarmReceiver.class)
                .setAction(ALARM_ACTION + id)
                .putExtra(EXTRA_PAYLOAD, payload);
        return PendingIntent.getBroadcast(context, notificationId(id), intent,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleExact(Context context, String id, long at, String payload) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            scheduleWork(context, id, at, payload);
            return;
        }
        PendingIntent pending = alarmIntent(context, id, payload, PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
    }

    private static void scheduleWork(Context context, String id, long at, String payload) {
        long delay = Math.max(1L, at - System.currentTimeMillis());
        Data input = new Data.Builder().putString(EXTRA_PAYLOAD, payload).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                .setInputData(input)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request);
    }

    private static void cancelRuntime(Context context, String id) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            PendingIntent pending = alarmIntent(context, id, "", PendingIntent.FLAG_NO_CREATE);
            if (pending != null) alarms.cancel(pending);
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id));
    }

    private static long applyQuietHours(JSONObject payload, long at) {
        if (!payload.optBoolean("quietHours", false) || payload.optBoolean("urgent", false)) return at;
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime start = LocalTime.parse(payload.optString("quietStart", "22:00"), formatter);
            LocalTime end = LocalTime.parse(payload.optString("quietEnd", "07:00"), formatter);
            ZoneId zone = ZoneId.systemDefault();
            LocalDateTime target = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone);
            LocalTime time = target.toLocalTime();
            LocalDate day = target.toLocalDate();
            boolean inside;
            LocalDate endDay = day;
            if (start.equals(end)) return at;
            if (start.isBefore(end)) {
                inside = !time.isBefore(start) && time.isBefore(end);
            } else {
                inside = !time.isBefore(start) || time.isBefore(end);
                if (!time.isBefore(start)) endDay = day.plusDays(1);
            }
            if (!inside) return at;
            return LocalDateTime.of(endDay, end).atZone(zone).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return at;
        }
    }
}
