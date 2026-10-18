# Phase 5 correction verification: P12 amendment A-P12-1 (`searchError`) and invalid-regex behaviour

Date: 2026-10-18. Build: `cpextended-0.5.1-phase5` (branch `phase5`, correction commit on top of `7061711`). One clean run of `tools/run-all.sh verification/phase5-correction` from an empty run directory. The original Phase 5 evidence in `verification/phase5/` is unchanged.

**Environment:** as Phase 5 (macOS 26.5.1; Gradle 9.7.1 on JDK 25.0.4; game on JDK 21.0.8; Minecraft 1.21.11, Loader 0.19.5, Fabric API 0.141.6, Chat Patches 8.0.0, YACL 3.8.2). Synthetic chat only.

## Outcome: 135 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

## The amendment (owner-approved Option A)
- **Handle:** a third exact-name P12 handle, instance field `searchError` on `ChatScreen`. Chat Patches 8.0.0 declares it in `ChatScreenMixin` as `private java.util.regex.PatternSyntaxException`, merged into `ChatScreen` at runtime.
- **Semantics, checked with `javap` in `onSearchFieldUpdate`:**
  - set to the exception when `Pattern.compile` fails in regex mode;
  - cleared to `null` on a successful compile, when regex mode is off, and when the query is empty;
  - while it's set, Chat Patches skips its result update, so its result list is stale, and it shows its full chat with a red field.
- **Resolution** goes through the adapter (`SearchFieldHandles.resolveSearchError`). Checks:
  - exactly one field with the exact name and no Mixin-renamed Chat Patches copy (F-1);
  - an instance field;
  - declared type named exactly `java.util.regex.PatternSyntaxException`, compared by name so Extended references no regex API.
- **Reading:** once per frame, only in regex mode with an active query, through a backend seam used for fault injection. `null` means valid. An `IllegalArgumentException` means invalid. Anything else is an interpretation failure.
- **Failures:** unresolvable, ambiguous, unreadable or uninterpretable is a **Class Q** failure. Search integration turns off with one warning and no degraded mode, and routing is unaffected.
- **Display fallback** (documented in the architecture document): while Chat Patches reports the expression invalid, every tab shows its full history, as Chat Patches' own chat does, with a red "Invalid search expression" label. Chat Patches' stale result list is never used as matches. Histories, routing, main chat, unread and configuration are untouched.
- **Unchanged:** no new mixins, Chat Patches unmodified, no regex in Extended (`V-46d.static`: 0 references, 0 regex-running `String` calls).

## Key results

| Check | Outcome | Key result |
|---|---|---|
| V-46c (f), invalid regex | **PASS** | `([unclosed` in regex mode: Chat Patches reports the error; the adapter reads INVALID; every tab shows its full history with the indicator (no stale matches). A message arriving meanwhile is routed, in the main chat, and shown. A second invalid expression behaves the same. Corrected to the earlier valid expression: Chat Patches' current results including the new arrival, no duplicates, no "Partial" indicator; corrected to a different expression: exact results. The same text in plain mode is a literal query. Tab histories, unread, configuration bytes unchanged; the main chat only gained the arrival (at the 16,384 cap one oldest entry was trimmed by it, recorded). No warning, not degraded. |
| V-46c, V-46d, V-46, V-46b, V-40, V-51, V-52 | PASS | Re-run unchanged on the corrected build. |
| V-46e | PASS | Five forced failures: the case setting, visibility, Chat Patches' results, the `searchError` read, and an uninterpretable `searchError` value. Each: tab search off, one warning, not degraded, field region protected, routing unaffected, Chat Patches' own search working. |
| V-59.4 | PASS (3 variants) | `conflict_bool`, `conflict_int` and the new `conflict_error`, in which a test mod adds its own `searchError` field to `ChatScreen`. The resolver sees 2 exact matches and refuses (Class Q, one warning, not degraded); with a valid and an invalid regex typed, tab search stays off and no further warning appears; routing unaffected. Never bound to the other mod's field. |
| V-13 | PASS | 16,384 messages, 5 large windows: render max 1.0 ms (arrivals), 1.2 ms (regex), 4.0 ms (typing); 0 full rebuilds from arrivals; the `searchError` read is one field read per frame in regex mode. |
| Regression | PASS | All 125 earlier checks, the structural main-chat/`chatlog.json` comparison and V-35. |

## Corrections during the correction
- A test assertion (main chat unchanged apart from the arrival) didn't allow for the main chat being at Chat Patches' 16,384 cap, where any arrival trims the oldest entry. In the first full run the cap was reached, so the assertion failed although search changed nothing. Reproduced in isolation on the same run directory: sizes 16,384 → 16,384, one entry removed from the oldest end. The assertion now accepts trimming at the oldest end only (at most one per arrival) and records the sizes. That run's evidence was discarded; this directory is from the rerun.
