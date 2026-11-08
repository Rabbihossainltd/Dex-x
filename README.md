<div align="center">

# Dex-X

### Your phone is already a computer. This gives it a desktop

Plug your Android phone into your Windows PC and a real desktop opens on your
screen — a taskbar, an app drawer, and windows you can drag, resize and snap
side by side. Your phone keeps its own screen the whole time: its apps, its
notifications, its home screen, exactly as it was.

[![Download](https://img.shields.io/badge/Download-latest%20build-2ea043?style=for-the-badge)](https://github.com/Rabbihossainltd/Dex-x/releases/latest)
[![Licence](https://img.shields.io/badge/Licence-GPL--3.0-blue?style=for-the-badge)](LICENSE)

</div>

---

## Get it

| File | What it is |
| --- | --- |
| `Dex-X-windows-x64-portable.zip` | The PC app. Unzip anywhere and run. The phone app is already inside it. |
| `Dex-X-windows-x64-setup.exe` | The same, as an installer. |
| `Dex-X-launcher.apk` | The phone half on its own, for a phone that never meets the PC. |

Builds are made by GitHub Actions from whatever is on `main` — open the
**Actions** tab, pick the newest green run, and the files are under *Artifacts*.

## What it does

- **A real desktop on your monitor.** Not your phone screen stretched out: a
  desktop at your monitor's size, with its own taskbar, sitting beside your
  other windows.
- **Proper windows.** Every app gets a title bar with minimise, maximise,
  resize and close. Drag them, resize them from a corner or an edge, drop one
  at the side of the screen and it fills that half.
- **Your phone's notifications** on the PC: banners, a bell with a count, the
  same buttons the phone would offer — including answering a call.
- **Files between the two.** Drop a file on the window and it lands in the
  phone's Downloads; an `.apk` installs itself.
- **Your phone stays your phone.** The desktop is its own virtual display, so
  the phone's own screen is untouched and usable at the same time.

## Requirements

| | |
| --- | --- |
| Your computer | Windows 10 or 11, 64-bit |
| Your phone | Any Android 8.0 or newer |
| Root | No |
| An account | No |
| Money | No |

## Building it yourself

```powershell
# Windows: installs Rust, Node, MSVC build tools, JDK 17 and the Android SDK
# if they are missing, then builds the phone app and the PC app.
powershell -ExecutionPolicy Bypass -File tools\build-windows.ps1
```

---

## Credit

Dex-X is a modified build of **[Open Android DeX](https://github.com/dotnetdreamer/open-android-dex)**
by **Idrees Khan** and its contributors, which is where the desktop, the window
daemon, the launcher and the whole architecture come from. That project is
GPL-3.0; so is this one, and it has to be — see `LICENSE` and `NOTICE.md`.

What is different here is listed, change by change, in `CHANGES.md`.

Nothing here is derived from any commercial product's source.
