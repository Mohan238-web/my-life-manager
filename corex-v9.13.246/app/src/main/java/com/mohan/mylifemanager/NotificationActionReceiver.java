package com.mohan.mylifemanager;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!NotificationPublisher.ACTION_DISMISS.equals(intent.getAction())) return;
        String id = intent.getStringExtra(NotificationPublisher.EXTRA_NOTIFICATION_ID);
        int notificationId = intent.getIntExtra("android_notification_id", ReminderScheduler.notificationId(id));
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(notificationId);
        ReminderStore.markDismissed(context, id);
        ReminderStore.remove(context, id);
    }
}
