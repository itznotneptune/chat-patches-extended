// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Access to the main chat's message list: the getter for liveness (P1), and the {@code @Mutable} setter approved by
 * Q36's P1 amendment for the orphan-restoration reference swap only (never for anything else).
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("allMessages")
	List<GuiMessage> cpextended$getAllMessages();

	/** Q36 only: swaps in the prepared list during the orphan-restoration commit, and back on rollback. */
	@Accessor("allMessages")
	@Mutable
	void cpextended$setAllMessages(List<GuiMessage> messages);
}
