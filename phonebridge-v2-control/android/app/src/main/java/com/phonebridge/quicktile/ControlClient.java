package com.phonebridge.quicktile;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class ControlClient {
    static final int DISCOVERY_PORT = 8990;
    static final int CONTROL_PORT = 8991;
    static final String PREFS = "phonebridge_control";
    static final String KEY_HOST = "host";
    static final String KEY_TOKEN = "token";

    static final class Status {
        final boolean connected;
        final boolean running;
        Status(boolean connected, boolean running) {
            this.connected = connected;
            this.running = running;
        }
    }

    static final class Discovery {
        final String host;
        final String name;
        final boolean alreadyPaired;
        Discovery(String host, String name, boolean alreadyPaired) {
            this.host = host;
            this.name = name;
            this.alreadyPaired = alreadyPaired;
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String host(Context context) {
        return prefs(context).getString(KEY_HOST, "");
    }

    static String token(Context context) {
        return prefs(context).getString(KEY_TOKEN, "");
    }

    static boolean isPaired(Context context) {
        return !host(context).isEmpty() && !token(context).isEmpty();
    }

    static void savePair(Context context, String host, String token) {
        prefs(context).edit().putString(KEY_HOST, host).putString(KEY_TOKEN, token).apply();
    }

    static void clearPair(Context context) {
        prefs(context).edit().clear().apply();
    }

    static Discovery discover() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1800);
            byte[] message = "PB_DISCOVER_V2".getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                    message, message.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            socket.send(packet);

            byte[] buffer = new byte[512];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8).trim();
            String[] parts = text.split("\\|");
            if (parts.length < 3 || !"PB_HERE_V2".equals(parts[0])) {
                throw new IllegalStateException("Unexpected discovery reply");
            }
            String name = java.net.URLDecoder.decode(parts[1], "UTF-8");
            return new Discovery(response.getAddress().getHostAddress(), name, "paired".equals(parts[2]));
        }
    }

    static String pair(String host, String pin) throws Exception {
        String reply = request(host, "PAIR|" + pin);
        String[] parts = reply.split("\\|");
        if (parts.length >= 2 && "OK".equals(parts[0])) return parts[1];
        throw new IllegalStateException("Pairing PIN was rejected");
    }

    static Status status(Context context) throws Exception {
        String host = host(context);
        String token = token(context);
        String reply = request(host, "STATUS|" + token);
        String[] parts = reply.split("\\|");
        if (parts.length >= 3 && "STATUS".equals(parts[0])) {
            return new Status("1".equals(parts[1]), "1".equals(parts[2]));
        }
        if (reply.startsWith("ERR|AUTH")) throw new SecurityException("Pairing expired");
        throw new IllegalStateException("Unexpected status reply");
    }

    static void turnOn(Context context) throws Exception {
        command(context, "ON");
    }

    static void turnOff(Context context) throws Exception {
        command(context, "OFF");
    }

    private static void command(Context context, String cmd) throws Exception {
        String reply = request(host(context), cmd + "|" + token(context));
        if (!reply.startsWith("OK|")) {
            if (reply.startsWith("ERR|AUTH")) throw new SecurityException("Pairing expired");
            throw new IllegalStateException("PC rejected command");
        }
    }

    private static String request(String host, String line) throws Exception {
        if (host == null || host.trim().isEmpty()) throw new IllegalStateException("PC address is missing");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host.trim(), CONTROL_PORT), 2500);
            socket.setSoTimeout(3500);

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.write(line);
            writer.write("\n");
            writer.flush();

            String reply = reader.readLine();
            if (reply == null) throw new IllegalStateException("PC closed the control connection");
            return reply.trim();
        }
    }

    private ControlClient() {}
}
