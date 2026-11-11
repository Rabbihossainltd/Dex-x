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

    // ── windows THIS renderer hid ──────────────────────────────────────────
    //
    // With the accessibility service off — the normal state for a sideloaded app on
    // Android 13+ until "Allow restricted settings" is granted — THIS is the renderer
    // that draws the title bars, so this is the one whose "—" hides a window. And
    // hiding it here told nobody: CaptionService.openPkgs() answers empty with no
    // service, publishMinimised() never runs, and the PC's broadcast is built from
    // visibility, so the window was in none of the three lists the taskbar merges.
    // The tile vanished the moment it was minimised and the window could not be
    // reached again by anything on screen — the "recent app hide hoye jai" report,
    // in the one configuration most installs actually run.
    //
    // So the hider records what it hid, and the taskbar asks it.

    /** One window we hid: what it was, and whether the daemon still has it. */
    private static final class Hid {
        final String pkg;
        final int display;
        long goneSince;                        // first pass it was missing, 0 = present
        long askedAt;                          // last time the daemon was asked
        /** How many times it has been asked and still existed — see {@link #askDelay}. */
        int asks;

        Hid(String pkg, int display) {
            this.pkg = pkg;
            this.display = display;
        }

        /**
         * How long to wait before asking the daemon again.
         *
         * <p>On the phones where a hidden task drops out of the root list, the window is
         * missing from every pass while it is minimised — a window can be minimised for
         * hours. Asking every two and a half seconds for that long is a socket round trip
         * every two and a half seconds, forever, to learn the same answer. It doubles to a
         * minute instead, and a single answer of "gone" ends it.
         */
        long askDelay() {
            int shift = Math.min(asks, 5);                 // 2.5s, 5, 10, 20, 40, 60
            return Math.min(60_000L, GRACE_FORGET_MS << shift);
        }
    }

    private final Map<Integer, Hid> hidden = new ConcurrentHashMap<>();
    private static final java.util.List<Runnable> watchers = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Told when the hidden set changes, so the taskbar can rebuild its row. */
    static void watch(Runnable r) {
        if (r != null && !watchers.contains(r)) watchers.add(r);
    }

    static void unwatch(Runnable r) {
        watchers.remove(r);
    }

    /**
     * Watchers rebuild the taskbar, and a taskbar is views — so every notification
     * lands on the main thread, whatever thread noticed the change.
     *
     * <p>Both the hide and the reconcile run on this class's worker thread; only a tile
     * click arrives on the main thread. Calling a view-touching watcher straight from
     * the worker is `CalledFromWrongThreadException` at the next minimise — which is
     * exactly the path this whole feature exists to serve, so "it works when I look at
     * the tidy case" would have been a crash in the only case that mattered.
     */
    private static void notifyWatchers() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            main.post(CaptionFallback::fireWatchers);
            return;
        }
        fireWatchers();
    }

    private static void fireWatchers() {
        for (Runnable r : watchers) {
            try {
                r.run();
            } catch (RuntimeException e) {
                DexLog.warn("caption", "a hidden-window watcher failed: " + e);
            }
        }
    }

    /** The main looper, for handing work back to the thread that owns the views. */
    private static final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Packages with a window we hid that is still open — for the taskbar. */
    static java.util.Set<String> hiddenPackages() {
        CaptionFallback f = instance;
        if (f == null || f.hidden.isEmpty()) return java.util.Collections.emptySet();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Hid h : f.hidden.values()) if (h.pkg != null && !h.pkg.isEmpty()) out.add(h.pkg);
        return out;
    }

    /**
     * Un-hide {@code pkg}'s window — what a click on its taskbar tile means.
     *
     * @return false when this renderer is not holding a hidden window of that package,
     *         and the caller should do whatever it would have done anyway.
     */
    static boolean restore(String pkg) {
        final CaptionFallback f = instance;
        if (f == null || pkg == null) return false;
        for (Map.Entry<Integer, Hid> e : f.hidden.entrySet()) {
            if (!pkg.equals(e.getValue().pkg)) continue;
            final int id = e.getKey();
            // The live display, not the one recorded at hide time: a reconnect gives the
            // desktop a new id, and showing a window on the old one does nothing.
            final int display = f.display >= 0 ? f.display : e.getValue().display;
            f.hidden.remove(id);
            f.worker.post(() -> f.wm.show(display, id));
            notifyWatchers();
            return true;
        }
        return false;
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
    /** How long a hidden task may be missing before the daemon is asked if it is gone. */
    private static final long GRACE_FORGET_MS = 2_500L;

    /** How long a daemon caption probe is trusted, ms. A dumpsys is not free. */
    private static final long PROBE_TTL_MS = 4000L;
    /** Per task: {caption px, uptime of the probe} — see seenCaption(). */
    private final Map<Integer, long[]> probed = new ConcurrentHashMap<>();
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
        // A reconnect creates a NEW display id. Keeping the old one meant every later
        // list() asked about a display that no longer exists and returned null forever.
        display = -1;
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

        // The bottom-most root task is the desktop itself, and the desktop must never
        // wear a bar — not the platform's and not ours. CaptionService skips it too.
        final int baseId = tasks.isEmpty() ? -1 : tasks.get(tasks.size() - 1).taskId;

        // EVERY visible window first, in the daemon's topmost-first order, so the
        // "is this caption covered" test below can see windows this loop later skips.
        // (0.7.20 built that list inside the decision loop, so a window skipped for its
        // package or its size could not occlude — and a bar floated over the window in
        // front.) A failed probe used to be invisible for the same reason.
        final List<WmClient.Task> showing = new ArrayList<>();
        for (WmClient.Task t : tasks) {
            if (!t.visible || !t.isFreeform()) continue;
            if (t.taskId != baseId) showing.add(t);
        }

        List<Integer> live = new ArrayList<>();
        for (int ix = 0; ix < showing.size(); ix++) {
            WmClient.Task t = showing.get(ix);
            if (app.getPackageName().equals(t.pkg) && !isChrome(t)) {
                continue;                       // the desktop itself is the background
            }
            if (t.right - t.left < 120 || t.bottom - t.top < px + 60) continue;

            // WHO DRAWS THIS WINDOW — CaptionRule.owner(), which a test drives.
            //
            // The accessibility renderer puts its bar inside a host window's surface
            // tree, and the platform's caption is a surface above the app's: a bar drawn
            // there always lands BELOW the platform's. A bar drawn HERE is a top-level
            // window of ours, above every app in the display: the one thing that covers
            // a caption. Both shapes of the caption are measured — the app-bounds inset
            // this renderer can read itself, and a caption WINDOW, which needs a witness.
            final int insetPx = t.platformCaption();
            final int seenPx = seenCaption(t, service);
            final int platformPx = CaptionRule.captionPx(insetPx, seenPx);
            // WHERE the platform's caption is decides where ours goes. An inset pushes the
            // app's content down inside the task, so the strip to cover starts at the task's
            // top. A caption WINDOW sits above the task's frame, so covering it means
            // drawing above that frame — put the bar at `top` there and it lands UNDER the
            // platform's caption (two bars) while covering the app's own first rows.
            final int shape = CaptionRule.captionShape(insetPx, seenPx, t.appTop == t.top);
            final int barPx = platformPx > 0 ? platformPx : px;
            // Never above the display: a caption window on a task flush with the top
            // edge is partly off-screen, and a bar at a negative y is not drawn at all.
            final int barY = Math.max(0, CaptionRule.barTop(t.top, shape, platformPx));

            final android.graphics.Rect capRect = new android.graphics.Rect(t.left, barY,
                    t.right, barY + Math.max(1, barPx));
            boolean covered = false;
            for (int jx = 0; jx < ix; jx++) {   // the windows above this one
                WmClient.Task a = showing.get(jx);
                if (android.graphics.Rect.intersects(
                        new android.graphics.Rect(a.left, a.top, a.right, a.bottom), capRect)) {
                    covered = true;
                    break;
                }
            }

            final int owner = CaptionRule.owner(platformPx, service,
                    CaptionService.stalledTask(t.taskId), covered);
            if (owner != CaptionRule.OVERLAY) {
                Bar held = bars.remove(t.taskId);
                if (held != null) remove(held);      // the service draws it, or nothing does
                continue;
            }
            live.add(t.taskId);
            // Reserve only where NOTHING is reserved — never release. The release
            // bookkeeping lives in CaptionService, which knows which strip it asked for;
            // a second renderer releasing them would be a strip/unstrip ping-pong on one
            // task's app bounds, and the window would flicker forever.
            if (platformPx == 0 && t.appTop == t.top) wm.strip(display, t.taskId, px);
            Bar b = bars.get(t.taskId);
            if (b == null) {
                b = add(wc, t, barPx, barY);
                if (b == null) continue;
                bars.put(t.taskId, b);
                DexLog.step("caption", "fallback bar task=" + t.taskId + " " + t.pkg
                        + " " + (t.right - t.left) + "x" + (t.bottom - t.top)
                        + (platformPx > 0 ? " over the platform's " + platformPx + "px" : ""));
            }
            b.sync(t, barPx, barY);
        }
        for (Bar b : new ArrayList<>(bars.values())) {
            if (!live.contains(b.taskId)) {
                remove(b);
                bars.remove(b.taskId);
            }
        }
        // A closed window's probe cache is dead weight, and a reused task id must be
        // asked about again rather than inherit what the last window with that number
        // answered. Against every live task — NOT against `bars` (see ProbeCache).
        // One guard, inside ProbeCache, against the list the sweep actually uses.
        // Checking here with `showing` and sweeping there with `tasks` meant the outer
        // test could pass and the inner one still refuse, because `tasks` is never
        // smaller than `showing` — a guard that quietly stopped guarding.
        ProbeCache.sweep(probed, idsOf(tasks));

        coverHome(tasks, baseId, service);

        // The windows WE hid: a task that is visible again came back on its own (or
        // something else un-hid it), and a task the daemon no longer has is closed.
        // Anything else stays, which is what keeps its tile on the taskbar.
        //
        // "Not in this list" is NOT "closed": a hidden task drops out of the root list
        // on several phones, which is exactly the reading that used to make a
        // minimised tile disappear. So the first miss only starts a clock, and the
        // daemon is asked — once, not once per pass — whether the task still exists.
        if (!hidden.isEmpty()) {
            boolean changed = false;
            long now = SystemClock.uptimeMillis();
            for (Map.Entry<Integer, Hid> e : new ArrayList<>(hidden.entrySet())) {
                WmClient.Task t = null;
                for (WmClient.Task c : tasks) if (c.taskId == e.getKey()) { t = c; break; }
                Hid h = e.getValue();
                if (t == null) {
                    if (h.goneSince == 0) h.goneSince = now;
                    else if (now - h.goneSince > GRACE_FORGET_MS
                            && now - h.askedAt > h.askDelay()) {
                        h.askedAt = now;
                        h.asks++;
                        Boolean alive = wm.has(e.getKey());
                        if (Boolean.FALSE.equals(alive)) {
                            hidden.remove(e.getKey());
                            DexLog.step("caption", "fallback: task " + e.getKey() + " ("
                                    + h.pkg + ") was closed while minimised");
                            changed = true;
                        }
                    }
                } else if (t.visible) {
                    hidden.remove(e.getKey());     // it is back on screen
                    changed = true;
                } else {
                    h.goneSince = 0;               // hidden, and still there
                }
            }
            if (changed) notifyWatchers();
        }
    }

    /** The task ids on this display, for the probe cache sweep. */
    private static List<Integer> idsOf(List<WmClient.Task> tasks) {
        List<Integer> ids = new ArrayList<>(tasks.size());
        for (WmClient.Task t : tasks) ids.add(t.taskId);
        return ids;
    }

    // ── the desktop's own caption ─────────────────────────────────────────
    //
    // The home is never decorated by US — see the base-task rule above, and CaptionService's
    // isDesktopTask. That says nothing about the PLATFORM, though: on a display in desktop
    // mode it can still draw its own caption on the home task, and then the desktop has a
    // title strip with ▢ ✕ over the wallpaper: "home a old caption bar dekhai".
    //
    // pinHome() is the proper answer and runs every pass — UNFLOAT the task, and if the phone
    // will not take it out of freeform, replace the task with a fullscreen one. This is what
    // happens when even that fails: the strip is COVERED. Not a decoration of ours — the
    // removal the platform would not do. The cover has no buttons, because the rule for the
    // home is that it has nothing to close or minimise, and it swallows touches so the
    // platform's own ▢ ✕ under it cannot be pressed.

    private View homeCover;
    private WindowManager.LayoutParams homeCoverLp;
    private WindowManager homeCoverWm;

    private void coverHome(List<WmClient.Task> tasks, int baseId, boolean service) {
        WmClient.Task home = null;
        for (WmClient.Task t : tasks) {
            boolean ours = app.getPackageName().equals(t.pkg) && isHomeActivity(t.activity);
            if (ours || t.taskId == baseId) home = t;
            if (ours) break;
        }
        if (home == null) {
            dropHomeCover();
            return;
        }
        final int insetPx = home.platformCaption();
        // NOT seenCaption(home, service). That one trusts the accessibility service —
        // "the service can see, and it sees nothing" — but this service NEVER MEASURES
        // THE HOME: applyTasks skips it a few lines before the measurement, by design,
        // because the home must not be dressed. So its answer for the home is always
        // zero, and with the service bound it does not even fall through to the daemon.
        // The cover was therefore blind to the very caption it exists to cover, on every
        // phone where the service is on — which is why "home a old caption bar" survived
        // four releases of fixes that looked right.
        final int seenPx = probeDaemonCaption(home);
        final int px = CaptionRule.captionPx(insetPx, seenPx);
        // Nothing above the home's own content: the phone gave us the fullscreen that
        // pinHome asked for, and there is nothing to cover.
        if (px <= 0 && home.appTop == home.top) {
            dropHomeCover();
            return;
        }
        final int height = px > 0 ? px : Math.round(CAPTION_DP * density(app));
        final int shape = CaptionRule.captionShape(insetPx, seenPx, home.appTop == home.top);
        final int y = Math.max(0, CaptionRule.barTop(home.top, shape, height));
        final int w = Math.max(1, home.right - home.left);
        if (homeCover == null) {
            Context ctx = host != null ? host : app;
            homeCoverWm = ctx.getSystemService(WindowManager.class);
            homeCover = new View(ctx);
            homeCover.setBackgroundColor(0xFF202124);      // the taskbar's own colour
            homeCover.setClickable(true);                   // swallow the platform's buttons
            homeCover.setOnClickListener(v -> { });
            homeCoverLp = params(home.left, y, w, height);
            try {
                homeCoverWm.addView(homeCover, homeCoverLp);
                DexLog.step("desktop", "the platform put a caption on the desktop's home — "
                        + "covering it (task " + home.taskId + ", " + height + "px)");
            } catch (Throwable e) {
                DexLog.warn("desktop", "could not cover the home's caption: " + e);
                homeCover = null;
                return;
            }
        } else if (homeCoverLp.x != home.left || homeCoverLp.y != y
                || homeCoverLp.width != w || homeCoverLp.height != height) {
            homeCoverLp.x = home.left;
            homeCoverLp.y = y;
            homeCoverLp.width = w;
            homeCoverLp.height = height;
            try {
                homeCoverWm.updateViewLayout(homeCover, homeCoverLp);
            } catch (Throwable ignored) {
            }
        }
    }

    /** One line for the diagnostics report: is the desktop's caption covered, and why. */
    static String homeCoverState() {
        CaptionFallback f = instance;
        if (f == null) return "no fallback instance";
        if (f.homeCover == null) return "none up";
        return "up at y=" + f.homeCoverLp.y + " h=" + f.homeCoverLp.height
                + " x=" + f.homeCoverLp.x + " w=" + f.homeCoverLp.width;
    }

    private void dropHomeCover() {
        if (homeCover == null) return;
        try {
            if (homeCover.isAttachedToWindow()) homeCoverWm.removeViewImmediate(homeCover);
        } catch (Throwable ignored) {
        }
        homeCover = null;
        homeCoverLp = null;
        DexLog.step("desktop", "the desktop's home is fullscreen again — cover removed");
    }

    /** One of our own windows that wears a bar like any app window. */
    /** The desktop's own home activity, in any of the forms a dumpsys parse produces. */
    private boolean isHomeActivity(String activity) {
        if (activity == null || activity.isEmpty()) return true;   // our package, no activity
        String simple = LauncherActivity.class.getSimpleName();
        return activity.equals(LauncherActivity.class.getName()) || activity.equals(simple)
                || activity.endsWith("." + simple);
    }

    private boolean isChrome(WmClient.Task t) {
        return SettingsActivity.class.getName().equals(t.activity)
                || TaskManagerActivity.class.getName().equals(t.activity)
                || FileTransferActivity.class.getName().equals(t.activity);
    }

    /**
     * The platform's caption height on this task from a WITNESS, not from the app bounds.
     *
     * <p>With the accessibility service bound its window list is the witness. With the
     * service switched off — the normal state for a sideloaded app on Android 13+ until
     * "Allow restricted settings" is granted — nothing in this process can see a caption
     * that is drawn as a window, and the old code guessed 40 dp and left the platform's
     * own buttons showing under our bar. The daemon can see it (`dumpsys`), so it is
     * asked, once every few seconds per task: a dumpsys is tens of milliseconds.
     */
    private int seenCaption(WmClient.Task t, boolean service) {
        int fromService = CaptionService.platformCaptionHeight(t.taskId);
        if (fromService > 0) return fromService;
        if (service) return 0;                 // the service can see, and it sees nothing
        long now = SystemClock.uptimeMillis();
        long[] hit = probed.get(t.taskId);
        if (hit != null && now - hit[1] < PROBE_TTL_MS) return (int) hit[0];
        int px = wm.captionProbe(display, t.taskId);
        probed.put(t.taskId, new long[]{px, now});
        if (px > 0) DexLog.step("caption", "task " + t.taskId + ": the daemon sees a "
                + px + "px caption window on it");
        return px;
    }

    /**
     * The platform's caption height on a task, asked of the daemon, cached by
     * {@link #PROBE_TTL_MS}. Used where the accessibility service's own measurement list
     * cannot be trusted — the home, which that service deliberately never measures.
     */
    private int probeDaemonCaption(WmClient.Task t) {
        long now = SystemClock.uptimeMillis();
        long[] hit = probed.get(t.taskId);
        if (hit != null && now - hit[1] < PROBE_TTL_MS) return (int) hit[0];
        int px = wm.captionProbe(display, t.taskId);
        probed.put(t.taskId, new long[]{px, now});
        if (px > 0) DexLog.step("desktop", "the daemon sees a " + px + "px caption window on task "
                + t.taskId + " (" + t.pkg + ")");
        return px;
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
        /** The bar's own top edge — above the frame when the platform's caption is a window. */
        int barY;

        Bar(Context ctx, WmClient.Task t, int px, int atY) {
            taskId = t.taskId;
            pkg = t.pkg;
            wmgr = ctx.getSystemService(WindowManager.class);
            root = build(ctx, t, px);
            barY = atY;
            lp = params(t.left, atY, Math.max(1, t.right - t.left), px);
            title = (TextView) root.getChildAt(0);
            left = t.left;
            top = t.top;
            right = t.right;
            bottom = t.bottom;
        }

        void sync(WmClient.Task t, int px, int atY) {
            right = t.right;
            bottom = t.bottom;
            if (t.left != left || t.top != top || barY != atY
                    || lp.width != Math.max(1, right - left)) {
                left = t.left;
                top = t.top;
                lp.x = left;
                lp.y = atY;
                barY = atY;
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

    private Bar add(Context ctx, WmClient.Task t, int px, int atY) {
        try {
            Bar b = new Bar(ctx, t, px, atY);
            b.wmgr.addView(b.root, b.lp);
            bandsFor(ctx, b);
            // Windows' caption arrives with the window, not before it: a bar that appears
            // instantly over a window that is still fading in reads as a flicker.
            b.root.setAlpha(0f);
            b.root.setTranslationY(-Math.min(6, px / 3f));
            b.root.animate().alpha(1f).translationY(0f).setDuration(140).start();
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
        View min = button(ctx, "\u2014", 0xFFE8EAED, v -> minimise(taskId));
        View max = button(ctx, "\u25A1", 0xFFE8EAED, v -> toggleMax(taskId));
        View close = button(ctx, "\u2715", 0xFFE8EAED, v -> close(taskId));
        buttons.addView(min);
        buttons.addView(max);
        buttons.addView(close);
        // Windows' caption buttons: a plain grey glyph that lifts on hover, and the close
        // button alone turning red — #C42B1C, the colour a Windows title bar uses, with the
        // glyph going white. Without it the bar read as a picture of a title bar rather
        // than something to press: nothing under the pointer ever changed.
        hoverButton(min, 0x33FFFFFF, 0);
        hoverButton(max, 0x33FFFFFF, 0);
        hoverButton(close, 0xFFC42B1C, 0xFFFFFFFF);

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

    /**
     * Hover and press feedback on a caption button.
     *
     * @param hover the background behind the glyph while the pointer is over it, or 0 for
     *              a subtle tint shared by all of them
     * @param glyphOnHover the glyph colour while hovered, or 0 to leave it alone
     */
    private void hoverButton(View b, int hover, int glyphOnHover) {
        final int restGlyph = b instanceof TextView ? ((TextView) b).getCurrentTextColor() : 0;
        b.setOnHoverListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    v.setBackgroundColor(hover);
                    if (glyphOnHover != 0 && v instanceof TextView) {
                        ((TextView) v).setTextColor(glyphOnHover);
                    }
                    v.setAlpha(1f);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    v.setBackgroundColor(0x00000000);
                    if (v instanceof TextView) ((TextView) v).setTextColor(restGlyph);
                    v.setAlpha(0.82f);
                    break;
                default:
                    break;
            }
            return false;          // the click listener still gets it
        });
        b.setAlpha(0.82f);
        b.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start();
            else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            }
            return false;          // never consume: the click must still land
        });
    }

    private void minimise(int taskId) {
        worker.post(() -> {
            Bar b = bars.remove(taskId);
            // Recorded BEFORE the hide, and from the bar: once the window is hidden
            // the task list may stop carrying it (see CaptionService's identical
            // note), so the package has to be captured while it is still known here.
            if (b != null) {
                hidden.put(taskId, new Hid(b.pkg, display));
                notifyWatchers();
            }
            wm.hide(display, taskId);
            if (b != null) remove(b);
        });
    }

    private void close(int taskId) {
        worker.post(() -> {
            wm.close(taskId);
            Bar b = bars.remove(taskId);
            if (b != null) remove(b);
            if (hidden.remove(taskId) != null) notifyWatchers();
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
