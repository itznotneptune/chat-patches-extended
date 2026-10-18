#!/usr/bin/env bash
# Milestone 1a acceptance run from a clean run directory, then evidence collection into verification/m1a/.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
run="build/run/clientGameTest"; logs="build/m1a-logs"; out="verification/m1a"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
rm -rf build/run "$logs" "$out"; mkdir -p "$run" "$logs" "$out/evidence" "$out/logs"

echo "== build"; ./gradlew build --console=plain > "$logs/build.log" 2>&1; echo "build exit=$?"
./gradlew dependencies --configuration compileClasspath --console=plain -q > "$logs/compileClasspath.txt" 2>&1

echo "== structural"
for m in structural_off structural_base structural_tabs; do
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
for m in restore clear faults conflict_bool conflict_int; do tools/run-mode.sh $m; done

echo "== V-35"; tools/run-v35.sh | tee "$out/evidence/v35-summary.txt"

echo "== collect"
cp "$run"/cpx-evidence/*.jsonl "$run"/cpx-evidence/*-mainchat.txt "$run"/cpx-evidence/*-chatlog.json "$out/evidence/" 2>/dev/null
cp "$logs/compileClasspath.txt" "$out/evidence/"
unzip -p build/libs/cpextended-*-m1a.jar fabric.mod.json > "$out/evidence/release-fabric.mod.json"
unzip -l build/libs/cpextended-*-m1a.jar | awk 'NR>3 {print $4}' | grep -v '^$' > "$out/evidence/release-jar-contents.txt"
for f in "$logs"/*.log; do
  sed -e "s#$HOME#~#g" "$f" | gzip -9 > "$out/logs/$(basename "$f").gz"
done
python3 -I tools/summarize_m1a.py "$out/evidence" > "$out/results-table.md"
echo "done"
