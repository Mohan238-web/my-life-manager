package com.mylifemanager.app;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ResponsiveStartupTest {
    @Test public void localAppCreatesAVisibleWebViewOnCurrentScreen() {
        AtomicReference<WebView> webViewReference = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView webView = findWebView(activity.findViewById(android.R.id.content));
                assertNotNull(webView);
                assertTrue(webView.getSettings().getJavaScriptEnabled());
                webViewReference.set(webView);
            });

            WebView webView = webViewReference.get();
            assertNotNull(webView);
            String result = waitForHealthyLayout(scenario, webView);
            assertEquals("true", result);
        }
    }

    private String waitForHealthyLayout(ActivityScenario<MainActivity> scenario, WebView webView) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
        String result = "false";
        while (System.currentTimeMillis() < deadline) {
            result = evaluate(scenario, webView,
                    "(()=>{const d=document.documentElement,b=document.body,f=[...document.querySelectorAll('iframe.frame')];" +
                    "return d.classList.contains('mlm-startup-ready')&&innerWidth>0&&innerHeight>0&&" +
                    "Math.max(d.scrollWidth,b.scrollWidth)<=innerWidth+2&&f.length===5&&" +
                    "f.some(x=>x.classList.contains('active')&&x.offsetWidth>0&&x.offsetHeight>0)})()");
            if ("true".equals(result)) return result;
            try { Thread.sleep(250L); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
        }
        return result;
    }

    private String evaluate(ActivityScenario<MainActivity> scenario, WebView webView, String javascript) {
        AtomicReference<String> result = new AtomicReference<>("false");
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> webView.evaluateJavascript(javascript, value -> {
            result.set(value);
            latch.countDown();
        }));
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return "false"; }
        return result.get();
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView result = findWebView(group.getChildAt(index));
                if (result != null) return result;
            }
        }
        return null;
    }
}
