"""Compares main chat and chatlog.json across the structural runs (Extended inert vs. installed vs. routing).
Usage: compare_structural.py <evidence-dir>. Masks only what legitimately differs between launches: wall-clock
times (Chat Patches timestamps and their epoch-millisecond insertion) and the world-creation date in boundary lines."""
import json
import pathlib
import re
import sys

d = pathlib.Path(sys.argv[1])
MODES = ["structural_off", "structural_base", "structural_tabs"]


def mask(s: str) -> str:
    s = re.sub(r'"insertion":\s*"\d+"', '"insertion": "<ms>"', s)
    s = re.sub(r"\d{1,2}:\d{2}(:\d{2})?", "HH:MM:SS", s)
    s = re.sub(r"\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{2,4}", "DATE", s)
    s = re.sub(r'"(timestamp|time|date)"\s*:\s*-?\d+', r'"\1":<n>', s)
    return s


def load(mode):
    main = (d / f"{mode}-mainchat.txt").read_text(encoding="utf-8").splitlines()
    log_path = d / f"{mode}-chatlog.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else None
    return [mask(x) for x in main], mask(json.dumps(log, sort_keys=True, ensure_ascii=False)) if log is not None else None


data = {m: load(m) for m in MODES}
base_main, base_log = data["structural_off"]
ok = True
report = {}
for m in MODES[1:]:
    main, log = data[m]
    same_main = main == base_main
    same_log = log == base_log
    ok &= same_main and same_log and base_log is not None
    first_diff = next((i for i, (a, b) in enumerate(zip(base_main, main)) if a != b), None)
    report[m] = {"mainChatEntries": len(main), "mainChatIdentical": same_main, "firstMainDiff": first_diff,
                 "chatlogIdentical": same_log, "chatlogBytes": len(log or "")}
report["structural_off"] = {"mainChatEntries": len(base_main), "chatlogBytes": len(base_log or "")}
print(json.dumps({"test": "structural.mainChatAndChatlogUnchanged", "outcome": "PASS" if ok else "FAIL", "details": report}))

# Hiding-on baseline (Phase 7), separate from the no-hiding comparison: the main chat equals the hiding-off main chat
# minus exactly the hidden messages (cpx#SM-3, cpx#SM-5), in the same order; Chat Patches' log is identical (hidden
# messages are still logged, D2).
if (d / "structural_hide-mainchat.txt").exists():
    hmain, hlog = load("structural_hide")
    hidden = ("cpx#SM-3", "cpx#SM-5")
    expected = [x for x in base_main if not any(h in x for h in hidden)]
    removed = [x for x in base_main if any(h in x for h in hidden)]
    hok = hmain == expected and hlog == base_log and len(removed) == 2
    print(json.dumps({"test": "structural.hidingOnBaseline", "outcome": "PASS" if hok else "FAIL",
                      "details": {"mainChatEntries": len(hmain), "expectedEntries": len(expected), "mainChatIsBaselineMinusHidden": hmain == expected,
                                  "hiddenRemovedFromMainChat": len(removed), "chatlogIdentical": hlog == base_log}}))
    ok &= hok
sys.exit(0 if ok else 1)
