package com.mylifemanager.app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    public static final int DEFAULT_ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String newSalt() {
        byte[] salt = new byte[24];
        RANDOM.nextBytes(salt);
        return hex(salt);
    }

    public static String derive(char[] password, String encodedSalt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, unhex(encodedSalt), iterations, KEY_BITS);
        try {
            byte[] result;
            try { result = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
            catch (java.security.NoSuchAlgorithmException unavailableBeforeApi26) { result = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded(); }
            return hex(result);
        } catch (Exception error) {
            throw new IllegalStateException("Secure password derivation failed", error);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) result.append(String.format("%02x", item));
        return result.toString();
    }

    private static byte[] unhex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("Invalid salt");
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        return result;
    }
}
