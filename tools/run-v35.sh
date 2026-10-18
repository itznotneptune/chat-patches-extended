#!/usr/bin/env bash
# V-35: launches with Chat Patches absent or a wrong version. Fabric Loader must refuse before any mod initializes.
# The refusal opens a blocking error dialog, so each launch is stopped once the refusal is in the log.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"; logs="$root/build/m1a-logs"; mkdir -p "$logs"
python3 -I "$root/tools/make_v35_stubs.py" > /dev/null
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
for v in none stub801 stub720 stub800mc; do
  log="$logs/v35-$v.log"
  ( "$root/gradlew" -p "$root" runClientGameTest --console=plain -PcpVariant=$v > "$log" 2>&1 ) &
  g=$!
  for i in $(seq 1 180); do grep -q "Incompatible mods found\|BUILD SUCCESSFUL\|BUILD FAILED" "$log" && break; sleep 1; done
  sleep 2; pkill -f "fabric.dli" ; wait $g
  reason=$(grep -m1 -E "requires version|requires .* of|is missing|wrong version" "$log" | sed 's/^[[:space:]]*//')
  started=$(grep -c "Chat Patches Extended\] \|\[diag\]\|adapter_selfcheck" "$log")
  echo "v35-$v: refused=$(grep -c 'Incompatible mods found' "$log") cpextendedOutput=$started reason=${reason:-none}"
done
pgrep -fl "fabric.dli" > /dev/null && echo "WARNING: game JVM still running" || echo "no stray game JVM"
