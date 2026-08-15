package com.mylifemanager.app;
import android.Manifest;import android.content.pm.PackageManager;import android.os.Build;import android.webkit.JavascriptInterface;import org.json.*;
public class NativeNotificationBridge{
 private final MainActivity a; NativeNotificationBridge(MainActivity a){this.a=a;}
 @JavascriptInterface public String schedule(String json){try{return ReminderScheduler.schedule(a,new JSONObject(json))?"ok":"failed";}catch(Exception e){return "failed";}}
 @JavascriptInterface public String cancel(String json){try{ReminderScheduler.cancel(a,new JSONObject(json).optString("id"));return "ok";}catch(Exception e){return "failed";}}
 @JavascriptInterface public String permissionStatus(){if(Build.VERSION.SDK_INT<33)return "granted";return a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED?"granted":"denied";}
 @JavascriptInterface public String requestPermission(){a.runOnUiThread(a::requestNotificationPermission);return "requested";}
}
