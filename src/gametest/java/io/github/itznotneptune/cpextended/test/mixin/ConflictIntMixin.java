// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** TEST ONLY (V-59 case 4): a same-named field of a different type. */
@Mixin(ChatScreen.class)
public abstract class ConflictIntMixin {
	@Unique
	private static int showSearchBar = 7;
}
