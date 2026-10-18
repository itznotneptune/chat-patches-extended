# RC.1 private validation: Chat Patches Extended 1.0.0-rc.1

Date: 2026-10-19. Artifact: `verification/phase8/release-candidate/cpextended-1.0.0-rc.1.jar`, SHA-256 `c8b9f1f3d2dfb836bcb92b5c8d1254b05ced04f03c11ab8a802ba9ef3b42e83b`. The checksum was verified before every launch, and a fourth clean build reproduced it. Nothing was published, pushed, merged or tagged.

## Verdict
**RC.1 is suitable for a private beta.** There are no release-blocking defects and no message-loss problem.

Three significant usability issues and some minor ones were found. None of them affects message safety, but S1 needs a documentation correction before beta testers rely on backups. An **RC.2 is recommended before any wider distribution**, with the narrow fixes listed below. It isn't required for a small private beta.

## 1. Artifact integrity and Java compatibility

| Check | Result |
|---|---|
| Checksum | **PASS.** `SHA256SUMS` verified; four clean builds identical. |
| Bytecode target | **PASS.** All 138 classes are class file major version 65 (Java 21). |
| Compiler | **PASS.** Gradle runs on JDK 25, but compilation used the **Temurin 21 toolchain** with `--release 21` (Gradle log: "Compiling with toolchain …temurin-21.jdk"). |
| JDK modules used (`jdeps`) | `java.base`, plus `jdk.management` for one diagnostics-only allocation measurement, run only with `-Dcpextended.debug=true`. Present in standard Java 21 runtimes. **Not tested:** Mojang's bundled runtime (`java-runtime-delta`). |
| Runtime on Java 21 | **PASS.** Real production launches on Temurin 21.0.8 (below). |

## 2. Normal-client smoke test (production runtime, not the development run configuration)
- **Profile:** `tools/prodtest/make_profile.py` builds a clean profile from published files only:
  - Mojang's **obfuscated** 1.21.11 client jar, remapped to intermediary at first launch by Fabric Loader 0.19.5, as in a normal installation;
  - the version manifest's libraries, Java 21, an offline account;
  - in `mods/`: Fabric API 0.141.6, YACL 3.8.2, Chat Patches 8.0.0 and **the RC.1 jar itself**.
  - `isDevelopmentEnvironment()` = **false**, so every development hook is inert.
- **Driving it:** the acceptance-test mod, remapped to intermediary with a test-only Loom task, plus Fabric's published client-gametest module drove the client with real keyboard and mouse input (`tools/prodtest/run-prod.sh`).
- **Why not computer use:** the game window belongs to a bare Java process without an app bundle, so it couldn't be granted for computer use.
- **Three launches** of one profile: first launch; relaunch; relaunch with a deliberately damaged settings file.

| Item | Base profile | + Chat Heads, No Chat Reports, Mod Menu |
|---|---|---|
| Launch, default config created (no windows, hiding on), dev environment false, Java 21.0.8 | PASS | PASS |
| Settings key (K) opens the settings; Add window; Add tab; name typed; include filter `cpx#SM`; exclude filter `skip`; hide toggle; second tab with "All"; Save | PASS | PASS |
| Routing and hiding: include match hidden and only in its tab; excluded message stays in the main chat; others routed to the catch-all tab | PASS | PASS |
| Unread count (3) cleared by clicking the tab in the chat-open window | PASS | PASS |
| Window drag (moved 60,30), resize grip (grew), tab dragged into empty space (new window, history kept), geometry saved | PASS | PASS |
| Search: plain query filters the tab; invalid regex shows "Invalid search expression" | PASS | PASS |
| Hiding switch: Off, matching message stays visible (and in its tab); On, hidden again; earlier hidden messages kept | PASS | PASS |
| Disconnect and new world: tab history kept, boundary line newest, routing continues | PASS | PASS |
| Relaunch: Chat Patches restores its log; restored matching messages go to the hiding tab and are hidden | PASS | PASS |
| Damaged settings file: no-save state, original kept plus `.corrupt-<time>` copy; recovery screen; "Replace file" confirmed, back to normal, valid file | PASS | PASS |
| Deleting the only tab holding a hidden message: delete confirmation, then the hidden-message disclosure; "No" keeps the tab | PASS | PASS |
| Exceptions mentioning any of these mods in the six launches | 0 | 0 |

Evidence: `evidence/{base,extras}-prod{1,2,3}.jsonl`, `logs/`, `screenshots/`, `evidence/*-profile.json` (exact mod list and classpath size).

## 3. Safety wording (requirement check)

| Statement | Where the player sees it | Result |
|---|---|---|
| Hidden-message retention is session-only | Hide-toggle tooltip (screenshot `rc1-gui-tab-editor-hide-tooltip`), the "Hiding" button tooltip, README, release notes | Present |
| Chat Patches' log isn't guaranteed backup storage | Same tooltips, README, release notes | Present |
| Older visible messages may be permanently pruned after a confirmed restoration | Disclosure text when pruning applies ("…pruned by Chat Patches' history limit and may not be recoverable"), README, release notes | Present (shown only when pruning applies) |
| Some orphan-producing changes are refused for safety | Hide-toggle tooltip ("…or is refused if that would lose any of them"), refusal messages, README | Present |
| A runtime-suspended tab needs a client restart | Toast "…suspended until you restart the game", README, release notes; the tab is marked "!" | Present |

## 4. Findings

### Release-blocking defects
None.

### Significant usability issues
- **S1. Settings backup recovery is weaker than the documentation says.**
  - **What happens:** under the approved P14/Q36 design, `cpextended.json.bak` is written only by the non-atomic fallback save. On normal file systems saves are atomic, so **no `.bak` exists**.
  - In the recovery run, "Restore from backup…" was therefore disabled with no explanation, and the only GUI path was "Replace file…", which resets the settings to the defaults in use.
  - The damaged original is preserved as `.corrupt-<time>`, so nothing is lost and a hand repair is possible.
  - **The README is wrong:** it states `.bak` is "the previous version".
  - **Fix (docs, RC.2 or beta notes):** correct the README and release notes.
  - **Owner decision:** whether to keep a last-good `.bak` on every successful save. P14 calls `.bak` "last-good, Q36 fallback", but the implementation follows the narrower reading. This would change approved behavior, so I haven't changed it.
  - **Tests:** a production recovery run expecting "Restore from backup" to be available after ordinary saves; V-21e and V-60 unchanged.
- **S2. The disclosure and refusal dialogs name tabs by internal id.**
  - "1 hidden message(s) held only by **tab-1**…" instead of "Smoke" (screenshot `rc1-gui-orphan-disclosure`). At the moment of a potentially lossy decision, the player can't tell which tab is meant.
  - **Fix:** show tab names (and the id only to tell duplicates apart) in `ConfigManager`/`OrphanRestore` messages and the disclosure. Display text only; no behavior change.
  - **Tests:** extend P4.confirmationsNeverBypassOrphanSafety and Q36 refusal checks to assert the tab name appears.
- **S3. The filter case-sensitivity button is unexplained.** Each filter row has a button labelled only `aa` / `Aa` (case-insensitive / case-sensitive), with no tooltip.
  - **Fix:** a tooltip ("Case-insensitive: matches any capitalization; click for case-sensitive"), keeping the label.
  - **Tests:** a GUI check that the tooltip exists; existing V-21 checks unchanged.

### Minor issues
- **M1. Clipped label:** the tab editor's "Restored history: On" toggle is too narrow at GUI scale 2; it reads "red history: O". **Fix:** give it the same width as the hide toggle. **Test:** a widget width / label-fit check at scales 1–4 (V-12 harness).
- **M2. Ids in titles:** screen titles and the window list show internal ids ("Window window-1", "Tab tab-1", "window-1 – 1 tab(s)"); windows have no names in the schema. **Fix (display only):** show the tab's name in the tab editor title; list a window's tab names next to its id.
- **M3. Clipped recovery banner:** it's limited to two lines and cut off the end of the explanation ("…; running" at 854×480); the warning toast overlaps the title for its first seconds. **Fix:** wrap to more lines or show a "details" tooltip.
- **M4. No empty-state guidance:** the empty settings screen doesn't say what a window or tab is. **Fix:** one line of help text.
- **M5. Possible surprise:** a message that was *visible* when it arrived (for example, with hiding switched off) is hidden after a relaunch if a hiding tab with "Restored history" matches it (approved §4.6 behavior; `SM-3` in prod2). **Fix:** a README sentence; no behavior change.
- **M6. Chat Patches cosmetic (not Extended):** the search placeholder shows the raw key `text.chatpatches.search.suggestion`.

### Known limitations (unchanged, documented)
- **Filters and matching:** text-only filters; translated messages match in the client language; Chat Heads' placeholder appears in player-message matching text; Chat Patches' custom name format applies to restored messages.
- **Retention and pruning:** session-only hidden retention; disclosed pruning of older visible messages can be permanent; Chat Patches' own log stops saving when full.
- **Config file:** the ~0.25 ms check-to-rename race remains.
- **Suspension:** recovery from a runtime suspension needs a restart (Q37 deferred).

### Not tested
- **Runtimes and platforms:** Mojang's bundled Java runtime (Temurin 21 only); the official launcher's own profile flow (an equivalent production launch command was used); Windows and Linux.
- **Hands-on use:** a person at the keyboard. All input was synthetic, and usability was judged from screenshots of the real client.
- **Accessibility:** the narrator, very large GUI scales on small windows, colour-blind readability of the unread and error arrow colours.
- **Multiplayer:** a real public server, and the optional mods together with a dedicated server.

## 5. Proposed RC.2 scope (if approved)
1. Docs: correct the `.bak` statement (S1), and add the M5 note.
2. Display text: tab names in the disclosure and refusal dialogs (S2); the `aa`/`Aa` tooltip (S3); the "Restored history" toggle width (M1); the tab name in the editor title and tab names in the window list (M2); recovery banner wrapping (M3); empty-state hint (M4).
3. Owner decision: last-good `.bak` on every successful save (S1). Only if approved.
4. Regression: the full Phase 1–8 suite, plus a production re-run (`tools/prodtest/run-prod.sh`), base and with mods.
