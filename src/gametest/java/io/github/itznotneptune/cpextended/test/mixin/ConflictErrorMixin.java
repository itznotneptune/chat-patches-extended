// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** TEST ONLY (V-59 case 4, P12 amendment): another mod adding a same-named {@code searchError} instance field. */
@Mixin(ChatScreen.class)
public abstract class ConflictErrorMixin {
	@Unique
	private RuntimeException searchError = null;
}
