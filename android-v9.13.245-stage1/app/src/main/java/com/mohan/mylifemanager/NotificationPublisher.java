package com.mohan.mylifemanager;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;

import org.json.JSONObject;

final class NotificationPublisher {
    static final String CHANNEL_ID = "my_life_manager_reminders_v1";
    static final String ACTION_DISMISS = "com.mohan.mylifemanager.NOTIFICATION_DISMISS";
    static final String EXTRA_NOTIFICATION_ID = "mlm_notification_id";
    private static final int BLUE = Color.rgb(18, 103, 214);

    private NotificationPublisher() {}

    static boolean show(Context context, String rawPayload) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            JSONObject payload = new JSONObject(rawPayload);
            String id = payload.optString("id", "mlm-reminder");
            String title = payload.optString("title", "My Life Manager");
            String body = payload.optString("body", "Open My Life Manager to review this reminder.");
            int notificationId = ReminderScheduler.notificationId(id);

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return false;
            ensureChannel(context, manager);

            Intent openIntent = new Intent(context, MainActivity.class)
                    .setAction("com.mohan.mylifemanager.OPEN." + id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(ReminderScheduler.EXTRA_PAYLOAD, payload.toString());
            PendingIntent open = PendingIntent.getActivity(context, notificationId, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent dismissIntent = new Intent(context, NotificationActionReceiver.class)
                    .setAction(ACTION_DISMISS)
                    .putExtra(EXTRA_NOTIFICATION_ID, id)
                    .putExtra("android_notification_id", notificationId);
            PendingIntent dismiss = PendingIntent.getBroadcast(context, notificationId, dismissIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String source = payload.optString("source", "");
            Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_fingerprint)
                    .setColor(BLUE)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .setDeleteIntent(dismiss)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(R.drawable.ic_stat_fingerprint, "Open", open).build())
                    .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismiss).build());
            if (!source.isEmpty()) builder.setSubText(labelFor(source));
            if (!payload.optBoolean("sound", true)) builder.setSound(null);
            if (!payload.optBoolean("vibration", true)) builder.setVibrate(new long[]{0L});
            manager.notify(notificationId, builder.build());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    private static String labelFor(String source) {
        String value = source.toLowerCase();
        if (value.startsWith("expense") || value.startsWith("bill")) return "Expense Manager";
        if (value.startsWith("trading") || value.startsWith("market")) return "Trading Journal";
        if (value.startsWith("mileage") || value.startsWith("service")) return "Mileage Calculator";
        if (value.startsWith("focus") || value.startsWith("priority") || value.startsWith("habit")) return "Focus Ledger";
        if (value.startsWith("todo")) return "To-Do";
        if (value.startsWith("notes")) return "Notes";
        return "My Life Manager";
    }
}
