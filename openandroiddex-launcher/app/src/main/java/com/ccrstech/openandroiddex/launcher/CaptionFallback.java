package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Title bars for every window, without the accessibility service.
 *
 * <p>WHY THIS EXISTS. Every caption this project has ever drawn was drawn by
 * {@link CaptionService}, and that is an accessibility service: it needs the
 * phone to BIND it, and it needs the phone to hand it the window list of a
 * display it does not own. Three things break that and none of them is our
 * code — Android 13 and later keep a sideloaded app's service switched off
 * behind "Allow restricted settings"; some OEMs refuse to bind a service whose
 * app was reinstalled; and a bound service can be given an empty window list
 * for a secondary display, which is exactly what all of this work has been
 * built on. Whatever the cause, the symptom is total and identical: no title
 * bar on any window, so no minimise, no close, no maximise and no resize
 * anywhere — while the platform's own title bar is still on screen, which makes
 * it look as though the feature was never built at all.
 *
 * <p>So this is a second, independent renderer with a different dependency. It
 * takes the task list from the window daemon over the same socket everything
 * else uses ({@link WmClient#list}), reserves each window's strip ({@link
 * WmClient#strip}) and draws the bar as a plain {@code TYPE_APPLICATION_OVERLAY}
 * window out of the launcher process — the one overlay path this app is known
 * to have, because the taskbar itself is drawn that way and is on screen every
 * session. No accessibility service, no a11y window list, nothing the phone can
 * refuse.
 *
 * <p>It draws only while {@link CaptionService#problem()} says the real caption
 * cannot, and {@link CaptionService} skips any task that has a bar from here,
 * so the two can never put two title bars on one window.
 */
final class CaptionFallback {

    /** Caption strip reserved for our own bar, in dp — the same 40 the service uses. */
    static final int CAPTION_DP = 40;
    /** Poll cadence. The daemon answers a LIST in about 2.5 ms; 250 ms is idle enough. */
    private static final long POLL_MS = 250L;
    /** Cadence while a finger is down on a bar or a grip. */
    private static final long POLL_BUSY_MS = 60L;
    /** How far a resize grip reaches out, in dp. */
    private static final int EDGE_DP = 10;

    private static CaptionFallback instance;

    static synchronized CaptionFallback get(Context ctx) {
        if (instance == null) instance = new CaptionFallback(ctx.getApplicationContext());
        return instance;
    }

    /** Give the fallback the desktop's display and start it. Called by the launcher. */
    static void attach(Context displayContext) {
        CaptionFallback f = get(displayContext);
        f.host = displayContext;
        f.kick();
    }

    static void detach() {
        CaptionFallback f = instance;
        if (f != null) f.stop();
    }

    /** How many bars from here are on screen — the diagnostics screen's number. */
    static int barCount() {
        CaptionFallback f = instance;
        return f == null ? 0 : f.bars.size();
    }

    /** Whether a bar from here is on screen for {@code taskId} right now. */
    static boolean hasBar(int taskId) {
        CaptionFallback f = instance;
        return f != null && f.bars.containsKey(taskId);
    }

    // ── state ─────────────────────────────────────────────────────────────

    private final Context app;
    private volatile Context host;
    private final WmClient wm = new WmClient();
    final Map<Integer, Bar> bars = new ConcurrentHashMap<>();
    private final HandlerThread thread;
    private final Handler worker;
    private volatile boolean running;
    private int display = -1;
    private long pausedUntil;
    private long hostWaitedSince;

    private CaptionFallback(Context app) {
        this.app = app;
        thread = new HandlerThread("caption-fallback");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void kick() {
        if (running) return;
        running = true;
        worker.post(this::loop);
    }

    void stop() {
        running = false;
        worker.post(() -> {
            for (Bar b : new ArrayList<>(bars.values())) remove(b);
            bars.clear();
        });
    }

    // ── the loop ──────────────────────────────────────────────────────────

    private void loop() {
        if (!running) return;
        long started = SystemClock.uptimeMillis();
        try {
            pass();
        } catch (Throwable t) {
            DexLog.warn("caption", "fallback pass failed: " + t);
        }
        if (!running) return;
        long spent = SystemClock.uptimeMillis() - started;
        long cadence = SystemClock.uptimeMillis() < pausedUntil ? POLL_BUSY_MS : POLL_MS;
        worker.postDelayed(this::loop, Math.max(16, cadence - spent));
    }

    private void pass() {
        // Per TASK, not per session. The real caption is the better renderer
        // and wins every window it can dress, however the others are doing; a
        // single window it cannot host must not take the whole desktop off it.
        // (Withdrawn bars come back the moment the service dresses those tasks
        // again — see the loop below.)
        boolean service = CaptionService.captionAvailable();
        Context wc = host;
        if (wc == null) return;
        if (display < 0) {
            display = wm.desktopDisplay();
            if (display < 0) {
                long now = SystemClock.uptimeMillis();
                if (hostWaitedSince == 0) hostWaitedSince = now;
                if (now - hostWaitedSince > 8000 && now - hostWaitedSince < 8600) {
                    DexLog.warn("caption", "fallback: the daemon knows no desktop display yet");
                }
                return;
            }
            DexLog.step("caption", "fallback captions on display " + display);
        }
        List<WmClient.Task> tasks = wm.list(display);
        if (tasks == null) return;             // daemon away — leave what is up on screen
        int px = Math.round(CAPTION_DP * density(wc));
        List<Integer> live = new ArrayList<>();
        for (WmClient.Task t : tasks) {
            if (!t.visible || !t.isFreeform()) continue;
            if (app.getPackageName().equals(t.pkg)) {
                // Our own settings and file-transfer windows are ordinary
                // windows on this display; they get a bar like any other.
                if (!(SettingsActivity.class.getName().equals(t.activity)
                        || TaskManagerActivity.class.getName().equals(t.activity)
                        || FileTransferActivity.class.getName().equals(t.activity))) {
                    continue;                  // the desktop itself is the background
                }
            }
            if (t.right - t.left < 120 || t.bottom - t.top < px + 60) continue;
            // Not ours while the accessibility caption is healthy: it is the
            // better renderer, and racing it on every launch would put two bars
            // on each window for the second it takes to attach. Only a task it
            // has left undressed for the whole grace is taken over.
            if (service && !CaptionService.stalledTask(t.taskId)) {
                Bar held = bars.remove(t.taskId);
                if (held != null) {
                    remove(held);
                    DexLog.step("caption", "task " + t.taskId + " back to the accessibility caption");
                }
                continue;
            }
            live.add(t.taskId);
            // The same rule the accessibility renderer follows (see
            // WmClient.Task#platformCaption): where the platform has already inset the
            // task for its own caption, our bar is exactly that tall and we reserve
            // nothing ourselves. Two insets is the content pushed down with the empty
            // black band under it; a bar of the wrong height is the platform's buttons
            // showing under ours.
            final int platformPx = t.platformCaption();
            final int barPx = platformPx > 0 ? platformPx : px;
            // Reserve only where NOTHING is reserved — never release. The release
            // bookkeeping lives in CaptionService, which knows which strip it asked for;
            // a second renderer releasing them would be a strip/unstrip ping-pong on one
            // task's app bounds, and the window would flicker forever.
            if (platformPx == 0 && t.appTop == t.top) wm.strip(display, t.taskId, px);
            Bar b = bars.get(t.taskId);
            if (b == null) {
                b = add(wc, t, barPx);
                if (b == null) continue;
                bars.put(t.taskId, b);
                DexLog.step("caption", "fallback bar task=" + t.taskId + " " + t.pkg
                        + " " + (t.right - t.left) + "x" + (t.bottom - t.top));
            }
            b.sync(t, barPx);
        }
        for (Bar b : new ArrayList<>(bars.values())) {
            if (!live.contains(b.taskId)) {
                remove(b);
                bars.remove(b.taskId);
            }
        }
    }

    private float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    // ── one bar ───────────────────────────────────────────────────────────

    /** The caption strip drawn for one task. */
    final class Bar {
        final int taskId;
        final String pkg;
        final WindowManager wmgr;
        final FrameLayout root;
        final TextView title;
        final WindowManager.LayoutParams lp;
        final List<Band> bands = new ArrayList<>();
        int left, top, right, bottom;

        Bar(Context ctx, WmClient.Task t, int px) {
            taskId = t.taskId;
            pkg = t.pkg;
            wmgr = ctx.getSystemService(WindowManager.class);
            root = build(ctx, t, px);
            lp = params(t.left, t.top, Math.max(1, t.right - t.left), px);
            title = (TextView) root.getChildAt(0);
            left = t.left;
            top = t.top;
            right = t.right;
            bottom = t.bottom;
        }

        void sync(WmClient.Task t, int px) {
            right = t.right;
            bottom = t.bottom;
            if (t.left != left || t.top != top || lp.width != Math.max(1, right - left)) {
                left = t.left;
                top = t.top;
                lp.x = left;
                lp.y = top;
                lp.width = Math.max(1, right - left);
                lp.height = px;
                try {
                    wmgr.updateViewLayout(root, lp);
                } catch (Throwable ignored) {
                }
                for (Band b : bands) b.place(this);
            }
            CharSequence label = labelFor(t.pkg);
            if (title != null && !label.toString().contentEquals(title.getText())) title.setText(label);
        }
    }

    private WindowManager.LayoutParams params(int x, int y, int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    private CharSequence labelFor(String pkg) {
        try {
            PackageManager pm = app.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (Throwable e) {
            return pkg;
        }
    }

    private Bar add(Context ctx, WmClient.Task t, int px) {
        try {
            Bar b = new Bar(ctx, t, px);
            b.wmgr.addView(b.root, b.lp);
            bandsFor(ctx, b);
            return b;
        } catch (Throwable e) {
            DexLog.warn("caption", "fallback could not add a bar for task " + t.taskId + ": " + e);
            return null;
        }
    }

    private void remove(Bar b) {
        for (Band band : b.bands) band.remove();
        b.bands.clear();
        try {
            if (b.root.isAttachedToWindow()) b.wmgr.removeViewImmediate(b.root);
        } catch (Throwable ignored) {
        }
    }

    // ── the bar's own view ────────────────────────────────────────────────

    private FrameLayout build(Context ctx, WmClient.Task t, int px) {
        float d = density(ctx);
        final int taskId = t.taskId;

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0xFF202124);
        root.setTag(taskId);

        TextView label = new TextView(ctx);
        label.setTextColor(0xFFE8EAED);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * d);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(Math.round(12 * d), 0, Math.round(8 * d), 0);
        root.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        buttons.addView(button(ctx, "\u2014", 0xFFE8EAED, v -> minimise(taskId)));
        buttons.addView(button(ctx, "\u25A1", 0xFFE8EAED, v -> toggleMax(taskId)));
        buttons.addView(button(ctx, "\u2715", 0xFFFF8A80, v -> close(taskId)));

        Drag drag = new Drag(taskId);
        root.setOnTouchListener(drag);
        root.setClickable(true);
        return root;
    }

    private View button(Context ctx, String glyph, int colour, View.OnClickListener onClick) {
        float d = density(ctx);
        TextView b = new TextView(ctx);
        b.setText(glyph);
        b.setTextColor(colour);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * d);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(false);
        b.setLayoutParams(new LinearLayout.LayoutParams(Math.round(44 * d),
                ViewGroup.LayoutParams.MATCH_PARENT));
        b.setOnClickListener(onClick);
        return b;
    }

    private void minimise(int taskId) {
        worker.post(() -> {
            wm.hide(display, taskId);
            Bar b = bars.remove(taskId);
            if (b != null) remove(b);
        });
    }

    private void close(int taskId) {
        worker.post(() -> {
            wm.close(taskId);
            Bar b = bars.remove(taskId);
            if (b != null) remove(b);
        });
    }

    private void toggleMax(int taskId) {
        worker.post(() -> {
            Bar b = bars.get(taskId);
            if (b == null) return;
            int[] max = deskRect();
            boolean isMax = Math.abs(b.left - max[0]) < 8 && Math.abs(b.right - max[2]) < 8
                    && Math.abs(b.top - max[1]) < 8;
            if (isMax) {
                int w = (int) ((max[2] - max[0]) * 0.62f);
                int h = (int) ((max[3] - max[1]) * 0.62f);
                int l = max[0] + ((max[2] - max[0]) - w) / 2;
                int t = max[1] + ((max[3] - max[1]) - h) / 2;
                wm.resize(display, taskId, l, t, l + w, t + h, b.lp.height);
            } else {
                wm.resize(display, taskId, max[0], max[1], max[2], max[3], b.lp.height);
            }
        });
    }

    /** The desktop's working area: the display less the taskbar along the bottom. */
    private int[] deskRect() {
        int w = 1080, h = 1920;
        float d = 1f;
        try {
            DisplayManager dm = app.getSystemService(DisplayManager.class);
            Display disp = dm == null ? null : dm.getDisplay(display);
            if (disp != null) {
                DisplayMetrics m = new DisplayMetrics();
                disp.getRealMetrics(m);
                w = m.widthPixels;
                h = m.heightPixels;
                d = m.density;
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0, w, h - Math.round(LauncherActivity.TASKBAR_DP * d)};
    }

    // ── resize bands ──────────────────────────────────────────────────────

    private void bandsFor(Context ctx, Bar b) {
        // Right, bottom and the corner: where a pointer goes to resize. A left
        // or top band would have to move the window as well as size it, and the
        // platform's own resize does the same three.
        b.bands.add(new Band(ctx, b, Band.RIGHT));
        b.bands.add(new Band(ctx, b, Band.BOTTOM));
        b.bands.add(new Band(ctx, b, Band.CORNER));
    }

    /** One invisible strip along an edge of a window that resizes it when dragged. */
    private final class Band implements View.OnTouchListener {
        static final int RIGHT = 1;
        static final int BOTTOM = 2;
        static final int CORNER = 3;

        final Bar bar;
        final int mode;
        final int size;
        final View view;
        final WindowManager wmgr;
        WindowManager.LayoutParams lp;
        private float downX, downY;
        private int startRight, startBottom;

        Band(Context ctx, Bar bar, int mode) {
            this.bar = bar;
            this.mode = mode;
            float d = density(ctx);
            size = Math.max(8, Math.round(EDGE_DP * d));
            view = new View(ctx);
            wmgr = bar.wmgr;
            view.setOnTouchListener(this);
            lp = params(0, 0, size, size);
            lp.alpha = 0.02f;
            place(bar);
            try {
                wmgr.addView(view, lp);
            } catch (Throwable e) {
                DexLog.warn("caption", "fallback band failed: " + e);
            }
        }

        void place(Bar b) {
            int px = b.lp.height;
            int w = Math.max(size, b.right - b.left - size);
            int h = Math.max(size, b.bottom - b.top - px * 2 - size);
            switch (mode) {
                case RIGHT:
                    lp.width = size;
                    lp.height = h;
                    lp.x = b.right - size;
                    // Below the bar — ONE bar-height, not two. The `px * 2`
                    // here left a 40 dp strip directly under the title bar
                    // that resized nothing, so a pull that started where the
                    // caption ends did nothing at all.
                    lp.y = b.top + px;
                    break;
                case BOTTOM:
                    lp.width = w;
                    lp.height = size;
                    lp.x = b.left;
                    lp.y = b.bottom - size;
                    break;
                default:
                    lp.width = size * 2;
                    lp.height = size * 2;
                    lp.x = b.right - size * 2;
                    lp.y = b.bottom - size * 2;
                    break;
            }
            try {
                if (view.isAttachedToWindow()) wmgr.updateViewLayout(view, lp);
            } catch (Throwable ignored) {
            }
        }

        void remove() {
            try {
                if (view.isAttachedToWindow()) wmgr.removeViewImmediate(view);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startRight = bar.right;
                    startBottom = bar.bottom;
                    pausedUntil = SystemClock.uptimeMillis() + 10_000;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(e.getRawX() - downX);
                    int dy = Math.round(e.getRawY() - downY);
                    int right = mode == BOTTOM ? startRight : startRight + dx;
                    int bottom = mode == RIGHT ? startBottom : startBottom + dy;
                    right = Math.max(bar.left + 240, right);
                    bottom = Math.max(bar.top + bar.lp.height + 160, bottom);
                    wm.resize(display, bar.taskId, bar.left, bar.top, right, bottom, bar.lp.height);
                    bar.right = right;
                    bar.bottom = bottom;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pausedUntil = 0;
                    return true;
                default:
                    return false;
            }
        }
    }

    // ── drag the caption to move its window ───────────────────────────────

    private final class Drag implements View.OnTouchListener {
        private final int taskId;
        private float downX, downY;
        private int startLeft, startTop;
        private boolean moving;

        Drag(int taskId) {
            this.taskId = taskId;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            Bar b = bars.get(taskId);
            if (b == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startLeft = b.left;
                    startTop = b.top;
                    moving = false;
                    pausedUntil = SystemClock.uptimeMillis() + 10_000;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(e.getRawX() - downX);
                    int dy = Math.round(e.getRawY() - downY);
                    if (!moving && Math.abs(dx) + Math.abs(dy) < 6) return true;
                    moving = true;
                    int nx = Math.max(0, startLeft + dx);
                    int ny = Math.max(0, startTop + dy);
                    wm.move(display, taskId, nx, ny);
                    b.left = nx;
                    b.top = ny;
                    b.lp.x = nx;
                    b.lp.y = ny;
                    try {
                        b.wmgr.updateViewLayout(b.root, b.lp);
                    } catch (Throwable ignored) {
                    }
                    for (Band band : b.bands) band.place(b);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pausedUntil = 0;
                    return true;
                default:
                    return false;
            }
        }
    }
}
