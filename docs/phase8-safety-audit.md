# Phase 8: data integrity and safety audit

Date: 2026-10-19. Scope: the highest-risk paths of `1.0.0-rc.1` (branch `phase8`). The method was a code review of each path against the approved design (D2, F1–F3, Q2, Q3, Q10, Q16b, Q17, Q22, Q24, Q27, Q32, Q36, P1, P3, P5–P7, P9, P14, C-1), cross-checked against the verification evidence.

**Finding summary:** no unaddressed message-loss defect outside the approved retention and pruning policies. One latent defect was found and fixed earlier (Phase 6, live boundary lines). One Q36 gap was found and closed in Phase 8 (the rescale retry). The cases where a player can lose access without an explicit prompt are listed in §9; each is approved policy or Chat Patches behavior, and each is now stated in the GUI and the documentation.

## 1. M1–M4 capture and the hiding decision
- **One decision per frame.** It's made at the first of M2/M3, and both admissions return its negation. Delivery happens exactly once (at M3, or at M1 exit, or by the reconciler for an abandoned frame). Evidence: V-47, V-47.hidden.
- **The hidden path:** a hidden message is refused by both admissions and recorded only in its tabs and the hidden list.
  - **Abandoned frame:** a frame decided hidden but abandoned before delivery is delivered to its tabs by the reconciler, so the message is never invisible everywhere.
  - **Partial suppression:** if another mod suppresses only one admission, that's detected and logged (outside Extended's control, §8.4).
- **Hide conditions (§4.6), checked in `Router.hideAllowed`:**
  - a healthy, enabled hiding destination in an enabled window;
  - the global switch on;
  - not degraded, with the cap readable;
  - not ambiguous;
  - no Q3 condensation.

  Boundary lines are exempt before routing. Since Phase 6 the live boundary line is recognized on Chat Patches' processed structure as well. Evidence: Hide.eligibility, V-45/V-45b, V-31.hiding, V-50.
- **Q3/P3 condensation detection:**
  - the snapshot is taken at M1 entry, so it precedes Chat Patches' condensing;
  - comparison is by identity over a window from the adapter, or a superset;
  - the duplicate counter is never used.

  Evidence: V-04, V-04c, including case 3 (an entry injected inside the same call) and case 4 (a restored saved counter).
- **Residual:** a mod adding *and* removing entries outside the snapshot window inside the same call isn't covered. Approved P3 scope; not observed with the tested mods.

## 2. Q36 transaction, reference swaps and rollback
- **Plan without mutation:** every refusal path returns before any assignment.
- **Prepare before swapping:** the temp file is written and flushed, atomic support is confirmed and the fingerprint is checked, all before the first swap.
- **Commit section:**
  - only assignments of objects built during planning, plus one Router install;
  - every swap is reverted in the failure branch, including each orphan record's `hidden` and `tabIds`;
  - the file is replaced last.
- **JVM errors aren't caught (by design).** The session state isn't trusted afterwards; the file is old (V-20l.error).
- **Re-plan at commit.** A confirmed change is re-planned in the same synchronous call. If the counts differ from what was disclosed, it's refused instead of committing a different outcome than the player confirmed.
- **Evidence:** V-20 to V-20p, V-20l (5 failure kinds plus a file edit during the commit), three process-kill points, Q36.rescaleRetry.
- **Gap closed in Phase 8:** a failed line rebuild is now also retried after a rescale.
  - Verified: retries never duplicate messages or repeat the configuration change; references and order are preserved; repeated failures are safe; recovery follows once rebuilding works.

## 3. Orphan-capacity projection
- **Projection:**
  - orphans are merged by capture sequence; unrecorded entries are anchored to their older recorded neighbour;
  - the actual cap is read through the adapter; degraded or unreadable means refusal (C-1);
  - trimming is oldest-first;
  - if even one orphan would be trimmed, the change is refused (P6).
- **Disclosed pruning equals actual pruning:** V-20g (3 = 3), V-20f (30 = 30).
- **Misaligned mirror:** reconciled first; if it's still misaligned, refused.
- **Policy kept as approved:** ordinary older visible messages may be pruned after disclosure. V-20f shows such pruned messages don't come back when Chat Patches' log has moved past them. The confirmation text now says they "may not be recoverable".

## 4. Hidden-reference pruning (P7)
- **Rule:** the cap is re-read at each hidden insertion; pruning is oldest-first; each pruned record leaves every tab, with its unread flags; the main chat is never touched; nothing is resurrected. Evidence: V-48, including the disabled-window and disabled tabs.
- **Unreadable cap:** degraded mode; existing hidden messages are kept; no new hiding (V-48(4), V-49.routing).
- **Silent by design:** P7 pruning shows no notice. It's ordinary retention (Q27). The hide-toggle tooltip and the documentation state the session-only, capped retention.

## 5. Configuration locks and external edits
- **Orphan definition:** a hidden message is orphaned when every tab id holding it is absent from the new file. A malformed tab entry without an id therefore counts as a deletion, which is conservative.
- **Every route is covered:**
  - in-game edits and edit batches: refused unless confirmed;
  - Apply file and Restore from backup: confirmed variants;
  - adopting an external edit: an orphaning edit enters the divergence lock;
  - Overwrite and Replace write memory as it is, so they can't orphan.
- **Locks:** the no-save state and the divergence lock block orphan-producing commits made in game.
- **Evidence:** Q36.externalEditsAndLocks, V-60, V-60.gui, P2.noOrphanRestoration.
- **Residual race:** the check-to-rename window (V-62) remains; it was about 0.25 ms in measurements. Documented in the release notes.

## 6. Chat Patches log behavior
- **Extended never writes Chat Patches' log.** Hidden messages are logged by Chat Patches before admission (Hide.mainChatVsLog, structural.hidingOnBaseline: log identical). Orphans are never re-logged (V-20).
- **Observed (Chat Patches 8.0.0):** `ChatLog.serialize` skips saving when the message count equals the last saved count. At its cap, new messages replace old ones without changing the count, so they aren't saved (Phase 6). This affects visible and hidden messages alike, and Extended can't correct it. It's documented.

## 7. Crashes and interrupted writes
- **The config file is always old or new.** Writes go to a flushed temp file, then an atomic rename. Process kills before the swap, between the swap and the rename, and after the rename left exactly the old, old and new file. Startup deletes stale temp files.
- **Session state** (hidden messages, unread) is lost on a crash, as approved (F1). Chat Patches' log restores what it holds; restored messages may be hidden again by tabs with "Restored history" on (Q16b.restoredUnderHiding).
- **Not verified:** power loss, and file systems without atomic rename (only simulated).

## 8. Privacy
- **Messages are never written to `cpextended.json`** (verified in Phase 2, noMessageData).
- **Logs and diagnostics never contain message text.**
  - Diagnostics are off unless `-Dcpextended.debug=true`. They carry only ids, counts, outcomes and the search query's length.
  - Warnings carry tab names, operation names and exception classes.
- **Test evidence** uses synthetic chat only; logs have `$HOME` redacted.

## 9. Where a player can lose access without an explicit prompt
Each case is approved behavior or a Chat Patches limitation, and each is stated in the GUI tooltip and/or the README and release notes:
1. **Session end** (quit or crash): hidden messages exist only in the session. Chat Patches' log may restore them.
2. **P7 cap:** the oldest hidden messages beyond Chat Patches' history limit are dropped from tabs.
3. **Clearing:** F3+D, or leaving a world with Chat Patches' vanilla clearing on, clears tabs and hidden messages, like the main chat (§4.8).
4. **Disabled tab or window, or `general.enabled = false`** (file only): hidden messages stay but aren't shown until re-enabled (Q24, Q32). Not lost.
5. **Disclosed pruning of older visible messages** during orphan restoration: prompted.
6. **Chat Patches' log at its cap** (§6): affects whether anything comes back after a restart.

## 10. Release-build review
- **Contents:** production classes only (no test classes or resources); the same five approved mixin entries (the Q36 setter lives in the existing accessor interface); no nested jars or bundled dependencies; LICENSE (LGPL-3.0) and COPYING (GPL-3.0) included.
- **Development hooks:**
  - The `dev` package and the `dev*` methods remain in the jar. Each starts with `DevHooks.requireDev()` or checks `isDevelopmentEnvironment()`, so outside a development environment they throw or do nothing.
  - The structural kill switch is also dev-only.
  - Removing them would mean refactoring production classes; the risk was judged nil. **Assessed choice:** keep them, inert.
- **Reproducibility:** fixed archive timestamps and entry order; two clean builds gave byte-identical jars.
