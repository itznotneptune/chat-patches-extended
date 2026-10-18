#!/usr/bin/env bash
# Seeds config/cpextended.json (and .bak) in the test run directory before a relaunch mode:
#   tools/seed-config.sh corrupt   -> unparseable primary + valid .bak   (mode config_corrupt, V-21c(a), V-21e(a))
#   tools/seed-config.sh newer     -> schemaVersion 99 primary           (mode config_newer, V-21c(b))
# Copies of the seeded bytes go to cpx-seed-*.bin so the test can prove the originals were left untouched.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"; cfg="$root/build/run/clientGameTest/config"
mkdir -p "$cfg"; rm -f "$cfg"/cpextended.json* "$cfg"/cpx-seed-*.bin
case "$1" in
  corrupt)
    printf '{"schemaVersion": 1, "windows": [ {"id": "w", "tabs": [ {"id": "x"' > "$cfg/cpextended.json"
    cat > "$cfg/cpextended.json.bak" <<'JSON'
{
  "schemaVersion": 1,
  "general": {"enabled": true, "hidingEnabled": true},
  "appearance": {"backgroundOpacity": null, "textOpacity": null, "scale": null, "lineSpacing": null},
  "windows": [{"id": "bak-w", "tabs": [{"id": "bak-tab", "name": "From backup", "include": [{"mode": "literal", "pattern": "cpx#BAK"}]}]}]
}
JSON
    cp "$cfg/cpextended.json.bak" "$cfg/cpx-seed-bak.bin" ;;
  newer)
    cat > "$cfg/cpextended.json" <<'JSON'
{
  "schemaVersion": 99,
  "futureField": {"feature": "from a newer version"},
  "general": {"enabled": true, "hidingEnabled": true},
  "windows": [{"id": "new-w", "futureWindowKey": 1, "tabs": [{"id": "new-tab", "include": [{"mode": "literal", "pattern": "cpx#NEW"}]}]}]
}
JSON
    ;;
  *) echo "unknown case $1" >&2; exit 2 ;;
esac
cp "$cfg/cpextended.json" "$cfg/cpx-seed-primary.bin"
