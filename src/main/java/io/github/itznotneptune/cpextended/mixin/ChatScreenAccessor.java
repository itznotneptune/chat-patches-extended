// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Approved P1 accessor inventory (architecture §3, §8.3): text clicks in Extended windows reuse vanilla's handler, and
 * the command-suggestion list is a protected region (Q18).
 */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
	@Invoker("handleComponentClicked")
	boolean cpextended$handleComponentClicked(Style style, boolean insert);

	@Accessor("commandSuggestions")
	CommandSuggestions cpextended$commandSuggestions();
}
