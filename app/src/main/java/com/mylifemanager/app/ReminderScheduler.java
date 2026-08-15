package com.mylifemanager.app;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.*;
import java.util.*;

public final class ReminderScheduler {
    static final String PREFS = "mlm_native_reminders";
    static final String PREFIX = "r:";
    static final String CH_ALERT="mlm_alert", CH_SOUND="mlm_sound", CH_VIBRATE="mlm_vibrate", CH_SILENT="mlm_silent";
    private ReminderScheduler() {}
    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel(nm, CH_ALERT, "My Life Manager reminders", true, true);
        createChannel(nm, CH_SOUND, "My Life Manager sound reminders", true, false);
        createChannel(nm, CH_VIBRATE, "My Life Manager vibration reminders", false, true);
        createChannel(nm, CH_SILENT, "My Life Manager silent reminders", false, false);
    }
    private static void createChannel(NotificationManager nm,String id,String name,boolean sound,boolean vibrate){
        NotificationChannel ch=new NotificationChannel(id,name, sound?NotificationManager.IMPORTANCE_HIGH:NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Scheduled reminders from My Life Manager"); ch.enableVibration(vibrate);
        if(vibrate) ch.setVibrationPattern(new long[]{0,180,90,180});
        if(!sound) ch.setSound(null,null); else { Uri uri=Settings.System.DEFAULT_NOTIFICATION_URI; AudioAttributes aa=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(); ch.setSound(uri,aa); }
        nm.createNotificationChannel(ch);
    }
    static int code(String id){ return (id.hashCode() & 0x7fffffff) % 2000000000; }
    static SharedPreferences prefs(Context c){ return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    static JSONObject get(Context c,String id){ try{String raw=prefs(c).getString(PREFIX+id,null);return raw==null?null:new JSONObject(raw);}catch(Exception e){return null;} }
    static void remove(Context c,String id){ prefs(c).edit().remove(PREFIX+id).apply(); }
    public static boolean schedule(Context c, JSONObject input){
        try{
            String id=input.optString("id", "mlm-"+System.currentTimeMillis()); long at=input.optLong("at",System.currentTimeMillis()+1000);
            if(!input.optBoolean("urgent",false) && input.optBoolean("quietHours",false)) at=adjustQuiet(at,input.optString("quietStart","22:30"),input.optString("quietEnd","06:00"));
            if(at<System.currentTimeMillis()+800) at=System.currentTimeMillis()+800; input.put("id",id); input.put("at",at);
            prefs(c).edit().putString(PREFIX+id,input.toString()).apply();
            Intent i=new Intent(c,ReminderReceiver.class).putExtra("id",id); PendingIntent pi=PendingIntent.getBroadcast(c,code(id),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE); if(Build.VERSION.SDK_INT>=23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi); else am.set(AlarmManager.RTC_WAKEUP,at,pi); return true;
        }catch(Exception e){return false;}
    }
    public static void cancel(Context c,String id){
        Intent i=new Intent(c,ReminderReceiver.class).putExtra("id",id); PendingIntent pi=PendingIntent.getBroadcast(c,code(id),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pi); pi.cancel(); remove(c,id);
    }
    public static void rescheduleAll(Context c){
        ensureChannels(c); Map<String,?> all=prefs(c).getAll(); long now=System.currentTimeMillis();
        for(Map.Entry<String,?> e:all.entrySet()) if(e.getKey().startsWith(PREFIX)&&e.getValue() instanceof String){try{JSONObject j=new JSONObject((String)e.getValue());if(j.optLong("at",0)>now)schedule(c,j);else remove(c,j.optString("id"));}catch(Exception ignored){}}
    }
    private static long adjustQuiet(long when,String start,String end){
        try{Calendar c=Calendar.getInstance();c.setTimeInMillis(when);int now=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);int s=parseMin(start),e=parseMin(end);boolean inside=s==e||(s<e?(now>=s&&now<e):(now>=s||now<e));if(!inside)return when;Calendar out=(Calendar)c.clone();out.set(Calendar.HOUR_OF_DAY,e/60);out.set(Calendar.MINUTE,e%60);out.set(Calendar.SECOND,0);out.set(Calendar.MILLISECOND,0);if(out.getTimeInMillis()<=when)out.add(Calendar.DAY_OF_MONTH,1);return out.getTimeInMillis();}catch(Exception x){return when;}
    }
    private static int parseMin(String s){String[] p=s.split(":");return Integer.parseInt(p[0])*60+Integer.parseInt(p[1]);}
    static String channelFor(JSONObject j){boolean sound=j.optBoolean("sound",true),vib=j.optBoolean("vibration",true);return sound?(vib?CH_ALERT:CH_SOUND):(vib?CH_VIBRATE:CH_SILENT);}
}
