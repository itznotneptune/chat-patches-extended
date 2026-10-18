// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.compat;

import net.minecraft.client.GuiMessageTag;
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
}
