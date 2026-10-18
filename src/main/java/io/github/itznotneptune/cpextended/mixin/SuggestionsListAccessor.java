// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Approved P1 accessor (§3; P1 condition 3): the open suggestion list's bounds. */
@Mixin(CommandSuggestions.SuggestionsList.class)
public interface SuggestionsListAccessor {
	@Accessor("rect")
	Rect2i cpextended$rect();
}
