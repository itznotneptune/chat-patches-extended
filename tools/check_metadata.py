"""V-64: checks the built jar's fabric.mod.json against P15 and the Fabric API modules the code actually imports.
Usage: check_metadata.py <jar> <src-main-java-dir> <log>... ; prints a JSON result line."""
import json
import os
import subprocess
import tempfile
import pathlib
import re
import sys
import zipfile

jar, src, logs = sys.argv[1], pathlib.Path(sys.argv[2]), sys.argv[3:]
meta = json.loads(zipfile.ZipFile(jar).read("fabric.mod.json"))
checks = {}
checks["id"] = meta.get("id") == "cpextended"
checks["name"] = meta.get("name") == "Chat Patches Extended"
checks["authorNeptune"] = meta.get("authors") == ["Neptune"]
checks["licenseLGPL3only"] = meta.get("license") == "LGPL-3.0-only"
desc = meta.get("description", "")
checks["unofficialNotice"] = "Unofficial" in desc and "Not affiliated" in desc
checks["contactAbsent"] = "contact" not in meta
dep = meta.get("depends", {})
checks["pins"] = dep.get("minecraft") == "=1.21.11" and dep.get("chatpatches") == "=8.0.0" and dep.get("java") == ">=21"
checks["loaderLowerBound"] = dep.get("fabricloader") == ">=0.19.5"
checks["modMenuSuggestedOnly"] = meta.get("suggests", {}).get("modmenu") == "*" and "modmenu" not in dep

# Fabric API modules used by imports vs declared
package_to_module = {
    "net.fabricmc.fabric.api.client.event.lifecycle": "fabric-lifecycle-events-v1",
    "net.fabricmc.fabric.api.event.lifecycle": "fabric-lifecycle-events-v1",
    "net.fabricmc.fabric.api.client.rendering": "fabric-rendering-v1",
    "net.fabricmc.fabric.api.client.screen": "fabric-screen-api-v1",
    "net.fabricmc.fabric.api.client.keybinding": "fabric-key-binding-api-v1",
}
used, unknown = set(), set()
for f in src.rglob("*.java"):
    for imp in re.findall(r"^import\s+(net\.fabricmc\.fabric\.api\.[\w.]+);", f.read_text(), re.M):
        mod = next((m for p, m in package_to_module.items() if imp.startswith(p)), None)
        (used.add(mod) if mod else unknown.add(imp))
declared = {k for k in dep if k.startswith("fabric-") and k != "fabric-api"}
checks["modulesExact"] = used == declared and not unknown
for m in declared:
    checks["lowerBound:" + m] = dep[m].startswith(">=")

warnings = []
for log in logs:
    for line in pathlib.Path(log).read_text(errors="replace").splitlines():
        if ("WARN" in line or "Warning" in line) and "FabricLoader" in line and "cpextended" in line:
            warnings.append(line.strip()[:200])
checks["noLoaderWarnings"] = not warnings

# V-46d (static): Extended never compiles or executes a regex. No class may reference java.util.regex, and none may
# call the String methods that run one internally (matches, replaceAll, replaceFirst, split).
regex_refs, regex_calls = [], []
javap = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javap")
with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(jar) as z:
    classes = [n for n in z.namelist() if n.endswith(".class")]
    for n in classes:
        data = z.read(n)
        if b"java/util/regex" in data:
            regex_refs.append(n)
        z.extract(n, tmp)
    out = subprocess.run([javap, "-c", "-p"] + [os.path.join(tmp, n) for n in classes], capture_output=True, text=True, check=True).stdout
    cls = None
    for line in out.splitlines():
        if line.startswith(("class ", "public ", "final ", "abstract ", "interface ")) and "{" in line:
            cls = line
        m = re.search(r"Method java/lang/String\.(matches|replaceAll|replaceFirst|split):", line)
        if m:
            regex_calls.append((cls or "?")[:120] + " -> " + m.group(1))
print(json.dumps({"test": "V-46d.static", "outcome": "PASS" if classes and not regex_refs and not regex_calls else "FAIL",
                  "classesScanned": len(classes), "regexReferences": regex_refs, "regexStringCalls": regex_calls}))
print(json.dumps({"test": "V-64", "outcome": "PASS" if all(checks.values()) else "FAIL", "checks": checks,
                  "declaredModules": sorted(declared), "usedModules": sorted(used), "unmappedImports": sorted(unknown),
                  "loaderWarnings": warnings,
                  "notRun": "launch with a newer Loader/API: no newer Fabric API exists for 1.21.11; Loader 0.20.0 was published 3 days before testing and wasn't adopted"}))
