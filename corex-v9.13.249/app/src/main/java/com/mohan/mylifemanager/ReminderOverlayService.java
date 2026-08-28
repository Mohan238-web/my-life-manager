package com.mohan.mylifemanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

public final class ReminderOverlayService extends Service {
    private static final String ACTION_SHOW = "com.mohan.mylifemanager.OVERLAY_SHOW";
    private static final String EXTRA_OVERLAY_PAYLOAD = "overlay_payload";
    private static final String SERVICE_CHANNEL = "corex_overlay_service_v1";
    private static final int SERVICE_NOTIFICATION_ID = 913247;
    private static final int BLUE = Color.rgb(45, 126, 245);

    private WindowManager windowManager;
    private View overlayView;
    private JSONObject currentPayload;

    static boolean show(Context context, String payload) {
        try {
            Intent intent = new Intent(context, ReminderOverlayService.class)
                    .setAction(ACTION_SHOW)
                    .putExtra(EXTRA_OVERLAY_PAYLOAD, payload);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String raw = intent == null ? null : intent.getStringExtra(EXTRA_OVERLAY_PAYLOAD);
        if (raw != null && !raw.isEmpty()) {
            try { ReminderStore.putActive(this, new JSONObject(raw)); } catch (Exception ignored) {}
        }
        if (overlayView == null) showNext(raw);
        return START_STICKY;
    }

    private Notification buildServiceNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null
                && manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(
                    SERVICE_CHANNEL, "Corex active reminder", NotificationManager.IMPORTANCE_MIN);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent launchIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent launch = PendingIntent.getActivity(this, SERVICE_NOTIFICATION_ID, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_fingerprint)
                .setContentTitle("Corex reminder waiting")
                .setContentText("Choose Open or Dismiss on the bottom reminder.")
                .setContentIntent(launch)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void showNext(String preferredRaw) {
        JSONObject next = parse(preferredRaw);
        if (next == null) {
            List<String> active = ReminderStore.active(this);
            for (String raw : active) {
                next = parse(raw);
                if (next != null) break;
            }
        }
        if (next == null) {
            stopSelfSafely();
            return;
        }
        currentPayload = next;
        overlayView = buildCard(next);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.width = Math.max(dp(280), getResources().getDisplayMetrics().widthPixels - dp(36));
        params.x = 0;
        params.y = dp(18);
        try {
            windowManager.addView(overlayView, params);
        } catch (Exception error) {
            if (currentPayload != null) {
                NotificationPublisher.show(this, currentPayload.toString());
                ReminderStore.removeActive(this, currentPayload.optString("id", ""));
            }
            overlayView = null;
            stopSelfSafely();
        }
    }

    private View buildCard(JSONObject payload) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams outerMargins = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        outerMargins.setMargins(dp(18), 0, dp(18), 0);
        outer.setLayoutParams(outerMargins);
        outer.setBackground(roundRect(Color.rgb(34, 34, 36), 30));
        outer.setElevation(dp(18));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.corex_icon_v249_art_webp);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(Color.rgb(7, 26, 146));
        icon.setBackground(iconBg);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(14), 0, 0, 0);
        TextView title = text(payload.optString("title", "Corex reminder"), 20, Color.WHITE, true);
        TextView body = text(payload.optString("body", "Open Corex to view this reminder."),
                16, Color.rgb(220, 220, 224), false);
        body.setPadding(0, dp(5), 0, 0);
        body.setMaxLines(3);
        textColumn.addView(title);
        textColumn.addView(body);
        titleRow.addView(textColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        outer.addView(titleRow);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(18), 0, 0);
        Button dismiss = actionButton("Dismiss", Color.rgb(77, 77, 80));
        Button open = actionButton(openLabel(payload), BLUE);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(62), 1f);
        left.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(62), 1f);
        right.setMargins(dp(8), 0, 0, 0);
        buttons.addView(dismiss, left);
        buttons.addView(open, right);
        outer.addView(buttons);

        dismiss.setOnClickListener(view -> finishCurrent(false));
        open.setOnClickListener(view -> finishCurrent(true));
        return outer;
    }

    private Button actionButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(roundRect(color, 20));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private static String openLabel(JSONObject payload) {
        String source = payload.optString("source", "").toLowerCase();
        if (source.startsWith("priority") || source.startsWith("focus")) return "Open Priority";
        if (source.startsWith("notes")) return "Open Note";
        if (source.startsWith("todo")) return "Open To-Do";
        if (source.startsWith("expense") || source.startsWith("bill")) return "Open Expense";
        if (source.startsWith("trading")) return "Open Trade";
        if (source.startsWith("mileage") || source.startsWith("service")) return "Open Mileage";
        if (source.startsWith("habit")) return "Open Habit";
        return "Open Corex";
    }

    private void finishCurrent(boolean openApp) {
        if (currentPayload == null) return;
        String id = currentPayload.optString("id", "");
        String raw = currentPayload.toString();
        ReminderStore.removeActive(this, id);
        if (!openApp) ReminderStore.markDismissed(this, id);
        removeOverlay();
        currentPayload = null;
        if (openApp) {
            Intent launch = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(ReminderScheduler.EXTRA_PAYLOAD, raw);
            startActivity(launch);
        }
        showNext(null);
    }

    private void removeOverlay() {
        if (overlayView == null || windowManager == null) return;
        try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        overlayView = null;
    }

    private void stopSelfSafely() {
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception ignored) { return null; }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
