package com.mohan.mylifemanager;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ReminderAlertActivity extends Activity {
    private int notificationId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        configureWindow();
        showReminder(getIntent());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM;
        params.dimAmount = 0.48f;
        window.setAttributes(params);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showReminder(Intent intent) {
        notificationId = intent.getIntExtra("requestCode", 1);
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String source = intent.getStringExtra("source");
        String openLabel = "todo".equals(source) ? "Open task" : "notes".equals(source) ? "Open note" : "Open app";

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(22));
        card.setBackground(rounded(Color.rgb(35, 35, 35), 30));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(com.mohan.mylifemanager.R.mipmap.ic_launcher);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView app = text("My Life Manager", 20, Color.WHITE, true);
        LinearLayout.LayoutParams appParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        appParams.setMarginStart(dp(12));
        heading.addView(app, appParams);
        card.addView(heading);

        TextView titleView = text(title == null || title.isEmpty() ? "Reminder" : title, 25, Color.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(20);
        card.addView(titleView, titleParams);
        TextView bodyView = text(body == null ? "" : body, 18, Color.rgb(220, 220, 220), false);
        bodyView.setMaxLines(4);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(8);
        card.addView(bodyView, bodyParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(24);

        Button dismiss = new Button(this);
        dismiss.setAllCaps(false);
        dismiss.setText("Dismiss");
        dismiss.setTextSize(18);
        dismiss.setTextColor(Color.WHITE);
        dismiss.setBackground(rounded(Color.rgb(78, 78, 78), 18));
        dismiss.setOnClickListener(v -> closeReminder());

        Button open = new Button(this);
        open.setAllCaps(false);
        open.setText(openLabel);
        open.setTextSize(18);
        open.setTextColor(Color.WHITE);
        open.setBackground(rounded(Color.rgb(47, 128, 237), 18));
        open.setOnClickListener(v -> openReminder(intent));

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(64), 1);
        buttonParams.setMarginEnd(dp(8));
        actions.addView(dismiss, buttonParams);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(64), 1);
        openParams.setMarginStart(dp(8));
        actions.addView(open, openParams);
        card.addView(actions, actionsParams);

        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(16), dp(8), dp(16), dp(20));
        root.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void closeReminder() {
        getSystemService(NotificationManager.class).cancel(notificationId);
        finishAndRemoveTask();
    }

    private void openReminder(Intent original) {
        getSystemService(NotificationManager.class).cancel(notificationId);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("reminderId", original.getStringExtra("reminderId"));
        open.putExtra("source", original.getStringExtra("source"));
        startActivity(open);
        finishAndRemoveTask();
    }

    @Override public void onBackPressed() {
        // A reminder requiring action remains until Dismiss or Open is selected.
    }
}
