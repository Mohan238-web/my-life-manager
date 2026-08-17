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
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new android.app.Notification.Builder(context, CHANNEL_ID)
            : new android.app.Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(intent.getStringExtra("title"))
            .setContentText(intent.getStringExtra("body"))
            .setAutoCancel(true)
            .setContentIntent(content)
            .setPriority(android.app.Notification.PRIORITY_HIGH);
        context.getSystemService(NotificationManager.class).notify(intent.getIntExtra("requestCode", 1), b.build());
    }
}
