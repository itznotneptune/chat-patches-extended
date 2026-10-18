# Phase 7 verification: safe main-chat hiding, hidden-message retention, orphan restoration

Date: 2026-10-19. Build: `cpextended-0.7.0-phase7` (branch `phase7`: Stage A `8aef9a4`, Stage B on top). One clean run of `tools/run-all.sh` from an empty run directory (00:30–01:01): every Milestone 1a/1b and Phase 2–7 mode, the Mod Menu / No Chat Reports launch, both structural comparisons and the build checks. The Stage A gate evidence is in `verification/phase7-stageA/`; it re-ran here.

**Environment:** macOS 26.5.1; Gradle 9.7.1 on JDK 25.0.4; game on JDK 21.0.8; Minecraft 1.21.11, Loader 0.19.5, Fabric API 0.141.6, Chat Patches 8.0.0, YACL 3.8.2. Synthetic chat only.

## Outcome: 205 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)
- 151 Phase 1–6 checks and 23 Stage A checks pass with hiding active in the build.
- 31 Stage B checks pass.
- Mixins: unchanged since Stage A. The only P1 change in Phase 7 is the Q36-approved `allMessages` setter.
- No persistent message storage: hidden references are session-only. `V-46d.static`: still no regex in Extended.

**Fixture adjustments made because hiding is now real:**
- **Phase 6 config:** global `hidingEnabled` is off. Its checks assert nothing is hidden. Restored messages under real hiding are tested in `hide_relaunch2`.
- **Hiding scenarios:** where they need the hiding tabs to be the only holders, the catch-all `hAll` has matchAll turned off.

## How hiding works (Stage B)
- **Decision (§4.6).** The message is hidden when all of these hold:
  - some delivering tab hides, is enabled, healthy (not config- or runtime-suspended), and its window is enabled;
  - `general.hidingEnabled` is on;
  - the adapter isn't degraded and the hidden cap is readable (P7);
  - the message isn't AMBIGUOUS (unknown origin, or an unrecognized restored structure);
  - Chat Patches didn't condense a visible duplicate during the call (Q3).

  Boundary lines are exempt earlier (Q10). Both admissions M2 and M3 then refuse; the message is delivered once to its tabs and the hidden list. Chat Patches has already logged it (D2).
- **Q3/P3 condensation detection:** a dual-signal snapshot taken at M1 entry, only when some tab could hide:
  - signal 1: the main-chat size;
  - signal 2: the identities of the newest entries over the adapter's duplicate-search window (a superset when its settings are unusual or unreadable).

  Detection compares by identity. The duplicate counter is never consulted.
- **P7 retention:** the hidden list's own cap is Chat Patches' `chatMaxMessages`, re-read at each hidden insertion (fallback 16,384).
  - Oldest-first pruning removes the record from every tab with its unread flags.
  - The main chat is never touched; pruned records never return.
  - This is ordinary retention, not orphan restoration.
- **Recovery:** a frame decided hidden but abandoned before delivery is delivered to its tabs by the reconciler (never invisible everywhere).
- **Integration:**
  - hidden entries show in their tabs and in plain-text search;
  - in regex mode a tab holding hidden entries shows "Partial results" (Chat Patches' results never include them, Q31; semantics unchanged);
  - unread follows P4;
  - moving a tab carries its state.
- **GUI:**
  - global "Hiding: On / Off / Paused" button: paused when degraded, with a tooltip;
  - the tab editor's hide toggle no longer says "not active yet";
  - deletions use the Stage A transaction.

## Results (Stage B; `hide.jsonl`, `hideperf.jsonl`, `hide_*.jsonl`, `structural-compare.json`)

| Check | Outcome | Key result |
|---|---|---|
| Hide.eligibility | PASS | (1) No hiding destination: stays in the main chat. (2) Three matching tabs, two hiding: hidden, one entry in each of the 3 tabs, in neither main-chat list. (3) Hiding plus non-hiding destinations: hidden (one healthy hiding tab suffices). (4) Disabled tab, disabled window: received, not hidden. Config-suspended or runtime-suspended tab: neither receives nor hides. (5) Hidden messages are shown in their tab's view. |
| V-45 / V-45b | PASS | Disabled window or tab: matching messages are received but stay in the main chat. Earlier hidden messages stay in the tab. Hiding resumes after re-enable. F1 doesn't change decisions. No orphan prompt. (Chat Visibility toggles not exercised; vanilla drops chat before `addMessage`.) |
| V-47.hidden | PASS | Hidden message: one record, in neither `allMessages` nor the line list; Chat Patches' log grew by exactly one. |
| V-04 / V-04b / V-04c | PASS | Visible X, then hiding on, then X again: Chat Patches condenses, and the replacement stays visible and routed. Hidden duplicates aren't condensed (two entries, accepted). Compact distance 5, 0, 500 and −5, counter off, and window settings unreadable: no missed removal. Counter off: no condensation and no detection. Unreadable settings: superset plus one warning. **Not exercised:** (3) a foreign mod adding an entry inside the call; (4) a restored message with a saved counter. |
| Hide.orphanRestorationRealHiding | PASS | Real hidden messages: deleting a tab whose messages another tab still holds orphans nothing. (9) A forced rename failure leaves the full state dump identical. (7) Then the deletion restores all 3 orphans in capture order, with no duplicate in the main chat or the hidden list. |
| V-48 | PASS | Cap 50, 60 hidden: the oldest 10 are pruned from every tab (including the disabled-window and disabled tabs); unread equals retained; the main chat is unchanged. Cap lowered to 30: trimmed to 30 on the next insertion. Raised to 80: grows to 70, nothing returns. Cap unreadable: degraded, one warning; new messages visible and routed; existing hidden kept; orphan deletion refused (C-1). |
| V-49.routing | PASS | C-1 (5): in degraded mode, hidden history stays in its tab and new matching messages still route there, not hidden. |
| V-50 | PASS | For each of `restoredTag`, `vanillaClearing`, `chatMaxMessages`, `isBoundaryLine`, failing twice then succeeding: degraded once and stays; one toast; existing hidden messages accessible; new messages visible and routed. |
| Hide.searchUnreadRelocation | PASS | Plain search shows the matching hidden entry. Regex shows only Chat Patches' results, with "Partial results". Unread flags of hidden entries clear on selection; the flag of one filtered out by search clears when its window becomes visible (V-46b). Moving the tab carries the same state, unread and hidden entries, with no orphan step. |
| V-18 | PASS (subset) | With hidden messages interleaved, Chat Patches' own line lookup maps each visible line to its message (1:1). Individual context-menu actions not driven. |
| V-22.hiding | PASS | The same script twice gives identical routing, hidden flags and unread counts. |
| V-44, V-06.hidden, V-33g.hidden | PASS | A runtime-suspended tab keeps visible and hidden entries read-only; a context-menu-style delete removes the visible one (liveness); a clear empties tabs, hidden list and unread. No restore prompt. |
| Hide.mainChatVsLog | PASS | 4 messages, 2 hidden: the log gained 4, the main chat 2. |
| structural.hidingOnBaseline | PASS | **Separate hiding-on baseline:** the main chat equals the hiding-off main chat minus exactly the 2 hidden messages (same order); `chatlog.json` is identical. The hiding-off comparison (`structural.mainChatAndChatlogUnchanged`) also still passes. |
| V-31.hiding | PASS | With a catch-all hiding tab active, ordinary messages are hidden; after a world switch the boundary line is the newest entry, unrouted and visible; no boundary line is ever hidden. |
| V-13.mixedHiding | PASS | 16,384 messages, half hidden (main chat 8,576, hidden 8,578): render max 0.94 ms (arrivals), 0.70 ms (regex), 4.13 ms (typing); 0 rebuilds from arrivals. |
| V-20f | PASS | With `chatlog=true` and `false`: the restore disclosed **30** pruned visible messages and pruned **30**. After relaunch, none of the 30 came back: Chat Patches' log (cap 40) had moved past them. With `chatlog=false` nothing from that session returns. |
| V-48.relaunch / V-23 | PASS (documentation) | `chatlog=true`: of 40 hidden messages the session held, Chat Patches restored 40 (inside its log window); Extended restores nothing itself. `chatlog=false`: nothing returns. |
| V-50.relaunch | PASS | Relaunch with the restored-tag read failing: degraded; all 41 restored messages AMBIGUOUS, routed, **none hidden**. |
| Q16b.restoredUnderHiding | PASS | Relaunch without the fault: restored history in a hiding tab with `restoreHistory` on. 41 recognized restored messages are hidden (§4.6); the 8 unrecognized (AMBIGUOUS) ones are **never** hidden. |

## Remaining risks and limitations
- **Approved ordinary pruning can be permanent.** An orphan restoration that pushes the main chat over its cap prunes the oldest *visible* messages (disclosed first, Q22/P6). If Chat Patches' log no longer holds them, they're gone for good (V-20f). Orphans themselves are never pruned.
- **P7 pruning removes hidden messages from tabs for the session.** Whether they return after relaunch depends only on Chat Patches' log.
- **Chat Patches 8.0.0's log at its cap:** new messages that replace old ones without changing the count aren't saved (`ChatLog.serialize` compares sizes; Phase 6 observation). That's Chat Patches' behavior.
- **V-62 residual race:** unchanged. Not claimed closed.
- **Not exercised:**
  - V-04c (3)(4);
  - Chat Visibility toggles in V-45;
  - every individual context-menu action (V-18);
  - real-mod compatibility (V-20n matrix, Phase 8);
  - multiplayer servers.
