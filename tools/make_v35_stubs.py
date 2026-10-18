"""Builds metadata-only "chatpatches" jars for V-35 (dependency pins). Output: build/stubs/chatpatches-<name>.jar.
Each jar holds only a fabric.mod.json, so the only thing exercised is Fabric Loader's dependency resolution."""
import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build" / "stubs"
VARIANTS = {
    "stub801": {"version": "8.0.1", "minecraft": "=1.21.11"},
    "stub720": {"version": "7.2.0", "minecraft": "=1.21.11"},
    # An 8.0.0 build for a different Minecraft version: satisfies the pin by version string, rejected by its own dependency.
    "stub800mc": {"version": "8.0.0", "minecraft": "=1.21.10"},
    # Phase 8 edges: a pre-release of 8.0.0 (lower than 8.0.0 in SemVer) must be refused; build metadata is ignored by
    # SemVer, so 8.0.0+<build> satisfies the pin: that one starts the game without any Chat Patches classes (V-36 stub).
    "stub800pre": {"version": "8.0.0-beta.1", "minecraft": "=1.21.11"},
    "stub800build": {"version": "8.0.0+stub.1", "minecraft": "=1.21.11"},
}

OUT.mkdir(parents=True, exist_ok=True)
for name, v in VARIANTS.items():
    meta = {"schemaVersion": 1, "id": "chatpatches", "version": v["version"], "name": "Chat Patches (V-35 stub)",
            "environment": "client", "depends": {"minecraft": v["minecraft"]}}
    with zipfile.ZipFile(OUT / f"chatpatches-{name}.jar", "w") as z:
        z.writestr("fabric.mod.json", json.dumps(meta, indent=2))
    print(OUT / f"chatpatches-{name}.jar")
