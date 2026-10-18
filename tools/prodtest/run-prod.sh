#!/usr/bin/env bash
# Private validation: the release jar in a clean production-style client (Java 21, no dev environment).
# Usage: tools/prodtest/run-prod.sh <out-dir> [extra mod jars...]
# The candidate is verification/<rc>/release-candidate/cpextended-<version>.jar for the version in gradle.properties
# (RC_DIR overrides the directory).
# Three launches of one profile: prod1 (first launch), prod2 (relaunch: restored history), prod3 (damaged settings file).
# The acceptance-test mod (remapped to intermediary) and Fabric's published client-gametest module drive the client
# through real input; nothing from the development run configuration is used.
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$root"
out="${1:?out dir}"; shift
extras=("$@")
tag="base"; [ ${#extras[@]} -gt 0 ] && tag="extras"
P="$root/build/prodtest/$tag"
m2="$HOME/.gradle/caches/modules-2/files-2.1"
FAPI=$(ls $m2/net.fabricmc.fabric-api/fabric-api/0.141.6+1.21.11/*/fabric-api-0.141.6+1.21.11.jar | head -1)
CP=$(ls $m2/maven.modrinth/chatpatches/8.0.0+1.21.11-fabric/*/chatpatches-8.0.0+1.21.11-fabric.jar | head -1)
YACL=$(ls $m2/maven.modrinth/yacl/3.8.2+1.21.11-fabric/*/yacl-3.8.2+1.21.11-fabric.jar | head -1)
GT=$(ls $m2/net.fabricmc.fabric-api/fabric-client-gametest-api-v1/4.3.5+bfe9fcf43e/*/fabric-client-gametest-api-v1-4.3.5+bfe9fcf43e.jar | head -1)
version=$(sed -n 's/^mod_version=//p' gradle.properties)
rcdir="${RC_DIR:-$root/verification/rc2/release-candidate}"
RC="$rcdir/cpextended-$version.jar"
TEST="$root/build/prodtest-jars/cpextended-$version-gametest.jar"
(cd "$rcdir" && shasum -a 256 -c SHA256SUMS) || exit 1
rm -rf "$P"; mkdir -p "$(dirname "$P")"
python3 -I tools/prodtest/make_profile.py "$P" "$FAPI" "$CP" "$YACL" "$RC" "$GT" "$TEST" ${extras[@]+"${extras[@]}"} > "$P.profile.json" || exit 1
cat > "$P/game/options.txt" <<'EOF'
version:4671
onboardAccessibility:false
pauseOnLostFocus:false
narrator:0
lang:en_us
key_key.cpextended.open_config:key.keyboard.k
EOF
mkdir -p "$out/evidence" "$out/logs" "$out/screenshots"
for mode in prod1 prod2 prod3; do
  if [ "$mode" = prod3 ]; then printf '{ "schemaVersion": 1, "windows": [ broken' > "$P/game/config/cpextended.json"; fi
  echo "$mode" > "$P/game/cpx-mode.txt"
  JVM_OPTS="-Dfabric.client.gametest -Dfabric.client.gametest.disableNetworkSynchronizer=true -Dcpx.expectedVersion=$version" "$P/launch.sh" > "$P/$mode.log" 2>&1
  echo "$tag $mode exit=$? pass=$(grep -cE '\[CPX-M1A\].*"outcome":"PASS"' "$P/$mode.log") notpass=$(grep -cE '\[CPX-M1A\].*"outcome":"(FAIL|INCONCLUSIVE)"' "$P/$mode.log") errors=$(grep -c '"t":"error"' "$P/$mode.log")"
  cp "$P/game/cpx-evidence/$mode.jsonl" "$out/evidence/$tag-$mode.jsonl" 2>/dev/null
  sed -e "s#$HOME#~#g" "$P/$mode.log" | gzip -9 > "$out/logs/$tag-$mode.log.gz"
done
cp "$P"/game/screenshots/*.png "$out/screenshots/" 2>/dev/null
cp "$P.profile.json" "$out/evidence/$tag-profile.json"
