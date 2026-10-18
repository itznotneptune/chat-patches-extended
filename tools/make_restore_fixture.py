"""Phase 6 restored-history fixture: appends structural edge cases to a real Chat Patches chatlog.json.

Usage: make_restore_fixture.py <chatlog.json>

The log itself comes from a real session (mode restore_seed), so player, team, system, plugin-formatted, translated,
dupe-counted and boundary entries are exactly as Chat Patches 8.0.0 saved them. This adds the shapes a real 8.0.0
session can't produce: entries as older Chat Patches versions or other tools could have left them (P2: each must be
AMBIGUOUS, never stripped by text heuristics). Synthetic text only; every entry carries a cpx#R-old token.
"""
import json
import sys

STAMP = {"text": "[10:00:00] ", "color": "#FF55FF", "insertion": "1700000000000"}
EDGE = [
    # plain string, no structure (pre-structure logs)
    "legacy plain cpx#R-old1",
    # timestamp and text inline in one literal: looks restored, but only text heuristics could strip it
    "[10:00:00] <Steve> old inline style cpx#R-old6",
    # empty root, two siblings (no dupe slot)
    {"text": "", "extra": [STAMP, "two siblings cpx#R-old2"]},
    # three siblings, timestamp insertion not digit-only
    {"text": "", "extra": [dict(STAMP, insertion="10:00"), "bad insertion cpx#R-old3", ""]},
    # three siblings, no insertion at all
    {"text": "", "extra": [{"text": "[10:00:00] "}, "no insertion cpx#R-old4", ""]},
    # root with its own text
    {"text": "root text cpx#R-old5 ", "extra": [STAMP, "middle", ""]},
    # four siblings
    {"text": "", "extra": [STAMP, "four siblings cpx#R-old7", "", " tail"]},
    # translatable root (not empty plain text)
    {"translate": "chat.type.announcement", "with": ["Server", "translatable root cpx#R-old8 "], "extra": [STAMP, "x", ""]},
]

path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    log = json.load(f)
log["messages"].extend(EDGE)  # newest at the end: restored last, just before the boundary line Chat Patches adds
with open(path, "w", encoding="utf-8") as f:
    json.dump(log, f, ensure_ascii=False)
print(json.dumps({"fixture": path, "messages": len(log["messages"]), "edgeCasesAdded": len(EDGE)}))
