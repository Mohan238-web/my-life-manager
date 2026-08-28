package com.mohan.mylifemanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NotificationAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String payload = intent.getStringExtra(ReminderScheduler.EXTRA_PAYLOAD);
        if (payload == null || payload.isEmpty()) return;
        ReminderDelivery.deliver(context, payload);
    }
}
