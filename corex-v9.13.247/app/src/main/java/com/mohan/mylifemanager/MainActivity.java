package com.mohan.mylifemanager;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

public final class MainActivity extends Activity {
    static final int REQUEST_NOTIFICATIONS = 401;
    static final int REQUEST_CAMERA = 402;
    private static final int REQUEST_FILE = 403;
    private static final String START_URL = "file:///android_asset/index.html";
    private static final int COREX_BLUE = Color.rgb(7, 26, 146);
    private static final String RUNTIME_PREFS = "corex_android_runtime";
    private static final String NOTIFICATION_PROMPTED = "notification_prompted_v913247";
    private static final String OVERLAY_PROMPTED = "overlay_prompted_v913247";

    private WebView webView;
    private FrameLayout root;
    private FrameLayout splashOverlay;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    private String pendingOpenPayload;
    private boolean pageReady;
    private boolean appReady;
    private boolean pendingCameraLaunch;
    private long splashShownAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(COREX_BLUE);
        getWindow().setNavigationBarColor(COREX_BLUE);

        root = new FrameLayout(this);
        root.setBackgroundColor(COREX_BLUE);
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(COREX_BLUE);
        root.addView(webView);
        splashOverlay = buildSplashOverlay();
        root.addView(splashOverlay);
        setContentView(root);
        splashShownAt = System.currentTimeMillis();

        configureWebView();
        handleIntent(getIntent());
        if (state == null) webView.loadUrl(START_URL);
        else webView.restoreState(state);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        webView.addJavascriptInterface(new NativeNotificationsBridge(this), "MLMNativeNotificationsNative");
        webView.addJavascriptInterface(new NativeAppBridge(this), "MLMNativeAppNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                dispatchPendingOpen();
                deliverDismissedIds();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                clearCameraOutput(false);
                if (acceptsImages(params)) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingCameraLaunch = true;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
                        return true;
                    }
                    Intent camera = createCameraIntent();
                    if (camera != null) {
                        startActivityForResult(camera, REQUEST_FILE);
                        return true;
                    }
                }
                Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(bestMimeType(params));
                String[] accepted = params == null ? null : params.getAcceptTypes();
                if (accepted != null && accepted.length > 1) choose.putExtra(Intent.EXTRA_MIME_TYPES, accepted);
                startActivityForResult(Intent.createChooser(choose, "Choose a file"), REQUEST_FILE);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        request.deny();
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
                    }
                });
            }
        });
    }

    private static String bestMimeType(WebChromeClient.FileChooserParams params) {
        if (params == null || params.getAcceptTypes() == null) return "*/*";
        for (String type : params.getAcceptTypes()) {
            if (type != null && !type.trim().isEmpty()) return type;
        }
        return "*/*";
    }

    private static boolean acceptsImages(WebChromeClient.FileChooserParams params) {
        if (params == null || params.getAcceptTypes() == null) return false;
        for (String type : params.getAcceptTypes()) {
            if (type != null && type.toLowerCase().startsWith("image/")) return true;
        }
        return false;
    }

    private Intent createCameraIntent() {
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (camera.resolveActivity(getPackageManager()) == null) return null;
        try {
            File directory = getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
            cameraOutputFile = File.createTempFile("corex-camera-", ".jpg", directory);
            cameraOutputUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", cameraOutputFile);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            camera.setClipData(ClipData.newRawUri("Corex camera photo", cameraOutputUri));
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return camera;
        } catch (IOException error) {
            clearCameraOutput(true);
            return null;
        }
    }

    private void clearCameraOutput(boolean deleteFile) {
        if (deleteFile && cameraOutputFile != null && cameraOutputFile.exists()) {
            // This is only the exact temporary camera file created for the cancelled chooser.
            //noinspection ResultOfMethodCallIgnored
            cameraOutputFile.delete();
        }
        cameraOutputFile = null;
        cameraOutputUri = null;
    }

    private FrameLayout buildSplashOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(COREX_BLUE);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.corex_fingerprint_icon);
        icon.setContentDescription(getString(R.string.app_name));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = Math.round(220 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams iconLayout = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        overlay.addView(icon, iconLayout);
        return overlay;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String payload = intent.getStringExtra(ReminderScheduler.EXTRA_PAYLOAD);
        if (payload == null || payload.isEmpty()) return;
        try {
            String id = new JSONObject(payload).optString("id", "");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && !id.isEmpty()) manager.cancel(ReminderScheduler.notificationId(id));
            ReminderStore.remove(this, id);
        } catch (Exception ignored) {}
        pendingOpenPayload = payload;
        dispatchPendingOpen();
        intent.removeExtra(ReminderScheduler.EXTRA_PAYLOAD);
    }

    private void dispatchPendingOpen() {
        if (!pageReady || pendingOpenPayload == null || pendingOpenPayload.isEmpty()) return;
        String payload = pendingOpenPayload;
        pendingOpenPayload = null;
        evaluate("(function(){try{var p=JSON.parse(" + JSONObject.quote(payload)
                + ");if(window.MLMOpenReminderTarget)window.MLMOpenReminderTarget(p);}catch(e){}})();");
    }

    void mirrorScheduledReminder(String payload) {
        evaluate("(function(){try{var p=JSON.parse(" + JSONObject.quote(payload)
                + ");if(window.MLMReceiveNativeReminder)window.MLMReceiveNativeReminder(p);}catch(e){}})();");
    }

    void mirrorCancelledReminder(String id) {
        evaluate("(function(){try{if(window.MLMNativeDismissed)window.MLMNativeDismissed("
                + JSONObject.quote(id == null ? "" : id) + ");}catch(e){}})();");
    }

    String extractReminderId(String payload) {
        try {
            String value = payload == null ? "" : payload.trim();
            return value.startsWith("{") ? new JSONObject(value).optString("id", "") : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void deliverDismissedIds() {
        String ids = ReminderStore.consumeDismissed(this);
        if ("[]".equals(ids)) return;
        evaluate("(function(){try{if(window.MLMNativeDismissed)window.MLMNativeDismissed(" + ids + ");}catch(e){}})();");
    }

    private void evaluate(String script) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    void onWebAppReady() {
        runOnUiThread(() -> {
            if (appReady) return;
            appReady = true;
            long delay = Math.max(0L, 700L - (System.currentTimeMillis() - splashShownAt));
            if (splashOverlay != null) splashOverlay.postDelayed(() -> splashOverlay.animate()
                    .alpha(0f).setDuration(360L).withEndAction(() -> {
                        if (root != null && splashOverlay != null) root.removeView(splashOverlay);
                        splashOverlay = null;
                        if (webView != null) webView.setBackgroundColor(Color.TRANSPARENT);
                        getWindow().setStatusBarColor(Color.rgb(21, 27, 25));
                        getWindow().setNavigationBarColor(Color.rgb(21, 27, 25));
                        requestNotificationPermissionOnce();
                    }).start(), delay);
            else requestNotificationPermissionOnce();
        });
    }

    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestOverlayPermissionOnce();
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).edit().putBoolean(NOTIFICATION_PROMPTED, true).apply();
            requestOverlayPermissionOnce();
            return;
        }
        if (getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).getBoolean(NOTIFICATION_PROMPTED, false)) {
            requestOverlayPermissionOnce();
            return;
        }
        getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).edit().putBoolean(NOTIFICATION_PROMPTED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    private void requestOverlayPermissionOnce() {
        if (Settings.canDrawOverlays(this)) {
            getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).edit().putBoolean(OVERLAY_PROMPTED, true).apply();
            return;
        }
        if (getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).getBoolean(OVERLAY_PROMPTED, false)) return;
        getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE).edit().putBoolean(OVERLAY_PROMPTED, true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    void requestOverlayPermission() {
        runOnUiThread(() -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            requestOverlayPermissionOnce();
            return;
        }
        if (requestCode == REQUEST_CAMERA && pendingCameraLaunch) {
            pendingCameraLaunch = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Intent camera = createCameraIntent();
                if (camera != null) {
                    startActivityForResult(camera, REQUEST_FILE);
                    return;
                }
            }
            if (fileCallback != null) {
                Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*");
                startActivityForResult(Intent.createChooser(choose, "Choose a picture"), REQUEST_FILE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (pageReady) deliverDismissedIds();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || fileCallback == null) return;
        Uri[] result;
        if (resultCode == RESULT_OK && cameraOutputUri != null) result = new Uri[]{cameraOutputUri};
        else result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        clearCameraOutput(resultCode != RESULT_OK);
    }

    @Override
    public void onBackPressed() {
        // The consolidated HTML owns Back inside the currently selected top-level tool.
        // It deliberately remains on that tool's main page instead of traversing another tool.
        evaluate("(function(){try{return window.MLMHandleAndroidBack?window.MLMHandleAndroidBack():'handled';}catch(e){return'handled';}})();");
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        clearCameraOutput(true);
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
