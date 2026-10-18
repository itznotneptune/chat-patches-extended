# Chat Patches Extended 1.0.0-rc.1 (release candidate, not published)

**Target:** Minecraft 1.21.11 · Fabric · Chat Patches 8.0.0 · Java 21+. Client-side. Unofficial; not affiliated with Chat Patches.

## Features
- Independent chat windows with named tabs, each with literal include/exclude filters, shown during gameplay and with chat open.
- Moving and resizing windows; tab drag-and-drop between windows or into a new window; scrolling tab strips.
- Per-tab unread counts.
- Optional hiding of routed messages from the main chat, with safety rules (below).
- Per-tab restored history from Chat Patches' saved log.
- Chat Patches search filters every window (plain text, and regex through Chat Patches' own results).
- A settings screen (key binding, or Mod Menu) with recovery tools for a damaged or externally edited settings file.

## What you should know about hidden messages
1. **Hidden messages are kept only for the current session**, in their tabs, up to Chat Patches' history limit. They aren't saved by Extended.
2. **Chat Patches' chat log is not a guaranteed recovery copy.** It logs hidden messages and restores what it holds at the next start, but its own limit and saving behavior decide what survives. In 8.0.0, its log stops saving new messages once it's full and its message count stays the same.
3. **Deleting a tab that holds hidden messages moves them back to the main chat**, after a confirmation. If the main chat would exceed its limit, the confirmation states how many **older visible messages will be pruned**; those may not be recoverable.
4. **That deletion is refused, with nothing changed, if any of the hidden messages can't safely survive it.** It's also refused while the chat is busy, or when Chat Patches' history limit can't be read. Keeping, disabling or moving the tab are always safe alternatives.
5. **Settings file safety:** changes are written to a temporary file and moved into place. A deletion that moves hidden messages back is refused on file systems without atomic file replacement. An external program writing `cpextended.json` within the fraction of a millisecond between Extended's last check and its save can be overwritten; this race remains.

These guarantees cover failures inside a running game. After a crash or a forced quit, the settings file is either the old or the new version (verified by interrupting the process at each step), and the session state is rebuilt from the file plus Chat Patches' restore. Extended doesn't claim protection against power loss or file-system faults beyond what's described here.

## Compatibility (tested)
Tested with Chat Heads 1.3.2, No Chat Reports Fabric-1.21.11-v2.18.0 and Mod Menu 17.0.1 (Text Placeholder API 2.8.2), separately and together, plus a vanilla 1.21.11 dedicated server. See `verification/phase8/` for details and the untested scenarios.

## Known limitations
See the README, *Known limitations*. In short:
- Filters match text only.
- Chat Heads changes the matched text of player messages.
- Custom Chat Patches name formats apply to restored messages.
- A tab suspended after an error recovers only after a restart.
