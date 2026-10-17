package io.github.itznotneptune.cpxspike.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** P11 vanilla accessors (input box, command suggestions). */
public final class S2AccessorsSpike {
	private S2AccessorsSpike() {}

	@Mixin(ChatScreen.class)
	public interface ChatScreenAcc {
		@Accessor("input")
		EditBox cpx$input();

		@Accessor("commandSuggestions")
		CommandSuggestions cpx$commandSuggestions();
	}

	@Mixin(CommandSuggestions.class)
	public interface CommandSuggestionsAcc {
		@Accessor("suggestions")
		CommandSuggestions.SuggestionsList cpx$suggestions();
	}

	@Mixin(CommandSuggestions.SuggestionsList.class)
	public interface SuggestionsListAcc {
		@Accessor("rect")
		net.minecraft.client.renderer.Rect2i cpx$rect();
	}
}
