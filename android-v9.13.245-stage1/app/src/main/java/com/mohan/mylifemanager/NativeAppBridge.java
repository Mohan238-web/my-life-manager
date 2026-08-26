package com.mohan.mylifemanager;

import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;

final class NativeAppBridge {
    private final MainActivity activity;

    NativeAppBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String cameraPermissionStatus() {
        return activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                ? "granted" : "denied";
    }

    @JavascriptInterface
    public String requestCameraPermission() {
        activity.runOnUiThread(() -> activity.requestPermissions(
                new String[]{Manifest.permission.CAMERA}, MainActivity.REQUEST_CAMERA));
        return "requested";
    }

    @JavascriptInterface
    public void closeApp() {
        activity.runOnUiThread(activity::finishAndRemoveTask);
    }
}
