package com.mohan.mylifemanager;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    public static final String CHANNEL_ID = "mlm_reminders";
    public static final String ACTION_DISMISS = "com.mohan.mylifemanager.DISMISS_REMINDER";

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "My Life Manager reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Focus, task, finance and other scheduled reminders");
            ch.enableVibration(true);
            ch.enableLights(true);
            ch.setLightColor(Color.rgb(47, 118, 109));
            nm.createNotificationChannel(ch);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        ensureChannel(context);
        int notificationId = intent.getIntExtra("requestCode", 1);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (ACTION_DISMISS.equals(intent.getAction())) {
            manager.cancel(notificationId);
            return;
        }
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String reminderId = intent.getStringExtra("reminderId");
        String source = intent.getStringExtra("source");
        long at = intent.getLongExtra("at", System.currentTimeMillis());
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("reminderId", reminderId);
        open.putExtra("source", source);
        PendingIntent content = PendingIntent.getActivity(context, notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent alert = new Intent(context, ReminderAlertActivity.class);
        alert.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (intent.getExtras() != null) alert.putExtras(intent.getExtras());
        PendingIntent fullScreen = PendingIntent.getActivity(context, notificationId + 2, alert, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent dismiss = new Intent(context, ReminderReceiver.class);
        dismiss.setAction(ACTION_DISMISS);
        dismiss.putExtra("requestCode", notificationId);
        PendingIntent dismissAction = PendingIntent.getBroadcast(context, notificationId + 1, dismiss, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String openLabel = "todo".equals(source) ? "Open task" : "notes".equals(source) ? "Open note" : "Open app";
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new android.app.Notification.Builder(context, CHANNEL_ID)
            : new android.app.Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
            .setWhen(at)
            .setShowWhen(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(content)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissAction)
            .addAction(android.R.drawable.ic_menu_view, openLabel, content)
            .setPriority(android.app.Notification.PRIORITY_HIGH);
        manager.notify(notificationId, b.build());
        try { context.startActivity(alert); } catch (Exception ignored) { }
    }
}
