package com.toy.tvlock;

/**
 * Off-device tests for the parts of the lock that do not touch Android.
 *
 * <p>Run with {@code ./test.sh}. This is deliberately not JUnit: the project builds with a hand
 * rolled aapt2/javac/d8 pipeline and adding a test framework would mean adding a build system.
 * The value here is that PIN derivation and backoff are exercised on every change instead of only
 * when a TV happens to be plugged in.
 */
public final class RunTests {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        pinDerivationIsStable();
        saltChangesTheVerifier();
        wrongPinIsRejected();
        malformedVerifierIsRejected();
        pinFormatValidation();
        hexRoundTrip();
        backoffSchedule();
        calibratedIterationsStayInRange();
        reportDerivationCost();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- PIN

    private static void pinDerivationIsStable() throws Exception {
        byte[] salt = PinCrypto.fromHex("000102030405060708090a0b0c0d0e0f");
        byte[] a = PinCrypto.derive("2025", salt, 1000, PinCrypto.ALG_SHA256);
        byte[] b = PinCrypto.derive("2025", salt, 1000, PinCrypto.ALG_SHA256);
        check("same pin+salt+iterations -> same verifier", java.util.Arrays.equals(a, b));
        check("verifier is 256 bits", a.length == PinCrypto.KEY_BITS / 8);
    }

    private static void saltChangesTheVerifier() throws Exception {
        byte[] a = PinCrypto.derive("2025", PinCrypto.randomSalt(), 1000, PinCrypto.ALG_SHA256);
        byte[] b = PinCrypto.derive("2025", PinCrypto.randomSalt(), 1000, PinCrypto.ALG_SHA256);
        check("different salts -> different verifier", !java.util.Arrays.equals(a, b));

        byte[] s1 = PinCrypto.randomSalt();
        byte[] s2 = PinCrypto.randomSalt();
        check("salt is SALT_BYTES long", s1.length == PinCrypto.SALT_BYTES);
        check("salts differ between calls", !java.util.Arrays.equals(s1, s2));
    }

    private static void wrongPinIsRejected() throws Exception {
        byte[] salt = PinCrypto.randomSalt();
        byte[] verifier = PinCrypto.derive("2025", salt, 1000, PinCrypto.ALG_SHA256);
        check("correct pin accepted",
                PinCrypto.matches("2025", salt, verifier, 1000, PinCrypto.ALG_SHA256));
        check("wrong pin rejected",
                !PinCrypto.matches("2024", salt, verifier, 1000, PinCrypto.ALG_SHA256));
        check("empty pin rejected",
                !PinCrypto.matches("", salt, verifier, 1000, PinCrypto.ALG_SHA256));
        check("null pin rejected",
                !PinCrypto.matches(null, salt, verifier, 1000, PinCrypto.ALG_SHA256));
        check("iteration count is part of the verifier",
                !PinCrypto.matches("2025", salt, verifier, 999, PinCrypto.ALG_SHA256));
    }

    private static void malformedVerifierIsRejected() {
        check("null verifier is not a match",
                !PinCrypto.matches("2025", PinCrypto.randomSalt(), null, 1000,
                        PinCrypto.ALG_SHA256));
        check("empty verifier is not a match",
                !PinCrypto.matches("2025", PinCrypto.randomSalt(), new byte[0], 1000,
                        PinCrypto.ALG_SHA256));
        check("null salt is not a match",
                !PinCrypto.matches("2025", null, new byte[]{1}, 1000, PinCrypto.ALG_SHA256));
    }

    private static void pinFormatValidation() {
        check("1234 is valid", PinCrypto.isValidPin("1234"));
        check("0000 is valid", PinCrypto.isValidPin("0000"));
        check("3 digits rejected", !PinCrypto.isValidPin("123"));
        check("5 digits rejected", !PinCrypto.isValidPin("12345"));
        check("letters rejected", !PinCrypto.isValidPin("12a4"));
        check("null rejected", !PinCrypto.isValidPin(null));
        check("empty rejected", !PinCrypto.isValidPin(""));
    }

    private static void hexRoundTrip() {
        byte[] bytes = {0x00, 0x0f, (byte) 0xa5, (byte) 0xff, 0x10};
        String hex = PinCrypto.toHex(bytes);
        check("hex output", "000fa5ff10".equals(hex));
        check("hex round trip", java.util.Arrays.equals(bytes, PinCrypto.fromHex(hex)));
        check("odd length hex rejected", PinCrypto.fromHex("abc") == null);
        check("non-hex rejected", PinCrypto.fromHex("zz") == null);
        check("empty hex rejected", PinCrypto.fromHex("") == null);
    }

    // ---------------------------------------------------------------- backoff

    private static void backoffSchedule() {
        for (int i = 0; i <= BackoffPolicy.FREE_ATTEMPTS; i++) {
            check("attempts=" + i + " has no wait", BackoffPolicy.delayMs(i) == 0L);
        }
        check("5 failures -> 30s", BackoffPolicy.delayMs(5) == 30_000L);
        check("6 failures -> 60s", BackoffPolicy.delayMs(6) == 60_000L);
        check("7 failures -> 120s", BackoffPolicy.delayMs(7) == 120_000L);
        check("8 failures -> 240s", BackoffPolicy.delayMs(8) == 240_000L);
        check("9 failures capped at 300s", BackoffPolicy.delayMs(9) == 300_000L);
        check("1000 failures still capped",
                BackoffPolicy.delayMs(1000) == 300_000L);
        check("free attempts left after 0", BackoffPolicy.freeAttemptsLeft(0) == 4);
        check("free attempts left after 4", BackoffPolicy.freeAttemptsLeft(4) == 0);
        check("free attempts never negative", BackoffPolicy.freeAttemptsLeft(99) == 0);
    }

    // ---------------------------------------------------------------- timing

    private static void calibratedIterationsStayInRange() {
        int chosen = PinCrypto.chooseIterations("2025");
        check("calibrated iterations >= minimum", chosen >= PinCrypto.ITERATIONS_MIN);
        check("calibrated iterations <= maximum", chosen <= PinCrypto.ITERATIONS_MAX);

        byte[] salt = PinCrypto.randomSalt();
        try {
            int iterations = PinCrypto.chooseIterations("1234");
            byte[] v = PinCrypto.derive("1234", salt, iterations, PinCrypto.ALG_SHA256);
            check("verifier made at a calibrated count still matches",
                    PinCrypto.matches("1234", salt, v, iterations, PinCrypto.ALG_SHA256));
        } catch (Exception e) {
            check("verifier made at a calibrated count still matches", false);
        }
    }

    /**
     * Not an assertion: the device is a 32-bit ARMT MT9653 and this machine is not, so the only
     * honest thing to do here is print the cost and let the on-device measurement decide whether
     * {@link PinCrypto#ITERATIONS} has to move.
     */
    private static void reportDerivationCost() throws Exception {
        byte[] salt = PinCrypto.randomSalt();
        long worst = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            PinCrypto.derive("2025", salt, PinCrypto.ITERATIONS, PinCrypto.ALG_SHA256);
            worst = Math.max(worst, (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println("[info] PBKDF2 " + PinCrypto.ITERATIONS + " iterations: worst "
                + worst + " ms on this host (device is slower; measure on MT9653)");
    }

    // ---------------------------------------------------------------- harness

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}
