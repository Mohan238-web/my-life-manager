package com.phonebridge.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * PhoneBridge v1.8 UI/persistence layer.
 *
 * IMPORTANT: media, CameraX, audio, transport, pairing and background-service
 * behaviour remain in the proven v1.6 StreamService. This Activity only changes
 * presentation and persists user choices in the existing private preferences file.
 */
public class MainActivity extends ComponentActivity implements StreamService.Listener {
    private static final int REQ = 42;
    private static final String PREFS = "phonebridge_v1";
    private static final String KEY_HOST = "trusted_host";
    private static final String KEY_PIN = "trusted_pin";
    private static final String KEY_AUTO = "auto_reconnect";
    private static final String KEY_RELEASE_MIC = "release_mic_background";
    private static final String KEY_AUDIO_PROFILE = "audio_profile";
    private static final String KEY_REMEMBER_BUTTONS = "remember_buttons";
    private static final String KEY_RUN_WITH = "run_with";
    private static final String KEY_SHARE_CAMERA = "remember_share_camera";
    private static final String KEY_SHARE_MIC = "remember_share_mic";
    private static final String KEY_MUTE = "remember_muted";
    private static final String KEY_MIRROR = "remember_mirror";
    private static final String KEY_TORCH = "remember_torch";
    private static final String KEY_ZOOM = "remember_zoom";

    private static final int RUN_LAST_USED = 0;
    private static final int RUN_CAMERA_MIC = 1;
    private static final int RUN_CAMERA_ONLY = 2;
    private static final int RUN_MIC_ONLY = 3;
    private static final int RUN_CONNECTED_ONLY = 4;

    private StreamService service;
    private boolean bound;
    private boolean autoConnectAttempted;
    private boolean syncingControls;
    private boolean torch;
    private int zoomProgress;

    private LinearLayout mainPanel;
    private ScrollView settingsPanel;
    private PreviewView preview;
    private TextView status;
    private TextView stats;
    private TextView addressSummary;
    private EditText host;
    private EditText pin;
    private Button connect;
    private Button settingsButton;
    private CheckBox autoReconnect;
    private CheckBox rememberButtons;
    private Spinner runWith;
    private CheckBox releaseMicBackground;
    private Spinner audioProfile;
    private CheckBox shareCameraMain;
    private CheckBox shareMicMain;
    private CheckBox shareCameraSetting;
    private CheckBox shareMicSetting;
    private CheckBox muteSetting;
    private CheckBox mirrorSetting;
    private CheckBox torchSetting;
    private SeekBar zoomSetting;

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((StreamService.LocalBinder) binder).getService();
            service.setListener(MainActivity.this);
            service.attachPreview(preview);
            service.setUiForeground(true);
            service.setReleaseMicWhenBackground(releaseMicBackground == null || releaseMicBackground.isChecked());
            service.setAudioProfile(audioProfile == null ? 0 : audioProfile.getSelectedItemPosition());
            bound = true;
            if (!service.isStreaming()) applyStartupSharingMode();
            else syncControlsFromService(false);
            refreshButtons();
            maybeAutoConnect();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            refreshButtons();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(247, 248, 250));
        getWindow().setNavigationBarColor(Color.rgb(247, 248, 250));
        setContentView(buildUi());
        loadSavedSettings();
        requestPermissionsIfNeeded();
        bindService(new Intent(this, StreamService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private View buildUi() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.rgb(247, 248, 250));
        mainPanel = new LinearLayout(this);
        mainPanel.setOrientation(LinearLayout.VERTICAL);
        mainPanel.setPadding(dp(18), dp(16), dp(18), dp(16));

        status = text("Ready", 14, true);
        status.setTextColor(Color.rgb(34, 92, 64));
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(4); statusParams.bottomMargin = dp(6);
        mainPanel.addView(status, statusParams);

        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(Color.rgb(18, 20, 24));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        previewParams.topMargin = dp(4); previewParams.bottomMargin = dp(10);
        mainPanel.addView(preview, previewParams);

        addressSummary = text("PC not configured", 11, false);
        addressSummary.setTextColor(Color.rgb(95, 100, 110));
        addressSummary.setGravity(Gravity.CENTER_HORIZONTAL);
        mainPanel.addView(addressSummary, new LinearLayout.LayoutParams(-1, -2));

        connect = button("Connect");
        connect.setOnClickListener(v -> toggleConnection());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, dp(48));
        connectParams.topMargin = dp(7); connectParams.bottomMargin = dp(5);
        mainPanel.addView(connect, connectParams);

        LinearLayout shareRow = row();
        shareCameraMain = compactCheck("Share Camera");
        shareMicMain = compactCheck("Share Microphone");
        shareRow.addView(shareCameraMain, weight());
        shareRow.addView(shareMicMain, weight());
        mainPanel.addView(shareRow);
        shareCameraMain.setOnCheckedChangeListener((v, checked) -> onShareCameraChanged(checked));
        shareMicMain.setOnCheckedChangeListener((v, checked) -> onShareMicChanged(checked));

        stats = text("Local connection • waiting for stream", 11, false);
        stats.setTextColor(Color.rgb(105, 110, 120));
        stats.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(-1, -2);
        statsParams.topMargin = dp(4);
        mainPanel.addView(stats, statsParams);
        rootFrame.addView(mainPanel, new FrameLayout.LayoutParams(-1, -1));

        settingsButton = button("⚙");
        settingsButton.setTextSize(22);
        settingsButton.setContentDescription("Settings");
        settingsButton.setOnClickListener(v -> showSettings(true));
        FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END | Gravity.CENTER_VERTICAL);
        gearParams.rightMargin = dp(8);
        rootFrame.addView(settingsButton, gearParams);

        settingsPanel = buildSettingsPanel();
        settingsPanel.setVisibility(View.GONE);
        rootFrame.addView(settingsPanel, new FrameLayout.LayoutParams(-1, -1));
        return rootFrame;
    }

    private ScrollView buildSettingsPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 248, 250));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(16), dp(18), dp(26));
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout titleRow = row();
        Button back = button("←");
        back.setContentDescription("Back to PhoneBridge");
        back.setOnClickListener(v -> showSettings(false));
        titleRow.addView(back, new LinearLayout.LayoutParams(dp(54), dp(46)));
        TextView title = text("Settings", 22, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(10);
        titleRow.addView(title, titleParams);
        page.addView(titleRow);

        page.addView(sectionTitle("Connection"));
        LinearLayout connectionCard = card();
        host = new EditText(this);
        host.setHint("PC / user address"); host.setSingleLine(true);
        connectionCard.addView(host, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout pinRow = row();
        pin = new EditText(this);
        pin.setHint("6-digit PIN"); pin.setSingleLine(true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pinRow.addView(pin, new LinearLayout.LayoutParams(0, dp(52), 1f));
        CheckBox showPin = compactCheck("Show PIN");
        showPin.setOnCheckedChangeListener((v, checked) -> {
            int pos = pin.getSelectionStart();
            pin.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance());
            if (pos >= 0 && pos <= pin.length()) pin.setSelection(pos);
        });
        pinRow.addView(showPin, new LinearLayout.LayoutParams(-2, dp(52)));
        connectionCard.addView(pinRow);
        Button discover = button("Find PC");
        discover.setOnClickListener(v -> discoverPc());
        connectionCard.addView(discover, new LinearLayout.LayoutParams(-1, dp(46)));
        autoReconnect = compactCheck("Remember this PC and reconnect automatically");
        autoReconnect.setOnCheckedChangeListener((v, checked) -> prefs().edit().putBoolean(KEY_AUTO, checked).apply());
        connectionCard.addView(autoReconnect);
        page.addView(connectionCard);

        page.addView(sectionTitle("Remember & Run With"));
        LinearLayout rememberCard = card();
        rememberButtons = compactCheck("Remember Buttons");
        rememberButtons.setOnCheckedChangeListener((v, checked) -> {
            prefs().edit().putBoolean(KEY_REMEMBER_BUTTONS, checked).apply();
            if (checked) saveCurrentButtonStates();
        });
        rememberCard.addView(rememberButtons);
        rememberCard.addView(note("When enabled, Share Camera, Share Microphone, Mute, Mirror, Torch and Zoom are restored after closing and reopening PhoneBridge."));
        LinearLayout runRow = row();
        TextView runLabel = text("Run With", 13, true);
        runRow.addView(runLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        runWith = new Spinner(this);
        String[] runModes = {"Last used buttons", "Camera + microphone", "Camera only", "Microphone only", "Connected only"};
        ArrayAdapter<String> runAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, runModes);
        runAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        runWith.setAdapter(runAdapter);
        runWith.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (syncingControls) return;
                prefs().edit().putInt(KEY_RUN_WITH, position).apply();
                if (service != null && !service.isStreaming()) { applyStartupSharingMode(); syncControlsFromService(false); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        runRow.addView(runWith, new LinearLayout.LayoutParams(0, -2, 1.25f));
        rememberCard.addView(runRow);
        rememberCard.addView(note("Run With controls what the phone starts sharing after a connection. 'Last used buttons' uses your remembered Share Camera / Share Microphone choices."));
        page.addView(rememberCard);

        page.addView(sectionTitle("Camera"));
        LinearLayout cameraCard = card();
        shareCameraSetting = compactCheck("Share Camera");
        shareCameraSetting.setOnCheckedChangeListener((v, checked) -> onShareCameraChanged(checked));
        cameraCard.addView(shareCameraSetting);
        LinearLayout cameraButtons = row();
        Button switchCamera = button("Switch camera");
        switchCamera.setOnClickListener(v -> { if (service != null) service.switchCamera(); });
        cameraButtons.addView(switchCamera, weight());
        torchSetting = compactCheck("Torch");
        torchSetting.setOnCheckedChangeListener((v, checked) -> {
            if (syncingControls) return;
            torch = checked;
            if (service != null) service.setTorch(checked);
            rememberBoolean(KEY_TORCH, checked);
        });
        cameraButtons.addView(torchSetting, weight());
        cameraCard.addView(cameraButtons);
        mirrorSetting = compactCheck("Mirror preview/share");
        mirrorSetting.setOnCheckedChangeListener((v, checked) -> {
            if (syncingControls) return;
            if (service != null) service.setMirrored(checked);
            preview.setScaleX(checked ? -1f : 1f);
            rememberBoolean(KEY_MIRROR, checked);
        });
        cameraCard.addView(mirrorSetting);
        TextView zoomLabel = text("Zoom", 12, false);
        zoomLabel.setTextColor(Color.rgb(85, 90, 100));
        cameraCard.addView(zoomLabel);
        zoomSetting = new SeekBar(this); zoomSetting.setMax(100);
        zoomSetting.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                zoomProgress = progress;
                if (fromUser && service != null) service.setZoom(progress / 100f);
                if (fromUser && rememberButtons != null && rememberButtons.isChecked()) prefs().edit().putInt(KEY_ZOOM, progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraCard.addView(zoomSetting);
        page.addView(cameraCard);

        page.addView(sectionTitle("Audio"));
        LinearLayout audioCard = card();
        shareMicSetting = compactCheck("Share Microphone");
        shareMicSetting.setOnCheckedChangeListener((v, checked) -> onShareMicChanged(checked));
        audioCard.addView(shareMicSetting);
        muteSetting = compactCheck("Mute PhoneBridge microphone");
        muteSetting.setOnCheckedChangeListener((v, checked) -> {
            if (syncingControls) return;
            if (service != null) service.setMuted(checked);
            rememberBoolean(KEY_MUTE, checked);
        });
        audioCard.addView(muteSetting);
        releaseMicBackground = compactCheck("Release phone microphone when I use other apps (recommended)");
        releaseMicBackground.setOnCheckedChangeListener((v, checked) -> {
            prefs().edit().putBoolean(KEY_RELEASE_MIC, checked).apply();
            if (service != null) service.setReleaseMicWhenBackground(checked);
        });
        audioCard.addView(releaseMicBackground);
        LinearLayout audioProfileRow = row();
        TextView audioLabel = text("Phone microphone profile", 12, false);
        audioProfileRow.addView(audioLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        audioProfile = new Spinner(this);
        String[] profiles = {"Balanced", "Meeting", "Studio"};
        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profiles);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioProfile.setAdapter(profileAdapter);
        audioProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (syncingControls) return;
                prefs().edit().putInt(KEY_AUDIO_PROFILE, position).apply();
                if (service != null) service.setAudioProfile(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        audioProfileRow.addView(audioProfile, new LinearLayout.LayoutParams(0, -2, 1f));
        audioCard.addView(audioProfileRow);
        audioCard.addView(note("Background camera sharing remains unchanged. In recommended mode, PhoneBridge releases the phone mic when you switch to another app."));
        page.addView(audioCard);

        page.addView(sectionTitle("Saved data"));
        LinearLayout maintenanceCard = card();
        Button reset = button("Reset saved settings");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset PhoneBridge settings?")
                .setMessage("This clears the saved address, PIN, remembered buttons and startup choices. It does not change PhoneBridge camera or microphone functionality.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, which) -> { prefs().edit().clear().apply(); autoConnectAttempted = false; recreate(); }).show());
        maintenanceCard.addView(reset, new LinearLayout.LayoutParams(-1, dp(46)));
        page.addView(maintenanceCard);

        TextView version = text("PhoneBridge v1.8 • UI & saved settings", 11, false);
        version.setTextColor(Color.rgb(115, 120, 130)); version.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-1, -2);
        versionParams.topMargin = dp(18);
        page.addView(version, versionParams);
        installPersistentTextWatchers();
        return scroll;
    }

    private void installPersistentTextWatchers() {
        host.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { prefs().edit().putString(KEY_HOST, s.toString().trim()).apply(); refreshAddressSummary(); }
        });
        pin.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { prefs().edit().putString(KEY_PIN, s.toString().trim()).apply(); }
        });
    }

    private void loadSavedSettings() {
        SharedPreferences p = prefs();
        syncingControls = true;
        host.setText(p.getString(KEY_HOST, ""));
        pin.setText(p.getString(KEY_PIN, ""));
        autoReconnect.setChecked(p.getBoolean(KEY_AUTO, false));
        rememberButtons.setChecked(p.getBoolean(KEY_REMEMBER_BUTTONS, true));
        runWith.setSelection(clamp(p.getInt(KEY_RUN_WITH, RUN_LAST_USED), 0, 4));
        releaseMicBackground.setChecked(p.getBoolean(KEY_RELEASE_MIC, true));
        audioProfile.setSelection(clamp(p.getInt(KEY_AUDIO_PROFILE, 0), 0, 2));
        boolean remembered = rememberButtons.isChecked();
        boolean cameraOn = remembered ? p.getBoolean(KEY_SHARE_CAMERA, true) : true;
        boolean micOn = remembered ? p.getBoolean(KEY_SHARE_MIC, true) : true;
        shareCameraMain.setChecked(cameraOn); shareCameraSetting.setChecked(cameraOn);
        shareMicMain.setChecked(micOn); shareMicSetting.setChecked(micOn);
        muteSetting.setChecked(remembered && p.getBoolean(KEY_MUTE, false));
        mirrorSetting.setChecked(remembered && p.getBoolean(KEY_MIRROR, false));
        torch = remembered && p.getBoolean(KEY_TORCH, false); torchSetting.setChecked(torch);
        zoomProgress = remembered ? clamp(p.getInt(KEY_ZOOM, 0), 0, 100) : 0; zoomSetting.setProgress(zoomProgress);
        preview.setScaleX(mirrorSetting.isChecked() ? -1f : 1f);
        syncingControls = false;
        refreshAddressSummary();
        if (!host.getText().toString().trim().isEmpty()) status.setText("Saved PC ready");
    }

    private void applyStartupSharingMode() {
        if (service == null || service.isStreaming()) return;
        int mode = runWith == null ? prefs().getInt(KEY_RUN_WITH, RUN_LAST_USED) : runWith.getSelectedItemPosition();
        boolean cameraOn, micOn;
        if (mode == RUN_CAMERA_MIC) { cameraOn = true; micOn = true; }
        else if (mode == RUN_CAMERA_ONLY) { cameraOn = true; micOn = false; }
        else if (mode == RUN_MIC_ONLY) { cameraOn = false; micOn = true; }
        else if (mode == RUN_CONNECTED_ONLY) { cameraOn = false; micOn = false; }
        else {
            boolean remember = rememberButtons == null ? prefs().getBoolean(KEY_REMEMBER_BUTTONS, true) : rememberButtons.isChecked();
            cameraOn = remember ? prefs().getBoolean(KEY_SHARE_CAMERA, true) : true;
            micOn = remember ? prefs().getBoolean(KEY_SHARE_MIC, true) : true;
        }
        service.setVideoEnabled(cameraOn); service.setAudioEnabled(micOn);
        if (rememberButtons != null && rememberButtons.isChecked()) {
            service.setMuted(prefs().getBoolean(KEY_MUTE, false));
            service.setMirrored(prefs().getBoolean(KEY_MIRROR, false));
            preview.setScaleX(service.isMirrored() ? -1f : 1f);
        }
        syncControlsFromService(false);
    }

    private void applyRememberedLiveControls() {
        if (service == null || rememberButtons == null || !rememberButtons.isChecked()) return;
        service.setMuted(prefs().getBoolean(KEY_MUTE, false));
        service.setMirrored(prefs().getBoolean(KEY_MIRROR, false));
        preview.setScaleX(service.isMirrored() ? -1f : 1f);
        zoomProgress = clamp(prefs().getInt(KEY_ZOOM, 0), 0, 100);
        if (zoomSetting != null) zoomSetting.setProgress(zoomProgress);
        service.setZoom(zoomProgress / 100f);
        torch = prefs().getBoolean(KEY_TORCH, false);
        if (torchSetting != null) { syncingControls = true; torchSetting.setChecked(torch); syncingControls = false; }
        if (torch) service.setTorch(true);
    }

    private void onShareCameraChanged(boolean checked) {
        if (syncingControls) return;
        syncingControls = true;
        if (shareCameraMain != null) shareCameraMain.setChecked(checked);
        if (shareCameraSetting != null) shareCameraSetting.setChecked(checked);
        syncingControls = false;
        if (service != null) service.setVideoEnabled(checked);
        rememberBoolean(KEY_SHARE_CAMERA, checked);
    }
    private void onShareMicChanged(boolean checked) {
        if (syncingControls) return;
        syncingControls = true;
        if (shareMicMain != null) shareMicMain.setChecked(checked);
        if (shareMicSetting != null) shareMicSetting.setChecked(checked);
        syncingControls = false;
        if (service != null) service.setAudioEnabled(checked);
        rememberBoolean(KEY_SHARE_MIC, checked);
    }

    private void syncControlsFromService(boolean persistRemembered) {
        if (service == null) return;
        syncingControls = true;
        boolean cam = service.isVideoEnabled(), mic = service.isAudioEnabled();
        if (shareCameraMain != null) shareCameraMain.setChecked(cam);
        if (shareCameraSetting != null) shareCameraSetting.setChecked(cam);
        if (shareMicMain != null) shareMicMain.setChecked(mic);
        if (shareMicSetting != null) shareMicSetting.setChecked(mic);
        if (muteSetting != null) muteSetting.setChecked(service.isMuted());
        if (mirrorSetting != null) mirrorSetting.setChecked(service.isMirrored());
        preview.setScaleX(service.isMirrored() ? -1f : 1f);
        syncingControls = false;
        if (persistRemembered && rememberButtons != null && rememberButtons.isChecked()) {
            prefs().edit().putBoolean(KEY_SHARE_CAMERA, cam).putBoolean(KEY_SHARE_MIC, mic)
                    .putBoolean(KEY_MUTE, service.isMuted()).putBoolean(KEY_MIRROR, service.isMirrored()).apply();
        }
    }

    private void saveCurrentButtonStates() {
        SharedPreferences.Editor e = prefs().edit();
        boolean cam = service != null ? service.isVideoEnabled() : shareCameraMain.isChecked();
        boolean mic = service != null ? service.isAudioEnabled() : shareMicMain.isChecked();
        e.putBoolean(KEY_SHARE_CAMERA, cam).putBoolean(KEY_SHARE_MIC, mic)
                .putBoolean(KEY_MUTE, service != null ? service.isMuted() : muteSetting.isChecked())
                .putBoolean(KEY_MIRROR, service != null ? service.isMirrored() : mirrorSetting.isChecked())
                .putBoolean(KEY_TORCH, torch).putInt(KEY_ZOOM, zoomProgress).apply();
    }
    private void rememberBoolean(String key, boolean value) {
        if (rememberButtons != null && rememberButtons.isChecked()) prefs().edit().putBoolean(key, value).apply();
    }

    private void saveTrustedPc() {
        String h = host.getText().toString().trim();
        String p = pin.getText().toString().trim();
        prefs().edit().putString(KEY_HOST, h).putString(KEY_PIN, p).apply();
        refreshAddressSummary();
    }
    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    private void maybeAutoConnect() {
        if (autoConnectAttempted || service == null || !bound || !hasRequiredPermissions()) return;
        if (autoReconnect == null || !autoReconnect.isChecked()) return;
        String h = host.getText().toString().trim(), p = pin.getText().toString().trim();
        if (h.isEmpty() || p.length() != 6) return;
        autoConnectAttempted = true;
        preview.postDelayed(() -> { if (service != null && !service.isStreaming()) startConnection(h, p, true); }, 600);
    }

    private void toggleConnection() {
        if (service == null) return;
        if (service.isStreaming()) { service.stopStreaming(); refreshButtons(); return; }
        String h = host.getText().toString().trim(), p = pin.getText().toString().trim();
        if (h.isEmpty()) { status.setText("Set the PC address in Settings"); showSettings(true); return; }
        if (p.length() != 6) { status.setText("Set the six-digit PIN in Settings"); showSettings(true); return; }
        saveTrustedPc(); startConnection(h, p, false);
    }
    private void startConnection(String h, String p, boolean automatic) {
        if (service == null) return;
        service.attachPreview(preview); service.setUiForeground(true);
        service.setReleaseMicWhenBackground(releaseMicBackground == null || releaseMicBackground.isChecked());
        service.setAudioProfile(audioProfile == null ? 0 : audioProfile.getSelectedItemPosition());
        if (!service.isStreaming()) applyStartupSharingMode();
        ContextCompat.startForegroundService(this, new Intent(this, StreamService.class));
        status.setText(automatic ? "Reconnecting to saved PC…" : "Connecting…");
        service.startStreaming(h, 8989, p); refreshButtons();
    }

    private void discoverPc() {
        status.setText("Searching for PC…"); showSettings(false);
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true); ds.setSoTimeout(2200);
                byte[] q = "PBR_DISCOVER_V1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ds.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 8990));
                byte[] buf = new byte[256]; DatagramPacket r = new DatagramPacket(buf, buf.length); ds.receive(r);
                String reply = new String(r.getData(), 0, r.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                if (!reply.startsWith("PBR_HERE_V1|")) throw new IllegalStateException("Unexpected reply");
                String ip = r.getAddress().getHostAddress();
                runOnUiThread(() -> { host.setText(ip); saveTrustedPc(); status.setText("PC found: " + ip); });
            } catch (Exception e) {
                runOnUiThread(() -> { status.setText("PC not found. Enter its address in Settings."); showSettings(true); });
            }
        }, "pc-discovery").start();
    }

    private void requestPermissionsIfNeeded() {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!list.isEmpty()) ActivityCompat.requestPermissions(this, list.toArray(new String[0]), REQ);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ) maybeAutoConnect();
    }

    private void refreshButtons() {
        if (connect != null) connect.setText(service != null && service.isStreaming() ? "Disconnect" : "Connect");
        refreshAddressSummary();
    }
    private void refreshAddressSummary() {
        if (addressSummary == null || host == null) return;
        String h = host.getText().toString().trim();
        addressSummary.setText(h.isEmpty() ? "PC not configured • open Settings" : "PC • " + h);
    }

    @Override public void onStatus(String value) {
        runOnUiThread(() -> {
            status.setText(value == null ? "Ready" : value);
            if (value != null && value.startsWith("Connected")) { saveTrustedPc(); applyRememberedLiveControls(); }
            syncControlsFromService(true); refreshButtons();
        });
    }
    @Override public void onStats(String value) { runOnUiThread(() -> stats.setText(value)); }

    @Override protected void onStart() {
        super.onStart();
        if (service != null) {
            service.setListener(this); service.attachPreview(preview);
            service.setReleaseMicWhenBackground(releaseMicBackground == null || releaseMicBackground.isChecked());
            service.setAudioProfile(audioProfile == null ? 0 : audioProfile.getSelectedItemPosition());
            service.setUiForeground(true); syncControlsFromService(false);
        }
    }
    @Override protected void onStop() {
        if (rememberButtons != null && rememberButtons.isChecked()) saveCurrentButtonStates();
        if (service != null) { service.attachPreview(null); service.setUiForeground(false); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (service != null) service.setListener(null);
        if (bound) unbindService(connection);
        bound = false; super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) { showSettings(false); return; }
        super.onBackPressed();
    }

    private void showSettings(boolean show) {
        if (settingsPanel == null || mainPanel == null || settingsButton == null) return;
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        mainPanel.setVisibility(show ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) refreshAddressSummary();
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), Color.rgb(226, 229, 234)); l.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8); l.setLayoutParams(p); return l;
    }
    private TextView sectionTitle(String s) { TextView t = text(s, 14, true); t.setTextColor(Color.rgb(55, 60, 68)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(14); p.bottomMargin = dp(6); t.setLayoutParams(p); return t; }
    private TextView note(String s) { TextView t = text(s, 11, false); t.setTextColor(Color.rgb(95, 100, 110)); t.setPadding(0, dp(3), 0, dp(7)); return t; }
    private CheckBox compactCheck(String s) { CheckBox c = new CheckBox(this); c.setText(s); c.setTextColor(Color.rgb(45, 48, 54)); c.setTextSize(13); c.setMinHeight(dp(42)); return c; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setMinHeight(dp(44)); return b; }
    private TextView text(String s, int sp, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(30, 30, 30)); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
