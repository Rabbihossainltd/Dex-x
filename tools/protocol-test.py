#!/usr/bin/env python3
"""The wire between the phone and its daemon, checked for drift.

WHY. `Wm.TaskRec.line()` writes a task as one line of text and `WmClient` reads it
back by index — two hand-written field lists, in two languages' worth of source, in
two directories that nothing compares. Insert a field on one side and the other
keeps working: it ignores the extra token, or reads the next field's number as its
own. The desktop then shows a window at the wrong place, or a resize does nothing,
and nothing in the build complains. This project has already spent four releases on
bugs of exactly that shape — a rule written twice, drifting apart.

WHAT IT DOES. Reads both sources and compares:
  1. the daemon's task fields, in the order `TaskRec.line()` writes them;
  2. the client's `p[n]` indices, in the order `Task.parse` reads them;
  3. the verbs the client sends against the verbs the daemon answers.

Nothing here needs a phone, an SDK or Gradle: it is text against text, and it fails
loudly when the two sides stop agreeing.

    python3 tools/protocol-test.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WMD = ROOT / "openandroiddex-wmd/src/com/ccrstech/openandroiddex/wmd"
LAUNCHER = ROOT / "openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher"

fails = []


def check(what, ok, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (("   " + detail) if (detail and not ok) else ""))
    if not ok:
        fails.append(what)


def read(p):
    return p.read_text(encoding="utf-8", errors="surrogateescape")


# ── 1. what the daemon writes ────────────────────────────────────────────────
wm = read(WMD / "Wm.java")

line_body = re.search(
    r'String line\(int index\)\s*\{\s*return\s*(.*?);\s*\}', wm, re.S)
if not line_body:
    print("FAIL cannot find TaskRec.line() in Wm.java")
    sys.exit(1)
body = line_body.group(1)

# The concatenation is "TASK " + index + " " + taskId + ... — so split it at the plus
# signs, throw away the string literals, and every remaining expression is one field.
# Dotted expressions are the rect parts (`bounds.left`), and the ternary is `visible`;
# both are mapped to the name the client uses, which is the point of the comparison.
RENAMES = {
    "bounds.left": "left", "bounds.top": "top",
    "bounds.right": "right", "bounds.bottom": "bottom",
    "appBounds.left": "appLeft", "appBounds.top": "appTop",
    "appBounds.right": "appRight", "appBounds.bottom": "appBottom",
    "(visible ? 1 : 0)": "visible",
}


def fields_of(expr):
    out = []
    depth = 0
    part = ""
    for ch in expr:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "+" and depth == 0:
            out.append(part)
            part = ""
        else:
            part += ch
    out.append(part)
    names = []
    for raw in out:
        t = raw.strip()
        if not t or t.startswith('"'):        # a literal separator, or "TASK "
            continue
        t = re.sub(r"\s+", " ", t)
        names.append(RENAMES.get(t, t))
    return names


written = fields_of(body)
check("the daemon's task line starts with the TASK marker", '"TASK "' in body, body[:60])
check("the daemon writes the sixteen fields the client expects", len(written) == 16,
      f"found {len(written)}: {written}")

# ── 2. what the client reads ─────────────────────────────────────────────────
client = read(LAUNCHER / "WmClient.java")

min_len = re.search(r'if \(p\.length < (\d+) \|\| !"TASK"\.equals\(p\[0\]\)\)', client)
if not min_len:
    print("FAIL cannot find the client's TASK length test in WmClient.java")
    sys.exit(1)
min_tokens = int(min_len.group(1))

# Every `t.field = ... p[n] ...` line, in source order, with the index it reads.
reads = re.findall(
    r'\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"?[^;\n]*?p\[(\d+)\]', client)
# only the ones inside the TASK branch, which is the block we found above
task_start = client.index('"TASK".equals(p[0])')
task_block = client[task_start:task_start + 1600]
reads = re.findall(r't\.([A-Za-z_][A-Za-z0-9_]*)\s*=[^;\n]*p\[(\d+)\]', task_block)

check("the client's minimum token count matches the daemon's field count",
      min_tokens == len(written) + 1,
      f"client wants >= {min_tokens} tokens, daemon writes {len(written)} fields (+1 marker)")

# Field-by-field: for each name the client reads, the daemon must write it at `index - 1`.
mismatches = []
for name, idx in reads:
    i = int(idx) - 1                      # p[1] is the first field, token 0 is "TASK"
    if i < 0 or i >= len(written) or written[i] != name:
        mismatches.append(f"client reads {name} from p[{idx}], daemon writes "
                          f"{written[i] if 0 <= i < len(written) else 'nothing'} there")
check("every field the client reads is the field the daemon wrote there",
      not mismatches, "; ".join(mismatches))

# And nothing the client reads at all is missing from the daemon's list.
unread = [w for w in written if w not in [n for n, _ in reads]]
check("the only fields the client ignores are ones it can ignore "
      "(activityType: the app never needs it)",
      unread == ["activityType"], str(unread))

# ── 3. verbs: what the client sends against what the daemon answers ──────────
daemon_src = read(WMD / "WmDaemon.java")
handled = set(re.findall(r'case "([A-Z_]+)"', daemon_src))

sent = set(re.findall(r'request\("([A-Z_]+)', client))
sent |= set(re.findall(r'request\("([A-Z_]+) ', client))
# commands built by concatenation ("STRIP " + …) still start with the verb literal
sent |= set(re.findall(r'out\.println\("([A-Z_]+) ', client))
sent |= set(re.findall(r'"([A-Z_]+) "', client))

# Only protocols the daemon's own switch answers; the apps channel has its own server.
known = {"LIST", "STRIP", "UNSTRIP", "MOVE", "BOUNDS", "RESIZE", "CLOSE", "CAPTION",
         "WINDOWS", "PROCS", "PING", "HAS", "DECORS", "HOME", "TOP", "FOCUS", "KEY",
         "ARMED", "DRAG", "UNFLOAT", "FLING", "ALIVE", "VERSION", "SCREEN", "FRONT",
         "FOCUSABLE", "ALWAYSONTOP", "ARM"}
unknown = sorted(v for v in sent & known if v not in handled)
check("every daemon verb the client sends is answered by the daemon",
      not unknown, "unanswered: " + ", ".join(unknown))

print()
if fails:
    print(f"{len(fails)} PROTOCOL CHECK(S) FAILED")
    sys.exit(1)
print("ALL PROTOCOL CHECKS PASS")
