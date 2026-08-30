package com.phonebridge.tile;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Toast;

import java.lang.reflect.Method;

public class TileStartActivity extends Activity {
    private static final int REQ = 220;
    private boolean turnOn;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = true;
            if (!turnOn) {
                finishClean();
                return;
            }
            try {
                Object service = binder.getClass().getMethod("getService").invoke(binder);
                Method isStreaming = service.getClass().getMethod("isStreaming");
                boolean already = Boolean.TRUE.equals(isStreaming.invoke(service));
                if (!already) configureAndStart(service);
                setActive(true);
            } catch (Throwable e) {
                setActive(false);
                Toast.makeText(TileStartActivity.this,
                        "PhoneBridge could not start sharing", Toast.LENGTH_SHORT).show();
            }
            finishClean();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setDimAmount(0f);
        overridePendingTransition(0, 0);

        turnOn = getIntent().getBooleanExtra("turn_on", true);
        if (!turnOn) {
            stopSharing();
            return;
        }

        if (!hasMediaPermissions()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ);
            return;
        }
        startSharing();
    }

    private boolean hasMediaPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ) return;
        if (hasMediaPermissions()) {
            startSharing();
        } else {
            setActive(false);
            Toast.makeText(this, "Camera and microphone permission are required", Toast.LENGTH_LONG).show();
            finishClean();
        }
    }

    private void startSharing() {
        SharedPreferences p = getSharedPreferences(QuickTileService.PREFS, MODE_PRIVATE);
        String host = p.getString("trusted_host", "");
        String pin = p.getString("trusted_pin", "");
        if (host == null) host = "";
        if (pin == null) pin = "";

        if (host.trim().isEmpty() || !pin.matches("\\d{6}")) {
            setActive(false);
            Toast.makeText(this, "Pair PhoneBridge with your PC once first", Toast.LENGTH_LONG).show();
            try {
                Intent open = new Intent();
                open.setClassName(getPackageName(), "com.phonebridge.app.MainActivity");
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(open);
            } catch (Throwable ignored) {}
            finishClean();
            return;
        }

        // User explicitly tapped the tile, so this transparent activity is the
        // user-visible launch point required by modern Android for camera/mic FGS.
        Intent service = streamServiceIntent();
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            if (!bindService(service, connection, BIND_AUTO_CREATE)) {
                throw new IllegalStateException("bind failed");
            }
        } catch (Throwable e) {
            setActive(false);
            Toast.makeText(this, "PhoneBridge service could not start", Toast.LENGTH_LONG).show();
            finishClean();
        }
    }

    private void configureAndStart(Object service) throws Exception {
        SharedPreferences p = getSharedPreferences(QuickTileService.PREFS, MODE_PRIVATE);
        boolean remember = p.getBoolean("remember_buttons", true);

        boolean camera;
        boolean mic;
        if (remember) {
            camera = p.getBoolean("remember_share_camera", true);
            mic = p.getBoolean("remember_share_mic", true);
        } else {
            int mode = p.getInt("run_with", 0);
            camera = mode != 3 && mode != 4;
            mic = mode != 2 && mode != 4;
        }

        boolean muted = remember && p.getBoolean("remember_muted", false);
        boolean mirror = remember && p.getBoolean("remember_mirror", false);
        boolean torch = remember && p.getBoolean("remember_torch", false);
        int zoom = remember ? p.getInt("remember_zoom", 0) : 0;
        int audioProfile = p.getInt("audio_profile", 0);
        String host = p.getString("trusted_host", "");
        String pin = p.getString("trusted_pin", "");

        // Required behavior: camera + microphone remain active after switching apps.
        p.edit().putBoolean("release_mic_background", false).apply();

        invoke(service, "setReleaseMicWhenBackground", new Class[]{boolean.class}, false);
        invoke(service, "setUiForeground", new Class[]{boolean.class}, false);
        invoke(service, "setVideoEnabled", new Class[]{boolean.class}, camera);
        invoke(service, "setAudioEnabled", new Class[]{boolean.class}, mic);
        invoke(service, "setMuted", new Class[]{boolean.class}, muted);
        invoke(service, "setMirrored", new Class[]{boolean.class}, mirror);
        invoke(service, "setTorch", new Class[]{boolean.class}, torch);
        invoke(service, "setZoom", new Class[]{float.class}, Math.max(0f, Math.min(1f, zoom / 100f)));
        invoke(service, "setAudioProfile", new Class[]{int.class}, audioProfile);
        invoke(service, "startStreaming",
                new Class[]{String.class, int.class, String.class, String.class},
                host.trim(), 8989, pin.trim(), "");
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getMethod(name, types);
        return m.invoke(target, args);
    }

    private void stopSharing() {
        try {
            Intent stop = streamServiceIntent();
            stop.setAction("com.phonebridge.app.STOP");
            startService(stop);
        } catch (Throwable ignored) {}
        setActive(false);
        finishClean();
    }

    private Intent streamServiceIntent() {
        Intent i = new Intent();
        i.setClassName(getPackageName(), "com.phonebridge.app.StreamService");
        return i;
    }

    private void setActive(boolean active) {
        getSharedPreferences(QuickTileService.PREFS, MODE_PRIVATE)
                .edit().putBoolean(QuickTileService.KEY_ACTIVE, active).apply();
        QuickTileService.requestRefresh(this);
    }

    private void finishClean() {
        if (bound) {
            try { unbindService(connection); } catch (Throwable ignored) {}
            bound = false;
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        if (bound) {
            try { unbindService(connection); } catch (Throwable ignored) {}
            bound = false;
        }
        super.onDestroy();
    }
}
