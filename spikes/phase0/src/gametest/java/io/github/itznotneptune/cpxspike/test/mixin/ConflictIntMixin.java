package io.github.itznotneptune.cpxspike.test.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** V-59 case 4b: another mod adds a same-named field of a different type to ChatScreen. */
@Mixin(ChatScreen.class)
public abstract class ConflictIntMixin {
	@Unique private static int showSearchBar = 7;
}
