package com.mohan.mylifemanager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReminderStore {
    private static final String PREFS = "my_life_manager_native_reminders";
    private static final String PREFIX = "reminder:";
    private static final String ACTIVE_PREFIX = "active:";
    private static final String DISMISSED = "dismissed_ids";
    private static final String LAST_STATUS = "last_delivery_status";

    private ReminderStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void put(Context context, JSONObject payload) {
        String id = payload.optString("id", "");
        if (id.isEmpty()) return;
        prefs(context).edit().putString(PREFIX + id, payload.toString()).apply();
    }

    static void remove(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        prefs(context).edit().remove(PREFIX + id).apply();
    }

    static void putActive(Context context, JSONObject payload) {
        String id = payload.optString("id", "");
        if (id.isEmpty()) return;
        try {
            JSONObject stored = new JSONObject(payload.toString());
            stored.put("_deliveryAttemptAt", System.currentTimeMillis());
            prefs(context).edit().putString(ACTIVE_PREFIX + id, stored.toString()).apply();
        } catch (Exception ignored) {
            prefs(context).edit().putString(ACTIVE_PREFIX + id, payload.toString()).apply();
        }
    }

    static void removeActive(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        prefs(context).edit().remove(ACTIVE_PREFIX + id).apply();
    }

    static List<String> active(Context context) {
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(ACTIVE_PREFIX) && entry.getValue() instanceof String) {
                rows.add((String) entry.getValue());
            }
        }
        return rows;
    }

    static boolean hasFreshActive(Context context, String id, long maxAgeMs) {
        if (id == null || id.isEmpty()) return false;
        String raw = prefs(context).getString(ACTIVE_PREFIX + id, null);
        if (raw == null) return false;
        try {
            long attemptedAt = new JSONObject(raw).optLong("_deliveryAttemptAt", 0L);
            if (attemptedAt > 0L && System.currentTimeMillis() - attemptedAt <= maxAgeMs) return true;
        } catch (Exception ignored) {}
        removeActive(context, id);
        return false;
    }

    static List<String> all(Context context) {
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(PREFIX) && entry.getValue() instanceof String) {
                rows.add((String) entry.getValue());
            }
        }
        return rows;
    }

    static boolean contains(Context context, String id) {
        return id != null && !id.isEmpty() && prefs(context).contains(PREFIX + id);
    }

    static void recordStatus(Context context, String event, String id, String detail) {
        try {
            JSONObject status = new JSONObject();
            status.put("event", event == null ? "unknown" : event);
            status.put("id", id == null ? "" : id);
            status.put("detail", detail == null ? "" : detail);
            status.put("at", System.currentTimeMillis());
            status.put("scheduledCount", all(context).size());
            status.put("activeCount", active(context).size());
            prefs(context).edit().putString(LAST_STATUS, status.toString()).apply();
        } catch (Exception ignored) {}
    }

    static String status(Context context) {
        String raw = prefs(context).getString(LAST_STATUS, "{}");
        try {
            JSONObject status = new JSONObject(raw == null ? "{}" : raw);
            status.put("scheduledCount", all(context).size());
            status.put("activeCount", active(context).size());
            return status.toString();
        } catch (Exception ignored) {
            return "{\"event\":\"unknown\"}";
        }
    }

    static void markDismissed(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        Set<String> ids = new HashSet<>(prefs(context).getStringSet(DISMISSED, new HashSet<>()));
        ids.add(id);
        prefs(context).edit().putStringSet(DISMISSED, ids).apply();
    }

    static String consumeDismissed(Context context) {
        Set<String> ids = new HashSet<>(prefs(context).getStringSet(DISMISSED, new HashSet<>()));
        prefs(context).edit().remove(DISMISSED).apply();
        JSONArray result = new JSONArray();
        for (String id : ids) result.put(id);
        return result.toString();
    }
}
