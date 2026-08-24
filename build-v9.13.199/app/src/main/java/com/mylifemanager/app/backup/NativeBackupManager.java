package com.mylifemanager.app.backup;

import com.mylifemanager.app.BuildConfig;
import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.data.AppDao;
import com.mylifemanager.app.data.KeyValueEntity;
import com.mylifemanager.app.data.ReminderEntity;
import com.mylifemanager.app.reminders.ReminderScheduler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class NativeBackupManager {
    private final MyLifeManagerApp app;

    public NativeBackupManager(MyLifeManagerApp app) { this.app = app; }

    public String exportJson() throws Exception {
        AppDao dao = app.database().dao();
        JSONArray values = new JSONArray();
        for (KeyValueEntity value : dao.allValues()) values.put(new JSONObject()
                .put("key", value.key).put("value", value.value).put("checksum", value.checksum).put("updatedAt", value.updatedAt));
        JSONArray reminders = new JSONArray();
        for (ReminderEntity reminder : dao.allReminders()) reminders.put(reminderToJson(reminder));
        JSONObject payload = new JSONObject().put("keyValues", values).put("reminders", reminders);
        // Credentials and cloud tokens are intentionally never exported.
        return BackupEnvelope.wrap(payload, BuildConfig.VERSION_NAME, System.currentTimeMillis()).toString();
    }

    public int importJson(String json, ReminderScheduler scheduler) throws Exception {
        JSONObject payload = BackupEnvelope.validateAndRead(json);
        JSONArray rawValues = payload.optJSONArray("keyValues");
        JSONArray rawReminders = payload.optJSONArray("reminders");
        List<KeyValueEntity> values = new ArrayList<>();
        List<ReminderEntity> reminders = new ArrayList<>();
        if (rawValues != null) for (int index = 0; index < rawValues.length(); index++) {
            JSONObject row = rawValues.getJSONObject(index);
            values.add(new KeyValueEntity(row.getString("key"), row.getString("value"), row.getString("checksum"), row.optLong("updatedAt"), false));
        }
        if (rawReminders != null) for (int index = 0; index < rawReminders.length(); index++) reminders.add(reminderFromJson(rawReminders.getJSONObject(index)));
        app.database().runInTransaction(() -> {
            AppDao dao = app.database().dao();
            dao.clearValues();
            dao.clearReminders();
            dao.clearSyncQueue();
            for (KeyValueEntity value : values) dao.put(value);
            for (ReminderEntity reminder : reminders) dao.putReminder(reminder);
        });
        for (ReminderEntity reminder : reminders) if (reminder.enabled && reminder.triggerAt > System.currentTimeMillis()) scheduler.schedule(reminder);
        return values.size();
    }

    private JSONObject reminderToJson(ReminderEntity item) throws Exception {
        return new JSONObject().put("id", item.id).put("title", item.title).put("body", item.body).put("source", item.source)
                .put("triggerAt", item.triggerAt).put("sound", item.sound).put("vibration", item.vibration)
                .put("snoozeMinutes", item.snoozeMinutes).put("urgent", item.urgent).put("enabled", item.enabled).put("updatedAt", item.updatedAt);
    }

    private ReminderEntity reminderFromJson(JSONObject item) throws Exception {
        return new ReminderEntity(item.getString("id"), item.optString("title", "My Life Manager"), item.optString("body", "Reminder"),
                item.optString("source", "global"), item.getLong("triggerAt"), item.optBoolean("sound", true),
                item.optBoolean("vibration", true), Math.max(1, item.optInt("snoozeMinutes", 10)),
                item.optBoolean("urgent", false), item.optBoolean("enabled", true), item.optLong("updatedAt", System.currentTimeMillis()));
    }
}
