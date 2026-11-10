#!/usr/bin/env python3
"""Mechanical audit of every claim Dex-X has made about itself.

Each check reads the SOURCE (never a changelog) and asserts the property the
release notes promise. Run it after every change:

    python3 tools/audit.py

Exit code 1 if any check fails. Nothing here needs a phone, a Rust toolchain or
Gradle — it is grep-shaped on purpose, so it can run in the same second a change
is made, and so a claim cannot quietly stop being true.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAUNCHER = ROOT / "openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher"
RUST = ROOT / "open-android-dex-tauri/src-tauri/src"
TAURI_UI = ROOT / "open-android-dex-tauri/src"

results = []


def check(name, ok, detail=""):
    results.append((bool(ok), name, detail))


def read(p):
    return p.read_text(encoding="utf-8", errors="surrogateescape")


def launcher(name):
    return read(LAUNCHER / name)


def rust(name):
    return read(RUST / name)


# ── 1. the pointer: the computer's by default ────────────────────────────────
app_tsx = read(TAURI_UI / "App.tsx")
check("pointer: the desktop asks for sdk mode",
      'mouseMode: "sdk"' in app_tsx, "App.tsx desktopOptions()")

scrcpy = rust("scrcpy.rs")
mouse_block = scrcpy[scrcpy.find('map.get("mouse2")'):][:900]
check("pointer: only an explicit 'uhid' leaves sdk",
      'Some("uhid") => opts.mouse_mode = Some("uhid".into())' in mouse_block
      and '_ => opts.mouse_mode = None' in mouse_block,
      "apply_stored_config")

# The stored row's name carries a version. Every build up to 0.7.14 wrote `mouse`,
# so a phone switched to the phone-drawn pointer once kept it for good and no later
# default could reach it. Nothing may read the old name again.
check("pointer: a stored value from an older build cannot force uhid",
      'map.get("mouse")' not in scrcpy and '"mouse2"' in scrcpy
      and '"mouse2"' in launcher("SettingsActivity.java"),
      "scrcpy.rs reads mouse2, SettingsActivity writes mouse2")

# …and the phone's own half of the same stale value: the Settings card and the
# cursor art both asked getString(KEY_MOUSE_MODE) directly, so an "uhid" written by
# an older build still showed as selected there. DexPrefs.mouseMode retires it once.
check("pointer: the phone retires the stale 'uhid' too, once",
      "static String mouseMode(Context ctx)" in launcher("DexPrefs.java")
      and "KEY_MOUSE_MIGRATED" in launcher("DexPrefs.java")
      and "DexPrefs.mouseMode(this)" in launcher("SettingsActivity.java")
      and "DexPrefs.mouseMode(ctx)" in launcher("DexCursors.java"),
      "DexPrefs.mouseMode + both readers")

prefs = launcher("DexPrefs.java")
check("pointer: the launcher's default mode is sdk",
      'DEF_MOUSE_MODE = "sdk"' in prefs, "DexPrefs.DEF_MOUSE_MODE")

cursors = launcher("DexCursors.java")
check("pointer: our own cursor art is not applied in sdk mode",
      "hostDrawsPointer" in cursors and 'return !"uhid".equals(DexPrefs.mouseMode(ctx));' in cursors,
      "DexCursors.hostDrawsPointer")

check("pointer: speed is never sent as a raw 1:1 lie",
      "DEF_CURSOR_SPEED = 0" in prefs, "DexPrefs.DEF_CURSOR_SPEED")

# ── 2. no navigation cluster anywhere ───────────────────────────────────────
launcher_activity = launcher("LauncherActivity.java")


def code_lines(text):
    """Every line that is not a comment — what the shell actually draws."""
    out = []
    for line in text.splitlines():
        t = line.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        out.append(line)
    return out


# a call site is "buildNavCluster()" / "buildWin11NavCluster()" NOT preceded by
# 'private View ' — and not inside the note that explains why they were deleted.
call_sites = [m.group(0) for m in re.finditer(r"build(?:Win11)?NavCluster\(\)",
                                              "\n".join(code_lines(launcher_activity)))
              if "private View " not in m.string[max(0, m.start() - 14):m.start()]]
check("nav: no taskbar navigation cluster is built",
      not call_sites, f"call sites: {call_sites}")

touchpad = "\n".join(code_lines(launcher("Touchpad.java")))
check("nav: the touchpad panel draws no ◁ ○ ▢ row",
      "◁" not in touchpad and '"○"' not in touchpad and '"▢"' not in touchpad,
      "Touchpad.java")

# the platform's own bar must still be hidden on the desktop display
sync = launcher_activity[launcher_activity.find("private void syncNavCluster()"):][:900]
first_line = next((l for l in sync.splitlines()[1:] if l.strip() and not l.strip().startswith("//")), "")
check("nav: hiding the platform's bars does not depend on our cluster existing",
      "hideSystemBars()" in first_line, first_line.strip()[:60])

# ── 3. the home cannot wear a caption ───────────────────────────────────────
manifest = read(ROOT / "openandroiddex-launcher/app/src/main/AndroidManifest.xml")
home = manifest[manifest.find('android:name=".LauncherActivity"'):][:1200]
check("home: LauncherActivity is not resizeable (so it cannot be put in a captioned task)",
      'android:resizeableActivity="false"' in home, "AndroidManifest.xml")

fallback = launcher("CaptionFallback.java")
check("home: the fallback renderer skips our own home",
      "the desktop itself is the background" in fallback, "CaptionFallback.pass")

service = launcher("CaptionService.java")
check("home: the accessibility renderer skips our own home",
      "isDesktopActivity(t.activity)" in service, "CaptionService.isDesktopTask")

# ── 4. no emoji as UI ───────────────────────────────────────────────────────
# The pictographs that render as colour emoji in a phone's font — and NOT the
# typographic marks that do not: ✓ U+2713 in a checkbox, ✕ U+2715 on a remove
# row, ▢ U+25A2 nowhere any more. Those three are text by default on every
# Android font we have seen, and replacing them with paths buys nothing but a
# larger diff. Everything that IS a pictograph is drawn (Glyphs).
EMOJI = re.compile("[\U0001F300-\U0001FAFF\u2700-\u27BF\u2B50\u26A1\u26A0\uFE0F"
                   "\u231A-\u231B\u25A2\u2750\u2931\u2B06\u2B07]")
ALLOWED = {"\u2713", "\u2715"}  # ✓ ✕ — typography, not pictographs
offenders = []
for f in sorted(LAUNCHER.glob("*.java")):
    for i, line in enumerate(read(f).splitlines(), 1):
        t = line.strip()
        if t.startswith("*") or t.startswith("//") or t.startswith("/*"):
            continue
        for ch in EMOJI.findall(t):
            if ch in ALLOWED:
                continue
            offenders.append(f"{f.name}:{i} {ch}")
check("emoji: none left in a string the shell draws",
      not offenders, "; ".join(offenders[:6]))
check("emoji: there is a drawn-icon class",
      (LAUNCHER / "Glyphs.java").exists(), "Glyphs.java")

# ── 5. sound moves to the computer ──────────────────────────────────────────
body = scrcpy[scrcpy.find("fn build_args"):scrcpy.find("fn build_args") + 4000]
check("audio: --audio-dup is never passed",
      'args.push("--audio-dup"' not in scrcpy, "build_args")
check("audio: the playback source is not requested (the default 'output' one silences the phone)",
      'args.push("--audio-source' not in scrcpy, "build_args")
check("audio: --no-audio is only used when audio is switched off",
      'if !opts.audio {' in body and 'args.push("--no-audio"' in body, "build_args")
_sa = scrcpy[scrcpy.find("fn stored_audio_forwarding"):]
_sa = _sa[:_sa.find("\n}\n")]           # to the end of the function
check("audio: the stored answer forwards and never duplicates",
      "(true, false)" in _sa, "stored_audio_forwarding")
check("audio: the Rust test pins the command line",
      "the_desktop_session_moves_audio_and_does_not_duplicate_it" in scrcpy, "scrcpy.rs tests")
check("audio: the sound chooser is not reachable from a window's title bar",
      "inside(&b.sound, x, y)" not in read(RUST / "apps/win/frame.rs")
      and "inside(&t.sound, x, y)" not in read(RUST / "apps/win/frame.rs"),
      "apps/win/frame.rs hit()")

# ── 6. a minimised app stays, and comes back ────────────────────────────────
openpkgs = service[service.find("static java.util.Set<String> openPkgs()"):][:1200]
check("minimised: the open set includes the minimised map",
      "s.minimised.values()" in openpkgs, "CaptionService.openPkgs")
focus = service[service.find("static boolean focusPackage(String pkg)"):][:2600]
check("minimised: a taskbar click can un-hide a hidden task",
      "s.minimised.entrySet()" in focus and "s.wm.show(display, id)" in focus,
      "CaptionService.focusPackage")
check("minimised: a restore addresses the task's own display",
      "final int display = best.display;" in service, "CaptionService.restoreReceiver")
check("minimised: a restore of a package with several windows is not arbitrary",
      "best.since" in service, "CaptionService.restoreReceiver")

# ── 7. resize: edges exist where they are drawn ─────────────────────────────
check("resize: the window's own right/bottom edges are ours",
      (LAUNCHER / "EdgeResize.java").exists(), "EdgeResize.java")
check("resize: those edges are driven from the reconcile",
      "syncEdgeResize(" in service, "CaptionService.applyTasks")
edges = launcher("EdgeResize.java")
check("resize: the bands are accessibility overlays on the desktop display",
      "TYPE_ACCESSIBILITY_OVERLAY" in edges and "createWindowContext" in edges, "EdgeResize.open")
check("resize: a gesture is not re-placed by a poll while it runs",
      "if (dragging && taskId == this.taskId) return;" in edges, "EdgeResize.sync")
check("resize: the fallback's right band starts under the title bar, not two bars down",
      "lp.y = b.top + px;" in fallback, "CaptionFallback.Band.place")

# ── 8. the desktop's own home: the display, never a window ──────────────────
#
# Two reports in one: "dex home double" (the phone's own home screen shown on the
# desktop display before ours, because the display was empty and the platform put
# it there) and "home er upore close minimize keno" (our launcher in a FREEFORM
# task, wearing the platform's caption — force_resizable_activities overrides
# android:resizeableActivity, so the manifest attribute never protected it).

daemon = read(ROOT / "openandroiddex-wmd/src/com/ccrstech/openandroiddex/wmd/WmDaemon.java")
home_case = daemon[daemon.find('case "HOME":'):][:2000]
check("home: the daemon starts it fullscreen, not into the display's freeform default",
      "--windowingMode 1" in home_case, "WmDaemon HOME")

check("home: a freeform home can be taken back out of its window",
      'case "UNFLOAT":' in daemon and 'request("UNFLOAT ' in launcher("WmClient.java"),
      "WmDaemon UNFLOAT + WmClient.unfloat")

caption_service = launcher("CaptionService.java")
check("home: the reconcile takes a freeform home back to the display",
      "pinHome(display, live);" in caption_service
      and "wm.unfloat(display, t.taskId" in caption_service, "CaptionService.pinHome")

check("home: the platform's home is swept every pass while ours is missing",
      "ticks % 10 == 0 || !desktopHomeAlive(live)" in caption_service,
      "CaptionService.applyTasks")

splash_patch = read(ROOT / "openandroiddex-scrcpy/splash-gate.patch")
build_mjs = read(ROOT / "openandroiddex-scrcpy/build.mjs")
check("home: the splash may not open the display before the launcher has started",
      "splash.mayOpen()" in splash_patch and "CAN_OPEN" in splash_patch
      and "splash-gate.patch" in build_mjs,
      "openandroiddex-scrcpy/splash-gate.patch")

adb = rust("adb.rs")
check("home: the computer opens the splash's gate when it starts the launcher",
      "DESKTOP_LAUNCHER_STARTED_FILE" in adb
      and "DESKTOP_LAUNCHER_STARTED_FILE" in scrcpy,
      "adb_start_launcher writes, request_desktop_splash clears")

# ── 9. the platform's navigation bar stays out of the taskbar's band ────────
check("nav: the taskbar's band is excluded from the system's gestures",
      "setSystemGestureExclusionRects(keep)" in launcher_activity
      and "excludeBottomGestures(v);" in launcher_activity,
      "LauncherActivity.excludeBottomGestures")

# ── 9b. the platform's caption is off the desktop display ───────────────────
#
# "home e ekhono caption bar roye geche" and "app open korle ektu upore uthe thake,
# niche kalo": both are the platform's own window decoration, which draws a title bar
# for every task (the home's included) and publishes a caption inset of its own — on
# top of the strip this launcher reserves. It is switched off through the daemon.
check("caption: the platform's own decoration is switched off on the desktop display",
      'case "DECORS":' in daemon and "setShouldShowSystemDecors" in daemon
      and 'request("DECORS ' in launcher("WmClient.java")
      and "ensurePlatformCaptionsOff(display);" in caption_service
      and "wm.decors(d, false)" in caption_service,
      "WmDaemon DECORS + WmClient.decors + CaptionService.ensurePlatformCaptionsOff")

# ── 9c. a minimised window stays, and comes back ────────────────────────────
check("minimise: a task missing from the polled list is checked before it is forgotten",
      'case "HAS":' in daemon and "taskExists(" in daemon
      and "Boolean has(int taskId)" in launcher("WmClient.java")
      and "askGone(e.getKey());" in caption_service,
      "WmDaemon HAS + WmClient.has + CaptionService.askGone")

check("minimise: closing and minimising collapse the window first",
      "animateOut(task, closedRect(task)" in caption_service
      and "animateOut(task, minimisedRect(task)" in caption_service
      and "animating.add(id);" in caption_service,
      "CaptionService.animateOut")

# ── 9d. the home can never wear a caption ───────────────────────────────────
#
# "home a caption bar ekhono ache": the exclusion used to be the package name only,
# and the package comes from the task's topActivity — NULL while a task shows its
# starting window. The bottom-most root task of the desktop display is the home
# whatever the list says about it.
check("home: the display's base task is never given a caption",
      "the display's base task has no readable package" in caption_service
      and "int baseId = -1;" in caption_service,
      "CaptionService.applyTasks")

check("home: a home the platform will not un-float is replaced, not just asked",
      "homeUnfloatTries.merge(t.taskId, 1, Integer::sum)" in caption_service
      and "replacing the task with a fullscreen one" in caption_service,
      "CaptionService.pinHome")

# ── 9e. the task manager lists what is open, hidden or not ──────────────────
task_manager = launcher("TaskManagerActivity.java")
check("minimise: the task manager lists minimised windows too",
      "private LinkedHashSet<String> openPackages()" in task_manager
      and "CaptionService.openPkgs()" in task_manager
      and "for (String pkg : openPackages())" in task_manager
      and "if (!openPackages().equals(shownPkgs)) rebuildAppList();" in task_manager,
      "TaskManagerActivity.openPackages + tick")

# ── 9f. a report can say WHO draws a caption ────────────────────────────────
check("diagnostics: the accessibility windows on the desktop are in the report",
      "static String describeWindows(int display)" in caption_service
      and "CaptionService.describeWindows(desk)" in launcher("Diagnostics.java"),
      "CaptionService.describeWindows + Diagnostics")

# ── 9g. ONE caption inset, measured, never guessed ──────────────────────────
#
# AOSP draws a caption on every freeform task and nothing a third-party app can
# call removes it (CaptionWindowDecorViewModel.shouldShowWindowDecor returns
# true for WINDOWING_MODE_FREEFORM before it looks at anything else). The space
# it takes is published as the app-bounds inset, so the only correct bar is one
# exactly that tall, over the top of it — and the only correct reservation is
# none of our own.
wm_client = launcher("WmClient.java")
check("caption: the platform's own caption height is read from the task",
      "int platformCaption()" in wm_client
      and "int d = appTop - top;" in wm_client
      and "boolean hasPlatformCaption()" in wm_client,
      "WmClient.Task.platformCaption")

rule = launcher("CaptionRule.java")
check("caption: the rule is pure — no android, no service state — so it can be run",
      "import android" not in rule and "static Plan decide(int top, int appTop" in rule,
      "CaptionRule.decide")

check("caption: our bar is built to the measured height, never to a guess",
      "barHeights.put(t.taskId, plan.barPx);" in caption_service
      and "Integer bar = barHeights.get(taskId);" in caption_service
      and "if (bar != null && bar > 0) return bar;" in caption_service,
      "CaptionService.captionHeight follows CaptionRule.Plan")

check("caption: one inset only — the strip is made ONLY where the platform draws none",
      "if (measured > 0) {" in rule and "p.strip = !animating && now != ourPx;" in rule
      and "p.strip = now == 0 && !animating;" in rule
      and "if (plan.strip) {" in caption_service
      and "wm.post(() -> wm.unstrip(display, taskId));" in caption_service,
      "CaptionRule.decide + CaptionService reconcile")

# The trap this release was one commit away from shipping: an app-bounds override
# REPLACES the number WMS reports, so measuring a task we have stripped reads our
# own guess back as fact and makes a wrong height permanent.
check("caption: our own strip can never be read back as the platform's caption",
      "if (weStripped && now > 0) {" in rule
      and "ourStrips.contains(t.taskId)," in caption_service
      and "private final Set<Integer> ourStrips = new HashSet<>();" in caption_service,
      "CaptionRule weStripped")

# The geometry that produced "old on top, new underneath": our bar lives in the APP's
# surface, the platform's caption is a surface above it, so a bar hosted on the app
# window can only land below the platform's. On a captoned task that host is illegal.
check("caption: no second bar is ever stacked inside the app's own window",
      "if (task.hasPlatformCaption()) {" in caption_service
      and "hosting on its app window" in caption_service,
      "CaptionService.hostOnAppWindow")

check("caption: an override left by an older build is released once per session",
      "private void clearStripsOnce(List<WmClient.Task> live) {" in caption_service
      and "clearStripsOnce(live);" in caption_service
      and "platformCaptions.clear();" in caption_service,
      "CaptionService.clearStripsOnce")

# The fallback draws where the service cannot, and it must not fight it for one
# task's app bounds: it makes a strip where nothing is reserved and NEVER releases
# one, because the release bookkeeping is the service's.
check("caption: the fallback covers the platform's caption and never fights for the strip",
      "final int platformPx = Math.max(t.platformCaption()," in launcher("CaptionFallback.java")
      and "final int barPx = platformPx > 0 ? platformPx : px;" in launcher("CaptionFallback.java")
      and "wm.unstrip(" not in launcher("CaptionFallback.java"),
      "CaptionFallback.pass")

# The home is the one window the user has ruled out OUR bar on, which leaves only
# one way to get the platform's bar off it: stop it being a window at all.
check("caption: a freeform home is replaced, whichever way it is recognised",
      "final int baseId = live.isEmpty() ? -1 : live.get(live.size() - 1).taskId;" in caption_service
      and "if (!ours && t.taskId != baseId) continue;" in caption_service,
      "CaptionService.pinHome")

check("diagnostics: a report names the platform's caption height and ours per task",
      "static String describeDressing()" in caption_service
      and "CaptionService.describeDressing()" in launcher("Diagnostics.java")
      and ".append(\" caption=\").append(t.platformCaption())" in launcher("Diagnostics.java"),
      "describeDressing + appendTasks")

# ── 9h. the renderer is chosen by geometry, and the choice is tested ────────
fallback = launcher("CaptionFallback.java")
# The caption has TWO shapes and only one of them reserves space. A display that draws
# it as a window (One UI, everything before Android 14) publishes no app-bounds inset,
# so an inset-only test says "no caption" while the platform's ▢ ✕ are on screen.
check("caption: both shapes of the platform's caption are detected",
      "int platformCaptionPx) {" in rule
      and "if (platformCaptionPx > 0) {" in rule
      and "final int platformPx = Math.max(t.platformCaption(), hostCaptionPx);" in caption_service
      and "final int hostCaptionPx = host == null ? 0 : measureCaption(host);" in caption_service,
      "CaptionRule + CaptionService reconcile")

check("caption: the overlay renderer asks the service for the window-form caption",
      "static int platformCaptionHeight(int taskId)" in caption_service
      and "CaptionService.platformCaptionHeight(t.taskId))" in fallback,
      "CaptionService.platformCaptionHeight + CaptionFallback")

check("caption: a captoned window is drawn by the overlay, not by the service",
      "static int owner(int platformPx, boolean serviceHealthy, boolean stalled," in rule
      and "if (platformPx > 0) return OVERLAY;" in rule
      and "final int owner = CaptionRule.owner(platformPx, service," in fallback
      and "if (owner != CaptionRule.OVERLAY) {" in fallback,
      "CaptionRule.owner + CaptionFallback.pass")

check("caption: the service hands a captoned window over instead of drawing under it",
      "handing it to the overlay renderer" in caption_service
      and "if (platformPx > 0) {" in caption_service,
      "CaptionService reconcile")

check("caption: our overlay never floats over the window in front of it",
      "android.graphics.Rect.intersects(r, capRect)" in fallback
      and "private void raise(Bar b) {" in fallback,
      "CaptionFallback occlusion + focus raise")

check("caption: the desktop's base task wears no bar from either renderer",
      "if (t.taskId == baseId) continue;" in fallback,
      "CaptionFallback.pass")

# Evidence, so the next round is not another guess: the daemon runs as shell and
# can read dumpsys window, which names the owner of every title bar on the display.
check("diagnostics: dumpsys window is in the report, through the daemon",
      'case "WINDOWS": {' in read(ROOT / "openandroiddex-wmd/src/com/ccrstech/openandroiddex/wmd/WmDaemon.java")
      and "java.util.List<String> windowsDump()" in wm_client
      and "wm.windowsDump()" in launcher("Diagnostics.java"),
      "WmDaemon WINDOWS verb + WmClient.windowsDump + Diagnostics")

# The rule is tested by executing it, not by reading it: the test lives in the
# repo and tools/rule-test.sh runs it with nothing but a JDK.
rule_test = ROOT / "openandroiddex-launcher/app/src/test/java/com/ccrstech/openandroiddex/launcher/CaptionRuleTest.java"
check("caption: the rule's test exists and covers the override trap",
      rule_test.exists()
      and "our own strip is released, not believed" in read(rule_test)
      and "ALL RULE CASES PASS" in read(rule_test)
      and (ROOT / "tools/rule-test.sh").exists(),
      "CaptionRuleTest + tools/rule-test.sh")

# ── 10. versions agree everywhere ───────────────────────────────────────────
gradle = read(ROOT / "openandroiddex-launcher/app/build.gradle")
pkg = read(ROOT / "open-android-dex-tauri/package.json")
cargo = read(ROOT / "open-android-dex-tauri/src-tauri/Cargo.toml")
conf = read(ROOT / "open-android-dex-tauri/src-tauri/tauri.conf.json")
lock = read(ROOT / "open-android-dex-tauri/package-lock.json")
vm = re.search(r'versionName "([\d.]+)"', gradle).group(1)
vc = re.search(r"versionCode (\d+)", gradle).group(1)
versions = {
    "build.gradle": vm,
    "package.json": re.search(r'"version": "([\d.]+)"', pkg).group(1),
    "Cargo.toml": re.search(r'version = "([\d.]+)"', cargo).group(1),
    "tauri.conf.json": re.search(r'"version": "([\d.]+)"', conf).group(1),
    "package-lock.json": re.search(r'"version": "([\d.]+)"', lock).group(1),
}
check("version: all five files agree", len(set(versions.values())) == 1, str(versions))
check("version: versionCode tracks the name",
      vc == vm.replace(".", "")[-3:], f"{vm} / {vc}")

# ── report ─────────────────────────────────────────────────────────────────
width = max(len(n) for _, n, _ in results)
bad = 0
for ok, name, detail in results:
    mark = "PASS" if ok else "FAIL"
    if not ok:
        bad += 1
    print(f"{mark}  {name:<{width}}  {'' if ok else detail}")
print()
print(f"{len(results) - bad}/{len(results)} checks pass")
sys.exit(1 if bad else 0)
