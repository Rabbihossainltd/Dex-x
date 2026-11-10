package com.ccrstech.openandroiddex.launcher;

import java.util.Map;

/**
 * The one rule that decides who owns a window's caption strip.
 *
 * <p>WHY THIS IS A CLASS OF ITS OWN. Three rounds of caption work shipped and the
 * same two symptoms came back: a second title bar under ours, and an app whose
 * content sat low with an empty black band beneath it. Both come from the same
 * thing — the strip at the top of a task can be reserved twice, once by the
 * platform and once by us — and the fix for that has to be arithmetic that can
 * be RUN and CHECKED, not a comment in the middle of a 3 500-line service. So
 * the decision is here, it takes integers and returns a plan, and it has no
 * Android dependency at all: {@code tools/audit.py} can hold it to its promises
 * and a test can drive it through every state it will ever see.
 *
 * <p>THE TWO FACTS IT IS BUILT ON.
 *
 * <ol>
 *   <li>The platform draws a caption on <em>every</em> freeform task. AOSP's
 *       {@code CaptionWindowDecorViewModel.shouldShowWindowDecor} answers true for
 *       {@code WINDOWING_MODE_FREEFORM} before it looks at the package, the
 *       activity or the display. No flag, setting or binder call removes it, so
 *       the only way our bar can be "the only one" is to occupy exactly the
 *       pixels of that caption.</li>
 *   <li>Since Android 14 that caption is not a window — shell draws it as a
 *       surface child of the task and publishes a {@code captionBar} inset, which
 *       WMS reports as {@code appBounds.top > bounds.top}. That difference is the
 *       measurement.</li>
 * </ol>
 *
 * <p>THE TRAP, which the first attempt at this fell into. We reserve space with an
 * app-bounds override of our own. While that override is in force it REPLACES the
 * number WMS reports, so {@code appBounds.top - bounds.top} stops being the
 * platform's caption and becomes our strip. Measured a second time it therefore
 * "confirms" a value we made up, and a wrong height becomes permanent — the exact
 * class of self-sustaining bug this project keeps being reported for. Hence
 * {@code weStripped}: an inset that we asked for is ours until it is released,
 * and only a task we have NOT touched can be measured.
 */
final class CaptionRule {

    private CaptionRule() {
    }

    /** What one pass should do with one task's strip. */
    static final class Plan {
        /** Height our bar must be drawn at, px. Always at least 1. */
        int barPx = 1;
        /** Reserve {@link #barPx} ourselves — the platform draws no caption here. */
        boolean strip;
        /** Release an override of ours that is in this task's app bounds right now. */
        boolean unstrip;
        /** The platform's own caption height, px, when {@link #remember} says so. */
        int platformPx;
        /** Whether {@link #platformPx} is a measurement worth keeping. */
        boolean remember;
    }

    /**
     * One pass. Pure: no state, no I/O, no Android — same inputs, same plan.
     *
     * @param top          the task's top edge, absolute px
     * @param appTop       the app-bounds top WMS reports for it, absolute px
     * @param measured     the cached platform caption height for this task, or null
     *                     if it has never been measured cleanly
     * @param weStripped   whether the strip in force (if any) is one WE asked for
     * @param ourPx        the height of the strip we would make, px (40 dp)
     * @param animating    a collapse/close animation is moving this task: do not
     *                     re-inset it, the rect it was measured against is gone
     * @param platformCaptionPx a platform caption actually SEEN on this task, px — an
     *                     app-bounds inset or a caption-sized window at the task's top;
     *                     0 when neither is visible
     */
    static Plan decide(int top, int appTop, Integer measured, boolean weStripped,
                       int ourPx, boolean animating, int platformCaptionPx) {
        Plan p = new Plan();
        final int now = appTop - top;          // what is reserved in this task right now

        // ── the SECOND form a platform caption takes ────────────────────────
        //
        // The app-bounds inset is only one of the two shapes this caption has. The other
        // is a caption-sized WINDOW at the top of the task — which is how One UI and every
        // build before Android 14 do it, and there it is a window inside the task's decor
        // and WMS publishes NO inset at all. So `now` is 0 while the platform's ▢ ✕ are
        // plainly on screen. Keying the whole decision on the inset alone (0.7.19) therefore
        // put such a display straight back to "the platform's bar and nothing of ours".
        //
        // A caption we have actually SEEN is evidence of the first order: it wins over the
        // cached measurement, and the bar is built to exactly its height and placed at the
        // task's top.
        if (platformCaptionPx > 0) {
            p.platformPx = platformCaptionPx;
            p.remember = true;
            p.barPx = platformCaptionPx;
            p.unstrip = weStripped && now > 0;   // any strip of ours here is now redundant
            return p;
        }

        if (measured != null) {
            // Settled: we know what the platform does on this task.
            p.platformPx = measured;
            p.remember = false;
            if (measured > 0) {
                // The platform owns the space. Our bar is exactly that tall and we hold
                // no override of our own; anything else in the app bounds is a strip we
                // made before this was measured, or a stale one from a moved window.
                p.barPx = measured;
                p.unstrip = now != measured;
            } else {
                // The platform draws nothing here, so the strip is ours to make.
                p.barPx = ourPx;
                p.strip = !animating && now != ourPx;
            }
            return p;
        }

        if (weStripped && now > 0) {
            // The only inset in there is the one we asked for, so it measures us, not
            // the platform. Release it, keep the window covered at that height for the
            // pass, and let the next pass read the truth.
            p.barPx = now;
            p.unstrip = true;
            p.remember = false;
            return p;
        }

        // Untouched task: this is a clean reading.
        p.platformPx = now > 0 ? now : 0;
        p.remember = true;
        p.barPx = now > 0 ? now : ourPx;
        p.strip = now == 0 && !animating;
        return p;
    }

    // ── who draws a window's bar ────────────────────────────────────────────

    /** Nothing is drawn: the caption is not on screen, or the other renderer owns it. */
    static final int NONE = 0;
    /** The accessibility renderer, inside a host window's surface tree. */
    static final int ACCESSIBILITY = 1;
    /** This app's own top-level overlay window, above every app in the display. */
    static final int OVERLAY = 2;

    /**
     * Which renderer draws the bar on one task.
     *
     * <p>THE GEOMETRY IS THE WHOLE ANSWER. A caption the platform draws on a freeform
     * task is a surface child of that task, above the app — so the accessibility
     * renderer, whose bar lives inside a host window's surface tree, can only ever put
     * its bar BELOW it. That is not a health problem to be retried; it is a position
     * that renderer can never occupy. Only a top-level window of ours sits above every
     * app in the display, so on a captoned task the overlay renderer is the primary one,
     * whatever the accessibility service is doing.
     *
     * <p>Where the platform draws no caption the accessibility bar is the better
     * renderer — it needs no extra window and cannot float over another app — and the
     * overlay stays the standby it always was, for the service being unbound or stalled.
     *
     * @param platformPx the platform's caption height on this task, px; 0 for none
     * @param serviceHealthy the caption service is bound and can reach the daemon
     * @param stalled the service has had this task undressed long enough to give up on
     * @param coveredByHigher an opaque window above this one covers where the caption sits
     */
    static int owner(int platformPx, boolean serviceHealthy, boolean stalled,
                     boolean coveredByHigher) {
        if (coveredByHigher) return NONE;         // the platform's own caption is hidden too
        if (platformPx > 0) return OVERLAY;       // only a top-level window can cover it
        if (serviceHealthy && !stalled) return ACCESSIBILITY;
        return OVERLAY;
    }

    /** Drop the measurement for a task that is gone, so a reused id is measured again. */
    static void forget(Map<Integer, Integer> measured, int taskId) {
        measured.remove(taskId);
    }
}
