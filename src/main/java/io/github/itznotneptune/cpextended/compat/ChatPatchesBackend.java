// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.compat;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/**
 * The raw Chat Patches reads behind {@link ChatPatchesAdapter}. Only {@link RealBackend} touches Chat Patches classes;
 * validation and failure classes live in the adapter. Development builds may substitute a backend to inject faults (V-36).
 */
public interface ChatPatchesBackend {
	GuiMessageTag restoredIndicator();
	boolean vanillaClearing();
	int chatMaxMessages();
	boolean isBoundaryLine(Component component);
	boolean counter();
	boolean compactChat();
	int compactDistance();
	boolean isLoading();
	boolean isRestoring();
	boolean searchEnabled();

	// Hit-testing (Class L, architecture §8.2)
	/** Whether the screen's Chat Patches context menu exists and is open. */
	boolean contextMenuOpen(ChatScreen screen);
	boolean overContextMenu(ChatScreen screen, double x, double y);
	boolean overSettingsMenu(ChatScreen screen, double x, double y);
	/** {@code ChatComponentAccess.getEoEIndex}: the main-chat message under the point, or -1. */
	int mainChatLineAt(double x, double y);

	// Search settings and results (Class Q, Q29a/Q31)
	boolean searchCaseSensitive();
	boolean searchRegex();
	boolean searchEmptyShowsFullChat();
	/** {@code ChatScreenAccess.getSearchResults()}: Chat Patches' current main-chat search results. */
	java.util.List<net.minecraft.client.GuiMessage> searchResults(ChatScreen screen);
	/** Reads the resolved {@code searchError} handle (P12 amendment); a seam for fault injection. */
	Object searchError(java.lang.reflect.Field handle, ChatScreen screen) throws ReflectiveOperationException;
}
