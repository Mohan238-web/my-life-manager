package com.mohan.mylifemanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.util.Locale;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 41;
    private static final int NOTIFICATION_PERMISSION = 42;
    private static final int CAMERA_PERMISSION = 43;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingReminderJson;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ReminderReceiver.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            new Handler(Looper.getMainLooper()).postDelayed(
                () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION), 700);
        }
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.addJavascriptInterface(new AppBridge(), "MLMNativeAppNative");
        web.addJavascriptInterface(new NotificationBridge(), "MLMNativeNotificationsNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("blob:")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); return true; }
                catch (Exception ignored) { return false; }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER); return true; }
                catch (Exception e) { fileCallback = null; return false; }
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FILE_CHOOSER && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingReminderJson == null || !canScheduleExactAlarms()) return;
        final String pending = pendingReminderJson;
        pendingReminderJson = null;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String result = scheduleReminder(pending);
            Toast.makeText(MainActivity.this,
                "scheduled".equals(result) ? "Reminder scheduled successfully" : "Reminder scheduling failed: " + result,
                Toast.LENGTH_LONG).show();
        }, 350);
    }

    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        return alarm != null && alarm.canScheduleExactAlarms();
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private String scheduleReminder(String json) {
        try {
            if (!canScheduleExactAlarms()) return "error:exact-alarm-permission-required";
            JSONObject p = new JSONObject(json);
            String id = p.optString("id", "reminder");
            int code = id.hashCode() & 0x7fffffff;
            long at = p.optLong("at", 0L);
            if (at <= System.currentTimeMillis()) return "error:reminder-time-is-in-the-past";
            Intent i = new Intent(MainActivity.this, ReminderReceiver.class);
            i.putExtra("title", p.optString("title", "My Life Manager"));
            i.putExtra("body", p.optString("body", "Reminder"));
            i.putExtra("requestCode", code);
            PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent show = new Intent(MainActivity.this, MainActivity.class);
            PendingIntent showIntent = PendingIntent.getActivity(MainActivity.this, code, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarm.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent), pi);
            return "scheduled";
        } catch (Exception e) { return "error:" + e.getMessage(); }
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("history.back();", null);
    }

    public class AppBridge {
        @JavascriptInterface public void closeApp() { runOnUiThread(() -> finishAndRemoveTask()); }
        @JavascriptInterface public String cameraPermissionStatus() {
            return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
        }
        @JavascriptInterface public void requestCameraPermission() {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION));
        }
    }

    public class NotificationBridge {
        @JavascriptInterface public String permissionStatus() {
            if (Build.VERSION.SDK_INT < 33) return "granted";
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
        }
        @JavascriptInterface public void requestPermission() {
            if (Build.VERSION.SDK_INT >= 33) runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION));
        }
        @JavascriptInterface public String exactAlarmPermissionStatus() {
            return canScheduleExactAlarms() ? "granted" : "denied";
        }
        @JavascriptInterface public void requestExactAlarmPermission() {
            runOnUiThread(() -> openExactAlarmSettings());
        }
        @JavascriptInterface public String schedule(String json) {
            if (!canScheduleExactAlarms()) {
                pendingReminderJson = json;
                runOnUiThread(() -> openExactAlarmSettings());
                return "error:exact-alarm-permission-required";
            }
            return scheduleReminder(json);
        }
        @JavascriptInterface public String cancel(String json) {
            try {
                String id = new JSONObject(json).optString("id", "reminder");
                int code = id.hashCode() & 0x7fffffff;
                Intent i = new Intent(MainActivity.this, ReminderReceiver.class);
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, code, i, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (pi != null) { ((AlarmManager)getSystemService(Context.ALARM_SERVICE)).cancel(pi); pi.cancel(); }
                return "cancelled";
            } catch (Exception e) { return "error:" + e.getMessage(); }
        }
    }

    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
