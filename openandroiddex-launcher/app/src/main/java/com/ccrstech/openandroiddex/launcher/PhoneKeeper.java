package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The phone's own screen is the phone's, and every window on it is a PHONE
 * window.
 *
 * <p>WHY THIS EXISTS. Android has one task per app, not one per display. A task
 * that lives on the desktop display keeps its freeform shape — the bounds and
 * the reserved caption strip — and when something on the phone claims that task
 * (a tap on the phone's own launcher, a notification, a call coming in), the
 * task MOVES to the phone's display <em>with that shape intact</em>: the phone
 * ends up showing a quarter-screen floating window at desktop coordinates, with
 * the desktop's strip cut out of its top. That is the reported "desktop a ei
 * app open thakle mobile a choto hoye thake".
 *
 * <p>Two of this project's own paths can produce it as well: a launch the
 * desktop display refused used to fall back to a freeform window at desktop
 * bounds <em>on the phone</em>, and the app's launch mode may be singleTask,
 * where the platform insists on reusing the one task it has.
 *
 * <p>{@code CaptionService.releaseFreeformOnPhone} was written for this and is
 * the right idea, but it lives inside the accessibility service — so on a phone
 * that has not bound that service (see {@link CaptionFallback}) it never runs at
 * all. This is the same job with no dependency on accessibility: it asks the
 * window daemon for the phone display's task list, and puts any leftover
 * desktop-shaped window back into a full-screen, ordinary phone window.
 *
 * <p>Only packages that have been opened on the desktop are touched. An app the
 * user keeps in a floating window on the phone for its own reasons is not ours
 * to move.
 */
final class PhoneKeeper {

    private static final long POLL_MS = 1000L;
    /** How many desktop packages to remember across restarts. */
    private static final int REMEMBER = 40;
    private static final String PREFS = "phone_keeper";
    private static final String KEY_PKGS = "desktop_pkgs";

    private static PhoneKeeper instance;

    static synchronized void attach(Context ctx) {
        if (instance == null) instance = new PhoneKeeper(ctx.getApplicationContext());
        instance.kick();
    }

    static void detach() {
        PhoneKeeper k = instance;
        if (k != null) k.stop();
    }

    /** A package was opened on the desktop: remember it as one whose phone copy must look like a phone app. */
    static void noteDesktopPackage(Context ctx, String pkg) {
        if (pkg == null) return;
        attach(ctx);
        instance.remember(pkg);
    }

    private final Context app;
    private final WmClient wm = new WmClient();
    private final HandlerThread thread;
    private final Handler worker;
    private final Set<String> desktop = new LinkedHashSet<>();
    private volatile boolean running;

    private PhoneKeeper(Context app) {
        this.app = app;
        thread = new HandlerThread("phone-keeper");
        thread.start();
        worker = new Handler(thread.getLooper());
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String all = p.getString(KEY_PKGS, "");
        for (String s : all.split("\n")) {
            if (!s.isEmpty()) desktop.add(s);
        }
    }

    private void remember(String pkg) {
        synchronized (desktop) {
            if (!desktop.add(pkg)) return;
            while (desktop.size() > REMEMBER) {
                java.util.Iterator<String> it = desktop.iterator();
                it.next();
                it.remove();
            }
            StringBuilder b = new StringBuilder();
            for (String s : desktop) b.append(s).append('\n');
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PKGS, b.toString()).apply();
        }
    }

    void kick() {
        if (running) return;
        running = true;
        worker.post(this::loop);
    }

    void stop() {
        running = false;
    }

    private void loop() {
        if (!running) return;
        long started = SystemClock.uptimeMillis();
        try {
            pass();
        } catch (Throwable t) {
            DexLog.warn("phone", "window keeper pass failed: " + t);
        }
        if (!running) return;
        long spent = SystemClock.uptimeMillis() - started;
        worker.postDelayed(this::loop, Math.max(50, POLL_MS - spent));
    }

    private void pass() {
        // 1. What the desktop has open right now, so its packages are known
        //    even if this process is the first thing to see them.
        int desk = wm.desktopDisplay();
        if (desk >= 0) {
            List<WmClient.Task> onDesk = wm.list(desk);
            if (onDesk != null) {
                for (WmClient.Task t : onDesk) {
                    if (t.pkg != null && !t.pkg.isEmpty()) remember(t.pkg);
                }
            }
        }
        Set<String> known;
        synchronized (desktop) {
            if (desktop.isEmpty()) return;
            known = new LinkedHashSet<>(desktop);
        }

        // 2. Anything of theirs that the phone is holding in desktop shape.
        List<WmClient.Task> phone = wm.list(Display.DEFAULT_DISPLAY);
        if (phone == null || phone.isEmpty()) return;
        DisplayMetrics dm = new DisplayMetrics();
        try {
            Display d = app.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return;
            d.getRealMetrics(dm);
        } catch (Throwable t) {
            return;
        }
        for (WmClient.Task t : phone) {
            if (t.pkg == null || !known.contains(t.pkg)) continue;
            // Desktop shape, either half of it:
            //  - freeform, whatever its size: the window itself is wrong;
            //  - or a reserved caption strip (the app starts below the top of
            //    its own frame) on a task that is otherwise full screen — the
            //    app's content is 40 dp down with a black band above it.
            boolean freeform = t.isFreeform();
            boolean stripped = t.appTop > t.top && t.appLeft == t.left
                    && t.appRight == t.right && t.appBottom == t.bottom;
            if (!freeform && !stripped) continue;
            if (!freeform
                    && t.left <= 0 && t.top <= 0
                    && t.right >= dm.widthPixels && t.bottom >= dm.heightPixels
                    && !stripped) {
                continue;
            }
            DexLog.step("phone", "freeing " + t.pkg + " from desktop shape "
                    + t.left + "," + t.top + "," + t.right + "," + t.bottom
                    + (freeform ? " [freeform]" : " [strip " + (t.appTop - t.top) + "px]")
                    + " → full screen " + dm.widthPixels + "x" + dm.heightPixels);
            wm.nativeWindow(Display.DEFAULT_DISPLAY, t.taskId,
                    dm.widthPixels, dm.heightPixels);
        }
    }
}
