# Chat Patches Extended 1.0.0-rc.2: private-beta handoff (Windows)

For hands-on testing on your own Windows PC only. Don't upload the jar or post it publicly; it's an unpublished release candidate. Nothing here changes the candidate: it's the RC.2 jar exactly as verified (`verification/rc2/README.md`).

**Note:** RC.2 has never run on Windows or on the Java runtime bundled with the Minecraft launcher; that's part of what this beta tests. The PowerShell commands below use standard cmdlets but weren't executed by me (no PowerShell on the build machine).

## 1. What to install

| File | Version | Where to get it | SHA-256 |
|---|---|---|---|
| **Chat Patches Extended** | `cpextended-1.0.0-rc.2.jar` | `verification/rc2/release-candidate/` in this repo (copy it to the PC by USB, a private share, etc.) | `58ebb1fbcea8538d239ef45cbe8ae29b0a0fae3f6ed9aa88567b39e61b47dff0` |
| Chat Patches | `chatpatches-8.0.0+1.21.11-fabric.jar` (exactly 8.0.0) | Modrinth, Chat Patches, version 8.0.0 for 1.21.11 Fabric | `68431ec8f64ebdd279c2351aee0c5a5fe2058ebe2728959917ec5b0bacbd899d` |
| YetAnotherConfigLib (required by Chat Patches) | `yacl-3.8.2+1.21.11-fabric.jar` | Modrinth, YACL, 3.8.2+1.21.11-fabric | `49c279bc28c80ca17d6c21c3dd299ec1c115b892272f21b3f034ed782829babd` |
| Fabric API | `fabric-api-0.141.6+1.21.11.jar` | Modrinth or CurseForge, Fabric API 0.141.6+1.21.11 | `bdff7fd7e220085cfad2ff9b1f40dde6534ae0b96cf378f97a374bc54cb9ed0f` |
| Fabric Loader | 0.19.5 | Fabric installer (fabricmc.net/use/installer) | n/a |
| *Optional:* Mod Menu | `modmenu-17.0.1.jar` | Modrinth, Mod Menu 17.0.1 | `2eb2d6db9964fcf2e44e2be1e4d172b8c4e829e385c8f31636de94a1708c2076` |

- **Hashes:** the dependency hashes are those of the exact files used in RC.2 testing, fetched from Modrinth's Maven. Check each download in PowerShell:

  ```powershell
  Get-FileHash .\cpextended-1.0.0-rc.2.jar -Algorithm SHA256
  ```

  If a hash doesn't match, don't use the file.
- **Versions:** Minecraft must be **1.21.11** and Chat Patches **exactly 8.0.0**. Any other version is refused at startup by design. Java 21 or newer: the launcher's bundled runtime is fine.
- **Start minimal:** only the files above at first. Add your usual mods later, one at a time, so a problem can be traced to one mod. Chat Heads 1.3.2 and No Chat Reports v2.18.0 were tested with RC.2 in the development and production-style runs.

## 2. Installation: a separate profile with its own game directory

The key safety step is a **separate game directory**. Mods, settings, Chat Patches' chat log and Extended's settings all live inside the game directory, so a separate one can't touch your normal installation.

1. **Create the folder.** Make a new, empty folder for the beta, for example `C:\Users\<you>\AppData\Roaming\.minecraft-cpx-beta`. Paste `%APPDATA%` into Explorer's address bar to get to `Roaming`.
2. **Install Fabric Loader.** Run the Fabric installer → **Client** tab → Minecraft **1.21.11**, Loader **0.19.5**.
   - **Untick "Create profile"**, so the installer doesn't create or overwrite a launcher profile.
   - Leave the install location at the default (your launcher's `.minecraft`); this only installs the loader version files.
   - Click **Install**.
3. **Create the profile.** In the Minecraft Launcher → **Installations** → **New installation**:
   - **Name:** `CPX beta`.
   - **Version:** `fabric-loader-0.19.5-1.21.11` (enable modded versions in the list if it isn't shown).
   - **Game directory:** **Browse** → the new folder from step 1.
   - Create.
4. **First launch.** Launch `CPX beta` once without mods, reach the title screen, then quit. This creates the folder structure.
5. **Add the mods.** Copy the jars from section 1 into `<beta folder>\mods`.
6. **Launch `CPX beta`.**
   - The title screen should load normally.
   - `config\cpextended.json` appears in the beta folder.
   - Optional: with Mod Menu, the Mods list shows "Chat Patches Extended 1.0.0-rc.2".
7. **Bind the settings key.** The settings key is **unbound by default**. Options → Controls → Key Binds → category **Chat Patches Extended** → **Open configuration** → pick a key (for example K). With Mod Menu you can also open it from the mod's config button.

## 3. Keeping your existing Chat Patches settings and history safe
- **Never point the beta profile at your normal `.minecraft`,** and never add the RC.2 jar to your normal profile's `mods`.
- **What lives where** (all relative to the profile's game directory):
  - Chat Patches settings: `config\chatpatches.json`.
  - Chat Patches chat history: `logs\chatlog.json`.
  - Extended: `config\cpextended.json`, `config\cpextended.json.bak`, and `config\cpextended.json.corrupt-<time>` (only if a damaged file was found).
- **Extended writes only its own `cpextended.json*` files.** It never writes `chatpatches.json` or `chatlog.json`. Chat Patches itself still saves its log as usual, including messages Extended hides from the main chat.
- **Testing with your real history (optional):** quit the game first, then **copy** (not move) your normal profile's `logs\chatlog.json` into `<beta folder>\logs\`. Your original stays untouched. Keep in mind this puts your real chat in the beta folder, which matters for bug reports (section 5).
- **Before each session,** zip the beta folder's `config` and `logs` folders. This gives you a restore point for the beta itself.
- **Never run two copies of the game on the same game directory.**

## 4. Hands-on checklist

Use a **singleplayer world with cheats on**. Make every test message distinctive (for example `beta apple 1`), so you never need real chat in screenshots or reports.

| # | Area | Do | Expect |
|---|---|---|---|
| 1 | First launch | Open the settings (your key) | Empty screen explaining windows and tabs, with **Add window** |
| 2 | Window creation | Add window → Add tab → name it `Apples` → **+ Include** `apple` → Save | List shows "Window 1: Apples"; a window appears on screen |
| 3 | Filters | Add an **Exclude** `rotten`. Send `beta apple 1`, `beta rotten apple`, `beta pear` | Only `beta apple 1` appears in Apples |
| 4 | Case | Hover the `aa` button on a filter, click it (`Aa`), save; send `BETA APPLE 2` | Tooltip explains both modes; with `Aa`, the uppercase message doesn't match |
| 5 | Hiding | In the tab, turn **Hide from main chat** on (read the tooltip); send `beta apple 3` | Message shown only in the tab, not the main chat. Settings → **Hiding: Off**, then send another: stays visible in both |
| 6 | Unread | Add a second tab `All` with **All: On**; select it; send `beta apple 4` | Apples shows an unread count; clicking Apples clears it |
| 7 | Tab relocation | Open chat (T); drag the window by its tab strip; resize from the corner; drag a tab onto empty space | Window moves and resizes; the tab becomes its own window with its history; positions survive a relaunch |
| 8 | Search | In chat, type `apple` in Chat Patches' search field; then an invalid regex in regex mode, e.g. `([bad` | Windows filter to matches; invalid regex shows "Invalid search expression" |
| 9 | Restored history | Send a few `beta apple` messages; quit to desktop; relaunch; join a world | Matching messages restored into Apples (and hidden if the tab hides) |
| 10 | Deleting a tab with hidden messages | Delete Apples while it holds hidden messages | A confirmation naming **"Apples"** with a count. **No** changes nothing; **Yes** puts the messages back in the main chat in order |
| 11 | Backup | Make two settings changes, then look in `config\` | `cpextended.json.bak` exists and contains your settings from **before** the last change |
| 12 | Recovery | Quit. Open `cpextended.json` in Notepad, delete its last few lines, save. Launch, open settings | Warning that saving is disabled; banner explains it (Details… for the full text); **Restore from backup…** is enabled; confirm. Settings are back, saving works, and the damaged file is kept as `cpextended.json.corrupt-<time>` |
| 13 | Hand edit while running | With the game running, rename a tab in Notepad and save; open settings | The edit is applied, not overwritten |
| 14 | Disconnect / reconnect | Leave the world and rejoin (or a server you control) | Tabs keep their history; routing continues |

Also note anything confusing, unclear wording, clipped text, or windows covering chat. Usability observations are as useful as defects; record them separately.

## 5. Bug reports without exposing private chat
- **Include:**
  - what you did, step by step;
  - what you expected and what happened;
  - the time it happened;
  - versions (RC.2 hash, Loader, Fabric API, Chat Patches, other mods);
  - a screenshot that shows only test messages.
- **The game log** (`<beta folder>\logs\latest.log`): Minecraft writes every chat line into it, including hidden ones, marked `[CHAT]`. Never share it raw. Make a filtered copy with `verification/rc2/beta/redact-log.ps1`:

  ```powershell
  powershell -ExecutionPolicy Bypass -File redact-log.ps1 -Log "$env:APPDATA\.minecraft-cpx-beta\logs\latest.log" -Player "YourMinecraftName"
  ```

  - **What it does:** removes `[CHAT]` lines and the "Setting user" line, and replaces your Windows and Minecraft names and server addresses with placeholders.
  - **Read the result before sharing.** Search it for names and chat text.
  - Older sessions are in `logs\*.log.gz`. Extract them first, then filter the same way.
- **Extended's own log lines** contain no chat text, only states, file names, counts and tab or window ids. They start with or mention `cpextended`, e.g. `Saving disabled: …`, `cpextended.json restored from …`. Don't enable `-Dcpextended.debug=true` unless asked; it's verbose (it still logs counts and ids, not text).
- **`config\cpextended.json`** contains your filter patterns, which may include player names. Replace private patterns with `x` before sharing, or describe the structure instead.
- **Crash reports** (`crash-reports\*.txt`) normally contain no chat, but read them before sharing.
- **Never share** `logs\chatlog.json` (your full chat history) or raw `latest.log`.

## 6. Stop testing immediately if…
These could mean message or configuration loss. Stop the session, don't relaunch yet, and **first copy** `config\cpextended.json*` and `logs\` (`chatlog.json`, `latest.log`) to a safe folder: a relaunch can rewrite them.

1. **A message disappears from everywhere,** neither in the main chat nor in any tab, when it should have been in at least one. This matters especially after hiding, deleting a tab or window, restoring a backup, or relaunching.
2. **A tab or window holding hidden messages is deleted without the confirmation,** or the confirmation's count doesn't match what then happens.
3. **Choosing No or Cancel in any confirmation still changes something.**
4. **Settings change on their own:** `cpextended.json` is emptied, reset to defaults, or loses settings without you choosing **Replace file**; or a hand edit is silently overwritten or reformatted.
5. **The backup holds the wrong thing:** `cpextended.json.bak` contains broken content or the *current* settings right after a change; or **Restore from backup** restores something other than your previous settings.
6. **Saving continues while the warning says saving is disabled** (the file keeps changing in that state).
7. **A crash or freeze** whose crash report or log mentions `cpextended` or `chatpatches`.
8. **Chat Patches' history is lost:** `chatlog.json` shrinks drastically or loses history after a relaunch, beyond its normal limit (16,384 messages by default).

Not stop-worthy, but report: wrong routing, clipped text, a window drawn over something, a repeated "Settings were not saved" toast (possibly Windows antivirus file locking), slow chat.

## 7. Rolling back
- **Stop using RC.2:** quit the game and remove `cpextended-1.0.0-rc.2.jar` from `<beta folder>\mods`, or just stop launching `CPX beta`.
  - Chat Patches keeps working normally.
  - Messages that were hidden are in Chat Patches' log, so they appear in the main chat at the next launch (within Chat Patches' limit).
- **Your normal installation needs nothing:** with the separate game directory it was never touched.
- **Return the beta to a known state:** quit, then replace `<beta folder>\config` and `<beta folder>\logs` with the zip from section 3. Or delete the beta folder entirely; nothing else depends on it.
- **Extended's leftover files** (`cpextended.json`, `.bak`, `.corrupt-*`) are harmless with the jar removed. Keep them if you're reporting a bug.
- **If the jar ever ended up in your normal profile:** remove it from `mods`. Extended never writes `chatpatches.json` or `chatlog.json`. The one in-game history change it can make is the **disclosed** pruning of older visible messages after a confirmed tab deletion, which can't be undone by rolling back. Restore `logs\chatlog.json` from a backup if you have one.
- **Remove the profile:** Installations → `CPX beta` → … → Delete. This removes only the launcher entry, not the folder.
