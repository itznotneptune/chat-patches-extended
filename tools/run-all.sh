#!/usr/bin/env bash
# Acceptance run (Milestones 1a, 1b, Phases 2-7) from a clean run directory, then evidence collection.
# Usage: tools/run-all.sh [output-dir]   (default verification/phase7; earlier evidence directories are left as recorded)
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
run="build/run/clientGameTest"; logs="build/m1a-logs"; out="${1:-verification/phase7}"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
rm -rf build/run build/libs "$logs" "$out"; mkdir -p "$run" "$logs" "$out/evidence" "$out/logs"

echo "== build"; ./gradlew build --console=plain > "$logs/build.log" 2>&1; echo "build exit=$?"
./gradlew dependencies --configuration compileClasspath --console=plain -q > "$logs/compileClasspath.txt" 2>&1

echo "== structural"
for m in structural_off structural_base structural_tabs structural_hide; do
  rm -f "$run/logs/chatlog.json"
  case $m in structural_off) p="cpextended.dev.disable=true";; *) p="cpextended.none=1";; esac
  tools/run-mode.sh $m "$p"
done
python3 -I tools/compare_structural.py "$run/cpx-evidence" > "$out/evidence/structural-compare.json"; echo "compare exit=$?"

echo "== capture, restore, clear, faults, conflicts"
rm -f "$run/logs/chatlog.json"
tools/run-mode.sh live "cpextended.debug=true,mixin.debug.export=true"
cc=$(find "$run/.mixin.out" -path "*net/minecraft/client/gui/components/ChatComponent.class" | head -1)
[ -n "$cc" ] && cp "$cc" "$out/evidence/ChatComponent.mixin-export.class"
for m in restore clear faults conflict_bool conflict_int conflict_error matcher drift; do tools/run-mode.sh $m; done

echo "== phase 2: configuration and HUD windows"
tools/run-mode.sh config
tools/seed-config.sh corrupt && tools/run-mode.sh config_corrupt
tools/seed-config.sh newer && tools/run-mode.sh config_newer
tools/run-mode.sh hud

echo "== phase 3: chat-open interaction and unread"
tools/run-mode.sh interact
tools/run-mode.sh unread

echo "== phase 4: configuration GUI, keybind, Mod Menu, tab relocation"
for m in gui drag keys scales; do tools/run-mode.sh $m; done
tools/run-mode.sh extras "cpextended.debug=true" -Pextras=modmenu,ncr

echo "== phase 5: global search"
for m in search searchperf; do tools/run-mode.sh $m; done

echo "== phase 6: restored history in tabs"
# A fresh log: Chat Patches 8.0.0 skips saving while its log is at the cap with an unchanged count (ChatLog.serialize
# compares sizes), so the seed's last messages would never be written after the earlier modes filled the log.
rm -f "$run/logs/chatlog.json"
tools/run-mode.sh restore_seed
python3 -I tools/make_restore_fixture.py "$run/logs/chatlog.json" | tee "$out/evidence/restore-fixture.json"
cp "$run/logs/chatlog.json" "$out/evidence/restore6-input-chatlog.json"
tools/run-mode.sh restore6

echo "== phase 7 stage A: Q36 orphan-restoration transaction (synthetic hidden fixtures)"
tools/run-mode.sh q36
tools/run-mode.sh q36_boundary
for ph in before between after; do
  tools/run-mode.sh q36_kill_$ph   # halts the JVM inside the commit on purpose (non-zero exit expected)
  cp "$logs/q36_kill_$ph.log" "$logs/q36_kill_$ph.halted.log" 2>/dev/null
  tools/run-mode.sh q36_kill_check
  cp "$run/cpx-evidence/q36_kill_check.jsonl" "$out/evidence/q36_kill_check-$ph.jsonl"
  cp "$logs/q36_kill_check.log" "$logs/q36_kill_check-$ph.log"
done

echo "== phase 7 stage B: main-chat hiding, hidden retention, integration"
tools/run-mode.sh hide
tools/run-mode.sh hideperf
rm -f "$run/logs/chatlog.json"
tools/run-mode.sh hide_seed
tools/run-mode.sh hide_relaunch
python3 -I tools/make_restore_fixture.py "$run/logs/chatlog.json" > "$out/evidence/hide-relaunch2-fixture.json"
tools/run-mode.sh hide_relaunch2
tools/run-mode.sh hide_seed_nolog
tools/run-mode.sh hide_relaunch_nolog

echo "== V-35"; tools/run-v35.sh | tee "$out/evidence/v35-summary.txt"

echo "== collect"
cp "$run"/cpx-evidence/restore_seed-live.json "$run"/cpx-evidence/hide-seed.json "$out/evidence/" 2>/dev/null
rm -f "$run/cpx-evidence/q36_kill_check.jsonl"  # kept per kill point above
cp "$run"/cpx-evidence/*.jsonl "$run"/cpx-evidence/*-mainchat.txt "$run"/cpx-evidence/*-chatlog.json "$out/evidence/" 2>/dev/null
cp "$logs/compileClasspath.txt" "$out/evidence/"
mkdir -p "$out/evidence/screenshots" && cp "$run"/screenshots/*p2-hud-*.png "$run"/screenshots/*p3-*.png "$run"/screenshots/*p4-*.png "$run"/screenshots/*p5-*.png "$run"/screenshots/*p6-*.png "$out/evidence/screenshots/" 2>/dev/null
jar=$(ls build/libs/cpextended-*.jar | grep -v sources | head -1)
unzip -p "$jar" fabric.mod.json > "$out/evidence/release-fabric.mod.json"
unzip -l "$jar" | awk 'NR>3 {print $4}' | grep -v '^$' > "$out/evidence/release-jar-contents.txt"
for f in "$logs"/*.log; do
  sed -e "s#$HOME#~#g" "$f" | gzip -9 > "$out/logs/$(basename "$f").gz"
done
python3 -I tools/check_metadata.py "$jar" src/main/java "$logs"/live.log "$logs"/gui.log "$logs"/extras.log > "$out/evidence/metadata-check.json"
python3 -I tools/summarize_m1a.py "$out/evidence" > "$out/results-table.md"
echo "done"
