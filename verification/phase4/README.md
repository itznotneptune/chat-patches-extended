# Phase 4 verification (production code): configuration GUI, keybind, Mod Menu, tab relocation

Date: 2026-10-18. Build: `cpextended-0.4.0-phase4` (branch `phase4`). One clean run of `tools/run-all.sh` from an empty run directory: every Milestone 1a/1b and Phase 2–4 mode, a launch with Mod Menu 17.0.1 (+ Text Placeholder API 2.8.2) and No Chat Reports 2.18.0 (`-Pextras=modmenu,ncr`), and the metadata check.

**Environment:** macOS 26.5.1 (aarch64); Gradle 9.7.1 on Temurin JDK 25.0.4; game on Temurin JDK 21.0.8 (854×480 window; the `scales` mode resizes it to 1280×960). Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11 (dev runtime), Chat Patches 8.0.0, YACL 3.8.2. Synthetic chat only.

## Outcome: 123 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

The full regression passes on the Phase 4 build: the 101 checks up to Phase 3, including the structural main-chat/`chatlog.json` comparison. No mixin changes since Phase 3. Declared Fabric API modules match actual use exactly (V-64).

### Configuration GUI (`gui.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| V-21b (screen open) | PASS | A hand edit is detected by content when the config screen opens and applied; the file isn't rewritten. |
| Create window and tab | PASS | Add window, Add tab, name and filter typed with the real keyboard, Save; the new tab routes the next message. |
| V-21 | PASS | Empty pattern: inline error, Save blocked. Typing stops at 1,024 units. Pasting 515 emoji (1,030 units) gives 1,024 units with no split pair (through `EditBox.insertText`, the method vanilla's paste calls). A saved filter applies to later messages only (Q9). |
| V-21d (GUI) | PASS | Stored regex filter shown read-only; editing the stored invalid (empty) filter while leaving it invalid is blocked; an unrelated rename saves and the untouched invalid filters, including the regex one's extra `note` key, are written back unchanged, the tab staying suspended; a new empty filter is blocked; deleting and fixing save and unsuspend the tab. |
| V-25d (GUI) | PASS | The field accepts exactly what's valid by `String.length() ≤ 1024`; over-limit input arrives truncated at a pair boundary (1,023 / 1,024 units) and is valid. Same verdicts as the file. |
| V-25e | PASS | A 1,500-unit stored pattern is shown read-only, never in a field, with "too long" and a Replace action. (a) An unrelated save keeps the same 1,500 units. (c) Replace left empty: Save blocked with an inline error. (b) Replace with a valid pattern: saved, tab unsuspended. An over-length replacement can't be entered (the field stops at 1,024). |
| V-25f (GUI) | PASS | Stored `§a` and `x\uD83Dy` are shown exactly; typing `!` after the lone surrogate saves `x\uD83Dy!`, with the other value untouched; a typed `§` is filtered by vanilla (documented). |
| V-41 | PASS | Config-screen moves: (1) into a populated visible window: unselected, unread and history kept; (2) into an empty visible window: selected, unread cleared; (3) into a disabled window: unread kept; (4) into an empty window under F1 with chat closed: kept, cleared once visible. A window emptied by a move stays (Q30e). |
| Confirmations vs orphan safety | PASS | Delete tab / Delete window behind confirmation dialogs; with hidden messages simulated, "Yes" is still refused with the orphan reason and nothing changes; "No" changes nothing; an empty window deletes. |
| V-60 (GUI) | PASS | Normal: no recovery actions. Divergence: banner and actions; Apply refused while the file is broken (lock stays); Overwrite "No" keeps the lock, "Yes" clears it; an orphaning hand edit's Apply is refused. No-save: Restore from backup and Replace file each work after confirmation. |
| V-14 (Mod Menu absent) | PASS | Mod Menu isn't loaded and its API classes aren't on the classpath; everything above ran without it. |

### Tab relocation and overflow (`drag.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| Strip overflow | PASS | 12 tabs in a narrow window: 2 visible, arrows shown; every tab is reached through the arrows; the wheel over the strip scrolls; selecting a scrolled-out tab brings it into view; arrows turn red or yellow for scrolled-out suspended or unread tabs. |
| V-41e | PASS | (1) drop on another window's topmost unobstructed strip: moved, history, unread and configuration unchanged, moved tab unselected; (2) on the source window: no-op; (3) on the input box, or the part of a strip under main-chat lines: cancelled; (4) on another window's body, or a strip obscured by another window: cancelled; (5) empty space (over a disabled window's area, bottom edge, right edge, bottom-right area): a new enabled on-screen window with the tab selected and unread cleared, history kept; (6) Esc and a forced screen change mid-drag: cancelled, tab stays. |
| V-41c | PASS | Covered strip parts refuse, the uncovered part of the same strip accepts, an obscured strip refuses; disabled windows aren't drawn and never accept. |
| V-41d | PASS | A new window is always created and fully on-screen, including over a disabled window's area. |
| V-09e (tab drag) | PASS | The tab drag keeps capture while crossing the input box and main chat; release over a protected region cancels. |
| No lost or duplicated tab | PASS | 16 tabs before and after every drop, each once. |

### Keybind, Mod Menu, scales, metadata

| Check | Outcome | Key result |
|---|---|---|
| V-38 | PASS (partial) | `key.cpextended.open_config` is unbound by default, in the custom category `cpextended:main` labelled "Chat Patches Extended" (K-A worked, no Misc fallback needed), and the only Extended key (no edit-mode key). The Controls screen opens. Once bound, it opens the config screen in a world; with chat open the key types into the input; on the pause and title screens it does nothing (A-3). **Not confirmed visually:** the category's position in the Controls list (the screenshot shows the top of the list). |
| V-14 (Mod Menu present) | PASS | With Mod Menu and No Chat Reports installed: Mod Menu's factory returns the same `ConfigScreen`, which opens; system and player chat route. |
| V-12 | PASS | 1280×960 window, GUI scales 1, 2, 3, 4 actually in effect: 0 window-text pixels over drawn protected UI, 0 input mismatches in 24 samples per scale, identical rectangles on both paths, 0 duplicate draws. |
| V-08 (input box) | PASS | A message with a hover event over its whole text, drawn under the vanilla input box: no Extended tooltip at any GUI scale. |
| V-64 | PASS (newer-version launch not run) | Built metadata: id, name, author `Neptune`, `LGPL-3.0-only`, unofficial/no-affiliation notice, no contact URLs, exact pins, Loader `>=0.19.5`, Mod Menu only suggested; declared Fabric API modules = modules imported (lifecycle, rendering, screen, key-binding), each lower-bounded; no Loader warnings about the mod. **Not run:** a launch with a newer Loader/API: no newer Fabric API exists for 1.21.11, and Loader 0.20.0 was published three days before testing and wasn't adopted. |

## Corrections made during Phase 4 (before the clean run)
- **Product:** the tab editor's "+ Include" button overlapped a settings toggle at small GUI sizes (layout fixed). Parse errors shown to players now say "not valid JSON at line N, column M" instead of Gson's internal hint. An edit can't rename a malformed `windows` value (refused instead).
- **Tests:** screens built on the client thread; config writes through the mod's own escaping writer; drag fixtures adjusted (a covering window over the strip's start; a new window overlapping later drops; the obscured-strip case re-establishing stacking); keybind typing sends the character event; scale-3 pixel mapping uses the true GUI scale (427 is rounded from 426.67).
