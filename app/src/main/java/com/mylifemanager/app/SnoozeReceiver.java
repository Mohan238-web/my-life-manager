package com.mylifemanager.app;
import android.app.NotificationManager;import android.content.*;import org.json.*;
public class SnoozeReceiver extends BroadcastReceiver{
 @Override public void onReceive(Context c,Intent in){try{JSONObject j=new JSONObject(in.getStringExtra("json"));int mins=Math.max(1,j.optInt("snoozeMinutes",10));j.put("at",System.currentTimeMillis()+mins*60000L);ReminderScheduler.schedule(c,j);((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(in.getIntExtra("notificationId",0));}catch(Exception ignored){}}
}
