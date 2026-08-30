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
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Date;
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
        String raw = intent == null ? null : intent.getStringExtra(EXTRA_OVERLAY_PAYLOAD);
        if (!Settings.canDrawOverlays(this)) {
            JSONObject payload = parse(raw);
            if (payload != null) {
                String id = payload.optString("id", "");
                boolean shown = NotificationPublisher.show(this, payload.toString());
                if (shown) ReminderStore.remove(this, id);
                ReminderStore.removeActive(this, id);
                ReminderStore.recordStatus(this,
                        shown ? "notification-visible" : "delivery-blocked", id,
                        shown ? "Overlay permission is off; notification fallback shown"
                                : "Overlay permission and notification fallback are unavailable");
            }
            stopSelf();
            return START_NOT_STICKY;
        }
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
        params.width = Math.max(dp(300), getResources().getDisplayMetrics().widthPixels - dp(16));
        params.x = 0;
        params.y = dp(10);
        try {
            windowManager.addView(overlayView, params);
            String id = currentPayload.optString("id", "");
            ReminderStore.remove(this, id);
            ReminderStore.recordStatus(this, "overlay-visible", id,
                    "Bottom reminder is visible");
        } catch (Exception error) {
            if (currentPayload != null) {
                String id = currentPayload.optString("id", "");
                boolean shown = NotificationPublisher.show(this, currentPayload.toString());
                if (shown) ReminderStore.remove(this, id);
                ReminderStore.removeActive(this, id);
                ReminderStore.recordStatus(this,
                        shown ? "notification-visible" : "overlay-window-failed", id,
                        shown ? "Xiaomi blocked the bottom window; notification fallback shown"
                                : error.getClass().getSimpleName());
            }
            overlayView = null;
            stopSelfSafely();
        }
    }

    private View buildCard(JSONObject payload) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams outerMargins = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        outerMargins.setMargins(dp(12), 0, dp(12), 0);
        outer.setLayoutParams(outerMargins);
        outer.setBackground(roundRect(Color.WHITE, 24));
        outer.setElevation(dp(18));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.corex_icon_v249_art_webp);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(Color.rgb(7, 26, 146));
        icon.setBackground(iconBg);
        topRow.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(13), 0, dp(9), 0);
        TextView eyebrow = text("COREX REMINDER", 12, BLUE, true);
        TextView title = text(reminderTitle(payload), 21, BLUE, true);
        TextView body = text(payload.optString("body", "Open Corex to view this reminder."),
                15, Color.rgb(28, 32, 35), false);
        TextView time = text(reminderTime(payload), 13, Color.rgb(99, 104, 109), false);
        title.setPadding(0, dp(2), 0, 0);
        body.setPadding(0, dp(3), 0, 0);
        time.setPadding(0, dp(4), 0, 0);
        body.setMaxLines(3);
        textColumn.addView(eyebrow);
        textColumn.addView(title);
        textColumn.addView(body);
        textColumn.addView(time);
        topRow.addView(textColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        Button dismiss = actionButton("Dismiss", Color.rgb(238, 239, 241), Color.rgb(24, 27, 30));
        Button open = actionButton(openLabel(payload), BLUE, Color.WHITE);
        LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        dismissParams.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        openParams.setMargins(dp(5), 0, 0, 0);
        buttons.addView(dismiss, dismissParams);
        buttons.addView(open, openParams);
        LinearLayout.LayoutParams buttonRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonRow.setMargins(0, dp(12), 0, 0);
        outer.addView(buttons, buttonRow);

        dismiss.setOnClickListener(view -> finishCurrent(false));
        open.setOnClickListener(view -> finishCurrent(true));
        return outer;
    }

    private Button actionButton(String label, int color, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(roundRect(color, 13));
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

    private static String reminderTitle(JSONObject payload) {
        String source = payload.optString("source", "").toLowerCase();
        int index = payload.optInt("priorityIndex", -1);
        if ((source.startsWith("priority") || source.startsWith("focus")) && index >= 0 && index < 3) {
            return "Priority " + (index + 1);
        }
        return payload.optString("title", "Corex reminder").replaceFirst("(?i)\\s+reminder$", "");
    }

    private String reminderTime(JSONObject payload) {
        long at = payload.optLong("at", 0L);
        if (at <= 0L) return "Reminder due";
        String day = DateUtils.isToday(at)
                ? "Today"
                : android.text.format.DateFormat.format("EEE, d MMM", at).toString();
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new Date(at));
        return day + " · " + time;
    }

    private void finishCurrent(boolean openApp) {
        if (currentPayload == null) return;
        String id = currentPayload.optString("id", "");
        String raw = currentPayload.toString();
        ReminderStore.removeActive(this, id);
        if (!openApp) ReminderStore.markDismissed(this, id);
        ReminderStore.recordStatus(this, openApp ? "opened" : "dismissed", id,
                openApp ? "User opened the reminder target" : "User dismissed the reminder");
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
