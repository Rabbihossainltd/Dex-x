import type { ReactNode } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { IS_MAC, TITLEBAR_LEAD } from "../lib/host";
import { Icon } from "./Icon";

/**
 * One section, one window: connect, then the desktop.
 *
 * The Apps and Linux tabs are gone by request — this is a desktop app, and a
 * second way to open single apps in their own window (with its own tab, its own
 * pinning, its own panel) was a second product bolted to the first. Everything
 * they could do has a home on the desktop itself: the app drawer opens any app
 * in a window, and Linux is one of the drawer's own tools.
 */
export type Tab = "desktop";

/**
 * The window's top strip: the app's name, the switch between the things it
 * does, and the help menu.
 *
 * The caption buttons are ours on Windows and Linux and the system's on macOS,
 * where `titleBarStyle: "Overlay"` floats the real traffic lights over this
 * strip — so the name is padded clear of them and no second set of controls is
 * drawn. `deep` walks into the children, which keeps the whole band draggable;
 * buttons still short-circuit the walk, so everything in it stays clickable.
 */
export function TitleBar({
  menu,
  notifications,
  desktopBusy,
}: {
  menu: ReactNode;
  /** The phone's notifications' bell, beside the help menu: on every tab, since they are. */
  notifications?: ReactNode;
  /** A desktop is being set up: said on its tab, for someone looking at the other one. */
  desktopBusy?: boolean;
}) {
  const win = getCurrentWindow();
  return (
    <header data-tauri-drag-region="deep" className="titlebar">
      <div className={`pointer-events-none flex min-w-0 flex-1 items-center gap-2 ${TITLEBAR_LEAD}`}>
        <img src="/logo.png" alt="" width={16} height={16} className="shrink-0" draggable={false} />
        <span className="titlebar-name truncate">Dex-X</span>
      </div>
      {/* The section switch used to live here. With one section it would be a
          control that can only ever say "you are here". */}
      {desktopBusy && (
        <div className="segmented-nav flex items-center gap-2 px-3 text-[12px] text-[var(--ink-3)]">
          <span className="dot-pulse" />
          Opening the desktop…
        </div>
      )}
      <div className={`flex flex-1 items-center justify-end gap-1 ${IS_MAC ? "pr-2.5" : ""}`}>
        {notifications}
        {menu}
        {!IS_MAC && (
          <div className="ml-1 flex self-stretch">
            <button className="caption-btn" onClick={() => win.minimize()} title="Minimize" aria-label="Minimize">
              <Icon name="minus" size={14} strokeWidth={1.3} />
            </button>
            <button className="caption-btn close" onClick={() => win.close()} title="Close" aria-label="Close">
              <Icon name="x" size={14} strokeWidth={1.3} />
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
