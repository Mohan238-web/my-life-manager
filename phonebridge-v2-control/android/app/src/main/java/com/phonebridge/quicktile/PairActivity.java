package com.phonebridge.quicktile;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Typeface;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class PairActivity extends Activity {
    private EditText hostField;
    private EditText pinField;
    private TextView statusText;
    private Button discoverButton;
    private Button pairButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("PhoneBridge Quick Toggle");

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Pair Quick Toggle");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView help = new TextView(this);
        help.setText("\nOn the PC tray icon choose “Pair mobile Quick Toggle”. Then use Discover and enter the 6-digit PIN once.\n");
        help.setTextSize(15);
        root.addView(help, matchWrap());

        hostField = new EditText(this);
        hostField.setHint("PC IP address");
        hostField.setSingleLine(true);
        hostField.setInputType(InputType.TYPE_CLASS_PHONE);
        String savedHost = ControlClient.host(this);
        if (!savedHost.isEmpty()) hostField.setText(savedHost);
        root.addView(hostField, matchWrap());

        pinField = new EditText(this);
        pinField.setHint("6-digit pairing PIN");
        pinField.setSingleLine(true);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(pinField, matchWrap());

        discoverButton = new Button(this);
        discoverButton.setText("Discover PC");
        discoverButton.setOnClickListener(v -> discover());
        root.addView(discoverButton, matchWrap());

        pairButton = new Button(this);
        pairButton.setText("Pair");
        pairButton.setOnClickListener(v -> pair());
        root.addView(pairButton, matchWrap());

        statusText = new TextView(this);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(12), 0, 0);
        root.addView(statusText, matchWrap());

        setContentView(root);
        discover();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void discover() {
        setBusy(true);
        statusText.setText("Searching your Wi-Fi / hotspot...");
        new Thread(() -> {
            try {
                ControlClient.Discovery d = ControlClient.discover();
                runOnUiThread(() -> {
                    hostField.setText(d.host);
                    statusText.setText("Found " + d.name + " at " + d.host);
                    setBusy(false);
                    pinField.requestFocus();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    statusText.setText("PC not found automatically. Enter the PC IPv4 address shown in the tray pairing window.");
                    setBusy(false);
                });
            }
        }, "PhoneBridgeDiscover").start();
    }

    private void pair() {
        String host = hostField.getText().toString().trim();
        String pin = pinField.getText().toString().trim();

        if (host.isEmpty()) {
            hostField.setError("Enter the PC address");
            return;
        }
        if (!pin.matches("\\d{6}")) {
            pinField.setError("Enter exactly 6 digits");
            return;
        }

        setBusy(true);
        statusText.setText("Pairing securely...");
        new Thread(() -> {
            try {
                String token = ControlClient.pair(host, pin);
                ControlClient.savePair(this, host, token);
                TileService.requestListeningState(
                        this,
                        new ComponentName(this, QuickTileService.class));

                runOnUiThread(() -> {
                    Toast.makeText(this, "PhoneBridge Quick Toggle paired", Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    statusText.setText("Pairing failed: " + ex.getMessage());
                    setBusy(false);
                });
            }
        }, "PhoneBridgePair").start();
    }

    private void setBusy(boolean busy) {
        discoverButton.setEnabled(!busy);
        pairButton.setEnabled(!busy);
        hostField.setEnabled(!busy);
        pinField.setEnabled(!busy);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
