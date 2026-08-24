package com.mylifemanager.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AndroidRequirementsTest {
    @Test public void packageDeclaresRequiredCapabilitiesAndUsesBrandedLauncherIcon() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PackageManager manager = context.getPackageManager();
        PackageInfo info = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        Set<String> permissions = new HashSet<>(Arrays.asList(info.requestedPermissions == null ? new String[0] : info.requestedPermissions));

        assertTrue(permissions.contains(Manifest.permission.INTERNET));
        assertTrue(permissions.contains(Manifest.permission.ACCESS_NETWORK_STATE));
        assertTrue(permissions.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED));
        assertTrue(permissions.contains(Manifest.permission.SCHEDULE_EXACT_ALARM));
        assertNotNull(context.getSystemService(Context.ALARM_SERVICE));
        assertNotNull(context.getSystemService(Context.NOTIFICATION_SERVICE));
        assertNotNull(context.getSystemService(Context.CONNECTIVITY_SERVICE));
        assertNotEquals(R.drawable.ic_notification, info.applicationInfo.icon);
        assertEquals("9.13.199", info.versionName);
        assertNotNull((AlarmManager) context.getSystemService(Context.ALARM_SERVICE));
        assertNotNull((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE));
        assertNotNull((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE));
    }
}
