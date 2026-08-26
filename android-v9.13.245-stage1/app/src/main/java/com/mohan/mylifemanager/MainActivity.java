package com.mohan.mylifemanager;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public final class MainActivity extends Activity {
    static final int REQUEST_NOTIFICATIONS = 401;
    static final int REQUEST_CAMERA = 402;
    private static final int REQUEST_FILE = 403;
    private static final String START_URL = "file:///android_asset/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingOpenPayload;
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(21, 27, 25));
        getWindow().setNavigationBarColor(Color.rgb(21, 27, 25));

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.rgb(21, 27, 25));
        setContentView(webView);

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

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
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
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        // The consolidated HTML owns Back inside the currently selected top-level tool.
        // It deliberately remains on that tool's main page instead of traversing another tool.
        evaluate("(function(){try{return window.MLMHandleAndroidBack?window.MLMHandleAndroidBack():'handled';}catch(e){return'handled';}})();");
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
