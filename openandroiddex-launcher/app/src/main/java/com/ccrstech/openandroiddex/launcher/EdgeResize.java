package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The window's OWN edges, made draggable.
 *
 * <p>Why this exists, in the user's words: "resize icon ta kone dekha jai kintu
 * kaj kore bhitor e" — the grip is drawn on the title bar, and a drag that
 * starts there resizes, but the window's actual bottom-right corner does
 * nothing. That was never a bug in the gesture; it was ownership. Our chrome is a
 * {@link android.view.SurfaceControlViewHost} whose surface is exactly the
 * 40 dp caption strip, so the app's own surface — everything below it, and the
 * corner — belongs to the app. A drag that starts there is the app's.
 *
 * <p>So the corner is brought to us instead, the way a real window manager has
 * it: three thin, invisible windows of OURS laid along the front-most window's
 * right edge, bottom edge and bottom-right corner. They are
 * {@code TYPE_ACCESSIBILITY_OVERLAY} windows on the desktop's display, created
 * from a window context of that display — the same route {@link SnapOverlay}
 * and {@link CaptionFallback} take, and the only one an AccessibilityService may
 * use without SYSTEM_ALERT_WINDOW.
 *
 * <p>An overlay also fixes the other half of the complaint. A touch stream
 * belongs to the window that received ACTION_DOWN until ACTION_UP, even when the
 * pointer travels far outside its bounds — so a drag that begins on a 8 dp band
 * keeps arriving after the pointer is a hundred pixels away, which a 40 dp
 * caption strip could not promise. That is why a drag "only worked inside".
 *
 * <p>Only the FRONT-most window gets bands. An overlay is above every app
 * window, so a band belonging to a window three deep would swallow clicks meant
 * for the one on top; and the window a person resizes is the one they can see.
 *
 * <p>Everything here is best-effort: a display that refuses the windows leaves
 * the title-bar grip working exactly as it is today.
 */
final class EdgeResize {

    private static final String TAG = "dex-edges";

    /** Right, bottom, bottom-right corner — the three the platform's own resize has. */
    private static final int RIGHT = 1, BOTTOM = 2, CORNER = 3;
    /** How thick a band is, in dp of the desktop display. */
    private static final int BAND_DP = 7;
    /** Stricter than a finger's slop: this is a pointer, and a band is 7 dp. */
    private static final int SLOP_PX = 2;

    /** What a finished drag says to the service: "put it here". */
    interface Sink {
        /** The window's rect right now, or null if it has gone. */
        int[] rect();

        /** The largest rect it may have (the display less the taskbar). */
        int[] limits();

        /** Apply a rect from the pointer. Called on every frame. */
        void apply(int left, int top, int right, int bottom);

        /** A hand resize cancels "maximised", whoever else remembers it. */
        void byHand();
    }

    private final AccessibilityService svc;

    private Context ctx;
    private WindowManager mgr;
    private int displayId = -1;

    private final View[] views = new View[4];   // index RIGHT/BOTTOM/CORNER
    private final int[] sizes = new int[4];

    /** The task the bands belong to, and the rect they were last placed on. */
    private int taskId = -1;
    private int[] placed;
    private Sink sink;

    /**
     * A gesture is running.
     *
     * <p>The reconcile poll keeps arriving during a drag and carries the rect the
     * DAEMON last knew — a frame or two behind the pointer. Re-placing the bands
     * from that would drag them backwards under the finger, so the bands are
     * frozen until the drag ends.
     */
    private boolean dragging;

    EdgeResize(AccessibilityService service) {
        this.svc = service;
    }

    /**
     * Show (or move) the bands for the front-most window, or take them away.
     *
     * @param taskId the task the bands belong to, or -1 for none
     * @param rect   its rect in display pixels, or null
     */
    void sync(int display, int taskId, int[] rect, int[] limits, Sink sink) {
        if (rect == null || taskId < 0) {
            hide();
            return;
        }
        // A maximised window has no edge to pull: every one of them is the
        // display's own edge, and there the taskbar and the system gesture area
        // are what a draw from the edge means. Same for a window that is all but
        // the size of the work area.
        if (limits != null
                && rect[2] - rect[0] >= limits[2] - limits[0] - 8
                && rect[3] - rect[1] >= limits[3] - limits[1] - 8) {
            hide();
            return;
        }
        this.sink = sink;
        if (dragging && taskId == this.taskId) return;   // the finger owns the rect
        if (!open(display)) {
            hide();
            return;
        }
        this.taskId = taskId;
        place(rect);
    }

    /** Take the bands down. Safe at any time, and called when nothing is front. */
    void hide() {
        taskId = -1;
        placed = null;
        sink = null;
        for (int i = 1; i <= 3; i++) {
            View v = views[i];
            views[i] = null;
            if (v == null || mgr == null) continue;
            try {
                mgr.removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
        }
        mgr = null;
        ctx = null;
        displayId = -1;
    }

    /** Is a band of ours on screen? (Diagnostics.) */
    boolean showing() {
        return views[1] != null || views[2] != null || views[3] != null;
    }

    // ── geometry ───────────────────────────────────────────────────────────

    private void place(int[] rect) {
        int band = Math.max(6, Math.round(BAND_DP * density()));
        int corner = band * 3;
        place(RIGHT, rect[2] - band, rect[1] + band, band, Math.max(band, rect[3] - rect[1] - band));
        place(BOTTOM, rect[0] + band, rect[3] - band, Math.max(band, rect[2] - rect[0] - band), band);
        place(CORNER, rect[2] - corner, rect[3] - corner, corner, corner);
        placed = new int[]{rect[0], rect[1], rect[2], rect[3]};
    }

    private void place(int which, int x, int y, int w, int h) {
        View v = views[which];
        if (v == null) {
            v = new View(ctx);
            v.setAlpha(0.02f);                 // present for input, invisible to the eye
            v.setOnTouchListener(new Drag(which));
            v.setContentDescription(which == CORNER ? "Resize corner" : "Resize edge");
            ViewGroupParams lp = new ViewGroupParams(x, y, w, h);
            try {
                mgr.addView(v, lp.params());
            } catch (Throwable t) {
                Log.w(TAG, "band " + which + " refused: " + t);
                return;
            }
            views[which] = v;
            sizes[which] = w;
            return;
        }
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            lp.x = x;
            lp.y = y;
            lp.width = Math.max(1, w);
            lp.height = Math.max(1, h);
            mgr.updateViewLayout(v, lp);
        } catch (Throwable ignored) {
        }
    }

    private float density() {
        try {
            DisplayManager dm = svc.getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(displayId);
            if (d != null) {
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                d.getRealMetrics(m);
                return m.density;
            }
        } catch (Throwable ignored) {
        }
        return 1f;
    }

    /** The display's window context and WindowManager, built once per display. */
    private boolean open(int display) {
        if (mgr != null && display == displayId) return true;
        hide();
        try {
            DisplayManager dm = svc.getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(display);
            if (d == null) return false;
            Context base = svc.createDisplayContext(d);
            Context c;
            if (Build.VERSION.SDK_INT >= 30) {
                c = base.createWindowContext(
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            } else {
                c = base;
            }
            WindowManager w = c.getSystemService(WindowManager.class);
            if (w == null) return false;
            ctx = c;
            mgr = w;
            displayId = display;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "no window context for display " + display + ": " + t);
            return false;
        }
    }

    /** The three layout params a band window needs, in one place. */
    private static final class ViewGroupParams {
        private final int x, y, w, h;

        ViewGroupParams(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        WindowManager.LayoutParams params() {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.max(1, w), Math.max(1, h),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // Focusable: NO (the app keeps its keyboard and its own focus);
                    // touch-modal: yes, so a press outside the band goes to the app;
                    // layout in screen: the coordinates above are display pixels.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = x;
            lp.y = y;
            return lp;
        }
    }

    // ── the gesture ────────────────────────────────────────────────────────

    /**
     * One band's drag.
     *
     * <p>The same arithmetic the title bar's grip does (CaptionChrome.propose),
     * for the same reasons, and deliberately a second copy rather than a shared
     * one: the grip works in the caption host's coordinate space and this works
     * in the display's, and a bug in one should not be able to move the other.
     */
    private final class Drag implements View.OnTouchListener {
        private final int which;
        private int l, t, r, b;
        private float downX, downY;
        private int wantL, wantT, wantR, wantB;
        private boolean pending;

        Drag(int which) {
            this.which = which;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            Sink s = sink;
            if (s == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int[] rect = s.rect();
                    if (rect == null) return false;
                    l = rect[0];
                    t = rect[1];
                    r = rect[2];
                    b = rect[3];
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = true;
                    s.byHand();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return true;
                    int dx = Math.round(e.getRawX() - downX);
                    int dy = Math.round(e.getRawY() - downY);
                    if (Math.abs(dx) < SLOP_PX && Math.abs(dy) < SLOP_PX) return true;
                    propose(s, dx, dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (dragging) {
                        int dx = Math.round(e.getRawX() - downX);
                        int dy = Math.round(e.getRawY() - downY);
                        propose(s, dx, dy);
                        DexLog.step("edges", "resize by hand: band=" + which
                                + " dx=" + dx + " dy=" + dy
                                + (e.getActionMasked() == MotionEvent.ACTION_CANCEL
                                        ? " (cancelled)" : ""));
                    }
                    dragging = false;
                    return true;
                }
                default:
                    return false;
            }
        }

        private void propose(Sink s, int dx, int dy) {
            int[] lim = s.limits();
            float d = density();
            int minW = Math.round(220 * d);
            int minH = Math.round(140 * d);
            int nl = l, nt = t, nr = r, nb = b;
            if (which == RIGHT) {
                nr = Math.max(r + dx, l + minW);
            } else {
                nr = Math.max(r + dx, l + minW);
                nb = Math.max(b + dy, t + minH);
            }
            if (lim != null) {
                nr = Math.min(nr, lim[2]);
                nb = Math.min(nb, lim[3]);
            }
            if (nr - nl < minW) nr = nl + minW;
            if (nb - nt < minH) nb = nt + minH;
            send(s, nl, nt, nr, nb);
        }

        private void send(Sink s, int nl, int nt, int nr, int nb) {
            wantL = nl;
            wantT = nt;
            wantR = nr;
            wantB = nb;
            if (pending) return;
            pending = true;
            // One frame per UI turn: the pointer can outrun a socket round trip,
            // but it must not queue frames behind one (WmClient.drag is
            // newest-wins for exactly this reason).
            views[which].post(() -> {
                pending = false;
                Sink now = sink;
                if (now != null) now.apply(wantL, wantT, wantR, wantB);
            });
        }
    }
}
