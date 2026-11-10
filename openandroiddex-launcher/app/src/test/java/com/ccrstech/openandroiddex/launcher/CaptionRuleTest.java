package com.ccrstech.openandroiddex.launcher;

/**
 * Drives the REAL {@link CaptionRule} — the same file the app compiles — through every
 * state a task can be in on the phone, as a SEQUENCE of passes rather than one call, so
 * that the strips and releases it issues are checked in order.
 *
 * <p>This exists because the last three rounds of caption work each shipped a fix that
 * looked right in the diff and was wrong on the phone, and because the one trap this
 * release was a commit away from shipping — measuring a task that still carries OUR
 * app-bounds override, and believing the answer — cannot be seen in a code review but
 * fails immediately here.
 *
 * <p>Plain Java on purpose (no JUnit, no Android): {@code tools/rule-test.sh} compiles
 * this file with {@code javac} and runs it, so it can be checked without Gradle, without
 * a device and without the SDK.
 */

public final class CaptionRuleTest {

    static int fails = 0;

    static void expect(String what, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + what + (detail.isEmpty() ? "" : "   " + detail));
        if (!ok) fails++;
    }

    /** A task on the phone: bounds and app bounds, plus what we did to it. */
    static final class W {
        int top = 100, appTop = 100;        // absolute px
        Integer measured;                   // cache: null = never measured
        boolean weStripped;                 // did WE ask for the inset in force?
        boolean animating;
        /** A platform caption SEEN as a window — the One UI / pre-14 form. */
        int seenPx;

        CaptionRule.Plan pass() {
            CaptionRule.Plan p = CaptionRule.decide(top, appTop, measured, weStripped, 80, animating, seenPx);
            if (p.remember) measured = p.platformPx;
            if (p.unstrip) {                    // the daemon applies it: the app bounds go back
                appTop = top;                   // to the platform's own inset next pass
                weStripped = false;
            }
            if (p.strip) {
                appTop = top + p.barPx;         // our override is now what WMS reports
                weStripped = true;
            }
            return p;
        }
    }

    public static void main(String[] a) {
        // 1. A phone whose platform draws a 48 px caption, task untouched.
        W w = new W();
        w.appTop = 148;                          // the platform already inset it by 48
        CaptionRule.Plan p = w.pass();
        expect("platform caption 48px -> bar is exactly 48px", p.barPx == 48, "barPx=" + p.barPx);
        expect("platform caption 48px -> we reserve nothing", !p.strip, "strip=" + p.strip);
        expect("platform caption 48px -> measured and remembered", p.remember && w.measured == 48, "");

        // 2. Same phone, later pass: stable, nothing issued.
        p = w.pass();
        expect("second pass -> no strip, no unstrip",
                !p.strip && !p.unstrip && p.barPx == 48, "barPx=" + p.barPx);

        // 3. A phone whose platform draws NO caption: we make the strip once.
        W n = new W();
        p = n.pass();
        expect("no platform caption -> we reserve 40dp (80px)", p.strip && p.barPx == 80, "");
        expect("no platform caption -> remembered as zero", p.remember && n.measured == 0, "");
        p = n.pass();
        expect("second pass -> already reserved, nothing issued", !p.strip && !p.unstrip, "");

        // 4. THE TRAP: a task carrying OUR strip, never measured (e.g. this build's
        //    first pass after an upgrade). Its 80 px inset must NOT be read as the
        //    platform's caption.
        W t = new W();
        t.appTop = 180;                          // +80: could be ours, could be the platform's
        t.weStripped = true;
        p = t.pass();
        expect("our own strip is released, not believed",
                p.unstrip && !p.remember, "unstrip=" + p.unstrip + " remember=" + p.remember);
        expect("window stays covered while it is re-measured", p.barPx == 80, "barPx=" + p.barPx);
        // next pass: the platform's truth, which on this phone is a 48 px caption
        t.appTop = 148;
        p = t.pass();
        expect("after release the truth is read: 48px", p.remember && p.barPx == 48, "barPx=" + p.barPx);

        // 5. The same upgrade on a phone with NO platform caption: release, then make
        //    our own strip again — and settle.
        W u = new W();
        u.appTop = 180;
        u.weStripped = true;
        p = u.pass();
        expect("stale strip released", p.unstrip, "");
        u.appTop = u.top;                        // the platform insets nothing
        p = u.pass();
        expect("then measured as zero and re-reserved", p.remember && p.strip && u.measured == 0, "");
        p = u.pass();
        expect("and settles with no further traffic", !p.strip && !p.unstrip, "");

        // 6. A window that moves: our strip is no longer aligned, the platform's inset
        //    (48) is what WMS reports for the new rect.
        W m = new W();
        m.appTop = 148;
        m.pass();
        m.top = 400;
        m.appTop = 448;
        p = m.pass();
        expect("moved window with a platform caption -> still 48, nothing issued",
                p.barPx == 48 && !p.strip, "barPx=" + p.barPx);

        // 7. A collapse animation: never re-inset a rect that is being dragged.
        W an = new W();
        an.animating = true;
        p = an.pass();
        expect("animating -> we do not reserve", !p.strip, "");
        expect("animating -> the bar still has a height", p.barPx == 80, "barPx=" + p.barPx);

        // 8. Nothing is ever zero-height: a bar of 0 px is no bar at all.
        W z = new W();
        z.appTop = z.top;
        z.animating = true;
        p = z.pass();
        expect("bar height is never 0", p.barPx >= 1, "barPx=" + p.barPx);

        // ── the caption that reserves NOTHING: a window inside the task ─────
        // One UI and every build before Android 14 draw the caption as a window at the
        // top of the task and publish no app-bounds inset. `now` is 0 there, so keying
        // the decision on the inset alone reports "no caption" while the platform's own
        // close and maximise are plainly on screen.
        W win = new W();
        win.seenPx = 72;
        p = win.pass();
        expect("a caption SEEN as a window -> bar is exactly its height", p.barPx == 72, "barPx=" + p.barPx);
        expect("a caption SEEN as a window -> we reserve nothing of our own", !p.strip, "");
        expect("a caption SEEN as a window -> recorded as the platform's", win.measured == 72, "");

        W winStripped = new W();
        winStripped.appTop = winStripped.top + 80;   // our strip, from an older pass
        winStripped.weStripped = true;
        winStripped.seenPx = 72;
        p = winStripped.pass();
        expect("a caption SEEN as a window releases our strip", p.unstrip, "");

        // ── who draws a bar, and why ────────────────────────────────────────
        // The accessibility bar lives inside a host window's surface tree; the platform's
        // caption is above the app's. On a captoned task that renderer cannot win, so
        // the overlay must be chosen — even when the service is perfectly healthy.
        expect("a captoned window is drawn by the overlay, service healthy or not",
                CaptionRule.owner(48, true, false, false) == CaptionRule.OVERLAY, "");
        expect("a captoned window is drawn by the overlay, service stalled",
                CaptionRule.owner(48, true, true, false) == CaptionRule.OVERLAY, "");
        expect("no platform caption + healthy service -> accessibility bar",
                CaptionRule.owner(0, true, false, false) == CaptionRule.ACCESSIBILITY, "");
        expect("no platform caption + stalled service -> overlay takes over",
                CaptionRule.owner(0, true, true, false) == CaptionRule.OVERLAY, "");
        expect("no platform caption + dead service -> overlay",
                CaptionRule.owner(0, false, false, false) == CaptionRule.OVERLAY, "");
        expect("a caption hidden behind a higher window is not covered",
                CaptionRule.owner(48, true, false, true) == CaptionRule.NONE, "");
        expect("no second bar: a covered window gets nothing, not the service's bar",
                CaptionRule.owner(0, true, false, true) == CaptionRule.NONE, "");

        System.out.println();
        System.out.println(fails == 0 ? "ALL RULE CASES PASS" : fails + " CASE(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
