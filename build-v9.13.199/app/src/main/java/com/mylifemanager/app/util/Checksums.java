package com.mylifemanager.app.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Checksums {
    private Checksums() {}

    public static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
