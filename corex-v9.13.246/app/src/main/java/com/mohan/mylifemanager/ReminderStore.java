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
    private static final String DISMISSED = "dismissed_ids";

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

    static List<String> all(Context context) {
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(PREFIX) && entry.getValue() instanceof String) {
                rows.add((String) entry.getValue());
            }
        }
        return rows;
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
