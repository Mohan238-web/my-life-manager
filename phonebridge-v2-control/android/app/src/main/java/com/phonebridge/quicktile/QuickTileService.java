package com.phonebridge.quicktile;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshFromPc();
    }

    @Override
    public void onClick() {
        super.onClick();

        if (!ControlClient.isPaired(this)) {
            openPairing();
            return;
        }

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel("PhoneBridge...");
            tile.updateTile();
        }

        new Thread(() -> {
            try {
                ControlClient.Status status = ControlClient.status(this);
                if (status.connected) {
                    ControlClient.turnOff(this);
                    main.post(() -> setTile(false, false));
                } else {
                    // The PC normally auto-starts and waits in the tray.
                    // A tap while it is merely waiting must CONNECT, not stop it.
                    ControlClient.turnOn(this);
                    main.post(() -> {
                        setTile(false, true);
                        launchPhoneBridge();
                    });
                    main.postDelayed(this::refreshFromPc, 2500);
                }
            } catch (SecurityException ex) {
                ControlClient.clearPair(this);
                main.post(this::openPairing);
            } catch (Exception ex) {
                main.post(() -> {
                    setTile(false, false);
                    openPairing();
                });
            }
        }, "PhoneBridgeTileClick").start();
    }

    private void refreshFromPc() {
        if (!ControlClient.isPaired(this)) {
            setTile(false, false);
            return;
        }
        new Thread(() -> {
            try {
                ControlClient.Status status = ControlClient.status(this);
                main.post(() -> setTile(status.connected, status.running));
            } catch (SecurityException ex) {
                ControlClient.clearPair(this);
                main.post(() -> setTile(false, false));
            } catch (Exception ex) {
                main.post(() -> setTile(false, false));
            }
        }, "PhoneBridgeTileStatus").start();
    }

    private void setTile(boolean connected, boolean running) {
        Tile tile = getQsTile();
        if (tile == null) return;

        if (connected) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("PhoneBridge");
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("Connected");
        } else if (running) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("PhoneBridge");
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("Waiting");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("PhoneBridge");
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("Off");
        }
        tile.updateTile();
    }

    private void openPairing() {
        Intent intent = new Intent(this, PairActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        collapseAndStart(intent);
    }

    private void launchPhoneBridge() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.phonebridge.app");
        if (launch == null) {
            Intent market = new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.phonebridge.app", "com.phonebridge.app.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            collapseAndStart(market);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        collapseAndStart(launch);
    }

    private void collapseAndStart(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        this, intent.hashCode(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Exception ex) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }
}
