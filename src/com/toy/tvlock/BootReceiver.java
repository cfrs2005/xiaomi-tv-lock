package com.toy.tvlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent s = new Intent(ctx, LockService.class);
        s.setAction("LOCK");
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(s);
            } else {
                ctx.startService(s);
            }
        } catch (Throwable t) {
            // 固件拒绝自启时不崩溃
        }
    }
}
