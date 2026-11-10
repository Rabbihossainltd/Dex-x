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
