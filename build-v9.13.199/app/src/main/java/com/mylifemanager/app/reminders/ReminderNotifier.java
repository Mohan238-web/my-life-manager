package com.mylifemanager.app.reminders;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.mylifemanager.app.MainActivity;
import com.mylifemanager.app.R;
import com.mylifemanager.app.data.ReminderEntity;

public final class ReminderNotifier {
    public static final String CHANNEL_ID = "my_life_manager_reminders_v1";
    private ReminderNotifier() {}

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    public static boolean notify(Context context, ReminderEntity reminder) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        Intent open = new Intent(context, MainActivity.class)
                .putExtra("targetSource", reminder.source)
                .putExtra("targetId", reminder.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, reminder.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent snooze = new Intent(context, NotificationActionReceiver.class)
                .setAction(NotificationActionReceiver.ACTION_SNOOZE)
                .putExtra("reminderId", reminder.id);
        PendingIntent snoozeIntent = PendingIntent.getBroadcast(context, reminder.id.hashCode() ^ 0x51F0, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(reminder.title)
                .setContentText(reminder.body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reminder.body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setPriority(reminder.urgent ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setOnlyAlertOnce(false)
                .setVibrate(reminder.vibration ? new long[]{0, 180, 120, 180} : null)
                .addAction(0, "Snooze " + reminder.snoozeMinutes + " min", snoozeIntent);
        if (!reminder.sound) builder.setSilent(true);
        NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), builder.build());
        return true;
    }
}
