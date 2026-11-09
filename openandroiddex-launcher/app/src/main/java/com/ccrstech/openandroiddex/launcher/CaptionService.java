package com.ccrstech.openandroiddex.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-window title bars that cannot separate from their window.
 *
 * The mechanism is {@link AccessibilityService#attachAccessibilityOverlayToWindow}. Its
 * server side does not put the surface on a display overlay layer — it forwards the
 * SurfaceControl to the target app's own process, where
 * AccessibilityInteractionController runs
 * {@code t.reparent(sc, mViewRootImpl.getSurfaceControl())}. Our caption therefore
 * becomes a CHILD LAYER of the app's own window surface, which is the same structural
 * guarantee AOSP's desktop caption gets from {@code .setParent(mTaskSurface)}:
 *
 *   - z-order among other freeform windows is correct with no invariant to maintain
 *   - the caption translates, raises and animates with its window for free
 *   - TYPE_APPLICATION_OVERLAY's policy-layer-11 problem never arises
 *   - Window#setHideOverlayWindows cannot suppress it
 *   - the system marks it a trusted overlay, so it does not trip untrusted-touch blocking
 *
 * The space it draws into is reserved by the shell-uid daemon via setAppBounds +
 * addInsetsSource(captionBar()) — see WmClient#strip. That half is measured working on
 * SM-S938B and on an AOSP Pixel alike; see doc/custom-titlebar-v2.md §0.
 *
 * The caption is clipped to the task bounds by Task#updateSurfaceSize's setWindowCrop,
 * so all chrome must live strictly inside the task rect. Hence "reserve a strip at the
 * top" rather than "float a bar above the window".
 *
 * WHAT WE ATTACH TO is the shell's own caption window when there is one — inside it our
 * bar covers the platform's exactly — and the task's app window when there is not. The
 * second case is not hypothetical: measured on an AOSP Pixel (API 36), Chrome's caption
 * window took 12 SECONDS to appear in the accessibility window list after its window was
 * already on screen, and a shell that does not decorate this display produces none at
 * all. Waiting for it unconditionally is what left windows wearing no bar; the wait is
 * therefore bounded per task by HOST_GRACE_MS — see {@link #hostOnAppWindow}.
 *
 * The app window is a good host where nothing occludes it: it is sized to the FULL task
 * rect (measured on that Pixel: task 184,36-1240,814, app window 1056x778 — the whole
 * task, strip included, with only the app's LAYOUT inset by our captionBar source), so a
 * bar at its top lands exactly in the reserved strip. What it cannot do is beat a caption
 * the shell actually draws: that one is a sibling in the task's decor container at
 * z=30000, above the app window, so a bar hosted underneath it is invisible. Hence the
 * grace before falling back, and hence pickHost still running on every pass afterwards.
 */
public final class CaptionService extends AccessibilityService {

    private static final String TAG = "DexCaption";

    /**
     * The connected service, or null when accessibility is off.
     *
     * Held for {@link WebInput}, which needs a real AccessibilityService to
     * dispatch a gesture — that is the only way an app on this phone can put a
     * touch into another app's window, and this is the only accessibility
     * service we have. A strong static reference to a Service is safe in a way
     * one to an Activity is not: the system owns its lifetime, and onDestroy
     * below clears it.
     */
    private static volatile CaptionService live;

    static CaptionService live() {
        return live;
    }

    /**
     * The display the desktop shell is hosted on, or &lt;0 when there is no live
     * desktop session (or the window daemon has not answered yet).
     *
     * Read by the Web viewer, which always captures the DEFAULT display: when
     * this is a SECONDARY display (id &gt; 0) the desktop is elsewhere and the
     * viewer is necessarily showing the phone, which the page says out loud.
     */
    static int desktopDisplay() {
        CaptionService s = live;
        return s == null ? -1 : s.desktopDisplayId;
    }


    /** Minimise one task, as its caption's minimise does. */
    static void minimiseTask(int taskId) {
        CaptionService s = live;
        if (s == null) return;
        WmClient.Task t = s.tasks.get(taskId);
        if (t != null && !s.minimised.containsKey(taskId)) s.onMinimise(t);
    }

    /**
     * Packages with a window on the desktop display right now — visible or not.
     *
     * The taskbar's open-apps row used to be fed only by the computer's
     * broadcast, and that one is built from VISIBILITY: a window in the middle
     * of a transition, hidden by us, or briefly not visibleRequested drops out
     * of it for a tick. The launcher merged the broadcast by "keep what is in
     * it", so one missed tick was enough to remove an app's tile for good —
     * the app stayed open with no way back from the taskbar. ("nicher recent
     * app hide hoye jai".)
     *
     * This service already polls the display's task list several times a second
     * for the captions, so it is the authoritative answer to "is it still
     * open", and it is in-process — no broadcast, no race.
     */
    /**
     * Bring an app's window to the front by package.
     *
     * The taskbar's way back to a window is normally the app's own launcher
     * activity, which is also how the drawer opens it. An app with NO launcher
     * activity — one started from a notification, a widget, another app — has no
     * activity to start, and the taskbar tile for it used to be a click that did
     * nothing. This is the other half: the window is already on the display, so
     * all it needs is to be raised.
     *
     * @return false when no live task of that package is known — the caller falls
     *         back to whatever it would have done.
     */
    static boolean focusPackage(String pkg) {
        CaptionService s = live;
        if (s == null || s.wm == null || pkg == null) return false;
        for (WmClient.Task t : s.tasks.values()) {
            if (!pkg.equals(t.pkg)) continue;
            final int display = t.displayId;
            final int id = t.taskId;
            s.wm.post(() -> s.wm.front(display, id));
            return true;
        }
        return false;
    }

    static java.util.Set<String> openPkgs() {
        CaptionService s = live;
        if (s == null) return java.util.Collections.emptySet();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        // `tasks` is the display's live task list, refreshed by the reconcile pass.
        for (WmClient.Task t : s.tasks.values()) {
            if (t.pkg != null && !t.pkg.isEmpty()) out.add(t.pkg);
        }
        return out;
    }

    /**
     * Put the desktop itself behind the windows on it.
     *
     * Called when the wallpaper is tapped. The launcher is an ordinary task on
     * this display, so Android raises it to the front the moment anything on it
     * is touched — and a full-screen task in front of the freeform windows
     * covers every one of them. That is the "blank space a click korle window
     * hide hoye jai" report: the window has not gone anywhere, the desktop has
     * climbed on top of it.
     *
     * A desktop is the back of the picture. Tapping it may focus it, but it may
     * not become the front-most window, so this puts it back where it belongs
     * in the same gesture that raised it.
     */
    static void sendDesktopToBack(int taskId) {
        CaptionService s = live;
        if (s == null || s.wm == null || s.desktopDisplayId < 0) return;
        final int display = s.desktopDisplayId;
        s.wm.post(() -> s.wm.sendToBack(display, taskId));
    }

    /** Is this task minimised — hidden by us, still open? */
    static boolean minimisedTask(int taskId) {
        CaptionService s = live;
        return s != null && s.minimised.containsKey(taskId);
    }

    /** Bring a minimised task back. The reconcile drops it from the set once it is visible. */
    static void restoreTask(int taskId) {
        CaptionService s = live;
        if (s == null || s.wm == null) return;
        final int display = s.desktopDisplayId;
        s.wm.post(() -> s.wm.show(display, taskId));
    }

    /** Is this task on the maximised rect now? */
    static boolean maximisedTask(int taskId) {
        CaptionService s = live;
        if (s == null) return false;
        WmClient.Task t = s.tasks.get(taskId);
        return t != null && near(t, s.maxRect(t.displayId));
    }

    /** Maximise ({@code on}) or restore, as the caption's ⤢ does; nothing if it is already so. */
    static void maximiseTask(int taskId, boolean on) {
        CaptionService s = live;
        if (s == null) return;
        WmClient.Task t = s.tasks.get(taskId);
        if (t == null) return;
        if (on != near(t, s.maxRect(t.displayId))) s.onMaximise(t);
    }

    /**
     * Make a task's content {@code w} × {@code h}, from where its top-left is,
     * inside the maximised rect: an app sizing its own window (a dialog that
     * grows to fit what it shows).
     */
    static void resizeTask(int taskId, int w, int h) {
        CaptionService s = live;
        if (s == null || s.wm == null) return;
        WmClient.Task t = s.tasks.get(taskId);
        if (t == null || w <= 0 || h <= 0) return;
        final int display = t.displayId;
        final int px = s.captionHeight(taskId);
        final int[] max = s.maxRect(display);
        final int left = t.left;
        final int top = t.top;
        final int right = Math.min(left + w, max[2]);
        final int bottom = Math.min(top + px + h, max[3]);
        if (right - left < 64 || bottom - top < px + 32) return;
        s.wm.post(() -> s.wm.resize(display, taskId, left, top, right, bottom, px));
    }

    /** A task's rect on the display — {left, top, right, bottom} — or null when it is not one we track. */
    static int[] taskRect(int taskId) {
        CaptionService s = live;
        if (s == null) return null;
        WmClient.Task t = s.tasks.get(taskId);
        return t == null ? null : new int[]{t.left, t.top, t.right, t.bottom};
    }

    /**
     * Put a task back at its size after the display's density moved under it:
     * {@code w} x {@code h} px about its centre, inside the maximised rect — or,
     * {@code maximised}, on the maximised rect itself, whose bottom moved with
     * the taskbar's height in dp. That is the Settings window keeping its size
     * in dp when the Display size it was just used for changes, as a
     * computer's own windows do when its scaling changes: left alone, a window
     * keeps its pixels, and at twice the density the card it was changed from
     * no longer fits in it.
     *
     * Straight to the rect rather than through {@link #onMaximise}, which would
     * file the stale maximised rect as the window's restore rect.
     *
     * @return the rect asked for, or null when there was nothing to do
     */
    static int[] placeTask(int taskId, int w, int h, boolean maximised) {
        CaptionService s = live;
        if (s == null || s.wm == null) return null;
        WmClient.Task t = s.tasks.get(taskId);
        if (t == null) return null;
        final int display = t.displayId;
        final int[] max = s.maxRect(display);
        final int[] r;
        if (maximised) {
            r = max.clone();
        } else {
            int ww = Math.min(w, max[2] - max[0]);
            int hh = Math.min(h, max[3] - max[1]);
            if (ww < 64 || hh < 64) return null;
            int cx = (t.left + t.right) / 2;
            int cy = (t.top + t.bottom) / 2;
            int left = Math.max(max[0], Math.min(cx - ww / 2, max[2] - ww));
            int top = Math.max(max[1], Math.min(cy - hh / 2, max[3] - hh));
            r = new int[]{left, top, left + ww, top + hh};
        }
        final int px = s.captionHeight(taskId);
        s.wm.post(() -> s.wm.resize(display, taskId, r[0], r[1], r[2], r[3], px));
        return r;
    }

    /**
     * A context for {@code TYPE_ACCESSIBILITY_OVERLAY} windows on a display:
     * above every app window and the taskbar, needing no permission — where a
     * Linux app's menus are shown. Null while the service is not running.
     */
    static android.content.Context overlayContext(int displayId) {
        CaptionService s = live;
        if (s == null) return null;
        try {
            Display d = s.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId);
            if (d == null) return null;
            android.content.Context ctx = s.createDisplayContext(d);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                ctx = ctx.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            }
            return ctx;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Fallback caption height in dp on the desktop display.
     *
     * Used only when One UI's own decoration cannot be measured. Our bar must be at least
     * as tall as the platform's caption, otherwise the difference shows as a sliver of the
     * old title bar peeking out underneath ours — One UI's is 40 dp on this device.
     */
    private static final int CAPTION_DP = 40;

    /** A candidate taller than this is the app window, not a caption. */
    private static final int CAPTION_MAX_DP = 72;

    /** taskId -> caption height actually in use, measured from the platform decor. */
    private final Map<Integer, Integer> captionHeights = new HashMap<>();

    private int captionHeight(int taskId) {
        Integer h = captionHeights.get(taskId);
        return h == null ? px(CAPTION_DP) : h;
    }

    /** Poll cadence for reconciling captions against the live task list, when idle. */
    private static final long RECONCILE_MS = 400L;

    /** Cadence while windows are moving. One LIST round trip measures ~2.5 ms. */
    private static final long RECONCILE_BUSY_MS = 60L;


    private final Handler main = new Handler(Looper.getMainLooper());
    private WmClient wm;
    /** Undoes the session when the cable is pulled and the window daemon went with it. */
    private UnplugWatch unplug;

    /** a11y window id -> live caption host. */
    /**
     * taskId -> its one caption.
     *
     * Keyed by TASK, not by accessibility window id: a task's host window id changes
     * whenever the app's window is recreated, and a window-keyed map orphans the old
     * entry under the old key. The surface stays in the layer tree, so the task ends up
     * wearing two identical bars with two live touch handlers, and every rebuild leaks
     * another SurfaceControlViewHost.
     */
    private final SparseArray<Caption> captions = new SparseArray<>();
    /** taskId -> last known task, refreshed by the daemon poll. */
    private final Map<Integer, WmClient.Task> tasks = new HashMap<>();

    /** The same tasks in the daemon's z-order, topmost first. See applyTasks. */
    private List<WmClient.Task> lastStack = new ArrayList<>();

    /**
     * taskId -> opaque strip that hides One UI's own caption until ours attaches.
     *
     * A freshly launched window shows the platform's bare title bar for the handful of
     * passes it takes the caption host to appear in the accessibility window list and our
     * real caption to attach on top of it — that gap is the launch "flash". A curtain the
     * same colour as our bar, drawn at the task's caption strip the instant the window is
     * seen, covers it so the swap to the real caption is seamless.
     */
    private final SparseArray<View> curtains = new SparseArray<>();
    /** taskId -> uptime the curtain went up, so one that never yields a host can give up. */
    private final Map<Integer, Long> curtainSince = new HashMap<>();
    /** Task ids that must NOT be curtained: already dressed, or gave up waiting for a host. */
    private final Set<Integer> noCurtain = new HashSet<>();

    /**
     * Tasks we maximised. A hint only — onMaximise branches on the window's LIVE rect, not
     * on this, because nothing reconciles a remembered flag against a window that anything
     * else on the desktop can resize. Kept because it is still the cheapest way to say "our
     * ▢ put it there" for the drag and snap paths, which unset it.
     */
    private final Set<Integer> maximized = new HashSet<>();
    /** taskId -> {l,t,r,b} to restore to, captured the first time it is maximised/snapped. */
    private final Map<Integer, int[]> restoreBounds = new HashMap<>();
    /** The drag ghost and the layout picker. Built on first use — see SnapOverlay. */
    private SnapOverlay snapOverlay;
    /**
     * The zone the window being dragged would land in, or SnapZones.NONE.
     *
     * Held between the move that set it and the release that acts on it, so the
     * rect the ghost drew is exactly the rect the release applies.
     */
    private int dragZone = SnapZones.NONE;
    /**
     * Longest a curtain stays up without a real caption landing. After this we remove it and
     * accept the platform caption, so an app that never exposes a host (e.g. activity
     * embedding) falls back to a visible One UI bar rather than wearing a dead strip forever.
     */
    private static final long CURTAIN_MAX_MS = 1500L;

    /** Volatile: written on the service's main thread, read by the Web server's. */
    private volatile int desktopDisplayId = -1;
    private boolean running;
    private int ticks;

    /**
     * taskId -> uptime we started waiting for a shell caption window to host on.
     *
     * The shell's caption window is the host we WANT — inside it our bar covers the
     * platform's exactly. But waiting for it unconditionally is what left windows bare:
     * measured on an AOSP Pixel, Chrome's caption window took 12 SECONDS to reach the
     * accessibility window list after its window was already on screen, and a shell that
     * does not decorate this display at all never produces one. Either way the curtain
     * gives up at CURTAIN_MAX_MS and the user is left looking at an undressed window.
     *
     * So the wait is bounded per task: after HOST_GRACE_MS we host on the app window
     * instead, and keep looking. This is a wait, not a verdict — {@link #pickHost} still
     * runs every pass, and if the shell's caption turns up later the bar rebuilds onto it.
     */
    private final Map<Integer, Long> hostWaitSince = new HashMap<>();

    /**
     * How long a task may go without a shell caption window before we host on its app
     * window instead.
     *
     * Not shorter, because One UI's caption window is missing from the list for roughly
     * one pass in two even when it exists, and hosting on the app window there is
     * INVISIBLE — below One UI's own caption in the task's decor container at z=30000. A
     * blink must never be enough to trigger this; only a real absence. Longer than
     * CURTAIN_MAX_MS would mean the curtain gives up first and the bare window shows
     * through, which is the whole thing we are fixing.
     */
    private static final long HOST_GRACE_MS = 1200L;

    /**
     * Displays on which a shell caption window has been seen — the grace is worth waiting
     * out there, because the caption that is missing this pass will be back.
     */
    private final Set<Integer> hostDisplays = new HashSet<>();

    /**
     * Displays whose shell has shown it draws no caption windows: a task there waited out
     * the whole grace for one and no task there has ever had one. Every later window on
     * such a display is hosted on its app window at once instead of after the grace again
     * — the wait is for a caption that might be about to appear, and here none ever does.
     * Measured on an Android 15 emulator, whose shell draws none on this display: 1.2 s
     * added to the bar of every new window. The first shell caption seen on the display
     * takes it off this list, and the grace applies again.
     */
    private final Set<Integer> captionlessDisplays = new HashSet<>();
    /** Tasks already reported as wearing no caption of ours — one log line each. */
    private final Set<Integer> diagnosed = new HashSet<>();

    private boolean captionless(int displayId) {
        return captionlessDisplays.contains(displayId) && !hostDisplays.contains(displayId);
    }

    /**
     * Accessibility title stamped on our own bars, so a host picker can tell OUR caption
     * from the platform's. Geometry cannot: ours sits at the same origin, spans the same
     * width and is the same height as the thing it covers.
     */
    private static final String CAPTION_WINDOW_TITLE = "openandroiddex-caption";

    private static final class Caption {
        SurfaceControlViewHost host;
        TextView title;
        int taskId;
        int width;
        int height;
        /**
         * Whether this bar was built with the two snap buttons.
         *
         * The button set is chosen from the width at BUILD time, and the bar is
         * resized in place for the rest of its life — so a window widened past
         * the threshold (or narrowed below it) kept the set picked for the old
         * size. Widened, that is a bar missing ◧ ◨ it has room for; narrowed,
         * it is the old "no minimise button" bug wearing a different hat. The
         * flag is what lets syncCaption notice and ask for a rebuild.
         */
        boolean snaps;
        /** Accessibility window this is attached to; changes when the host is recreated. */
        int windowId;
    }

    /** Breadcrumb file: logcat from this uid is unreliable on One UI, a file is not. */
    private void trace(String what) {
        Log.i(TAG, what);
        try (java.io.FileWriter w =
                     new java.io.FileWriter(new java.io.File(getFilesDir(), "caption.log"), true)) {
            w.write(System.currentTimeMillis() + " " + what + "\n");
        } catch (Throwable ignored) {
        }
    }

    /**
     * A theme or shell change repaints the captions. There is no way to
     * re-colour or re-measure a surface that is already attached inside
     * another app's window, so the bars are dropped and the reconcile pass —
     * which rebuilds any task without one — puts them back in the new look on
     * its next tick.
     *
     * The allowlist below is the whole contract: a setting that is NOT in it
     * leaves every open window wearing the old chrome until it is closed and
     * opened again. Anything that reaches {@link #build} has to be here.
     */
    private final android.content.BroadcastReceiver themeReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                    String key = intent.getStringExtra(DexPrefs.EXTRA_KEY);
                    if (!DexPrefs.KEY_THEME.equals(key) && !DexPrefs.KEY_DARK.equals(key)
                            // The shell decides the bar's palette AND its
                            // geometry — Windows 11 captions carry wider
                            // buttons and a red close. Neither can be changed
                            // on a bar that is already attached.
                            && !DexPrefs.KEY_SHELL.equals(key)
                            && !DexPrefs.KEY_PAPER_TEXTURE.equals(key)
                            && !DexPrefs.KEY_GRAIN.equals(key)
                            // suppresses the grain, so the captions carry the
                            // old finish until they are rebuilt too
                            && !DexPrefs.KEY_PERF.equals(key) && !"*".equals(key)
                            // a PointerIcon is stored on the view, not asked
                            // for per event, so a new cursor needs a new bar
                            && !DexCursors.isCursorKey(key)) {
                        return;
                    }
                    for (int i = 0; i < captions.size(); i++) release(captions.valueAt(i));
                    captions.clear();
                    trace("theme, shell or cursor changed — captions dropped for rebuild");
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        trace("onCreate");
    }

    @Override
    protected void onServiceConnected() {
        trace("onServiceConnected sdk=" + android.os.Build.VERSION.SDK_INT);
        // Published even on the API < 34 path below, where captions are inert:
        // gesture dispatch has no such floor, so the Web viewer's control
        // still works on a phone this service can draw nothing on.
        live = this;
        // Ahead of the API floor below, which the cable-pulled undo does not share.
        if (unplug == null) {
            unplug = new UnplugWatch(this, () -> main.post(this::disableSelf));
            unplug.start();
        }
        if (android.os.Build.VERSION.SDK_INT < 34) {
            // attachAccessibilityOverlayToWindow is API 34. Below that there is no way to
            // get a surface into another app's window, so the service is inert rather
            // than half-working.
            Log.w(TAG, "needs API 34+, captions disabled");
            return;
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
        }
        wm = new WmClient();
        running = true;
        android.content.IntentFilter f =
                new android.content.IntentFilter(LauncherActivity.ACTION_RESTORE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(restoreReceiver, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(restoreReceiver, f);
        }
        // "Show desktop" lives here rather than in the launcher because both
        // halves of the toggle do — the live task list AND the record of what
        // we hid. Splitting them would mean two owners for one piece of state.
        android.content.IntentFilter desktop =
                new android.content.IntentFilter(LauncherActivity.ACTION_SHOW_DESKTOP);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(showDesktopReceiver, desktop, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(showDesktopReceiver, desktop);
        }
        android.content.IntentFilter settings =
                new android.content.IntentFilter(DexPrefs.ACTION_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(themeReceiver, settings, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(themeReceiver, settings);
        }
        // This service is restarted on every reconnect (see restart_caption_service), and
        // the minimised map does not survive that. Say so, or the launcher keeps pinning
        // packages nothing here can un-hide any more — a taskbar icon that does nothing.
        publishMinimised();
        main.post(reconcile);
        trace("connected");
    }

    @Override
    public void onDestroy() {
        if (snapOverlay != null) {
            snapOverlay.detach();
            snapOverlay = null;
        }
        running = false;
        live = null;
        if (unplug != null) unplug.stop();
        try {
            unregisterReceiver(restoreReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(showDesktopReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(themeReceiver);
        } catch (Exception ignored) {
        }
        main.removeCallbacks(reconcile);
        for (int i = 0; i < captions.size(); i++) release(captions.valueAt(i));
        captions.clear();
        for (int i = 0; i < curtains.size(); i++) {
            try {
                View v = curtains.valueAt(i);
                v.getContext().getSystemService(WindowManager.class).removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
        }
        curtains.clear();
        while (floating.size() > 0) hideFloatingBar(floating.keyAt(0));
        curtainSince.clear();
        noCurtain.clear();
        maximized.clear();
        restoreBounds.clear();
        dropAnchor();
        if (wm != null) {
            // Leave no window permanently inset if we go away.
            final WmClient client = wm;
            final Map<Integer, WmClient.Task> snapshot = new HashMap<>(tasks);
            final int display = desktopDisplayId;
            client.post(() -> {
                for (WmClient.Task t : snapshot.values()) client.unstrip(display, t.taskId);
                client.shutdown();
            });
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Surface recreation silently orphans an attached overlay, and window changes are
        // when that happens, so re-run the whole reconcile rather than patching one entry.
        main.removeCallbacks(reconcile);
        main.post(reconcile);
    }

    @Override
    public void onInterrupt() {
    }

    // ── reconcile ──────────────────────────────────────────────────────────

    private final Runnable reconcile = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // getWindows() and every AccessibilityWindowInfo touch must stay on the
            // service's main thread; only the socket round trip goes to the io thread.
            wm.post(() -> {
                final int display = wm.desktopDisplay();
                final List<WmClient.Task> live = display < 0 ? null : wm.list(display);
                main.post(() -> {
                    if (!running) return;
                    if (display != desktopDisplayId) {
                        trace("desktop display = " + display);
                        desktopDisplayId = display;
                        dropAnchor();               // the anchor belongs to the old display
                        hostWaitSince.clear();      // those task ids are on the old display
                        hostDisplays.clear();
                        captionlessDisplays.clear();
                        // The new one now, not at the first caption: a window added this
                        // pass has no surface until it is laid out, so the first bar built
                        // on a fresh anchor finds no token and waits a pass for it (seen
                        // on an Android 15 emulator: ~0.45 s on the first window).
                        Display d = display < 0 ? null
                                : getSystemService(android.hardware.display.DisplayManager.class)
                                        .getDisplay(display);
                        if (d != null) anchorToken(d);
                    }
                    if (ticks++ % 25 == 0) {
                        trace("tick display=" + display + " tasks="
                                + (live == null ? "?" : live.size())
                                + " captions=" + captions.size());
                    }
                    // A null list is "the daemon did not answer", not "nothing is open" —
                    // see WmClient#list. Skipping the pass costs one tick; acting on it
                    // would release every caption and un-pin every minimised window.
                    boolean busy = live != null && applyTasks(display, live);
                    main.removeCallbacks(reconcile);
                    // Poll fast while geometry is actually changing, idle slowly otherwise.
                    // A fixed 400ms tick means a window moved by anything other than our own
                    // title bar — a platform edge-resize, an app moving itself — drags its
                    // caption behind it for up to a full tick. Chasing that with a
                    // permanently fast tick would spend a round trip to the daemon 16x a
                    // second forever, for windows that are usually still.
                    main.postDelayed(reconcile, busy ? RECONCILE_BUSY_MS : RECONCILE_MS);
                });
            });
        }
    };

    /**
     * The desktop is the non-default display the scrcpy session created. Its id changes
     * every session, so it must never be hard-coded — and it is re-resolved whenever the
     * display goes away, because a reconnect produces a new id.
     *
     * Note getWindows() is DEFAULT-DISPLAY ONLY; windows on a secondary display are only
     * visible through getWindowsOnAllDisplays().
     */
    private List<AccessibilityWindowInfo> windowsOnDisplay(int display) {
        SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
        return all == null ? null : all.get(display);
    }

    private List<AccessibilityWindowInfo> windowsOn(int display) {
        SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
        if (all == null) return null;
        return all.get(display);
    }

    /** Set by ensureCaption when a bar had to be moved or rebuilt this pass. */
    private boolean moved;

    /**
     * The desktop itself — the launcher's own home task, which must never wear
     * a caption because it IS the background.
     *
     * This used to exclude our whole package, which also excluded our Settings
     * window: it is a normal freeform window and was the one window on the
     * desktop still wearing One UI's own title bar. Settings runs in its own
     * task (see its taskAffinity in the manifest), so the activity name tells
     * the two apart, and our other windows are exempted the same way.
     */
    private boolean isDesktopTask(WmClient.Task t) {
        return getPackageName().equals(t.pkg)
                && !SettingsActivity.class.getName().equals(t.activity)
                && !TaskManagerActivity.class.getName().equals(t.activity)
                && !FileTransferActivity.class.getName().equals(t.activity);
    }

    /**
     * Packages seen on the desktop display during this session.
     *
     * Their tasks are the ones that can be left in FREEFORM after being opened on
     * the phone: they were launched with {@code --windowingMode 5} here, and
     * Android carries the same task over when it moves to the default display —
     * so the app opens on the phone as a small freeform window wearing a title
     * bar instead of filling the screen. ("pc te kono app use korle phone a oi
     * app a gele screen choto abnormal dekhai.")
     *
     * Only these are ever touched on the phone's display — never an app the user
     * opened there themselves, and never a task the desktop never launched.
     */
    private final Set<String> desktopPkgs = new HashSet<>();

    /** Reconcile ticks between phone-display sweeps (the poll runs every 60-400 ms). */
    private int phoneSweep = 0;

    /**
     * Give the phone's screen back to an app the desktop left in freeform.
     *
     * A task launched onto the desktop display keeps its windowing mode when
     * Android moves it to the default display, so the app comes up on the phone
     * as a small floating window — with our caption strip's app-bounds inset
     * still applied, which is what makes it look not merely small but wrong.
     * Resizing it to the display and dropping the inset is the whole fix; the
     * windowing mode itself is not ours to change (that needs
     * {@code ActivityTaskManager}, and the daemon's task is the desktop's).
     *
     * Runs on the reconcile poll, so a window moved to the phone is put right
     * within a couple of seconds of it landing there.
     */
    private void releaseFreeformOnPhone() {
        if (wm == null || desktopPkgs.isEmpty()) return;
        List<WmClient.Task> phone;
        try {
            phone = wm.list(Display.DEFAULT_DISPLAY);
        } catch (RuntimeException e) {
            return;
        }
        if (phone == null || phone.isEmpty()) return;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        try {
            Display d = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return;
            d.getRealMetrics(dm);
        } catch (Throwable t) {
            return;
        }
        for (WmClient.Task t : phone) {
            if (!t.isFreeform() || t.pkg == null || !desktopPkgs.contains(t.pkg)) continue;
            // Already the whole screen: nothing to do, and resizing again would
            // only cost a relayout.
            if (t.right - t.left >= dm.widthPixels - 1 && t.bottom - t.top >= dm.heightPixels - 1
                    && t.left <= 1 && t.top <= 1) {
                continue;
            }
            trace("phone: releasing " + t.pkg + " from freeform " + t.left + "," + t.top
                    + "," + t.right + "," + t.bottom);
            wm.unstrip(Display.DEFAULT_DISPLAY, t.taskId);
            wm.bounds(Display.DEFAULT_DISPLAY, t.taskId, 0, 0, dm.widthPixels, dm.heightPixels);
        }
    }

    /** @return true if any caption geometry changed, i.e. windows are on the move. */
    private boolean applyTasks(int display, List<WmClient.Task> live) {
        trackDensity(display);
        moved = false;
        tasks.clear();
        // Kept in the daemon's order — TOPMOST FIRST — because stacking depends on it.
        // The map alone cannot carry this; HashMap order is not the z-order.
        List<WmClient.Task> stack = new ArrayList<>();
        for (WmClient.Task t : live) {
            if (t.isFreeform() && t.visible && !isDesktopTask(t)) {
                tasks.put(t.taskId, t);
                stack.add(t);
            }
        }
        // Kept because `tasks` cannot carry it — see onShowDesktop, which is
        // the one caller that has to put windows back in the order it found
        // them.
        lastStack = stack;
        for (WmClient.Task t : stack) {
            if (t.pkg != null) desktopPkgs.add(t.pkg);
        }
        // Every ~2 s: an app that was launched here and is now open on the PHONE
        // has to be given the phone's screen back. Cheap (one LIST on another
        // display, ~2.5 ms) and done on this poll rather than its own thread.
        if (++phoneSweep >= 8) {
            phoneSweep = 0;
            releaseFreeformOnPhone();
        }

        // A minimised task stops being minimised when it comes back, and stops existing
        // when it is really closed. Either way the taskbar should stop pinning it.
        //
        // "Came back" cannot just mean `visible`. setHidden is applied inside a TRANSITION,
        // and TaskInfo#isVisible only flips when that transition COMMITS — a couple of
        // hundred ms later, while this poll runs every 60ms and is kicked immediately by
        // the window-change event the minimise itself produces. Reading that pre-transition
        // `true` as "it is back" un-pinned every window on the very next pass after it was
        // minimised, so the app vanished from the taskbar and the open-apps popup the
        // instant it was minimised — the PC's list is built from visibility and cannot tell
        // a minimised task from a closed one, and this map was the only thing that could.
        //
        // So a visible reading counts only once the hide has actually been SEEN to land,
        // with a grace window to unpin a hide that never lands at all.
        if (!minimised.isEmpty()) {
            boolean changed = false;
            long now = SystemClock.uptimeMillis();
            for (java.util.Iterator<Map.Entry<Integer, Min>> it =
                    minimised.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Min> e = it.next();
                Min m = e.getValue();
                WmClient.Task t = byId(live, e.getKey());
                if (t == null) {
                    it.remove();                       // closed while it was minimised
                    changed = true;
                } else if (!t.visible) {
                    m.hidden = true;                   // the hide landed; now it can be undone
                } else if (m.hidden || now - m.since > HIDE_GRACE_MS) {
                    it.remove();                       // restored — or the hide never took
                    changed = true;
                }
            }
            if (changed) publishMinimised();
        }

        List<AccessibilityWindowInfo> windows = windowsOnDisplay(display);
        if (windows == null) {
            if (ticks % 25 == 0) trace("no a11y windows for display " + display);
            return false;
        }

        // One caption per TASK. For each task pick the single best host window — see
        // pickHost — rather than decorating every a11y window that overlaps the rect.
        // Height is measured from the platform's own decoration first, because the strip
        // we reserve and the bar we draw both have to cover it exactly.
        int missing = 0;
        for (WmClient.Task t : stack) {
            // The shell's caption window first — it is the better host, and it is still
            // checked on every pass even for a task already wearing an app-window bar, so
            // a caption that turns up late is adopted rather than left drawing over us.
            AccessibilityWindowInfo host = pickHost(windows, t);
            if (host == null) {
                host = hostOnAppWindow(windows, t);   // null until the grace runs out
            } else {
                hostWaitSince.remove(t.taskId);
                hostDisplays.add(display);
            }
            if (host != null) captionHeights.put(t.taskId, measureCaption(host));
            final int px = captionHeight(t.taskId);
            // Reserve — or RE-reserve — the strip. The app-bounds override does not follow
            // a task that moves, so this also repairs a stale one after a drag or resize.
            if (!t.hasStrip(px)) {
                final int taskId = t.taskId;
                wm.post(() -> wm.strip(display, taskId, px));
            }
            // Title and size first, and without needing the host — a window resized on a
            // pass where the host is missing must still get a correctly sized bar.
            //
            // A task wearing the display-level fallback bar counts as dressed: it has
            // no Caption record, so syncCaption answers false for it forever, and the
            // caller reads false as "still waiting" — which held the reconcile loop at
            // its 60 ms busy cadence for the rest of the session on a desktop where one
            // window had no host. That is a phone kept warm for nothing.
            boolean ok = syncCaption(t) || floating.get(t.taskId) != null;
            if (host == null) {
                // The host window is not in the accessibility list this pass. On One UI
                // that is the platform's caption blinking out; do NOT fall back to the app
                // window there, because a caption hosted below One UI's own never appears,
                // which reads as "it works sometimes" rather than as a failure. Where the
                // shell draws no caption at all the app window IS the host and was already
                // tried above. Either way: keep whatever bar this task has, try next pass.
                //
                // Only a task with NO bar yet is worth hurrying for. Counting the others
                // would hold the fast tick on forever, since the host is absent for a good
                // fraction of passes even when everything on screen is already correct.
                //
                // While the host is missing the platform's own caption is on screen with
                // nothing of ours over it — the launch flash. Cover it with an opaque strip,
                // but only for a task we have never dressed (never throw a curtain over a
                // working caption whose host just blinked out), and give up after
                // CURTAIN_MAX_MS so a window that never yields a host is not left with a dead
                // bar.
                // Say WHY, once per task, so a window that wears no caption of
                // ours can be diagnosed from the log instead of guessed at:
                // how long it has waited, what the window list did contain, and
                // whether the display is one the shell draws captions on.
                if (hostWaitSince.containsKey(t.taskId)
                        && captions.get(t.taskId) == null && !noCurtain.contains(t.taskId)) {
                    Long waited = hostWaitSince.get(t.taskId);
                    long ms = waited == null ? 0 : SystemClock.uptimeMillis() - waited;
                    if (ms > 5000 && !diagnosed.contains(t.taskId)) {
                        diagnosed.add(t.taskId);
                        trace("no caption host for task " + t.taskId + " after " + ms + "ms"
                                + " (captionlessDisplay=" + captionless(display)
                                + ", task=" + t.left + "," + t.top + "," + t.right + ","
                                + t.bottom + ") — wearing a curtain, not a bar; windows: "
                                + describe(windows));
                    }
                }
                if (captions.get(t.taskId) == null) {
                    if (noCurtain.contains(t.taskId)) {
                        // Already given up on finding a host: the bar lives on the
                        // DISPLAY, not in the window, so nothing moves it but this
                        // loop. Skipping the call for a task in noCurtain — which is
                        // what the guard here used to do — left the bar behind the
                        // moment the window was dragged, with the buttons still
                        // acting on a rect that had moved out from under them.
                        showFloatingBar(t);
                    } else {
                        Long since = curtainSince.get(t.taskId);
                        if (since != null
                                && SystemClock.uptimeMillis() - since > CURTAIN_MAX_MS) {
                            // Not "give up": give the user our buttons anyway. The
                            // curtain has been up for 1.5s and nothing has come to
                            // host on, which on a Samsung desktop means the window
                            // is sitting there wearing One UI's own ▢ ✕ with no
                            // minimise. Draw the real bar on the display instead.
                            hideCurtain(t.taskId);
                            noCurtain.add(t.taskId);
                            showFloatingBar(t);
                        } else {
                            showCurtain(t);
                        }
                    }
                }
                if (!ok) missing++;
                continue;
            }
            ensureCaption(host, t);
            // A host turned up after all, so the fallback is in the way now.
            if (captions.get(t.taskId) != null) hideFloatingBar(t.taskId);
        }
        if (missing > 0) {
            moved = true;          // poll fast until every task has a bar
            if (ticks % 25 == 0) {
                trace("waiting for a caption host on " + missing + "/" + stack.size()
                        + " tasks");
            }
        }

        // Drop captions whose task is gone (closed, or no longer freeform/visible).
        // Nothing else releases them: the loop above only ever visits LIVE tasks, so a
        // closed window's SurfaceControlViewHost would be held for the life of the
        // service. Descending, because removal shifts later indices.
        for (int i = captions.size() - 1; i >= 0; i--) {
            int taskId = captions.keyAt(i);
            if (tasks.containsKey(taskId)) continue;
            release(captions.valueAt(i));
            captions.removeAt(i);
            captionHeights.remove(taskId);
            hostWaitSince.remove(taskId);
            noCurtain.remove(taskId);
            maximized.remove(taskId);
            restoreBounds.remove(taskId);
        }

        // Curtains outlive their caption's absence, so sweep them on the same rule: a task
        // that is no longer live (closed, or no longer freeform/visible) must not keep an
        // opaque strip floating where its window used to be.
        for (int i = curtains.size() - 1; i >= 0; i--) {
            int taskId = curtains.keyAt(i);
            if (tasks.containsKey(taskId)) continue;
            hideCurtain(taskId);
            noCurtain.remove(taskId);
        }
        // The fallback bars outlive their window the same way, and one left behind
        // is worse than a curtain left behind: it has buttons, and they would act on
        // a task id that has since been reused by something else.
        for (int i = floating.size() - 1; i >= 0; i--) {
            int taskId = floating.keyAt(i);
            if (tasks.containsKey(taskId)) continue;
            hideFloatingBar(taskId);
            noCurtain.remove(taskId);
        }
        // A task that closed while still waiting for a host never reaches the sweep above,
        // because it never had a caption to release.
        hostWaitSince.keySet().retainAll(tasks.keySet());
        rememberGeometry(display, stack);
        return moved;
    }

    // ── remembering where each window was left ─────────────────────────────

    /** Last rect seen for a live task, so a change can be told from a repeat. */
    private final Map<Integer, int[]> geometrySeen = new HashMap<>();
    /** When that rect was first seen — the settle clock. */
    private final Map<Integer, Long> geometrySince = new HashMap<>();
    /** Task ids whose current rect has already been written down. */
    private final Set<Integer> geometrySaved = new HashSet<>();

    /**
     * How long a window has to hold still before its position is worth keeping.
     *
     * A drag arrives here as a stream of distinct rects at the busy poll rate,
     * and a resize the same; writing each one would be a SharedPreferences
     * commit every 60ms for the length of the gesture, and the intermediate
     * positions are not what the user chose anyway. Comfortably longer than the
     * busy poll and shorter than "did it save?" — the user lets go, and by the
     * time they have moved the pointer it is recorded.
     */
    private static final long GEOMETRY_SETTLE_MS = 700L;

    /**
     * Write down where each window has come to rest, for
     * {@link LauncherActivity#windowBoundsFor} to open it at next time.
     *
     * This is done from the reconcile poll rather than from the drag handler
     * because the poll is the only place that sees ALL of it. Our own title bar
     * is one of several things that move a window: One UI's edge-resize, the
     * PC-side enforcer, the caption's own maximise and snap, and an app moving
     * itself all bypass {@link #dragHandler} entirely, and a memory that only
     * learned from our own drags would forget every one of them.
     *
     * Only tasks in {@code stack} are considered, which is already the right
     * set: freeform, visible, and not the desktop itself. A minimised window is
     * not in it, so a minimise cannot be mistaken for a move.
     */
    private void rememberGeometry(int display, List<WmClient.Task> stack) {
        if (stack.isEmpty()) {
            geometrySeen.clear();
            geometrySince.clear();
            geometrySaved.clear();
            return;
        }
        if (!WindowMemory.enabled(this)) return;
        long now = SystemClock.uptimeMillis();
        for (WmClient.Task t : stack) {
            int[] rect = {t.left, t.top, t.right, t.bottom};
            int[] previous = geometrySeen.get(t.taskId);
            if (previous == null || !java.util.Arrays.equals(previous, rect)) {
                geometrySeen.put(t.taskId, rect);
                geometrySince.put(t.taskId, now);
                geometrySaved.remove(t.taskId);
                continue;
            }
            if (geometrySaved.contains(t.taskId)) continue;
            Long since = geometrySince.get(t.taskId);
            if (since == null || now - since < GEOMETRY_SETTLE_MS) continue;
            geometrySaved.add(t.taskId);
            String key = WindowMemory.keyFor(this, t.pkg, t.activity);
            if (key != null) {
                WindowMemory.remember(this, key,
                        new android.graphics.Rect(t.left, t.top, t.right, t.bottom),
                        displaySize(display));
            }
        }
        // A closed window's entries would otherwise be held for the life of the
        // service, and its task id can be handed to a different app later.
        geometrySeen.keySet().retainAll(tasks.keySet());
        geometrySince.keySet().retainAll(tasks.keySet());
        geometrySaved.retainAll(tasks.keySet());
    }

    /**
     * Height of One UI's own caption for this window, when we can see it.
     *
     * The platform's decoration is itself an accessibility window: same origin and width
     * as the task, only a caption tall. Matching its height is what stops a sliver of the
     * old title bar showing beneath ours.
     */
    private int measureCaption(AccessibilityWindowInfo host) {
        android.graphics.Rect r = new android.graphics.Rect();
        host.getBoundsInScreen(r);
        int h = r.height();
        if (h > px(CAPTION_MAX_DP)) return px(CAPTION_DP);   // that was the app window
        // Only ever grow to cover, never shrink to match. Once our bar exists it is itself
        // an accessibility window at the same origin and width as the platform's caption,
        // so a measurement can land on *us* — and a plain `return h` then latches the
        // height at whatever we last drew. Shrinking is what leaves a sliver of the old
        // title bar visible below ours; being a few px too tall costs nothing.
        return Math.max(h, px(CAPTION_DP));
    }

    /**
     * The platform's own caption window for this task: TYPE_APPLICATION, taskId -1, at the
     * task origin, task-wide, only a caption tall. Both the host we attach to and the thing
     * we measure, so our bar covers One UI's exactly.
     *
     * Returns null when it is not in the list this pass — which happens, transiently, for
     * roughly one task in two. Null means "not now", not "never": the caller keeps the bar
     * the task already has and tries again on the next pass.
     *
     * Ties break on the lowest window id so the result does not depend on the list order,
     * which is not stable between passes and would otherwise rebuild the caption endlessly.
     */
    private AccessibilityWindowInfo pickHost(List<AccessibilityWindowInfo> windows,
                                             WmClient.Task task) {
        AccessibilityWindowInfo decor = null;
        int taskHeight = task.bottom - task.top;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityWindowInfo w : windows) {
            // Deliberately NO window-type test here. The type was standing in
            // for "is this the caption strip", and on One UI the strip is not
            // always TYPE_APPLICATION — a window that fails this test is a task
            // that wears the platform's own ▢ ✕ (and no minimise of ours) for
            // the whole session. Bounds already say what it is: full task
            // width, at the task's top, no taller than a caption. Those three
            // are the test.
            w.getBoundsInScreen(r);
            // The host must span the FULL task width. Our surface is a child of the host
            // window's surface and is cropped to it: hosting on a narrower window yields a
            // bar clipped to that window even though the buffer is task-wide. Measured on
            // two-pane Settings — 705px buffer, composited 397..693. So an app using
            // activity embedding gets no caption rather than a half-covered one.
            if (Math.abs(r.left - task.left) > 2 || Math.abs(r.right - task.right) > 2) continue;
            if (r.top < task.top - 2 || r.top > task.appTop + 2) continue;
            // Caption-shaped only. There is deliberately NO fallback to the app window:
            // hosting there is below One UI's caption and therefore invisible, so falling
            // back does not degrade gracefully, it fails silently — and because this
            // window blinks out of the list for the odd pass, the fallback also caused a
            // rebuild every time it did, flipping the host back and forth forever.
            // Returning null instead means "keep what this task already has, try again".
            if (r.height() <= px(CAPTION_DP) * 3
                    && !isOurs(w)
                    && (decor == null || w.getId() < decor.getId())) {
                decor = w;
            }
        }
        return decor;
    }

    /**
     * The task's OWN app window: same rect as the task, and taller than any caption.
     *
     * This is the host on a shell that draws no caption window — see the class javadoc.
     * The height test is what keeps it honest: it excludes both a platform caption and
     * OUR bar, which is itself a window in this list at the same origin and width, and
     * which would otherwise be re-adopted as its own host on the pass after it attached.
     *
     * Matched on the task rect rather than on a package name because a window's package
     * costs a getRoot() round trip per window per pass, and the rect is already decisive:
     * a full-width, full-height window at the task's origin belongs to that task.
     *
     * Ties break on the lowest window id — the base window rather than a later child of
     * the same size (YouTube keeps two) — so the host does not change with list order,
     * which is not stable between passes and would rebuild the bar endlessly.
     */
    private AccessibilityWindowInfo appHost(List<AccessibilityWindowInfo> windows,
                                            WmClient.Task task) {
        AccessibilityWindowInfo app = null;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (isOurs(w)) continue;
            w.getBoundsInScreen(r);
            if (Math.abs(r.left - task.left) > 2 || Math.abs(r.right - task.right) > 2) continue;
            // At the task's top, or just below it — a shell that draws its own
            // caption puts the app's window under that caption inside the same
            // task, and demanding the exact top made those tasks unhostable:
            // no bar, and the platform's ▢ ✕ as the only buttons. The offset is
            // handled where the bar is attached (ensureCaption's dy).
            if (r.top < task.top - 2) continue;
            if (r.top > task.top + captionHeight(task.taskId) + 2) continue;
            if (r.height() <= px(CAPTION_MAX_DP)) continue;  // a caption, or one of ours
            if (app == null || w.getId() < app.getId()) app = w;
        }
        return app;
    }

    /**
     * Every window in the list, one line, for the log.
     *
     * Bounds, type and id are what decide whether a window can host a caption
     * (see pickHost), so a task that ends up with no bar of ours has to say
     * what the list actually contained — guessing at it from a screenshot is
     * how a real bug survives two releases.
     */
    private static String describe(List<AccessibilityWindowInfo> windows) {
        StringBuilder sb = new StringBuilder();
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityWindowInfo w : windows) {
            w.getBoundsInScreen(r);
            if (sb.length() > 0) sb.append(" | ");
            sb.append('#').append(w.getId())
                    .append(" t=").append(w.getType())
                    .append(" [").append(r.left).append(',').append(r.top)
                    .append(' ').append(r.right).append(',').append(r.bottom).append(']')
                    .append(" \"").append(w.getTitle()).append('"');
        }
        return sb.toString();
    }

    /** One of our own bars, by the title stamped on it in {@link #params}. */
    private static boolean isOurs(AccessibilityWindowInfo w) {
        CharSequence t = w.getTitle();
        return t != null && CAPTION_WINDOW_TITLE.contentEquals(t);
    }

    /**
     * The app window, but only once this task has waited HOST_GRACE_MS for a shell caption
     * window that never came.
     *
     * The clock starts on the first pass this task wanted a host and had none, and is
     * cleared the moment a shell caption does turn up, so a task that merely blinks never
     * reaches the grace. A task already wearing an app-window bar keeps returning here
     * (same window, so ensureCaption is a no-op) until pickHost finds something better.
     *
     * @return the host to attach to, or null meaning "not yet — keep waiting".
     */
    private AccessibilityWindowInfo hostOnAppWindow(List<AccessibilityWindowInfo> windows,
                                                    WmClient.Task task) {
        // Only ever dress an UNDRESSED window. A task that already has a bar is having its
        // shell caption blink out of the list — the normal case, not an absence — and
        // moving it to the app window and back on every blink rebuilds the surface twice a
        // second. Measured doing exactly that before this guard: 231 -> 233 -> 231 in nine
        // seconds. Keeping the bar it has is what the null-host path already does.
        if (captions.get(task.taskId) != null) return null;
        long now = SystemClock.uptimeMillis();
        boolean captionless = captionless(task.displayId);
        Long since = hostWaitSince.get(task.taskId);
        if (since == null) {
            hostWaitSince.put(task.taskId, now);
            if (!captionless) return null;
            since = now;
        }
        if (!captionless && now - since < HOST_GRACE_MS) return null;
        AccessibilityWindowInfo app = appHost(windows, task);
        if (app != null) {
            trace("no caption host for task " + task.taskId
                    + " after " + (now - since) + "ms — hosting on its app window"
                    + (captionless ? " (this display's shell draws none)" : ""));
            if (!hostDisplays.contains(task.displayId)) captionlessDisplays.add(task.displayId);
        }
        return app;
    }

    /**
     * Attach the bar INSIDE One UI's own caption window for this task.
     *
     * This is the whole design, and everything good about it follows from where the surface
     * lives rather than from code here. Inside the platform's caption window our surface is
     * a descendant of the task, so it moves with the window, clips with it, stacks with it,
     * and disappears behind whatever the window disappears behind — all for free, at the
     * compositor's expense rather than ours. There is no lag because nothing has to
     * reposition it, and no occlusion logic because nothing has to compute it.
     *
     * The alternatives were both tried and are both worse:
     *  - the app's own window: also glued, but permanently BELOW One UI's caption, which is
     *    a sibling of the app window in the task's decor container at z=30000. Invisible.
     *  - the DISPLAY's accessibility overlay: always visible, but above every app window, so
     *    it has to be moved by the reconcile loop (visible lag when dragging) and manually
     *    cropped against the windows in front (a rectangle cannot express an occluder that
     *    lands in the middle of a bar, so bars end up visibly cut).
     *
     * The one weakness is that the host window is not always in the accessibility window
     * list — measured missing for roughly one task in two, transiently. That is what made
     * captions "sometimes not appear", and it is a retry problem, not a design problem:
     * pickHost is re-evaluated every pass and this attaches as soon as the window shows up.
     */
    /**
     * Keep an existing bar's title and size current. Deliberately independent of the host
     * window, so a resize lands even on the passes where the host is not in the list.
     *
     * @return true if the bar is now correct and needs no rebuild.
     */
    private boolean syncCaption(WmClient.Task task) {
        Caption c = captions.get(task.taskId);
        if (c == null) return false;
        c.title.setText(labelFor(task));

        int width = task.right - task.left;
        int height = captionHeight(task.taskId);
        if (c.width == width && c.height == height) return true;
        // The button set is a function of the width, and the set this bar
        // carries was chosen for the width it was BUILT at. When the answer
        // changes the bar has to be made again — a relayout would only stretch
        // a bar that is wearing the wrong buttons.
        if (roomForSnaps(width) != c.snaps && !WidgetDetourActivity.isDetourTask(task.taskId)) {
            return false;
        }
        if (roomForSnaps(width) != c.snaps) {
            // a detour bar has no snaps at all: relayout and let it be
            c.snaps = roomForSnaps(width);
        }
        if (!relayout(c.host, width, height)) return false;   // caller rebuilds
        c.width = width;
        c.height = height;
        moved = true;                                          // a resize is motion
        return true;
    }

    private void ensureCaption(AccessibilityWindowInfo host, WmClient.Task task) {
        int id = host.getId();
        int width = task.right - task.left;
        int height = captionHeight(task.taskId);
        Caption c = captions.get(task.taskId);

        // Already correct, and still on the window we think it is.
        if (c != null && c.windowId == id && c.width == width && c.height == height) return;
        if (c != null) {
            release(c);
            captions.remove(task.taskId);
        }

        try {
            Caption made = build(task, width, height);
            if (made == null) return;
            SurfaceControl sc = surfaceOf(made);
            if (sc == null) {
                release(made);
                return;
            }
            // Host-relative, and the host is not always at the task's origin. One UI's
            // caption window is (offset 0), but an app window on a shell without captions
            // may start below the strip we reserved, and a bar pinned to its top-left
            // would then sit ON the app's content with the strip left empty above it.
            // Measure the offset instead of assuming it; the surface is cropped to the
            // TASK, not to the host, so a negative y is legal and lands in the strip.
            android.graphics.Rect hb = new android.graphics.Rect();
            host.getBoundsInScreen(hb);
            final float dy = task.top - hb.top;
            attachAccessibilityOverlayToWindow(id, sc);
            // The attach only reparents and marks the surface trusted — it never shows it.
            // In normal SurfaceControlViewHost use the host SurfaceView does that
            // (SurfaceView#setChildSurfacePackage calls show). Nothing does it for an
            // accessibility overlay, so the surface sits in the tree permanently
            // "hidden by ... layer flag". We own the layer, so we show it ourselves and
            // lift it above the host's own content.
            new SurfaceControl.Transaction()
                    .setVisibility(sc, true)          // show() is @hide; this is the public one
                    .setLayer(sc, Integer.MAX_VALUE)
                    .setPosition(sc, 0f, dy)          // host-relative: the task moves us
                    .apply();
            made.windowId = id;
            captions.put(task.taskId, made);
            // The real caption now covers the platform's — retire the launch curtain and
            // stop curtaining this task (a later host blink-out must not re-cover it).
            noCurtain.add(task.taskId);
            hideCurtain(task.taskId);
            moved = true;          // a resize is motion too — keep polling fast
            trace("attached window=" + id + " task=" + task.taskId
                    + " w=" + width + " h=" + height + " sc=" + sc.isValid());
        } catch (Throwable t) {
            trace("attach failed task=" + task.taskId + ": " + t);
        }
    }

    private static SurfaceControl surfaceOf(Caption c) {
        return c.host == null || c.host.getSurfacePackage() == null
                ? null : c.host.getSurfacePackage().getSurfaceControl();
    }

    /**
     * The bar itself: the title, the buttons, the resize grip.
     *
     * Lifted out of {@link #build} so the display-level fallback in
     * {@link #showFloatingBar} can put up the SAME bar. That matters more than
     * it looks: the fallback exists for the windows where the shell's caption
     * host is never found, and if it drew a different bar the two paths would
     * disagree about which buttons a given width has room for — which is the
     * bug that comes back as "sometimes there is no minimise".
     *
     * @param c the bar's bookkeeping record; its {@code title} is set here.
     */
    private LinearLayout barView(android.content.Context ui, WmClient.Task task,
                                 int width, int height, Caption c) {
        DexTheme theme = DexTheme.of(this);
        LinearLayout bar = new LinearLayout(ui);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Always opaque: this strip is inside the app's own window surface, so
        // anything see-through here would show the app, not the desktop. In
        // Paper mode it carries the same grain as the rest of the chrome.
        bar.setBackground(theme.surface(theme.caption, 0f));
        bar.setPadding(px(10), 0, 0, 0);
        // The strip is the window's move handle — its drag listener is attached
        // below — so it says so. Whether a pointer resolves at all here is the
        // one open question in this file: the bar lives in a
        // SurfaceControlViewHost reparented into the APP's window surface, not
        // in a window of ours, and if hover does not cross that boundary the
        // platform arrow simply stands. That is an acceptable outcome; nothing
        // else depends on it.
        DexCursors.apply(bar, DexCursors.ROLE_GRAB);

        c.title = new TextView(ui);
        c.title.setText(labelFor(task));
        c.title.setTextColor(theme.textDim);
        // Absolute px against the desktop display: the strip is sized in its pixels (px),
        // so the text has to be sized in the same space rather than in scaled sp.
        c.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        c.title.setSingleLine(true);
        bar.addView(c.title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Snap to half the display, left or right — the same controls the commercial DeX
        // exposes from its title-bar dropdown, as explicit buttons here (a PopupWindow inside
        // a SurfaceControlViewHost on a virtual display is unreliable; buttons are not).
        //
        // The widget detour's window is an alert box waiting for an answer, so it gets none
        // of them. Snapping and maximising resize a dialog that will not grow, and — is
        // worse than useless: onMinimise HIDES the task, a hidden dialog delivers no result,
        // and the add would hang with an allocated widget id nobody can reach. ✕ stays,
        // because ours is a real close (wmd removeTask, not One UI's hide), which the detour
        // turns into a clean cancel.
        final boolean detour = WidgetDetourActivity.isDetourTask(task.taskId);
        final int btnPx = buttonWidth();
        // A Horizontal LinearLayout clips from its RIGHT end when it runs out of
        // room, and the title next to it has weight 1 — so a narrow window used
        // to eat the buttons right to left and end up wearing a ✕ and nothing
        // else. That is exactly how a window comes to have "no minimise
        // option". Decide from the surface's own width instead: the close, the
        // minimise, the maximise and the resize grip are fixed costs, and the
        // two snap buttons are given up first when there is no room for them.
        final boolean roomForSnaps = !detour && roomForSnaps(width);
        c.snaps = roomForSnaps;
        if (!detour) {
            if (roomForSnaps) {
                bar.addView(button(ui, "◧", false, v -> onSnapLeft(task)), buttonParams(height));
                bar.addView(button(ui, "◨", false, v -> onSnapRight(task)), buttonParams(height));
            }
            bar.addView(button(ui, "—", false, v -> onMinimise(task)), buttonParams(height));
            // ▢ takes a long press as well: the snap layouts. The click stays
            // maximise, because that is what the button has always done and
            // what the glyph says — the long press is the second thing it can
            // do, not a replacement.
            TextView maxBtn = button(ui, "▢", false, v -> onMaximise(task));
            maxBtn.setOnLongClickListener(v -> {
                showLayoutPicker(task, v);
                return true;
            });
            bar.addView(maxBtn, buttonParams(height));
        }
        // Resize, where the hand already is: a grip in the title bar that pulls
        // the bottom-right corner. The window's own bottom-right corner is not
        // reachable — our surface is the strip — so the corner comes to the
        // pointer instead. See CaptionChrome for the two edge bands as well.
        if (!detour) {
            bar.addView(CaptionChrome.grip(ui, resizerFor(task), "⤡", px(13), theme.textDim, btnPx),
                    buttonParams(height));
        }
        bar.addView(button(ui, "✕", true, v -> onClose(task)), buttonParams(height));
        bar.setOnTouchListener(dragHandler(c, task, bar));
        return bar;
    }

    private Caption build(WmClient.Task task, int width, int height) {
        Display display = getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(task.displayId);
        // Views MUST be inflated against the target display's Context, not the service's.
        // The service's resources describe the phone panel (~450dpi); the desktop runs at
        // its own — 160dpi on a 1x screen, 320 on a Retina one. Using the wrong one scales
        // every sp/dp by up to ~2.8 and the caption comes out enormous.
        final android.content.Context ui = createDisplayContext(display);

        Caption c = new Caption();
        c.taskId = task.taskId;
        c.width = width;
        c.height = height;
        c.host = newHost(display);
        if (c.host == null) return null;

        LinearLayout bar = barView(ui, task, width, height, c);
        if (bar == null) {
            release(c);
            return null;
        }


        // The bar never quite reaches the window's edges: the resize bands live
        // in that margin, and a button sitting under one would answer the resize
        // instead of its own click.
        CaptionChrome chrome = new CaptionChrome(ui, resizerFor(task));
        chrome.setBar(bar, px(CaptionChrome.bandDp()));

        setView(c.host, chrome, width, height);
        return c;
    }

    /** The width of every caption button, in the desktop display's px. */
    private int buttonWidth() {
        return px(DexTheme.of(this).win11 ? 46 : 38);
    }

    /**
     * Is this bar wide enough for the two snap buttons?
     *
     * The fixed costs are close, minimise, maximise, the resize grip and the
     * gap the resize bands need at either end; the snaps are what is given up
     * first. Read by {@link #build} when the bar is made and by
     * {@link #syncCaption} on every resize, so the two can never disagree about
     * which set a given width deserves.
     */
    private boolean roomForSnaps(int width) {
        int budget = width - px(10) - 2 * px(CaptionChrome.bandDp());
        return budget >= buttonWidth() * 6 + px(70);
    }

    /**
     * What a resize gesture needs to know about one task, handed to
     * {@link CaptionChrome}.
     *
     * The rect is read from {@link #tasks} on every call rather than captured:
     * the listener outlives the rect it was built with, and a capture would
     * resize the window from wherever it was when the bar was first attached.
     */
    private CaptionChrome.Resizer resizerFor(WmClient.Task task) {
        final int id = task.taskId;
        final int display = task.displayId;
        return new CaptionChrome.Resizer() {
            @Override
            public int[] rect() {
                WmClient.Task t = tasks.get(id);
                return t == null ? null : new int[]{t.left, t.top, t.right, t.bottom};
            }

            @Override
            public int[] limits() {
                return maxRect(display);
            }

            @Override
            public void apply(int left, int top, int right, int bottom) {
                final int strip = captionHeight(id);
                maximized.remove(id);
                // The new rect is this window's restore target from now on: a
                // hand resize is the user saying where it belongs.
                restoreBounds.put(id, new int[]{left, top, right, bottom});
                WmClient.Task t = tasks.get(id);
                if (t != null) {
                    t.left = left;
                    t.top = top;
                    t.right = right;
                    t.bottom = bottom;
                }
                wm.post(() -> wm.resize(display, id, left, top, right, bottom, strip));
            }

            @Override
            public void onResizedByHand() {
                maximized.remove(id);
            }

            @Override
            public int px(int dp) {
                return CaptionService.this.px(dp);
            }
        };
    }

    /**
     * A SurfaceControlViewHost needs an InputTransferToken. Which one is right depends
     * entirely on where the surface is going to live.
     *
     * For an overlay attached to a WINDOW it must belong to a live window, so that
     * WindowlessWindowManager#addToDisplay -> IWindowSession#grantInputChannel accepts it;
     * a freshly-minted Binder fails in the worst possible way, with the constructor and the
     * attach both succeeding and the surface simply never appearing in the layer tree.
     *
     * For an overlay attached to a DISPLAY — what we do now — a NEW token is correct, and
     * borrowing one is actively wrong: the borrowed token parents our surface under that
     * window, and the reparent onto the display's overlay layer then leaves it with no
     * parent at all. Same silent symptom, opposite cause.
     */
    private SurfaceControlViewHost newHost(Display display) {
        Object token = anchorToken(display);
        if (token == null) {
            if (ticks % 25 == 0) trace("no host token yet");
            return null;
        }
        try {
            return SurfaceControlViewHost.class
                    .getConstructor(android.content.Context.class, Display.class,
                            Class.forName("android.window.InputTransferToken"))
                    .newInstance(this, display, token);
        } catch (Throwable t) {
            trace("SurfaceControlViewHost failed: " + t);
            return null;
        }
    }

    /** 1x1 invisible window that exists only to own a valid host token. */
    private View anchorView;
    private int anchorDisplay = -1;

    /**
     * Our own token source, so captions do not depend on the launcher Activity being up.
     * An AccessibilityService may add TYPE_ACCESSIBILITY_OVERLAY windows without
     * SYSTEM_ALERT_WINDOW, and a window context binds it to the desktop display.
     *
     * The anchor draws nothing and takes no input; only its InputTransferToken matters.
     * A window overlay needs a token belonging to a LIVE window — a freshly minted Binder,
     * or a fresh InputTransferToken, fails in the worst possible way: the constructor and
     * the attach both succeed and the surface never appears in the layer tree.
     */
    private Object anchorToken(Display display) {
        try {
            if (anchorView == null || anchorDisplay != display.getDisplayId()) {
                dropAnchor();
                android.content.Context ctx = createDisplayContext(display)
                        .createWindowContext(
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
                View v = new View(ctx);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                ctx.getSystemService(WindowManager.class).addView(v, lp);
                anchorView = v;
                anchorDisplay = display.getDisplayId();
                trace("anchor added on display " + anchorDisplay);
            }
            android.view.AttachedSurfaceControl root = anchorView.getRootSurfaceControl();
            return root == null ? null : root.getInputTransferToken();
        } catch (Throwable t) {
            trace("anchor failed: " + t);
            return null;
        }
    }

    private void dropAnchor() {
        if (anchorView == null) return;
        try {
            anchorView.getContext().getSystemService(WindowManager.class)
                    .removeViewImmediate(anchorView);
        } catch (Throwable ignored) {
        }
        anchorView = null;
        anchorDisplay = -1;
    }

    /**
     * Only {@code setView(View, int, int)} is public, and it builds LayoutParams with
     * {@code flags = 0} — i.e. a focusable window. We want FLAG_NOT_FOCUSABLE so keyboard
     * focus stays with the app while touch still lands on the strip (input dispatch does
     * not require focus for touch). The LayoutParams overload that would let us say so is
     * {@code @hide}, so try it reflectively and fall back to the public one.
     */
    private static void setView(SurfaceControlViewHost host, View view, int w, int h) {
        try {
            SurfaceControlViewHost.class
                    .getMethod("setView", View.class, WindowManager.LayoutParams.class)
                    .invoke(host, view, params(w, h));
            return;
        } catch (Throwable ignored) {
            // hidden-API enforcement or a signature change — public path below still works
        }
        host.setView(view, w, h);
    }

    private static WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        // So a host picker can recognise our own bars in the window list — see isOurs.
        // The field is @hide, hence the reflection, and the whole thing is optional: if it
        // does not land, or an embedded hierarchy never surfaces the title, every picker
        // still rejects our bars on height. This is only ever a second, cheaper filter.
        try {
            WindowManager.LayoutParams.class
                    .getField("accessibilityTitle")
                    .set(lp, CAPTION_WINDOW_TITLE);
        } catch (Throwable ignored) {
        }
        return lp;
    }

    /**
     * Resize an existing bar in place.
     *
     * Rebuilding instead would need the host window, and the host is missing on a fair
     * share of passes — so a window resized while it is missing would keep a bar at the old
     * width. The surface is then clipped to the (narrower) window and the buttons, which
     * sit at the right end, simply vanish until the window is widened again.
     *
     * Note the public {@code relayout(int,int)} builds LayoutParams with {@code flags = 0},
     * i.e. a FOCUSABLE window — the same trap as {@code setView}. Every resize would hand
     * the bar keyboard focus and take it from the app. Prefer the LayoutParams overload.
     *
     * @return false if the host cannot be resized, in which case the caller rebuilds.
     */
    private static boolean relayout(SurfaceControlViewHost host, int w, int h) {
        if (host == null) return false;
        try {
            SurfaceControlViewHost.class
                    .getMethod("relayout", WindowManager.LayoutParams.class)
                    .invoke(host, params(w, h));
            return true;
        } catch (Throwable ignored) {
            // fall through to the public form
        }
        try {
            host.relayout(w, h);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The desktop display's density, as a multiple of 160 dpi — what every size in here is
     * drawn at. This was written when the desktop ran at 1.0 and px was dp; a display the
     * desktop app fits to a Retina screen runs at 2.0, and a bar sized at 1.0 there is half
     * a title bar with One UI's own showing underneath. Followed every pass, see
     * {@link #trackDensity}.
     */
    private float density = 1f;

    /** dp → px on the desktop display. */
    private int px(int dp) {
        return Math.round(dp * density);
    }

    /**
     * Follow the desktop display's density. On a change — the Display size setting, a new
     * session on a different computer screen — the bars and the heights measured for them
     * are in the old pixels, so they are dropped and the next pass builds them again.
     *
     * Read off the Display (getRealMetrics), which is current the moment `wm density`
     * lands: the service's own Resources describe the phone panel, and a display
     * context's would be one more Context per pass.
     */
    private void trackDensity(int display) {
        float d;
        try {
            Display disp = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(display);
            if (disp == null) return;
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            disp.getRealMetrics(dm);
            d = dm.densityDpi / 160f;
        } catch (Throwable t) {
            return;
        }
        if (d <= 0f || d == density) return;
        density = d;
        for (int i = 0; i < captions.size(); i++) release(captions.valueAt(i));
        captions.clear();
        captionHeights.clear();
        trace("desktop density " + d + " — captions dropped for rebuild");
    }

    private LinearLayout.LayoutParams buttonParams(int height) {
        // 46px under the Windows 11 shell, which is the width its own caption
        // buttons are; ours is narrower because the DeX strip carries more.
        return new LinearLayout.LayoutParams(buttonWidth(), height);
    }

    /**
     * One caption control.
     *
     * {@code close} marks the ✕, and only matters under the Windows 11 shell:
     * a red close button is the one piece of that title bar everybody can name,
     * and it is also a real affordance — the destructive control being the one
     * you cannot hit by accident without seeing it go red first.
     *
     * The fills are a StateListDrawable rather than a ripple: this view lives
     * in a SurfaceControlViewHost reparented into the app's own window surface,
     * where whether hover crosses the boundary at all is the open question of
     * this file (see build). Pressed does cross it, so the button is never
     * silent even where hover turns out not to resolve.
     */
    private TextView button(android.content.Context ui, String glyph, boolean close,
                            View.OnClickListener onClick) {
        DexTheme theme = DexTheme.of(this);
        TextView b = new TextView(ui);
        b.setText(glyph);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(onClick);

        boolean hot = close && theme.win11;
        int lit = hot ? 0xFFc42b1c : theme.hover;
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(lit));
        bg.addState(new int[]{android.R.attr.state_hovered}, new ColorDrawable(lit));
        bg.addState(new int[0], new ColorDrawable(0x00000000));
        b.setBackground(bg);
        b.setTextColor(hot
                // white on the red, in both themes — the fill is the same red
                // either way, so the ink over it cannot follow the palette
                ? new ColorStateList(
                        new int[][]{{android.R.attr.state_pressed},
                                {android.R.attr.state_hovered}, {}},
                        new int[]{0xFFffffff, 0xFFffffff, theme.textDim})
                : ColorStateList.valueOf(theme.textDim));

        DexCursors.apply(b, DexCursors.ROLE_HAND);
        return b;
    }

    /**
     * Drag the window by its title bar.
     *
     * The bar is a child of the app's window surface, so it travels with the task for
     * free — we only have to move the task. Bounds are pushed through the daemon's MOVE,
     * which uses applyTransaction (measured 2.49 ms on this device) rather than a
     * transition: transitions serialize behind one another and cannot be issued per
     * frame, applyTransaction can.
     *
     * Latest-wins coalescing, never drop: the final frame of a gesture carries the final
     * position, so discarding it would leave the window short of where it was released.
     */
    /**
     * Is this display point inside one of the bar's clickable children?
     *
     * Asked in DISPLAY coordinates off the view's own placement, because the bar
     * is not necessarily laid out where the service thinks it is: the host owns
     * the surface's position, and a stale {@code task.left} is exactly the case
     * this has to survive.
     */
    private static boolean overButton(View bar, int rawX, int rawY) {
        if (!(bar instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) bar;
        int[] loc = new int[2];
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!child.isClickable() || child.getVisibility() != View.VISIBLE) continue;
            child.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX < loc[0] + child.getWidth()
                    && rawY >= loc[1] && rawY < loc[1] + child.getHeight()) {
                return true;
            }
        }
        return false;
    }

    private View.OnTouchListener dragHandler(Caption caption, WmClient.Task task) {
        return dragHandler(caption, task, null);
    }

    /**
     * The same handler, told which bar it belongs to so it can stand down over
     * the buttons.
     *
     * A ViewGroup gives its children the touch first, so this only ever runs for
     * a press the buttons did not take — but the surface here is reparented into
     * ANOTHER app's window, and on that path hit-testing has been observed to
     * miss: the press reaches the bar, the bar starts a drag, and the ✕ or the
     * ▢ that was pressed never fires. That is the "one click does nothing, the
     * second one works" report, and the black frame between them is the app
     * relaying out for a window that was dragged by a pixel instead of
     * maximised. So: if the press lands on a child that is clickable, this
     * returns false immediately and the tap goes where it was aimed.
     */
    private View.OnTouchListener dragHandler(Caption caption, WmClient.Task task, View bar) {
        return new View.OnTouchListener() {
            private int grabDx, grabDy;
            private boolean dragging;
            private int wantX, wantY;
            private boolean pending;
            /** Uptime of the last tap that was a tap, not a drag. 0 = no tap pending. */
            private long lastTap;
            private int lastTapX, lastTapY;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int rawX = (int) e.getRawX();
                int rawY = (int) e.getRawY();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        if (overButton(bar, rawX, rawY)) return false;
                        // Double-click the bar to maximise/restore, as on Windows. Only the
                        // empty space gets here: the buttons are child views and consume
                        // their own taps, so this cannot fire from ✕ or ▢. The title is a
                        // plain TextView, not clickable, so it counts as empty space.
                        if (lastTap != 0
                                && SystemClock.uptimeMillis() - lastTap <= DOUBLE_TAP_MS
                                && Math.abs(rawX - lastTapX) <= px(DOUBLE_TAP_SLOP_DP)
                                && Math.abs(rawY - lastTapY) <= px(DOUBLE_TAP_SLOP_DP)) {
                            lastTap = 0;          // consumed: a third tap starts a new pair
                            dragging = false;
                            onMaximise(task);
                            return true;
                        }
                        grabDx = rawX - task.left;
                        grabDy = rawY - task.top;
                        dragging = false;
                        wm.post(() -> wm.front(task.displayId, task.taskId));
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!dragging
                                && Math.abs(rawX - (task.left + grabDx)) < px(TOUCH_SLOP_DP)
                                && Math.abs(rawY - (task.top + grabDy)) < px(TOUCH_SLOP_DP)) {
                            return true;   // still a tap; let the buttons win
                        }
                        dragging = true;
                        // Dragging a maximised window ungrips it, so the maximise button goes
                        // back to "maximise" and does not try to restore to a stale rect.
                        maximized.remove(task.taskId);
                        // No surface move here. The bar lives inside the task, so moving
                        // the task moves it — the compositor keeps them together with no
                        // lag and nothing for us to synchronise.
                        send(rawX - grabDx, rawY - grabDy);
                        // Where this would land, shown while the pointer is near an
                        // edge so the release holds no surprise. Decided from the
                        // POINTER, not the window: the title bar is dragged by
                        // whatever part of it was grabbed, so a wide window can be
                        // flush left while the pointer is still in the middle.
                        showZonePreview(task.displayId, rawX, rawY);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            clearZonePreview();
                            send(rawX - grabDx, rawY - grabDy);   // replay the last point
                            dragging = false;
                            lastTap = 0;   // a drag is not half of a double-click
                            // Drag to an edge and let go: fill that half, as Windows
                            // and every tiling desktop do. It is the gesture people
                            // already make, and without it the only way to place two
                            // windows side by side is two trips to the title bar.
                            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                dropToZone(task);
                            } else {
                                clearZonePreview();
                            }
                            return true;
                        }
                        // Remember it as the first half of a possible double-click. Doing
                        // this on UP rather than DOWN is what makes a drag-then-tap not
                        // count as one.
                        lastTap = SystemClock.uptimeMillis();
                        lastTapX = rawX;
                        lastTapY = rawY;
                        return false;      // a tap — let onClick run
                }
                return false;
            }

            private void send(int x, int y) {
                wantX = x;
                wantY = y;
                if (pending) return;       // a pump is already in flight; it will see the
                pending = true;            // newest position when it drains
                wm.post(this::pump);
            }

            private void pump() {
                int x, y;
                synchronized (this) {
                    x = wantX;
                    y = wantY;
                    pending = false;
                }
                wm.move(task.displayId, task.taskId, x, y);
                task.left = x;
                task.top = y;
            }
        };
    }

    /**
     * How close to an edge a pointer has to be, in desktop px, for that edge to
     * mean "put it there".
     *
     * Generous on purpose — the same order as Windows' own snap band, wide
     * enough that a quick flick at an edge needs no pixel aim, and capped
     * against the display in {@link SnapZones#at} so a small display keeps a
     * reachable middle.
     */
    private int snapBandPx() {
        return px(28);
    }

    /** The lowest row a window may occupy: the display less whatever the bar takes. */
    private int snapBottom(int display) {
        android.graphics.Point size = displaySize(display);
        return size.y - barPx(display);
    }

    /**
     * Which zone the pointer is over right now, and the ghost drawn there.
     *
     * Decided from the POINTER rather than the window's top-left, which is what
     * the old edge test used: the title bar is dragged by whatever part of it
     * was grabbed, so a wide window can be flush against an edge while the
     * pointer is nowhere near it — and, worse, the window's corner can reach a
     * corner zone the user was not aiming at.
     *
     * Idempotent, so it can run on every move event: an unchanged zone costs
     * nothing.
     */
    private void showZonePreview(int display, float x, float y) {
        android.graphics.Point size = displaySize(display);
        int bottom = snapBottom(display);
        int zone = SnapZones.at(size.x, bottom, x, y, snapBandPx());
        if (zone == dragZone) return;
        dragZone = zone;
        if (zone == SnapZones.NONE) {
            if (snapOverlay != null) snapOverlay.hidePreview();
            return;
        }
        int[] rect = SnapZones.rect(zone, size.x, bottom);
        if (rect != null) overlay().preview(display, rect);
    }

    private void clearZonePreview() {
        dragZone = SnapZones.NONE;
        if (snapOverlay != null) snapOverlay.hidePreview();
    }

    /**
     * The release: put the window in the zone the ghost was showing, or leave it
     * exactly where it was dropped.
     *
     * Reading {@link #dragZone} rather than testing the pointer again is what
     * makes the ghost honest — a release a pixel outside the band still lands
     * where the user was told it would, because the ghost was drawn for that
     * zone and the finger never left it.
     */
    private void dropToZone(WmClient.Task task) {
        int zone = dragZone;
        clearZonePreview();
        if (zone == SnapZones.NONE) return;
        if (zone == SnapZones.MAXIMISE) {
            onMaximise(task);
        } else {
            snapTo(task, zone);
        }
    }

    /**
     * Long-press on ▢: the snap layouts, as a picker.
     *
     * ◧ and ◨ place the two halves already, but a corner quarter had no gesture
     * of its own until drag-to-corner existed — and a drag is a poor way to
     * arrange four windows. The picker is the explicit route, and it sits on ▢
     * because that button's whole subject is "make this bigger".
     */
    private void showLayoutPicker(WmClient.Task task, View anchor) {
        final int display = task.displayId;
        final android.graphics.Point size = displaySize(display);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int anchorX = loc[0] + anchor.getWidth() / 2;
        int anchorY = loc[1] + anchor.getHeight() + px(6);
        overlay().picker(display, new int[]{size.x, size.y}, snapBottom(display),
                anchorX, anchorY, zone -> {
                    WmClient.Task live = tasks.get(task.taskId);
                    WmClient.Task t = live != null ? live : task;
                    if (zone == SnapZones.MAXIMISE) onMaximise(t);
                    else snapTo(t, zone);
                });
    }

    /** The overlay, built on first use. */
    private SnapOverlay overlay() {
        if (snapOverlay == null) snapOverlay = new SnapOverlay(this);
        return snapOverlay;
    }

    /** Movement under this is a click on the bar, not the start of a drag. */
    private static final int TOUCH_SLOP_DP = 6;

    /** Two taps this close together, in time and in space, are a double-click. */
    private static final long DOUBLE_TAP_MS =
            android.view.ViewConfiguration.getDoubleTapTimeout();
    /**
     * In the desktop display's dp — the scaled value from ViewConfiguration describes the
     * phone panel (~450dpi) and would be ~3x too large here. Deliberately looser than
     * TOUCH_SLOP_DP: a double-click is aimed once and clicked twice, and a few px of drift
     * between the two must not lose the gesture.
     */
    private static final int DOUBLE_TAP_SLOP_DP = 24;

    /**
     * Window title: the top activity's label, falling back to the application
     * label and finally the package name.
     *
     * Activity first because that is what a window is — most apps leave the
     * activity label unset and {@code ActivityInfo#loadLabel} then returns the
     * application label anyway, so this only differs where the app meant it to
     * (our own Settings window is titled "Settings", not "Open Android DeX").
     */
    private String labelFor(WmClient.Task task) {
        PackageManager pm = getPackageManager();
        if (task.activity != null && !task.activity.isEmpty() && !"?".equals(task.activity)) {
            try {
                return pm.getActivityInfo(
                        new ComponentName(task.pkg, task.activity), 0).loadLabel(pm).toString();
            } catch (Exception ignored) {
                // aliases and non-exported entry points can fail to resolve
            }
        }
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(task.pkg, 0)).toString();
        } catch (Exception e) {
            return task.pkg;
        }
    }

    // ── caption actions ────────────────────────────────────────────────────

    /**
     * ✕ — a real close (wmd removeTask), not One UI's hide.
     *
     * Our own windows that would end something running — Linux, Docker, File
     * transfer, a Linux app's — are ASKED rather than closed, and close
     * themselves once the user confirms (OwnWindows.askToClose). Everything
     * else, including our own Settings window, closes here as before.
     */
    private void onClose(WmClient.Task task) {
        final int display = task.displayId;
        final int id = task.taskId;
        if (getPackageName().equals(task.pkg) && OwnWindows.askToClose(this, id, task.activity)) {
            return;
        }
        wm.post(() -> {
            wm.unstrip(display, id);
            wm.close(id);
        });
    }

    /**
     * Maximise, or restore if already maximised.
     *
     * The size to restore to is whatever the window had the moment it was first maximised or
     * snapped, so "maximise then bring it back" lands on exactly the old geometry. Both legs
     * go through RESIZE, which sets bounds and the caption strip in one animated transition —
     * the old path did a bare BOUNDS and let the reconcile re-strip a frame later, which is
     * the maximise/restore flicker.
     *
     * WHICH leg to take is decided from the window's LIVE rect, not from the {@link
     * #maximized} set. That set is a belief nothing ever reconciles against the screen, and
     * it goes out of phase every time something other than this button resizes the window:
     * the taskbar's app menu, the PC enforcer's own toggle, One UI, or simply this service
     * being restarted on a reconnect while everybody else's state survives. Out of phase, a
     * press meant to MAXIMISE ran the restore leg and dropped the window onto a stale
     * remembered rect — after a snap that rect is the left half of the display, which is
     * exactly the "maximise does not go full width" report. The rect on screen cannot be out
     * of phase with itself, so it is the only safe thing to branch on.
     */
    private void onMaximise(WmClient.Task task) {
        final int display = task.displayId;
        final int id = task.taskId;
        final int px = captionHeight(id);
        // The poll-refreshed record, never the snapshot this listener closed over when the
        // bar was built: pump() only ever writes left/top back, so a captured right/bottom
        // can be several resizes old.
        final WmClient.Task live = tasks.containsKey(id) ? tasks.get(id) : task;
        final int[] max = maxRect(display);
        if (isMaxed(live)) {
            maximized.remove(id);
            int[] r = restoreBounds.remove(id);
            // Never "restore" to the window's current rect: at this point that IS the
            // maximise pin, so the press would do nothing and the button reads as dead.
            final int[] rr = r != null ? r : defaultRestore(display);
            trace("restore task " + id + " -> " + rr[0] + "," + rr[1] + "," + rr[2] + ","
                    + rr[3]);
            wm.post(() -> wm.resize(display, id, rr[0], rr[1], rr[2], rr[3], px));
            return;
        }
        rememberRestore(live);
        maximized.add(id);
        trace("maximise task " + id + " -> " + max[0] + "," + max[1] + "," + max[2] + ","
                + max[3]);
        wm.post(() -> wm.resize(display, id, max[0], max[1], max[2], max[3], px));
    }

    private void onSnapLeft(WmClient.Task task) {
        snapTo(task, SnapZones.LEFT);
    }

    private void onSnapRight(WmClient.Task task) {
        snapTo(task, SnapZones.RIGHT);
    }

    /**
     * Put a window in one of the six zones; a snapped window is not "maximised".
     *
     * Height stops at the taskbar — or at the display's edge while the bar hides
     * itself — for the same reason maximise does: the taskbar is an overlay
     * drawn above app windows, so the rows underneath it are pixels the window
     * can never show. It matters twice over here: the PC enforcer adopts
     * whatever rect a non-maximised window is observed at as that task's
     * restore target, so a snap that runs under the taskbar teaches the PC to
     * restore under the taskbar too.
     */
    private void snapTo(WmClient.Task task, int zone) {
        final int display = task.displayId;
        final int id = task.taskId;
        final android.graphics.Point size = displaySize(display);
        final int[] rect = SnapZones.rect(zone, size.x, snapBottom(display));
        if (rect == null) return;
        rememberRestore(tasks.containsKey(id) ? tasks.get(id) : task);
        maximized.remove(id);
        final int strip = captionHeight(id);
        trace("snap task " + id + " -> " + SnapZones.name(zone) + " "
                + rect[0] + "," + rect[1] + "," + rect[2] + "," + rect[3]);
        WmClient.Task live = tasks.get(id);
        if (live != null) {
            live.left = rect[0];
            live.top = rect[1];
            live.right = rect[2];
            live.bottom = rect[3];
        }
        wm.post(() -> wm.resize(display, id, rect[0], rect[1], rect[2], rect[3], strip));
    }

    /** Capture the window's current rect as its restore target, once, until it is restored. */
    private void rememberRestore(WmClient.Task task) {
        if (!restoreBounds.containsKey(task.taskId)) {
            restoreBounds.put(task.taskId,
                    new int[]{task.left, task.top, task.right, task.bottom});
        }
    }

    /**
     * The desktop display's size, in ITS pixels. Feeds maximise and both snaps, so every
     * rect those three produce is only as right as this is.
     *
     * Asked through a WINDOW context, and the "window" there is load-bearing.
     *
     * A display context alone is NOT enough, and this is measured rather than reasoned:
     * {@code createDisplayContext(d).getSystemService(WindowManager.class)} is a non-UI
     * context, so the WindowManager it hands back is not bound to that display — it answers
     * for the process's own, which for this service is the phone panel. On SM-S938B, with
     * the desktop at 1920x1080 (display 82), it returned 1080x2340 and maximise traced
     * {@code 0,0,1076,2288}: a window 56% of the width, left-flush, running off the bottom.
     * That is the "maximise does not go full width" report, exactly.
     *
     * {@link android.content.Context#createWindowContext} is what actually associates the
     * WindowManager with the display — the same call the caption anchor and the curtain
     * already make a few hundred lines up, which is why THEIR geometry was always right and
     * only this was wrong.
     *
     * The fallback is the display context's Resources, which are display-adjusted even
     * though its WindowManager is not (that asymmetry is the whole bug): it is what
     * {@link #taskbarPx} reads, and it demonstrably reported the desktop's density of 1.0
     * while the metrics above were reporting the phone.
     */
    private android.graphics.Point displaySize(int display) {
        final android.graphics.Point size = new android.graphics.Point();
        Display d = null;
        try {
            d = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(display);
            android.graphics.Rect b = createDisplayContext(d)
                    .createWindowContext(
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    .getSystemService(WindowManager.class)
                    .getMaximumWindowMetrics().getBounds();
            size.set(b.width(), b.height());
            return size;
        } catch (Throwable t) {
            trace("window metrics unavailable for " + display + " (" + t + ")");
        }
        try {
            android.util.DisplayMetrics dm =
                    createDisplayContext(d).getResources().getDisplayMetrics();
            size.set(dm.widthPixels, dm.heightPixels);
            return size;
        } catch (Throwable t) {
            // Deliberately not the service's own metrics: a phone-shaped answer looks
            // plausible and silently mis-sizes every window, which is worse than a default
            // that is at least obviously a default.
            trace("display size unavailable for " + display + " (" + t + ")");
            size.set(1920, 1080);
        }
        return size;
    }

    /**
     * Where a maximised window sits: the whole display, bar the taskbar and four pixels of
     * width. Both insets are load-bearing and neither is cosmetic.
     *
     * The taskbar is a 52dp overlay pinned to the bottom of the desktop and always composited
     * ABOVE app windows, so a window owning those rows owns pixels it can never show.
     *
     * The 4px is the handshake with the PC. The enforcer treats a freeform task spanning the
     * FULL display width as "something other than us just made this full-width" (scrcpy.rs,
     * Enforcer::fills) and reacts to it, and its own pin is 4px narrow precisely so its own
     * work is not mistaken for that event. Maximising to exactly (0,0,W,H) — what this used
     * to send — made every press of our ▢ indistinguishable from it, so one press was counted
     * twice: once here, once by the enforcer's toggle. Whenever the two toggles were out of
     * phase (a taskbar-menu maximise, a parked retry, a caption-service restart on reconnect)
     * the enforcer's half ran as a RESTORE onto the last rect it had observed the window
     * windowed at — the left half of the display, after a snap. Hence "maximise goes half
     * width". Same arithmetic as maxed_rect on the PC side, so both agree on which rect means
     * maximised.
     */
    private int[] maxRect(int display) {
        final android.graphics.Point size = displaySize(display);
        return new int[]{0, 0, size.x - 4, size.y - barPx(display)};
    }

    /**
     * Is this window sitting on a maximise pin — either one?
     *
     * The pin's height follows the taskbar: with auto-hide on it reaches the
     * display's edge, with it off it stops above the bar. That preference is
     * also known to the PC, which computes the same rect for the windows it
     * pins, but the two copies can be a beat apart — a fresh session starts
     * with the PC's default until the launcher's first `taskbar` row lands, and
     * a toggle arrives at both ends at slightly different times. When they
     * disagree, a strict test would look at a maximised window and call it
     * "not maximised", so ▢ would maximise it onto the rect it is already
     * sitting on and the button would read as dead — the window could not be
     * restored at all.
     *
     * Both heights therefore count as "maximised". The cost of being wrong is
     * one restored window in an edge case; the cost of being strict is a
     * maximise that cannot be undone.
     */
    private boolean isMaxed(WmClient.Task task) {
        if (task == null) return false;
        android.graphics.Point size = displaySize(task.displayId);
        int[] withBar = {0, 0, size.x - 4, size.y - taskbarPx(task.displayId)};
        int[] without = {0, 0, size.x - 4, size.y};
        return near(task, withBar) || near(task, without) || near(task, maxRect(task.displayId));
    }

    /**
     * How much of the display's bottom the taskbar takes from a maximized window.
     *
     * Zero while the bar hides itself: the PC computes the same rect (scrcpy.rs
     * maxed_rect), and the two MUST agree — this service tells a maximized
     * window from a merely screen-filling one by comparing bounds against this
     * rect, so a mismatch would read every maximize as One UI fullscreen and
     * undo it. The preference is the launcher's; the PC is told separately by
     * RequestProvider and keeps its own copy in step.
     */
    private int barPx(int display) {
        int d = DexPrefs.getBool(this, DexPrefs.KEY_TASKBAR_AUTOHIDE, true) ? 0 : 1;
        return d == 0 ? 0 : taskbarPx(display);
    }

    /**
     * The taskbar's height in the DESKTOP display's pixels.
     *
     * Read from a display context of its own rather than through px(int), which follows
     * the display the captions were last laid out on: this one is asked about a display by
     * id, and the launcher lays its taskbar out in dp against that display's density. This
     * is also why it cannot come from the service's own Resources, which describe the phone
     * panel.
     */
    private int taskbarPx(int display) {
        try {
            Display d = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(display);
            float density = createDisplayContext(d).getResources()
                    .getDisplayMetrics().density;
            return Math.round(LauncherActivity.TASKBAR_DP * density);
        } catch (Throwable t) {
            trace("taskbar height unavailable for " + display + " (" + t + ")");
            return px(LauncherActivity.TASKBAR_DP);
        }
    }

    /**
     * Is this task sitting on that rect? The ±8px slack is the same the PC enforcer allows
     * (scrcpy.rs, near), so "maximised" means the same thing on both sides of the wire even
     * when the two computed the pin from densities that round differently.
     */
    private static boolean near(WmClient.Task t, int[] r) {
        return Math.abs(t.left - r[0]) <= 8
                && Math.abs(t.top - r[1]) <= 8
                && Math.abs(t.right - r[2]) <= 8
                && Math.abs(t.bottom - r[3]) <= 8;
    }

    /**
     * Restore target of last resort: a centred window at three fifths of the display, the
     * same shape the enforcer hands a launch (scrcpy.rs, default_windowed). Only reached when
     * the remembered rect was lost — the caption is torn down and rebuilt whenever a task
     * stops being freeform/visible for a pass, and that sweep drops restoreBounds with it.
     */
    private int[] defaultRestore(int display) {
        final android.graphics.Point size = displaySize(display);
        return new int[]{size.x / 5, size.y / 8, size.x * 4 / 5, size.y * 8 / 9};
    }

    /**
     * Minimise, i.e. hide the window but keep the app open.
     *
     * This used to send the task to the back, which reads as a CLOSE: the launcher is a
     * fullscreen task, so anything at the bottom is fully occluded, the window manager
     * stops it, and it drops out of the taskbar's open-apps list along with everything else
     * that is not visible. setHidden says what we actually mean, and the package is pinned
     * below so the taskbar keeps offering it.
     */
    private void onMinimise(WmClient.Task task) {
        final int display = task.displayId;
        final int id = task.taskId;
        minimised.put(id, new Min(task.pkg, task.activity, SystemClock.uptimeMillis()));
        publishMinimised();
        wm.post(() -> wm.hide(display, id));
    }

    /** One window we minimised: what to pin in the taskbar, and how far the hide has got. */
    private static final class Min {
        final String pkg;
        /**
         * The task's top activity. Package alone cannot address a restore:
         * our own package owns TWO windows (Settings and Linux), and a
         * pkg-only match would un-hide whichever came first.
         */
        final String activity;
        /** uptime the minimise was issued at — the grace window starts here. */
        final long since;
        /** Set once the task has actually been observed hidden. */
        boolean hidden;
        /**
         * Where in the stack this window was when it went away, 0 = topmost.
         *
         * Only "show desktop" fills it in, because only "show desktop" puts
         * several windows away at once and has to give them back in the order
         * they were in. A window minimised on its own comes back on top, which
         * is what the user just asked for, and carries {@link #LOOSE}.
         */
        int depth = LOOSE;

        /** Not part of a batch — restore before anything that is. */
        static final int LOOSE = -1;

        Min(String pkg, String activity, long since) {
            this.pkg = pkg;
            this.activity = activity;
            this.since = since;
        }
    }

    /**
     * How long a hide gets to land before we stop pinning its package.
     *
     * Only reached when the hide genuinely failed (daemon gone, task already dying); a
     * transition that runs normally is observed committed well inside it.
     */
    private static final long HIDE_GRACE_MS = 2_000L;

    /**
     * taskId -> the window we minimised.
     *
     * Needed because a minimised task and a closed one are indistinguishable downstream:
     * One UI's caption ✕ also merely hides the task, so "not visible" cannot mean "gone".
     * Only the side that performed the minimise knows the difference, and that is here.
     */
    private final Map<Integer, Min> minimised = new HashMap<>();

    /** Taskbar click on a minimised app — un-hide it rather than launching it again. */
    /**
     * "Show desktop", from a touchpad gesture — one action, both directions.
     *
     * A toggle rather than two commands because the user performs one gesture
     * and expects it to undo itself, and because only this service can answer
     * which way round it currently is.
     */
    private final android.content.BroadcastReceiver showDesktopReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent i) {
                    onShowDesktop();
                }
            };

    /**
     * Hide every window, or bring back the ones we hid.
     *
     * Goes through the same {@code minimised} map the caption's — button does,
     * and for the same reason: the PC builds the taskbar's open-apps list from
     * visibility, so a window hidden without being recorded here simply
     * disappears from the taskbar with nothing able to bring it back. "Show
     * desktop" would then mean "close everything".
     */
    private void onShowDesktop() {
        if (wm == null) return;
        final int display = desktopDisplayId;
        List<WmClient.Task> open = new ArrayList<>(tasks.values());
        // Which way the toggle goes is decided by what is ON SCREEN, not by
        // whether anything happens to be minimised. A user who minimised one
        // window by hand and then asks for the desktop means "clear the other
        // two", not "put that one back".
        if (open.isEmpty()) {
            // DEEPEST FIRST. wm.show un-hides AND raises, so replaying the
            // stack from the bottom puts every window back where it was; an
            // unordered walk — and `minimised` is a HashMap — would hand back
            // a desktop shuffled into an arbitrary order with the wrong window
            // focused.
            List<Map.Entry<Integer, Min>> back = new ArrayList<>(minimised.entrySet());
            java.util.Collections.sort(back, (a, b) -> b.getValue().depth - a.getValue().depth);
            for (Map.Entry<Integer, Min> e : back) {
                final int id = e.getKey();
                wm.post(() -> wm.show(display, id));
            }
            // Left in `minimised` on purpose: the reconcile drops each entry
            // once the un-hide has actually landed, which is also what keeps
            // the taskbar from un-pinning a window mid-transition.
            return;
        }
        // `stack` order, not the map's: `tasks` is a HashMap and `lastStack`
        // is the daemon's topmost-first list, which is the only record of how
        // the desktop looked before this gesture.
        List<WmClient.Task> ordered = lastStack.isEmpty() ? open : lastStack;
        for (int i = 0; i < ordered.size(); i++) {
            WmClient.Task t = ordered.get(i);
            if (!tasks.containsKey(t.taskId)) continue;
            Min m = new Min(t.pkg, t.activity, SystemClock.uptimeMillis());
            m.depth = i;
            minimised.put(t.taskId, m);
            final int id = t.taskId;
            wm.post(() -> wm.hide(display, id));
        }
        // Once, after the whole batch: the launcher rebuilds its taskbar on
        // every one of these, and N windows should not cost N rebuilds.
        publishMinimised();
    }

    private final android.content.BroadcastReceiver restoreReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent i) {
                    String pkg = i.getStringExtra("pkg");
                    if (pkg == null) return;
                    // Optional narrowing for the one package with several
                    // windows (ours: Settings and Linux). Absent = any window
                    // of the package, the taskbar's meaning.
                    String activity = i.getStringExtra("activity");
                    for (Map.Entry<Integer, Min> e : minimised.entrySet()) {
                        if (!pkg.equals(e.getValue().pkg)) continue;
                        if (activity != null && !activity.equals(e.getValue().activity)) continue;
                        final int taskId = e.getKey();
                        final int display = desktopDisplayId;
                        wm.post(() -> wm.show(display, taskId));
                        break;
                    }
                    // The reconcile drops it from `minimised` once it is visible again.
                }
            };

    private static WmClient.Task byId(List<WmClient.Task> tasks, int taskId) {
        for (WmClient.Task t : tasks) {
            if (t.taskId == taskId) return t;
        }
        return null;
    }

    /** Tell the launcher which packages to keep in the taskbar despite being invisible. */
    private void publishMinimised() {
        StringBuilder pkgs = new StringBuilder();
        StringBuilder activities = new StringBuilder();
        for (Min m : minimised.values()) {
            if (pkgs.length() > 0) {
                pkgs.append(',');
                activities.append(',');
            }
            pkgs.append(m.pkg);
            activities.append(m.activity);
        }
        sendBroadcast(new android.content.Intent(LauncherActivity.ACTION_MINIMISED)
                .setPackage(getPackageName())
                .putExtra("pkgs", pkgs.toString())
                // aligned with pkgs — the launcher needs the per-window view
                // to route its own two windows' restore correctly
                .putExtra("activities", activities.toString()));
    }

    private void release(Caption c) {
        if (c == null) return;
        try {
            if (c.host != null) c.host.release();
        } catch (Throwable ignored) {
        }
    }

    // ── launch curtain ─────────────────────────────────────────────────────
    //
    // A display-level cover for One UI's own caption during the window between a task
    // appearing and our real caption attaching to it.

    /**
     * Put an opaque strip over the task's caption area, or move an existing one to match.
     *
     * Drawn as a display-level {@code TYPE_ACCESSIBILITY_OVERLAY} — the same window type the
     * anchor uses, and the only kind available here: the task's caption host is not yet in
     * the accessibility list, so there is no window to parent a window-level overlay to. The
     * colour matches the real bar so the handoff in {@link #ensureCaption} is invisible.
     */
    private void showCurtain(WmClient.Task task) {
        int w = task.right - task.left;
        int h = captionHeight(task.taskId);
        if (w <= 0 || h <= 0) return;
        try {
            View existing = curtains.get(task.taskId);
            if (existing != null) {
                existing.getContext().getSystemService(WindowManager.class)
                        .updateViewLayout(existing, curtainParams(task, w, h));
                return;
            }
            Display display = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(task.displayId);
            if (display == null) return;
            android.content.Context ctx = createDisplayContext(display)
                    .createWindowContext(
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            View v = new View(ctx);
            v.setBackgroundColor(DexTheme.of(this).caption);   // == the real bar's background
            ctx.getSystemService(WindowManager.class).addView(v, curtainParams(task, w, h));
            curtains.put(task.taskId, v);
            curtainSince.put(task.taskId, SystemClock.uptimeMillis());
            trace("curtain up task=" + task.taskId + " w=" + w + " h=" + h);
        } catch (Throwable t) {
            trace("curtain failed task=" + task.taskId + ": " + t);
        }
    }

    /**
     * Absolute placement on the desktop display: gravity TOP|LEFT with x/y at the task
     * origin, sized to the task width and the caption height. NOT_TOUCHABLE so a stray tap
     * during the (brief) cover still reaches the app; NOT_FOCUSABLE so it never takes focus.
     */
    private static WindowManager.LayoutParams curtainParams(WmClient.Task task, int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = task.left;
        lp.y = task.top;
        return lp;
    }

    private void hideCurtain(int taskId) {
        View v = curtains.get(taskId);
        if (v == null) return;
        curtains.remove(taskId);
        curtainSince.remove(taskId);
        try {
            v.getContext().getSystemService(WindowManager.class).removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
    }

    // ── the bar drawn on the display, for a task nothing will host ──────────
    //
    // The last resort, and the one that answers the report that started it: on a
    // Samsung desktop a window can be left wearing One UI's own caption — a ▢ and
    // a ✕ and no minimise, because none of those are ours. The host search above
    // is the fix for that, but only when the shell's caption window is in the
    // accessibility list at all. When it is not, giving up leaves the user looking
    // at the platform's bar, which is exactly the complaint.
    //
    // So the same bar goes up as a display-level TYPE_ACCESSIBILITY_OVERLAY at the
    // task's caption strip instead. It is the arrangement the class javadoc calls
    // the worse alternative — it has to be moved by the reconcile loop rather than
    // by the compositor — but "a bar that trails the window by a frame" beats
    // "no minimise button at all", and this path only ever runs for windows where
    // the alternative was nothing.

    /** taskId -> the bar we drew on the display for a task we could not host. */
    private final SparseArray<Floating> floating = new SparseArray<>();

    private static final class Floating {
        View root;
        TextView title;
        int width;
        int height;
        int x;
        int y;
    }

    /**
     * Put our bar on the display over this task's caption strip, or keep the one
     * already there in step with the window.
     *
     * Called from the reconcile loop on every pass while the task has no host, so a
     * window that is dragged or resized has its bar follow it: the window manager
     * moves the window, and the next pass moves this.
     */
    private void showFloatingBar(WmClient.Task task) {
        final int width = task.right - task.left;
        final int height = captionHeight(task.taskId);
        if (width <= 0 || height <= 0) return;
        try {
            Floating f = floating.get(task.taskId);
            if (f != null) {
                final CharSequence want = labelFor(task);
                if (!want.toString().contentEquals(f.title.getText())) f.title.setText(want);
                if (f.x != task.left || f.y != task.top
                        || f.width != width || f.height != height) {
                    f.root.getContext().getSystemService(WindowManager.class)
                            .updateViewLayout(f.root, floatingParams(task, width, height));
                    f.x = task.left;
                    f.y = task.top;
                    f.width = width;
                    f.height = height;
                }
                return;
            }
            Display display = getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(task.displayId);
            if (display == null) return;
            android.content.Context ctx = createDisplayContext(display)
                    .createWindowContext(
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            Caption c = new Caption();
            c.taskId = task.taskId;
            c.width = width;
            c.height = height;
            LinearLayout bar = barView(ctx, task, width, height, c);
            if (bar == null) return;
            ctx.getSystemService(WindowManager.class)
                    .addView(bar, floatingParams(task, width, height));
            Floating made = new Floating();
            made.root = bar;
            made.title = c.title;
            made.width = width;
            made.height = height;
            made.x = task.left;
            made.y = task.top;
            floating.put(task.taskId, made);
            noCurtain.add(task.taskId);      // this bar replaces the curtain
            hideCurtain(task.taskId);
            trace("floating bar task=" + task.taskId + " w=" + width + " h=" + height);
        } catch (Throwable t) {
            trace("floating bar failed task=" + task.taskId + ": " + t);
        }
    }

    /**
     * Placement for the fallback bar: the task's own caption strip, absolutely.
     *
     * Touchable, unlike the curtain — the buttons ARE the point — but never
     * focusable, so the keyboard, back and the app's own focus stay where they were.
     */
    private static WindowManager.LayoutParams floatingParams(WmClient.Task task, int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = task.left;
        lp.y = task.top;
        return lp;
    }

    private void hideFloatingBar(int taskId) {
        Floating f = floating.get(taskId);
        if (f == null) return;
        floating.remove(taskId);
        try {
            f.root.getContext().getSystemService(WindowManager.class).removeViewImmediate(f.root);
        } catch (Throwable ignored) {
        }
    }
}
