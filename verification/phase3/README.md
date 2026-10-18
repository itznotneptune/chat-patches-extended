# Phase 3 verification (production code): chat-open interaction, rendering, stacking and unread

Date: 2026-10-18. Build: `cpextended-0.3.0-phase3` (branch `phase3`). One clean run of `tools/run-all.sh` from an empty run directory: every Milestone 1a/1b, Phase 2 and Phase 3 mode.

**Environment:** macOS 26.5.1 (aarch64); Gradle 9.7.1 on Temurin JDK 25.0.4; game on Temurin JDK 21.0.8, 854×480 window (GUI scale 2). Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11 (dev runtime), Chat Patches 8.0.0, YACL 3.8.2. Fabric client gametest in singleplayer. Synthetic chat only.

## Outcome: 101 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

The full regression passes on the Phase 3 build: the 74 checks up to Phase 2, including the structural main-chat/`chatlog.json` comparison. Two Phase 2 HUD checks were updated to the Phase 3 rule (exactly one path per frame; the chat-open path draws while chat is open).

### Rendering (`interact.jsonl`, `screenshots/`)

| Check | Outcome | Key result |
|---|---|---|
| V-39 | PASS | Gameplay: drawn; gameplay + F1: not drawn; chat open + F1: drawn and interactive (tab select works, Q26); Chat Visibility Hidden: not drawn in either state; disabled window: never drawn. |
| V-55 | PASS | 1,593 frames over 75 `setScreen` transitions (normal, F1, Hidden) and 5 keyboard open/close cycles (T / Esc): 0 frames with a missing or duplicate window, 0 duplicate draws across the two paths. |
| V-53 | PASS | Window p3-c under main-chat lines, input, command suggestions, Chat Patches search field, settings panel and context menu; checked pixel by pixel against the input precedence map. **0 window-text pixels over anything a protected element draws.** Click-only bands, where input goes to the protected element but window text is visible, are recorded: 6 px (search field clickable 2 rows below its painted background) and 699 px (context menu `isMouseOver` box larger than its buttons, as in Phase 0 F-5; all clicks pass while it's open). |
| V-54 | PASS | 48 scroll samples per scene × 5 scenes: the window consumed exactly where it's on top and unprotected; 0 mismatches. |
| V-07 | PASS | Hovering hover-event text in an unobstructed window draws the tooltip within the same frame (afterRender + `nextStratum` + `renderDeferredElements`, Phase 0 F-3); `p3-tooltip.png` shows Chat Patches' timestamp hover. |
| V-08 | PASS (partial) | No Extended tooltip for window text under main-chat lines or suggestions, or with the Chat Patches region read forced to fail (conservative). The input-box case found no hover text under the input in this layout (not exercised). Search field, settings and context menu coverage share the V-53/V-54 map. |
| V-56 | PASS | With a sidebar scoreboard overlapping a window in gameplay, the window draws above the scoreboard, identically in two consecutive frames (documented order). |
| V-12 | PASS (partial) | Identical clamped rectangles on both paths, no duplicate draws, and the file isn't modified by rendering. **GUI scales 1 and 2 only**: the 854×480 test window caps the GUI scale at 2, so the rows requested at 3 and 4 rendered at 2. |
| V-11 (live part) | PASS | A fresh line is drawn on the gameplay path and gone after 215 ticks (vanilla's 200-tick fade). The restored-line part is deferred to Phase 6 (restored history isn't routed yet). |

### Input (`interact.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| V-09 | PASS | With Chat Patches' context menu open, a click on a window's tab passes (no select); with menu detection forced to fail, clicks are treated as "menu open" and pass. |
| V-09b | PASS | Search button toggles the bar, the search field takes focus, the settings panel opens; the window never consumes. |
| V-09c | PASS | Tab click selects; right-click on the body is consumed (0 vanilla calls, no context menu); middle button passes (1 vanilla call); wheel scrolls the window; scrollbar click jumps; a `suggest_command` link runs vanilla's handler (input gets `/cpxlink`); input value, cursor and focus unchanged by the other interactions. |
| V-09d | PASS | Visible main-chat lines get scroll (the main chat scrolls), the input gets clicks, open suggestions get scroll; closed suggestions don't intercept. |
| V-09e | PASS (partial) | Resize from the grip ([239,10,179,108] → [239,10,159,98]); a move by the strip keeps capture across the main chat and input and clamps on-screen; no drag starts under a protected region or with right/middle; Esc mid-drag cancels and the committed geometry stays. **Tab drags (cases 1 and 4) are deferred** with tab relocation. |
| V-57 | PASS | Consumed left/right clicks, a scroll and a drag reach vanilla `ChatScreen` 0 times and open no context menu; passed events reach it once each. |
| V-58 | PASS | Foreign listener consuming in an earlier phase: Extended doesn't act (no error). Observing early: Extended works. Consuming in a later phase: Extended acts first. |
| V-28 | PASS | Chat closed: input over window positions isn't touched (listeners exist only on the chat screen). |

### Stacking and save locks (`interact.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| V-41b | PASS | Clicking the lower window brings it to the front; the overlap then goes to it; the order is restored after restart (saved with the geometry debounce); during a divergence lock the order changes in memory but the file is untouched. |
| V-60 / V-21c automatic save | PASS | During a divergence lock the strip shows the lock icon; a window move commits in memory, the debounced save is blocked, the file is untouched; the icon goes after Overwrite. |

### Unread (`unread.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| V-33 | PASS | Selected tab in a visible window: 0; unselected: 1; tab in a disabled window: 1; selected under F1 (chat closed): 1; selected under Chat Visibility Hidden: 1. |
| V-33b | PASS | Selecting a tab clears it (1 → 0). |
| V-33d | PASS | Flags clear when F1 is released, when Chat Visibility is shown, and when a disabled window is re-enabled. |
| V-33e | PASS | F1 with chat closed: flagged; opening chat under F1: drawn, interactive, cleared; closing again: new deliveries flagged. |
| V-33f / V-33c | PASS | `X` then `X` in an unselected tab: 1 retained entry, 1 unread (Chat Patches condenses; the earlier entry leaves with its flag); read then `X` again: 1. Restored history is never flagged by the delivery rule (restored routing is Phase 6). |
| V-33g | PASS | Deleting an unread entry (Chat Patches delete) lowers the count by 1; F3+D clears to 0; every tab's count equals its retained flagged entries. |
| Unread not persisted | PASS | No delivered message text, unread or history data in `cpextended.json`. |

## Deferred (not implemented, not verified)
- **Tab relocation between windows** (Q21 drag-and-drop, Q30b–e, Q33): V-41c, V-41d, V-41e, V-09e cases (1) and (4), and V-41 (Q25 moves).
- **Search:** P4.7 (search never touches flags) has nothing to act on until Phase 5.
- **Restored history in tabs:** V-11 restored part and the restored half of V-33c (Phase 6).

## Corrections made during Phase 3 (before the clean run)
- **Test geometry:** with the default chat width the open main chat's line area covered the test window, so chosen points were correctly protected. Tests now narrow the vanilla chat width and pick verified unobstructed points.
- **V-53 measurement:** compares against what protected elements draw. The search field's two-row click-only band and the context menu's oversized hover box are recorded separately.
- **Test sequencing:** unread checks wait between F1 and Chat Visibility changes; the persistence check ignores tab filter patterns; V-09e resizes before moving (the moved grip sat under the input box, correctly protected); V-56 sends fresh lines so the gameplay window has visible content.
- **Regression tests:** the Phase 2 HUD checks were updated to Phase 3 behaviour (chat-open path draws with chat open; the HUD path stays inactive).
