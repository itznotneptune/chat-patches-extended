// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.compat;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import obro1961.chatpatches.Boundary;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;
import obro1961.chatpatches.accessor.ChatComponentAccess;
import obro1961.chatpatches.accessor.ChatScreenAccess;

/** Direct reads of Chat Patches 8.0.0 public members (architecture §8.2). Linkage errors surface to the adapter. */
final class RealBackend implements ChatPatchesBackend {
	@Override public GuiMessageTag restoredIndicator() { return ChatLog.RESTORED_INDICATOR; }
	@Override public boolean vanillaClearing() { return ChatPatches.config.vanillaClearing; }
	@Override public int chatMaxMessages() { return ChatPatches.config.chatMaxMessages; }
	@Override public boolean isBoundaryLine(Component component) { return Boundary.isBoundaryLine(component); }
	@Override public boolean counter() { return ChatPatches.config.counter; }
	@Override public boolean compactChat() { return ChatPatches.config.compactChat; }
	@Override public int compactDistance() { return ChatPatches.config.compactDistance; }
	@Override public boolean isLoading() { return ChatLog.isLoading(); }
	@Override public boolean isRestoring() { return ChatLog.isRestoring(); }
	@Override public boolean searchEnabled() { return ChatPatches.config.search; }

	@Override public boolean contextMenuOpen(ChatScreen screen) {
		var menu = ((ChatScreenAccess) screen).getContextMenu();
		return menu != null && menu.isFunctional();
	}
	@Override public boolean overContextMenu(ChatScreen screen, double x, double y) {
		var menu = ((ChatScreenAccess) screen).getContextMenu();
		return menu != null && menu.isFunctional() && menu.isMouseOver(x, y);
	}
	@Override public boolean overSettingsMenu(ChatScreen screen, double x, double y) { return ((ChatScreenAccess) screen).isMouseOverSettingsMenu(x, y); }
	@Override public int mainChatLineAt(double x, double y) { return ((ChatComponentAccess) Minecraft.getInstance().gui.getChat()).getEoEIndex(x, y); }
	@Override public boolean searchCaseSensitive() { return ChatPatches.config.caseSensitive; }
	@Override public boolean searchRegex() { return ChatPatches.config.regex; }
	@Override public boolean searchEmptyShowsFullChat() { return ChatPatches.config.searchEmptyShowsFullChat; }
	@Override public java.util.List<net.minecraft.client.GuiMessage> searchResults(ChatScreen screen) { return ((ChatScreenAccess) screen).getSearchResults(); }

	@Override
	public Object searchError(java.lang.reflect.Field handle, ChatScreen screen) throws ReflectiveOperationException {
		return handle.get(screen);
	}
}
