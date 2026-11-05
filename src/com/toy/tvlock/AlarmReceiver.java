package com.toy.tvlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent s = new Intent(ctx, LockService.class);
        s.setAction("TICK");
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(s);
            } else {
                ctx.startService(s);
            }
        } catch (Throwable t) {
            // 系统拒绝拉起时忽略
        }
    }
}
