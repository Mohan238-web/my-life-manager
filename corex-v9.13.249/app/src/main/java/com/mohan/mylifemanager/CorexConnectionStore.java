package com.mohan.mylifemanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class CorexConnectionStore {
    private static final String PREFS = "corex_pc_connection_v1";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String SERVER_ID = "server_id";
    private static final String SERVER_NAME = "server_name";
    private static final String PEER_KEY = "peer_key";
    private static final String REVISION = "revision";
    private static final String SNAPSHOT = "snapshot";
    private static final String PREVIOUS_SNAPSHOT = "previous_snapshot";
    private static final String PENDING_SNAPSHOT = "pending_snapshot";
    private static final String LAST_SYNC = "last_sync";
    private static final String LAST_ERROR = "last_error";
    private static final int DEFAULT_PORT = 47625;
    private static final int MAX_RESPONSE = 16 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CorexConnectionStore() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String deviceId(Context context) {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null || id.trim().isEmpty() ? "corex-android" : id.trim();
    }

    static JSONObject state(Context context) {
        SharedPreferences p = prefs(context);
        JSONObject out = new JSONObject();
        try {
            boolean paired = !p.getString(PEER_KEY, "").isEmpty();
            out.put("paired", paired);
            out.put("host", p.getString(HOST, ""));
            out.put("port", p.getInt(PORT, DEFAULT_PORT));
            out.put("serverId", p.getString(SERVER_ID, ""));
            out.put("serverName", p.getString(SERVER_NAME, ""));
            out.put("revision", p.getLong(REVISION, 0));
            out.put("lastSync", p.getLong(LAST_SYNC, 0));
            out.put("lastError", p.getString(LAST_ERROR, ""));
            out.put("transport", transportForHost(p.getString(HOST, "")));
        } catch (Exception ignored) {}
        return out;
    }

    static JSONObject pair(Context context, String rawHost, int rawPort, String rawCode,
                           String snapshot) throws Exception {
        String host = normalizeHost(rawHost);
        int port = rawPort > 0 && rawPort <= 65535 ? rawPort : DEFAULT_PORT;
        String code = rawCode == null ? "" : rawCode.replaceAll("\\D", "");
        if (host.isEmpty()) throw new IllegalArgumentException("Enter the PC address shown by Corex Companion.");
        if (code.length() != 6) throw new IllegalArgumentException("Enter the six-digit pairing PIN.");

        JSONObject info = request("GET", host, port, "/api/v1/info", null, null);
        String serverId = info.optString("serverId", "").trim();
        String serverName = info.optString("serverName", "Corex PC").trim();
        if (serverId.isEmpty()) throw new IllegalStateException("This is not a Corex Companion address.");

        String id = deviceId(context);
        byte[] pairingKey = pbkdf2(code.toCharArray(), ("corex-pair:" + serverId).getBytes(StandardCharsets.UTF_8));
        String clientNonce = base64(randomBytes(18));
        String proofText = "corex-pair|" + id + "|" + clientNonce + "|" + serverId;
        String proof = base64(hmac(pairingKey, proofText.getBytes(StandardCharsets.UTF_8)));

        JSONObject payload = new JSONObject();
        payload.put("deviceId", id);
        payload.put("deviceName", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        payload.put("clientNonce", clientNonce);
        payload.put("proof", proof);
        JSONObject response = request("POST", host, port, "/api/v1/pair", payload.toString(), null);
        byte[] iv = unbase64(response.optString("iv", ""));
        byte[] cipher = unbase64(response.optString("cipher", ""));
        byte[] plain = decrypt(pairingKey, iv, cipher,
                (id + "|" + serverId).getBytes(StandardCharsets.UTF_8));
        JSONObject secret = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        byte[] peerKey = unbase64(secret.optString("peerKey", ""));
        if (peerKey.length != 32) throw new IllegalStateException("The PC pairing response was invalid.");

        SharedPreferences.Editor edit = prefs(context).edit()
                .putString(HOST, host)
                .putInt(PORT, port)
                .putString(SERVER_ID, serverId)
                .putString(SERVER_NAME, serverName)
                .putString(PEER_KEY, base64(peerKey))
                .putLong(REVISION, 0)
                .putString(LAST_ERROR, "");
        if (snapshot != null && !snapshot.trim().isEmpty()) edit.putString(SNAPSHOT, snapshot);
        edit.apply();
        JSONObject result = exchange(context, snapshot, true);
        result.put("paired", true);
        result.put("serverName", serverName);
        result.put("host", host);
        result.put("port", port);
        return result;
    }

    static JSONObject exchange(Context context, String suppliedSnapshot, boolean forcePhoneSnapshot)
            throws Exception {
        SharedPreferences p = prefs(context);
        String host = p.getString(HOST, "");
        int port = p.getInt(PORT, DEFAULT_PORT);
        byte[] key = unbase64(p.getString(PEER_KEY, ""));
        if (host.isEmpty() || key.length != 32) throw new IllegalStateException("Pair Corex with the PC first.");

        String current = suppliedSnapshot;
        if (current == null || current.trim().isEmpty()) current = p.getString(SNAPSHOT, "{}");
        if (!looksLikeObject(current)) current = "{}";
        long revision = p.getLong(REVISION, 0);
        long now = System.currentTimeMillis();
        String requestId = base64(randomBytes(18));
        String path = "/api/v1/sync/exchange";
        String aadText = "POST|" + path + "|" + now + "|" + requestId;

        JSONObject plain = new JSONObject();
        plain.put("revision", revision);
        plain.put("snapshot", current);
        plain.put("snapshotHash", sha256Hex(current));
        plain.put("updatedAt", now);
        plain.put("forcePhoneSnapshot", forcePhoneSnapshot);
        byte[] iv = randomBytes(12);
        byte[] encrypted = encrypt(key, iv, plain.toString().getBytes(StandardCharsets.UTF_8),
                aadText.getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject();
        envelope.put("iv", base64(iv));
        envelope.put("cipher", base64(encrypted));

        JSONObject headers = new JSONObject();
        headers.put("X-Corex-Device", deviceId(context));
        headers.put("X-Corex-Time", Long.toString(now));
        headers.put("X-Corex-Request", requestId);
        JSONObject responseEnvelope = request("POST", host, port, path, envelope.toString(), headers);
        byte[] responseIv = unbase64(responseEnvelope.optString("iv", ""));
        byte[] responseCipher = unbase64(responseEnvelope.optString("cipher", ""));
        String responseAad = "RESPONSE|" + path + "|" + now + "|" + requestId;
        JSONObject response = new JSONObject(new String(decrypt(key, responseIv, responseCipher,
                responseAad.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        long newRevision = response.optLong("revision", revision);
        String serverSnapshot = response.optString("snapshot", current);
        if (!looksLikeObject(serverSnapshot)) throw new IllegalStateException("The PC returned invalid Corex data.");
        boolean changed = !sha256Hex(current).equals(sha256Hex(serverSnapshot));
        SharedPreferences.Editor edit = p.edit()
                .putLong(REVISION, newRevision)
                .putLong(LAST_SYNC, System.currentTimeMillis())
                .putString(LAST_ERROR, "")
                .putString(SNAPSHOT, serverSnapshot);
        if (changed) {
            edit.putString(PREVIOUS_SNAPSHOT, current);
            edit.putString(PENDING_SNAPSHOT, serverSnapshot);
        }
        edit.apply();
        JSONObject result = state(context);
        result.put("changed", changed);
        result.put("snapshot", changed ? serverSnapshot : "");
        return result;
    }

    static void queueSnapshot(Context context, String snapshot) {
        if (!looksLikeObject(snapshot)) return;
        prefs(context).edit().putString(SNAPSHOT, snapshot).apply();
    }

    static String consumePendingSnapshot(Context context) {
        SharedPreferences p = prefs(context);
        String value = p.getString(PENDING_SNAPSHOT, "");
        if (!value.isEmpty()) p.edit().remove(PENDING_SNAPSHOT).apply();
        return value;
    }

    static String storedSnapshot(Context context) {
        return prefs(context).getString(SNAPSHOT, "{}");
    }

    static boolean isPaired(Context context) {
        return !prefs(context).getString(PEER_KEY, "").isEmpty();
    }

    static void recordError(Context context, Throwable error) {
        String message = error == null ? "Connection failed." : error.getMessage();
        if (message == null || message.trim().isEmpty()) message = "Connection failed.";
        prefs(context).edit().putString(LAST_ERROR, message).apply();
    }

    static void disconnect(Context context) {
        String snapshot = prefs(context).getString(SNAPSHOT, "{}");
        prefs(context).edit().clear().putString(SNAPSHOT, snapshot).apply();
    }

    private static JSONObject request(String method, String host, int port, String path,
                                      String body, JSONObject headers) throws Exception {
        URL url = new URL("http", host, port, path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                connection.setRequestProperty(name, headers.optString(name, ""));
            }
        }
        if (body != null) {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(data); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String text = readLimited(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            try {
                String message = new JSONObject(text).optString("error", "PC connection failed (" + status + ").");
                throw new IllegalStateException(message);
            } catch (org.json.JSONException ignored) {
                throw new IllegalStateException("PC connection failed (" + status + ").");
            }
        }
        return new JSONObject(text);
    }

    private static String readLimited(InputStream stream) throws Exception {
        if (stream == null) return "{}";
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[8192];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, read);
                if (out.length() > MAX_RESPONSE) throw new IllegalStateException("PC response is too large.");
            }
        }
        return out.toString();
    }

    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim();
        host = host.replaceFirst("(?i)^https?://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        if (host.startsWith("[") && host.contains("]")) host = host.substring(1, host.indexOf(']'));
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        if (!host.matches("[A-Za-z0-9._-]{1,253}")) return "";
        return host;
    }

    private static String transportForHost(String host) {
        if (host == null || host.isEmpty()) return "offline";
        if (host.startsWith("192.168.42.") || host.startsWith("192.168.137.")) return "USB tethering";
        return "Wi-Fi / hotspot";
    }

    private static boolean looksLikeObject(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) return false;
        try { new JSONObject(text); return true; } catch (Exception ignored) { return false; }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 120000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static byte[] encrypt(byte[] key, byte[] iv, byte[] plain, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(plain);
    }

    private static byte[] decrypt(byte[] key, byte[] iv, byte[] encrypted, byte[] aad) throws Exception {
        if (iv.length != 12 || encrypted.length < 16) throw new IllegalStateException("Encrypted response is invalid.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(encrypted);
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte item : digest) out.append(String.format(Locale.US, "%02x", item & 0xff));
        return out.toString();
    }

    private static byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        RANDOM.nextBytes(value);
        return value;
    }

    private static String base64(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] unbase64(String value) {
        try { return Base64.decode(value == null ? "" : value, Base64.DEFAULT); }
        catch (Exception ignored) { return new byte[0]; }
    }
}
