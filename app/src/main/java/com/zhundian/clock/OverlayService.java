package com.zhundian.clock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class OverlayService extends Service {
    public static final String ACTION_START = "com.zhundian.clock.START";
    public static final String ACTION_REFRESH = "com.zhundian.clock.REFRESH";
    public static final String ACTION_STOP = "com.zhundian.clock.STOP";

    private static final String CHANNEL = "floating_clock";
    private static final int NOTIFICATION_ID = 1001;
    private static volatile boolean running;
    private static volatile boolean appVisible;
    private static volatile boolean overlayPageVisible;
    private static OverlayService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private final Date clockDate = new Date();
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private SevenSegmentView digits;
    private PowerManager powerManager;
    private boolean safeMode;
    private float downX;
    private float downY;
    private int originX;
    private int originY;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (digits == null) return;
            long now = System.currentTimeMillis();
            clockDate.setTime(now);
            digits.setTime(clockFormat.format(clockDate));
            checkTarget(now);
            handler.postDelayed(this, powerManager.isInteractive() ? 16 : 1000);
        }
    };

    public static boolean isRunning() {
        return running;
    }

    public static void setAppVisible(boolean visible) {
        appVisible = visible;
        if (instance != null) instance.updateVisibility();
    }

    public static void setOverlayPageVisible(boolean visible) {
        overlayPageVisible = visible;
        if (instance != null) instance.updateVisibility();
    }

    private void updateVisibility() {
        if (overlay == null) return;
        boolean show = !appVisible || overlayPageVisible;
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) overlay.post(this::clampPosition);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction()) && !running) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startForegroundNotification();
            showOverlay();
        } else {
            applyConfig();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "悬浮时钟", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("准点悬浮时钟运行状态");
        manager.createNotificationChannel(channel);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("准点悬浮时钟")
                .setContentText("正在显示手机时间")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopIntent)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(11);
        overlay.setPadding(horizontal, dp(9), horizontal, dp(8));

        TextView label = new TextView(this);
        label.setText("准点 · 手机时间");
        label.setTextColor(Color.rgb(178, 215, 169));
        label.setTextSize(9);
        label.setTypeface(null, 1);
        overlay.addView(label);
        digits = new SevenSegmentView(this);
        LinearLayout.LayoutParams digitLayout = new LinearLayout.LayoutParams(-2, -2);
        digitLayout.topMargin = dp(5);
        overlay.addView(digits, digitLayout);
        overlay.setOnTouchListener(this::handleDrag);

        SharedPreferences prefs = getSharedPreferences("clock", MODE_PRIVATE);
        params = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt("overlayX", dp(16));
        params.y = prefs.getInt("overlayY", dp(80));
        applyConfig();
        try {
            windowManager.addView(overlay, params);
            running = true;
            updateVisibility();
            handler.post(ticker);
        } catch (Exception exception) {
            stopSelf();
        }
    }

    private void applyConfig() {
        if (overlay == null) return;
        SharedPreferences prefs = getSharedPreferences("clock", MODE_PRIVATE);
        int size = prefs.getInt("size", 1);
        int opacity = Math.max(0, Math.min(100, prefs.getInt("opacity", 65)));
        boolean noBg = prefs.getBoolean("noBg", false);
        safeMode = prefs.getBoolean("safeMode", false);
        digits.setSize(size);

        GradientDrawable background = new GradientDrawable();
        background.setColor(noBg ? Color.TRANSPARENT : Color.argb(opacity * 255 / 100, 9, 22, 12));
        background.setCornerRadius(dp(5));
        overlay.setBackground(background);
        if (params != null) {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | (safeMode ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
            // Android 12+ only forwards touches through an untrusted overlay at alpha <= 0.8.
            params.alpha = safeMode ? .79f : 1f;
            if (running) {
                windowManager.updateViewLayout(overlay, params);
                overlay.post(this::clampPosition);
            }
        }
    }

    private boolean handleDrag(View view, MotionEvent event) {
        if (safeMode) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                originX = params.x;
                originY = params.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                params.x = originX + Math.round(event.getRawX() - downX);
                params.y = originY + Math.round(event.getRawY() - downY);
                clampPosition();
                return true;
            case MotionEvent.ACTION_UP:
                getSharedPreferences("clock", MODE_PRIVATE).edit()
                        .putInt("overlayX", params.x).putInt("overlayY", params.y).apply();
                return true;
            default:
                return false;
        }
    }

    private void clampPosition() {
        if (overlay == null || params == null || windowManager == null) return;
        Point screen = new Point();
        windowManager.getDefaultDisplay().getSize(screen);
        params.x = Math.max(0, Math.min(params.x, screen.x - overlay.getWidth()));
        params.y = Math.max(0, Math.min(params.y, screen.y - overlay.getHeight()));
        windowManager.updateViewLayout(overlay, params);
    }

    private void checkTarget(long now) {
        SharedPreferences prefs = getSharedPreferences("clock", MODE_PRIVATE);
        long target = prefs.getLong("targetMillis", 0);
        if (target == 0 || now < target || now - target > 2000
                || prefs.getLong("lastAlertedTarget", 0) == target) return;
        prefs.edit().putLong("lastAlertedTarget", target).apply();
        if (prefs.getBoolean("vibrate", true)) {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }
        if (prefs.getBoolean("sound", false)) {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            handler.postDelayed(tone::release, 300);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        handler.removeCallbacks(ticker);
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (IllegalArgumentException ignored) {
            }
        }
        overlay = null;
        digits = null;
        super.onDestroy();
    }
}
