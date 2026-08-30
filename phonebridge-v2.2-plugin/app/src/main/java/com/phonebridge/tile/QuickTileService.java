package com.phonebridge.tile;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickTileService extends TileService {
    static final String PREFS = "phonebridge_v1";
    static final String KEY_ACTIVE = "quick_tile_active";

    @Override
    public void onStartListening() {
        super.onStartListening();
        render();
    }

    @Override
    public void onClick() {
        super.onClick();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean turnOn = !p.getBoolean(KEY_ACTIVE, false);

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel(turnOn ? "PhoneBridge…" : "Disconnecting…");
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(turnOn ? "Connecting" : "Stopping");
            tile.updateTile();
        }

        Intent intent = new Intent(this, TileStartActivity.class)
                .putExtra("turn_on", turnOn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        if (Build.VERSION.SDK_INT >= 34) {
            int requestCode = turnOn ? 2201 : 2202;
            PendingIntent pi = PendingIntent.getActivity(
                    this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void render() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean active = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
        tile.setLabel("PhoneBridge");
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(active ? "Connected / reconnecting" : "Off");
        }
        tile.updateTile();
    }

    static void requestRefresh(android.content.Context context) {
        TileService.requestListeningState(
                context,
                new ComponentName(context, QuickTileService.class));
    }
}
