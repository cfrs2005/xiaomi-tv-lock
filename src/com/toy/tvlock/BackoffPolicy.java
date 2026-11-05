package com.toy.tvlock;

/**
 * Failure backoff for PIN entry.
 *
 * <p>Pure Java so it can be tested off-device. The first {@link #FREE_ATTEMPTS} mistakes cost
 * nothing (fat fingers on a TV remote are normal); after that the wait doubles, capped so the lock
 * screen never becomes a dead end for the owner.
 *
 * <p>Persisted by {@link PinManager}, which is what makes this survive a reboot: a restart must not
 * clear the penalty.
 */
public final class BackoffPolicy {

    public static final int FREE_ATTEMPTS = 4;
    private static final long STEP_MS = 30_000L;
    private static final long MAX_MS = 300_000L;

    private BackoffPolicy() {
    }

    /** Wait required after {@code failedAttempts} consecutive wrong PINs. */
    public static long delayMs(int failedAttempts) {
        if (failedAttempts <= FREE_ATTEMPTS) return 0L;
        int step = Math.min(failedAttempts - FREE_ATTEMPTS, 8);
        long delay = STEP_MS << (step - 1);
        return Math.min(delay, MAX_MS);
    }

    /** How many more mistakes are free before the wait kicks in. */
    public static int freeAttemptsLeft(int failedAttempts) {
        return Math.max(0, FREE_ATTEMPTS - failedAttempts);
    }
}
