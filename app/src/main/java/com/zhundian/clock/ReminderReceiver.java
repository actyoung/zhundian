package com.zhundian.clock;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) TimeAuthority.clearAfterBoot(context);
        else TimeAuthority.initialize(context);
        if (ReminderScheduler.ACTION_FIRE.equals(intent.getAction())) {
            ReminderScheduler.fire(context, intent.getLongExtra(ReminderScheduler.EXTRA_TARGET, 0));
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(intent.getAction())) {
            ReminderScheduler.schedule(context);
            TimeAuthority.syncIfDue(context, Intent.ACTION_TIME_CHANGED.equals(intent.getAction()));
        }
    }
}
