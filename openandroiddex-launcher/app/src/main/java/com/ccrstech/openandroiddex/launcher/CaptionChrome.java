package com.ccrstech.openandroiddex.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * The frame a window's title bar is drawn in: the bar itself, plus the invisible
 * bands and the one visible grip that let a mouse or a finger RESIZE the window.
 *
 * <p>Why the bands live here and not on the desktop: our chrome is a child of the
 * app's own window surface ({@code attachAccessibilityOverlayToWindow}), and the
 * only part of that surface we are allowed to draw into is the strip the daemon
 * reserves at the top of the task. So the whole window's edges — which on a real
 * desktop would be eight hot zones around the whole frame — are, here, the two
 * vertical edges of that strip plus one corner grip. Between them they cover
 * every resize a person actually reaches for: left edge, right edge, and both
 * axes at once from the bottom-right.
 *
 * <p>That is the fix for "window resize kora jai na": before this, the strip had
 * snap and maximise buttons and no way at all to set an arbitrary size with the
 * pointer, which is the one thing a desktop window has to do.
 *
 * <p>Nothing here draws the bar — {@link CaptionService} builds that and hands it
 * in. This class only decides where the bar may be pressed and what a press
 * there does.
 */
final class CaptionChrome extends FrameLayout {

    /** How wide the invisible edge bands are, in dp of the desktop display. */
    private static final int BAND_DP = 8;

    /** What a resize gesture needs to know about the window it is resizing. */
    interface Resizer {
        /** The window's rect right now, as {left, top, right, bottom}. */
        int[] rect();

        /** The largest rect it may grow to: the display, less the taskbar. */
        int[] limits();

        /** Hand a proposed rect to the daemon. Called once per gesture frame at most. */
        void apply(int left, int top, int right, int bottom);

        /** The user started dragging an edge: the window is no longer maximised. */
        void onResizedByHand();

        /** dp → px on the desktop display. */
        int px(int dp);
    }

    /** Which edge a drag is pulling. */
    private static final int LEFT = 1, RIGHT = 2, CORNER = 3;

    private final Resizer resizer;

    CaptionChrome(Context ui, Resizer resizer) {
        super(ui);
        this.resizer = resizer;
        setClipChildren(false);
        setClipToPadding(false);
    }

    /**
     * Add the bar, then the bands over it.
     *
     * Order matters: a child added later is drawn and dispatched later, so the
     * bands take the press before the bar's own drag listener does. The bar is
     * inset by the band width on both sides so no button ever sits under one.
     */
    void setBar(View bar, int bandPx) {
        removeAllViews();
        bar.setPadding(Math.max(bandPx, bar.getPaddingLeft()), bar.getPaddingTop(),
                Math.max(bandPx, bar.getPaddingRight()), bar.getPaddingBottom());
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(band(LEFT), edgeParams(Gravity.START));
        addView(band(RIGHT), edgeParams(Gravity.END));
    }

    private LayoutParams edgeParams(int gravity) {
        LayoutParams lp = new LayoutParams(resizer.px(BAND_DP), LayoutParams.MATCH_PARENT);
        lp.gravity = gravity;
        return lp;
    }

    private View band(int edge) {
        View v = new View(getContext());
        v.setOnTouchListener(resizeHandler(edge));
        v.setPointerIcon(PointerIcon.getSystemIcon(getContext(),
                PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW));
        return v;
    }

    /**
     * The one visible resize handle: drag it and the window grows or shrinks
     * from its bottom-right corner.
     *
     * Made a real view rather than another invisible band because a person has
     * to be able to find it. Every desktop window manager in the world hides
     * this in a corner; here the corner we can reach is inside the title bar,
     * so it says so with a glyph.
     */
    static View grip(Context ui, Resizer res, CharSequence glyph, float textPx,
                     int colour, int sizePx) {
        android.widget.TextView g = new android.widget.TextView(ui);
        g.setText(glyph);
        g.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx);
        g.setGravity(Gravity.CENTER);
        g.setTextColor(colour);
        g.setOnTouchListener(resizeHandler(CORNER, res));
        g.setPointerIcon(PointerIcon.getSystemIcon(ui,
                PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW));
        LayoutParams lp = new LayoutParams(sizePx, LayoutParams.MATCH_PARENT);
        g.setLayoutParams(lp);
        return g;
    }

    // ── the gesture ─────────────────────────────────────────────────────────

    /**
     * One edge pull, coalesced the same way the move handler is: the newest
     * position always wins and none is dropped, because the last frame of a
     * gesture is the one that decides where the window ends up.
     */
    private View.OnTouchListener resizeHandler(int edge) {
        return resizeHandler(edge, resizer);
    }

    /** The gesture, for any resizer — the bands use this chrome's, the grip its own. */
    private static View.OnTouchListener resizeHandler(final int edge, final Resizer res) {
        return new View.OnTouchListener() {
            private int l, t, r, b;          // the rect at the start of the drag
            private float downX, downY;
            private boolean active;
            private boolean pending;
            private int wantL, wantT, wantR, wantB;
            /** The view the gesture is on: it is what a coalesced pump posts to. */
            private View owner;
            private int slop = -1;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (slop < 0) slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        int[] rect = res.rect();
                        if (rect == null) return false;
                        l = rect[0]; t = rect[1]; r = rect[2]; b = rect[3];
                        owner = v;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        active = true;
                        res.onResizedByHand();
                        // Claim the gesture so the bar underneath does not also
                        // start dragging the window by its title.
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (!active) return true;
                        int dx = Math.round(e.getRawX() - downX);
                        int dy = Math.round(e.getRawY() - downY);
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                        propose(edge, dx, dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (active) {
                            int dx = Math.round(e.getRawX() - downX);
                            int dy = Math.round(e.getRawY() - downY);
                            propose(edge, dx, dy);
                            // One line per gesture, and only on the diagnostics
                            // log: "the resize grip only works where it is
                            // drawn" cannot be settled from source alone — it
                            // depends on whether the platform keeps delivering
                            // MOVEs to a 40 dp overlay after the pointer leaves
                            // it, which differs by device. With this, the next
                            // report has a number: what the gesture actually
                            // moved, and whether the drag was CANCELLED.
                            DexLog.step("caption", "resize by hand: edge=" + edge
                                    + " dx=" + dx + " dy=" + dy
                                    + (e.getActionMasked() == MotionEvent.ACTION_CANCEL
                                            ? " (cancelled)" : ""));
                        }
                        active = false;
                        return true;
                    }
                    default:
                        return false;
                }
            }

            /**
             * Turn the pointer's travel into a rect, clamped to the display and
             * to a floor size — a window may not be dragged so small that its
             * own title bar no longer fits, or the controls to grow it back
             * would be clipped away with it.
             */
            private void propose(int which, int dx, int dy) {
                int[] lim = res.limits();
                int minW = res.px(220);
                int minH = res.px(140);
                int nl = l, nt = t, nr = r, nb = b;
                if (which == LEFT) {
                    nl = Math.min(l + dx, r - minW);
                } else if (which == RIGHT) {
                    nr = Math.max(r + dx, l + minW);
                } else {
                    nr = Math.max(r + dx, l + minW);
                    nb = Math.max(b + dy, t + minH);
                }
                if (lim != null) {
                    nl = Math.max(nl, lim[0]);
                    nt = Math.max(nt, lim[1]);
                    nr = Math.min(nr, lim[2]);
                    nb = Math.min(nb, lim[3]);
                }
                if (nr - nl < minW) {
                    if (which == LEFT) nl = nr - minW; else nr = nl + minW;
                }
                if (nb - nt < minH) nb = nt + minH;
                send(nl, nt, nr, nb);
            }

            private void send(int nl, int nt, int nr, int nb) {
                wantL = nl; wantT = nt; wantR = nr; wantB = nb;
                if (pending) return;
                pending = true;
                owner.post(this::pump);
            }

            private void pump() {
                pending = false;
                res.apply(wantL, wantT, wantR, wantB);
            }
        };
    }

    /**
     * Hover over an edge should say so. Resolved rather than set per-view where
     * it can be, because the SurfaceControlViewHost boundary is the one place
     * where hover may not arrive at all — if it does not, the system arrow
     * simply stands and nothing else depends on it.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent ev, int pointerIndex) {
        return super.onResolvePointerIcon(ev, pointerIndex);
    }

    /** Exposed for tests and for the bar's own padding arithmetic. */
    static int bandDp() {
        return BAND_DP;
    }

    /** Kept for the pixel format constant the caption's host expects. */
    static int translucent() {
        return PixelFormat.TRANSLUCENT;
    }
}
