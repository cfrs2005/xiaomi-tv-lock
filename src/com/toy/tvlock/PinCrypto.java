package com.toy.tvlock;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PIN verifier primitives.
 *
 * <p>Deliberately free of Android imports so the derivation and comparison logic can be exercised
 * on a desktop JVM (see {@code test/RunTests.java}). Everything Android-specific (where the salt
 * lives, how failures are stored) belongs in {@link PinManager}.
 */
public final class PinCrypto {

    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;

    /**
     * PBKDF2-HMAC-SHA256 iteration count used when no calibration has run yet.
     *
     * <p>The stored verifier carries its own count, so this value only seeds new PINs and keeps old
     * ones verifiable after a policy change.
     */
    public static final int ITERATIONS = 100_000;

    /** Bounds for {@link #chooseIterations}: below this the KDF is theatre, above it the TV lags. */
    public static final int ITERATIONS_MIN = 60_000;
    public static final int ITERATIONS_MAX = 250_000;
    /** Unlock budget. A 4-digit PIN on a remote is typed slowly; the KDF must not add to it. */
    public static final long TARGET_UNLOCK_MS = 220L;

    public static final String ALG_SHA256 = "PBKDF2WithHmacSHA256";
    public static final String ALG_SHA1 = "PBKDF2WithHmacSHA1";

    private PinCrypto() {
    }

    /**
     * Picks an iteration count that costs about {@link #TARGET_UNLOCK_MS} on the machine running it.
     *
     * <p>The target device is a 32-bit ARM MT9653, roughly an order of magnitude slower than a
     * laptop, so a hard-coded count is either too weak or visibly laggy depending on where it was
     * chosen. A short probe at 10k iterations estimates the rate, and the result is clamped to a
     * range that is worth something either way. Called once, when a PIN is set; the chosen count is
     * stored with the verifier so verification always uses the same one.
     */
    public static int chooseIterations(String pin) {
        final int probe = 10_000;
        byte[] salt = new byte[SALT_BYTES];
        String algorithm = pickAlgorithm();
        long elapsedMs;
        try {
            long t0 = System.nanoTime();
            derive(pin, salt, probe, algorithm);
            elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
        } catch (Exception e) {
            return ITERATIONS;
        }
        long scaled = probe * TARGET_UNLOCK_MS / elapsedMs;
        if (scaled < ITERATIONS_MIN) return ITERATIONS_MIN;
        if (scaled > ITERATIONS_MAX) return ITERATIONS_MAX;
        return (int) scaled;
    }


    /** SHA-256 where the platform has it (Android 8+), SHA-1 otherwise. Stored with the verifier. */
    public static String pickAlgorithm() {
        try {
            SecretKeyFactory.getInstance(ALG_SHA256);
            return ALG_SHA256;
        } catch (Exception e) {
            return ALG_SHA1;
        }
    }

    public static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    public static byte[] derive(String pin, byte[] salt, int iterations, String algorithm)
            throws Exception {
        if (pin == null) throw new IllegalArgumentException("pin is null");
        if (salt == null) throw new IllegalArgumentException("salt is null");
        if (iterations <= 0) throw new IllegalArgumentException("iterations <= 0");
        char[] chars = pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    /** Constant-time comparison of the derived verifier. Never logs or returns the verifier. */
    public static boolean matches(String pin, byte[] salt, byte[] expected, int iterations,
            String algorithm) {
        if (pin == null || salt == null || expected == null || expected.length == 0) return false;
        try {
            return MessageDigest.isEqual(derive(pin, salt, iterations, algorithm), expected);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidPin(String pin) {
        if (pin == null || pin.length() != 4) return false;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] out = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }

    /** Returns null for malformed input instead of throwing; callers treat null as "no verifier". */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.length() == 0 || (hex.length() & 1) != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
