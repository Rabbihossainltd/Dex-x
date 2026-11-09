package com.ccrstech.openandroiddex.launcher;

import android.util.Log;

/**
 * Breadcrumbs for the phone half of a desktop launch.
 *
 * The PC streams `logcat -s OpenDeX` for as long as a session lives and folds
 * the lines into its own trace file, so anything written here lands in the
 * same timeline as the adb commands and scrcpy output that produced it. That
 * is the whole point: when a desktop half-starts, the interesting question is
 * usually which side got as far as what, and the two sides used to keep
 * separate diaries — the PC's said nothing about the launcher, and the
 * launcher kept none at all.
 *
 * Keep it to steps and state, not to per-frame chatter: this is a shared log,
 * and a noisy tag drowns the one line that mattered.
 */
final class DexLog {
    static final String TAG = "OpenDeX";

    /**
     * The last few hundred lines this process logged, kept in memory.
     *
     * <p>logcat needs READ_LOGS, which this app does not have and cannot get,
     * so "show me what happened" had no answer on the phone at all — the only
     * copy of these lines was in a terminal on somebody else's computer. The
     * diagnostics screen reads this.
     */
    private static final int KEEP = 600;
    private static final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>(KEEP);
    private static java.io.File file;

    private DexLog() {
    }

    /** Where the same lines go, so they survive the process: <external>/files/dex-x.log. */
    static void init(android.content.Context ctx) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            dir.mkdirs();
            file = new java.io.File(dir, "dex-x.log");
            if (file.length() > 512 * 1024) {
                // One generation back is plenty: this is a ring for a bug report.
                java.io.File old = new java.io.File(dir, "dex-x.1.log");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * A whole block into the kept log — the diagnostics report, so the file on
     * the phone has it even if nobody copied it out of the dialog.
     */
    static void dump(String tag, String block) {
        if (block == null) return;
        for (String line : block.split("\n")) {
            step(tag, line);
        }
    }

    /** The kept lines, oldest first. */
    static synchronized java.util.List<String> recent() {
        return new java.util.ArrayList<>(recent);
    }

    private static synchronized void keep(String line) {
        if (recent.size() >= KEEP) recent.removeFirst();
        recent.addLast(line);
        if (file == null) return;
        try (java.io.FileWriter w = new java.io.FileWriter(file, true)) {
            w.write(line);
            w.write('\n');
        } catch (Throwable ignored) {
        }
    }

    /** One step, attributed to the part of the launcher that took it. */
    static void step(String area, String message) {
        Log.i(TAG, "[" + area + "] " + message);
        keep(stamp() + " [" + area + "] " + message);
    }

    private static String stamp() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT);
        return f.format(new java.util.Date());
    }

    static void warn(String area, String message) {
        Log.w(TAG, "[" + area + "] " + message);
        keep(stamp() + " W [" + area + "] " + message);
    }

    static void warn(String area, String message, Throwable t) {
        Log.w(TAG, "[" + area + "] " + message, t);
    }
}
