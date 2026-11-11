package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * The platform's bars, off — the same treatment the desktop gets, for every other window of
 * ours.
 *
 * <p>Why this is a class and not three copies: the launcher had this right and its three
 * sibling windows did not, and the difference is invisible until you look at the bottom of
 * the screen. A window that keeps the system's navigation bar also keeps the strip the bar
 * occupies reserved, so the window draws short of the display's edge and what shows through
 * is the window background — black. That is the report "navigation full ashe na, olpo ektu
 * kalo hoi", and it applies to whichever of our windows is on top, not only to the desktop.
 *
 * <p>Both halves are needed and they are not the same thing:
 * <ul>
 *   <li>{@code hide(systemBars())} removes the bar's surface;</li>
 *   <li>{@code setDecorFitsSystemWindows(false)} stops the layout reserving its space.</li>
 * </ul>
 * With only the first, the reserved strip is empty — black. {@link Activity#onWindowFocusChanged}
 * is when the platform re-asserts its bars, so {@link #apply} is called there as well as in
 * onCreate.
 */
final class SystemBars {

    private SystemBars() {
    }

    /** Hide the status and navigation bars and draw edge to edge. Safe to call repeatedly. */
    static void apply(Activity a) {
        if (a == null || a.getWindow() == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                a.getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = a.getWindow().getInsetsController();
                if (c == null) return;
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else {
                a.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable t) {
            DexLog.warn("taskbar", "could not hide the system bars", t);
        }
    }
}
