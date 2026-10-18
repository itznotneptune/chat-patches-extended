// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.compat;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.network.chat.Component;
import obro1961.chatpatches.Boundary;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;

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
}
