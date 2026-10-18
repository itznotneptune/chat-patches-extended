"""Summarizes Milestone 1a evidence (*.jsonl results plus the structural comparison) as a markdown table."""
import json
import pathlib
import sys

d = pathlib.Path(sys.argv[1])
rows = []
for f in sorted(d.glob("*.jsonl")):
    for line in f.read_text(encoding="utf-8").splitlines():
        e = json.loads(line)
        if e.get("t") == "result":
            det = {k: v for k, v in e.items() if k not in ("t", "mode", "test", "outcome")}
            rows.append((e["mode"], e["test"], e["outcome"], json.dumps(det, ensure_ascii=False)))
        elif e.get("t") == "error":
            rows.append((f.stem, "ERROR", "FAIL", json.dumps(e, ensure_ascii=False)))
cmp_path = d / "structural-compare.json"
if cmp_path.exists():
    c = json.loads(cmp_path.read_text())
    rows.append(("structural_*", c["test"], c["outcome"], json.dumps(c["details"])))
v35 = d / "v35-summary.txt"
if v35.exists():
    for line in v35.read_text().splitlines():
        if line.startswith("v35-"):
            name, rest = line.split(":", 1)
            refused = "refused=1" in rest and "cpextendedOutput=0" in rest
            rows.append(("v35", "V-35." + name[4:], "PASS" if refused else "FAIL", rest.strip()))
print("| Mode | Test | Outcome | Details |")
print("|---|---|---|---|")
for mode, test, outcome, det in rows:
    det = det.replace("|", "\\|")
    if len(det) > 700:
        det = det[:700] + "…"
    print(f"| {mode} | {test} | **{outcome}** | `{det}` |")
