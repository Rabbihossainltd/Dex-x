package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The two things a user sees before a window lands somewhere: the ghost that
 * shows where it will go while it is being dragged, and the layout picker behind
 * a long press on the maximise button.
 *
 * <p>Both are windows of OURS rather than views inside the app being moved —
 * they have to be, because the thing they draw on top of is another app's
 * surface. They are {@code TYPE_ACCESSIBILITY_OVERLAY} windows on the desktop's
 * display, created from a window context of that display, which is the same
 * route {@link CaptionService}'s anchor takes and the only one an
 * AccessibilityService may use without SYSTEM_ALERT_WINDOW.
 *
 * <p>Everything here is best-effort by design: a display that refuses the window
 * (an untrusted display on Samsung withholds overlays) leaves snapping exactly
 * as it was — the rect is still applied on release, there is simply no ghost in
 * front of it.
 */
final class SnapOverlay {

    private static final String TAG = "dex-snap";

    /** What a tap in the picker does — the service applies the rect. */
    interface Picker {
        void onZone(int zone);
    }

    private final AccessibilityService svc;

    /** The one window we may have up: a ghost or a picker, never both. */
    private View window;
    private WindowManager windowManager;
    private int windowDisplay = -1;
    private boolean picker;

    /** The ghost. Kept across a drag; moved by {@link #preview}. */
    private Ghost ghost;

    SnapOverlay(AccessibilityService service) {
        this.svc = service;
    }

    // ── the drag ghost ──────────────────────────────────────────────────────

    /**
     * Show — or move — the ghost over {@code rect}, or take it away when rect is
     * null or already gone.
     *
     * <p>Called on every frame of a drag that is near an edge, so it updates the
     * existing window in place rather than removing and re-adding it: a window
     * added per frame is a window the compositor has to relayout per frame, and
     * the ghost would visibly lag the pointer.
     */
    void preview(int displayId, int[] rect) {
        if (rect == null) {
            hidePreview();
            return;
        }
        try {
            if (window != null && (picker || windowDisplay != displayId)) detach();
            if (window == null) {
                if (!open(displayId, false)) return;
                ghost = new Ghost(window.getContext());
                window = ghost;
                addWindow(window, rect[2] - rect[0], rect[3] - rect[1]);
            }
            if (!(window instanceof Ghost)) return;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) window.getLayoutParams();
            lp.x = rect[0];
            lp.y = rect[1];
            lp.width = Math.max(1, rect[2] - rect[0]);
            lp.height = Math.max(1, rect[3] - rect[1]);
            windowManager.updateViewLayout(window, lp);
        } catch (Throwable t) {
            Log.w(TAG, "ghost unavailable on display " + displayId + ": " + t);
            detach();
        }
    }

    void hidePreview() {
        if (window instanceof Ghost) detach();
    }

    // ── the layout picker ──────────────────────────────────────────────────

    /**
     * Show the snap layouts: the display in miniature, cut into the quarters a
     * corner gives, with the two halves spelled out underneath.
     *
     * @param anchorX where the picker should point — the middle of the window's
     *                title bar, in display pixels
     * @param anchorY just below it, so the picker never covers the button that
     *                opened it
     */
    void picker(int displayId, int[] size, int bottomPx, int anchorX, int anchorY, Picker cb) {
        detach();
        try {
            if (!open(displayId, true)) return;
            Context ctx = windowManager == null ? null : windowContext;
            if (ctx == null) return;

            DexTheme theme = DexTheme.of(svc);
            int pad = dp(12);
            int gap = dp(6);
            int panelW = Math.min(dp(360), Math.max(dp(240), size[0] / 3));

            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(0x00000000);
            // Anywhere outside the panel is a dismiss, like a popup's outside tap.
            root.setOnClickListener(v -> detach());

            LinearLayout panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(pad, pad, pad, pad);
            GradientDrawable card = new GradientDrawable();
            card.setColor(theme.cardSolid);
            card.setCornerRadius(dp(14));
            card.setStroke(dp(1), theme.divider);
            panel.setBackground(card);
            panel.setElevation(dp(16));
            panel.setOnClickListener(v -> { /* swallow: a tap on the card is not a dismiss */ });

            TextView title = new TextView(ctx);
            title.setText(svc.getString(R.string.lx_snap_title));
            title.setTextColor(theme.textDim);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11.5f));
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            panel.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // The miniature: the display's own shape, quartered. Each tile is
            // one drop zone, and the tile's own rect on screen says which —
            // top-left tile is the top-left quarter, and so on.
            int miniW = panelW - 2 * pad;
            int miniH = Math.max(dp(90), Math.round((float) miniW * bottomPx
                    / Math.max(1, size[0])));
            FrameLayout mini = new FrameLayout(ctx);
            LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(miniW, miniH);
            miniLp.topMargin = dp(10);
            panel.addView(mini, miniLp);

            int tileW = (miniW - gap) / 2;
            int tileH = (miniH - gap) / 2;
            int[][] quads = {
                    {0, 0, SnapZones.TOP_LEFT},
                    {1, 0, SnapZones.TOP_RIGHT},
                    {0, 1, SnapZones.BOTTOM_LEFT},
                    {1, 1, SnapZones.BOTTOM_RIGHT},
            };
            for (int[] q : quads) {
                View tile = zoneTile(ctx, theme, false);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(tileW, tileH);
                lp.leftMargin = q[0] * (tileW + gap);
                lp.topMargin = q[1] * (tileH + gap);
                tile.setOnClickListener(v -> {
                    cb.onZone(q[2]);
                    detach();
                });
                mini.addView(tile, lp);
            }

            LinearLayout halves = new LinearLayout(ctx);
            halves.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams halvesLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            halvesLp.topMargin = dp(10);
            panel.addView(halves, halvesLp);

            String[] labels = {
                    svc.getString(R.string.lx_snap_left), svc.getString(R.string.lx_snap_right),
            };
            int[] zones = {SnapZones.LEFT, SnapZones.RIGHT};
            for (int i = 0; i < zones.length; i++) {
                final int zone = zones[i];
                TextView b = new TextView(ctx);
                b.setText(labels[i]);
                b.setGravity(Gravity.CENTER);
                b.setTextColor(theme.text);
                b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
                b.setBackground(zoneTile(ctx, theme, true).getBackground());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (i == 0) lp.rightMargin = gap;
                b.setOnClickListener(v -> {
                    cb.onZone(zone);
                    detach();
                });
                halves.addView(b, lp);
            }

            FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                    panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
            // Centred on the title bar, and never off the display.
            int x = Math.max(dp(8), Math.min(anchorX - panelW / 2, size[0] - panelW - dp(8)));
            panelLp.leftMargin = x;
            panelLp.topMargin = Math.max(dp(8), anchorY);
            root.addView(panel, panelLp);

            window = root;
            picker = true;
            addWindow(window, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        } catch (Throwable t) {
            Log.w(TAG, "picker unavailable on display " + displayId + ": " + t);
            detach();
        }
    }

    /** One tappable zone tile: a soft fill that lights up under the pointer. */
    private View zoneTile(Context ctx, DexTheme theme, boolean row) {
        View v = new View(ctx);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable hot = rounded(theme.accentSoft, theme.accent, dp(1));
        GradientDrawable idle = rounded(theme.field, theme.divider, dp(1));
        bg.addState(new int[]{android.R.attr.state_pressed}, hot);
        bg.addState(new int[]{android.R.attr.state_hovered}, hot);
        bg.addState(new int[0], idle);
        v.setBackground(bg);
        if (row) v.setClickable(true);
        return v;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(10));
        d.setStroke(strokePx, stroke);
        return d;
    }

    // ── window plumbing ────────────────────────────────────────────────────

    private Context windowContext;

    /** Create the display's window context and remember its WindowManager. */
    private boolean open(int displayId, boolean touchable) {
        try {
            DisplayManager dm = svc.getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(displayId);
            if (d == null) return false;
            Context base = svc.createDisplayContext(d);
            Context ctx;
            if (Build.VERSION.SDK_INT >= 30) {
                ctx = base.createWindowContext(
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            } else {
                ctx = base;
            }
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            if (wm == null) return false;
            windowContext = ctx;
            windowManager = wm;
            windowDisplay = displayId;
            picker = touchable;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "no window context for display " + displayId + ": " + t);
            return false;
        }
    }

    private void addWindow(View v, int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(1, w), Math.max(1, h),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never focusable: the app being dragged keeps the keyboard.
                // Touchable only for the picker — the ghost must let clicks
                // through to whatever is under it, or it would swallow the very
                // release that is supposed to complete the drag.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | (picker ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(v, lp);
    }

    /** Take whatever window is up down. Safe to call at any time. */
    void detach() {
        View v = window;
        window = null;
        ghost = null;
        picker = false;
        if (v == null || windowManager == null) return;
        try {
            windowManager.removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
        windowManager = null;
        windowContext = null;
        windowDisplay = -1;
    }

    /** Is a ghost on screen right now? */
    boolean previewing() {
        return window instanceof Ghost;
    }

    /**
     * dp in the DESKTOP's density, not the phone's.
     *
     * The service's own Resources describe the phone panel; this overlay is laid
     * out on the desktop display, whose density is whatever "Display size" put
     * there. Measuring with the phone's would draw the picker at a third of its
     * size on a 1920x1080 desktop and twice its size on a phone-shaped one.
     */
    private int dp(int v) {
        return Math.round(v * density());
    }

    private float sp(float v) {
        Resources r = resources();
        return v * r.getDisplayMetrics().scaledDensity;
    }

    private float density() {
        return resources().getDisplayMetrics().density;
    }

    private Resources resources() {
        Context c = windowContext != null ? windowContext : svc;
        return c.getResources();
    }

    /**
     * The ghost itself: the zone's rect, tinted, so the window being dragged has
     * somewhere obvious to land.
     */
    private final class Ghost extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Ghost(Context ctx) {
            super(ctx);
            DexTheme theme = DexTheme.of(svc);
            fill.setColor(theme.accent);
            fill.setAlpha(56);
            edge.setColor(theme.accent);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(2));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas c) {
            float inset = dp(1);
            float r = dp(12);
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            c.drawRoundRect(box, r, r, fill);
            c.drawRoundRect(box, r, r, edge);
        }
    }
}
