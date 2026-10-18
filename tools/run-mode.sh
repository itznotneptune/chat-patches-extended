#!/usr/bin/env bash
# Runs one Milestone 1a acceptance mode: tools/run-mode.sh <mode> [sysprops] [extra gradle args...]
# Gradle needs JDK 25 (Loom 1.18); the game itself runs on the JDK 21 toolchain.
set -u
mode="$1"; props="${2:-cpextended.debug=true}"; shift; [ $# -gt 0 ] && shift
root="$(cd "$(dirname "$0")/.." && pwd)"
run="$root/build/run/clientGameTest"; logs="$root/build/m1a-logs"
mkdir -p "$run" "$logs"
echo "$mode" > "$run/cpx-mode.txt"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
"$root/gradlew" -p "$root" runClientGameTest --console=plain "-PcpxSysProps=$props" "$@" > "$logs/$mode.log" 2>&1
code=$?
echo "$mode exit=$code $(grep -cE '\[CPX-M1A\].*"outcome":"PASS"' "$logs/$mode.log") pass, $(grep -cE '\[CPX-M1A\].*"outcome":"(FAIL|INCONCLUSIVE)"' "$logs/$mode.log") not-pass, $(grep -c '"t":"error"' "$logs/$mode.log") errors"
exit $code
