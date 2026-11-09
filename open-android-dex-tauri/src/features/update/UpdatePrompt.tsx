import { useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { openUrl } from "@tauri-apps/plugin-opener";
import { error as logError } from "@tauri-apps/plugin-log";
import { Dialog } from "../../ui/kit";

/**
 * "There is a newer Dex-X" — the same prompt the phone shows, on the computer.
 *
 * The Windows app is a sideloaded exe: nothing updates it, and nothing tells
 * its owner that a build exists they have not got. This asks the repository's
 * latest release what version it is, once a day at most, and says so once per
 * version — a dialog, with a link to the release page where the new exe and the
 * new apk are. It never downloads or installs anything itself: an exe that
 * quietly replaces itself is not something a person should have to trust.
 *
 * The fetch is the webview's own: api.github.com answers with
 * `Access-Control-Allow-Origin: *`, the app's CSP is unset, and a request from
 * here costs one round trip on a screen that is already talking to adb. Six
 * seconds and no retry; offline says nothing at all.
 */

const API = "https://api.github.com/repos/Rabbihossainltd/Dex-x/releases/latest";
const PAGE = "https://github.com/Rabbihossainltd/Dex-x/releases/latest";

/** When the network was last asked — one ask a day, whatever the answer. */
const KEY_ASKED = "dex-x.update.asked";
/** The version the person has already been told about, so it is said once. */
const KEY_TOLD = "dex-x.update.told";

const ASK_EVERY_MS = 24 * 60 * 60 * 1000;
const TIMEOUT_MS = 6000;

type Release = { version: string; url: string; name: string };

/** What a check found. `null` while one is in flight. */
export type CheckResult =
  | { kind: "newer"; version: string; url: string; name: string }
  | { kind: "current"; version: string }
  | { kind: "failed"; why: string };

/**
 * Ask the repository what its newest release is, once, and say what came back.
 *
 * The same request the prompt makes, with the two guards the prompt needs
 * (once a day, once per version) deliberately left to the prompt: a check the
 * person asked for must answer every time, including "you are up to date" and
 * "could not reach it" — the silence that keeps a background check quiet is
 * exactly what makes a manual one look broken.
 */
export async function checkNow(): Promise<CheckResult> {
  const mine = await getVersion().catch(() => "");
  if (!mine) return { kind: "failed", why: "this build has no version" };
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(API, {
      headers: { Accept: "application/vnd.github+json" },
      signal: controller.signal,
    });
    if (!res.ok) return { kind: "failed", why: `GitHub answered ${res.status}` };
    const json = (await res.json()) as { tag_name?: string; html_url?: string; name?: string };
    const tag = json.tag_name ?? "";
    const latest = tag.startsWith("v") ? tag.slice(1) : tag;
    if (!latest) return { kind: "failed", why: "the release has no version" };
    const url = (json.html_url ?? "").startsWith("https://") ? json.html_url! : PAGE;
    if (compare(latest, mine) > 0) return { kind: "newer", version: latest, url, name: json.name ?? "" };
    return { kind: "current", version: mine };
  } catch (e) {
    return { kind: "failed", why: String(e) };
  } finally {
    window.clearTimeout(timer);
  }
}

export function UpdatePrompt() {
  const [release, setRelease] = useState<Release | null>(null);
  const [current, setCurrent] = useState("");

  useEffect(() => {
    let live = true;
    const asked = Number(localStorage.getItem(KEY_ASKED) ?? 0);
    if (Date.now() - asked < ASK_EVERY_MS) return;
    localStorage.setItem(KEY_ASKED, String(Date.now()));

    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), TIMEOUT_MS);
    (async () => {
      const mine = await getVersion().catch(() => "");
      const res = await fetch(API, {
        headers: { Accept: "application/vnd.github+json" },
        signal: controller.signal,
      });
      if (!res.ok) return;
      const json = (await res.json()) as { tag_name?: string; html_url?: string; name?: string };
      const tag = json.tag_name ?? "";
      const latest = tag.startsWith("v") ? tag.slice(1) : tag;
      if (!live || !latest || !mine) return;
      if (compare(latest, mine) <= 0) return;
      if (localStorage.getItem(KEY_TOLD) === latest) return;
      setCurrent(mine);
      setRelease({
        version: latest,
        url: (json.html_url ?? "").startsWith("https://") ? json.html_url! : PAGE,
        name: json.name ?? "",
      });
    })()
      .catch(() => {
        // Offline, rate-limited, GitHub moved: nothing to say, and nothing to retry.
      })
      .finally(() => window.clearTimeout(timer));
    return () => {
      live = false;
      controller.abort();
      window.clearTimeout(timer);
    };
  }, []);

  if (!release) return null;

  const done = () => {
    localStorage.setItem(KEY_TOLD, release.version);
    setRelease(null);
  };

  return (
    <Dialog
      open={!!release}
      title={`Dex-X ${release.version} is out`}
      onDismiss={done}
      actions={
        <>
          <button className="btn btn-secondary" onClick={done}>
            Not now
          </button>
          <button
            className="btn btn-primary"
            onClick={() => {
              openUrl(release.url).catch((e) => logError(`update: could not open the browser: ${e}`));
              done();
            }}
          >
            Open the release
          </button>
        </>
      }
    >
      <p className="body">
        You have {current || "an older build"}. The release page has the new Windows app and the new phone APK
        — both built from the same push.
      </p>
      {release.name && <p className="caption mt-2">{release.name}</p>}
      <p className="caption mt-2">
        Nothing is downloaded for you, and nothing is replaced: the update is two files, like the ones you
        installed.
      </p>
    </Dialog>
  );
}

// ── versions ───────────────────────────────────────────────────────────────

/** 0.7.10 is newer than 0.7.9, which a string compare gets wrong. */
function compare(a: string, b: string): number {
  const x = parts(a);
  const y = parts(b);
  for (let i = 0; i < 3; i++) {
    if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  }
  return 0;
}

function parts(v: string): number[] {
  const out = [0, 0, 0];
  const bits = v.split(".");
  for (let i = 0; i < 3 && i < bits.length; i++) {
    // "-rc1" and "+build" tails are not part of the number.
    const digits = /^\d+/.exec(bits[i]);
    out[i] = digits ? Number(digits[0]) : 0;
  }
  return out;
}
