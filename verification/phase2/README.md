# Phase 2 verification (production code): configuration persistence and read-only HUD windows

Date: 2026-10-18. Build: `cpextended-0.2.0-phase2` (branch `phase2`). One clean run of `tools/run-all.sh`, starting from an empty run directory, covered all Milestone 1a/1b modes and the Phase 2 modes. `config_corrupt` and `config_newer` are real relaunches with files placed before startup (`tools/seed-config.sh`).

**Environment:** macOS 26.5.1 (aarch64); Gradle 9.7.1 on Temurin JDK 25.0.4; game on Temurin JDK 21.0.8. Minecraft 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11 (dev runtime), Chat Patches 8.0.0, YACL 3.8.2. Fabric client gametest in singleplayer. Synthetic chat only.

## Outcome: 74 PASS, 0 FAIL, 0 INCONCLUSIVE (for the checks that were run)

The full 1a/1b regression suite passes on the Phase 2 build: 50 checks, including the structural main-chat/`chatlog.json` comparison, the reconciler scenarios and the R-1 differential.

### Configuration (`config.jsonl`, `config_corrupt.jsonl`, `config_newer.jsonl`)

| Check | Outcome | Key result |
|---|---|---|
| Loading and routing | PASS | Windows and tabs from `cpextended.json` drive routing (include/exclude, matchAll). |
| V-25c | PASS | Empty, `glob`, 1,025-unit and `regex` filters are flagged and their tabs suspended; other tabs route; after an unrelated save every stored mode and pattern is unchanged; still suspended after reload. |
| V-25d | PASS | Valid exactly when `String.length() ≤ 1024`: 1,024 BMP ✓, 1,023 + emoji ✗, 1,022 + emoji ✓, combining marks 1,024 ✓ / 1,026 ✗, U+1D400 ×512 ✓ / ×513 ✗. The file and the edit API give the same verdicts. |
| V-25f | PASS | `§a`, a tab character and a lone surrogate load, match unit by unit, and survive an edit of the same pattern and a save exactly (lone surrogate written as `\uD83D`). |
| V-42 | PASS | Regex-mode filters (including `(a+)+$`) are invalid (`regex_unsupported`), tabs suspended, nothing compiled; other tabs work. |
| V-21c (a) unparseable at startup | PASS | Real relaunch: no-save state; original bytes untouched; byte-identical `cpextended.json.corrupt-<timestamp>`; read-only on a valid `.bak` (routing works from it); edits and saves blocked; one warning; Restore from backup refused without confirmation, then restores `.bak` bytes and re-enables saving; repairing the file in place also leaves the state. |
| V-21c (b) schemaVersion 99 | PASS | Real relaunch: no-save; original untouched; no `.corrupt` copy; understood fields route; automatic and manual saves blocked; Replace with current settings (explicit) writes schemaVersion 1 and keeps unknown keys. |
| V-21c (c) duplicates, unknown `selectedTab` | PASS | Later duplicate tab and window suspended and flagged; first tab shown; file not rewritten. |
| V-21d | PASS | With an invalid filter (extra keys `note`, `weight`) present, geometry, stacking, renaming another tab and moving the invalid tab all save; the invalid filter is written back unchanged and stays suspended; editing it while still invalid, or adding an empty filter, is refused; fixing or deleting saves and unsuspends. |
| V-63 | PASS | Unknown top-level, `general`, window, tab and filter keys, a non-object window entry, an unrecognized window, an invalid filter and a duplicate-ID tab all survive unrelated edits; the moved tab carries its unknown keys; only intended fields change; invalid/duplicate entries still suspended after reload; no message or unread data in the file. |
| V-21b | PASS | A hand edit made while a debounced save was pending is detected by content before the automatic save, applied, and the pending geometry edit is re-applied on top; the check the config screen will run applies an edit without writing. |
| V-61 | PASS | (a) two same-length edits with the modification time restored, (b) `touch -r`-style time restore, (c) an editor's atomic replace: all detected by content. (d) File deleted → divergence lock; Overwrite recreates it, Apply adopts defaults. (e) Unreadable (mode 000) → save blocked, no lock, saved after it became readable. (f) Missing at startup → defaults written. |
| V-60 | PASS | A hand edit deleting a tab that holds hidden messages (simulated, see notes) → divergence lock; the file is preserved, memory unchanged, manual and automatic saves blocked; Apply file refused with nothing changed; after the condition clears, Apply succeeds and clears the lock; Overwrite needs confirmation, then writes and clears the lock. |
| V-21e | PASS | With atomic move unavailable (simulated), saves copy the previous file to `.bak` and write directly, after the content check. Transient lock: one retry, then "not saved", model stays dirty; a single failure is absorbed by the retry. Corruption or a truncated write between saves → no-save on the next load, original preserved plus a `.corrupt` copy, `.bak` offered read-only, Restore from backup writes it back. |
| V-62 | PASS (recorded) | A write just before the final check → detected and merged. A write after the replace → detected at the next save. **A write inside the check-to-replace window is overwritten: the residual race was reproduced, not eliminated.** Window: 129 µs clean, 262 µs with the injected write. |
| No orphan restoration | PASS | Deleting a tab that holds hidden messages (simulated) is refused; unrelated deletions and moves work; nothing is restored to the main chat. |
| Only configuration persisted | PASS | No message history, hidden references or unread state in `cpextended.json`. |

### Read-only HUD windows (`hud.jsonl`, `screenshots/`)

| Check | Outcome | Key result |
|---|---|---|
| V-39 (Phase 2 portion) | PASS | Drawn in gameplay (each enabled window once per frame); not drawn under F1, under Chat Visibility: Hidden (also with chat open), or when disabled. |
| V-55 (Phase 2 portion) | PASS | 1,552 frames over 75 chat open/close transitions (normal, F1, Hidden, back to normal): 0 frames with a wrong draw count, 0 duplicate draws. |
| V-53 gameplay portion | PASS | Windows draw after vanilla's chat HUD: 7,544 unblended window-text pixels over the main-chat area (`p2-hud-gameplay.png`). |
| Not drawn while chat is open | PASS | 0 frames drawn; no window pixels in the window area beyond the main chat, nor in the input box (`p2-hud-chatopen.png`). |
| Render-time clamping | PASS | A window stored at x 1.5, y −0.5, width 2.0 is drawn fully on-screen; `cpextended.json` unchanged by rendering. Wrap cache: 4,550 hits, 0 misses in the measured window. |
| `general.enabled: false` | PASS | Nothing drawn and nothing routed. |

## Implementation notes (engineering choices within the approved design)
- **Stacking order (Q34):** the order of the `windows` array, back to front; "bring to front" moves the window to the end.
- **Anchors:** `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`. `x` and `y` are fractions offsetting the window's corner from the same screen corner. An unknown anchor is drawn as `TOP_LEFT`, and the stored value is kept.
- **Defaults for missing fields:** window enabled, geometry 0.01 / 0.10 / 0.30 / 0.25, tab enabled, `hideFromMainChat` false, `restoreHistory` true, `matchAll` false. A missing file is created at first start with no windows.
- **Parsing:** strict JSON; invalid UTF-8 and a repeated key inside one object count as unparseable, so a duplicate key is never silently dropped.
- **Writing:** pretty-printed in the raw tree's key order. Control characters and lone surrogates are written as `\uXXXX`; numbers keep their stored text.
- **Mid-session edits:** applied through the model API (`ConfigEdit`) that the Phase 3/4 GUI will call. Pending edits are re-applied after an adopted external change.
- **External-edit checks:** at startup, before every save attempt, and through `checkForExternalChanges()` for the Phase 4 config screen. There's no background polling.
- **Orphan guard:** every config-changing path (edits, Apply file, Restore from backup) refuses changes that would orphan hidden messages. Hiding is off in Phase 2, so the refusal was exercised with a development hook.

## Corrections made during Phase 2 (before the clean run)
- **HUD with chat open (product change):** an early build kept drawing windows through the HUD path while chat was open, as a temporary presentation. The test showed window text drawn over the main-chat lines, because 1.21.11 draws those lines in the HUD's chat layer. The path is now inactive with chat open, as P10 specifies; windows appear with chat open in Phase 3.
- **Wrap cache (product change):** one message shown in windows of different widths kept evicting itself. The cache now keys by width.
- **Test fixes:** save expectations for edits that save immediately; the V-61(e) step order; pixel checks that excluded the main chat's own magenta lines; capturing the window rectangle before resetting the counters.
- **Observed Chat Patches limitation:** Chat Patches can't save its log while it holds a message with a lone surrogate (`UnmappableCharacterException` in Chat Patches' save). Extended isn't involved; the V-25f test deletes that synthetic message through Chat Patches afterwards.

## Reproduce
```
tools/run-all.sh                                         # everything, clean (about 20 min) into verification/phase2
tools/run-mode.sh config                                 # in-session configuration tests
tools/seed-config.sh corrupt && tools/run-mode.sh config_corrupt
tools/seed-config.sh newer && tools/run-mode.sh config_newer
tools/run-mode.sh hud
```
