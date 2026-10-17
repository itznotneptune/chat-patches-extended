"""V-35: metadata-only 'chatpatches' jars with versions that must be rejected by the =8.0.0 pin."""
import json, sys, zipfile, pathlib
out = pathlib.Path(sys.argv[1])
for name, ver in (("stub801", "8.0.1"), ("stub7", "7.2.0")):
    with zipfile.ZipFile(out / f"chatpatches-{name}.jar", "w") as z:
        z.writestr("fabric.mod.json", json.dumps({"schemaVersion": 1, "id": "chatpatches", "version": ver,
                                                   "name": "Chat Patches (stub for V-35)", "environment": "client"}))
print("ok")
