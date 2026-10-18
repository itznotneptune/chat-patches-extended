# Milestone 1b verification (production code)

Date: 2026-10-18. Build: `cpextended-0.1.0-m1b` (branch `phase1/m1b`). One clean run of `tools/run-all.sh` covered all 1a and 1b modes, starting from an empty run directory. Afterwards the `matcher` mode was re-run alone with a corrected trade-off measurement; `evidence/matcher.jsonl` and `logs/matcher.log.gz` come from that re-run.

**Environment:** macOS 26.5.1 (aarch64, 18 cores); Gradle 9.7.1 on Temurin JDK 25.0.4; game on OpenJDK 21.0.8 (Temurin), 12 GB max heap. Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11 (dev runtime), Chat Patches 8.0.0, YACL 3.8.2. Fabric client gametest in singleplayer. Synthetic chat only.

## Outcome: 50 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

All 1a checks were re-run against the 1b build and pass (V-01, V-01b, V-02, V-03, V-05, V-06, V-15, V-22, V-24, V-25b, V-29, V-35, V-36, V-37, V-47, V-59, the structural check, and diagnostics off by default).

### Reconciler (`drift.jsonl`)

| # | Check | Outcome | Key result |
|---|---|---|---|
| 1 | Main-chat additions that bypass capture | PASS | 3 foreign entries (newest, middle, oldest) became UNOBSERVED records: no original, no tabs. Main chat untouched. |
| 2 | Removals | PASS | 2 removed entries left every tab (`stale=2`). |
| 2 | Reordering | PASS | `reordered=true`; mirror and tab order follow the main chat (`cpx#D-8` before `cpx#D-7`). |
| 3 | Abandoned capture frames | PASS | 3 open frames closed. The decided-but-undelivered frame was delivered once (its message is in the main chat); the delivered one wasn't delivered twice; the undecided one → UNOBSERVED. 0 open frames afterwards. |
| 4 | Partial and interrupted admissions | PASS | Foreign cancel of the message-list add → removed from tabs. Cancel of the visible-line add only → kept. Foreign exception → reached the caller, removed from tabs, no frame left open. |
| 5 | Recovery within one pass | PASS | Every injection scenario: aligned and tab-consistent after the first pass. |
| 6 | Idempotent | PASS | Every second pass: `aligned=true`, no changes. |
| 7 | No duplicate tab delivery | PASS | Invariant check: each tab entry appears once, follows mirror order, and carries its tab id. |
| 8 | Main chat unchanged by reconciliation | PASS | Identity snapshot before and after every pass: identical. |
| 9 | Chat log unchanged | PASS | `ChatLog.messageCount()` is unchanged by every pass, and the `chatlog.json` structural comparison (inert / installed / routing) is identical (`structural-compare.json`). |
| 10–11 | 16,384 retained entries | PASS | Steady tick pass: max 0.48 ms. Drift pass (100 removals, 100 foreign inserts, 50 swaps): 10.4 ms, `stale=100`, `unobserved=100`, reordered. After drift, steady max 0.51 ms. All under 50 ms. |
| 12 | 3,000-message burst | PASS | Mirror = main chat (3,029); 3,000 burst entries in the tab; max reconcile pass 0.26 ms. |
| 13 | Chat Patches deletion (`ChatUtil.deleteMessage`) | PASS | The deleted entry left every tab on the next tick. |
| 14–16 | V-22, V-47, V-06 re-run | PASS | `live.jsonl`, `restore.jsonl`, `clear.jsonl`. |

### R-1 matcher (`matcher.jsonl`, `live.jsonl` V-37)

**Differential (optimized vs. the 1a matcher, verbatim from `bd6ccbb`): 0 mismatches in 1,715,456 comparisons**
- 2 × 300,000 randomized comparisons (ASCII, Latin-1, Greek sigma forms, Georgian, Cherokee, Turkish dotted/dotless i, Kelvin and Ångström signs, titlecase digraphs, combining marks, ZWJ/VS16, `§` codes, emoji, Deseret, Osage, Adlam, mathematical letters, lone high and low surrogates; substrings cut at any unit, case-scrambled; Latin-1-only and mixed strings; case-sensitive and -insensitive; a shared `MatchText` as well as a standalone one).
- 1,048,576 single-unit comparisons: every BMP unit against its case variants and neighbours.
- 65,280 supplementary comparisons: cased supplementary blocks, split pairs, and neighbouring lone surrogates.
- 1,600 comparisons with 1,023- and 1,024-unit patterns.

The first differential run found a real divergence, before the fix: a JDK `regionMatches` quirk in which a lone surrogate next to a surrogate pair makes the comparison fall out of step and accept a match early. The fix keeps the original scan in exactly those cases (see the matcher notes below). It was corrected before the clean run.

**Timings: 20 matchers on one message, worst of N rounds after 2 warm-up rounds, client thread**

| Scenario (1,024-unit pattern, 32,768-unit message) | 1a matcher worst | 1b matcher worst |
|---|---|---|
| Adversarial `a…ab` vs `a…a`, case-insensitive (the R-1 case) | 398 ms | **1.0 ms** |
| Same, case-sensitive | 170 ms | 1.0 ms |
| Adversarial mixed case | 479 ms | 1.2 ms |
| Adversarial surrogate pairs | 197 ms | 2.3 ms |
| Adversarial case-folded surrogate pairs | 2,598 ms | 2.4 ms |
| Realistic BMP | 1.8 ms | 0.7 ms |
| Realistic emoji-heavy | 12.0 ms | 1.1 ms |
| Match at the end | 2.8 ms | 0.7 ms |
| **Trade-off (original scan kept):** surrogate pattern, text with a lone surrogate | 194 ms | 194 ms |

V-37 through the router (live mode): worst 7.0 ms for the specified variants, 1.6 ms for the adversarial case. The end-to-end `addMessage` overhead at 32,768 units is 4.0 ms with 20 tabs vs 2.9 ms with none.

## Reproduce
```
tools/run-all.sh                  # clean run of all modes (about 15 min) into verification/m1b
tools/run-mode.sh matcher         # R-1 only (no world)
tools/run-mode.sh drift           # reconciler scenarios (needs a run directory; any mode can run first)
```
