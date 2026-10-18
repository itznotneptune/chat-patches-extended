#!/usr/bin/env bash
# Phase 8 real-mod matrix: the compat suite once per mod combination, then a log scan for exceptions per run.
# Usage: tools/run-compat.sh <evidence-dir>
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
out="${1:-verification/phase8}"; run="build/run/clientGameTest"; logs="build/m1a-logs"
mkdir -p "$out/evidence"
scan="$out/evidence/compat-log-scan.txt"; : > "$scan"
for combo in none chatheads ncr modmenu chatheads,ncr,modmenu; do
  tag="${combo//,/+}"
  if [ "$combo" = none ]; then tools/run-mode.sh compat "cpextended.debug=true"; else tools/run-mode.sh compat "cpextended.debug=true" -Pextras=$combo; fi
  cp "$run/cpx-evidence/compat.jsonl" "$out/evidence/compat-$tag.jsonl" 2>/dev/null
  cp "$logs/compat.log" "$logs/compat-$tag.log"
  # Exceptions logged by any mod during the run (stack traces and ERROR lines), excluding the known harmless Realms notice
  n=$(grep -E "Exception|ERROR" "$logs/compat-$tag.log" | grep -v "Realms\|SignedJWT\|scenario failed" | grep -ci "cpextended\|chatpatches\|chat_heads\|chatheads\|nochatreports\|modmenu" || true)
  echo "compat-$tag: exception/error lines mentioning these mods: $n" >> "$scan"
  grep -E "Exception|ERROR" "$logs/compat-$tag.log" | grep -v "Realms\|SignedJWT\|scenario failed" | grep -i "cpextended\|chatpatches\|chat_heads\|chatheads\|nochatreports\|modmenu" | head -5 | sed "s#$HOME#~#g" >> "$scan"
done
cat "$scan"
