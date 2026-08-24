package com.mylifemanager.app.auth;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PasswordHasherTest {
    @Test public void samePasswordAndSaltMatchWithoutStoringPlainText() {
        String salt = PasswordHasher.newSalt();
        String first = PasswordHasher.derive("correct horse".toCharArray(), salt, 100_000);
        String second = PasswordHasher.derive("correct horse".toCharArray(), salt, 100_000);
        String wrong = PasswordHasher.derive("wrong horse".toCharArray(), salt, 100_000);
        assertTrue(PasswordHasher.constantTimeEquals(first, second));
        assertFalse(PasswordHasher.constantTimeEquals(first, wrong));
        assertFalse(first.contains("correct horse"));
    }
}
