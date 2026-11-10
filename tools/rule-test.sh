#!/usr/bin/env bash
# Compile the caption rule together with its test and RUN it.
#
# The rule it checks is pure Java with no Android dependency on purpose, so this needs
# nothing but a JDK — no Gradle, no SDK, no phone. Run from anywhere:
#
#     bash tools/rule-test.sh
#
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
src="$here/openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher"
test="$here/openandroiddex-launcher/app/src/test/java/com/ccrstech/openandroiddex/launcher"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -encoding UTF-8 -d "$out" "$src/CaptionRule.java" "$src/ProbeCache.java" \
    "$test/CaptionRuleTest.java" "$test/ProbeCacheTest.java"
java -cp "$out" com.ccrstech.openandroiddex.launcher.CaptionRuleTest
java -cp "$out" com.ccrstech.openandroiddex.launcher.ProbeCacheTest
