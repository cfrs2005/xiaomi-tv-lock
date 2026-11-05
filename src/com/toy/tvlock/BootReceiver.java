package com.toy.tvlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Cold boot: put the state back to LOCKED.
 *
 * <p>Deliberately narrow. It only reacts to the three boot-ish actions it is declared for, so a
 * third-party app cannot use it to pull anything to the front. The work runs on a worker thread
 * with {@code goAsync()}: the broadcast timeout counts every settings/state call into
 * system_server, and a slow boot has been observed to blow through it on vendor firmware.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            DiagLog.i("boot receiver ignored action " + action);
            return;
        }

        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final String trigger = "boot:" + action.substring(action.lastIndexOf('.') + 1);
        final long start = SystemClock.elapsedRealtime();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LockCoordinator.onBoot(app, trigger);
                } catch (Throwable t) {
                    DiagLog.e("boot handling failed", t);
                } finally {
                    DiagLog.i(trigger + " handled in "
                            + (SystemClock.elapsedRealtime() - start) + " ms");
                    pending.finish();
                }
            }
        }, "tvlock-boot").start();
    }
}
