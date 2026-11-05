package com.toy.tvlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Owns the lock: state reconciliation, the overlay window, and the retry policy.
 *
 * <p>The design rule this class exists to enforce: <b>the window is derived from the state, never
 * the other way round.</b> A dead process therefore reads back as LOCKED on the next start instead
 * of "no window visible, so let them in".
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>{@code SCREEN_OFF} persists LOCKED and <b>keeps the window attached</b>. This is the whole
 *       point of the revival anchor: the firmware force-stops the process on standby, so the lock
 *       must already be on screen when the panel lights up. Detaching on screen off and racing to
 *       re-attach after wake leaves PatchWall visible for seconds.</li>
 *   <li>A new process that finds UNLOCKED persisted re-locks. If the process died while unlocked,
 *       nobody was guarding; the cheap wrong answer is to stay unlocked.</li>
 *   <li>Any guard defect (overlay permission revoked, window cannot be added) goes to
 *       RECOVERY_REQUIRED and is retried, never silently ignored.</li>
 * </ul>
 */
public final class LockCoordinator {

    private static LockCoordinator sInstance;
    /** Once per process: the "a restarted process must not inherit an unlock" check. */
    private static boolean sProcessChecked;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LockOverlay overlay;
    private boolean receiverRegistered;
    private boolean changeInProgress;
    private int attachFailures;

    private final Runnable reconcileTask = new Runnable() {
        @Override
        public void run() {
            reconcile();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Ask the hardware, not the broadcast: a broadcast can arrive late on this firmware.
                onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                DiagLog.i("screen on");
                reconcile();
            }
        }
    };

    private final LockOverlay.Listener overlayListener = new LockOverlay.Listener() {
        @Override
        public void onUnlocked() {
            if (changeInProgress) {
                changeInProgress = false;
                LockStateManager.unlock(app, "pin_changed");
            } else {
                LockStateManager.unlock(app, "pin_entered");
            }
            detach("unlocked");
            clearRecoveryIfHealed();
        }

        @Override
        public void onPinChanged() {
            if (changeInProgress) {
                changeInProgress = false;
                LockStateManager.unlock(app, "pin_changed");
            } else {
                LockStateManager.unlock(app, "pin_created");
            }
            detach("pin flow finished");
            clearRecoveryIfHealed();
        }

        @Override
        public void onCancelRequested() {
            if (!changeInProgress) return;
            changeInProgress = false;
            DiagLog.i("change-pin cancelled by back key");
            // Replace the window with whatever the current state calls for: detached if the owner
            // was unlocked, the normal lock screen if not.
            detach("change cancelled");
            scheduleReconcile(0);
        }
    };

    private LockCoordinator(Context app) {
        this.app = app;
        if (!sProcessChecked) {
            sProcessChecked = true;
            if (LockStateManager.get(app) == LockStateManager.UNLOCKED) {
                // Nobody was guarding between the death of the previous process and now.
                LockStateManager.lock(app, "process_restarted");
                DiagLog.i("process restart found UNLOCKED; re-locked");
            }
        }
    }

    /** Idempotent entry point. Safe from any thread. */
    public static LockCoordinator install(Context context) {
        synchronized (LockCoordinator.class) {
            if (sInstance == null) {
                DiagLog.init(context);
                sInstance = new LockCoordinator(context.getApplicationContext());
                sInstance.registerScreenReceiver();
            }
        }
        sInstance.scheduleReconcile(0);
        return sInstance;
    }

    public static LockCoordinator peek() {
        return sInstance;
    }

    // ---------------------------------------------------------------- events

    /** Boot: entering LOCKED is unconditional here. */
    public static void onBoot(Context context, String trigger) {
        Context app = context.getApplicationContext();
        LockStateManager.lock(app, trigger);
        install(app);
    }

    /** The system (re)bound the notification listener: this process is alive again. */
    public static void onHostConnected(Context context) {
        install(context.getApplicationContext());
        LockCoordinator c = sInstance;
        if (c != null) c.scheduleReconcile(0);
    }

    /** The binding went away: assume the worst until proven otherwise. */
    public static void onHostDisconnected(Context context) {
        LockStateManager.lock(context.getApplicationContext(), "listener_unbound");
    }

    /** Manual lock from the settings screen. */
    public static void lockNow(Context context, String reason) {
        Context app = context.getApplicationContext();
        LockStateManager.lock(app, reason);
        LockCoordinator c = install(app);
        c.scheduleReconcile(0);
    }

    /** Opens the change-PIN flow. Only meaningful while unlocked; the lock screen still asks for
     *  the current PIN first. */
    public static void beginChangePin(Context context) {
        LockCoordinator c = install(context.getApplicationContext());
        c.changeInProgress = true;
        c.scheduleReconcile(0);
    }

    public static boolean cancelChangePin(Context context) {
        LockCoordinator c = sInstance;
        if (c == null || !c.changeInProgress) return false;
        c.changeInProgress = false;
        c.scheduleReconcile(0);
        return true;
    }

    // ---------------------------------------------------------------- internals

    private void registerScreenReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(screenReceiver, f);
            }
            receiverRegistered = true;
            DiagLog.i("screen receiver registered");
        } catch (Throwable t) {
            DiagLog.e("screen receiver registration failed", t);
        }
    }

    private void onScreenOff() {
        LockStateManager.lock(app, "screen_off");
        if (changeInProgress) {
            // Do not keep a settings flow on a screen nobody is looking at.
            changeInProgress = false;
            if (overlay != null) overlay.setMode(LockOverlay.MODE_UNLOCK);
        }
        // Deliberately no detach(): the window must survive into standby so that the first frame
        // after wake is already the lock.
        reconcile();
    }

    private void scheduleReconcile(long delayMs) {
        handler.removeCallbacks(reconcileTask);
        handler.postDelayed(reconcileTask, delayMs);
    }

    private void reconcile() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scheduleReconcile(0);
            return;
        }
        int state = LockStateManager.get(app);

        if (state == LockStateManager.UNLOCKED && !changeInProgress) {
            detach("state unlocked");
            return;
        }

        if (!hasOverlayPermission(app)) {
            // Without the permission there is no lock at all. Say so, loudly, and keep retrying:
            // the owner can grant it from Settings and the guard heals without a restart.
            detach("no overlay permission");
            if (state != LockStateManager.RECOVERY_REQUIRED) {
                LockStateManager.requireRecovery(app, "overlay permission missing");
            }
            scheduleReconcile(10_000L);
            return;
        }

        if (overlay != null && overlay.isAttached()) {
            overlay.refresh();
            clearRecoveryIfHealed();
            return;
        }

        attach(state);
    }

    private void attach(int state) {
        try {
            LockOverlay o = new LockOverlay(app, overlayListener);
            if (changeInProgress) o.setMode(LockOverlay.MODE_CHANGE);
            o.show();
            overlay = o;
            attachFailures = 0;
            DiagLog.i("lock overlay attached (state=" + LockStateManager.label(
                    LockStateManager.get(app)) + ")");
            clearRecoveryIfHealed();
        } catch (Throwable t) {
            attachFailures++;
            DiagLog.e("overlay attach failed (attempt " + attachFailures + ")", t);
            if (attachFailures >= 3 && state != LockStateManager.RECOVERY_REQUIRED) {
                LockStateManager.requireRecovery(app, "overlay attach failed");
            }
            long delay = attachFailures <= 1 ? 2_000L : attachFailures == 2 ? 5_000L : 30_000L;
            scheduleReconcile(delay);
        }
    }

    private void detach(String why) {
        if (overlay == null) return;
        overlay.hide();
        overlay = null;
        attachFailures = 0;
        DiagLog.i("lock overlay detached (" + why + ")");
    }

    /** Leaves RECOVERY_REQUIRED only when the guard is demonstrably working again. */
    private void clearRecoveryIfHealed() {
        if (LockStateManager.get(app) != LockStateManager.RECOVERY_REQUIRED) return;
        if (!hasOverlayPermission(app)) return;
        if (overlay == null || !overlay.isAttached()) return;
        LockStateManager.clearRecovery(app, "guard healed");
    }

    // ---------------------------------------------------------------- checks

    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the system currently lists our notification listener. */
    public static boolean isListenerEnabled(Context context) {
        try {
            String flat = new ComponentName(context, RevivalListener.class).flattenToString();
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.contains(flat);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isInteractive(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    // ---------------------------------------------------------------- report

    /** One-screen summary for the settings UI and for `dumpsys`. No secrets. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        int state = LockStateManager.get(app);
        sb.append("state=").append(LockStateManager.label(state))
                .append(" reason=").append(LockStateManager.reason(app))
                .append(" changed=").append(LockStateManager.changedAt(app))
                .append('\n');
        sb.append("pinSet=").append(PinManager.hasPin(app))
                .append(" fails=").append(PinManager.failedAttempts(app))
                .append(" waitMs=").append(PinManager.millisUntilNextAttempt(app))
                .append('\n');
        sb.append("overlayPermission=").append(hasOverlayPermission(app))
                .append(" listenerEnabled=").append(isListenerEnabled(app))
                .append(" windowAttached=").append(overlay != null && overlay.isAttached())
                .append(" interactive=").append(isInteractive(app))
                .append(" changeInProgress=").append(changeInProgress)
                .append('\n');
        sb.append(DiagLog.dump());
        return sb.toString();
    }
}
