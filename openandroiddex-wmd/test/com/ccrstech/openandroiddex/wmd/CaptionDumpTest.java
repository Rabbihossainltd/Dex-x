package com.ccrstech.openandroiddex.wmd;

/**
 * Drives the REAL {@link CaptionDump} with dumpsys text shaped the way AOSP prints it.
 *
 * <p>The parser turns "who owns the title bar" into one number, and a wrong number here is
 * worse than none: it decides how tall our bar is drawn. So it is tested against a dump
 * that contains the awkward cases on purpose — a caption on another display, a window that
 * shares the top edge but not the width, an app window that is caption-height but not at the
 * top, and a second caption lower down — and the right answer must still come out.
 *
 * <p>Plain Java, no Android, no JUnit: {@code bash tools/dump-test.sh} compiles this file
 * and {@code CaptionDump.java} with nothing but a JDK and runs it.
 */
public final class CaptionDumpTest {

    static int fails = 0;

    static void expect(String what, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + what
                + (detail.isEmpty() ? "" : "   " + detail));
        if (!ok) fails++;
    }

    /** A dump in AOSP's `dumpsys window windows` shape, decoys included. */
    static final String DUMP =
            "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
            + "  Display: mDisplayId=2\n"
            // 1. the caption the platform draws on our freeform task: 120..900 x 240..312
            + "    Window #7 Window{8f2c1a3 u0 com.example.alpha/com.example.alpha.MainActivity}:\n"
            + "      mDisplayId=2 mTaskId=118 mHasSurface=true\n"
            + "      mFrame=[120,240][900,312] last=[120,240][900,312]\n"
            + "      mAttrs={(0,0)(fillx72) gr=TOP LEFT} ty=APPLICATION fmt=TRANSLUCENT\n"
            // 2. DECOY: same top edge, different width — an app window beside it
            + "    Window #8 Window{1b2c3d4 u0 com.example.alpha/com.example.alpha.Panel}:\n"
            + "      mDisplayId=2 mTaskId=0 mHasSurface=true\n"
            + "      mFrame=[120,240][500,700] last=[120,240][500,700]\n"
            // 3. DECOY: the same caption geometry, on ANOTHER display
            + "    Window #9 Window{2c3d4e5 u0 com.example.beta/com.example.beta.MainActivity}:\n"
            + "      mDisplayId=0 mTaskId=44 mHasSurface=true\n"
            + "      mFrame=[300,240][700,312] last=[300,240][700,312]\n"
            // 4. DECOY: caption-height, right width, but nowhere near the task's top
            + "    Window #10 Window{3d4e5f6 u0 com.example.alpha/com.example.alpha.Tooltip}:\n"
            + "      mDisplayId=2 mTaskId=0 mHasSurface=true\n"
            + "      mFrame=[120,800][900,872] last=[120,800][900,872]\n";

    public static void main(String[] a) {
        // The task: display 2, 120..900 across, top at 240 — the caption is 72 px tall.
        int h = CaptionDump.captionHeight(DUMP, 2, 120, 240, 900, "com.example.alpha", 300);
        expect("the caption on the task is found, and it is 72 px tall", h == 72, "h=" + h);

        // A task that is on another display must not borrow this one's caption.
        expect("a task on display 0 gets nothing from display 2's dump",
                CaptionDump.captionHeight(DUMP, 0, 120, 240, 900, "com.example.alpha", 300) == 0, "");

        // A window that fills the screen is not a caption, however it is framed.
        expect("no caption when the top edge does not match",
                CaptionDump.captionHeight(DUMP, 2, 120, 500, 900, "com.example.alpha", 300) == 0, "");

        // The width test is what keeps a side panel from being mistaken for a caption.
        expect("a window sharing the top but not the width is not a caption",
                CaptionDump.captionHeight(DUMP, 2, 120, 240, 700, "com.example.alpha", 300) == 0, "");

        // An app window cannot be caption-height: maxPx is the caller's bound.
        expect("a caption-shaped window taller than the caller allows is rejected",
                CaptionDump.captionHeight(DUMP, 2, 120, 240, 900, "com.example.alpha", 40) == 0, "");

        // Garbage in, nothing out — never a crash on a phone whose dump format differs.
        expect("an empty dump answers 0",
                CaptionDump.captionHeight("", 2, 120, 240, 900, "x", 300) == 0, "");
        expect("a null dump answers 0",
                CaptionDump.captionHeight(null, 2, 120, 240, 900, "x", 300) == 0, "");
        expect("lines that are not windows answer 0",
                CaptionDump.captionHeight("mFrame=[0,0][1,1]\nhello\n", 2, 0, 0, 1, "x", 300) == 0, "");

        // Reformatting: dumpsys pads and wraps differently between releases.
        String spaced = "Window #1 Window{a u0 com.example.alpha/.Main}:\n"
                + "   mFrame=[ 120, 240 ][ 900, 312 ] last=[120,240][900,312]\n";
        expect("spaces inside the frame do not defeat the parser",
                CaptionDump.captionHeight(spaced, -1, 120, 240, 900, "com.example.alpha", 300) == 72, "");

        System.out.println();
        System.out.println(fails == 0 ? "ALL DUMP CASES PASS" : fails + " CASE(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
