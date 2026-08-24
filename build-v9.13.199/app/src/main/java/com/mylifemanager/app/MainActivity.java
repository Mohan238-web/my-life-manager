package com.mylifemanager.app;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.mylifemanager.app.backup.NativeBackupManager;
import com.mylifemanager.app.bridge.NativeAppBridge;
import com.mylifemanager.app.bridge.NativeAuthBridge;
import com.mylifemanager.app.bridge.NativeNotificationsBridge;
import com.mylifemanager.app.bridge.NativeStorageBridge;
import com.mylifemanager.app.reminders.ReminderScheduler;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends AppCompatActivity {
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int MAX_BACKUP_BYTES = 20 * 1024 * 1024;
    private FrameLayout root;
    private WebView webView;
    private LinearLayout statusPanel;
    private TextView statusTitle;
    private TextView statusDetail;
    private ProgressBar progress;
    private Button retry;
    private NativeStorageBridge storageBridge;
    private volatile String pendingBackupJson;
    private boolean pageReady;
    private String pendingTargetSource;
    private String pendingTargetId;

    private final ActivityResultLauncher<String> createBackup = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> { if (uri != null) writeBackup(uri); else backupResult(false, "Backup cancelled.", false); });
    private final ActivityResultLauncher<String[]> openBackup = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) readBackup(uri); else backupResult(false, "Restore cancelled.", false); });
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> evaluateJs("window.dispatchEvent(new Event('focus'))"));
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> evaluateJs("window.dispatchEvent(new Event('focus'))"));

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureTarget(getIntent());
        buildRoot();
        setupWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }

    public MyLifeManagerApp app() { return (MyLifeManagerApp) getApplication(); }

    private void buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_background));
        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setPadding(dp(28), dp(28), dp(28), dp(28));
        progress = new ProgressBar(this);
        statusTitle = new TextView(this);
        statusTitle.setText(R.string.loading_app);
        statusTitle.setTextColor(ContextCompat.getColor(this, R.color.app_text));
        statusTitle.setTextSize(19);
        statusTitle.setGravity(Gravity.CENTER);
        statusTitle.setPadding(0, dp(18), 0, dp(8));
        statusDetail = new TextView(this);
        statusDetail.setTextColor(Color.DKGRAY);
        statusDetail.setTextSize(14);
        statusDetail.setGravity(Gravity.CENTER);
        retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(view -> setupWebView());
        statusPanel.addView(progress);
        statusPanel.addView(statusTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statusPanel.addView(statusDetail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = dp(16);
        statusPanel.addView(retry, retryParams);
        root.addView(statusPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void setupWebView() {
        pageReady = false;
        showLoading();
        if (webView != null) {
            root.removeView(webView);
            webView.removeJavascriptInterface("MLMNativeStorageNative");
            webView.removeJavascriptInterface("MLMNativeNotificationsNative");
            webView.removeJavascriptInterface("MLMNativeAppNative");
            webView.removeJavascriptInterface("MLMNativeAuthNative");
            webView.destroy();
        }
        webView = new WebView(this);
        root.addView(webView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) WebView.setSafeBrowsingWhitelist(java.util.Collections.singletonList("appassets.androidplatform.net"), null);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        storageBridge = new NativeStorageBridge(this);
        webView.addJavascriptInterface(storageBridge, "MLMNativeStorageNative");
        webView.addJavascriptInterface(new NativeNotificationsBridge(this), "MLMNativeNotificationsNative");
        webView.addJavascriptInterface(new NativeAppBridge(this), "MLMNativeAppNative");
        webView.addJavascriptInterface(new NativeAuthBridge(app()), "MLMNativeAuthNative");

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())) return assets.shouldInterceptRequest(uri);
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked by local-only policy",
                        java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost()));
            }
            @Override public void onPageCommitVisible(WebView view, String url) {
                pageReady = true;
                statusPanel.setVisibility(View.GONE);
                deliverPendingTarget();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showRecoverableError("My Life Manager could not open", error.getDescription().toString());
            }
            @RequiresApi(Build.VERSION_CODES.O)
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                showRecoverableError("The app page stopped responding", "Tap Retry to restore the page. Your Room data remains safe.");
                return true;
            }
            @RequiresApi(Build.VERSION_CODES.O_MR1)
            @Override public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse callback) {
                callback.backToSafety(true);
                showRecoverableError("Blocked unsafe navigation", "Only the packaged My Life Manager page is allowed.");
            }
        });
        webView.loadUrl(APP_URL);
    }

    private void handleBack() {
        if (!pageReady) { finishAfterTransition(); return; }
        evaluateJs("window.MLMHandleAndroidBack?window.MLMHandleAndroidBack():'unhandled'", result -> {
            String value = result == null ? "" : result.replace("\"", "");
            if ("handled".equals(value)) return;
            if (webView.canGoBack()) webView.goBack(); else finishAfterTransition();
        });
    }

    @Override protected void onPause() {
        if (webView != null) evaluateJs("try{frames.forEach(frame=>{frame.contentWindow.postMessage({type:'workspace-native-background'},'*');frame.contentWindow.dispatchEvent(new Event('pagehide'))});MLMNativeStorage?.flush()}catch{}void 0");
        if (storageBridge != null) storageBridge.flush();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pageReady && storageBridge != null) storageBridge.requestHydration();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureTarget(intent);
        deliverPendingTarget();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("MLMNativeStorageNative");
            webView.removeJavascriptInterface("MLMNativeNotificationsNative");
            webView.removeJavascriptInterface("MLMNativeAppNative");
            webView.removeJavascriptInterface("MLMNativeAuthNative");
            webView.destroy();
        }
        super.onDestroy();
    }

    public void evaluateJs(String script) { evaluateJs(script, null); }
    public void evaluateJs(String script, @Nullable android.webkit.ValueCallback<String> callback) {
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(script, callback); });
    }

    public void showRecoverableError(String title, String detail) {
        runOnUiThread(() -> {
            statusTitle.setText(title);
            statusDetail.setText(detail == null ? "Your saved Room data has not been deleted." : detail);
            progress.setVisibility(View.GONE);
            retry.setVisibility(View.VISIBLE);
            statusPanel.setVisibility(View.VISIBLE);
            statusPanel.bringToFront();
        });
    }

    private void showLoading() {
        statusTitle.setText(R.string.loading_app);
        statusDetail.setText("Loading the local app and Room recovery store.");
        progress.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        statusPanel.setVisibility(View.VISIBLE);
        statusPanel.bringToFront();
    }

    public void requestNotificationPermission() {
        runOnUiThread(() -> { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); });
    }

    public void requestCameraPermission() { runOnUiThread(() -> cameraPermission.launch(Manifest.permission.CAMERA)); }

    public void requestExactAlarmPermission() {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (!manager.canScheduleExactAlarms()) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        });
    }

    public void startBackupExport() {
        app().executors().disk.execute(() -> {
            try {
                pendingBackupJson = new NativeBackupManager(app()).exportJson();
                String date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
                runOnUiThread(() -> createBackup.launch("My-Life-Manager-Android-" + date + ".mlm.json"));
            } catch (Exception error) { backupResult(false, "Backup failed: " + error.getMessage(), false); }
        });
    }

    public void startBackupImport() { runOnUiThread(() -> openBackup.launch(new String[]{"application/json", "text/plain", "application/octet-stream"})); }

    private void writeBackup(Uri uri) {
        String json = pendingBackupJson;
        pendingBackupJson = null;
        if (json == null) { backupResult(false, "Backup data was not prepared.", false); return; }
        app().executors().disk.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("The selected file could not be opened.");
                output.write(json.getBytes(StandardCharsets.UTF_8));
                output.flush();
                backupResult(true, "Version-checked Android backup saved. Login credentials were excluded.", false);
            } catch (Exception error) { backupResult(false, "Backup failed: " + error.getMessage(), false); }
        });
    }

    private void readBackup(Uri uri) {
        app().executors().disk.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalStateException("The selected file could not be opened.");
                byte[] buffer = new byte[16_384]; int read, total = 0;
                while ((read = input.read(buffer)) != -1) { total += read; if (total > MAX_BACKUP_BYTES) throw new IllegalArgumentException("Backup exceeds the 20 MB safety limit."); output.write(buffer, 0, read); }
                int count = new NativeBackupManager(app()).importJson(new String(output.toByteArray(), StandardCharsets.UTF_8), new ReminderScheduler(this));
                backupResult(true, "Restore complete · " + count + " Room record groups checked.", true);
            } catch (Exception error) { backupResult(false, "Restore rejected: " + error.getMessage(), false); }
        });
    }

    private void backupResult(boolean ok, String message, boolean restored) {
        evaluateJs("window.MLMNativeBackupResult&&window.MLMNativeBackupResult(" + ok + "," + JSONObject.quote(message) + "," + restored + ")");
    }

    private void captureTarget(Intent intent) {
        pendingTargetSource = intent == null ? null : intent.getStringExtra("targetSource");
        pendingTargetId = intent == null ? null : intent.getStringExtra("targetId");
    }

    private void deliverPendingTarget() {
        if (!pageReady || pendingTargetSource == null) return;
        String source = pendingTargetSource, id = pendingTargetId == null ? "" : pendingTargetId;
        pendingTargetSource = null; pendingTargetId = null;
        evaluateJs("window.MLMOpenReminderTarget&&window.MLMOpenReminderTarget({source:" + JSONObject.quote(source) + ",id:" + JSONObject.quote(id) + "})");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
