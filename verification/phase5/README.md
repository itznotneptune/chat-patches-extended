# Phase 5 verification (production code): global search over tabs

Date: 2026-10-18. Build: `cpextended-0.5.0-phase5` (branch `phase5`). One clean run of `tools/run-all.sh` from an empty run directory: every Milestone 1a/1b and Phase 2–5 mode, the Mod Menu / No Chat Reports launch (`-Pextras=modmenu,ncr`), the structural comparison and the build checks.

**Environment:** macOS 26.5.1 (aarch64); Gradle 9.7.1 on Temurin JDK 25.0.4; game on Temurin JDK 21.0.8 (854×480 window; `scales` resizes it to 1280×960). Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11 (dev runtime), Chat Patches 8.0.0, YACL 3.8.2. Synthetic chat only.

## Outcome: 133 PASS, 1 FAIL, 0 INCONCLUSIVE (for the checks that were run)

The full regression passes on the Phase 5 build: all 123 checks up to Phase 4, including the structural main-chat/`chatlog.json` comparison, V-59 (both field-conflict variants: search integration off, routing unaffected) and V-35. Ten new Phase 5 checks; the one FAIL is the invalid-regex sub-case of V-46c (f), a limit of the approved P12 handles, reported for an owner decision.

No mixin changes since Phase 3; no new P12 handles (still `showSearchBar` and `searchField`); no new persistent data.

### Search (`search.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| V-46 | PASS | Typing `apple` (real keyboard) into Chat Patches' field: three tabs show three different results, each from its own history (`sv` → SV-1, `sm` → SM-1, `sz` → empty with "No matches"). Chat Patches' case-insensitive setting gives SM-1 and SM-2 (`Apple`). Paste (`EditBox.insertText`) filters the same way. Clearing restores every tab's full view. A query change resets a scrolled search view (7 lines up) to the newest match without changing the selected tab or unread. With `searchDrafting` on the query re-applies on reopening; off, it doesn't. |
| V-46b | PASS | (1) A burst of queries and setting changes leaves every tab's entries and order, unread counts, the config file bytes and the main chat's `GuiMessage` identities unchanged; the next message still routes. (2) Two unread entries to the selected tab of a disabled window (one matching): search alone leaves 2; enabling the window clears both, including the one the search filters out. (3) A non-matching message to the selected tab of a visible window is not shown while searching, isn't unread, and appears after clearing. |
| V-46c | PASS | (a) zero matches, setting off: empty view, "No matches", scroll 0; (b)(c) turning `searchEmptyShowsFullChat` on mid-search shows the full history with no indicator from the next frame, off again restores "No matches"; (d) a matching arrival appears at once; (e) a non-matching one appears only after clearing; (f) bar hidden, `config.search` off, no chat screen, a screen before `init`: no filtering, no indicator, no warning. |
| V-46c (f), invalid regex | **FAIL** (approved-design limit) | Chat Patches' regex mode with `([unclosed`: Chat Patches shows its error and its full chat, but Extended keeps showing the previous valid query's matches. Chat Patches stores the error only in its private `searchError` field and leaves its result list unchanged; Extended can't tell an invalid pattern from an unchanged valid one through the two P12 handles. Owner decision requested (see the completion report). No crash, no warning, no degraded mode. |
| V-46d | PASS | Regex mode (`.*apple.*`): every shown entry is in Chat Patches' own result list (identity). A later matching arrival isn't shown and the window shows "Partial results". A catastrophic pattern runs in Chat Patches only (108 ms for the whole query change); history and unread unchanged. **Static:** the release jar has no `java.util.regex` reference and no `String.matches/replaceAll/replaceFirst/split` call (`V-46d.static` in `metadata-check.json`; the detector flags both the Phase 4 jar and Chat Patches' jar). |
| V-40 | PASS | Extended's visibility read matches Chat Patches' field; the field is protected while shown. Hidden with the search button (mouse) it stays hidden after reopening and the window under it gets the input. Toggled back with the real Enter key on the focused button (focus set programmatically: in chat, Tab goes to command suggestions, so Tab focus can't reach the button). Visibility read forced to fail: the field area stays reserved with the bar hidden (conservative), one warning, not degraded. |
| V-46e | PASS | Three forced failures, each separately: (a) a query-side read (Chat Patches' case setting), (b) the visibility read, (c) Chat Patches' regex results. Each: tab search off (no degraded mode), one warning, the search-field region still protected, routing unaffected, Chat Patches' own search keeps working. |
| V-51 | PASS | (a) The same search failure three times: one toast, repeats counted. (b) Distinct failures in sequence (tab A suspended, tab B suspended, search failure, degraded mode): toasts 1, 2, 3, 4; three more chat openings add none. The optional extra reminder on the next chat opening isn't implemented (P9 allows at most one). |
| V-52 | PASS | Valid unusual Chat Patches values (compact distance −5/0/500, counter off, max messages 1 and 100,000, vanilla clearing, chat log off, search off, bar hidden, empty query, invalid regex): no degraded mode, no failure warning. Negative control: `chatMaxMessages = 0` degrades. |

### Search performance (`searchperf.jsonl`, V-13)

16,384 retained messages, 10 tabs in 5 large windows (45 % × 32 % of the screen), plain-text search active.

| Phase | Render max | Render mean | Full rebuilds | Other |
|---|---|---|---|---|
| 10 s of arrivals at ~50 msg/s (500 messages, cap reached) | 2.03 ms | 0.19 ms | 0 | 250 incremental checks (one per arrival to a shown tab); 1,086 frames |
| Regex mode (`.*alpha.*`), 5 s of arrivals | 1.36 ms | 0.24 ms | 0 | later arrivals reported as "Partial results" |
| Rapid typing, one character per tick, 17 query changes, arrivals continuing | 3.59 ms | 0.66 ms | 80 | ≤ 5 per change (one per visible window) |

Render times are Extended's whole chat-open render call (search state read, views, wrapping, drawing). Arrivals are checked incrementally: one check per message for a tab whose window is visible; trimming at the 16,384 cap drops matches from the oldest end without a rebuild. A query change rebuilds each visible window's view once. Allocation inside Extended's render (vanilla text drawing included): 117 KB (plain) and 135 KB (regex) per frame. **Not measured:** frame times of the whole game (vanilla and Chat Patches); mixed hiding (Phase 7).

## Corrections made during Phase 5 (before the clean run)
- **Product (performance):** at the retained-message cap every arrival evicted the oldest record, which counted as a structural change and rebuilt every search view (249 rebuilds in 10 s). Eviction from the oldest end is now followed incrementally. Tab entry lists are immutable snapshots rebuilt only on change, and the chat-open renderer caches each window's total line count, so a frame touches only the lines it draws (mean render 0.66 → 0.20 ms, allocation 487 → 117 KB per frame). Regex mode reuses Chat Patches' result set while it's unchanged instead of rebuilding every frame.
- **Product:** `ConfigParser`'s error description no longer uses `java.util.regex` (parsed by hand), so Extended contains no regex at all.
- **Tests:** the search field captured off the client thread; regex queries must match the whole line (Chat Patches uses `matches()`); V-51's two suspension triggers made distinct.
- **Run environment:** one `searchperf` attempt crashed during world loading in the Fabric gametest network synchronizer ("Network synchronizer in invalid state"), before any test code ran; the retry and the clean run didn't reproduce it.

## Screenshots
`evidence/screenshots/0000_p5-search-plain-apple.png` (per-tab results, "No matches" in `sz`) and `0001_p5-search-regex-partial.png` (regex mode, "Partial results" after a later arrival).

## Not verified in Phase 5
- Search over hidden entries, hidden-only tabs and mixed hiding (V-46 variant, V-46b hidden flags, V-46d hidden part, V-13 mixed hiding): hiding is Phase 7.
- Search over restored history: restoration into tabs is Phase 6.
- Whole-game frame times (only Extended's own render call is timed).
