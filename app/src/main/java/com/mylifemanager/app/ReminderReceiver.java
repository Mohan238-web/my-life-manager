package com.mylifemanager.app;
import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.os.Build;import org.json.*;
public class ReminderReceiver extends BroadcastReceiver{
 @Override public void onReceive(Context c,Intent in){
  String id=in.getStringExtra("id"); if(id==null)return; JSONObject j=ReminderScheduler.get(c,id); if(j==null)return;
  ReminderScheduler.ensureChannels(c);
  if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)return;
  Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent content=PendingIntent.getActivity(c,ReminderScheduler.code(id),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Intent snooze=new Intent(c,SnoozeReceiver.class).putExtra("json",j.toString()).putExtra("notificationId",ReminderScheduler.code(id));
  PendingIntent spi=PendingIntent.getBroadcast(c,ReminderScheduler.code(id)+1,snooze,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,ReminderScheduler.channelFor(j)):new Notification.Builder(c);
  b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(j.optString("title","My Life Manager")).setContentText(j.optString("body","Reminder")).setStyle(new Notification.BigTextStyle().bigText(j.optString("body","Reminder"))).setContentIntent(content).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setWhen(System.currentTimeMillis()).addAction(new Notification.Action.Builder(android.R.drawable.ic_popup_sync,"Snooze",spi).build());
  if(Build.VERSION.SDK_INT<26){if(!j.optBoolean("sound",true))b.setSound(null);if(j.optBoolean("vibration",true))b.setVibrate(new long[]{0,180,90,180});}
  ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ReminderScheduler.code(id),b.build()); ReminderScheduler.remove(c,id);
 }
}
