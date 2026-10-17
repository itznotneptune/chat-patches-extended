package io.github.itznotneptune.cpxspike.test.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** V-59 case 4a: another mod adds a same-named, same-typed static field to ChatScreen. */
@Mixin(ChatScreen.class)
public abstract class ConflictBooleanMixin {
	@Unique private static boolean showSearchBar = false;
}
