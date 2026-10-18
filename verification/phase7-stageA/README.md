# Phase 7 Stage A verification: the Q36 orphan-restoration transaction

Date: 2026-10-18. Build: `cpextended-0.7.0-phase7a` (branch `phase7`, based on Phase 6 `17de0bc`). One clean run of `tools/run-all.sh verification/phase7-stageA` from an empty run directory, finished 23:49.

**Hiding is still disabled:** every routing decision has `hidden = false`. All hidden messages in these tests are **synthetic fixtures**: a development-only helper (`SessionMirror.devAddHidden`) records a message in the hidden list and its tabs, not in the main chat, exactly as a hidden capture would leave it.

**Environment:** as Phase 6 (macOS 26.5.1; Gradle 9.7.1 on JDK 25.0.4; game on JDK 21.0.8; Minecraft 1.21.11, Loader 0.19.5, Fabric API 0.141.6, Chat Patches 8.0.0, YACL 3.8.2). Synthetic chat only.

## Outcome: 174 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

The run covered:
- all 151 earlier checks (Phases 1–6), including the structural main-chat/`chatlog.json` comparison, V-59 with three conflict variants, V-35, `V-46d.static` and V-64;
- the updated Phase 2/4 orphan checks (P14 V-60, V-60.gui, V-21e, V-62);
- 23 new Stage A checks.

The only P1 change is the Q36-approved `@Accessor @Mutable` setter for `ChatComponent.allMessages`, added to the existing accessor interface. No new mixin classes and no change to the M1–M4 injectors.

## What was built
- **`core/OrphanRestore`: plan, with no mutation.**
  - **Safe point:** client thread, no chat screen, no capture frame, Chat Patches neither loading nor restoring. Otherwise the change is refused, never queued.
  - **History cap:** read through the adapter. If it's unreadable or the adapter is degraded, the change is refused (C-1).
  - **Alignment:** a misaligned mirror is reconciled first; if it's still misaligned, the change is refused.
  - **Projection:** the orphans are merged by capture sequence among the visible entries (unrecorded entries anchored to their older recorded neighbour), then trimmed oldest-first to the cap.
  - **Q27/P6 refusal:** if even one orphan would be trimmed, the whole change is refused. The message names keep, disable or move, and never suggests waiting.
  - **Prepared state:** every structure the commit needs is built as a fresh object.
- **Commit** (one synchronous call on the client thread):
  - swap the references: `allMessages` (through the setter), the mirror's lists and tab map, each orphan's record fields, and the configuration;
  - then the atomic file step;
  - on failure, the previous references are reassigned, with no file write.
- **Post-commit:** entries trimmed by the projection leave their tabs, then `rescaleChat()`. A rebuild failure keeps the commit and sets `linesDirty`: up to 3 retries with the chat closed, then one toast, then a retry at every chat close.
- **`config/ConfigStorage`:**
  - a cached probe for atomic-replace support;
  - `prepareAtomic`: P14 content check, then temp file in the same directory, then `force`, then confirm atomic support;
  - `replaceAtomic`: content check immediately before `ATOMIC_MOVE`; one retry on a transient failure; `AtomicMoveNotSupportedException` marks atomic replace unsupported for the session; never a non-atomic fallback;
  - startup deletes temp files left by a killed save.
- **`config/ConfigManager`:**
  - Every path that could orphan plans first: edits, Apply file, Restore from backup, adopting an external edit.
  - Unconfirmed, they're refused with an explanation. An external orphaning edit enters the divergence lock.
  - The confirmed variants (`editAllRestoringOrphans`, `applyFileRestoringOrphans`, `restoreFromBackupRestoringOrphans`) re-plan and refuse if the counts differ from what was disclosed, then run the transaction.
  - The no-save state and the divergence lock block in-game orphan-producing commits.
- **Orphan definition (`OrphanGuard`):** a hidden message is orphaned only when every tab holding it is absent from the new file. Disabling a tab or window, moving, invalid-filter or duplicate-id suspension, and `general.enabled = false` keep the tab, so they never orphan.
- **GUI:**
  - Delete tab, Apply file and Restore from backup show a second confirmation with the restored and pruned counts (Q22). "No" changes nothing.
  - A nonempty window opens a per-tab move-or-delete screen (P5); its moves and deletion are one operation.
  - An empty window is deleted after the ordinary confirmation.

## Results

| Check | Outcome | Key result |
|---|---|---|
| V-20 | PASS | 2 orphans from a deleted tab return in capture order (`V3, H1-b, V2, H1-a, V1`) as the **same `GuiMessage` objects**. Each appears once, is no longer hidden, belongs to no tab, and the tab is gone from the file. Chat Patches' log count is unchanged; nothing went through `addMessage` (0 new deliveries). Lines match a reference rebuild, and Chat Patches' own line lookup (`getEoEIndex`) maps each line to its message. The reconciler finds the mirror aligned on the next tick. |
| V-20b | PASS | GUI: Delete, then Yes, then the disclosure, then **No**: the full state dump is identical. |
| Q36.sharedHiddenNotOrphaned | PASS | A hidden message also held by another tab isn't orphaned: an ordinary deletion, and it stays hidden in the other tab. |
| V-20g | PASS | (1) One old orphan with the main chat at the cap: refused (message names keep, disable or move; no "wait"), and the dump is identical even when a confirmation is forced. Many orphans: refused. (3) Still refused after more traffic. (2) All orphans survive: succeeds; **disclosed pruned 3 = actual pruned 3**; main chat stays at the cap (60); tabs follow liveness. |
| V-20c | PASS | Orphans pushing the main chat over the cap: the oldest visible entries are pruned, as disclosed. Extended never touches Chat Patches' log. |
| V-49 | PASS | Cap read forced to fail (degraded): deleting the tab or its window is refused (C-1 message) with an identical dump. Unrelated geometry and filter edits save; moving and disabling the tab don't prompt; the hidden message stays in its tab. The "new messages route but aren't hidden" part is Stage B. |
| V-20h | PASS | `isLoading`, `isRestoring` (real adapter path, faulted backend), chat screen open, and an open capture frame: each is refused ("busy"). After the conditions clear, nothing happens: the dump is identical (not deferred). |
| V-20l | PASS | Temp write fails; atomic probe unsupported; rename reports `AtomicMoveNotSupported`; rename transient twice; rename permanent: each is refused, 0 temp files left, and the dump is identical, including **the same `allMessages` list object**. The file-system cases name the limitation. A transient failure that clears is retried and commits. A file edited inside the commit before the rename: detected at the rename, rolled back (memory unchanged; the file holds the external edit). |
| V-20l.error | PASS | A JVM `Error` thrown inside the commit propagates (not caught, by design). The config file is still the old version; in-memory state is not trusted afterwards (documented). |
| V-20l.kill.* | PASS (3) | Process halted (`Runtime.halt`) before the swap, between the swap and the rename, and after the rename. On relaunch the file is exactly the old, old and new version respectively, parseable, and loads normally. The leftover temp file of an interrupted save is deleted at startup. |
| V-20o | PASS | Rebuild fails 1–3 times: commit stands, the flag clears after retries, and lines equal a reference rebuild. No retry while the chat is open; rebuilt after closing. 4 failures: one toast, flag kept, rebuilt at the next chat close. |
| V-20p | PASS | A probe at the swap point already sees the complete new state (orphan in the main chat, not hidden, tab gone, config without the tab). 0 ticks and 0 `addMessage` calls happen during the commit (one synchronous call). |
| V-20k | PASS | Nonempty window: per-tab choice screen. One tab moved (same `TabState`, unread kept) and the hiding tab deleted, its orphan restored, in one operation. The empty window is deleted directly. |
| V-20d / V-20e / V-45c | PASS | Disabling a window or tab, moving (including to a new window), turning the hide toggle off, and suspension by a hand-edited invalid filter: no prompt; the hidden message stays in its tab. |
| Q36.externalEditsAndLocks | PASS | A hand edit deleting the hiding tab enters the divergence lock. In-game orphaning deletion is refused while locked; Apply file without confirmation is refused; the confirmed Apply file restores the orphan and clears the lock. |
| V-20m | PASS (subset) | After the swap, Chat Patches search finds the restored message, and duplicate condensation removes from the new list. |
| V-20n | PASS | A cached reference to the old list is a different object and doesn't see the restored message (stale, documented); no crash. |
| V-20i | PASS | Orphans newer than the newest boundary line are restored; at the next world switch Chat Patches adds a new boundary line (1 → 2), the newest entry. Boundary lines stay in the main chat (Q10). |
| Q36.recoveryAfterRefusal | PASS | A refused attempt (temp write fault) is followed by a successful restoration. |
| Updated earlier checks | PASS | P2.noOrphanRestoration (unconfirmed deletion refused), V-60 (orphaning hand edit locked; unconfirmed Apply refused), P4.confirmationsNeverBypassOrphanSafety and V-60.gui (disclosure; "No" changes nothing), V-21e (no atomic move: ordinary saves fall back, orphan-producing deletions refused), V-62 (residual race reproduced as before). |

## Not verified, or limitations
- **V-62 residual race:** an external write inside the final check-to-rename window is overwritten; the window measured about 240–290 µs. The Q36 rename has the same check-then-rename structure. Not eliminated, and not claimed.
- **Not claimed: disk + memory atomicity.** Within a running session, memory and the file agree after every failure tested. After a crash the file is old or new. Sudden power loss wasn't tested; directory metadata can't be flushed portably from Java.
- **V-20f, V-20j and V-18 were not tested here; they belong to Stage B.**
  - V-20f (relaunch pruning recoverability) needs real hiding.
  - V-20j: restored lines keep their original `addedTime`; the HUD fade wasn't measured.
  - V-18 needs every context-menu action with mixed traffic.
- **The full real-mod compatibility matrix for V-20n is Phase 8.**
- **"Next rescale" retry trigger not implemented:** after the 3 scheduled attempts, retries happen at every chat close instead (the design's "or rescale" trigger would need a mixin).
