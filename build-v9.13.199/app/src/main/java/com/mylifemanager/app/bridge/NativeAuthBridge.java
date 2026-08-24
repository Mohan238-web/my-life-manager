package com.mylifemanager.app.bridge;

import android.webkit.JavascriptInterface;

import com.mylifemanager.app.MyLifeManagerApp;
import com.mylifemanager.app.auth.PasswordHasher;
import com.mylifemanager.app.data.CredentialEntity;

public final class NativeAuthBridge {
    private final MyLifeManagerApp app;

    public NativeAuthBridge(MyLifeManagerApp app) { this.app = app; }

    @JavascriptInterface public void storeCredential(String scope, String derivedHash, String salt, int iterations) {
        if (!safe(scope) || derivedHash == null || derivedHash.length() < 32 || salt == null || salt.length() < 16) return;
        app.executors().disk.execute(() -> app.database().dao().putCredential(
                new CredentialEntity(scope, derivedHash, salt, Math.max(100_000, iterations), System.currentTimeMillis())));
    }

    @JavascriptInterface public void clearCredential(String scope) {
        if (!safe(scope)) return;
        app.executors().disk.execute(() -> app.database().dao().deleteCredential(scope));
    }

    @JavascriptInterface public String verifyDerived(String scope, String candidateHash) {
        if (!safe(scope) || candidateHash == null) return "invalid";
        // This read is intentionally not used by normal UI flow; PBKDF2 remains asynchronous in the trusted asset.
        // Native login screens should call the repository off the UI thread.
        return "async-required";
    }

    static boolean matches(CredentialEntity credential, String candidateHash) {
        return credential != null && PasswordHasher.constantTimeEquals(credential.derivedHash, candidateHash);
    }

    private boolean safe(String value) { return value != null && value.matches("[a-zA-Z0-9._-]{1,64}"); }
}
