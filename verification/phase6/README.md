# Phase 6 verification (production code): restored history in tabs

Date: 2026-10-18. Build: `cpextended-0.6.0-phase6` (branch `phase6`, based on the corrected Phase 5 commit `20a1d00`). One clean run of `tools/run-all.sh` from an empty run directory: every Milestone 1a/1b and Phase 2–6 mode, the Mod Menu / No Chat Reports launch, the structural comparison and the build checks.

**Environment:** macOS 26.5.1 (aarch64); Gradle 9.7.1 on Temurin JDK 25.0.4; game on Temurin JDK 21.0.8. Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11, Chat Patches 8.0.0, YACL 3.8.2. Synthetic chat only.

## Outcome: 151 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

The full regression passes on the Phase 6 build: all 135 earlier checks (Phases 1–5 incl. the correction), the structural main-chat/`chatlog.json` comparison, V-59 (three conflict variants), V-35, V-46d.static and V-64, plus 16 Phase 6 checks.

**Main-chat hiding stays disabled:** every routing decision has `hidden = false`; `rTok` has `hideFromMainChat` on and nothing leaves the main chat. No new mixins; no adapter change; no new persistent data (the match text is session-only, on the in-memory record); Chat Patches' log is never written by Extended.

## Fixtures
- **`restore_seed`** runs a real session on a fresh Chat Patches log, then Chat Patches saves it.
  - Messages sent: a player chat message (default `nameFormat`), `/say`, a team message (`-> [team] <name>`), plain system text, a plugin-formatted message (styled prefix, hover and click events on the name), a plugin line with legacy `§` codes, a translated announcement, nested styles, three look-alike genuine texts (`[12:00:00] …`, `… (x2)`, `… x3`), a message sent three times in a row (dupe counter), and a player message under a custom `nameFormat` `[$]`.
  - It then switches worlds, so Chat Patches adds its boundary line, and sends one more message.
  - Recorded live, in `restore_seed-live.json`: each message's matching text and processed component tree, plus every dupe copy with its timestamp insertion.
- **`tools/make_restore_fixture.py`** appends eight older or foreign structures to that log, each carrying a `cpx#R-old` token:
  - plain string;
  - inline timestamp text;
  - two siblings;
  - non-digit insertion;
  - no insertion;
  - root text;
  - four siblings;
  - translatable root.
- **`restore6`** relaunches and restores it, with five live messages racing the load. The log it restored is `restore6-input-chatlog.json`.

## Results (`restore6.jsonl`, `restore_seed.jsonl`, `restore.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| P2.unit | PASS | Constructed components: recognized structure (with and without visible timestamp) gives sibling 1 unchanged; plain string, 2 or 4 siblings, non-digit / empty / missing / non-ASCII-digit insertion, root text and translatable root are AMBIGUOUS with best-effort text; the input component is never modified. |
| V-03 | PASS | 24 restored records = 24 restored entries in the main chat; 0 live messages tagged restored; the 5 messages racing the load recorded live. In `restore` (live-mode log): 3009 restored captures = 3009 logged, outcome PASS. |
| V-27 | PASS | Player (default `<$>`), `/say`, team, system, plugin-formatted, legacy-`§`, translated, nested-style and post-boundary messages: recognized, matching text identical to the live text. A `<name> hello` filter matches the restored player message; a filter needing the timestamp text (`] system restored`) doesn't; a case-sensitive `§6[VIP] §f` filter matches (codes kept, Q12). |
| V-27b | PASS | Custom `nameFormat` `[$]`: recognized, formatted name kept (`[Player0] custom format cpx#R-custom`). All eight older/foreign structures AMBIGUOUS with the failed rule recorded, routed to the matching tabs, still in the main chat. **Not run:** Chat Heads; logs written by an actual older Chat Patches release (synthetic older shapes used). |
| V-27c | PASS | `[12:00:00] genuine …`, `… (x2)`, `… x3`: matching text identical to live (nothing stripped) and each reaches its filter tab. |
| V-27d | PASS | Dupe-counted message: matching text `dupe restored cpx#R-dupe` (counter slot removed); display keeps the counter (`[13:17:05] dupe restored cpx#R-dupe §8(§7x§r2§8)`). |
| V-27e | PASS | Saved `[name]` formatting kept: a `[name] custom format` filter matches, `<name> custom format` doesn't; no reversal. |
| V-27f | PASS | Player, system, plugin, nested-style and boundary messages: restored tree identical to the live processed tree (contents, styles incl. hover/click, siblings, insertion). Dupe: the restored survivor matches the live copy with the same timestamp insertion (empty root, three siblings, identical sibling 1; Chat Patches saved that copy with its `x2` counter). |
| Q4.perTabRestore | PASS | Tabs with `restoreHistory` on (`rAll`, `rTok`) hold every fixture; `rNone` (off) holds 0 restored and its live entries; 0 boundary lines routed; 0 restored records missing from the main chat (nothing hidden although `rTok` has `hideFromMainChat`). |
| Restore.noDuplicatesOrder | PASS | Every tab: no repeated entry, every entry in the main chat, main-chat order (restored before live). Each message racing the load exactly once in `rAll` and `rNone`; per restored message, tab copies = main-chat copies. |
| V-33c.restored | PASS | 0 restored entries flagged in any tab after the restore; a live message to an unselected tab adds exactly one flag; the selected tab stays at 0. Condensed copies and deletions follow liveness (below). |
| V-11.restored | PASS | Right after the restore, the window whose selected tab holds only restored entries draws 0 HUD lines while those entries are still inside vanilla's 10 s fade window (vanilla's chat shows them, screenshot `p6-hud-after-restore`); a matching live message is drawn at once. |
| Search.restored | PASS | Plain-text search finds the restored system message in its tab; regex mode shows it through Chat Patches' own results; clearing restores the full view. |
| V-31 | PASS | Two world switches: Chat Patches' boundary line is the newest entry each time, exempt (no tab, not even the catch-all `rTok` with `hideFromMainChat`), and on the second switch Chat Patches replaces it (count 2 -> 3 -> 3). No boundary record is ever routed. |
| Restore.pruneAndClear | PASS | With the main-chat cap lowered to 40, 45 live messages prune every restored entry from the tabs by liveness (0 tab entries outside the main chat, nothing recreated); a clear empties every tab. |
| restored.routedPhase6 | PASS | On the 3,009-entry restore of `restore` (live-mode log): every restored message with a test token reaches the token tab (all test tabs have `restoreHistory` on). |

## Corrections made during Phase 6 (before the clean run)
- **Product: boundary lines (latent since Milestone 1a).** The live boundary line Chat Patches adds when you join a world was routed into catch-all tabs. Chat Patches recognizes its boundary line by the insertion on sibling 1 of its *processed* structure; the router only checked the raw component, which lacks that structure for live lines. It now also checks the processed main-chat content (Chat Patches' own detection on its own structure). Found by the first V-31 run.
- **Tests:**
  - `Component.append("")` drops empty strings, so the structural fixtures use `Component.empty()`;
  - one fixture token ran into the following text;
  - tree comparison normalizes identity hashes in hover-event `toString`;
  - V-11 uses a tab holding only restored entries;
  - the dupe round trip compares the restored survivor with the live copy carrying the same timestamp insertion, since Chat Patches' log holds counted copies (§4.3);
  - the old Milestone 1a check `restored.notRoutedIn1a` became `restored.routedPhase6`.
- **Fixture environment:** in the first full run the seed's last message never reached the log. After the earlier modes, Chat Patches' log was at its cap, and Chat Patches 8.0.0 skips saving when the message count equals the last saved count (`ChatLog.serialize`, verified with `javap`). Messages replacing old ones at the cap are therefore not saved; that's Chat Patches' own behavior, recorded as an observation. `restore_seed` now starts from a fresh log.

## Not verified
- Hiding-related restoration behaviour (orphans, Q36, never-hide of AMBIGUOUS under real hiding): Phase 7. Here AMBIGUOUS is recorded and nothing is hidden at all.
- Chat Heads, and logs produced by an actual older Chat Patches release (synthetic older shapes only).
- Restored history on a multiplayer server (singleplayer only), and team prefix/suffix parity beyond one plain team.
- Languages other than `en_us` for translated messages (§4.2: text depends on the client language).
