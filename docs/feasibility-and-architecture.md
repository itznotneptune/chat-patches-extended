# Chat Patches Extended — Architecture, Specification & Feasibility

**Chat Patches Extended** (mod ID `cpextended`, package `io.github.itznotneptune.cpextended`, Maven group `io.github.itznotneptune`, LGPL-3.0) is an independent, unofficial, client-side companion mod for [Chat Patches](https://github.com/mrbuilder1961/ChatPatches). It isn't affiliated with or endorsed by the Chat Patches maintainers. It was previously developed under the working name *Chat Patches Tabs*.

| | |
|---|---|
| Status | Documentation only. **Implementation isn't authorized.** See §10 for readiness. |
| Targets (D11 + Q5, Approved) | Minecraft 1.21.11 · Fabric Loader ≥ 0.19.5 · Fabric API 0.141.6+1.21.11 · **Chat Patches 8.0.0 only** · Java 21 |
| Decision register | [decisions.md](decisions.md) (Approved: D1–D12, D6.1, F1–F4, Q1–Q34, Q36, C-1, P1–P15; assumptions A-3, A-4; open: **Q37** only, non-blocking) |
| Test plan | [verification.md](verification.md) |

**Labels:**
- **Approved:** an owner decision.
- **Proposed:** awaiting approval.
- **Verified:** from Chat Patches source or published jar, vanilla 1.21.11 bytecode, Fabric API jar, RE2/J source, or a scratch JDK experiment that was actually run.
- **Unverified:** needs a test (`V-xx`).

**No test has been run in a Minecraft client.**

---

## 1. Summary

- **Main chat (D1):** the Chat Patches main chat is untouched, except for D2 hiding and Q2 restoration.
- **Capture:** each message is captured once inside vanilla `ChatComponent.addMessage`: the **original text** for filtering (D5, Q12) and the processed `GuiMessage` for display.
- **Filtering is Literal-only in the initial release (Q15).** Regex is deferred; a regex filter in the file is invalid, never executes, and suspends its tab (Q14, Q17, Q28).
- **Routing:** every message goes to every matching, non-suspended tab, including tabs in disabled or hidden windows (Q19, Q24).
- **Hiding:** only by a hiding tab in an **enabled** window (Q24). Never for ambiguous restored messages (Q16b), boundary lines (Q10), or when Chat Patches just condensed a visible duplicate (Q3). F1 and Chat Visibility never affect hiding (Q24).
- **Retention:** visible entries mirror `allMessages` (D3); hidden entries are session-only references (F1); persistence comes only from Chat Patches' saved log (F2).
- **Orphans:** only **deletion** orphans hidden messages (Q24). Restoration is chronological, respects the cap (Q22), and is **blocked** if it would prune an orphan that lacks a verified recoverable copy (Q27).
- **Unread:** per displayed entry (Q23), with the move rules (Q25) and F1 rules (Q26).
- **Global search (Q29a):** Chat Patches' own search field filters the main chat **and all Extended tabs** as a display-only filter. In Chat Patches' regex mode, tabs use only Chat Patches' own result list (Q31, approved).
- **UI:**
  - custom GUI (Q6), opened via optional Mod Menu or an unbound keybind (Q20);
  - windows beneath the chat screen, with vanilla and Chat Patches regions winning input (Q18, Q29b);
  - last-interacted window on top (Q30a);
  - drag-and-drop between windows, or into a new window (Q30b–e, Q33).
- **Compatibility:** an exact Chat Patches 8.0.0 pin (Q5); one adapter for all Chat Patches references (F4).

## 2. Verified findings

### 2.1 Chat Patches 8.0.0

| # | Finding | Evidence |
|---|---|---|
| S1 | Message rewritten by `@ModifyVariable(HEAD, argsOnly)` on `addMessage(Component, MessageSignature, GuiMessageTag)` → `ChatUtil.modifyMessage` | `mixin/gui/ChatComponentMixin.java` |
| S2 | While the chat log loads, `addMessage` is cancelled at HEAD; the **unmodified** component is queued and later replayed via `chat.addMessage` | `ChatComponentMixin.queueMessageWhileLoading`, `ChatLog.queueMessage/drainPendingMessages`; `modifyMessage` skipped while `isLoading()` |
| S3 | `buildMessage` always produces an **empty root with exactly three siblings** `[timestamp, message, dupeSlot]`. Missing parts become `Component.empty()`. `tryCondenseDupes` replaces slot 2 with the counter. | `ChatUtil.buildMessage`, `tryCondenseDupes` |
| S4 | The timestamp sibling **always** carries `insertion = String.valueOf(epochMillis)`, even when timestamps are disabled (it's then an empty component with that style) | `Config.makeTimestamp` |
| S5 | Player-name reformatting happens **only** if the message matches the vanilla `<name> text` format and came from a vanilla chat type. The name becomes an empty-root component `[prefix, (teamPrefix), name, (teamSuffix), suffix + " "]` built from `nameFormat` (default `<$>`), with the player's display-name hover style. Other messages keep their content as `m.copy()`. | `ChatUtil.modifyMessage`, `Config.formatPlayername` |
| S6 | `tryCondenseDupes` removes earlier duplicates **directly** from `allMessages`/`trimmedMessages` (main chat only), *before* the new `GuiMessage` is built; it doesn't touch `ChatLog` | `ChatUtil.tryCondenseDupes` |
| S7 | `ChatLog.addMessage(processed)` runs inside `modifyMessage`, so the saved log holds **processed** components | `ChatUtil.modifyMessage` |
| S8 | Restoration replays saved messages via `chat.addMessage(msg, null, RESTORED_INDICATOR)` (`logTag "Restored"`); in restoring mode only dupe condensing runs | `ChatLog.restore`, `modifyMessage` |
| S9 | The log loads **once per game launch**, on the first join | `ChatLog.load` |
| S10 | `chatlog=false`: no load or save; in-memory only | `ChatLog.load/serialize` |
| S11 | `clearMessages`: `vanillaClearing=false` → F3+D clears the main chat **and** `ChatLog`, disconnect clears nothing, vanilla is always cancelled. `vanillaClearing=true` → vanilla clears the main chat; `ChatLog` is kept. | `ChatComponentMixin.clear` |
| S12 | Main-chat cap raised to `chatMaxMessages` (default 16384) | `ChatComponentMixin.moreMessages` |
| S13 | The context menu maps line → `allMessages` index; Chat Patches requires a 1:1 mapping between `allMessages` entries and end-of-entry lines | `ContextMenu` ~L203, `ChatUtil.visible2Message/message2Visible` |
| S14 | Search temporarily filters `allMessages`, calls `rescaleChat()`, then restores the list, synchronously | `ChatScreenMixin.onSearchFieldUpdate` |
| S15 | Chat-screen additions: `charTyped`/`mouseMoved` overrides, `@WrapMethod mouseClicked`, TAIL click handler, `captureClickableText` wrapper | `ChatScreenMixin` |
| S16 | Search button: a widget whenever `config.search` is on. Search field: added as a widget on **every** chat-screen `init` when `config.search` is on, **regardless of** the static `showSearchBar` state; removed only when the user toggles the bar off (and re-added when toggled on). Widget registration therefore **doesn't** indicate visibility. Context menu: adds its buttons as widgets and takes focus; `isMouseOver` is false once closed (`noOp`). Settings panel: hit-tested manually (`isMouseOverSettingsMenu` includes its open check). | `ChatScreenMixin.initSearchWidgets` (L200–237), `SearchButton` callback, `gui/ContextMenu.java` L686 |
| S17 | `ScreenMixin` suppresses tooltips over Chat Patches menus | `mixin/gui/ScreenMixin.java` |
| S18 | Boundary lines are identified by the insertion on the message sibling (`Boundary.isBoundaryLine`); `sendBoundaryLine` inspects `allMessages.getFirst()` | `Boundary.java`, `Config.sendBoundaryLine` |
| S19 | `chatHidePacket = true` by default (server deletions cancelled) | `ClientPacketListenerMixin`, `Config` |
| S20 | The published `fabric.mod.json` has `id chatpatches`, `version 8.0.0`, `minecraft =1.21.11`, `fabricloader >=0.19.0`, requires YACL, **`conflicts: morechathistory`** | Downloaded `chatpatches-8.0.0+1.21.11-fabric.jar` |
| S21 | The published jar uses intermediary names; adapter members exist with the expected signatures (`ChatLog.RESTORED_INDICATOR`, `isLoading()`, `messageCount()`) | `javap` on the published jar |
| S22 | `ChatComponentAccess.getEoEIndex(mouseX, mouseY)` is a public interface method on `ChatComponent`; it returns the main-chat line under the mouse, accounting for Chat Patches' width and shift settings | `accessor/ChatComponentAccess.java`, `ChatComponentMixin` |
| S23 | License LGPL-3.0 | `LICENSE` |
| S24 | **Search matching:** text = `ChatFormatting.stripFormatting(guiMessage.content().getString())`, i.e. **processed** text including the timestamp. `config.regex` → `Pattern.compile(query, caseSensitive ? 0 : CASE_INSENSITIVE)` with **`matches()`** (whole-text match; unbounded `java.util.regex`). Otherwise `contains` (case-sensitive) or `Strings.CI.contains`. Defaults: `caseSensitive = true`, `regex = false`, `searchEmptyShowsFullChat = false`. An invalid regex shows an error and the full chat. | `ChatScreenMixin.onSearchFieldUpdate`, `Config` L176–181 |
| S25 | Search is applied **only while the chat screen is open**: the responder runs on each field edit. New messages arriving mid-search are added to the main chat **unfiltered** until the next field update. On close, the main chat is rescaled to unfiltered and the query is kept in the static `searchDraft`, re-applied on the next open if `searchDrafting` (default true). | `ChatScreenMixin.initSearchWidgets`, `onScreenClose`, `onSearchFieldUpdate` |
| S26 | `searchResults` (static) holds the matching main-chat `GuiMessage`s for the current query and is exposed through the public `ChatScreenAccess.getSearchResults()` | `ChatScreenMixin` L135, L636; `accessor/ChatScreenAccess.java` |
| S27 | In the published jar, `ChatScreenMixin` declares `private static boolean showSearchBar`, `private EditBox searchField` (`class_342`), `private static String searchDraft`, `private static Matcher searchMatcher`, and `private static List searchResults`. These are merged into `ChatScreen` at runtime (names kept unless Mixin detects a conflict). | `javap` on the published jar |

### 2.2 Vanilla 1.21.11

| # | Finding |
|---|---|
| V1 | `addMessage(C,S,T)` → `new GuiMessage` → `logChatMessage` → `addMessageToDisplayQueue` → `addMessageToQueue` |
| V2 | `addMessage(Component)` delegates to the three-argument overload |
| V3 | `addMessageToQueue` (only caller: `addMessage`) does `allMessages.addFirst(msg)`, then `removeLast()` above the cap, so the list is **newest-first** |
| V4 | `rescaleChat()` = `resetChatScroll()` + `refreshTrimmedMessages()`, and `refreshTrimmedMessages` rebuilds lines from `allMessages` via `addMessageToDisplayQueue` |
| V5 | F3+D → `clearMessages(false)`; disconnect / `clearClientLevel` → `Gui.onDisconnected` → `clearMessages(true)` |
| V6 | `Gui.renderChat` draws the HUD chat only when not focused; `ChatScreen.render` draws it focused |
| V7 | Clicks: `ActiveTextCollector.ClickableStyleFinder` → private `ChatScreen.handleComponentClicked(Style, boolean)` |
| V8 | `GuiGraphics.textRenderer(HoveredTextEffects)` and `renderComponentHoverEffect(Font, Style, int, int)` exist; `GuiMessage.splitLines` is public |
| V9 | `deleteMessage(signature)` replaces the entry with a new `createDeletedMarker` object |

### 2.3 Fabric API 0.141.6+1.21.11
- `HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, …)`.
- `ScreenEvents.beforeRender/afterRender`.
- `ScreenMouseEvents.allowMouseClick/Drag/Release/Scroll`.

### 2.4 Regex-engine research

| # | Finding | Evidence |
|---|---|---|
| R1 | `java.util.regex` is a backtracking engine with no timeout, step limit or cancellation API | JDK API; experiment below |
| R2 | JDK 25 memoization defuses classic cases: `(a+)+$`, `(a\|aa)+$` and `(x+x+)+y` finished in under 1 ms on 30 characters. **Backreference patterns still run away:** `(a+)+\1b` and `(a\|a?)+\1c` on 30 × `a` ran for over 3 s (over 700 M `charAt` reads). | Scratch experiment `regexlab/Lab2.java`, run 2026-10-18 on JDK 25.0.4 |
| R3 | A runaway matcher thread **doesn't stop** on `Thread.interrupt()`, and `Thread.stop()` throws `UnsupportedOperationException`. Timeouts therefore can't reclaim the CPU. | Same experiment |
| R4 | A `charAt` read budget would have stopped every runaway found (they read input heavily). Attempts to build zero-read exponential paths (`(?:^\|^){k}(?!)`, `(?:(\|)\1){k}(?!)`) were fast, but **absence of a counterexample isn't a proof** that all expensive paths read input. | Same experiment |
| R5 | RE2/J (`com.google.re2j`, v1.8, BSD-3-Clause, actively maintained):<br>- only an NFA simulation (`Machine`), no backtracker, so linear in input length for a given program;<br>- no backreferences or lookaround;<br>- each `{n,m}` is limited to ≤ 1000, but **nested repetition size isn't limited**, so a pattern under 1024 characters like `((a{1000}){1000}){1000}` could produce a huge program at compile time. | RE2/J source (`Parser.java` L1029, file list), README |
| R6 | RE2/J's runtime cost is O(input length × program size); memory O(program size) per match | RE2/J design (README, `Machine`); runtime cost Unverified (V-26) |

---

## 3. Component architecture (Proposed)

```
io.github.itznotneptune.cpextended
├─ CpExtended                 ClientModInitializer: config load, events, adapter self-check, keybind (Q20)
├─ mixin/
│   ├─ ChatComponentMixin     M1 @WrapMethod addMessage(C,S,T)              capture context
│   │                         M2 @WrapWithCondition addMessageToDisplayQueue (inside addMessage only)
│   │                         M3 @WrapWithCondition addMessageToQueue        (inside addMessage only)
│   │                         M4 @WrapMethod clearMessages(boolean)          clear synchronization
│   ├─ ChatComponentAccessor  @Accessor allMessages (getter: liveness, projection)
│   │                         @Accessor @Mutable allMessages (setter: Q36 reference swap ONLY)
│   ├─ ChatScreenAccessor     @Invoker handleComponentClicked; @Accessor commandSuggestions (Q18)
│   ├─ CommandSuggestionsAccessor  @Accessor suggestions (the open SuggestionsList, or null) (Q18)
│   └─ SuggestionsListAccessor     @Accessor rect (private Rect2i bounds of the open list) (Q18; P1 condition 3)
├─ compat/ChatPatchesAdapter  sole Chat Patches reference point (§8.2)
├─ core/   CaptureContext · MessageRecord · SessionMirror · Router · Reconciler · OrphanRestorer
├─ filter/ FilterSpec · LiteralMatcher · PatternValidator   (no RegexMatcher in the initial release, Q15)
├─ model/  ChatWindow (owns ordered tabs) · Tab · UnreadState
├─ render/ WindowRenderer · LineCache · HudLayer · TooltipPass
├─ ui/     ChatScreenInput (Fabric screen events) · HitTester · TabDragController (Q21/Q30)
└─ gui/    custom config screens (Q6): WindowList, TabEditor, FilterEditor, ConfirmOrphanRestore
```

Mixins total: one injector class (4 injectors) plus four accessor/invoker interfaces. **P1 is conditionally approved**, **amended by Q36** with one `@Accessor @Mutable` setter for `ChatComponent.allMessages`, permitted solely for the orphan-restoration reference swap; no other P1 change is implied. Design approved, runtime assumptions V-01, V-02, V-05, V-06 and V-15 still Unverified; see decisions.md §1.3c. Accessors and invokers can't conflict with other mods' injectors. **No mixins into Chat Patches.**

---

## 4. Message lifecycle & routing specification

### 4.1 Capture sequence (Proposed; ordering Verified from source, runtime Unverified)

```
M1 enter:  push CaptureContext {
             original      = arg component (pre-Chat Patches; @WrapMethod wraps Chat Patches' HEAD injectors)  V-01
             origin        = RESTORED if tag == adapter.restoredTag() else LIVE
             sizeAtEntry   = allMessages.size(); headSnapshot = newest k identities (k via adapter, P3)  (Q3, §5.2)
           }
  original body: Chat Patches may cancel & queue (S2) → nothing below runs, frame popped
                 Chat Patches modifies, may dupe-condense (removes from allMessages, S6), logs to ChatLog (S7)
                 GuiMessage g built; logChatMessage(g)
M2:        ctx.decision = Router.decide(ctx, g)          // once per message
           return !ctx.decision.hidden                    // false → not added to trimmedMessages
M3:        return !ctx.decision.hidden                    // false → not added to allMessages
           after: SessionMirror.record(g, ctx) → deliver to tabs, update unread
M1 exit:   pop
```

- **Re-entrancy:** the context is a stack, so a nested `addMessage` from another mod gets its own frame.
- **M2/M3 coordination (P1 condition 2, Approved requirement; mechanism Proposed):**
  - **Single decision:** the routing and visibility decision is computed **once per frame**, by whichever of M2 or M3 runs first, and stored in the frame. The other wrapper reads it; it never recomputes.
  - **Same answer:** M2 and M3 return the **same** admit/skip value from that stored decision, so a message is either admitted to **both** main-chat lists or to **neither**. Extended can't cause partial admission.
  - **Delivery exactly once:** delivery to tabs, unread flags and the session mirror happen once per frame, guarded by a frame flag, after the decision. If M3 never runs (for example, another mod cancels inside the call), delivery happens at M1 exit if a decision exists.
  - **No frame, no effect:** M2/M3 without an active frame (`refreshTrimmedMessages`, rescale, search) do nothing and return `true`.
  - **Foreign mods:** if another mod's own condition suppresses only one of the two calls, the resulting partial admission is outside Extended's control. It's detected (`allMessages` / line mismatch logged once) and listed under incompatibilities (§8.4).
  - Verified by V-47.
- **Outside `addMessage`:** `refreshTrimmedMessages` (rescale, deletion, Chat Patches search) calls `addMessageToDisplayQueue` with no active frame, so M2 is inert there (V4, V-05).

### 4.2 Matching text

| Case | Matching text | Status |
|---|---|---|
| Live (server, client-local, command feedback) | `original.getString()` **exactly**: no trimming, whitespace or punctuation changes, no `§`-code stripping (Q12). Only case sensitivity is applied, at match time. | Approved (D5, Q12); runtime V-01 |
| Queued during Chat Patches' load, then replayed | Same as live (the unmodified component is queued, S2) | Verified; V-02 |
| **Restored** | Reconstructed text per §4.3 (Q1 exception) | Approved (Q1); details Q16 |
| Chat Patches boundary line | Not matched; exempt from filtering and always in the main chat (Q10) | Approved; detection Verified (S18) |

Inherent properties of `getString()` (documented, not normalization):
- It concatenates text content and drops styling.
- Translatable components resolve in the **client's current language**, so the same server message can produce different text in another language.
- Object components (for example player sprites) contribute their fallback text (V-10).
- Legacy `§` codes inside literal text are kept verbatim (Q12).

D6 limitations: players are identified only by how their name appears in the text. Rank prefixes, nicknames, name changes, spoofed names in message bodies, and server formats without names all defeat or confuse text filters.

### 4.3 Restored-message reconstruction (Q1, Q16a, Q16b Approved; algorithm **P2 conditionally approved**, gated on V-03, V-27–V-27f, V-31; runtime Unverified)

The algorithm is structural only (S3, S4); it never uses text heuristics, so genuine text is never removed.

1. **Boundary check:** if `adapter.isBoundaryLine(R)` → exempt (Q10); not routed; stays in the main chat. *Implementation (Phase 6):* Chat Patches recognizes its boundary line by the insertion on sibling 1 of its **processed** structure (`Boundary.fromMessage` → `ChatUtil.getPart(c, 1)`, verified with `javap`), so the router checks both the original and the processed main-chat content; a live boundary line's original lacks that structure (found by V-31; a latent Milestone 1a defect).
2. **Structure check:** `R` has empty contents and **exactly three siblings** (S3). Otherwise → **AMBIGUOUS** (e.g. logs from older Chat Patches versions). "Empty" is tested by **content** (empty text, no translation or other contents), not object identity, because the saved log is decoded by vanilla `ComponentSerialization.CODEC` (via Chat Patches' `UNSAFE_CODEC`). Whether the round-trip preserves this structure is V-27f.
3. **Timestamp marker:** sibling 0's style insertion is a non-empty string of ASCII digits (S4). Otherwise → **AMBIGUOUS**. If valid, sibling 0 is removed.
4. **Dupe slot:** sibling 2 is removed (Chat Patches' counter slot, S3).
5. **Matching text** = `sibling1.getString()`, **unchanged**. **Username formatting is preserved as saved (Q16a);** no reversal is attempted. Q12 case rules apply.

**AMBIGUOUS (Q16b):** the message routes to matching tabs using the best-effort text (`sibling1.getString()` if present, else `R.getString()`) and is **never hidden**.

*Implementation (Phase 6):* `filter/RestoredText` applies steps 2–5 to the component exactly as it reached `addMessage` (never modifying it); the router uses its text for every tab with `restoreHistory` on (Q4) and records the matching text and, for AMBIGUOUS, the failed rule on the session-only `MessageRecord`. "Digit" means ASCII `0`–`9` only. Restored entries are never flagged unread (Q23) and start faded in the gameplay HUD (§6.1, V-11); they take part in tab display and search like live entries. Chat Patches 8.0.0 doesn't suppress their fade in its own chat (`ignoreRestoredMessages` only skips chat logging), so vanilla's chat shows restored lines for 10 s after joining while Extended's windows don't.

**Limitations (documented, accepted by Q16a):**
- With a non-default Chat Patches `nameFormat` (e.g. `[$]`), restored player messages contain the *formatted* name. Filters written against the live original (`<Steve>`) won't match them. With the default `<$>`, restored and live text are expected to be identical (V-27).
- Chat Heads head components, language-dependent translations (§4.2), and team prefix/suffix parity (V-27).
- Chat Patches' log can hold both `x1` and `x2` copies of a condensed message (S6), so restored history may contain duplicates that live chat didn't show.

### 4.4 Filter matching (D6.1 + Q12 + Q14 + Q14.1 + Q15 Approved)

**Initial release (Q15): Literal mode only.**
- **Literal:** matches if `pattern` occurs anywhere in the matching text as an exact character sequence. No character is special.
  - Case-insensitive (the default, Q12) compares with `regionMatches(ignoreCase=true)`, which is locale-independent.
  - Worst case is O(text length × pattern length), with patterns at most 1024 characters long (V-37).
- **Regex:** deferred.
  - A filter whose `mode` is `"regex"` is a **recognized but unsupported** mode: it's **invalid** ("Regex mode isn't available in this version"), and its tab is suspended (Q14, Q17).
  - Its pattern and mode are preserved verbatim; saving follows **Q28** (Approved): untouched invalid filters are written back unchanged; new or modified invalid filters can't be saved (§7.3).
  - The GUI's mode selector offers Literal only. A loaded regex filter shows as "Regex (unsupported)" and can be switched to Literal, keeping the pattern text exactly (Q14.1(4)).
  - Research for a future approval is kept in §2.4 and §5.1.

**Validation (Approved, Q14.1; retained rules):**

| Rule | Behaviour |
|---|---|
| Length > 1024 characters | Invalid. Measured as Java `String.length()` (**UTF-16 code units**), counted **exactly as stored**, **no Unicode normalization**; the same rule in the file and the GUI (**P8, conditionally approved**). Emoji and other supplementary characters count 2; combining marks count separately. |
| Empty pattern | Invalid |
| Unknown `mode` (any string other than `literal`/`regex`) | Invalid; preserved verbatim |
| `mode: "regex"` (initial release) | Invalid (unsupported); preserved verbatim |
| Mode switch | Pattern text kept **exactly**, revalidated |
| GUI | Inline errors; new or modified invalid filters can't be saved; unrelated valid changes can (Q28) |
| GUI pattern field (P8) | New and valid patterns are edited in an `EditBox` with `setMaxLength(1024)` (vanilla counts `String.length()` and doesn't split a surrogate pair when typed or pasted input is truncated; Verified in bytecode). **An over-length stored pattern is never put into a truncating `EditBox`**, because vanilla `setValue` truncates with a plain `substring`, silently and without regard to surrogate pairs (Verified in bytecode). It's shown **read-only** with an explicit error and a **Replace** action; replacing is intentional and must yield a valid pattern before Save is allowed. |
| Stored-pattern preservation (P8, Q28) | Existing patterns, including `§`, control characters, lone surrogates and other unusual sequences, are kept **exactly** (same UTF-16 contents) when loaded, displayed and saved after unrelated changes. Nothing is filtered or repaired. Whole-file formatting may change on save; only the stored values are guaranteed. |
| Documented input limitation (P8) | Vanilla `EditBox.insertText` passes typed and pasted text through `StringUtil.filterText`, which drops characters not allowed in chat, including `§` and control characters (Verified in bytecode). Such characters can only be **added** by editing the file; existing ones survive edits elsewhere in the pattern, since `setValue` doesn't filter. **No custom text editor** is provided to bypass this. Lone surrogates in hand-edited patterns are accepted, and Literal matching compares them unit by unit. |
| Hand-edited file | Never repaired; the tab is suspended |

**Runtime filter guard (P9, conditionally approved; runtime Unverified):**
- Every Literal filter evaluation is wrapped in `try/catch (RuntimeException)`. If it throws:
  - that evaluation is **no-match**;
  - the **entire** tab is **runtime-suspended** (not just the filter): existing history stays read-only, there's no new routing or hiding through it, and an error marker is shown (Q14, Q17);
  - a bounded warning and a diagnostic log entry follow (§8.2 warnings);
  - **nothing propagates** into vanilla or Chat Patches processing.
- **No partial routing or hiding:** each message's per-tab evaluations all complete **before** the single P1 decision is made.
  - A tab that throws is excluded from that decision, as if it had never matched.
  - The message is hidden only if another **healthy** delivering tab hides it.
  - Delivery to healthy tabs happens normally, exactly once (P1 coordination, V-47).
- **Recovery condition:** a runtime-suspended tab stays suspended **until relaunch**. Its read-only history follows normal liveness and retention meanwhile. Suspension is session state, never written to the config file. **No automatic recovery** (no retries, no timed un-suspension). Whether an intentional player edit of that tab's filters should lift the suspension mid-session is **open (Q37)**.
- Q14 (invalid filters suspend their tab until fixed), Q17 and Q28 are unchanged.

### 4.5 Routing (Approved: D2, F3, Q4, Q8, Q9, Q10, Q14, Q17, Q19, Q24; exact rules Proposed)
- **Delivery:** a message is delivered to tab `T` iff all of these hold:
  - `T` isn't suspended (Q14, Q17);
  - it isn't a boundary line (Q10);
  - for restored messages, `T.restoreHistory` is on (Q4);
  - the filters match: any include filter matches, or there are no include filters and `matchAll` is set; and no exclude filter matches.
- **Disabled or hidden windows still receive** (Q19, Q24). **Disabled tabs (Q32, approved):** keep receiving; don't hide new messages; history preserved; hiding resumes when re-enabled.
- **Multi-tab:** the same `GuiMessage` reference goes to every delivering tab; overlapping tabs have no priority.
- **Config snapshot at arrival:** filter edits apply to future messages only (Q9).

### 4.6 Visibility (Approved: D2, F3, Q3, Q10, Q14, Q16b, Q17, Q24; rule wording Proposed)

A message is **hidden** iff all of the following hold:
1. The adapter isn't degraded (§8.2).
2. Some tab `T` delivered it with `T.hideFromMainChat`, `T` isn't suspended, and **`T`'s window is enabled** (Q24). `T` itself must also be enabled: a disabled tab keeps receiving but **doesn't hide new messages**; hiding resumes on re-enable (Q32, approved).
3. For restored messages, reconstruction wasn't AMBIGUOUS (Q16b).
4. It isn't a boundary line (Q10).
5. Chat Patches didn't condense a visible duplicate during this call (Q3; detection §5.2).

*Implementation (Phase 7 Stage B):* `Router.decide` computes the decision after evaluating every tab (P9: a throwing tab is excluded); `Capture` takes the Q3/P3 dual-signal snapshot at M1 entry only when some tab could hide (`RoutingConfig.anyHidingTab`) and `condensationDetected` compares size and newest-entry identities at decision time; `SessionMirror.enforceHiddenCap` applies P7 at each hidden insertion; a frame decided hidden but abandoned before delivery is delivered to its tabs by the reconciler. Verified: `verification/phase7/`.

Properties:
- **Deterministic.** F1, Chat Visibility, fade, selection, search and position **never** affect the decision (Q24, Q29a).
- **Not retroactive**, except for Q2 restoration.
- **Disabling a window** keeps its earlier hidden messages in their tabs (accessible on re-enable) and only stops new hiding (Q24).

### 4.7 Retention (D3, F1, F2; hidden cap **P7 conditionally approved**, runtime Unverified)
- **Visible:** a tab entry lives while its `GuiMessage` is in `allMessages` (identity).
  - Pruning runs after each capture and on a tick check of `allMessages.size()`, never inside screen input handlers (S14).
- **Hidden (F1, P7):** session-only references, **never serialized**, cleared by the §4.8 rule.
  - **Separate cap:** the hidden list has its **own** cap, equal to Chat Patches' `chatMaxMessages`, read **through the adapter**. It's independent of the main-chat cap, so a session may hold up to about 2 × cap messages.
  - **Pruning:** when an insertion would exceed the cap, the **oldest** hidden references are pruned first. Each pruned message is removed from **every tab holding it**, together with all its unread flags (P4.4).
  - **Main chat untouched:** main chat is **never** pruned or modified to enforce this cap.
  - **Same cap everywhere:** hidden messages held by tabs in disabled windows, disabled tabs or suspended tabs count toward the same cap.
  - **Cap changes:** the cap is re-read as needed (*Proposed:* at each hidden insertion).
    - a **decrease** trims the list on the next insertion;
    - an **increase** allows more future retention;
    - previously pruned messages are **never resurrected**.
  - **Ordinary retention:** cap pruning is ordinary retention pruning (Q27), **not** orphan restoration, and it never triggers Q2. Q27/P6's prohibition on immediate orphan loss during tab deletion is unchanged.
- **Session retention ≠ Chat Patches' log retention:**
  - Chat Patches' saved log caps **all** logged messages (hidden and visible together) at `chatMaxMessages` (`ChatLog.enforceLimits`), saves periodically, and saves nothing when `chatlog=false` (S10).
  - The session's hidden list keeps the newest `chatMaxMessages` **hidden** messages, and the main chat the newest `chatMaxMessages` **visible** ones.
  - The two windows can therefore differ: the session may hold hidden messages the log has already dropped, or vice versa. **After relaunch, only what Chat Patches' log restores comes back.**
  - Extended never assumes the log holds a durable copy of any pruned hidden message (P6).
- **Chat Patches logging off (`chatlog=false`):** hidden messages, like visible ones, exist only in memory and are **gone after the game closes** (S10).

### 4.8 Clearing, reconnecting, switching servers (mirrors S9, S11, V5)

| Event | Chat Patches | Chat Patches Extended |
|---|---|---|
| F3+D | Main chat cleared (plus `ChatLog` when `vanillaClearing=false`) | Clear tab entries, hidden list and unread counts |
| Disconnect / world or server switch, `vanillaClearing=false` | Nothing cleared | Nothing cleared |
| Disconnect / switch, `vanillaClearing=true` | Main chat cleared, `ChatLog` kept | Clear tabs, hidden list and unread counts |
| Reconnect in the same launch | No restoration | No restoration |
| First join after launch | Restore, then replay queued live messages | Restored per §4.3 and Q4; queued live messages as live |

M4 applies `cleared = adapter.vanillaClearing() || !history` after the wrapped call runs, then checks the post-condition (V-06).

### 4.9 Configuration changes (Approved: Q2, Q8, Q9, Q17, Q21, Q22, Q24, Q27, Q28, Q30)
- **Filter edits:** future messages only (Q9).
- **Hide toggle off; disabling a window** (Q24); **disabling a tab** (Q32); **suspension** (Q17); **moving a tab** (Q21, Q30): **no orphan restoration**.
  - Hidden messages stay in their tabs and become accessible again once the tab or window is re-enabled or the filter is fixed.
- **Suspended tabs (Q17):** keep entries read-only (still subject to liveness, §4.7), show errors, and receive and hide nothing new.
- **Orphan-producing changes:** only **deleting** a tab, or a nonempty window together with tabs the player chooses to delete, that holds hidden messages (Q24). **Disabling a window or tab, moving a tab, and suspension never orphan.**
- **Window deletion (P5, conditionally approved):**
  - An **empty** window may be deleted directly (it otherwise persists, Q30e).
  - Deleting a **nonempty** window requires the player to choose, **for each contained tab**, to **move** it (to an existing window) or **delete** it.
  - Moves follow Q21/Q25 (no orphaning). Deleted tabs that hold hidden messages follow the Q2/Q22/Q27 flow below, all as **one** operation.
- **Restore procedure (Q2, Q22, Q27 Approved; mechanics P5 conditionally approved; runtime Unverified):**
  1. **Safe-point check, at confirmation time.** All of these must hold:
     - Minecraft client thread;
     - no `ChatScreen` open;
     - no capture frame active (Extended isn't processing an incoming message);
     - `adapter.isLoading()` and `adapter.isRestoring()` both false.

     If any fails → **refuse** the whole operation with a clear message ("Chat is busy; try again in a moment"). **Nothing is queued or deferred** (P5).
  2. **Plan phase (no mutation).** Build, without touching live state:
     - the new configuration model (immutable copy);
     - the orphan set;
     - a **projected** `allMessages` list: orphans merged by **capture sequence number** among visible entries (unrecorded entries keep their relative position, anchored to their nearest recorded neighbours), then trimmed oldest-first to `chatMaxMessages`;
     - the projected hidden-reference set and tab contents.
  3. **Q27 check on the projection (P6, conditionally approved).** No Chat Patches log copy is ever treated as verified (no private log lists, saved JSON or async serialization are consulted). Using the projection and the **actual** main-chat cap, if **even one orphan** would be immediately pruned → **refuse the entire operation**, with nothing changed (config, main chat, tab histories, hidden references). The refusal message explains that the player can **keep, disable or move** the tab instead, and **doesn't** suggest waiting: an old orphan may stay outside the retained window indefinitely. Ordinary visible-message pruning is permitted and disclosed (Q22).
  4. **Confirmation** shows the restored and pruned counts. **Cancel** → nothing changes (Q22).
  5. **Prepare phase, continued (Q36, conditionally approved; still no live mutation):**
     - **5a.** Serialize the projected config into a **temp file in the same directory** as `cpextended.json`, then `FileChannel.force(true)`. Any I/O failure → refuse; live state untouched.
     - **5b.** Confirm that **atomic replacement is available**: a cached startup probe in the config directory (temp file + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`), and not marked unsupported since. If it's unavailable → **refuse** the orphan-producing operation with a clear message ("Your file system doesn't support safe config replacement; this deletion can't be performed safely"), delete the temp file, live state unchanged. **Never fall back to a non-atomic write for orphan-producing operations.**
     - **5c.** All new in-memory structures already exist as fresh objects from step 2: the projected `allMessages` list, tab structures, the hidden-reference set and the config model. The previous references are held for rollback.
  6. **Commit phase** (client thread; one synchronous section; nothing else runs or observes in between):
     - **6a. Reference swaps, with no allocation:**
       - set `ChatComponent.allMessages` to the projected list through the **`@Accessor @Mutable` setter** (P1 amendment, Q36);
       - assign the new tab structures, hidden-reference set and config model.
       - Orphans are **never** passed through `addMessage`, so Chat Patches neither reprocesses nor re-logs them (S7).
     - **6b. Atomic replace:** `Files.move(temp, cpextended.json, ATOMIC_MOVE, REPLACE_EXISTING)`.
       - A **transient** failure (e.g. a Windows `AccessDeniedException` / sharing violation from antivirus or indexers) gets **one** retry after a short delay.
       - `AtomicMoveNotSupportedException` marks atomic replacement **unsupported** for the session.
     - **6c. Rename failure rollback:** if 6b ultimately fails, **reassign the previous references** (no allocation, no file write), delete the temp file, refuse with a message. The file on disk is untouched, so memory and disk agree.
  7. **Post-commit (derived state):**
     - `rescaleChat()` rebuilds the displayed lines (V4).
     - A rebuild failure **doesn't roll back** the successful commit. It sets a `linesDirty` flag (*Proposed*).
     - While `linesDirty`, each client tick with **no chat screen open** (Chat Patches search intentionally filters lines while open, S14) retries `rescaleChat()`, up to 3 attempts at increasing tick intervals.
     - On success the flag clears. On final failure: one error log line and toast ("Chat display needs a refresh; reopen chat or rejoin"), and the flag stays set so the next chat-screen close or rescale retries.
     - *Limitation:* there's no cheap structural "lines match entries" proof, because vanilla caps `trimmedMessages` by **line** count, so at the cap fewer messages have lines than `allMessages` holds. The flag records rebuild completion, not structural equality; tests compare against a reference rebuild (V-20o).
  8. **Unrecorded entries** (e.g. vanilla deleted-message markers, V9) are ordered by neighbour position. That's deterministic but approximate.
- **Failure taxonomy** (Q36; *design approved conditionally, runtime Unverified*, V-20l):

| Failure | Where | Outcome |
|---|---|---|
| Safe-point / Q27 / projection failure | Steps 1–4 | Refused; nothing changed |
| Recoverable exception in prepare (temp write, flush, probe) | 5a–5b | Refused; temp deleted; nothing changed |
| Unsupported atomic replace | 5b or 6b | Refused (6b: references swapped back); unsupported cached; ordinary saves use the fallback (§7.1) |
| Transient rename failure | 6b | One retry; then 6c rollback; refused |
| Exception during reference swaps | 6a | Plain field assignments of existing objects; not expected to throw. If one did, 6c rollback applies. |
| Rebuild failure | 7 | Commit stands; derived-state retry (`linesDirty`) |
| JVM `Error` (e.g. `OutOfMemoryError`) | Any | Not caught by Extended. Vanilla's main loop handles OOM (`emergencySave`, `OutOfMemoryScreen`, Verified in bytecode); other `Error`s crash. The in-memory session ends; disk holds the old **or** new config (6b is a single rename). |
| Process crash | Any | Same as above: old-or-new config on disk; the next launch rebuilds tabs and hiding from config plus Chat Patches' restore |
| Sudden power loss | After 6b | **Atomicity ≠ durability.** The rename is atomic for readers, and the temp file is flushed before it. Directory metadata can't be flushed portably from Java, so after power loss the **old** file may reappear: the deletion is lost, state stays consistent. A truncated file isn't expected (flushed temp + rename) but can't be ruled out on all file systems; if it occurs, the unparseable-config path applies (§7.3). |

- **Implementation (Phase 7 Stage A):** `core/OrphanRestore` (plan, commit, post-commit, `linesDirty` retries), `config/ConfigStorage` (`atomicReplaceAvailable` probe, `prepareAtomic`, `replaceAtomic`, stale-temp cleanup at startup) and `config/ConfigManager` (`checkEdits`/`editAllRestoringOrphans`, `checkApplyFile`/`applyFileRestoringOrphans`, `checkRestoreFromBackup`/`restoreFromBackupRestoringOrphans`). A confirmed change is re-planned at commit time and refused if its counts differ from what was disclosed. Orphaned = held by no tab id present in the new file (so disabling, moving, suspension and `general.enabled=false` never orphan). Apply file needs no write (the file already holds the new version): its file step verifies the fingerprint is unchanged. The "next rescale" retry trigger isn't implemented (it would need a hook); retries are the three scheduled attempts and every later chat close. Verified: `verification/phase7-stageA/`.
- **What's not claimed:** disk and memory are **not** one atomic transaction. The design guarantees that a failure inside a running session leaves memory and the config file in agreement, and that after any crash the file is old-or-new; the session-only state is then rebuilt. No full atomicity has been **verified**.
- **Compatibility risk (documented):** the swap replaces the `allMessages` **object**.
  - Chat Patches and vanilla read the field on each access (Verified: `getChat().allMessages` in `ChatUtil`, `ChatLog`, `ContextMenu`).
  - Chat Patches' static `searchResults` keeps references to old entries, but it's rebuilt when the chat screen reopens (S25), and the restore never runs with the chat screen open.
  - **Other mods that cached the old list** would see stale data. Tested by V-20n, listed in §8.4.
- **Known side effects (documented; not workarounds):**
  - `rescaleChat()` resets the main-chat scroll.
  - Restored orphans newer than Chat Patches' newest boundary line move that boundary off the top of the list. Chat Patches inspects `allMessages.getFirst()` (S18), so it may append a **duplicate boundary line** at the next world switch. Boundary lines are still preserved in the main chat, so this is believed not to violate Q10, but V-20i must confirm it. A violation goes back to the owner.
  - A restore shortly after joining can rebuild lines whose fade-in Chat Patches suppressed (`hideRecentMessages`), so restored history may briefly reappear in the HUD (V-20j).
- **Saving (Q28):** see §7.3.

### 4.10 Unread counters (Approved: D10, Q7, Q19, Q21, Q23, Q25, Q26, Q29a; **model P4 Approved**; runtime Unverified)
- **Model (P4, Approved):**
  - every retained tab entry has its **own** `unread` flag;
  - a tab's count = the number of retained flagged entries;
  - there's **no separate authoritative counter** (a cached total, if any, is derived and never trusted over the flags);
  - flags are **per tab**: a message routed to several tabs has an independent flag in each.
- **Window "hidden" state for unread:** the window is disabled; or Chat Visibility: Hidden; or F1 is on **and the chat screen is closed** (Q19, Q26). Under F1 with chat open, windows are visible (Q26).
- **On delivery:** the entry is flagged iff the tab isn't selected or its window is hidden. **Restored-history entries are never flagged** (Q23, P4.3).
- **Removal (P4.4):** pruning, deletion, clearing and duplicate condensation (through liveness) remove entries **together with their flags**. Counts therefore never exceed the retained unread entries (Q23). A condensed duplicate is one new displayed entry, flagged per the delivery rule. Hidden duplicates aren't condensed and each counts.
- **Becoming read:**
  - selecting a tab clears its flags (P4.5);
  - when a selected tab's window **becomes visible** (F1 released with chat closed, chat opened under F1, Chat Visibility shown, window re-enabled), **all** its flags clear, **including entries currently filtered out by global search** (P4.6, Q7).
  - *Mechanism Proposed:* a cheap per-tick check of each window's visibility transition.
- **Search (Q29a, P4.7):** search by itself never sets or clears flags and never changes counts.
- **Moving tabs (Q21, Q25):** flags travel with entries.
  - Into a populated window: unselected, flags kept.
  - Into an **empty, visible** window (incl. a window created by a Q30c drop): auto-selected, flags cleared.
  - Into a hidden destination: flags kept; they clear later by the becoming-visible rule.
  - **Config-screen moves evaluate destination visibility as though the chat screen were closed** (P4.8), so **F1 makes an otherwise enabled destination hidden**, and unread is preserved (P4.9).
- **Suspended tabs:** receive nothing new; counts can only fall.
- Session-only.

### 4.11 Global search (Q29a, Q29b Approved; tab display semantics **P13 conditionally approved**; reading P12; regex mode Q31)
- **Source of the query:** Chat Patches' own search field. The adapter reads the live field value and Chat Patches' `caseSensitive`, `regex` and `searchEmptyShowsFullChat` settings (S24–S27). There's **no search field in Extended windows**.
- **When active:** exactly when Chat Patches' search is active: chat screen open, `config.search` on, the field shown, and a non-empty query without a regex error (S24, S25). Closing the chat screen or clearing the query restores the normal tab view. A drafted query re-applies on the next open, as Chat Patches' does.
- **Semantics:** tabs reproduce **Chat Patches' search semantics**, not D5 filter semantics. Text = `stripFormatting(processedContent.getString())`; case per Chat Patches' `caseSensitive` (default **true**, unlike filters).
  - **Plain-text mode:** `contains`, or a case-insensitive `contains`, evaluated by Extended per entry. That's a bounded literal search.
  - **Regex mode (Q31, approved):** tabs show an entry as a match **only** if it's in Chat Patches' own result list (`getSearchResults()`, identity membership).
    - **Extended never executes a regex.**
    - Results may be **incomplete** (hidden entries and later arrivals aren't in Chat Patches' list), so tabs show the approved **partial-results indicator** while regex mode is active and a tab has entries Chat Patches didn't evaluate.
    - Display-only.
    - *Mechanism (engineering):* the indicator is subtle text in the tab strip area; it's never a message or history entry.
- **Per-tab results (P13, conditionally approved, option A with both UI clarifications; runtime Unverified):**
  - Each window shows only its selected tab's matching entries, computed from **that tab's own retained history**. No tab's behaviour depends on another tab's or the main chat's results.
  - **Zero matches in a tab:**
    - `searchEmptyShowsFullChat` **false** → an empty result view with a subtle **"No matches"** indicator;
    - **true** → the tab's **complete retained history**, with no indicator.
    - The indicator appears **only** while a search is active, the tab has zero matches, and the setting is off. It's display-only, never a message or history entry.
  - **Setting changed mid-search:** affected tab views update on the next render/update cycle.
  - **Live arrivals (plain-text mode):**
    - each new message delivered to a tab is evaluated against the **current** query as it arrives;
    - matches appear in the filtered view; non-matches stay in the tab's history but out of view until the search clears;
    - Chat Patches' main-chat behaviour of showing arrivals unfiltered until the query changes (S25) is **deliberately not reproduced** (documented difference).
    - Literal matching only; **regex mode follows Q31**; no Extended regex engine.
  - **Scroll on query change:** each tab's *search-view* scroll resets to its **newest matching entry**. With no matches, it's the empty-result position. With the full-history fallback, it's the newest retained entry.
    - Scroll resets never select a tab, acknowledge unread, or change history or order.
  - **Clearing search** restores the normal unfiltered view.
  - **Unread (P4, unchanged):** a message delivered to the selected tab of a visible window is read even if the active search hides it from view. Search never changes flags or counts (P4.7).
  - *Mechanism (Proposed):* per tab, a cached match list rebuilt on query or setting change and extended incrementally on arrival, to keep per-frame cost and allocation bounded (V-13).
- **Invariants (Approved):** search doesn't modify stored entries, routing, hiding, filters or unread. It isn't routing, and it's evaluated at render time over existing entries.
- **Hit testing (Q29b):** the search field's region takes priority only while it's actually visible (§5.4).

## 5. Feasibility investigation

### 5.1 Regex safety — **Resolved for the initial release by Q15 (Literal-only); future Regex still needs approval**
- Evidence: R1–R6 and executed test X-01 in [verification.md](verification.md).
- `java.util.regex` can't be demonstrably bounded:
  - backreference patterns ran away on JDK 25;
  - a stuck thread can't be stopped (`interrupt()` is ignored; `Thread.stop()` throws `UnsupportedOperationException`);
  - a `charAt` budget isn't provably complete.
- RE2/J is linear-time but changes the regex language, and it needs our own nested-repetition size bound.
- **No engine is approved.** A future proposal must include an adversarial test plan (V-25, V-26).

### 5.2 Message-routing correctness — **Feasible; runtime Unverified**
- **Suppressing admission:** M2/M3 skip the two vanilla calls inside `addMessage` only. Chat Patches has already logged the message (S7); the 1:1 invariant (S13) holds. V-16, V-18.
- **Detecting condensation for Q3** (**P3 conditionally approved**, decisions.md §1.3c; runtime Unverified, gated on V-01, V-04, V-04b, V-04c, V-18, V-22, V-47). Two independent signals; **either one** keeps the replacement **visible in the main chat**, while it's still **delivered to tabs normally**:
  1. **Size delta:** `allMessages.size()` at M2 < `sizeAtEntry` (M1).
     - Chat Patches removes before the `GuiMessage` is built (S6), and vanilla adds only after the decision (V3).
     - Limitation: a foreign mod adding in the same call can mask a removal. That's covered by signal 2.
  2. **Identity snapshot:** at M1, the **identities** (not text) of the newest *k* `allMessages` entries.
     - *k* covers Chat Patches' full duplicate-search window and is computed **through the adapter** from `counter`, `compactChat`, `compactDistance` and vanilla `getLinesPerPage()`, following `ChatUtil.tryCondenseDupes` L656–665 (Verified):
       - `counter=false` → no condensation;
       - Compact Chat off → 1;
       - distance 0 → lines-per-page;
       - otherwise `min(compactDistance, size)`.
     - **Conservative superset (Approved requirement):** if settings are unreadable or unusual (negative or zero results, read failure), use `max(1, linesPerPage, |compactDistance|)`.
     - Always **bounded by the number of existing entries**. At M2, any snapshot entry no longer present (identity) means a removal.
  - **Diagnostic only:** the processed message's dupe-counter slot (S3) is logged for diagnostics and **never** establishes condensation on its own.
  - Evaluated under P1's single-decision M2/M3 coordination. The snapshot may be skipped when no hiding-capable tab exists, since no hide decision could then change.
  - V-04, V-04b, V-04c.
- **Orphan restoration without corrupting history:**
  - Restoration is a **reference swap** of a pre-built projected list (Q36), followed by `rescaleChat()` (V4). This preserves 1:1 and never writes to `ChatLog`, which only `modifyMessage` writes (S7), so there are no duplicate log entries.
  - Chat Patches' context-menu delete on a restored orphan removes it from `ChatLog` by equality. V-20.
- **Pruning consequences (Q22, verified from source):**
  - `ChatLog.enforceLimits` caps the saved log at `chatMaxMessages` across **all** logged messages, hidden included, dropping the oldest. The main chat's cap counts visible messages only.
  - So the main chat can hold visible messages **older than anything still in Chat Patches' log**.
  - When the restore prunes them, they're gone from the session. They're recoverable on next launch **only if** they're still within the log's window **and** `chatlog=true` **and** the log is saved (on disconnect, the save interval, or pause).
  - Whether a given message is still in the log, or saved to disk, can't be determined through public API (`ChatLog` exposes only `messageCount()`; saving is periodic). Under **Q27** (Approved), any restore that would immediately prune an **orphan** is therefore **blocked** (§4.9). Ordinary pruning is disclosed. Runtime checks V-20f, V-20g.
- **Verified safe point:** see §4.9 step 1 (P5: refuse when unsafe, never queue). `ChatLog.isRestoring()` and `isLoading()` are public static (S21). The config screen isn't a `ChatScreen`, and keybinds don't fire while the chat input has focus (V-38).

### 5.3 Restored history — **Feasible; Q16a/b Approved**
- Recognition is structural only (S3–S5), so genuine text is never stripped. Ambiguity sources and limitations are in §4.3. V-27.

### 5.4 GUI compatibility — **Feasible; Q18, Q29b, Q30, Q33 Approved; P10–P12 conditionally approved**
- **Rendering (P10, conditionally approved, option B; deferred-rendering order Unverified):**
  - **Gameplay path:** an Extended HUD element registered after `VanillaHudElements.CHAT`. It draws enabled, visible windows with the approved fading. Visibility is **explicitly gated** on `options.hideGui` (F1), Chat Visibility: Hidden and disabled windows, not left to implicit gating. It **draws nothing while a `ChatScreen` is open.**
  - **Chat-open path:** `ScreenEvents.beforeRender` for `ChatScreen` draws the windows, then calls **`GuiGraphics.nextStratum()`**. Window backgrounds **and text** must then sit behind:
    - vanilla main-chat lines;
    - the input field;
    - command suggestions;
    - Chat Patches' search field, context menus and settings panel.

    This path is drawn even under F1 (Q26) and not under Chat Visibility: Hidden.
  - **One rendering path per window per frame:**
    - **Proposed mechanism:** the HUD path decides from "is a `ChatScreen` the current screen this frame". The screen path draws only when its `beforeRender` fires for a `ChatScreen`. Both read the same frame-start visibility snapshot, so opening or closing chat switches paths without a frame of double drawing or of nothing drawn (V-55).
    - Visibility and unread semantics are unchanged (P4, Q26).
  - **Between Extended windows:** configuration order, with the last interacted on top (Q30a; persisted per Q34).
  - **Tooltips:** drawn by Extended in `ScreenEvents.afterRender`.
    - Hover detection is done **by Extended** (style under the cursor), not through vanilla's automatic hover helper.
    - Suppressed whenever the cursor is over a protected vanilla or Chat Patches region, using the same §5.4 precedence as input. If a region can't be identified reliably, the Class L conservative fallback applies.
    - Must appear in the correct frame, with no flicker, delay or leakage (V-54).
  - **P1 unchanged:** no `ChatScreen.render` mixin or other new mixin. If Fabric events can't achieve the required layering or priority, the failure goes **back to the owner** for a separate architectural decision.
- **Input handling (P11, conditionally approved, option B; runtime Unverified).** This uses Fabric's per-screen `ScreenMouseEvents.allowMouseClick/Drag/Release/Scroll` on `ChatScreen`; no new mixins, P1 unchanged.
  - **Precedence (set semantics):**
    1. If the cursor is over an **active, visible protected region**, the event **passes** to its existing vanilla or Chat Patches handler.
    2. Otherwise, if it's over an **unobstructed part of the topmost Extended window**, Extended may **consume** relevant events, and that window comes to the front (Q30a).
    3. Otherwise → pass.
  - **Protected regions:**
    - the vanilla chat input;
    - **visible** command suggestions (accessors to `suggestions`/`rect`);
    - **visible** main-chat lines and their interactive regions (`getEoEIndex ≥ 0`, S22);
    - Chat Patches context menus;
    - Chat Patches settings panels (`isMouseOverSettingsMenu`);
    - the Chat Patches search button;
    - the Chat Patches search field **when visible**, with Q29b's conservative fallback.
    - Invisible or inactive regions never intercept (Q18). Unreadable regions use the Class L conservative fallback (P9).
  - **Chat Patches context menu open:** every click **passes**, so Chat Patches can close or use its menu normally, and **that click never starts an Extended interaction**. If the open state can't be determined reliably (signals: `isMouseOver`, `isFunctional()`, `isFocused()`, Unverified), the menu is treated as **open** (conservative). Hover and tooltips follow the protected-region rule (P10).
  - **Buttons:**
    - **left** clicks on eligible, unobstructed Extended controls may start interactions;
    - **right** clicks over an unobstructed window are **consumed with no action**, so Chat Patches doesn't build a context menu for a non-main-chat area;
    - **middle and additional buttons pass through.**
  - **Scroll:** over an unobstructed window → that window scrolls; over protected regions → vanilla or Chat Patches handles it (e.g. the main chat scrolls).
  - **Focus:** a consumed event never changes screen focus; the chat input keeps its focus, text, selection and cursor.
  - **Drag capture:**
    - **left button only**;
    - may start only on an **eligible, unobstructed** control (window strip, resize grip, scrollbar, tab), never through a protected region or an occluding window;
    - once started, Extended captures **all** drag and release events until completion or cancellation, even across protected regions;
    - capture **doesn't override drop validation**;
    - **ChatScreen closing mid-drag** (Esc, chat key, any other screen transition, detected via `ScreenEvents.remove`) **cancels** it: a cancelled tab drag leaves the tab in its original window, and a cancelled window move keeps its **last committed** position;
    - window positions stay subject to on-screen clamping.
  - **Tab drops:** a release over a **protected region cancels** the move (Q33 rule 3). The complete Q33 rule set is approved (§6.2). Q21 and Q30 are unchanged.
  - **Other mods' listeners:** Fabric doesn't guarantee ordering between `allow*` listeners. A mod that consumes an event first prevents Extended from seeing it; Extended then simply doesn't act. That's consistent, but a feature may appear unresponsive (documented). If listener ordering prevents the approved behaviour, it goes **back to the owner**.
  - **Alignment:** input precedence must match P10's actual layering (V-54).
- **Q29b investigation (accessor/mixin vs. reflection):**

| Approach | Behaviour | Failure mode | Verdict |
|---|---|---|---|
| Our own `ChatScreen` mixin with `@Shadow` of Chat Patches' `showSearchBar` | Needs our mixin applied **after** Chat Patches' (priority > 2000). Shadowing another mixin's `@Unique` member is unsupported practice; if Mixin renames it on a conflict, our mixin fails to apply. | **Hard failure at game start** | Not preferred |
| `@Accessor` mixin | Accessors target the class as declared; the field doesn't exist until Chat Patches' mixin is applied | Same as above | Not preferred |
| **Reflection via the adapter** (look up the static `boolean showSearchBar` and instance `EditBox searchField` on `ChatScreen` once, cached `VarHandle`/`Field`) | Names verified in the 8.0.0 jar (S27); exact version pin (Q5); startup self-test | **Soft failure** → Q29b fallback | **Proposed** |
| Inferring from widget registration | Registration doesn't track visibility (S16) | Wrong answers | Rejected |

  Reflection is also how the adapter reads the live search query (`searchField.getValue()`), since `searchDraft` only updates on close (S25). V-40, V-46.
- **P12 (conditionally approved, option A):**
  - Handles are resolved **once at startup** by **exact name** on `ChatScreen` (the class Chat Patches' mixin merges into) and cached. **Startup self-check:** `showSearchBar` must be `static boolean`, `searchField` an **instance** field assignable to `EditBox`. No ChatScreen instance is needed or assumed.
  - **Expected vs. genuine:** a `null` `searchField` (no ChatScreen, or before `init`) means "not available", not a failure. Missing, renamed, wrong-typed or wrong-static members, and read exceptions on an initialized screen, are genuine Class Q failures.
  - **Visibility and query fail independently:**
    - a query failure with readable visibility keeps accurate hit-testing and disables only tab search;
    - a visibility failure triggers the Q29b reservation (and search integration needs visibility, so it's off too).
  - **No guessing:** no lookup by type or position.
  - **Client thread only.** Tabs refilter when the live query changes (compared once per frame).
  - Query changes never touch routing, hiding, history or unread.
  - Q31 regex behaviour unchanged; no Extended regex engine.
  - **No P1 change, no new mixins.** A failure that also affects a Class S dependency follows the safety-critical rules (P9).
  - **Amendment A-P12-1 (owner-approved, Phase 5 correction):** a third handle, instance `searchError` on `ChatScreen` (declared type `java.util.regex.PatternSyntaxException`, compared by name; verified with `javap` on the 8.0.0 jar: set when `Pattern.compile` fails in regex mode, cleared to null on a successful compile, in non-regex mode and for an empty query; while set, Chat Patches skips its result update and shows its full chat). Same exact-name, instance and F-1 ambiguity checks; any failure is Class Q. Read once per frame only in regex mode with an active query; a non-null value that isn't an `IllegalArgumentException` is an interpretation failure (Class Q). Display fallback: every tab shows its full history with an "Invalid search expression" label; Chat Patches' stale result list is never used. Read through a backend seam for fault injection.
- **Drag-and-drop (Q30b–e Approved):**
  - valid target = the topmost visible, unobstructed strip of another window;
  - a drop on the source window does nothing;
  - hidden or disabled windows can't receive drops;
  - an empty source window remains;
  - drop resolution follows **Q33** (approved; §6.2).
  - No drag or drop triggers orphan restoration.

### 5.5 Suspended tabs × orphaned messages — **Resolved by Q17**
Read-only history keeps hidden messages accessible, so suspension needs no restore.

### 5.6 Remaining open items
**No blocking owner questions.** Q37 (runtime-suspension recovery) is owner-reserved and non-blocking; the baseline is relaunch-only.

## 6. Rendering, interaction and GUI (D8, Q6, Q11, Q18–Q21, Q30, Q33 Approved; P10, P11, P15 conditionally approved; runtime Unverified)

### 6.1 Rendering
- **Gameplay:** each enabled window shows its selected tab's newest lines, fading by `addedTime` (restored lines start faded, V-11), with chat opacity, scale and spacing. **Not drawn** under F1 (Q26), Chat Visibility: Hidden, or for disabled windows. Under "Commands only", vanilla drops player chat before `addMessage`.
- **Chat open:** full opacity, tab strip with unread badges and error markers (Q17), scrollbar, resize grip. **Drawn even under F1** (Q26); not drawn under Chat Visibility: Hidden. A global search filter (§4.11) applies.
- **Layers and paths (P10, conditionally approved):** gameplay uses the HUD element (explicit F1, Chat Visibility and disabled gating; inactive while chat is open). Chat open uses `beforeRender` plus `nextStratum()`, beneath all protected UI. Tooltips are drawn by Extended in `afterRender`, with protected-region suppression. Exactly one path per window per frame (§5.4).
- **Wrapping:** `GuiMessage.splitLines`, cached per (record, width). **Geometry:** screen fractions plus an anchor, clamped at render time.

### 6.2 Interaction while chat is open (D8, Q21, Q30)
- Scroll a window's body, select tabs, move windows by dragging the strip background, resize from the grip. Any interaction brings the window to the front (Q30a).
- Text click and hover reuse vanilla's handlers.
- **Tab drag-and-drop:**
  - drop on another window's topmost visible, unobstructed strip → **move** (Q30b);
  - drop on the source → nothing;
  - **drop resolution (Q33, approved; first match wins):**
    1. topmost visible, unobstructed destination strip → **move**;
    2. source window → **no-op**;
    3. protected vanilla or Chat Patches region → **cancel**;
    4. another window's body or an obscured strip → **cancel**;
    5. empty space, including coordinates occupied only by hidden or disabled windows → **create a new on-screen window** containing the tab;
    6. Esc or ChatScreen closing during the drag → **cancel**.
  - *Proposed placement details for rule 5 (not part of Q33):* sized like the source window; enabled; new ID; clamped fully on-screen and to the minimum size; preferably positioned so its strip isn't covered by a protected region. These details must never make rule 5 fail to create the window.
  - In the new window (empty and visible), the moved tab is selected and its unread clears (Q25).
  - Identity, filters, entries and unread flags are preserved, except for the Q25 empty-visible-destination rule.
  - **Never** triggers orphan restoration.
  - Leaving the source empty keeps it (Q30e).

### 6.3 Custom configuration GUI (Q6, Q20)
- Vanilla `Screen` subclasses only: window list, tab editor, filter editor (Literal; regex filters shown as unsupported), move-tab control, confirmation dialogs. **No YACL.**
- Opened from an **optional** Mod Menu entrypoint (`suggests: modmenu`) or the key mapping `key.cpextended.open_config` (P15): registered through `fabric-key-binding-api-v1`, **unbound by default** (Q20), polled on client tick.
  - **Category:** a custom **"Chat Patches Extended"** Controls category (K-A) if compatible. In 1.21.11 that's a `KeyMapping.Category` built from an `Identifier`; vanilla's own registration helper is private (Verified), so Fabric's handling of a custom category is **Unverified** (V-38).
  - If it fails verification, use vanilla **Misc** (K-B fallback) **without** a further owner decision.
  - No edit-mode keybind (D8).
- Works **in-world only**, since vanilla processes key mappings only with no screen open (A-3; V-38). The mod is fully usable without Mod Menu once the key is bound.

## 7. Configuration (D7 + Q6 + Q8 + Q14.1)

### 7.1 Single source of truth and persistence (P14, conditionally approved, option B; mechanisms *Proposed* where marked; runtime Unverified)
- **Files (Approved):**
  - `config/cpextended.json`: the active configuration, and the **only** persisted Extended state. No message history, hidden-message database or durable unread state is ever persisted (D3, F1, P4).
  - `cpextended.json.bak`: the **last-good** backup. **Amended in RC.2 (decisions §1.3d-1):** every successful save promotes the replaced version when it's valid and reconciled; it also protects Q36's ordinary-save fallback. Ordering and crash generations: `docs/rc2-p14-backup.md`.
  - `cpextended.json.corrupt-<timestamp>`: a **preserved copy of an unparseable original**. This replaces the earlier `.bak-<timestamp>` naming.
  - The two backup purposes are kept distinct.
- **States:**
  - **Normal:** saves allowed. Every save follows the external-edit check below, then Q36's atomic path (or, if atomic replacement is unsupported, the ordinary-save fallback; orphan-producing operations are refused).
  - **No-save** (Approved): entered when the file is **unparseable** or has a **newer incompatible `schemaVersion`**.
    - The original file is **preserved untouched**. *Proposed:* a byte copy is also written to `.corrupt-<timestamp>` for unparseable files; the primary file is left in place so the player can repair it there.
    - **All** writes, automatic and manual, are blocked.
    - Extended runs **read-only** with safely available settings:
      - for a newer schema, the fields it understands;
      - for an unparseable file, the last-good `.bak` if it parses, otherwise defaults.
    - That fallback **never** grants permission to write.
    - Leaving the state requires the player to **repair the file** (detected by the external-edit check) or to **explicitly approve replacement**, e.g. "Restore from backup" or "Replace with current settings". A replacement that would delete tabs holding hidden messages goes through P5/P6/Q27/C-1 (refused, or confirmed atomically).
  - **Divergence lock** (Approved): entered when on-disk changes are detected but **can't be applied** (declined, refused under P5/P6/Q27/C-1, incompatible, or invalid).
    - In-memory state and the external file are both preserved.
    - **All saves are blocked**, including delayed automatic saves from dragging, resizing or stacking.
    - Two reconciliation actions:
      - **Apply file:** reload and validate the external file and apply it in-memory **atomically**. If orphan-producing, this uses P5's prepare and reference-swap commit; the file is already the source, so no write. On any failure, nothing changes and the lock stays.
      - **Overwrite file with current settings:** explicit confirmation, then the normal atomic save. In-memory tabs are kept, so this can't orphan.
    - A failed reconciliation never partially applies a configuration or clears the lock.
- **Save paths (Q36, retained):**
  - **Atomic path:** temp file in the same directory, flushed, `ATOMIC_MOVE` replace. RC.2: the replaced version is staged as the next backup before the replace and moved over `.bak` only after it (a failed commit leaves both files unchanged).
  - **Atomic replacement unavailable:** orphan-producing operations are **refused**.
  - **Ordinary saves** (geometry, filters, non-orphaning tab changes) use the backup-protected non-atomic fallback (*mechanism Proposed*):
    1. write the current (valid, reconciled) version to `cpextended.json.bak` and flush; a failure aborts the save;
    2. write the new content directly and flush;
    3. keep the `.bak` until the next successful save.
  - **Transient failures** (e.g. Windows file locks) get one retry, then a "not saved" message; the model stays dirty.
  - Every path runs the external-edit check first.
- **External-edit detection** (Approved requirement; mechanism *Proposed*):
  - Extended keeps the **SHA-256 digest and byte length** of the exact file version it last loaded or wrote.
  - **When:** at startup (establishing the baseline), when the config screen opens, and **immediately before every attempted save** (manual or automatic). The modification time is only a cheap hint and is **never** relied on alone, so two edits within one timestamp tick, or an unchanged timestamp, are still detected.
  - **Content changed:** the save is blocked; the file is re-read and validated:
    - valid, non-orphaning → applied, then the pending save is re-evaluated against the new baseline;
    - otherwise → divergence lock.
  - **File missing** at a later check, though it existed at the baseline → treated as an external change ("file was removed"): divergence lock; Apply file = adopt defaults (subject to P5/P6/Q27/C-1); Overwrite = recreate.
  - **File missing at startup** → defaults, and the first save creates it (nothing to lose).
  - **File replaced** (e.g. an editor's atomic rename) → detected by content like any other change.
  - **Unreadable** (I/O or permission error during the check) → the change can't be ruled out, so the save is **blocked**, with a message. It's retried at the next save attempt; no lock is set unless the content later proves different.
  - **Concurrent modification, residual race (documented, not eliminated):** between the final digest check and Q36's atomic rename, an external write landing in that short window (milliseconds) would be **overwritten**. Java offers no portable compare-and-swap rename. *Mitigation (Proposed):* the check runs immediately before the rename, after the temp file is fully written and flushed, keeping the window minimal. This risk is listed as **unverified** and not treated as closed. The ordinary-save fallback has the same race over its slightly longer direct write.
- **Raw JSON preservation** (Approved requirement; mechanism *Proposed*):
  - The parsed model keeps a reference to the **raw JSON tree** (Gson `JsonObject`, insertion-ordered) it came from.
  - **On save,** Extended starts from that raw tree and writes **only recognized fields that the player intentionally changed** (dirty-tracked per object). Unknown keys, untouched invalid filters (exact mode string and pattern, P8/Q28), duplicate-ID entries, and unrecognized or suspended structures are written back **as stored**.
  - **Deleted objects** are removed from the tree; **moved tabs** carry their raw subtree, unknown keys included.
  - **No reactivation:** raw-preserved invalid or duplicate entries are re-validated on every load and stay **suspended and flagged**. Preservation never turns them into an active configuration.
  - **Formatting:** whitespace and indentation aren't guaranteed (pretty-printed on write); key order follows the raw tree.
- **Player information (*Proposed*, bounded like P9):**
  - a toast at the moment no-save or divergence begins;
  - a **persistent banner** in the config screen ("Saving disabled: …", naming the file and backup involved, with the reconciliation or recovery buttons);
  - a small save-lock icon on window strips while chat is open;
  - log entries naming `.corrupt-<timestamp>` or `.bak` when used.
- **Retained:** schema versioning; nested window/tab ownership (Q8); duplicate-ID validation and suspension; unknown-`selectedTab` fallback; render-time clamping without file writes; debounced geometry saves **when saves are permitted**; Q36 atomic path and fallback. **Stacking order persists across restarts** (Q34, approved), saved with the geometry debounce, subject to the no-save and divergence locks.

### 7.2 Schema (draft; tabs nested in their owning window, which enforces Q8)

```jsonc
{
  "schemaVersion": 1,
  "general": { "enabled": true, "hidingEnabled": true },
  "appearance": { "backgroundOpacity": null, "textOpacity": null, "scale": null, "lineSpacing": null },
  "windows": [
    { "id": "w-main-side", "enabled": true, "anchor": "TOP_LEFT",
      "x": 0.01, "y": 0.10, "width": 0.30, "height": 0.25, "selectedTab": "staff",
      "tabs": [
        { "id": "staff", "name": "Staff", "enabled": true, "hideFromMainChat": true,
          "restoreHistory": true,                                              // Q4, per tab
          "matchAll": false,
          "include": [ { "mode": "literal", "pattern": "[SC]",  "caseSensitive": false } ],
          "exclude": [ { "mode": "regex",   "pattern": "^\\[SC\\] \\[bot\\]" } ] }  // mode default literal, caseSensitive default false
      ] }
  ]
}
```

- **Both GUI and file:** everything above. The GUI covers windows, tabs (including moves, Q21), filters (mode, pattern, case), hide and restore toggles, and appearance.
- **File-only:** nothing is required to be file-only. Hand editing remains supported (D7).

### 7.3 Validation and error handling (no silent repair)

| Problem | Handling |
|---|---|
| Unparseable JSON | **No-save state** (P14): original preserved (plus a `.corrupt-<timestamp>` copy, *Proposed*); read-only fallback (`.bak` if valid, else defaults); toast, banner, log; writing requires repair or explicit replacement |
| Invalid filter (empty, over 1024 characters, unknown mode, `regex` in this release) | Flagged; tab suspended with errors shown (Q14, Q17); **never executed** (Q28). **Saving (Q28):** valid unrelated changes, including geometry, stacking, other tabs and moving the tab, may be saved; **untouched** invalid filters are written back unchanged (raw mode string, pattern and any extra fields). Saving a **new or modified** invalid filter is blocked with inline errors. Deleting an invalid filter, or fixing it into a valid one, is allowed. |
| Duplicate window/tab IDs; `selectedTab` not in its window | The later duplicate is suspended and flagged and **preserved as raw JSON** on unrelated saves (P14); an unknown `selectedTab` shows the first tab; the file isn't rewritten |
| Out-of-range geometry | Clamped at render time; file kept until the user saves |
| Unknown keys | Ignored for behaviour, logged, and **preserved** via the raw JSON tree on every save (P14) |
| `schemaVersion` newer than supported | **No-save state** (P14): understood fields used read-only; warning; replacement only by explicit approval |
| External edit not applicable (declined, refused, incompatible, invalid) | **Divergence lock** (P14): all saves blocked; Apply file / Overwrite file |

## 8. Compatibility & dependencies (D11, Q5, F4, Q6)

### 8.1 Metadata (P15, conditionally approved: M-B)
```jsonc
{
  "schemaVersion": 1,
  "id": "cpextended", "name": "Chat Patches Extended", "environment": "client",
  "authors": ["Neptune"],
  "license": "LGPL-3.0-only",
  "description": "Unofficial companion for Chat Patches … not affiliated with or endorsed by the Chat Patches project.",
  "depends": {
    "java": ">=21",
    "minecraft": "=1.21.11",                                    // mandatory exact pin (D11)
    "chatpatches": "=8.0.0",                                    // mandatory exact pin (Q5)
    "fabricloader": ">=<tested>",                               // lower bound at the tested version (0.19.5 planned)
    "fabric-api-base": ">=<tested>", "fabric-lifecycle-events-v1": ">=<tested>", "fabric-screen-api-v1": ">=<tested>",
    "fabric-rendering-v1": ">=<tested>", "fabric-key-binding-api-v1": ">=<tested>", "fabric-resource-loader-v0": ">=<tested>"
  },
  "suggests": { "modmenu": "*" },
  "entrypoints": { "client": ["…CpExtended"], "modmenu": ["…ModMenuEntry"] },
  "mixins": ["cpextended.mixins.json"]
  // "contact": { homepage / issues / sources } — UNSET until supplied by the owner; never invented (P15)
}
```
- `<tested>` = the module or Loader version actually used in verification (planned baseline: Loader 0.19.5; the module versions in Fabric API 0.141.6+1.21.11). Newer versions are accepted by the lower bound but **not described as verified** unless tested (P15, V-64).
- **Fabric API modules:** only those actually used are declared (finalized against real imports, an engineering task; V-64).
- **Exact pins:** Chat Patches' published version string is `8.0.0` with no build suffix (Verified S20). On Chat Patches ≠ 8.0.0 or Minecraft ≠ 1.21.11, Fabric Loader refuses to launch (V-35).
- **No YACL** (Q6). **No `breaks`/`conflicts`** (Chat Patches already declares `morechathistory`, S20).

### 8.2 Adapter (F4)

Each adapter operation has a **failure class** (P9, conditionally approved):
- **S (safety-critical):** an unreliable result enters **session-wide degraded mode**.
- **L (local fallback):** an already-approved conservative fallback applies; **no** degraded mode.
- **Q (search-only):** the P9 search-only exception.

| Call | Chat Patches member | Use | Class | Genuine failure | Valid unusual values (never degrade) |
|---|---|---|---|---|---|
| `restoredTag()` | `ChatLog.RESTORED_INDICATOR` | Origin of restored messages | **S** | link/reflection error; `null` | — |
| `vanillaClearing()` | `ChatPatches.config.vanillaClearing` | Clear sync of tabs and hidden references | **S** | read error | `true` or `false` |
| `chatMaxMessages()` | `ChatPatches.config.chatMaxMessages` | Hidden cap (P7), Q27 projection (P6, C-1) | **S** | read error; value **≤ 0** | any integer ≥ 1, including values outside Chat Patches' UI range (UI constraint 1–32767, Verified `@IntConstraints`; hand-edited files may exceed it) |
| `isBoundaryLine(c)` | `Boundary.isBoundaryLine` | Q10 exemption | **S** | link error, or an exception on a well-formed component | — |
| `condenseWindow()` | `config.counter`, `compactChat`, `compactDistance` (+ vanilla `getLinesPerPage`) | Q3 snapshot size (P3) | **L**: P3 conservative superset | read error | `counter=false`; `compactDistance` negative, 0, or above the UI max of 50 (Verified `@IntConstraints(max = 50)`; hand-edits may exceed it) |
| `isLoading()` / `isRestoring()` | `ChatLog.isLoading()`, `isRestoring()` | P5 safe point | **L**: the operation is refused at that moment (as C-1) | read error | — |
| `isOverChatPatchesUi(screen,x,y)` | `ChatScreenAccess.getContextMenu()`, `isMouseOverSettingsMenu` | Hit test | **L**: treat as Chat Patches region | exception | — |
| `mainChatLineAt(x,y)` | `ChatComponentAccess.getEoEIndex` | Hit test (Q18) | **L**: treat the main-chat area as protected | exception | `-1` (no line) |
| `isSearchFieldShown(screen)` (**visibility**, P12) | Cached exact-name handle for static `boolean ChatScreen.showSearchBar`, plus public `config.search` | Q29b/P11 hit-testing | **Q** (visibility part): Q29b conservative reservation while `config.search` | handle missing, renamed, wrong type, or not static; read exception | `false` (bar hidden); `config.search=false` |
| `searchQuery(screen)` (**query**, P12) | Cached exact-name handle for instance `EditBox ChatScreen.searchField` → `getValue()`; public `config.caseSensitive/regex/searchEmptyShowsFullChat`; `ChatScreenAccess.getSearchResults()` | Q29a/Q31 tab search | **Q** (query part): search integration off; visibility hit-testing unaffected if still readable | handle missing, renamed, wrong type, or static; read exception on an **initialized** screen | field `null` before `init` or with no ChatScreen (= "not available", **not** a failure); empty query; invalid Chat Patches regex (S24) |
| (structure constants) | `ChatUtil.TIMESTAMP_INDEX/MESSAGE_INDEX/DUPE_INDEX` | P2 | n/a | compile-time constants (inlined by `javac`); no runtime read | — |

- *Removed:* the former `nameFormat()` row. It's no longer used, since Q16a forbids name-format reversal.
- **Self-check:** each operation is exercised once at startup, and its result validated against the "genuine failure" column. Later calls apply the same validation.
  - A Class S failure, at startup or mid-session, enters degraded mode.
  - Unusual-but-valid values (rightmost column) **never** trigger degraded mode.
- **Search-only exception (P9):** a Class Q failure disables **only** Extended's search integration for the rest of the session (no retries). Q29b's conservative field-region reservation applies, and routing, hiding, history and unread continue when their own dependencies are healthy. **The exception doesn't apply** if the failing member is also needed for safe routing, hiding or history. None of the Class Q members is, by the table above.
- **Degraded mode** (session-wide, **one-way until relaunch**):

| # | Effect | Approved by |
|---|---|---|
| 1 | **No new hiding:** newly arriving messages always go to the main chat | P7 (cap failure); **P9** (all Class S failures) |
| 2 | **Existing hidden history preserved** and accessible in its tabs; never discarded; tab functionality not disabled | P7; P9 |
| 3 | Hidden cap fallback **16,384** when the cap is unreadable | **P7** |
| 4 | **Safe routing to tabs continues** (Literal filters on original text need no Chat Patches internals) | P7; P9 |
| 5 | Restored messages whose origin or structure can't be trusted are **AMBIGUOUS**: routed, **never hidden** | **P9** (with Q16b) |
| 6 | **Conservative hit-testing** around Chat Patches UI regions | **P9** (with Q29b) |
| 7 | Orphan-producing deletions **refused**; state unchanged; keep/disable/move named; no "wait" advice; unrelated changes allowed | **C-1** |
| 8 | Bounded player warning (below) | **P9** |

- **Warnings (P9, Approved):** per **distinct failure**:
  - one toast and one diagnostic log entry when it first occurs;
  - at most **one** more player warning, on the next chat-screen opening;
  - no repeated toasts for the same failure (repeats are counted in the log at debug level only).
  - **Distinct failure key** (*Proposed*): (adapter operation or tab ID, failure kind, exception class). A new key always gets its own warning, so unrelated failures are never suppressed by an earlier one. Degraded-mode entry is its own key.

### 8.3 Mixin surface

| Target | Ours | Chat Patches | Interaction |
|---|---|---|---|
| `ChatComponent.addMessage(C,S,T)` | `@WrapMethod` | HEAD `@Inject` (cancel), HEAD `@ModifyVariable` | Chat Patches runs inside our wrapper (V-01, V-02) |
| `addMessageToDisplayQueue` / `addMessageToQueue` calls | `@WrapWithCondition` | none on the calls | Independent |
| `ChatComponent.clearMessages` | `@WrapMethod` | HEAD `@Inject` (cancel) | V-06 |
| Accessors/invokers | `ChatComponent.allMessages` (getter; plus `@Mutable` setter, Q36 swap only); `ChatScreen.handleComponentClicked`, `commandSuggestions`; `CommandSuggestions.suggestions`; `CommandSuggestions$SuggestionsList.rect` | n/a | None |

### 8.4 Incompatibilities
- **Known:** `morechathistory` (via Chat Patches).
- **Likely incompatible (untested):** mods that replace the chat HUD or `ChatComponent` storage, or `@Redirect` the wrapped calls.
- **Risk from the Q36 reference swap:** mods that **cache** the `allMessages` list object would keep a stale list after an orphan restoration (V-20n).
- **To test:** Chat Heads, No Chat Reports, Mod Menu.

### 8.5 Licensing (D12)
Not legal advice.
- Ship `LICENSE` (LGPL-3.0) and `COPYING` (GPL-3.0); SPDX headers.
- No Chat Patches or Mojang code is to be copied. Chat Patches' `VersionUtil` contains copied Mojang code, so don't replicate it.
- If a future Regex approval adopts RE2/J (not approved; Q15 is Literal-only), it's BSD-3-Clause and compatible. It would be bundled via Loom `include`, and its notice shipped.
- Fabric API (Apache-2.0) and MixinExtras (MIT) are used as APIs.

---

## 9. Phased implementation plan (Proposed sequencing; not authorized)

| Phase | Scope | Open dependencies |
|---|---|---|
| 0 Scaffold | Loom with Mojang mappings, Q13 identifiers, P15 metadata, LICENSE/COPYING, dev runtime with Chat Patches 8.0.0 | authorization |
| S1 Spike: capture and Chat Patches integration (separate authorization) | Throwaway build: V-01, V-02, V-05, V-06, V-15, V-35, V-47, V-03, V-27f, V-04c, V-20m, V-59, V-36 | authorization |
| S2 Spike: UI layering and input (separate authorization) | Throwaway build: V-53, V-55, V-39, V-07, V-57, V-58, V-54 | authorization |
| 1 Capture & routing (headless) | M1–M4, accessors, adapter (classes S/L/Q), `SessionMirror`, Literal filters, runtime guard, `Reconciler`, debug dump | S1 results |
| 2 Config file & read-only windows | Schema, validation, P14 states (no-save, divergence lock, fingerprint, raw preservation), Q28/P8 rules, HUD rendering | S1 results |
| 3 Chat-open interaction & unread | P10 rendering, P11 input, Q30a/Q34 stacking, tab selection, P4 unread flags | S2 results |
| 4 Custom GUI & tab moves | Editors, keybind (P15), Mod Menu entry, config-screen moves, drag-and-drop (Q33) | S2 results |
| 5 Global search | Q29a/P12/P13 over tabs; Chat Patches regex mode via Q31 (result-list membership, no Extended regex) | S1 results (V-59) |
| 6 Restored history into tabs | P2 reconstruction, per-tab restore (Q4) | S1 results (V-27f) |
| 7 Hiding & orphan restore | D2 conditions, P3 detection, P5/Q36 commit, P6/C-1, P7 cap | S1 results |
| 8 Compatibility release | Full [verification.md](verification.md); release-time metadata inputs (decisions.md §2.3) | owner inputs |
| — Regex filters | **Deferred (Q15)** | future approval |

## 10. Readiness (2026-10-18)
- **Fully specified (design):** all phases, including global search in both plain-text and Chat Patches regex modes (Q31).
- **Blocking owner decisions:** **none.** Open but non-blocking: Q37 (baseline relaunch-only). Release-only input: contact URLs (decisions.md §2.3).
- **Must be verified before implementation relies on the design:** Phase 0 spikes **S1** and **S2** (verification.md §0, plan §13), then the **Q36 commit checkpoint** before Phase 7.
- **Not authorized:** scaffold creation, executable code, builds, Minecraft launches, spikes and implementation. Each needs explicit owner authorization.

## 11. Reference: AdvancedChatHUD
AdvancedChatHUD (last release for 1.19.4) replaces the vanilla chat HUD, so it can't coexist with Chat Patches. Only UX ideas are borrowed; no code.

---

## 12. Inventory of Proposed (unapproved) specifications

These were the design choices the document relied on. **Q35 is complete:** each item below is approved by the owner (P4), conditionally approved (P1–P3, P5–P14), or decided under delegated engineering authority (P15).

| # | Proposal | Where |
|---|---|---|
| P1 | Capture design M1–M4 (`@WrapMethod` / `@WrapWithCondition`), accessors, no mixins into Chat Patches — **Conditionally approved** (Q35, decisions.md §1.3c): gated on V-01, V-02, V-05, V-06, V-15; M2/M3 coordination; `SuggestionsList.rect` accessor; **amended by Q36**: + `@Accessor @Mutable` setter for `allMessages` (reference swap only) | §3, §4.1, §8.3 |
| P2 | Restored-message structural algorithm (three siblings, digit-insertion marker) — **Conditionally approved** (decisions.md §1.3c): gated on V-03, V-27, V-27b–f, V-31 | §4.3 |
| P3 | Q3 detection signals (size delta plus identity snapshot) — **Conditionally approved** (decisions.md §1.3c): adapter-computed window with conservative superset, identity comparison, counter diagnostic only; gated on V-01, V-04, V-04b, V-04c, V-18, V-22, V-47 | §5.2 |
| P4 | Per-entry unread flags; config-screen visibility evaluated as "chat closed" — **Approved** (decisions.md §1.3c); runtime behaviour Unverified (V-33–V-33g, V-41, V-41d, V-46b, V-22, V-47, V-04, V-06) | §4.10 |
| P5 | Orphan safe-point definition; insertion by capture sequence number; window deletion only when empty or with explicit tab deletion — **Conditionally approved** (decisions.md §1.3c): refusal instead of queueing; atomic plan/commit with rollback; residual risk Q36; gated on V-20, V-20b–d, V-20f–l, V-45c, V-18, V-22, V-47; **commit mechanism amended by Q36** (prepare → reference swap → atomic replace → reference rollback; post-commit rebuild retry) | §4.9 |
| P6 | Q27 conservative rule: no recoverable copy is ever considered verified — **Conditionally approved, option A** (decisions.md §1.3c): refuse if any orphan would be pruned; alternatives explained; no "wait" advice; gated on V-20g (extended), V-20l, V-20c | §4.9 |
| P7 | Hidden-reference list capped separately at `chatMaxMessages` — **Conditionally approved, option A + fallback (i)** (decisions.md §1.3c): oldest-first, removed from all tabs with flags, main chat untouched, live cap changes without resurrection, degraded fallback 16,384; gated on V-48, V-13, V-23, V-33g, V-44, V-06 | §4.7, §8.2 |
| P8 | 1024-character limit measured in UTF-16 code units — **Conditionally approved, option E** (decisions.md §1.3c): no normalization; GUI read-only display plus Replace for over-length patterns; exact preservation of stored patterns; vanilla input limits documented; gated on V-25c–g, V-21, V-21d, V-37 | §4.4 |
| P9 | Runtime guard (exception → no-match + suspension) and adapter degraded mode — **Conditionally approved, option A with search-only exception** (decisions.md §1.3c): whole-tab runtime suspension until relaunch; adapter failure classes S/L/Q; one-way degraded mode; bounded warnings; gated on V-25b, V-36, V-40, V-44, V-46e, V-48, V-49, V-50, V-51, V-52 | §4.4, §8.2 |
| P10 | Rendering layers (HUD in gameplay, `beforeRender` with chat open, tooltips in `afterRender`) — **Conditionally approved, option B** (decisions.md §1.3c): explicit gating, `nextStratum()` separation, Extended-side hover with suppression, one path per frame; **no P1 change**; gated on V-07, V-08, V-09d, V-11, V-12, V-13, V-33e, V-39, V-53–V-56 | §5.4, §6.1 |
| P11 | Input precedence order and drag pointer capture — **Conditionally approved, option B** (decisions.md §1.3c): protected-region-first set rule; context-menu click-through; left-only interactions and drags, right-click consumed, others pass; scroll and focus rules; capture with cancel on screen close; no P1 change; gated on V-09–V-09e, V-28, V-40, V-41c, V-41e, V-54, V-57, V-58 | §5.4 |
| P12 | Reflection for search-field visibility and query — **Conditionally approved, option A** (decisions.md §1.3c): cached exact-name handles, type and static validation, safe self-check, independent visibility/query failure handling, no guessing, no P1 change; gated on V-59, V-40, V-46, V-46c, V-46e, V-51, V-52 | §5.4, §8.2 |
| P13 | Global-search tab semantics: per-tab `searchEmptyShowsFullChat`; live evaluation of new arrivals — **Conditionally approved, option A with both UI clarifications** (decisions.md §1.3c): "No matches" indicator (setting off only); scroll reset to newest match; display-only; gated on V-46, V-46b, V-46c, V-13 (+ P12/Q29/Q31 gates) | §4.11 |
| P14 | Config file path, schema, atomic save, newer-file reload, backup on unparseable JSON, read-only on newer schema, duplicate-ID suspension, render-time clamping — **Conditionally approved, option B** (decisions.md §1.3c): no-save and divergence-lock states, content-fingerprint detection, raw preservation, `.bak` / `.corrupt-<timestamp>` naming; residual check-to-rename race documented; gated on V-21b–e, V-12, V-34b, V-20l, V-25e, V-25f, V-60–V-63 | §7 |
| P15 | Metadata values (`depends` exact pin, `suggests` modmenu), keybind registration — **Conditionally approved** (owner; supersedes ED-3): M-B metadata with author `Neptune`, `LGPL-3.0-only`, `=` pins, individual modules, Loader/API lower bounds; keybind K-A (custom category) with K-B (Misc) fallback; URLs unset; gated on V-14, V-15, V-35, V-38, V-64 | §8.1, §6.3 |

---

## 13. Phase 0 execution plan (prepared 2026-10-18; **not authorized to execute**)

Phase 0 = a minimal, throwaway feasibility build plus spikes **S1** and **S2**. It produces evidence, not features: no complete GUI, no full routing, no persistence beyond what a test needs.

### 13.1 Prerequisites (environment)
- **Repository:** `git init` this folder (it isn't a repository yet), then run the spike on a branch `spike/phase0`, so docs and spike are versioned and the spike can be dropped cleanly.
- **JDK 21:** Temurin 21.0.8, already installed (checked read-only), set as the Gradle toolchain. JDK 25 is also present, but Minecraft 1.21.11 targets Java 21.
- **Gradle:** wrapper on the cached Gradle 9.7.1 if Loom supports it; otherwise the version Loom requires (an engineering choice at execution).
- **Fabric Loom:** a release supporting 1.21.11 (Chat Patches builds with Loom 1.18-SNAPSHOT); prefer a non-snapshot if one exists.
- **Mappings:** Mojang official + Parchment `2025.12.20` (as Chat Patches).
- **Dependencies:**
  - Minecraft 1.21.11; Fabric Loader 0.19.5;
  - Fabric API 0.141.6+1.21.11 modules: `fabric-lifecycle-events-v1`, `fabric-screen-api-v1`, `fabric-rendering-v1`, plus **spike-only** `fabric-command-api-v2` (debug commands) and `fabric-client-gametest-api-v1` (automated scenarios and screenshots; module present in the local cache);
  - Chat Patches `8.0.0+1.21.11-fabric` from the Modrinth maven (`modCompileOnly` + `modLocalRuntime`);
  - YACL `3.8.2+1.21.11` (`modLocalRuntime`, required by Chat Patches).
- **Network access** is needed once, for the Mojang, Fabric and Modrinth downloads.
- **Run modes:** Loom `runClient` (integrated server; singleplayer covers all S1 cases) and `runClientGameTest` (scripted scenarios and screenshots). No dedicated server is needed for Phase 0.

### 13.2 Spike scaffold layout (isolated; never merged)
```
spikes/phase0/                     separate Gradle project; mod id "cpx_spike" (distinct from cpextended)
  build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew + wrapper
  src/main/resources/fabric.mod.json          depends: minecraft =1.21.11, chatpatches =8.0.0 (exercises V-35)
  src/main/resources/cpx_spike.mixins.json
  src/main/java/io/github/itznotneptune/cpxspike/
    SpikeMod.java                 client entrypoint; registers probes and /cpxspike client commands
    SpikeLog.java                 one-line JSON evidence records, prefixed "[CPX-SPIKE]"
    s1/ChatComponentSpikeMixin.java   M1–M4 with production-shaped targets, probe-only bodies
    s1/ChatComponentAccessorSpike.java  allMessages getter + @Mutable setter (Q36 swap)
    s1/SpikeAdapter.java          resolves and self-checks every §8.2 member (incl. P12 reflection)
    s1/Probes.java                structure dump (V-27f), condensation ground truth (V-04c), list swap and swap-back (V-20m)
    s2/SpikeWindow.java           ONE fake window: HUD path, beforeRender path (+ nextStratum toggle), afterRender tooltip
    s2/InputSpike.java            ScreenMouseEvents.allow* + protected-region hit test + decision log
    s2/SuggestionsAccessors.java  CommandSuggestions.suggestions, SuggestionsList.rect; ChatScreen invoker
    s2/InstrumentationMixin.java  SPIKE-ONLY call counters on ChatScreen.mouseClicked/mouseScrolled (V-57 evidence)
  src/gametest/java/...           scripted S1/S2 scenarios + screenshots
  testmods/foreign-listener/      allowMouseClick consumer (V-58) + in-call allMessages adder (V-04c case 3)
  testmods/conflict-field/        adds a ChatScreen.showSearchBar field (V-59 case 4)
  tools/make_chatlog.py           generates a large logs/chatlog.json for V-02
  stubs/                          fabric.mod.json-only jars "chatpatches" 8.0.1 / 7.x for V-35 negatives
  evidence/                       retained logs, screenshots, mixin exports, chatlog diffs
  RESULTS.md                      pass/fail per test with evidence links
```
The spike-only pieces (instrumentation mixin, debug commands, gametests, test mods, stubs) **never** enter the production project. They exist only to observe behaviour.

### 13.3 Spike S1: message capture and Chat Patches integration
**Minimum code:**
- M1–M4 injectors on the approved targets, logging (frame ID, original text, origin, size, snapshot, decision);
- a debug "hide" predicate (messages containing `[HIDE]`) to exercise M2/M3 skipping;
- adapter resolution and self-check;
- three probes;
- `/cpxspike swap | swapback | dump | fault <member>` commands.

| Test | Pass criterion (objective) | Evidence |
|---|---|---|
| **V-01** | For every captured message, M1's text has **no** Chat Patches timestamp structure, while M3's processed component does (digit insertion on sibling 0). The exported `ChatComponent` class (`-Dmixin.debug.export=true`) shows Chat Patches' injectors **inside** the wrapped body. | `[CPX-SPIKE]` capture lines; `.mixin.out` class dump |
| V-02 | With a 2,000-entry generated `chatlog.json` and uniquely numbered messages sent during load, each message is captured **exactly once**, after replay | capture counts per message ID |
| V-03 | Restored captures = restored entries; no live message tagged restored | counts |
| V-05 | M2 invocations outside an `addMessage` frame (rescale, Chat Patches search) have **no effect** (no decision, no record) | frame-less M2 counter |
| V-06 | F3+D and disconnect × `vanillaClearing` on/off: the adapter's clear rule equals the observed `allMessages` emptiness in **every** case | clear log |
| V-47 | Exactly one decision per frame; M2 and M3 return equal values; delivery once; a nested `addMessage` gets its own frame; foreign partial suppression is detected | decision log |
| **V-27f** | Live vs. restored component trees (root contents, sibling count, sibling 0 digit insertion, sibling 1 text) are **identical** for player, system, plugin, boundary and `x3` messages | structure dumps (before and after relaunch) |
| **V-04c** | Extended's detection equals the **ground truth** (identity diff of `allMessages` across Chat Patches' processing) in all cases: Compact Chat 5/0/500/−5, `counter=false`, foreign in-call add, restored `x3`, forced read failure | detection vs. truth table |
| **V-20m** (Q36 subset) | After `swap` (pre-built copy + one test message in sequence order, setter swap, `rescaleChat()`): Chat Patches' context-menu copy/delete target the clicked message; search (plain and regex) is correct; condensation removes from the new list; a world switch handles the boundary; `chatlog.json` gains **no** duplicates. `swapback` restores the **same** object identities. | screenshots, chatlog diff, identity hashes |
| **V-59** | Both fields resolve at startup with no ChatScreen; no false failure before `init`; the `conflict-field` test mod and a wrong-name flag produce the Class Q fallback, no crash | self-check log |
| V-15 | Builds with only declared modules; self-check passes | build log |
| **V-35** | Loader refuses each stub `chatpatches` version and the missing case, naming `=8.0.0`; no partial start | launcher logs |
| V-36 (partial) | Fault injection per adapter member gives Class S → degraded mode, Class L → local fallback only. The real stub-jar variant is deferred to Phase 8. | fault log |

### 13.4 Spike S2: rendering, layering and mouse input
**Minimum code:**
- one fake window with a unique-colour background (e.g. magenta) and text (bright green), drawn via the HUD path in gameplay and the `beforeRender` path with chat open;
- a `nextStratum()` on/off toggle;
- an `afterRender` tooltip driven by Extended's own hover detection;
- `allow*` handlers with the P11 protected set;
- a per-frame path log.

| Test | Pass criterion (objective) | Evidence |
|---|---|---|
| **V-53** | With `nextStratum()`, screenshots show **zero** magenta or green pixels inside the bounds of: visible main-chat lines, the input box, open suggestions, the shown search field, the open context menu, the settings panel. The without-toggle run is recorded for comparison. | screenshots + pixel-scan report |
| **V-55** | Over 50 scripted chat open/close transitions (F1 on and off), each frame logs **exactly one** draw of the window when it should be visible, and none when hidden | per-frame path log |
| V-39 | Gameplay+F1: no draw; chat+F1: draw; Chat Visibility Hidden: no draw in either state; disabled: no draw | path log |
| V-07 | The tooltip draw is logged in the **same frame** as hover detection; the screenshot shows the tooltip | frame log, screenshot |
| **V-57** | For consumed events, the instrumentation counters for vanilla `ChatScreen.mouseClicked`/`mouseScrolled` stay **0**, and no Chat Patches effect occurs (no context menu, no search-field focus); passed events increment normally | counter log |
| **V-58** | With `foreign-listener` consuming first: Extended doesn't act and doesn't error. Observing only: Extended works. Both registration orders. | event log |
| V-54 | Over a 20×20 sample grid per overlap scene, the recorded topmost visual equals the input recipient at **every** point | grid report |

### 13.5 Later critical checkpoint: Q36 commit (before Phase 7)
S1 covers only the Chat Patches-compatibility part of the reference swap (V-20m, plus swap-back identity). The full **Q36 commit** stays a **critical checkpoint, C-Q36**, to run before Phase 7: **V-20l** (all six cases, incl. rename failure → reference rollback), **V-20p**, **V-20n**, **V-62** (residual race measurement), **V-21e** (non-atomic file system).

### 13.6 Retention, cleanup, isolation
- **Retained:** `spikes/phase0/evidence/` (logs, screenshots, mixin exports, diffs) and `RESULTS.md`, plus an executed-test entry per spike test in verification.md §1 (X-02 onward) with date, environment and outcome.
- **Cleanup:** after results are recorded, tag the spike branch (`spike/phase0-results`) and **remove** `spikes/phase0/` from the main line. Run directories (`run/`, worlds, generated chat logs) are deleted.
- **Status (2026-10-18):** done. The spike, its evidence and `RESULTS.md` are preserved at tag `spike/phase0-results` (branch `spike/phase0`); `spikes/phase0/` is removed from `main`, and the run directories are deleted.
- **Isolation:** the production project (Phase 1) starts fresh under its own Gradle project and mod ID `cpextended`. Spike code may be read as reference but is **never** copied or merged, especially the instrumentation mixin, debug commands and test mods.

### 13.7 Conditions that return to the owner (architectural decisions)
| Condition | Affects |
|---|---|
| **V-01 fails**: M1 doesn't see the pre-Chat Patches text | P1, D5 capture design |
| V-02 / V-47: duplicate or partial capture that can't be fixed within P1's injectors | P1 |
| **V-27f fails**: restored structure differs from live | P2, Q1 |
| **V-20m fails**: Chat Patches misbehaves after the list swap, or the log is duplicated | Q36, P5 |
| **V-53 fails** even with `nextStratum()`, or **V-55** shows unavoidable double or missing frames | P10 (would need a non-approved `ChatScreen` mixin) |
| **V-57 fails**: cancelling doesn't stop vanilla or Chat Patches handling; or **V-58** ordering prevents the approved behaviour | P11 |
| V-35: Fabric can't enforce the exact pins | Q5, P15 |
| V-59 shows visibility permanently unreadable on the pinned build (the Q29b reservation would become permanent player-visible behaviour) | P12, Q29b (report) |

Engineering-level outcomes (e.g. picking a Loom or Gradle version, fixing a probe, adjusting the hit-test geometry) are resolved without escalation and recorded in `RESULTS.md`.
