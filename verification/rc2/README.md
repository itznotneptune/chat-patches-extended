# RC.2 verification: Chat Patches Extended 1.0.0-rc.2

Date: 2026-10-19. Branch `rc2` (from the RC.1 private-validation baseline `a05228c` on `phase8`). Not published, pushed, merged or tagged. RC.1's jar, checksum and evidence are unchanged (`verification/phase8/release-candidate`, `verification/rc1-private`).

## Recommendation: GO for RC.2 as the private-beta candidate
- Every authorized change is in: the P14 amendment (last-good backup), S2, S3, M1–M4, and the S1/M5 docs.
- **Full clean regression: 258 PASS, 0 FAIL, 0 INCONCLUSIVE.** That's Phase 1–8 (222) plus 36 new RC.2 checks.
- **Production-client smoke test on Java 21 with the release jar: 14/14**, in both the base profile and the profile with the optional mods.
- No approved routing, hiding, retention or transaction behavior changed. No mixins were added (the mixin config is byte-identical to RC.1).
- No escalation was needed: the backup ordering keeps every Q36 guarantee (below).

## Candidate
| | |
|---|---|
| Jar | `verification/rc2/release-candidate/cpextended-1.0.0-rc.2.jar` |
| SHA-256 | `58ebb1fbcea8538d239ef45cbe8ae29b0a0fae3f6ed9aa88567b39e61b47dff0` (two clean builds identical; `SHA256SUMS`) |
| Build | `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew clean build -x test`. Gradle 9.7.1 on Temurin 25.0.4; compiled with the JDK 21 toolchain, `--release 21`; Loom 1.18.4; fixed timestamps and entry order |
| Contents | 168 entries. 143 classes, all class-file major version 65 (Java 21). No test classes, no nested jars. `jdeps`: `java.base`, plus `java.management`/`jdk.management` for the diagnostics-only allocation probe (as in RC.1). New classes vs RC.1: `Labels` and four `ConfigStorage` backup types |
| Environment | macOS 26.5.1 (aarch64); game on Temurin 21.0.8; Minecraft 1.21.11; Fabric Loader 0.19.5 (plus 0.20.0 for V-64); Fabric API 0.141.6; Chat Patches 8.0.0; YACL 3.8.2 |

## 1. P14 backup implementation and failure semantics
Design, written before the code: [`docs/rc2-p14-backup.md`](../../docs/rc2-p14-backup.md). Decision record: `docs/decisions.md` §1.3d-1.
- **Promotion rule:** a save that replaces active version A with N writes A to `.bak` **byte for byte**, only if all of these hold:
  - A matched the baseline at the content check and the manager is in NORMAL (A is reconciled);
  - A parses with status OK;
  - A ≠ N, and A ≠ the current backup.

  Otherwise `.bak` is untouched. **Overwrite file** (divergence), **Replace file** and **Restore from backup** (no-save) never promote.
- **Atomic path:**
  1. write N to a temp file and flush;
  2. verify A against the baseline;
  3. stage A as `cpextended.json.bak.<r>.tmp` and flush;
  4. run the content check, then `ATOMIC_MOVE` N over the active file;
  5. `ATOMIC_MOVE` the staged A over `.bak`.

  - **Failures:** if staging (3) or finalizing (5) fails, the save still succeeds and `.bak` stays one generation older. If the commit (4) fails, both files stay byte-identical and the staged file is deleted.
  - **Q36:** steps 1–3 happen in prepare. Step 5 runs only after `OrphanRestore.commit` returns committed, outside its rollback scope.
- **Non-atomic fallback** (ordinary saves only; orphan-producing operations still refused):
  - A is written to `.bak` (flushed) **before** the direct write. A failure there aborts the save.
  - Identical content isn't rewritten.
- **Crash generations, verified by real process halts (`Runtime.halt`) at 12 points:**

| Halt point | Active | `.bak` | Next launch |
|---|---|---|---|
| mid temp write; after temp; mid backup staging; after staging; Q36 after staging | A | B₀ | NORMAL, temps deleted |
| after active replace (ordinary and Q36) | N | B₀ | NORMAL |
| after backup finalize (ordinary and Q36) | N | A | NORMAL |
| fallback: mid backup write | A | torn | NORMAL; backup reported damaged; next save writes A |
| fallback: after backup write | A | A | NORMAL |
| fallback: mid direct write | torn | A | NO_SAVE on backup; Restore from backup gives A |

`.bak` was never N, never an unreconciled edit, and never torn on the atomic path. **Two-file atomicity isn't claimed**; power-loss durability of renames isn't claimed (no portable directory fsync).

## 2. Q36 compatibility and rollback
| Check | Result |
|---|---|
| RC2.B12: confirmed orphan-producing commit | PASS. Tab removed, orphan in the main chat, `.bak` = the previous active version |
| RC2.B13: commit rename fails permanently | PASS. Full state dump identical (config bytes, main chat identity, tabs, hidden list, unread, mirror, Chat Patches log count); `.bak` unchanged; no temp files |
| RC2.B13b: staging or post-commit backup rename fails | PASS. Commit stands; `.bak` keeps the older generation |
| RC2.B14: Restore from backup that would orphan | PASS. Unconfirmed: refused, nothing changed. GUI disclosure names `"Smoke"`, and "No" changes nothing. Confirmed: restored through the Q36 transaction |
| RC2.B15: restore refused by the capacity projection | PASS. Q27 refusal names the tab; files, state and session unchanged |
| Existing Q36 suite (q36, q36_boundary, 3 kill points) | PASS (unchanged) |

## 3. Files and components changed
- **Product:**
  - `config/ConfigStorage` (promotion, staging, finalize, fallback order, `inspectBackup`, dev fault and crash probes, all inert in production);
  - `config/ConfigManager` (promotion only in NORMAL; finalize after the Q36 commit; backup validation messages; names in refusals);
  - `config/Labels` (new);
  - `core/OrphanRestore` (refusal text uses a tab-name describer);
  - `gui/ConfigScreen`, `WindowScreen`, `DeleteWindowScreen`, `TabScreen`, `BaseScreen`;
  - `lang/en_us.json`;
  - `gradle.properties` (1.0.0-rc.2).
- **Docs:** `README.md`, `RELEASE_NOTES.md`, `docs/rc2-p14-backup.md` (new), `docs/decisions.md`, `docs/feasibility-and-architecture.md`, `docs/verification.md`.
- **Tests and tools (not in the jar):** `Rc2Tests` (new); `ProdTests` (RC.2 checks); `AcceptanceTest` and `Phase7aTests` (dispatch and record arity); `tools/run-all.sh` (RC.2 section); `tools/prodtest/run-prod.sh` (version-parameterized).

## 4. GUI fixes (S2, S3, M1–M4)
- **S2 (tab names):** in the tab and window deletion disclosures, the pruning disclosure, busy and capacity refusals, the unconfirmed-deletion refusal, the divergence banner, and restore-from-backup orphan dialogs.
  - Example: `"Smoke"`. Duplicates show `"Smoke" (gx1)` and `"Smoke" (gx2)`; unnamed entries show `unnamed tab (gx3)`.
  - Counts and consequences unchanged.
- **S3 (case button):** the `aa`/`Aa` label is kept, with a tooltip stating the current mode and what a click does. Verified with mouse and keyboard (Tab, then Enter); the flag written and the matching are unchanged.
- **M1 (Restored history toggle):** now as wide as the hide toggle, with a tooltip (also covers M5). Fits at GUI scales 1–4 (1280×960), 854×480 and 640×480, with no overlapping widgets on the tab, window and settings screens.
- **M2 (window labels):** windows are shown as "Window 1: Smoke, Everything", or `Window "custom-w"` for a non-numbered id, with the internal id in a tooltip. Tab screens are titled `Tab "Trade"`; move targets read "-> Window 2". No schema change.
- **M3 (recovery banner):**
  - Wraps under the recovery buttons, up to 3 lines, ending in "…" when longer. A **Details…** button shows the full text.
  - The list moves down to make room; nothing overlaps at any tested size.
  - Restore from backup states why it's unavailable (missing, damaged, newer schema, unreadable).
  - The confirmation names the backup's save time and the `.corrupt` copy.
- **M4 (empty state):** one paragraph explaining windows and tabs and pointing to "Add window". Verified at 640×480 and 320×240 GUI sizes; Add window works from it.

## 5. Documentation (S1, M5)
- **README:**
  - **Settings file, backups and recovery:** what `.bak` holds, when it updates, when it doesn't (broken, newer or unapplied edits; no saved change yet), what Restore does and checks, and what it doesn't cover (one generation old, possible lag after a crash, no power-loss guarantee).
  - **Restored history (M5):** restored messages are routed by the current tabs, both ways.
  - All earlier warnings are retained: session-only retention, Chat Patches' log limits, disclosed pruning, refusals, the check-to-rename race, relaunch-only suspension.
- **RELEASE_NOTES:** an RC.2 changes section, backup wording in the safety list, and M5 in the limitations.

## 6. Regression and focused tests
One clean run of `tools/run-all.sh verification/rc2`: **258 PASS, 0 FAIL, 0 INCONCLUSIVE** (`results-table.md`, `evidence/`, `logs/`).
- **Phase 1–8:** 222 PASS, the same checks as the Phase 8 run.
  - Includes all Q36 tests, the P14 save-state tests (V-21b–e, V-60–V-63), restoration, routing and hiding, search, restored history, unread, windows and tabs, both structural comparisons, the compat matrix, multiplayer, V-35 and V-36, and the release-jar checks (V-46d, V-64).
  - The context-menu Reply/Delete check passed **in the full run** this time (Phase 8 needed a targeted re-run).
- **RC.2:** 36 PASS.
  - `rc2_backup`: 18 (RC2.B1–B10, B12–B18 including B13b).
  - `rc2_gui`: 6 (S2, S3, M1, M2, M3, M4).
  - `rc2_kill_*` plus check: 12.
- **Found by the first full run and fixed:** `Labels` used `java.util.regex` to parse `window-<n>`, which the V-46d static check forbids (Extended never uses a regex engine). It was replaced with plain parsing and **the whole suite was re-run from scratch**. The numbers above are from that second run.
- **Each assertion counted once.**
  - Not counted: the B11 kill launches themselves (halted on purpose; their checks are the 12 check launches), and the halted `q36_kill_*` launches.
  - Simulated rather than real conditions:
    - atomic-move-unsupported and the I/O failures use development fault injection;
    - "unreadable backup" uses POSIX permissions 000.

## 7. Production-client smoke test (`production/`)
`tools/prodtest/run-prod.sh` with the release jar (checksum verified):
- Mojang's obfuscated 1.21.11 client, remapped by Fabric Loader 0.19.5 at launch, on Temurin 21.0.8, offline account; not a development environment.
- Three launches: first launch, relaunch, relaunch with a damaged settings file.
- Real keyboard and mouse input.

| Check | RC.1 | RC.2 base | RC.2 + Chat Heads, No Chat Reports, Mod Menu |
|---|---|---|---|
| Environment (dev false, Java 21.0.8, candidate version and jar) | PASS | PASS | PASS |
| Create windows, tabs and filters through the GUI | PASS | PASS | PASS |
| **Backup generated by those saves** (valid, previous version) | n/a (none) | PASS | PASS |
| Routing and hiding; unread and selection; drag, resize and relocate; search and invalid regex; hiding switch; disconnect and reconnect | PASS (6) | PASS (6) | PASS (6) |
| Restored history after relaunch | PASS | PASS | PASS |
| **Safety dialog names** (`"Smoke"`, no id) | ids shown | PASS | PASS |
| **GUI labels** (window list, title, case tooltip, Restored history fits) | issues S3, M1, M2 | PASS | PASS |
| Damaged settings recovery | Replace file only | PASS | PASS |
| **Recovery from the game's own backup** (restored bytes = backup) | not possible | PASS | PASS |
| Total | 10/10 | **14/14** | **14/14** |

- **No regressions vs RC.1.**
- **Log scan:** in all six logs, no stack frame mentions Extended, Chat Patches or any of the optional mods. The exception lines are offline-account Realms and authentication failures plus one vanilla option warning, as in RC.1.
- **Two test-code corrections** were made during this run; product code was unchanged:
  - the environment check expected the rc.1 version string;
  - the label check assumed both tabs share a window, but prod1's drag test moves one into its own window.

  Screenshots: `production/screenshots/` and `evidence/screenshots/`.

## 8. Remaining risks and known limitations
- **Backup:**
  - one generation only;
  - may lag one generation after a crash or failure between the two renames;
  - no power-loss durability guarantee;
  - a hand edit the game never applied isn't backed up;
  - no backup exists before the first saved change (documented).
- **Check-to-rename race (V-62):** unchanged, documented.
- **Q37:** relaunch-only suspension baseline unchanged.
- **Toasts:** the warning toast can briefly cover the screen title when the settings screen opens right after startup (vanilla toast, transient). Not changed.
- **Not tested:**
  - Windows and Linux;
  - Mojang's bundled Java runtime and the official launcher's profile flow;
  - a person at the keyboard (all input synthetic);
  - the narrator (the case tooltip is narrated by vanilla but wasn't listened to);
  - file systems without atomic rename (simulated only);
  - real power loss;
  - the RC.1 "Not tested" list in general.
- **Known limitations, unchanged:**
  - text-only filters;
  - Chat Heads' placeholder in matched text;
  - custom name formats on restored messages;
  - Chat Patches 8.0.0 log behavior;
  - mods holding the old message list.
