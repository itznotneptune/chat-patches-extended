"""Summarize spike evidence (*.jsonl) into a markdown pass/fail table. Usage: summarize.py <evidence-dir> [<evidence-dir> ...]"""
import json
import pathlib
import sys

rows = []
for d in sys.argv[1:]:
    for f in sorted(pathlib.Path(d).glob("*.jsonl")):
        for line in f.read_text().splitlines():
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            if e.get("t") == "result":
                details = {k: v for k, v in e.items() if k not in ("t", "test", "pass")}
                rows.append((f.stem, e["test"], "PASS" if e["pass"] else "FAIL", json.dumps(details, ensure_ascii=False)))
            elif e.get("t") == "error":
                rows.append((f.stem, "ERROR", "FAIL", json.dumps(e, ensure_ascii=False)))

print("| Mode | Test | Result | Details |")
print("|---|---|---|---|")
for mode, test, res, det in rows:
    det = det.replace("|", "\\|")
    if len(det) > 900:
        det = det[:900] + "…"
    print(f"| {mode} | {test} | **{res}** | `{det}` |")
