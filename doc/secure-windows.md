# Secure windows, and typing

Two things a phone app does that a screen share cannot follow, and what Dex-X does
about each.

## Secure windows

An app that sets `FLAG_SECURE` on its window — a banking app, a password field, a
keyguard — is not drawn into a capture session. Mirror the phone's own screen and
those windows come out black. That is the "screen share" feel the desktop must not
have: the window is there, it is the app you asked for, and it is a black rectangle.

The fix is to stop capturing and start owning the display. Both halves of the product
already create their own virtual displays, and a virtual display can be asked for as
a **secure** one (`VIRTUAL_DISPLAY_FLAG_SECURE`, `1 << 2`): secure windows are then
composited into the surface we handed over, like any other window, because the surface
is the display's output rather than a recording of it.

Who asks:

* **The desktop display** — `openandroiddex-scrcpy/secure-display.patch`, applied by
  `build.mjs` after `display-scale.patch`. `NewDisplayCapture` adds the flag to that
  same flag word and, if the phone throws `SecurityException`, retries once without it
  and logs why: nothing else changes, secure windows stay blank.
* **A phone app's window** — `AppServer.createDisplay` in `openandroiddex-wmd`, with
  the same shape of fallback: drop the secure flag, keep the trusted one, and say so
  in the log once rather than on every window.

The permission is the catch. `VIRTUAL_DISPLAY_FLAG_SECURE` needs
`CAPTURE_SECURE_VIDEO_OUTPUT`, which is a signature permission: the shell holds it on
some builds and not on others, and no app-side work can conjure it. So the honest
statement is the one in the log and in `CHANGES.md` — the request is made, the phone
either allows it or it does not, and either way the desktop is never a black window
with no explanation.

## Typing

Typing on the computer is typing on a keyboard, so the display the typing goes to
gets no on-screen keyboard at all: `--display-ime-policy=hide`, passed for every
`--new-display` (see `scrcpy.rs`). Without it, Android puts the phone's IME on the
new display, and the first focused text field slides a phone-sized keyboard across
the bottom of the desktop — in the wrong place, at the wrong size, and on top of the
window being typed into.

With the policy set, key events from the computer still become text: the injected
events go through the same input path a hardware keyboard uses, which is how a text
field on a phone with a physical keyboard attached receives characters. `hideIme()`
in `AppServer` does the same for each app window's display (`setDisplayImePolicy`,
Android 11+), and needs the display to be trusted — which it is whenever the phone
lets the shell make one.
