package com.zhundian.clock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.time.Instant;

public class MainActivity extends Activity {
    private WebView webView;
    private boolean pageReady;
    private boolean pendingStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(11, 16, 14));
        getWindow().setNavigationBarColor(Color.rgb(10, 14, 12));
        getWindow().getDecorView().setSystemUiVisibility(0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 16, 14));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 16, 14));
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new NativeBridge(), "ZhundianAndroid");
        OverlayService.setOverlayPageVisible(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"file".equals(request.getUrl().getScheme())
                        || !request.getUrl().getPath().startsWith("/android_asset/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                syncToPage();
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        webView.loadUrl("file:///android_asset/clock-ui.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.setAppVisible(true);
        if (webView != null) webView.onResume();
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            launchOverlay();
        }
        ReminderScheduler.schedule(this);
        syncToPage();
    }

    @Override
    protected void onPause() {
        OverlayService.setAppVisible(false);
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    private void syncToPage() {
        if (!pageReady) return;
        boolean permission = Settings.canDrawOverlays(this);
        boolean running = OverlayService.isRunning();
        webView.evaluateJavascript("window.nativeOverlayStatus && window.nativeOverlayStatus(" + running + "," + permission + ")", null);
        webView.evaluateJavascript("window.nativeReminderStatus && window.nativeReminderStatus("
                + ReminderScheduler.canScheduleExact(this) + "," + ReminderScheduler.isArmed(this) + ")", null);
    }

    private void requestPermission(boolean startAfterGrant) {
        if (Settings.canDrawOverlays(this)) {
            if (startAfterGrant) launchOverlay();
            else syncToPage();
            return;
        }
        pendingStart = startAfterGrant;
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception exception) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void launchOverlay() {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(OverlayService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            webView.postDelayed(this::syncToPage, 500);
        } catch (Exception exception) {
            webView.evaluateJavascript("window.nativeOverlayError && window.nativeOverlayError('悬浮窗启动失败，请重试')", null);
        }
    }

    private void saveConfig(String json) {
        try {
            JSONObject data = new JSONObject(json);
            SharedPreferences preferences = getSharedPreferences("clock", MODE_PRIVATE);
            boolean vibrate = data.optBoolean("vibrate", true);
            boolean sound = data.optBoolean("sound", false);
            String target = data.optString("target", "");
            long targetMillis = target.isEmpty() ? 0 : Instant.parse(target).toEpochMilli();
            boolean reminderChanged = preferences.getLong("targetMillis", 0) != targetMillis
                    || preferences.getBoolean("vibrate", true) != vibrate
                    || preferences.getBoolean("sound", false) != sound;
            SharedPreferences.Editor editor = preferences.edit();
            editor.putInt("size", data.optInt("size", 1));
            editor.putInt("opacity", data.optInt("opacity", 65));
            editor.putBoolean("noBg", data.optBoolean("noBg", false));
            editor.putBoolean("safeMode", data.optBoolean("safeMode", false));
            editor.putBoolean("vibrate", vibrate);
            editor.putBoolean("sound", sound);
            editor.putLong("targetMillis", targetMillis);
            if (reminderChanged) {
                editor.commit();
                ReminderScheduler.schedule(this);
                syncToPage();
            } else {
                editor.apply();
            }
            if (OverlayService.isRunning() && data.optBoolean("active", false)) {
                Intent refresh = new Intent(this, OverlayService.class);
                refresh.setAction(OverlayService.ACTION_REFRESH);
                startService(refresh);
            }
        } catch (Exception ignored) {
            // Keep the last valid configuration if local web storage is malformed.
        }
    }

    private final class NativeBridge {
        @JavascriptInterface
        public void saveConfig(String json) {
            runOnUiThread(() -> MainActivity.this.saveConfig(json));
        }

        @JavascriptInterface
        public void startOverlay(String json) {
            runOnUiThread(() -> {
                MainActivity.this.saveConfig(json);
                MainActivity.this.requestPermission(true);
            });
        }

        @JavascriptInterface
        public void stopOverlay() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                webView.postDelayed(MainActivity.this::syncToPage, 100);
            });
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> MainActivity.this.requestPermission(false));
        }

        @JavascriptInterface
        public void requestExactAlarmPermission() {
            runOnUiThread(() -> {
                if (ReminderScheduler.canScheduleExact(MainActivity.this)) {
                    syncToPage();
                    return;
                }
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception exception) {
                    webView.evaluateJavascript("window.nativeReminderError && window.nativeReminderError('无法打开精确提醒授权页面')", null);
                }
            });
        }

        @JavascriptInterface
        public void testReminder(String json) {
            runOnUiThread(() -> {
                MainActivity.this.saveConfig(json);
                new Thread(() -> ReminderScheduler.test(getApplicationContext()), "reminder-preview").start();
            });
        }

        @JavascriptInterface
        public void sync() {
            runOnUiThread(MainActivity.this::syncToPage);
        }

        @JavascriptInterface
        public void showOverlayOnPage(boolean visible) {
            runOnUiThread(() -> OverlayService.setOverlayPageVisible(visible));
        }
    }
}
