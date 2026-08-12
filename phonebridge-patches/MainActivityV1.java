package com.phonebridge.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class MainActivity extends ComponentActivity implements StreamService.Listener {
    private static final int REQ = 42;
    private static final String PREFS = "phonebridge_v1";
    private static final String KEY_HOST = "host";
    private static final String KEY_PIN = "pin";
    private static final String KEY_AUTO = "auto";

    private StreamService service;
    private boolean bound;
    private boolean autoAttempted;
    private PreviewView preview;
    private TextView status;
    private TextView stats;
    private EditText host;
    private EditText pin;
    private Button connect;
    private Button mute;
    private CheckBox remember;
    private boolean torch;

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((StreamService.LocalBinder) binder).getService();
            service.setListener(MainActivity.this);
            service.attachPreview(preview);
            bound = true;
            refreshButtons();
            maybeAutoConnect();
        }
        @Override public void onServiceDisconnected(ComponentName name) { bound = false; service = null; }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestPermissionsIfNeeded();
        bindService(new Intent(this, StreamService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private View buildUi() {
        SharedPreferences p = prefs();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 249, 250));

        TextView title = text("PhoneBridge", 24, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = text("Camera + microphone • local connection", 12, false);
        subtitle.setTextColor(Color.rgb(90, 96, 104));
        root.addView(subtitle);

        status = text("Ready", 14, true);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout connection = row();
        host = new EditText(this);
        host.setHint("PC IP");
        host.setSingleLine(true);
        host.setText(p.getString(KEY_HOST, ""));
        connection.addView(host, new LinearLayout.LayoutParams(0, -2, 2f));
        pin = new EditText(this);
        pin.setHint("6-digit PIN");
        pin.setSingleLine(true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setText(p.getString(KEY_PIN, ""));
        connection.addView(pin, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(connection);

        LinearLayout pairRow = row();
        Button discover = button("Find PC");
        discover.setOnClickListener(v -> discoverPc());
        pairRow.addView(discover, weight());
        connect = button("Connect");
        connect.setOnClickListener(v -> toggleConnection());
        pairRow.addView(connect, weight());
        root.addView(pairRow);

        remember = new CheckBox(this);
        remember.setText("Remember this PC and reconnect automatically");
        remember.setChecked(p.getBoolean(KEY_AUTO, true));
        remember.setTextColor(Color.rgb(45,45,45));
        root.addView(remember);

        LinearLayout controls = row();
        Button camera = button("Switch camera");
        camera.setOnClickListener(v -> { if (service != null) service.switchCamera(); });
        controls.addView(camera, weight());
        Button torchBtn = button("Torch");
        torchBtn.setOnClickListener(v -> { torch = !torch; if (service != null) service.setTorch(torch); });
        controls.addView(torchBtn, weight());
        mute = button("Mute");
        mute.setOnClickListener(v -> { if (service != null) { service.setMuted(!service.isMuted()); refreshButtons(); } });
        controls.addView(mute, weight());
        Button mirror = button("Mirror");
        mirror.setOnClickListener(v -> { if (service != null) service.setMirrored(!service.isMirrored()); preview.setScaleX(preview.getScaleX() * -1f); });
        controls.addView(mirror, weight());
        root.addView(controls);

        TextView zoomLabel = text("Zoom", 12, false);
        root.addView(zoomLabel);
        SeekBar zoom = new SeekBar(this);
        zoom.setMax(100);
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (service != null && fromUser) service.setZoom(progress / 100f); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(zoom);

        stats = text("720p stable pipeline • PCM 48 kHz", 12, false);
        stats.setTextColor(Color.rgb(90,96,104));
        stats.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(stats);
        return root;
    }

    private void savePairing() {
        if (!remember.isChecked()) {
            prefs().edit().putBoolean(KEY_AUTO, false).apply();
            return;
        }
        prefs().edit()
                .putString(KEY_HOST, host.getText().toString().trim())
                .putString(KEY_PIN, pin.getText().toString().trim())
                .putBoolean(KEY_AUTO, true)
                .apply();
    }

    private void maybeAutoConnect() {
        if (autoAttempted || service == null || service.isStreaming() || remember == null || !remember.isChecked()) return;
        String h = host.getText().toString().trim();
        String p = pin.getText().toString().trim();
        if (h.isEmpty() || p.length() != 6) return;
        autoAttempted = true;
        preview.postDelayed(() -> {
            if (service != null && !service.isStreaming()) {
                status.setText("Reconnecting to remembered PC…");
                startConnection(h, p);
            }
        }, 450);
    }

    private void startConnection(String h, String p) {
        savePairing();
        service.attachPreview(preview);
        ContextCompat.startForegroundService(this, new Intent(this, StreamService.class));
        service.startStreaming(h, 8989, p);
        refreshButtons();
    }

    private void toggleConnection() {
        if (service == null) return;
        if (service.isStreaming()) {
            service.stopStreaming();
        } else {
            String h = host.getText().toString().trim();
            String p = pin.getText().toString().trim();
            if (h.isEmpty()) { status.setText("Enter the PC IP address"); return; }
            if (p.length() != 6) { status.setText("Enter the six-digit PC PIN"); return; }
            startConnection(h, p);
        }
        refreshButtons();
    }

    private void discoverPc() {
        status.setText("Searching for PC…");
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                ds.setSoTimeout(2200);
                byte[] q = "PBR_DISCOVER_V1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ds.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 8990));
                byte[] buf = new byte[256];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                ds.receive(r);
                String reply = new String(r.getData(), 0, r.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                if (!reply.startsWith("PBR_HERE_V1|")) throw new IllegalStateException("Unexpected reply");
                String ip = r.getAddress().getHostAddress();
                runOnUiThread(() -> { host.setText(ip); status.setText("PC found: " + ip); savePairing(); });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("PC not found. Enter its IP manually."));
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

    private void refreshButtons() {
        if (connect != null) connect.setText(service != null && service.isStreaming() ? "Disconnect" : "Connect");
        if (mute != null) mute.setText(service != null && service.isMuted() ? "Unmute" : "Mute");
    }

    @Override public void onStatus(String value) { runOnUiThread(() -> { status.setText(value); refreshButtons(); }); }
    @Override public void onStats(String value) { runOnUiThread(() -> stats.setText(value)); }

    @Override protected void onDestroy() {
        if (bound) unbindService(connection);
        bound = false;
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private TextView text(String s, int sp, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(30,30,30)); if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return t; }
}
