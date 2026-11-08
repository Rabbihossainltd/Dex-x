package com.ccrstech.openandroiddex.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Dex-X in the phone's quick settings: status at a glance, and the switch to
 * put the desktop up without touching the computer.
 *
 * <p>The tile is the answer to the question the app screen cannot answer while
 * it is closed — "is this phone driving a desktop right now" — and to the one
 * thing a phone owner expects to be able to do from the shade: turn the thing
 * on. It is deliberately the ONLY part of the app that is ever visible in the
 * notification panel: no ongoing notification, no notification channel the
 * user has to mute.
 *
 * <p><b>What "on" means.</b> The desktop lives on the computer — the display,
 * the windows, the taskbar are all its — so this tile cannot start one by
 * itself. What it can do is ask: {@link RequestProvider#enqueue} puts a
 * {@code start} row where the computer reads it, and a computer that is running
 * and has this phone attached opens the desktop on its own. A phone with no
 * computer running says so in the toast rather than pretending. That is the
 * honest half of the switch, and it is the half that matters: the common case
 * is a phone plugged in with the Dex-X window already open on the PC.
 *
 * <p><b>Status.</b> {@link HostLink} is the computer's own beacon, the same one
 * {@link ConnectActivity} reads, so the tile and the app screen can never
 * disagree. The beacon stops within a couple of seconds of the computer going
 * away, which is what makes the tile a live indicator rather than a memory of
 * the last session.
 */
public class DexTileService extends TileService {

    /**
     * How often the tile re-reads the beacon while the shade is open.
     *
     * The shade can stay open for minutes and the session can end under it — a
     * cable pulled, a desktop closed on the PC — so a tile painted once on
     * {@code onStartListening} would be a lie for the rest of the visit. Cheap:
     * {@link HostLink#available()} is two volatile reads.
     */
    private static final long TICK_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean listening;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            paint();
            if (listening) handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * Ask the system to re-read this tile even though the shade is closed.
     *
     * Without this a tile is only refreshed from {@link #onStartListening},
     * which the system calls when the shade opens — so it would be correct
     * whenever anyone looked at it and never update a badge in between. Called
     * from the transitions that change the answer (see {@code LauncherActivity}
     * and {@link ConnectActivity}); a no-op on a phone that has never seen the
     * tile.
     */
    static void refresh(Context ctx) {
        if (Build.VERSION.SDK_INT < 24) return;   // not reachable: minSdk 26
        try {
            TileService.requestListeningState(ctx,
                    new ComponentName(ctx, DexTileService.class));
        } catch (Throwable ignored) {
            // No tile on this phone, or the shade is locked down. Nothing to fix.
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        listening = true;
        handler.removeCallbacks(tick);
        paint();
        handler.postDelayed(tick, TICK_MS);
    }

    @Override
    public void onStopListening() {
        listening = false;
        handler.removeCallbacks(tick);
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        listening = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    /**
     * Tap: connected means "show me the way out", otherwise "ask the PC to open
     * the desktop".
     *
     * Both halves end in this app's own screen for the connected case, because
     * Disconnect lives there and a tile cannot re-draw itself mid-flight to
     * become a confirm button. For the disconnected case there is nothing to
     * show on the phone — the desktop appears on the computer — so the tap is a
     * request and a toast, and the shade stays open over the computer's answer.
     */
    @Override
    public void onClick() {
        boolean connected = HostLink.available();
        if (connected) {
            openApp();
            return;
        }
        // The row has to outlive this process: the computer may read it two
        // seconds from now, and Android is free to have killed us by then.
        RequestProvider.attach(getApplicationContext());
        RequestProvider.enqueue("start", "dex");
        DexLog.step("tile", "start requested from quick settings");
        Toast.makeText(this, getString(R.string.tile_asking), Toast.LENGTH_SHORT).show();
        paint();
    }

    /** Bring this app's one screen up, collapsing the shade on the way. */
    private void openApp() {
        Intent intent = new Intent(this, ConnectActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntentBox.startActivityAndCollapse(this, intent);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    /**
     * The API 34+ form, kept in its own class so the older one is not compiled
     * into a call that a pre-34 runtime resolves and then rejects — the same
     * reason the rest of this app keeps platform-version shims in tiny classes.
     */
    private static final class PendingIntentBox {
        static void startActivityAndCollapse(TileService service, Intent intent) {
            service.startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                            service, 0, intent,
                            android.app.PendingIntent.FLAG_IMMUTABLE
                                    | android.app.PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }

    /** One pass: the tile's state, label and the two lines under it. */
    private void paint() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean connected = HostLink.available();
        tile.setState(connected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(getString(connected
                    ? R.string.tile_connected
                    : R.string.tile_not_connected));
            // What a screen reader says for the tile. Tile has no separate
            // status line to set — the subtitle IS the status the system draws
            // under the label — so the two say the same thing in different words.
            tile.setContentDescription(getString(connected
                    ? R.string.tile_state_connected
                    : R.string.tile_state_not_connected));
        }
        tile.updateTile();
    }
}
