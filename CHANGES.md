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

## v0.7.0 — the screen, the mark, and 35 MB of things nobody used

| # | Asked for | What it took |
| --- | --- | --- |
| 1 | The status on the phone should be its own thing, not a line inside the instructions card — plus an About block | The status is the screen's **headline** now (28 sp, with its dot), and the card under it holds only the detail and the button. About sits below both: made by **MD Rabbi Hossain**, the site, the version, and the licence. |
| 2 | A new logo, on the PC and on the phone | The mark is drawn once (`brand-wordmark.png`, cut out of the supplied art) and every surface is generated from it: the Windows/macOS/Linux icons (`npx tauri icon`), the phone's adaptive icon (white tile + wordmark), the titlebar's 16 px X, `doc/img/logo.png` and the window icon. |
| 4 | Sound: the desktop's on the PC, the phone's on the phone, at the same time — and no audio-source switch in the app | `DexMedia.audioMode` is now always both (`--audio-source=playback --audio-dup`): scrcpy copies the phone's audio instead of **diverting** it, so the handset keeps every sound it makes while the PC plays the desktop's. The three-way "Where sound plays" card is gone from Settings. |
| 6 | Make the phone app smaller; delete the files of the features that were removed | **36 MB → 0.85 MB.** The Linux container, Docker, the phone's remote access and the browser viewer are gone, and with them: `jniLibs/` (QEMU 20 MB + dexremote 8 MB + proot 0.2 MB per ABI), `assets/linux`, `assets/docker`, `assets/web`, and the WebRTC SDK — whose `libjingle_peerconnection_so` was 16 MB across three ABIs. 43 Java files and four manifest components went with them. |
| 11 | Clean the workspace | Every old clone, release folder, downloaded artifact and unpacked APK is deleted; the working tree is a fresh clone. |

### Also

* The PC's font/label for the app is unchanged, but the **icon** is the new mark everywhere, including the taskbar and the installer.
* Rebuilt the phone app's About line so the version is read from the manifest, never typed twice.

### Not in this build (next)

* QR pairing (EXE shows a code, the phone scans it).
* Physical-keyboard behaviour and FLAG_SECURE screens — both need the phone's own logs.
* In-app update prompt.
* Windows-feel extras, desktop-mode apps, and the "separate device" identity.

---

## v0.7.1 — typing that stays on the computer, and secure windows

| # | Asked for | What it took |
| --- | --- | --- |
| 5 | Typing on the PC must not raise the phone's keyboard; password and flagged screens must not go black | Two causes, two places. The keyboard: every `--new-display` now gets `--display-ime-policy=hide` (`scrcpy.rs`), so Android never puts the phone's IME on the desktop — without it the first focused field slid a phone-sized keyboard across the bottom of the window. The black screens: a virtual display can be asked for as a **secure** one (`VIRTUAL_DISPLAY_FLAG_SECURE`), and then secure windows are composited into the surface we own instead of being blanked — `secure-display.patch` for the desktop display, `AppServer.createDisplay` for a phone app's window. Both fall back honestly: if the phone refuses (`CAPTURE_SECURE_VIDEO_OUTPUT` is a signature permission the shell holds only on some builds) they retry without the flag and log the reason once. See `doc/secure-windows.md`. |
| 7 | An update prompt, on both halves | The APK is sideloaded and the exe is a zip: neither has a store to say a newer build exists. **Phone** — `UpdateCheck.java` asks the repository's latest release on a background thread (6 s timeouts, no retry), compares it with the manifest's `versionName`, and shows one dialog that links to the release page. **PC** — `src/features/update/UpdatePrompt.tsx` does the same through the webview's own fetch and the app's `Dialog`. Both ask at most **once a day** and never twice about the same version, and neither installs anything: the answer is a link to two files, like the ones that were sideloaded. |
| 3 | QR pairing | Already built and where it should be: the connect screen's Wi-Fi panel offers **Scan a code** (`wireless_qr_begin`/`wireless_qr_poll` in `wireless.rs`, drawn from the `qrcode` crate's matrix), which arms an `WIFI:T:ADB;…` payload for the phone's own *Pair device with QR code* scanner, then pairs and connects over mDNS. Nothing to add — the audit that found it gone was of the phone app, which never carried it. |
| — | The daemon was reaching into the launcher for its video encoder | `LinuxStreamEncoder` was the one survivor of the Linux stream, kept only because `AppVideo` compiles it from the launcher's tree. It is `openandroiddex-wmd/src/.../StreamEncoder.java` now: the daemon owns the file it uses, and `build.sh`/`build.cmd` no longer name a path outside the module. Same for the `:linuxapp` module — deleted, with its `include`, which is what `assembleDebug` had been failing on. |

---

## v0.7.2 — the computer and the phone keep their own clipboards

| # | Asked for | What it took |
| --- | --- | --- |
| 10 | "PC ar mobile er kaj alada thakbe, shompurno" — the exe as a device of its own, not a second screen of the phone | The one place the two still shared state was the **clipboard**, and it shared it by default: copying on either side silently replaced what the other was about to paste. Sync is now **off** unless it is turned on (`DexPrefs.DEF_CLIP_SYNC`, and `clipboard_autosync: false` in the desktop's defaults), with the switch reworded to say what it costs. The rest of the separation was already there: the desktop is its own virtual display with its own focus, the phone keeps its own screen and its own apps, and `--audio-dup` means the phone keeps its own sound while the PC plays the desktop's. |
| 8 | More Windows-feel extras | A **clipboard flyout** in the taskbar tray (the Win+V habit): the phone's own history, newest first, a tap to put an entry back on the phone's clipboard, a Clear row at the bottom. It is the phone's list — it never leaves the device — so it works with sharing off, which is now the default. Toggleable via `clipboard_list`. |

### Not in this build, honestly

* **#9 (phone apps behaving like desktop apps).** The three areas named — typing, window
  behaviour, several windows at once — are all paths that need a phone in front of me to
  see fail; guessing at them would be another round of "fixed" that is not. What is in
  this build for typing is the IME fix from 0.7.1. What would settle the rest is one
  screenshot or fifteen seconds of recording per complaint: which app, what was typed or
  dragged, and what happened instead.

---

## v0.7.4 — why the title bars were never there, and why the computer was silent

0.7.3 changed the *layout* of the caption bar and the *storage* of the sound choice.
Both were real bugs and both were the wrong floor: the caption bar was not being
drawn at all, so no layout inside it could matter, and the audio companion was
competing with the desktop session for the phone's one capture, so no preference
could make the loser win. This release goes after the two actual causes.

| # | Reported | The cause | Fix |
| --- | --- | --- | --- |
| 8 | "caption button o nai … resize o kora jai na" — on **every** window | Every title bar this project has ever drawn comes from `CaptionService`, which is an **accessibility service**: it needs the phone to bind it *and* to hand it the window list of a display it does not own. Android 13+ keeps a sideloaded app's service switched off behind "Allow restricted settings"; some OEMs refuse to bind one after a reinstall; and a bound service can be given an empty window list for a secondary display. All three fail silently, and all three leave the platform's own title bar on screen — which reads as "the feature was never built". Resize lived *in* that bar (8 dp edge bands, the ⤡ grip), so it vanished with it. | **A second renderer that needs none of it.** `CaptionFallback` takes the task list from the window daemon over the same socket everything else uses, reserves each window's strip with the daemon's `STRIP` verb, and draws the bar as a plain `TYPE_APPLICATION_OVERLAY` window out of the launcher process — the one overlay path this app is known to have, because the taskbar is drawn that way every session. It has — ▢ ✕, drag-to-move, and right/bottom/corner resize bands. It draws **only** the windows the accessibility caption has left undressed for four seconds, and hands each one back the moment the real caption dresses it, so there is never a second title bar. |
| 8 | the failure was invisible | Nothing anywhere said which of the three had happened. | A red **No title bars** pill in the tray whenever the caption cannot draw, and a tap that says which one it is: the phone has not enabled the service (with the exact route out of "restricted settings"), the computer's window helper is not answering (with a Retry that asks the computer to start it again), or the phone is not giving us this display's window list (and the plain bars are in use). |
| 8 | "resize o kora jai na" | Resize existed only as the caption's own edge bands. | Same bands in the fallback bar, **and** a taskbar app menu with **Snap left / Snap right / Snap to a half** — geometry that does not depend on a title bar existing at all. |
| 4 | "default desktop a audio hoi nai alada alada" | The desktop session was started with `audio: true`, so scrcpy captured the phone's sound for the desktop window — **and** `reconcile_audio_session` started a second scrcpy (`--audio-source=playback --audio-dup`) to do the same. Two captures of one device: whichever loses exits about a second after it starts, with its reason in a log line nobody listening for sound would ever read, and the desktop is silent with no error anywhere. | One owner: the desktop window is now started with `--no-audio` and sound comes **only** from the companion. And the companion is **watched**: 2.6 s after it starts, if it is gone, its own output tail is logged and it is retried on the other capture path (`--audio-source=playback` without duplication — every Android 11+ phone has it, at the cost of the phone going silent *while it plays here*). Both attempts appear in the log with the exact command line scrcpy was given. |
| — | APK size | Two 23 KB PNG layers of the launcher icon were dead: the adaptive icon references the **vector** `ic_launcher_foreground`/`_monochrome`, and the nodpi rasters were a second copy of the same art that aapt shipped anyway. | Deleted. The APK is smaller than 0.7.3's despite this release adding code. |

## v0.7.3 — the three buttons, sound that an old preference cannot silence, and a real About

Everything here comes from one screenshot and one list of ten verdicts, and most of them
were the same three failures: a caption bar missing the buttons a Windows window is judged
by, a sound setting deleted from the UI but not from the config, and a logo that was new
where it was generated and old where it was hand-copied.

| # | Reported | What was actually wrong | Fix |
| --- | --- | --- | --- |
| 8 | "minimise / maximise / close are not there — the screenshot shows two buttons" | The bar appended every button to a horizontal row whose FIRST child was the title with `weight = 1`. A `LinearLayout` gives the weighted child whatever is left and clips the rest from the RIGHT, so a narrow window lost buttons, and which ones depended on the width it happened to have. | `barView` builds the button set as ONE right-aligned cluster with fixed widths; the title is what gives up room. Minimise, maximise/restore and close are always present, in Windows order, close at the very edge. Snaps and the resize grip are the extras that go first, and only on a bar too narrow for them. The maximise glyph follows the window (▢ / ❐) on every poll. |
| 8 | "the recent app goes from the taskbar" — a minimised app cannot be got back | A taskbar tile click was `focusPackage` → `wm.front()`, and `front()` only RAISES. `onMinimise` HIDES the task, so a hidden window's tile raised something nobody could see. | `focusPackage` un-hides first (`wm.show`, the same call `restoreTask` makes) and drops the id from the minimised set; `refreshOpenApps` asks it before falling back to `launch`. |
| 4 | "audio still not fixed — sound only on the mobile" | `stored_audio_forwarding` read the two legacy keys with `true` defaults — but an installation that had once picked "phone only" still had `stream_audio=off` stored, and nothing in the UI could turn it back. The setting was deleted; its persisted state was not. | The stored answer is no longer read: `stored_audio_forwarding` returns `(true, true)`. The computer always plays the desktop's sound and `--audio-dup` keeps the phone's own. The rows that offered a source are gone from both sound menus; what is left is the phone's own outputs. |
| 1 | "no About button — just 'made by Rabbi Hossain'" | The About block was text, not a control. | **Scan a code** and **About** are buttons under the card. About opens a dialog with the author, the site, **info@rabbihossainltd.online**, the version, the licence — and **Check for updates**. |
| 3 | "the apk has no scan option" | QR pairing lives in the PC app (a phone cannot scan its own screen), and the phone had no route to its own pairing scanner. | **Scan a code** on the connect screen: the three steps and a button to Developer options, where *Pair device with a QR code* is. Written as instructions, not a promise — that scanner is the system's and no deep link reaches it on every OEM. |
| 7 | "no popup in the new release" | The check compares the newest release with `versionName` — and the build WAS the newest release, so saying nothing was correct. Correct and indistinguishable from broken. | A check the person asked for always answers: **Check for updates** in the phone's About dialog and in the PC's menu — "newest build", "X is out", or "could not reach it". The daily background check still stays quiet. |
| 2 | "the logo is not changed everywhere in the exe" | The icons came from a 1024 tile whose wordmark is unreadable at 16 px, and `icon.ico` held one image, so Windows scaled it down into mud. `public/logo.png` was a different crop again. | One source: the **X**, cut clean of the dash, on the white tile. Small sizes are the X (16/24/30/32/44/48/50), large ones the full wordmark (64–310), `icon.ico` carries seven images, and `resources/icon/scrcpy.png` plus `public/logo*.png` come from the same tile. |
| 5 | "keyboard fixed; password flag not" | `VIRTUAL_DISPLAY_FLAG_SECURE` needs `CAPTURE_SECURE_VIDEO_OUTPUT`, a signature|privileged permission the shell does not hold, so the phone refuses and the display is made without it. | Unchanged, and stated plainly: a window that marks itself secure stays blank unless the phone's shell holds that permission, which no app-side code can obtain. The request and its one-line reason stay in the log. |
| 9 | "ok" | — | Nothing to do. |

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
