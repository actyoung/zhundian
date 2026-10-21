package com.zhundian.clock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

final class ReminderScheduler {
    static final String ACTION_FIRE = "com.zhundian.clock.FIRE_REMINDER";
    static final String EXTRA_TARGET = "targetMillis";
    private static final String PREFS = "clock";
    private static final String SCHEDULED_TARGET = "scheduledTargetMillis";

    private ReminderScheduler() {}

    static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    static boolean isArmed(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long target = prefs.getLong("targetMillis", 0);
        return canScheduleExact(context) && target > System.currentTimeMillis()
                && prefs.getLong(SCHEDULED_TARGET, 0) == target;
    }

    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long target = prefs.getLong("targetMillis", 0);
        long now = System.currentTimeMillis();
        PendingIntent fire = PendingIntent.getBroadcast(context, 0,
                new Intent(context, ReminderReceiver.class).setAction(ACTION_FIRE)
                        .putExtra(EXTRA_TARGET, target),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(fire);
        prefs.edit().putLong(SCHEDULED_TARGET, 0).apply();
        if (prefs.getLong("lastAlertedTarget", 0) == target
                || (!prefs.getBoolean("vibrate", true) && !prefs.getBoolean("sound", false))
                || !canScheduleExact(context)) return;
        if (target <= now) {
            if (target > now - 2000) fire(context, target);
            return;
        }

        PendingIntent show = PendingIntent.getActivity(context, 1,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(target, show), fire);
            prefs.edit().putLong(SCHEDULED_TARGET, target).apply();
        } catch (SecurityException ignored) {
            // The user can revoke exact-alarm access between the permission check and scheduling.
        }
    }

    static void fire(Context context, long target) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (target <= 0 || target != prefs.getLong("targetMillis", 0)
                || target == prefs.getLong("lastAlertedTarget", 0)) return;
        if (System.currentTimeMillis() < target) {
            schedule(context);
            return;
        }
        boolean vibrate = prefs.getBoolean("vibrate", true);
        boolean sound = prefs.getBoolean("sound", false);
        prefs.edit().putLong("lastAlertedTarget", target).putLong(SCHEDULED_TARGET, 0).commit();
        play(context, vibrate, sound);
    }

    static void test(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        play(context, prefs.getBoolean("vibrate", true), prefs.getBoolean("sound", false));
    }

    private static void play(Context context, boolean vibrate, boolean sound) {
        if (vibrate) {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = context.getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 180, 100, 180}, -1),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            }
        }
        if (sound) {
            ToneGenerator tone = null;
            try {
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 350);
                SystemClock.sleep(400);
            } catch (RuntimeException ignored) {
                // A device without an available alarm audio stream can still vibrate.
            } finally {
                if (tone != null) tone.release();
            }
        }
    }
}
