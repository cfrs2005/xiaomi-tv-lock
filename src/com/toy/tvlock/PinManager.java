package com.toy.tvlock;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * PIN storage and verification.
 *
 * <p>Only a PBKDF2 verifier is stored ({@link PinCrypto}); the PIN itself is never written,
 * never logged and never shown. Failures and the resulting wait are persisted here as well, so a
 * reboot does not hand an attacker a fresh set of guesses.
 *
 * <p>There is no default PIN. Until the owner sets one, {@link #verify} returns
 * {@link #NOT_SET} and the lock screen asks for a new PIN instead of accepting anything.
 */
public final class PinManager {

    public static final int OK = 0;
    public static final int WRONG = 1;
    public static final int THROTTLED = 2;
    public static final int NOT_SET = 3;
    public static final int ERROR = 4;

    private static final String PREFS = "pin_v1";
    private static final String KEY_SALT = "salt";
    private static final String KEY_HASH = "hash";
    private static final String KEY_ITER = "iter";
    private static final String KEY_ALG = "alg";
    private static final String KEY_FAILS = "fails";
    private static final String KEY_UNTIL = "until";

    private PinManager() {
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        Context dp = app.createDeviceProtectedStorageContext();
        return (dp != null ? dp : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasPin(Context context) {
        try {
            SharedPreferences p = prefs(context);
            return p.getString(KEY_HASH, null) != null && p.getString(KEY_SALT, null) != null;
        } catch (Throwable t) {
            DiagLog.e("pin prefs unreadable", t);
            return false;
        }
    }

    /** Throws IllegalArgumentException for anything that is not exactly four digits. */
    public static void setPin(Context context, String pin) {
        if (!PinCrypto.isValidPin(pin)) throw new IllegalArgumentException("PIN must be 4 digits");
        try {
            String algorithm = PinCrypto.pickAlgorithm();
            byte[] salt = PinCrypto.randomSalt();
            // Calibrated once per PIN so the unlock never inherits a count picked on faster hardware.
            int iterations = PinCrypto.chooseIterations(pin);
            byte[] hash = PinCrypto.derive(pin, salt, iterations, algorithm);
            prefs(context).edit()
                    .putString(KEY_SALT, PinCrypto.toHex(salt))
                    .putString(KEY_HASH, PinCrypto.toHex(hash))
                    .putInt(KEY_ITER, iterations)
                    .putString(KEY_ALG, algorithm)
                    .putInt(KEY_FAILS, 0)
                    .putLong(KEY_UNTIL, 0L)
                    .commit();
            DiagLog.i("pin set (kdf=" + algorithm + ", iter=" + iterations + ")");
        } catch (Throwable t) {
            DiagLog.e("pin set failed", t);
            throw new IllegalStateException("could not store PIN", t);
        }
    }

    /** Milliseconds left before another attempt is accepted; 0 when an attempt is allowed now. */
    public static long millisUntilNextAttempt(Context context) {
        long until = prefs(context).getLong(KEY_UNTIL, 0L);
        long left = until - System.currentTimeMillis();
        return left > 0 ? left : 0L;
    }

    public static int failedAttempts(Context context) {
        return prefs(context).getInt(KEY_FAILS, 0);
    }

    public static int verify(Context context, String pin) {
        try {
            SharedPreferences p = prefs(context);
            String hashHex = p.getString(KEY_HASH, null);
            String saltHex = p.getString(KEY_SALT, null);
            if (hashHex == null || saltHex == null) return NOT_SET;
            if (millisUntilNextAttempt(context) > 0) return THROTTLED;

            byte[] salt = PinCrypto.fromHex(saltHex);
            byte[] expected = PinCrypto.fromHex(hashHex);
            int iterations = p.getInt(KEY_ITER, PinCrypto.ITERATIONS);
            String algorithm = p.getString(KEY_ALG, PinCrypto.ALG_SHA256);
            if (salt == null || expected == null || iterations <= 0) {
                DiagLog.e("pin record malformed", null);
                return ERROR;
            }

            if (PinCrypto.matches(pin, salt, expected, iterations, algorithm)) {
                p.edit().putInt(KEY_FAILS, 0).putLong(KEY_UNTIL, 0L).commit();
                return OK;
            }

            recordFailure(context, p);
            return WRONG;
        } catch (Throwable t) {
            // A broken record must not be read as "correct".
            DiagLog.e("pin verify failed", t);
            return ERROR;
        }
    }

    private static void recordFailure(Context context, SharedPreferences p) {
        int fails = p.getInt(KEY_FAILS, 0) + 1;
        long delay = BackoffPolicy.delayMs(fails);
        long until = delay > 0 ? System.currentTimeMillis() + delay : 0L;
        p.edit().putInt(KEY_FAILS, fails).putLong(KEY_UNTIL, until).commit();
        // Count only. The entered digits and the verifier never reach the log.
        DiagLog.i("pin wrong (" + fails + " consecutive, wait " + (delay / 1000) + "s)");
    }
}
