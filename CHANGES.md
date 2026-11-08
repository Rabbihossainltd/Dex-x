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
