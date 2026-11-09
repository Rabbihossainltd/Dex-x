package com.ccrstech.openandroiddex.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.List;

/**
 * One tap that answers "what is actually happening on this phone".
 *
 * <p>Every round of this project's bug reports so far has been a screenshot of
 * a symptom and a guess about its cause — and the guesses were wrong four times
 * running, because the three things that can break the desktop all fail
 * SILENTLY and cannot be told apart from the outside:
 *
 * <ul>
 *   <li>the accessibility service is not <em>enabled</em> (Android 13+ hides the
 *       switch behind "Allow restricted settings" for a sideloaded app),</li>
 *   <li>the accessibility service is enabled but not <em>bound</em> (the phone
 *       refused it, and says nothing),</li>
 *   <li>the window daemon (wmd) is not answering — no task list, no strip, no
 *       move, no resize, nothing.</li>
 * </ul>
 *
 * <p>This collects all of it into plain text a person can paste into a message,
 * plus the tail of this process's own log. It is read-only: nothing here
 * changes any state.
 */
final class Diagnostics {

    private Diagnostics() {
    }

    /** The whole report. Never null; a section that throws says so in place. */
    static String report(Context ctx) {
        StringBuilder b = new StringBuilder(4096);
        b.append("Dex-X launcher diagnostics\n");

        b.append("version: ").append(version(ctx)).append('\n');
        b.append("\n-- accessibility --\n");
        b.append("service running: ").append(CaptionService.isLive() ? "yes" : "NO").append('\n');
        b.append("service enabled in settings: ").append(accessibilityEnabled(ctx) ? "yes" : "NO")
                .append('\n');
        b.append("caption problem(): ")
                .append(String.valueOf(CaptionService.problem())).append('\n');

        WmClient wm = new WmClient();
        b.append("\n-- window daemon (wmd) --\n");
        boolean up = false;
        try {
            up = wm.isUp();
            b.append("PING: ").append(up ? "OK" : "NO ANSWER").append('\n');
            int desk = wm.desktopDisplay();
            b.append("desktop display: ").append(desk).append('\n');
        } catch (Throwable t) {
            b.append("PING threw: ").append(t).append('\n');
        }

        b.append("\n-- displays --\n");
        try {
            DisplayManager dm = ctx.getSystemService(DisplayManager.class);
            for (Display d : dm.getDisplays()) {
                DisplayMetrics m = new DisplayMetrics();
                //noinspection deprecation
                d.getRealMetrics(m);
                b.append("display ").append(d.getDisplayId()).append(": ")
                        .append(m.widthPixels).append('x').append(m.heightPixels)
                        .append(" @").append(m.densityDpi).append("dpi name=")
                        .append(String.valueOf(d.getName())).append(" state=")
                        .append(d.getState()).append('\n');
            }
        } catch (Throwable t) {
            b.append("displays threw: ").append(t).append('\n');
        }

        b.append("\n-- tasks --\n");
        if (up) {
            try {
                int desk = wm.desktopDisplay();
                int phone = Display.DEFAULT_DISPLAY;
                appendTasks(b, "desktop display " + desk, wm.list(desk));
                appendTasks(b, "phone display " + phone, wm.list(phone));
            } catch (Throwable t) {
                b.append("task list threw: ").append(t).append('\n');
            }
        } else {
            b.append("(no task list: the daemon is not answering)\n");
        }

        b.append("\n-- caption fallback --\n");
        b.append("fallback bars on screen: ").append(CaptionFallback.barCount()).append('\n');

        b.append("\n-- own log (oldest first) --\n");
        List<String> lines = DexLog.recent();
        if (lines.isEmpty()) {
            b.append("(nothing logged yet)\n");
        } else {
            int from = Math.max(0, lines.size() - 160);
            for (int i = from; i < lines.size(); i++) b.append(lines.get(i)).append('\n');
        }
        try {
            wm.shutdown();
        } catch (Throwable ignored) {
        }
        return b.toString();
    }

    private static void appendTasks(StringBuilder b, String label, List<WmClient.Task> tasks) {
        if (tasks == null) {
            b.append(label).append(": NO ANSWER\n");
            return;
        }
        b.append(label).append(": ").append(tasks.size()).append(" task(s)\n");
        int n = 0;
        for (WmClient.Task t : tasks) {
            if (n++ >= 12) {
                b.append("  …\n");
                break;
            }
            b.append("  task ").append(t.taskId)
                    .append(" mode=").append(t.windowingMode)
                    .append(t.visible ? " vis" : " hidden")
                    .append(" ").append(t.left).append(',').append(t.top)
                    .append(' ').append(t.right - t.left).append('x').append(t.bottom - t.top)
                    .append(" app ").append(t.appLeft).append(',').append(t.appTop)
                    .append(" ").append(t.pkg).append('/').append(t.activity).append('\n');
        }
    }

    /** Is our accessibility service switched ON in the phone's settings? */
    static boolean accessibilityEnabled(Context ctx) {
        try {
            ComponentName cn = new ComponentName(ctx, CaptionService.class);
            String flat = cn.flattenToString();
            String shortFlat = cn.flattenToShortString();
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) enabled = "";
            for (String s : enabled.split(":")) {
                if (s.equalsIgnoreCase(flat) || s.equalsIgnoreCase(shortFlat)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static String version(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
