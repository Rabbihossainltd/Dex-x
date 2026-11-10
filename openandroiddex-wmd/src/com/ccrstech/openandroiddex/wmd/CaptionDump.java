package com.ccrstech.openandroiddex.wmd;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the platform's caption window for one task in {@code dumpsys window windows}.
 *
 * <p>WHY THIS EXISTS. The caption a phone draws on a freeform window takes two shapes, and
 * the launcher can only see one of them without help. The app-bounds inset is visible to
 * anything that can read the task; a caption drawn as a WINDOW inside the task's decor is
 * visible only to an accessibility service — and when that service is switched off, which is
 * the normal state on Android 13+ for a sideloaded app until "Allow restricted settings" is
 * granted, nothing in the app can measure it. A bar is then drawn at a guessed height and
 * the platform's own buttons show underneath: the "double caption bar" report, in the one
 * state where the app has no other witness.
 *
 * <p>The daemon runs as shell, so {@code dumpsys} is available to it. This class is the part
 * that reads the answer: it is deliberately PURE — strings in, integers out, no Android, no
 * I/O — so that it can be compiled and run against real dumpsys text in a test
 * ({@code tools/dump-test.sh}) instead of being trusted because it looks right.
 *
 * <p>The format it reads, from AOSP's {@code WindowManagerService#dump}:
 *
 * <pre>
 *   Window #4 Window{8f2c1a3 u0 com.example/com.example.MainActivity}:
 *     mDisplayId=2 mTaskId=118 mHasSurface=true
 *     mFrame=[120,240][900,1200] last=[120,240][900,1200]
 * </pre>
 */
final class CaptionDump {

    private CaptionDump() {
    }

    /** A window line's own header, which carries the owner it belongs to. */
    private static final Pattern HEADER = Pattern.compile("Window #\\d+ (Window\\{.*?\\}:?.*)");
    private static final Pattern FRAME = Pattern.compile(
            "mFrame=\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]");
    private static final Pattern DISPLAY = Pattern.compile("mDisplayId=(-?\\d+)");

    /** A window as dumpsys describes it: who owns it, where it is, on which display. */
    private static final class Win {
        String title = "";
        int display = -1;
        int left, top, right, bottom;
        boolean hasFrame;
    }

    /**
     * The height, in px, of a caption window sitting on top of the given task rect, or 0.
     *
     * <p>"Sitting on top of" is the whole test, and it is meant to be strict: same display,
     * same left and right edges as the task, a top edge at the task's top, and a height
     * small enough to be a caption rather than the app. A near-miss returns 0, because a
     * wrong number is worse here than no number — a bar of the wrong height is exactly the
     * bug this is fixing.
     *
     * @param dump    the text of {@code dumpsys window windows}
     * @param display the display the task is on
     * @param left    the task's left edge, px
     * @param top     the task's top edge, px
     * @param right   the task's right edge, px
     * @param pkg     the task's package, or null/empty when unknown
     * @param maxPx   the tallest thing that could still be a caption (about 2.5 normal ones)
     */
    static int captionHeight(String dump, int display, int left, int top, int right,
                             String pkg, int maxPx) {
        if (dump == null || dump.isEmpty() || maxPx <= 0) return 0;
        int best = 0;
        for (Win w : parse(dump)) {
            if (!w.hasFrame || w.display != display) continue;
            if (Math.abs(w.left - left) > 2 || Math.abs(w.right - right) > 2) continue;
            if (Math.abs(w.top - top) > 2) continue;
            int h = w.bottom - w.top;
            if (h <= 0 || h > maxPx) continue;
            // A window that names our package is preferred; a decor window that names
            // nothing at all is still a candidate, because the caption of a task often
            // carries only the activity name. Both are bounded by the geometry above.
            boolean names = pkg != null && !pkg.isEmpty() && w.title.contains(pkg);
            int weight = names ? 1000 : 0;
            if (h + weight > best) best = h + weight;
        }
        // Strip the preference weight: the answer is a height, never a score.
        return best <= 0 ? 0 : (best >= 1000 ? best - 1000 : best);
    }

    /** Split a dumpsys into windows. Visible for the test. */
    static List<String> titles(String dump) {
        List<String> out = new ArrayList<>();
        if (dump == null) return out;
        for (Win w : parse(dump)) out.add(w.title);
        return out;
    }

    private static List<Win> parse(String dump) {
        List<Win> out = new ArrayList<>();
        Win cur = null;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            Matcher head = HEADER.matcher(line);
            if (head.find()) {
                cur = new Win();
                cur.title = head.group(1);
                out.add(cur);
                continue;
            }
            if (cur == null) continue;
            Matcher frame = FRAME.matcher(line);
            if (frame.find()) {
                cur.left = Integer.parseInt(frame.group(1));
                cur.top = Integer.parseInt(frame.group(2));
                cur.right = Integer.parseInt(frame.group(3));
                cur.bottom = Integer.parseInt(frame.group(4));
                cur.hasFrame = true;
            }
            Matcher disp = DISPLAY.matcher(line);
            if (disp.find()) cur.display = Integer.parseInt(disp.group(1));
        }
        return out;
    }
}
