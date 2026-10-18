// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** TEST ONLY (V-59 case 4): another mod adding a same-named field, which makes Mixin rename Chat Patches' copy. */
@Mixin(ChatScreen.class)
public abstract class ConflictBooleanMixin {
	@Unique
	private static boolean showSearchBar = false;
}
