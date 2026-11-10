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

## v0.7.17 — whose caption is it, and the two holes that let the home wear one

Two reports, and one of them is a correction of this project's own last release.

### 0. 0.7.16 was wrong about `setShouldShowSystemDecors`, and says so

AOSP's `IWindowManager#setShouldShowSystemDecors` is documented as "system decors include
**status bar, navigation bar, launcher**". It is the switch for the BARS — not for a task's
caption. 0.7.16 shipped it as the fix for "the home still has a caption" and it could not
have worked; the code and the daemon's comment are corrected here rather than left claiming
something the framework does not do. The call is kept (a desktop display with no status or
navigation bar of the platform's is worth having) with an honest comment.

### 1. "Home still has a caption bar with close and maximise"

Two independent holes, and either one alone puts a title bar on the home:

* **`isDesktopTask` matched on the package, and the package can be empty.** It comes from
  the task's `topActivity`, which is **NULL** while a task is showing its starting window
  and for a task whose activity has gone but whose task has not. `getPackageName().equals("")`
  is false, so the home stopped being "the desktop" and this service dressed it like an app:
  minimise / restore / close, ours, over the platform's own strip.
  The bottom-most ROOT task of the desktop display is the home whatever the task list says
  about it — apps open above it, in freeform — so that task is now never dressed, package
  or no package.
* **The platform will not always take the home out of freeform.** `pinHome` asks
  (`UNFLOAT` → `setWindowingMode`, Android 12+); when the release refuses, asking again
  every four seconds for the whole session changed nothing. After three passes the task is
  now **replaced**: closed, and the home started again through the daemon's `HOME`, which
  passes `--windowingMode 1` — a task that is fullscreen by construction has no caption to
  draw. One flicker, once, rather than a title bar nothing in this process can remove.

### 2. "The task manager still does not list a minimised app"

The taskbar got this in 0.7.16; the Task Manager did not. Its app list is built from the
PC's running broadcast, which is built from VISIBILITY — a minimised window is invisible and
leaves it. It now lists `CaptionService.openPkgs()` (the in-process answer the taskbar uses,
which carries the minimised set) and re-checks that set on its two-second tick, so a window
minimised while this screen is open appears without waiting for a broadcast.

### 3. A report that can name the owner of a caption

Connect → Diagnostics now also prints every accessibility window on the desktop display:
type, layer, active, title, bounds. That answers the one question a screenshot cannot —
whether the strip over the home is OURS (a window of the caption service), the platform's
window decoration (a TYPE_APPLICATION window at the task's origin), or something else.
The report is written to `Downloads/Dex-X-diagnostics.txt` (0.7.16).

### Checks

`python3 tools/audit.py` — **46/46**, four new: the base-task rule, the replace-not-ask
escalation, the Task Manager's open set, and the windows dump.

## v0.7.16 — the platform's own caption, a minimised window that came back, and the first animation

Five reports. The first three are one cause each, and the third one had never been named
correctly in four rounds of guessing at it.

### 1 + 3. "Home still has a caption bar" and "the app sits high with black below"

**The platform's own window decoration.** The desktop display runs the platform's desktop
decoration model, which draws a caption for EVERY task on it — the launcher's own home
included — and publishes a caption *inset* that shrinks each app's content. Everything
this project did until now was about OUR bar: the accessibility caption, the fallback
caption, `isDesktopActivity`, the delete-the-nav-builders round. None of it could touch
the platform's own strip, so:

* the home wore a title bar with minimise/maximise/close from the platform, not from us;
* every app was inset twice — once by the platform's caption inset, once by our strip —
  so its content started a caption lower than it should (the top under two bars) and its
  frame ran a caption past its bottom edge, which is the black band.

The switch that owns this is `Wm.setShouldShowSystemDecors`, which the window daemon may
call because it runs at uid 2000 with INTERNAL_SYSTEM_WINDOW. New `DECORS` verb, new
`WmClient.decors`, and `CaptionService.ensurePlatformCaptionsOff` — asserted every ~10 s,
because the display is recreated with every session, and the change is logged with what
the setting was before. `--no-vd-system-decorations` (which scrcpy already passes) is
not honoured by this phone's decoration model; this is not a duplicate of it.

### 2. "A minimised app is not in the taskbar any more"

0.7.13's fix made `openPkgs()` include the minimised set, so a hidden window should have
stayed on the bar. It did not, because `CaptionService` also had this in its reconcile:

```java
if (t == null) { it.remove();   // closed while it was minimised
```

`t == null` means "not in this pass's list", and the list is the display's ROOT tasks —
which a task hidden with `setHidden(true)` drops out of on several phones. So the
minimised entry was deleted a poll after it was created, the taskbar lost the app on the
very next broadcast, and there was no way back to it. Now a missing task only starts a
clock; after 2.5 s the daemon is asked once (`HAS <taskId>`, over every task on the
phone), and only "OK 0" — genuinely gone — removes it.

### 5. Close and minimise move now

There is nothing in this process to fade: the window is a task on the phone and its
pixels belong to the app. What we own is its RECT, so that is what moves — eight frames
of 20 ms through the unanimated `bounds` path a drag already uses, eased out. Close
collapses toward the ✕ that was pressed; minimise collapses down onto the taskbar. The
reconcile leaves the geometry of a collapsing window alone (no re-strip mid-animation,
no edge bands on it), and the window's real action — hide or close — happens when the
collapse finishes.

### 4. The cursor, answered

Reported twice as "the cursor does not change shape". It cannot, in the mode the desktop
uses, and that is a choice this project already made with the same question answered two
releases ago: scrcpy's `sdk` mode injects events *below* the pointer layer, so the
computer's own pointer is what is on screen — Windows' arrow, one for one, free to leave
the window — and no pointer the phone would have drawn exists to change shape. The
alternative (`uhid`) does change shape, because the phone draws it, and it is also
captured, relative and Android-styled. Asked, and answered: **keep the computer's arrow**.
So the shape not changing is now documented behaviour rather than a bug to chase, and
Settings → Mouse & cursor keeps the one-tap switch for anyone who wants the other trade.

### 6. A report that can leave the phone

Connect → Diagnostics now also writes `Downloads/Dex-X-diagnostics.txt` (MediaStore, no
storage permission involved) with the pointer mode, the platform-caption state, the task
list with bounds and app bounds, and the launcher's own log. A screenshot of a symptom
cannot say whether the caption switch was refused; that file can.

### Checks

`python3 tools/audit.py` — **42/42**: three new ones pin this round (decors wired end to
end, the `HAS` retention path, both collapse animations).

## v0.7.15 — the desktop's own home, the phone's home screen, and a setting nobody chose

Four reports. Three of them share a cause that has been in the code since the desktop was,
and the fourth is a limitation of Android that no amount of patching removes — so it says so.

### 1. "Dex home double" — the phone's home screen was on the desktop display

Two screens in a row: first one with nothing on it but a button (the phone's own wallpaper
behind it), then the real desktop. The second one is ours. The first one came from three
places at once:

* **The splash's pixel probe.** The desktop's splash decides the desktop has drawn by
  sampling the display and looking for anything that is not one flat colour. A virtual
  display that has no task on it yet is not necessarily blank: several phones put their
  own home screen — their wallpaper, their icons — on a public display the moment it
  exists. The probe read that as "the desktop is up", opened, and the user was shown the
  phone's home first. The splash may now look at the pixels **only after the computer has
  started the launcher** (`splash-gate.patch`, a marker in `/data/local/tmp` written by
  `adb_start_launcher`, cleared by every session that asks for a splash).
* **The platform's own home task.** If the phone puts its launcher on the desktop display,
  `CaptionService` sweeps it away — once every four seconds, and only if the close
  succeeded. Both halves are wrong when it is the only home there is: the sweep now runs on
  **every pass while our own home is missing**, and a refused close is asked again instead
  of being remembered as done.
* **A freeform home.** That is the other half of report #1 — see below.

### 2. "…and the second one has close/minimize — why?"

A freeform task wears the platform's caption. Our desktop's home must never be a window,
and `android:resizeableActivity="false"` does not make that true: `prepare_desktop` sets
`force_resizable_activities=1` for the whole phone, and that setting makes **every**
activity resizeable. 0.7.12 relied on the manifest attribute, which is why the report
survived it.

The real cause was the daemon's `HOME` verb: `am start` with no windowing mode, onto a
display whose default mode is FREEFORM — the launcher came back as a small window with a
caption. It is now `--windowingMode 1`, matching the computer's own start. And because a
task keeps the mode it was made with, the reconcile now also **takes a freeform home back
out of its window** (`WmDaemon UNFLOAT` → fullscreen, bounds, app bounds, caption inset
removed, one transition). Any start from anywhere — a stale intent, a notification, a
resume after the display was rebuilt — self-heals on the next poll.

### 3. "The navigation bar comes back when I take the mouse to the bottom"

It is the system's own gesture bar, not ours: immersive sticky hides it, and a pointer at
the bottom edge brings it back transiently over the taskbar, then it hides itself. The one
thing an app may say about that area is `setSystemGestureExclusionRects`, so the taskbar's
band (plus the gesture slop) is now excluded, re-applied whenever the window is laid out.
It cannot be *deleted* — it is drawn by the system over every window on the display — but
it no longer appears for a pointer that is only on its way to a taskbar button.

### 4. "The cursor — why does it look like that?"

Because of a setting nobody chose. Every build up to 0.7.14 wrote the pointer mode to a key
called `mouse`, so anyone who once tried the phone-drawn pointer kept it **for good**: the
0.7.12 change of default could not reach it, and every report since was reading a stored
value rather than the default. That key is retired on both sides:

* the PC reads `mouse2` only, which only the new Settings writes;
* the phone retires the stale value once (`DexPrefs.mouseMode`, marker
  `stream_mouse2_migrated`), so the Settings card and the cursor art agree with the PC.

The default — and now the real behaviour — is the computer's own pointer: Windows' arrow,
free to leave the window, one pixel for one pixel. No Android art, no resize handles drawn
by us, exactly as the report asked.

### 5. Sound: what you answered

Android has **one** audio capture path for the whole device. `output` moves the entire mix
to the computer and silences the phone; `playback` is a copy apps may refuse; `--audio-dup`
plays it on the phone as well. There is no "the Dex display's audio here, the phone's own
audio there" — the phone's video, its music and the desktop's sound are one stream. You
chose **the desktop's sound here and the phone silent**, which is what 0.7.14 already does;
nothing changed in this build, and this note is here so the behaviour is a decision rather
than a surprise.

### 6. The audit, now 39 checks

`tools/audit.py` grew the checks this round turns into promises: the daemon's `HOME` pins
fullscreen, `UNFLOAT` exists on both sides, `pinHome` runs from the reconcile, the platform
home is swept every pass while ours is missing, the splash cannot open before the launcher
starts, the computer opens that gate, the taskbar's band is excluded from the system
gestures, and neither side can be dragged back to `uhid` by a stored value from an older
build. **39/39 pass.**

## v0.7.14 — going deeper: scrcpy's own documentation, and edges that are really ours

Two asks in this round: go deeper, and try another way. Both are in this build, plus the first
**mechanical** proof this project has had that its own claims are true.

### 1. The sound: we were using the wrong source all along

Read scrcpy v5.0.1's `doc/audio.md` instead of trusting memory. It says, in as many words:

| Source | What scrcpy does | Android floor |
| --- | --- | --- |
| `output` (**the default**) | "forwards the whole audio output, **and disables playback on the device**" (REMOTE_SUBMIX) | 11 |
| `playback` | "captures the audio playback (**Android apps can opt out**, so the whole output is not necessarily captured)" | 13 for the dup path |
| `playback --audio-dup` | keeps the sound playing on the phone **as well** | 13 |

Every round until now asked for `playback`:

* 0.7.12/0.7.13 passed `--audio-dup` → the phone kept the sound. That was the report.
* 0.7.13 (after its re-read) dropped the flag but kept `playback` → the phone *probably* went
  quiet, but every app that opts out of playback capture — most DRM video, some music apps — still
  played out of the phone's own speaker. "Sound phone e e baje" would have survived for exactly
  those apps.

**Now the flag is simply not passed.** scrcpy's default `output` source takes the whole mix away
from the phone and gives it to the computer, no app can opt out, and the phone's own output comes
back when the session ends. `--audio-dup` is not passed anywhere.

### 2. Resize: the window's own edges are ours now, not just the title bar

The report was "the icon is on the corner but it only works inside". That was never a gesture bug —
it was ownership. Our chrome is a `SurfaceControlViewHost` whose surface *is* the 40 dp caption
strip, so the app's own surface below it (and its corner) answers to the app.

New `EdgeResize`: three thin, invisible `TYPE_ACCESSIBILITY_OVERLAY` windows on the desktop's
display, laid along the **front-most** window's right edge, bottom edge and bottom-right corner.
An overlay also fixes the other half of the complaint — a touch stream belongs to the window that
received ACTION_DOWN until ACTION_UP, so a drag that starts on a 7 dp band keeps arriving after the
pointer is a hundred pixels away, which a 40 dp strip could not promise.

Only the front-most window gets bands: an overlay sits above every app, so a band belonging to a
window three deep would swallow clicks meant for the one on top. Bands are hidden while a window is
maximised, frozen while a drag is in flight (the reconcile poll carries a rect a frame or two behind
the pointer), and applied on the same newest-wins `DRAG` channel the title-bar grip uses.

### 3. Every caption button is drawn

◧ ◨ — ▢ ❐ ✕ on each window's title bar were TextViews holding dingbats: the stroke weight and
optical size came from whatever font the display shipped, inside a bar whose other icons are drawn.
They are `Glyphs` paths now, tinted through a colour-state list so the close button still goes white
over its red. The fullscreen toggle in the tray, and the ⚠ in a Settings dialog, went the same way.

The two dead navigation builders (`buildNavCluster`, `buildWin11NavCluster`) are **deleted**, not
kept unreferenced — a builder that exists is a builder somebody will call, which is exactly how this
view survived two rounds of "navigation bar ekhono ache".

### 4. `tools/audit.py` — the claims, checked by machine

`python3 tools/audit.py` reads the source (never a changelog) and asserts each promise: the pointer
mode and its gate, no navigation cluster anywhere, the home's resizeable flag, no pictograph in any
string, `--audio-dup` absent, `--audio-source` absent, the stored answer's `(true, false)`, the
minimised set feeding `openPkgs`/`focusPackage`, `EdgeResize`'s overlay plumbing and its
drag-freeze, the fallback band's offset, and that all five version files agree. **30/30 pass now**,
and it exits non-zero the moment one stops being true — the first time in this project that a claim
cannot quietly rot.

Two of its first three failures were real code (a leftover `◁ ○ ▢` in a comment that turned out to
be only a comment — and the caption's dingbats), and one was the check itself being too crude; the
script is part of the repo so the next round starts from evidence.

### Still open, unchanged

UAC "Unknown publisher" (needs a CA certificate), Ctrl+V paste, the minimise animation, the in-APK
QR scanner, `FLAG_SECURE` windows, and a whole-window resize *frame* on the left and top edges (the
right, bottom and the corner are ours now). Nothing in this round was verified on a device — the
evidence is the source, the scrcpy documentation, and the audit.

## v0.7.13 — the re-read: four things 0.7.12 claimed or missed

Asked to go back over every report instead of trusting the last round. Four defects came out of it,
one of them mine, from 0.7.12, shipped an hour earlier.

| What the re-read found | Where | Fix |
| --- | --- | --- |
| **The navigation row was still there** — in the **touchpad**, not the taskbar. The pad's panel ended in our own ◁ ○ ▢ (Back · Home · Recents). 0.7.12 removed the taskbar's cluster and the release notes said "removed everywhere"; this copy survived in `Touchpad.java`, which is a different window on the same desktop. | `Touchpad.java` 807–814 | Deleted, with its two now-unused helpers. The pad's own three-finger gestures already are those three actions (tap = Back, up = open apps, down = show desktop), and they are mapped in Settings → Touchpad. |
| **A regression I introduced in 0.7.12**: removing the navigation child from the taskbar's `FrameLayout` also changed the *remaining* child from `WRAP_CONTENT`+`CENTER` to `MATCH_PARENT`+`FILL`. The Windows 11 cluster is that child, and a fill-parent child aligns its content to the LEFT — the centred Windows taskbar would have jumped to the left edge. | `LauncherActivity.buildTaskbar` | Back to `WRAP_CONTENT`+`Gravity.CENTER`, which is what it was before the cluster was removed. Removing a child must not re-align the ones that stayed. |
| **The sound chooser was still in the UI** — "Where the sound plays", on the title bar of every app window in the Windows shell (and on the full-screen bar). Every round so far removed it from the *phone's* screens; it lived in the computer's. It also still worked: picking a phone output routes the phone's audio back through the daemon. | `apps/win/frame.rs` (`hit`, `top_hit`), `apps/win/render.rs` | Nothing answers a click in that slot any more and no speaker glyph is drawn. The hit-test returns `Caption` there, so the window drags. The rectangle stays in the layout arithmetic (moving it would move every caption button after it), and `Hit::Sound` keeps its wire code. |
| **Restoring a minimised window could un-hide the wrong one.** `CaptionService`'s restore receiver walked the `minimised` map — a `HashMap` — and took the *first* package match. For a package with several windows (ours: Settings, File transfer, Task manager) that is arbitrary. It also addressed the task by `desktopDisplayId` rather than the display the task was minimised on. | `CaptionService.restoreReceiver` | An explicit `activity` wins; otherwise the most *recently* minimised window, which is what a taskbar click means. The un-hide uses `Min.display`, the display the task id was recorded on. |

### Also in this round

* **The resize gesture now says what it did.** One `DexLog.step` line per gesture — edge, dx, dy, and
  whether the platform sent `ACTION_CANCEL`. "Resize only works where it is drawn" cannot be settled
  from source: it turns on whether a device keeps delivering MOVE events to a 40 dp overlay after the
  pointer leaves it, and that differs by device. Next report will have the number in the log.
* **The two dead navigation builders are labelled as such** in `LauncherActivity` rather than left
  looking live.

### What the re-read confirmed as still broken, with no fix in this build

1. **UAC "Unknown publisher"** — needs a CA-issued code-signing certificate. Unchanged.
2. **Ctrl+V paste** — unchanged from 0.7.11.
3. **Minimise animation** — unchanged.
4. **QR scanner inside the APK** — not built.
5. **`FLAG_SECURE` windows still black** — not solved.
6. **Resize from a window's own bottom-right corner** — our chrome owns the strip, not the frame.
   Still a rewrite, not a patch.
7. **`--audio-dup` is gone, but not every app can be captured.** Apps that opt out of playback
   capture (most DRM video) keep their sound on the phone; nothing on the computer's side can change
   that.

## v0.7.12 — the pointer, the bar and the sound, traced to the line

Nine reports. Each one below is traced to the line that causes it, not to a guess; where a report
could not be traced, it says so instead of shipping a "fix".

### The pointer (three reports, one cause)

| Report | What was actually wrong | Fix |
| --- | --- | --- |
| **The mouse is too sensitive** | Not a speed setting: in `uhid` the pointer is **relative** and moves in *device* pixels, so the same physical centimetre moved it further than the arrows drawn under it. `DEF_CURSOR_SPEED = 0` was already the middle of the range, and the only thing the speed slider writes is Android's `pointer_speed` over adb, which cannot slow a relative device down enough to make 1:1 true. | The default is `sdk` again (`App.tsx`, and `apply_stored_config` only takes `uhid` when the phone's settings say `uhid` by name). In `sdk` the pointer is absolute: one pixel of the desk is one pixel of the window, so there is no ratio to get wrong. |
| **The cursor locks inside the Dex window** | That is what `uhid` *is*: the computer's pointer is captured by the device while it is over the stream — click to grab, Alt to release. Not a bug in our code, and not something a setting inside a captured pointer can fix. | Same change. `sdk` mode never captures: the pointer leaves the window like it leaves any other window. |
| **The cursor art must be Windows' own** | It was ours. `DexCursors` renders seven styles of arrow — rounded, keylined, shadowed, some hollow — and one of them is the default, so every desktop session drew a pointer that was deliberately *not* the platform's and could never be Windows'. | `DexCursors.apply()` now returns before touching anything unless the phone asked for `uhid`. In `sdk` nothing of ours is applied and the arrow on screen is the computer's own — the one Windows draws. The styles still exist for the one mode where the phone owns the pointer. |

`uhid` is not gone: Settings → Mouse & cursor → *Pointer rendering* still offers it, and it is still
the only mode in which Android changes the pointer's shape over a link or a window edge. What changed
is which of the two is the default, and that the art is not applied to a pointer the computer owns.

### The navigation bar, again — third time

`buildTaskbar()` still built it. The 0.7.11 change set `navCluster` to `GONE` **after** building
`buildNavCluster()` (◁ ○ ▢) or `buildWin11NavCluster()` (◁), and 0.7.11's report was right that it
still existed — a gone view that is still constructed is one layout pass, one accessibility node and
one theme change away from being visible again.

Now: no cluster is built at all. `buildTaskbar` no longer calls either builder, the two builders are
unreferenced, and `syncNavCluster()` — which used to return early when there was no cluster to sync —
hides the platform's bars first and unconditionally, because the early return had been skipping that
for every session since the cluster went `GONE`.

### The caption on the home — the platform's, not ours

Ours was already fixed in 0.7.11 (`isDesktopActivity` compares simple class names, so the home is
never dressed). What was left is the strip Android draws: `LauncherActivity` was declared
`resizeableActivity="true"`, and a resizeable activity may be placed in a **freeform** task — and on a
desktop-mode display every freeform task wears the platform's own caption, minimise/maximise/close
included, above the desktop's wallpaper.

It is `resizeableActivity="false"` now. A non-resizeable activity can only be fullscreen, and a
fullscreen task has no caption to draw. The desktop is fullscreen by design; nothing about it wants a
freeform home.

### Emoji are gone from the shell

Every one of them: the magnifier in Search, 👤 in the account row, 📁 in the drawer and in the taskbar's
own-window tiles, 🔔 on the bell, 🖱 on the touchpad button, ⚙ 📊 📁 in the app menu, 🔋 ⚡ in the battery
pill, and the twelve quick-settings fallbacks (📶 ✈ 🔇 🔦 📱 🔒 📷 and the rest).

They are drawn now, from paths, by a new `Glyphs` class: 24-unit icons that scale to whatever density
`wm density` last set, in the shell's own colours, at the shell's own weight. The reason is not taste —
an emoji is whatever glyph the phone's font happens to carry: coloured where everything around it is
not, a different optical size and weight from every other control in the bar, differently drawn on
every OEM, and a tofu box on an old font.

### Sound still on the phone — `--audio-dup` means "keep playing it there too"

0.7.11 fixed the override that had been forcing `--no-audio`; the sound then really was forwarded. It
was forwarded **with `--audio-dup`**, and that flag is not "play it here as well", it is *"keep playing
it on the phone as well"*. So the computer played it, the phone played it, and the report was exactly
right.

`--audio-dup` is not passed any more — not by the desktop session, not by the audio companion. Playback
capture on its own **moves** the output: the phone is silent for the life of the session, and gets its
own output back the moment the session ends. (Android's floor for a move is 11; 13 was only needed for
the duplicate we are not asking for, so the `< 33` branch that used to disable audio is gone too.)

### A minimised app stays in the taskbar — and comes back when clicked

`CaptionService.openPkgs()` walks `tasks`, and `tasks` is built in `applyTasks()` from
`t.isFreeform() && t.visible` — so the moment a window was minimised it left the only list that could
name it. The `minimised` map was the sole record, and only the broadcast carried that; the taskbar's
per-tile click went through `focusPackage()`, which also walks `tasks`, found nothing, and fell through
to a fresh `am start` — which cannot clear the hidden flag, so the window never came back.

Two changes: `openPkgs()` unions the minimised set, and `focusPackage()` — when no *visible* task
matches — looks the package up in `minimised` and un-hides that task (`wm.show`). `Min` now carries its
display id, because there is no task record left to ask.

### Not fixed, and said so

* **The resize grip works where it is drawn — the window's edge does not.** Measured in code, and this
  is the honest answer: our chrome owns the caption strip and *nothing else*; the window's other three
  edges and its corner belong to the app's own surface. A hand that starts on the drawn `⤡` resizes
  (that is a real gesture on the strip), and a hand that starts on the window's bottom-right corner
  reaches the app, not us. Owning that corner needs the app's bounds inset on all four sides — a
  frame, not a strip — which is a daemon verb and a chrome rewrite, not a bug fix. One real bug *was*
  found and fixed here: `CaptionFallback`'s right-hand band started **two** bar-heights below the top
  instead of one, so the 40 dp directly under the title bar resized nothing.
* **Ctrl+V paste** and **the minimise animation** are unchanged from 0.7.11 — still open.
* **Code signing** is unchanged: still no certificate, still "Unknown publisher" at UAC.

## v0.7.11 — the two overrides that undid the last two releases

Checked again as asked, and the check found why 0.7.9 and 0.7.10 could both look unchanged:
`apply_stored_config` — the function every session passes through — **overruled the caller**.

| What was asked for | What `apply_stored_config` then did | Result on the phone |
| --- | --- | --- |
| `audio: true, audioPlayback: true` (0.7.9's "the desktop carries its own sound") | `opts.audio = false; opts.audio_playback = false;` — a leftover from the days of the audio companion | every desktop session was built with `--no-audio`; the phone kept its sound and the computer stayed silent |
| `mouseMode: "uhid"` (0.7.9's "Android draws the pointer") | `_ => opts.mouse_mode = None`, where `_` covers "unset" | every desktop silently fell back to `--mouse=sdk`, the one mode in which Android never draws a cursor — so no hand, no I-beam, no resize arrow |

Both are fixed: audio is the caller's decision and nothing overrides it (the Android floors are
still applied in `start_mirror`, before the command line exists), and only an explicit `mouse: sdk`
in the stored settings may take the pointer back — "unset" no longer means sdk.

### The rest of the list

| Report | What it was | Fix |
| --- | --- | --- |
| **The home wears a caption with ✕, and closing it shows the phone's wallpaper** | The desktop's own home was recognised by comparing the task's activity to the full class name. What a `dumpsys` parse reports is not always that form (`.LauncherActivity` on some platforms), so the home failed the test, got a caption — minimize, maximize, CLOSE — and closing it left the display to Android, i.e. the platform's home with the phone's wallpaper. | The test is on the simple class name now, in every form a parse produces, so the home is the home and never wears chrome. If it ever does go missing, the service starts it again through the daemon's `HOME`. |
| **The taskbar's "Sound output — Computer and phone" row** | A row offering a choice that no longer exists, and misreporting the state while it was at it. | Removed. Sound comes to the computer and the phone keeps playing; there is nothing to choose. |
| **CPU / RAM / DISK in the corner** | A desktop widget nobody asked for. | Never built — not hidden, not a setting. The numbers are still in the Task Manager. |
| **Navigation buttons in the drawer** | Our own ◁ ○ ▢ strip, which survived the last round on the phone's dock. | Removed everywhere. Windows carry — □ ✕, the taskbar carries Start, Back is the phone's. |
| **Touchpad options in the exe's Settings** | The phone-as-touchpad is a phone feature; its settings were in the desktop's Settings window too. | The section is gone from that window. |
| **UAC says "Unknown publisher"** | UAC's *Verified publisher* line comes from an Authenticode signature, and there is none — every name field Windows reads (uninstall key, CompanyName on both exes, the wizard's welcome page) already says Rabbihossainltd. | Attempted: signing in CI with a self-signed `CN=Rabbihossainltd` certificate. **It did not work** — `Set-AuthenticodeSignature` hangs on the runner until its timeout, with and without a timestamp server, so the step was removed rather than left to burn five minutes a build. What remains is the honest answer: the name is everywhere except the signature, and a **Verified publisher** needs a CA-issued code-signing certificate. |

## v0.7.10 — gestures that follow the pointer, and a taskbar that stays

| Report | What it was | Fix |
| --- | --- | --- |
| **Resizing a window is not smooth** | Every drag frame went through the daemon's `RESIZE`, and `RESIZE` issues `Wm.transition` — a windowing **animation**. One animation per frame, each cancelling the last, is a window that trails the pointer by the length of an animation and rubber-bands the harder you drag. The daemon's own comment on `MOVE` already said a transition is the wrong tool per frame; the resize path never got the message. | A new `DRAG` verb: the same bounds + app-bounds write as `RESIZE`, applied with `applyTransaction` — no animation. `RESIZE` keeps the transition for the one-shots (maximise, restore, snap) where there is one jump to animate. |
| **…and moving a window stutters too** | Drag frames shared the request/reply connection with the reconcile's `LIST` poll, so a poll in flight was a frame the pointer had already outrun, and frames queued behind it. | Gesture frames (`DRAG`, `MOVE`) now travel on their own socket, newest-wins: a frame not yet sent is replaced by the next one rather than queued. A slow round trip can cost a frame; it can no longer build a backlog. |
| **The taskbar vanishes when the app drawer opens** | Nothing was covering it — it had **auto-hidden**. Opening the drawer moves the pointer off the bar, the idle timer expires, and the bar slides away to its peek strip. | The drawer pins the taskbar: while it is open, both collapse timers refuse to run, the bar is revealed, and (overlay path) its window is re-added so it stacks above the drawer's scrim. `hideDrawer` unpins. |
| **Publisher name in the Windows installer** | Set in 0.7.8 — `publisher: "Rabbihossainltd"` becomes the uninstall key's `Publisher`, the setup's `CompanyName` and the app exe's `CompanyName`. | The **welcome page now says it too**: "Published by Rabbihossainltd". Windows' "Unknown publisher" on the UAC prompt is a code-signing question and stays until there is a certificate; the name itself is in every field Windows reads. |

Two things found while re-checking 0.7.9 instead of trusting it:

- `reconcile_audio_session` decided whether a companion was still wanted from a helper that also counted a live **desktop** as "sound is here". Between the decision and the spawn, a desktop alone could pass that check and start a second capture — the exact double-capture the 0.7.9 change exists to remove. Both places now ask the app-window question only.
- The platform-home sweep is asked once per task id. If a phone ever did relaunch its home on the desktop display, the old loop would close it every four seconds for the whole session.

## v0.7.9 — five things a working caption bar made visible

With the window daemon finally running, the desktop appeared and the next five reports
became answerable in the code:

| Report | What was actually wrong | Fix |
| --- | --- | --- |
| **No sound on the computer, and not separated from the phone** | The sound lived in a second, `--no-video` scrcpy process (the "companion"). It could die a second after starting and leave nothing behind but a log line — and on a phone below Android 13 its `--audio-dup` is refused outright, so the retry then took the phone's sound away. | The desktop session carries the sound itself: `--audio-source=playback --audio-dup` on the one process the user is already watching. The Android floors are now enforced in Rust before the command line is built — dup on 13+, any forwarding on 11+ — so an old phone gets "sound moved here" instead of a session that exits. The companion still exists for app windows, which have no other owner. |
| **The pointer is a plain arrow, never a hand or a resize handle** | The desktop asked for `--mouse=sdk`, the one mode in which Android never draws a pointer. The launcher has had a whole pointer vocabulary all along (`DexCursors`: hand, I-beam, resize, resize-h, resize-v, grabbing, no-drop) and none of it could ever reach the screen. | `--mouse=uhid`: the phone draws the pointer, with the shape the platform picks for whatever is under it. The pointer is captured while it is over the desktop — click to grab, Alt to hand it back — which is what scrcpy's own documentation describes for this mode. A phone that refuses `/dev/uhid` never gets a desktop at all, which the existing `fallback_options` retry already recovers from. |
| **An app does not stay in the taskbar when minimised** | The PC's open-apps broadcast is built from *visibility*, and a minimised window is not visible — the device side has a "minimised, still open" set exactly for this (`CaptionService.minimised`), but it only broadcast it when it *changed*. A launcher activity re-created mid-session therefore started with the set empty and never heard about it again. | The set is re-stated every ~2 s while it is non-empty. An in-process broadcast, and it removes the class of "the tile vanished and never came back". |
| **The phone's navigation bar is on the desktop** | Ours, drawn by `syncNavCluster` whenever the platform's bar was absent — which is exactly when the desktop looks right otherwise. | The desktop never draws navigation keys: `— □ ✕` are on every caption, Start and the open apps are on the taskbar, and the phone's dock keeps its keys because there is no taskbar there. The platform's own bars are still asked to stay away (`hideSystemBars`), and the log now says which of the two was on screen. |
| **Two home screens, one on top of the other** | Android launches the phone's HOME activity onto a new display by itself, before our launcher is started on it. Our surface is opaque, so the platform's home showed through at every redraw — and it brings its own wallpaper and its own bars with it. | `CaptionService` now sweeps the desktop display: a task whose package IS the current HOME package (and is not ours) is closed. The phone's own home is untouched — it is a different display, and nothing of the user's lives in that task. |

## v0.7.8 — the window daemon was last in a chain that could stop anywhere

A full read of the desktop's start path, and the reason a working session could be refused
on any phone that ignores one setting:

| # | The defect | Why it matters | Fix |
| --- | --- | --- | --- |
| 1–6 | `adb_prepare_desktop` ended with `start_wmd`. Ahead of it sat a chain of `settings put` round trips joined by `?`, so **the first refused key aborted the function and the window daemon was never started**. The phone then reports `PING: NO ANSWER`, and with no daemon there is no caption bar, no resize, no taskbar row, no strip and no phone-side repair — every item on the list, from one line. | A managed phone, an OEM that drops `hidden_api_policy`, or one failed `adb shell` is enough. The desktop still appears (scrcpy makes the display either way), which is why it looked like ten separate bugs. | The daemon starts **first**, before anything that can fail, and the settings write is no longer fatal: it logs and the read-back below says what actually landed. |
| — | The PC report did not look at the desktop's own wiring | "Create a diagnostics report" listed devices and payloads, but not whether `adb forward tcp:7191` existed, whether the daemon answered, or what the phone's accessibility list said. | The report gains **desktop plumbing**: `adb forward --list`, a `PING` to the daemon, the desktop display id, the tail of `/data/local/tmp/wmd.log`, the phone's `enabled_accessibility_services`, and the launcher's installed version. |
| — | The installer had no branding and the publisher read as the app | Reported directly. | `publisher: "Rabbihossainltd"` — that is what Windows shows in Settings → Apps and in the setup's own "Published by". The setup now carries its own logo throughout: `installer.ico` (the setup exe's icon and title bar), `installer-header.ico`, and NSIS's `headerImage` / `sidebarImage` bitmaps, all generated from `scripts/brand-src` by `brand-art.py`. |

## v0.7.7 — the installer that could not overwrite a running adb

The 0.7.6 diagnostics came back, and with it three screenshots of the installer failing.
Read together they name the bug that made the last four releases look like ten:

| # | Reported | The cause | Fix |
| --- | --- | --- | --- |
| — | "Error opening file for writing: …\resources\bin\adb.exe" (and `AdbWinApi.dll`, `AdbWinUsbApi.dll`) | Those three files are exactly what a **running adb** holds open. The Dex-X window keeps an adb server alive for as long as it is open, so the installer could not replace them, and it stopped half way. A half-installed folder is worse than an old one: the exe is new, adb and its two DLLs are old, and every symptom after that (no window daemon, no desktop, no taskbar row, no resize) looks like a different bug. | NSIS hooks (`src-tauri/windows/nsis-hooks.nsh`) shut the session down before a single file is touched — `Dex-X.exe`, `open-android-dex-tauri.exe`, `adb.exe`, `scrcpy.exe` — on install **and** on uninstall, then wait 800 ms for the handles to close. After the files land, `ie4uinit.exe -show` tells Explorer the icon changed, which is the "shortcut still shows the old logo" report. |
| 7 | "desktop shortcut করলে পুরোনো লোগো আসে" | Two causes, both real: Explorer caches shell icons per path, and the bundled exe was still named after the upstream project. | `mainBinaryName: "Dex-X"` — the installed program is now **Dex-X.exe**, so the shortcut, the taskbar button and the icon cache key are all new. Plus the `ie4uinit` above. |
| — | the diagnostics said "PING: NO ANSWER" and then, on its last line, `NetworkOnMainThreadException` | **The diagnostic was lying about the thing it exists to measure.** The report gathered socket I/O on the UI thread; Android throws for that, and the catch turned the exception into the answer. A daemon that was answering perfectly could be reported dead. | The gather runs on a worker thread; the dialog only renders. The exception can no longer become a verdict. |
| 8 | "phone er keyboard open hoi na connected thakle" | A phone driven from a computer has a **hardware keyboard** attached as far as Android is concerned, and Android's default is to keep the on-screen keyboard hidden while one is — so with Dex-X connected, tapping a field ON THE PHONE opens nothing. | `show_ime_with_hard_keyboard=1` joins the snapshot/restore set (`DESKTOP_SECURE`), so it is set for the session and put back on exit like `policy_control`. |
| — | the report had to be interpreted | — | A **VERDICT:** line first, in words: no daemon (the computer is not running a session) / daemon but no display (a session started and died) / display but no accessibility (no title bars, no resize) / all three up. And the whole report is appended to the on-disk log, so `adb pull Android/data/<pkg>/files/dex-x.log` gets it without copy-paste. |

### What the 0.7.6 report already proved

* `service enabled in settings: NO`, `service running: NO` — accessibility was not on, so **no title bar
  could have been drawn by any version**. That is a state only the phone can report, and now it does.
* `display 0: 1080x2400` — **the only display was the phone's own**. There was no desktop display at
  all, so there was no desktop to put a taskbar row on, nothing to resize and nothing to caption.
  Everything on the list follows from that one line.

## v0.7.6 — a build that makes the phone answer

Four releases in a row were guessed at from screenshots, and four in a row missed, because
three things can break the desktop at once and **all three are silent**:

* the accessibility service is not **enabled** (Android 13+ hides the switch behind
  *Allow restricted settings* for a sideloaded app);
* it is enabled but not **bound** (the phone refuses and says nothing);
* the window daemon (**wmd**) is not answering — which takes the task list, the caption
  strip, move, resize, the taskbar's app row and the phone-side repair with it.

This release does not guess. It makes those three answerable in one tap.

| What | Why |
| --- | --- |
| **Diagnostics** — a third pill on the connect screen | A plain-text report: the service running / enabled in Settings / what `problem()` answers; `PING` to wmd and the display it reports; every display with its real size and dpi; the full task list of the desktop display **and** of the phone's display, with windowing mode, rect, app bounds and package; how many fallback bars are up; and the last 160 lines of this process's own log. Selectable, with **Copy** and **Share**. |
| The log is now kept on the phone | `logcat` needs `READ_LOGS`, which this app does not have — so the only copy of these lines was in a terminal on somebody else's computer. `DexLog` keeps the last 600 lines in memory and appends them to `Android/data/com.ccrstech.openandroiddex.launcher/files/dex-x.log`, which `adb pull` can reach. |
| The pill blames the right component | It reported "the phone has not enabled window control" for **both** a missing service and a dead daemon. Those have different fixes; `problem()` now answers service → daemon → stuck, in that order. |

Nothing else changed. If the desktop is still wrong, that text says which link is broken.

## v0.7.5 — the phone's screen is the phone's

Reported with a screenshot: an app that is open on the desktop shows up on the phone
as a **small floating card in the middle of a black screen**, no title bar, and the
phone's own task switcher underneath. "completely separate devices" — which is what it
was always supposed to be.

| # | Reported | The cause | Fix |
| --- | --- | --- | --- |
| 10 | "desktop a same app open thakle mobile a choto hoye thake" | Android has **one task per app, not one per display**. A task that lives on the desktop keeps its freeform bounds and its reserved caption strip, and any phone-side claim on it — the phone's launcher, a notification, a call, `am start` without a display — moves the *same* task onto the phone with that shape intact. Three of our own paths feed it: `shapeForDesktop`'s freeform mode, the strip we reserve for the caption, and — the worst of the three — `withoutLaunchDisplay`, which rebuilt the failed launch with **the desktop's bounds and WINDOWING_MODE_FREEFORM minus only the display id**, i.e. it deliberately opened a small floating window on the phone. The old repair for this (`CaptionService.releaseFreeformOnPhone`) lives inside the accessibility service, so on a phone that has not bound it (see 0.7.4) it never ran. | Three changes. **`withoutLaunchDisplay` is now plain options** — a launch that cannot go to the desktop opens as a normal full-screen phone app instead of a floating card at desktop coordinates. **The daemon's LAUNCH mints a task of its own** (`-f 0x18000000`, NEW_TASK\|MULTIPLE_TASK) when the app has no window on the desktop yet, so the platform cannot hand the desktop a task the phone is already using — and drops the flag when it is a refocus, so a tap never mints a second copy. **`PhoneKeeper`** — a new, in-process loop with no accessibility dependency — watches the phone's own display through the window daemon and puts any desktop-shaped window of a desktop package back where it belongs: `NATIVE` in the daemon sets WINDOWING_MODE_FULLSCREEN, the full-screen bounds and clears the caption inset in one transaction. |
| 10 | — | — | `NATIVE` is also the cheap half of the old repair on phones where it did work: it does the un-strip **and** the bounds **and** the mode in one request, where the old path needed three. |
| 2 | "logo ager ta keno?" — the phone's home screen still showed the OLD logo | The rebrand made new art for the exe (`icon.ico`, the PNG set) but the **launcher's adaptive icon was never touched**: `ic_launcher_foreground.xml` was still the vector this project was forked with — a laptop with a phone in front of it. The two 23 KB PNG copies of the *new* mark that 0.7.4 deleted as "dead" were dead for exactly that reason: nothing pointed at them. And the exe's small sizes carried the full wordmark (3.7:1, about six pixels tall at 16 px), which is the "mud at 48 px and below" the branding rules were written to avoid. | `scripts/brand-art.py` — one source (`scripts/brand-src/{x,wordmark}.png`), one rule, every place the mark ships: **below 64 px the X**, **64 px and up the wordmark**, the launcher's adaptive foreground and monochrome layers included, and `icon.ico` rebuilt with **seven images each drawn at its own size** (PIL's own multi-size writer resamples one image and put the wordmark in the 16 px slot). The old vectors are deleted. |

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
