package com.mylifemanager.app.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureTokenStore {
    private static final String KEY_ALIAS = "mlm_cloud_token_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private final SharedPreferences preferences;

    public SecureTokenStore(Context context) {
        preferences = context.getSharedPreferences("secure_tokens", Context.MODE_PRIVATE);
    }

    public void put(String token) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String value = android.util.Base64.encodeToString(cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
            String iv = android.util.Base64.encodeToString(cipher.getIV(), android.util.Base64.NO_WRAP);
            preferences.edit().putString("token", value).putString("iv", iv).apply();
        } catch (Exception error) {
            throw new IllegalStateException("Token encryption failed", error);
        }
    }

    public String get() {
        String value = preferences.getString("token", null), iv = preferences.getString("iv", null);
        if (value == null || iv == null) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, android.util.Base64.decode(iv, android.util.Base64.NO_WRAP)));
            return new String(cipher.doFinal(android.util.Base64.decode(value, android.util.Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            preferences.edit().clear().apply();
            return "";
        }
    }

    public void clear() { preferences.edit().clear().apply(); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
