package com.toy.tvlock;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The access state, persisted in device-protected storage.
 *
 * <p>Two rules that the previous implementation broke and this one must not:
 *
 * <ol>
 *   <li><b>State is not inferred from whether a service or a window is alive.</b> A dead process
 *       must read back as LOCKED, not as "no lock is visible, therefore unlocked".</li>
 *   <li><b>The default is LOCKED.</b> A first run, a corrupted file or a failed read all resolve to
 *       locked; there is no code path where a missing value means "let them in".</li>
 * </ol>
 *
 * <p>Device-protected storage is used because the notification listener is {@code directBootAware}
 * and may be bound before the user is unlocked, when credential-protected preferences are not
 * readable. Nothing secret is stored here — the PIN verifier lives in {@link PinManager}.
 */
public final class LockStateManager {

    public static final int LOCKED = 1;
    public static final int UNLOCKED = 2;
    /** Fail closed: the guard itself is broken (no overlay permission, no host, no PIN). */
    public static final int RECOVERY_REQUIRED = 3;

    private static final String PREFS = "lock_state";
    private static final String KEY_STATE = "state";
    private static final String KEY_REASON = "reason";
    private static final String KEY_AT = "at";

    private LockStateManager() {
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        Context dp = app.createDeviceProtectedStorageContext();
        return (dp != null ? dp : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int get(Context context) {
        try {
            return prefs(context).getInt(KEY_STATE, LOCKED);
        } catch (Throwable t) {
            DiagLog.e("state read failed; assuming LOCKED", t);
            return LOCKED;
        }
    }

    public static boolean isLocked(Context context) {
        return get(context) != UNLOCKED;
    }

    public static String reason(Context context) {
        return prefs(context).getString(KEY_REASON, "");
    }

    public static long changedAt(Context context) {
        return prefs(context).getLong(KEY_AT, 0L);
    }

    public static void lock(Context context, String reason) {
        write(context, LOCKED, reason);
    }

    public static void unlock(Context context, String reason) {
        write(context, UNLOCKED, reason);
    }

    public static void requireRecovery(Context context, String reason) {
        // Never downgrade an existing RECOVERY_REQUIRED to LOCKED, and never upgrade to UNLOCKED.
        if (get(context) != RECOVERY_REQUIRED) {
            write(context, RECOVERY_REQUIRED, reason);
        } else {
            DiagLog.i("recovery still required: " + reason);
        }
    }

    /** Leaving RECOVERY_REQUIRED is only allowed back into LOCKED, never straight to UNLOCKED. */
    public static void clearRecovery(Context context, String reason) {
        if (get(context) == RECOVERY_REQUIRED) write(context, LOCKED, reason);
    }

    private static void write(Context context, int state, String reason) {
        if (context == null) return;
        try {
            // commit(), not apply(): the process may be killed a moment later by the firmware.
            prefs(context).edit()
                    .putInt(KEY_STATE, state)
                    .putString(KEY_REASON, reason == null ? "" : reason)
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .commit();
            DiagLog.i("state -> " + label(state) + " (" + reason + ")");
        } catch (Throwable t) {
            DiagLog.e("state write failed", t);
        }
    }

    public static String label(int state) {
        switch (state) {
            case UNLOCKED:
                return "UNLOCKED";
            case RECOVERY_REQUIRED:
                return "RECOVERY_REQUIRED";
            case LOCKED:
            default:
                return "LOCKED";
        }
    }
}
