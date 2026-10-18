// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Approved P1 accessor (§3): the open suggestion list, or null when none is shown (Q18). */
@Mixin(CommandSuggestions.class)
public interface CommandSuggestionsAccessor {
	@Accessor("suggestions")
	CommandSuggestions.SuggestionsList cpextended$suggestions();
}
