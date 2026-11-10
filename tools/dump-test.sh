#!/usr/bin/env bash
# Compile the daemon's dumpsys caption parser together with its test and RUN it.
#
# CaptionDump is pure Java with no Android dependency on purpose, so this needs nothing
# but a JDK — no Gradle, no SDK, no phone. It decides how tall our title bar is drawn,
# so it is checked against real dumpsys text instead of trusted.
#
#     bash tools/dump-test.sh
#
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
src="$here/openandroiddex-wmd/src/com/ccrstech/openandroiddex/wmd"
test="$here/openandroiddex-wmd/test/com/ccrstech/openandroiddex/wmd"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -encoding UTF-8 -d "$out" "$src/CaptionDump.java" "$test/CaptionDumpTest.java"
java -cp "$out" com.ccrstech.openandroiddex.wmd.CaptionDumpTest
