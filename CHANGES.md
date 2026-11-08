# Dex-X — what changed, and why

Running log of every change from upstream, so the next person (or the next
session) does not have to diff 42 000 lines of Rust to find out.

---

## v0.4.0 — Dex-X, first build

### Bugs

| # | Reported | What was actually wrong | Fix |
| --- | --- | --- | --- |
| 1 | An app used on the PC opens **small and distorted** on the phone afterwards | The desktop launches apps with `--windowingMode 5` on its own display. Android keeps the *same task* when it moves to the phone's display, so the app arrives there still freeform — small, floating, and still carrying the caption-strip inset the desktop gave it. | `CaptionService.releaseFreeformOnPhone()` — every ~2 s, tasks on the **default** display that are (a) freeform and (b) belong to a package this session launched on the desktop are un-stripped and resized to the phone's screen. Nothing else on the phone is touched. |
| 2 | The **recent apps** on the taskbar disappear and never come back | The taskbar's open-app row is fed by the PC's broadcast, which is built from *visibility*. Mid-transition, mid-maximise, or one missed poll and a package drops out of it — and the launcher merge kept only what the broadcast had, so the tile was gone for good while the app stayed open. | The row now also merges `CaptionService.openPkgs()`, which is the in-process answer to "is this still open" — the same task list the captions are built from. A tile leaves the taskbar only when its task does. |
| 3 | There is **no minimise option** | The caption bar is a horizontal `LinearLayout` with the title at `weight = 1` and five buttons after it. Too narrow a window and the buttons are clipped **from the right**, eating ◧ ◨ — first, so a narrow window kept only ✕. | Buttons are laid out against a budget computed from the surface's real width; the two snap buttons are the ones given up when it is tight, so minimise, maximise, resize and close are always present. |
| 4 | **A window cannot be resized** | There was no resize affordance at all. Snap and maximise were the only geometry controls, so no window could ever be given an arbitrary size with the pointer. | `CaptionChrome` — invisible 8 dp bands down the left and right edges of the strip (horizontal resize, double-arrow cursor) and a visible `⤡` grip that pulls the bottom-right corner in both axes. Plus **drag-to-edge snap**: release a window at the left or right edge and it fills that half. |
| 5 | Clicking **blank desktop space hides the window** | Nothing hid it. The launcher is an ordinary task on the display, so touching its wallpaper raised it in front of the freeform windows — a full-screen task covering everything. | `LauncherActivity.onDesktopTapped()` → `CaptionService.sendDesktopToBack()`. A tap on the wallpaper may focus the desktop, but the desktop goes back where it belongs in the same gesture. The desktop is the back of the picture. |
| 6 | **✕ / ▢ needs two clicks**; the first one turns the app black | `ViewGroup` gives children the touch first, so a press on a button should reach it — but this surface is reparented into *another app's* window, and on that path hit-testing has been seen to miss: the bar started a drag instead, the window moved a pixel, the app relaid out black, and the second press worked. | `dragHandler` now hit-tests the press against the bar's own clickable children in display coordinates and returns `false` immediately when it lands on one, so the tap goes where it was aimed. |

### Removed

* **The Apps tab and the Linux tab.** One window, one job: connect, then the
  desktop. Opening a single app in its own PC window was a second product
  bolted to the first — and the drawer on the desktop already opens any app in
  any window. Linux is still there, as one of the drawer's tools.
* **The second window after connecting.** The control window used to be
  *minimised* when the desktop came up, which left a taskbar button next to the
  desktop — read as two halves of one app that failed to close. It is now
  **hidden** outright, and comes back when the session ends, or when the app is
  started again (single-instance turns that into a show).
* **The upstream release workflows.** `desktop.yml` and `android.yml` cut
  versioned releases across four platforms and needed signing secrets this
  repository does not have. Replaced by one workflow that builds on every push.

### Added

* `.github/workflows/dex-x.yml` — on every push to `main`: the launcher APK, the
  wmd daemon, scrcpy's patched server, and the Windows exe + installer, all as
  downloadable artifacts on the run.
* `NOTICE.md` and this file.

---

## v0.5.1 — the window's own buttons, and no phone bar on the desktop

Reported from a real desktop session with a screenshot: a window was wearing
One UI's caption (a ▢, a ✕, no minimise) and the phone's navigation bar was
still along the bottom of the desktop display.

| # | Reported | What was actually wrong | Fix |
| --- | --- | --- | --- |
| 1 | **No minimise option** — the window has One UI's ▢ ✕, not ours | The caption bar is drawn *inside* the shell's caption window for the task (that is what glues it to the window). That host was found by asking for `TYPE_APPLICATION`, and on this Samsung shell the caption strip is **not** that type — so the search returned nothing, nothing was attached, and the window kept the platform's own buttons. | The host is now chosen by **bounds alone**: full task width, at the task's top, no taller than a caption. The window type was only ever a stand-in for "is this the caption strip" and it was the wrong stand-in. |
| 2 | Same, for windows whose app view starts *below* the shell's caption | The app-window fallback demanded that the app window's top equal the task's top. A shell that draws its own caption puts the app's view one caption lower, so those tasks were unhostable — no bar, and the platform's ▢ ✕ as the only buttons. | The fallback accepts a top up to one caption height below the task, and the bar is placed against the measured offset instead of assuming zero. |
| 3 | Same, when nothing can be hosted at all | The launch curtain gave up after 1.5 s and left the bare window — the platform's caption, with none of our buttons. | The curtain is replaced by the **real bar drawn on the display** (`TYPE_ACCESSIBILITY_OVERLAY` at the task's caption strip), moved with the window by the reconcile loop. A bar that trails a drag by a frame beats no minimise button at all, and this path only runs where the alternative was nothing. |
| 4 | The phone's **navigation bar is still on the desktop display** | Nothing removed it. A virtual display made without system decorations is *supposed* to have none, but One UI adds its own to any public trusted display, and nothing an app can do per-window removes it: bar visibility follows the **focused** window, which is whatever app is in front and has no reason to ask for immersive. | `adb_prepare_desktop` now writes `policy_control immersive.full=*` and `immersive_mode_confirmations confirmed` — the only root-free, system-wide form of the ask. Both values are snapshotted into `desktop-restore.json` (`secure.*`) and put back by the same one-line undo the wmd watchdog is armed with, so a pulled cable still restores them. |
| 5 | Diagnosing any of this from a screenshot | The "no caption host" log line said how long it had waited but not what the window list actually contained. | It now dumps every window on the display — id, type, bounds, title — so a task with no bar of ours can be explained from `caption.log` instead of guessed at twice. |

---

## v0.6.0 — straight to the desktop, and a Dex-X tile in the panel

| # | Asked for | What it took |
| --- | --- | --- |
| 1 | **No middle screen**: plug the phone in and the desktop comes up, not a "ready — Open desktop" card first | Auto-launch was armed once, at startup, and disarmed by every launch — so the second plug-in of a run always stopped at the card. It is now armed by the phone APPEARING (`lastReadySerials`): a phone that was not usable a poll ago and is now opens its desktop by itself, including one attached when the app starts. A phone that stays attached after a session ends is not an arrival, so Disconnect still means disconnected. |
| 2 | **Dex-X in the notification panel**: turn it on from there, see its status | A quick-settings tile (`DexTileService`). It reads the computer's beacon — the same one the app screen reads — so its subtitle is a live "Desktop running / Tap to start" rather than a memory of the last session, and it repaints while the shade is open. Tapping it when nothing is running queues a `start` request; a computer that is open with this phone attached opens the desktop on its own (new `adb_requests` / `adb_ack_requests` commands, polled on the device tick — with no session there was no pump to read the phone's queue). |
| 3 | More bugs found and fixed | below |

### Bugs

* **The reconcile loop ran at 60 ms forever on a window with no host.** A task wearing the display-drawn fallback bar (v0.5.1) has no `Caption` record, so `syncCaption` answered false for it on every pass, and false is what the caller reads as "still waiting" — a phone kept warm for the rest of the session for nothing. A fallback bar now counts as dressed.
* **The fallback bar did not follow its window.** It lives on the display, not inside the window, so the loop is the only thing that moves it — and the task was added to the "do not touch again" set the moment the bar was created. Dragging the window left the bar behind, with its buttons acting on a rect that had moved out from under them. The bar is now re-placed on every pass.
* **Requests died with the process that raised them.** The launcher's queue was an in-memory `ArrayDeque`; the quick-settings tile is a screen that exists for one tap, and a tap milliseconds before Android reclaims the app was a request the computer never saw. The queue is written to prefs on every enqueue and pruned on every ack (capped at 32 rows), so a request outlives the process and the id counter comes back ahead of it.
* **Open apps vanished from the taskbar when they had no launcher activity.** The open-apps row resolved each package through the drawer's list, which is built from `ACTION_MAIN`/`CATEGORY_LAUNCHER` — so a camera opened from the lock screen, an app opened from a notification, or a settings sub-screen was named, found in no list, and skipped. Those packages are now resolved through the PackageManager, and their tile RAISES the window it already has (`CaptionService.focusPackage`) instead of trying to start something that does not exist.

---

## Next, in order

1. **Taskbar auto-hide with hover reveal** — what the reporter of the taskbar
   bug asked for in the same breath: a full-screen app should get the whole
   screen, and the bar should come back when the pointer reaches the bottom
   edge.
2. **Snap layouts** — quarter snaps at the corners, and an outline preview while
   a window is dragged near an edge (the release action already snaps; the
   *preview* is the part that makes it discoverable).
3. **Multi-instance** — one app in two windows.
4. **Screenshot and recording** from a shortcut, straight into the PC's
   clipboard.
5. **Virtual desktops**, **per-app "open on phone"**, **drawer folders**,
   **clipboard history**.

Each of these needs a phone in hand to tune; the list is ordered by how much of
the "this is not quite DeX" feeling each one removes.

## How to build without GitHub Actions

Actions is blocked by an account billing lock, so `tools/build-windows.ps1`
does the same build on a Windows PC: it installs Rust, Node, the MSVC C++
build tools, JDK 17 and the Android command-line SDK (each one skipped if it is
already there), then runs `npm ci` and `npx tauri build` — which builds the
launcher APK, the wmd daemon and scrcpy's patched server, and bundles all three
into the exe and the NSIS installer.

```powershell
cd C:\path\to\Dex-X
powershell -ExecutionPolicy Bypass -File tools\build-windows.ps1
```

First run 30–60 minutes (Rust compiling ~500 crates), later runs ~2 minutes.
