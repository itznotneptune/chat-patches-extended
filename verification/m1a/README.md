# Milestone 1a verification (production code)

Date: 2026-10-18. Build: `cpextended-0.1.0-m1a` (branch `phase1/m1a`). All results come from one clean run of `tools/run-all.sh`, starting from an empty run directory.

**Environment:** macOS 26.5.1; Gradle 9.7.1 on Temurin JDK 25.0.4 (needed by Loom 1.18.4); game and compile toolchain on Temurin JDK 21. Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11 (dev runtime only, because YACL needs it), Chat Patches 8.0.0+1.21.11-fabric, YACL 3.8.2. Fabric client gametest in singleplayer. All chat content is synthetic (`cpx#…` tokens).

## Outcome: 39 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

| Check | Outcome | Evidence (in `evidence/`) | Key result |
|---|---|---|---|
| V-15 | PASS | `compileClasspath.txt`, `release-fabric.mod.json`, `live.jsonl` (V-15.selfCheck) | The main compile classpath has only `fabric-lifecycle-events-v1` (plus its own base module) and Chat Patches as compile-only. The release metadata declares only that module. The adapter self-check passes with no warnings. |
| V-35 | PASS (for the cases run) | `v35-summary.txt`, `logs/v35-*.log.gz` | Missing, 8.0.1, 7.2.0, and an 8.0.0 build for another Minecraft version are each refused before any mod initializes (no Extended output), naming `=8.0.0` (or Minecraft for the last). **Not run:** Minecraft ≠ 1.21.11 and Java < 21. |
| V-01 | PASS | `live.jsonl`, `V-01-bytecode-excerpt.txt`, `ChatComponent.mixin-export.class` | Player chat, system, client-local, translatable and command feedback: each captured once as LIVE. M1's original lacks Chat Patches' structure; the processed message has it. The exported bytecode shows Chat Patches' `queueMessageWhileLoading` and `modifyMessage` inside our M1-wrapped body, with M2 and M3 around the two admission calls. |
| V-01b | PASS | `live.jsonl` | Leading, trailing and double spaces, `§` codes, emoji, and a lone surrogate are preserved exactly (client-local) and through the integrated-server path; literal tabs match them. |
| V-02 | PASS | `restore.jsonl` | 3,009-entry log; 20 live messages sent at join while the load was in progress were each captured exactly once. |
| V-03 | PASS | `restore.jsonl` | 3,009 restored captures = 3,009 logged at save; 0 live messages tagged restored; 0 bulk entries captured as live. 3,008 restored records survive, equal to the restored entries in the main chat (Chat Patches re-condensed one duplicate). |
| V-05 | PASS | `live.jsonl` | 89 display-queue calls outside a frame (rescale, Chat Patches search typing): 0 new records. |
| V-06 | PASS | `clear.jsonl` | F3+D and disconnect × `vanillaClearing` false/true: the rule's prediction = observed emptiness in all 4 cases; mirror = main chat; tab entries = live token messages. |
| V-47 | PASS | `live.jsonl` | A nested `addMessage` (test mixin playing a foreign mod) gets frame depth 2; outer and inner each delivered once; 0 partial-admission warnings. |
| V-36 | PASS (fault-injection scope) | `faults.jsonl` | Class S (restored tag missing or null, vanillaClearing missing, chatMaxMessages missing or 0, boundary check missing): degraded, exactly 1 toast, routing continues; unreadable restored tag → origin UNKNOWN, ambiguous. Clearing with vanillaClearing unreadable follows the observed main chat. Class L (condensation settings, safe point): fallback only, no degraded mode, no toast. Valid unusual values (cap 40,000; compact distance −5/0/500; counter off): no degraded mode, correct window. |
| V-59 | PASS | `live.jsonl`, `conflict_bool.jsonl`, `conflict_int.jsonl` | (1) both handles resolve at startup, no warnings; (2) NOT_AVAILABLE with no screen and before `init`; (3) the resolver refuses a missing name, wrong type and wrong static-ness; (4) both conflict mods → Class Q: search off, no degraded mode, 1 toast, routing unaffected. Case 3 was tested at resolver level only (no end-to-end wrong-name build). |
| V-24 | PASS | `live.jsonl` | 29 unit cases plus 17 end-to-end messages: substring semantics, `. * \ ^ $ (` ordinary characters, case-insensitive by default, include/exclude. Invalid filters (regex mode, empty) config-suspend their tab and never run (Q14). |
| V-25b | PASS (routing scope) | `live.jsonl`, `restore.jsonl` | Only tab A is suspended; *m* delivered once to B and to C, stays in the main chat; *m2* not delivered to A, stays in the main chat; A's earlier history is kept; 1 toast plus 1 log entry; A is active again after relaunch. **Not applicable in 1a:** hiding via B, and edit-and-save (Q37). |
| V-29 | PASS (routing scope) | `live.jsonl` | One message goes to three tabs as the same record and the same `GuiMessage` instance that's in the main chat. Hiding part not applicable in 1a. |
| V-22 | PASS | `live.jsonl`, `restore.jsonl` | The same 14-message script gives identical routing twice in one launch and across launches. |
| V-37 | PASS (as specified); see risk R-1 | `live.jsonl` | 20 tabs × 1,024-unit case-insensitive patterns, messages of 256/4,096/32,768 units: worst 11.7 ms (emoji-heavy, 32,768 units). End-to-end `addMessage` overhead at 32,768 units: 4.4 ms vs 4.0 ms with no tabs. **An extra adversarial case (not in V-37's definition) takes 343 ms.** |
| Structural | PASS | `structural-compare.json`, `structural_*-mainchat.txt`, `structural_*-chatlog.json` | Main chat (component JSON, tag, signature) and `chatlog.json` are identical with Extended inert (development kill switch: no mixins, no entrypoint), installed with no tabs, and installed routing everything. Only wall-clock timestamps are masked. |
| Diagnostics off by default | PASS | `structural_base.jsonl` | Without `-Dcpextended.debug=true`, diagnostics are disabled and record nothing. |
| Mirror liveness | PASS | `live.jsonl` | An entry removed outside `addMessage` leaves every tab on the next tick; after 3,000 bulk messages, mirror records = main-chat entries (3,007). |

## Reproduce
```
tools/run-all.sh          # clean run of every mode + evidence collection (about 12 min)
tools/run-mode.sh <mode>  # one mode: structural_off|structural_base|structural_tabs|live|restore|clear|faults|conflict_bool|conflict_int
tools/run-v35.sh          # V-35 launches (stopped at Fabric's error dialog)
```
`live` must run before `restore` and `clear`, in the same run directory. Logs in `logs/` have the home-directory path replaced with `~`.

## Test-harness corrections made before the final run (no production change)
- Test-mixin counter moved out of the mixin class (Mixin forbids public static fields there).
- V-03 counts restored captures rather than surviving records (Chat Patches re-condenses duplicates on restore, as in Phase 0).
- Token lookups ignore history restored from earlier launches, which can repeat a token (this caused a false `conflict_int` failure).
- Structural comparison: the timestamp-mask regex didn't allow for JSON spacing; the evidence directory is created before the chat log is copied.
