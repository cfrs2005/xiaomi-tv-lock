package com.toy.tvlock;

import android.content.Context;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * In-process diagnostic ring buffer.
 *
 * <p>The TV is not easy to attach a logcat to, and the interesting events (force-stop, revival,
 * overlay attach failures) are exactly the ones nobody is watching for. Keeping the last few
 * hundred lines in memory lets {@code dumpsys activity service com.toy.tvlock/.RevivalListener}
 * print the timeline months later, without writing PIN material to disk.
 *
 * <p>Never pass a PIN, a verifier, a salt or a recovery credential to this class.
 */
public final class DiagLog {

    private static final int CAPACITY = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(CAPACITY);
    private static long sStartElapsed = SystemClock.elapsedRealtime();
    private static long sStartWall = System.currentTimeMillis();

    private DiagLog() {
    }

    public static void init(Context context) {
        // Process start, not app start: this tells a fresh process from a revived one.
        sStartElapsed = SystemClock.elapsedRealtime();
        sStartWall = System.currentTimeMillis();
        append("process start");
    }

    public static void i(String message) {
        append(message);
    }

    public static void e(String message, Throwable t) {
        append(message + " :: " + (t == null ? "null" : t.getClass().getSimpleName()
                + ": " + t.getMessage()));
    }

    private static void append(String message) {
        long t = SystemClock.elapsedRealtime() - sStartElapsed;
        String line = String.format(java.util.Locale.US, "+%6.2fs  %s", t / 1000.0, message);
        synchronized (LINES) {
            if (LINES.size() >= CAPACITY) LINES.removeFirst();
            LINES.addLast(line);
        }
    }

    /** Full timeline, oldest first. */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("tvlock diagnostics\n");
        sb.append("process started: ").append(sStartWall).append(" (wall)\n");
        synchronized (LINES) {
            Iterator<String> it = LINES.iterator();
            while (it.hasNext()) sb.append("  ").append(it.next()).append('\n');
        }
        return sb.toString();
    }
}
