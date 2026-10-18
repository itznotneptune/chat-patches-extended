# Phase 8 verification: release hardening, compatibility, release candidate

Date: 2026-10-19. Build: **`cpextended-1.0.0-rc.1`** (branch `phase8`). One clean run of `tools/run-all.sh` from an empty run directory (01:46–02:11), covering:
- every Milestone 1a/1b and Phase 2–8 mode;
- the real-mod matrix (`tools/run-compat.sh`);
- a vanilla dedicated server;
- the V-35/V-36 launches;
- Loader 0.20.0;
- both structural comparisons and the build checks.

**Environment:** macOS 26.5.1 (aarch64); Gradle 9.7.1 on JDK 25.0.4; game on Temurin JDK 21.0.8 (854×480). Minecraft 1.21.11, Fabric Loader 0.19.5 (plus one run on 0.20.0), Fabric API 0.141.6+1.21.11, Chat Patches 8.0.0, YACL 3.8.2. Synthetic chat only; no other players.

## Outcome: 221 PASS, 0 FAIL, 1 INCONCLUSIVE, plus the NOT RUN items listed below
- All 205 Phase 1–7 checks pass, including all Q36 safety tests, both structural baselines (hiding off and hiding on), V-59 (three conflict variants) and V-35.
- 16 new Phase 8 checks.
- The INCONCLUSIVE is the Reply/Delete button click path of Chat Patches' context menu (`V-18.contextMenu.replyDelete`, below).
- Each assertion is counted once: the Loader 0.20.0 re-run of the `live` mode is summarized as a single result, `V-64.newerLoader`.

## Release candidate
- **Jar:** `verification/phase8/release-candidate/cpextended-1.0.0-rc.1.jar`.
- **SHA-256:** `c8b9f1f3d2dfb836bcb92b5c8d1254b05ced04f03c11ab8a802ba9ef3b42e83b`. Three separate clean builds gave this identical checksum.
- **Build command:**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew clean build -x test
  ```

  Built with Gradle 9.7.1, Loom 1.18.4 and the JDK 21 toolchain; archives use fixed timestamps and a stable entry order. Output: `build/libs/cpextended-1.0.0-rc.1.jar`.
- **Contents:**
  - 163 entries: production classes only (no test classes or resources);
  - the same five approved mixin entries (the Q36 setter is in the existing accessor);
  - no nested jars or bundled dependencies;
  - `LICENSE` (LGPL-3.0) and `COPYING` (GPL-3.0).
- **Metadata:** `fabric.mod.json` with id `cpextended`, author `Neptune`, `LGPL-3.0-only`, the unofficial/unaffiliated description, no contact URLs; depends `minecraft =1.21.11`, `chatpatches =8.0.0`, `java >=21`, Loader `>=0.19.5`, four Fabric API modules lower-bounded; Mod Menu only suggested (V-64 checks).
- **Not published, uploaded, tagged or pushed.**

## Phase 8 results

| Check | Outcome | Key result |
|---|---|---|
| Q36.rescaleRetry | PASS | Commit with line rebuilding failing at the commit and all 3 scheduled retries: committed, lines dirty, one toast. Two later rescales (chat width changes) still fail; messages, references and the config file are unchanged by the failed retries. A further rescale (window resize) with rebuilding working recovers: same references and order, no duplicates, the configuration change not repeated, lines equal to a reference rebuild, restored lines visible. |
| compat.none / chat_heads / nochatreports / modmenu / all three | PASS (5) | Each combination: player chat captured and routed; hiding; Q3 replacement visible; P7 cap (20); orphan restoration of 20 messages; Chat Patches search on the swapped list finds them (V-20n: a cached old list is a different object, no crash); input box protected and window strip free; config GUI opens; with Mod Menu, its factory opens the same screen; world switch keeps tabs and the boundary line exempt; vanilla clearing clears tabs and hidden. **The log scan found 0 exception or error lines from any of these mods in every run.** |
| V-18.contextMenu.copyActions | PASS | 11 actions with real clicks on a visible message whose neighbours are hidden messages: Copy Text (Raw, Formatted, Timestamp-less, JSON), Copy Time (Text, Hover Text, Formatted, Unix), Copy Link, Copy Sender (Username, UUID). Each copied exactly the clicked message's data. |
| V-18.contextMenu.replyDelete | **INCONCLUSIVE** | The Reply and Delete buttons had **no effect in this harness**, neither on a real click nor on a direct press; cause not determined. Verified instead from the open menu's own state: the selected line is the clicked message, and the sender is the player. Chat Patches' own `deleteMessage` applied to that selection removed exactly that message, and Extended's tabs followed (liveness). The click path itself is unverified. |
| V-45.chatVisibility | PASS | Full, Commands only and Hidden: every message that reached `addMessage` (system and player) got the same hide decision. |
| V-04c.case3 | PASS | A test-only mixin adds a foreign entry inside the same call while Chat Patches condenses a duplicate; the identity snapshot detects it and the replacement stays visible. |
| V-04c.case4 | PASS | A restored message carrying a saved `x3` counter in a hiding tab is recognized and hidden. The counter never signals condensation. |
| Lang.translatedMatching | PASS | With the client in German, a translated system message matches a filter on its German text; back in English it doesn't (documented, §4.2). |
| V-38.categoryPosition | PASS | "Chat Patches Extended" is the last of 9 categories in Controls (screenshot `p8-controls-category`). This completes the Phase 4 partial. |
| MP.dedicatedServer | PASS | See *Multiplayer* below. |
| V-35 (5 launches) | PASS | Missing, 8.0.1, 7.2.0, 8.0.0-for-1.21.10 and the new **8.0.0-beta.1** pre-release are all refused by Fabric Loader before any mod initializes. |
| V-36.stubJar | PASS | Chat Patches `8.0.0+stub.1` with **no classes** satisfies the `=8.0.0` pin (SemVer ignores build metadata). Extended starts in degraded mode with linkage errors contained per operation; search integration is off; messages route; nothing is hidden; the chat and config screens open. 4 toasts, one per distinct failure key (P9). |
| V-64.newerLoader | PASS | The `live` mode re-run on **Fabric Loader 0.20.0** (dev runtime only; metadata unchanged): 18/18 checks, no Loader warnings about Extended. No newer Fabric API exists for 1.21.11. This completes the Phase 4 partial. |

## Real-mod compatibility matrix
Versions: Chat Heads 1.3.2 (Fabric 1.21.11), No Chat Reports Fabric-1.21.11-v2.18.0, Mod Menu 17.0.1 with Text Placeholder API 2.8.2. Singleplayer.

| Area | None | Chat Heads | No Chat Reports | Mod Menu | All three |
|---|---|---|---|---|---|
| Capture and routing (player and system) | PASS | PASS¹ | PASS | PASS | PASS¹ |
| Main-chat hiding, log still records | PASS | PASS | PASS | PASS | PASS |
| Duplicate condensation (Q3) | PASS | PASS | PASS | PASS | PASS |
| Hidden retention (P7) | PASS | PASS | PASS | PASS | PASS |
| Orphan restoration, V-20n | PASS | PASS | PASS | PASS | PASS |
| Chat Patches search on the restored list | PASS | PASS | PASS | PASS | PASS |
| Window rendering and protected-region input | PASS | PASS | PASS | PASS | PASS |
| Config GUI / Mod Menu entry | PASS / n.a. | PASS / n.a. | PASS / n.a. | PASS / PASS | PASS / PASS |
| World switch, reconnect, vanilla clearing | PASS | PASS | PASS | PASS | PASS |
| Exceptions in log | 0 | 0 | 0 | 0 | 0 |

¹ With Chat Heads, the matched text of player messages includes its head placeholder: `<[Player0 head]Player0Player0> …` instead of `<Player0> …`. A `<name>` filter doesn't match; documented in the README. Routing, hiding and every other check behave identically.

**Combinations or areas NOT RUN:**
- Chat Heads + No Chat Reports without Mod Menu (covered only by the all-three run);
- No Chat Reports or Chat Heads on a dedicated server (the multiplayer run used no extra mods);
- context-menu actions with the extra mods (run only in the base set);
- other Chat Heads, No Chat Reports or Mod Menu versions.

## Multiplayer (`mp.jsonl`)
A vanilla 1.21.11 **dedicated server** started in-process by Fabric's client gametest API (offline mode, port 25611; port 25565 was already in use by another server on this machine and was left alone). One synthetic player.

| Scenario | Side | Result |
|---|---|---|
| Player chat (client to server to client) | server relays; client routes | Routed: `<Player0> mp player chat …` |
| `/say` and plugin-style `/tellraw` (styled prefix, hover and click events) | server | Routed with the expected text |
| 200 messages in one server tick | server burst; client | Each delivered once, in order |
| Duplicates ×3 | client (Chat Patches) | Visible: condensed to 1 entry. Hidden: 3 entries (not condensed, accepted). |
| Hiding with several matching tabs; disabled tab; suspended tab | client | Hidden into the 3 tabs; the disabled tab receives without hiding; the suspended tab neither receives nor hides |
| Search and unread | client | Plain search shows the hidden match in its tab; unread counted |
| Dimension change (nether teleport) | server | Tab history kept, routing continues |
| Kick while a burst of 50 messages was being sent | server | Client shows the disconnect screen; tabs kept (210 entries); **none of the 50 messages sent in the same server tick as the kick reached the client.** They never got to `addMessage`, so Extended never had them. Server and network behavior. |
| Reconnect to the same server | both | Routing works; tab history and hidden messages kept |

## Corrections made during Phase 8
- **Product:** the missing Q36 rescale retry was added. GUI wording was expanded: the hide-toggle and Hiding tooltips (session-only retention; Chat Patches' log isn't a backup), and the orphan disclosure now says pruned visible messages may not be recoverable. The build was made reproducible and the version set to 1.0.0-rc.1.
- **Tests and fixtures (none changed product behavior):**
  - **Rescale:** at 854×480, GUI scales 2 and 3 both clamp to 2, so chat width and window size changes are used instead.
  - **Language:** the German string is read from the game instead of assumed.
  - **Controls category:** found by its key binding.
  - **Context menu:** the first version reported PASS from stale clipboard data; it was rewritten with a clipboard sentinel, hovering before clicking, and strict per-action expectations.
  - **Compat orphan step:** the catch-all tab still held the hidden messages.
  - **Multiplayer:** port conflict (above); the deliberate kick's connection-close assertion is now caught.

## Observations (not Extended defects)
- **Chat Patches 8.0.0** shows its search placeholder as the raw key `text.chatpatches.search.suggestion`. The text is computed once into a `static final` field when its mixin class loads. Seen with and without the other mods.
- **Chat Patches 8.0.0's log** stops saving once full with an unchanged count (Phase 6).

## Remaining limitations and untested scenarios
- **Reply/Delete click path:** INCONCLUSIVE (above).
- **Mod coverage:** the NOT RUN combinations and areas above.
- **Not tested:**
  - power loss, and file systems without atomic rename (only simulated);
  - Windows and Linux hosts (macOS only);
  - other client languages beyond the German check;
  - GUI scales beyond 1–4.
- **V-62 race:** the check-to-rename race remains.
- **Q37:** deferred (relaunch-only baseline).
