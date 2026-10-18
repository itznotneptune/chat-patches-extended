# Chat Patches Extended

An **unofficial** companion mod for [Chat Patches](https://modrinth.com/mod/chatpatches) that sorts chat messages into separate windows and tabs. Not affiliated with or endorsed by the Chat Patches project.

Author: Neptune. License: LGPL-3.0-only (see `LICENSE` and `COPYING`).

## Requirements

| | Version |
|---|---|
| Minecraft | **1.21.11** (exactly) |
| Mod loader | Fabric Loader 0.19.5 or newer |
| Fabric API | 0.141.6+1.21.11 or newer (lifecycle events, rendering, screen API, key bindings) |
| Chat Patches | **8.0.0** for Fabric 1.21.11 (exactly), with its own dependencies (YACL) |
| Java | 21 or newer |
| Mod Menu | optional (adds a settings button) |

Client-side only. Other Minecraft or Chat Patches versions aren't supported, and the game refuses to start with them.

## Opening the settings
- Bind **"Open Chat Patches Extended settings"** in Options → Controls → Key Binds. It's in the "Chat Patches Extended" category at the end of the list and is unbound by default.
- Or, with Mod Menu installed, use the mod's settings button.

## Windows and tabs
- **Add window** creates an empty window. A window holds one or more tabs; the selected tab's messages are shown.
- In a window, **Add tab** creates a tab. Each tab can be renamed and has:
  - **Enabled**: a disabled tab keeps receiving its messages but isn't shown in the strip.
  - **Hide from main chat**: see *Hiding messages* below.
  - **Restored history**: whether messages Chat Patches restores from its log when you join a world go into this tab.
  - **All**: receive every message (when the tab has no include filters).
  - **Include / Exclude filters**: a message goes into the tab if any include filter matches (or the tab has none and "All" is on) and no exclude filter matches.
- **Filters are literal text**: the pattern must appear in the message exactly; no wildcards or regular expressions. Each filter is case-insensitive unless you turn case sensitivity on. Patterns are up to 1,024 characters. A filter applies to new messages only.
- A tab with an invalid filter (for example an empty pattern edited into the file) is **suspended**: its history stays visible but it receives nothing new. The settings show why.

## Using windows in game
- **With chat closed**, windows show their newest lines, fading like the vanilla chat. They're hidden by F1, by Chat Visibility: Hidden, and when disabled.
- **With chat open** (the chat input box stays focused):
  - **Click a tab** to select it; selecting it clears its unread count.
  - **Drag the strip background** to move a window; **drag the bottom-right grip** to resize it. Positions are saved.
  - **Scroll** with the mouse wheel or the scrollbar. Each window scrolls independently.
  - **Drag a tab** onto another window's tab strip to move it there. Drop it on empty space to put it in a new window. Dropping on the chat input, on Chat Patches' controls or on another window's body cancels, and so does Esc.
  - When tabs don't fit, use the strip's **arrows** or the wheel over the strip. An arrow turns yellow if a tab scrolled out of view has unread messages, and red if one is suspended.
- Clicking a window brings it to the front. Clicks on the chat input, Chat Patches' search field, buttons and context menu always go to them, never to a window.

## Unread indicators
A tab counts messages that arrived while it wasn't selected, or while its window was hidden (disabled, F1, Chat Visibility). The count clears when you select the tab, or when its window becomes visible while the tab is selected. Restored history never counts as unread.

## Hiding messages safely
Turning on **Hide from main chat** for a tab keeps matching messages out of the main chat; you read them in that tab.
- **A message is hidden only when it's safe.** All of these must hold:
  - at least one enabled, working tab in an enabled window with "Hide from main chat" receives it;
  - the global **Hiding** switch (main settings screen) is on.

  Otherwise it stays in the main chat:
  - messages that only disabled or suspended tabs, or tabs in disabled windows, would receive;
  - Chat Patches' world-join boundary lines;
  - a message that replaces a duplicate already visible in the main chat;
  - restored messages whose format can't be recognized reliably;
  - every message while Extended reports a compatibility problem with Chat Patches (the settings then show **Hiding: Paused**).
- **Hidden messages are kept for this session only**, in their tabs, up to Chat Patches' chat history limit (`chatMaxMessages`, 16,384 by default). When the limit is exceeded, the oldest hidden messages are dropped from the tabs. Leaving a world with Chat Patches' "vanilla clearing" on, or clearing the chat (F3+D), also clears them.
- **Chat Patches' chat log is not a guaranteed backup.** Chat Patches still logs hidden messages and restores what its log holds when you start the game again. Its log has its own limit, and Extended doesn't rely on it.
- **Turning hiding off, disabling a tab or window, or moving a tab never loses hidden messages.** They stay in their tab.
- **Deleting a tab** that holds hidden messages no other tab holds asks first. Those messages go back into the main chat in their original order.
  - If that would push the main chat over its limit, the confirmation says how many **older visible messages will be pruned**; they may not be recoverable.
  - If even one of the hidden messages couldn't survive (it's older than everything the main chat keeps), the deletion is **refused** and nothing changes. You can keep, disable or move the tab instead.
  - Deletions are also refused while the chat is open, loading or restoring, or while Chat Patches' history limit can't be read.

## Restored history
When you join a world after starting the game, Chat Patches restores its saved chat. Tabs with **Restored history** on receive the restored messages that match their filters:
- Chat Patches' timestamp and duplicate counter are ignored for matching.
- The message text, including saved name formatting, is matched as it was saved.
- Messages in an unrecognized format still go to matching tabs but are never hidden.
- **Restored messages are routed by the tabs as they are now.** A message that stayed visible in the main chat when it first arrived (for example while hiding was off, or before a tab existed) can be hidden after a restart, when a tab with **Restored history** and **Hide from main chat** matches it. The reverse also happens: a message hidden last session comes back visible if no hiding tab restores it. Chat Patches' log is the only source; Extended never saved which messages were hidden.

## Search
Chat Patches' search field (in the chat screen) also filters every window, each in its own tab history.
- Plain search uses Chat Patches' settings (case sensitivity, "show full chat when nothing matches"). A tab with no matches says "No matches".
- In **regex mode**, Extended shows only Chat Patches' own results. Its results don't include hidden messages or messages that arrived after the search, so a window may say **"Partial results"**. An invalid expression shows **"Invalid search expression"** and the full history.
- Search never changes routing, hiding, history or unread counts.

## Settings file, backups and recovery
- **Files:**
  - `config/cpextended.json`: the settings in use. It's safe to edit by hand; the game detects edits by content.
  - `config/cpextended.json.bak`: the **last-good backup**, your settings as they were **before the most recent change saved in game**. Each successful save moves the version it replaces into `.bak`, byte for byte (hand edits and unknown entries included).
  - `config/cpextended.json.corrupt-<time>`: a copy of a file that couldn't be read.
- **When the backup is updated:** only when the version being replaced is valid and is the one the game loaded or saved (or a hand edit it already applied). A broken file, a file from a newer version, or an outside edit the game hasn't applied never becomes the backup; the older backup is kept instead. There's no backup before your first saved change, and none if you only ever edited the file by hand.
- If the file **can't be used** (broken, or from a newer version), saving is disabled and the settings run read-only (from a valid backup if there is one). The settings screen offers, each after confirmation:
  - **Restore from backup**: writes `.bak` back as `cpextended.json` and uses it. The backup is checked first; if it's missing, damaged or from a newer version, the button is unavailable and says why. Deleting tabs through a restore follows the same hidden-message rules as deleting them by hand (confirmation, or refusal).
  - **Replace file**: writes the settings currently in use as `cpextended.json`.
- **What the backup doesn't cover:** it's one generation old and contains settings only (never messages). After a crash or failure at the wrong moment it may be one generation older than expected, and a power loss or file-system fault can undo a recent save. It's a recovery aid, not a guarantee.
- If the file was **changed outside the game** in a way that can't be applied, saving is blocked until you choose **Apply file** or **Overwrite file**.
- Messages are never written to this file. Only settings are.
- **Known limitation:** if another program writes the file in the fraction of a millisecond between Extended's last check and its save, that external change is overwritten.

## Known limitations
- **Matching is text only.** Players are recognized only by how their name appears in the message. Rank prefixes, nicknames and custom chat formats change that text. Translated server messages match in your client's language.
- **Chat Heads:** the matched text of player messages includes Chat Heads' head placeholder (for example `<[Steve head]SteveSteve> …`), so a filter like `<Steve>` doesn't match. Filter on the message content or `Steve>` instead.
- **Chat Patches custom name format:** restored messages keep the custom name format (for example `[Steve]`). Filters written for `<Steve>` don't match them.
- **Mods that keep their own reference** to the main chat's message list may not see messages moved back by a tab deletion.
- **A tab suspended after an error** stays suspended until you restart the game.
- **Chat Patches 8.0.0's own log** stops saving new messages once it's at its limit and the message count no longer changes. That's Chat Patches' behavior.
