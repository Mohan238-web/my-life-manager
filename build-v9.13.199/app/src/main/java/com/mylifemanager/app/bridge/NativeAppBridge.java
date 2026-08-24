package com.mylifemanager.app.bridge;

import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;

import androidx.core.content.ContextCompat;

import com.mylifemanager.app.MainActivity;

public final class NativeAppBridge {
    private final MainActivity activity;
    public NativeAppBridge(MainActivity activity) { this.activity = activity; }

    @JavascriptInterface public void closeApp() { activity.runOnUiThread(activity::finishAfterTransition); }
    @JavascriptInterface public String cameraPermissionStatus() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "granted" : "required";
    }
    @JavascriptInterface public String requestCameraPermission() { activity.requestCameraPermission(); return "requested"; }
}
