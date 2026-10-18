// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import io.github.itznotneptune.cpextended.test.TestCounters;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TEST ONLY (V-57): counts calls into vanilla ChatScreen click and scroll handling. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenCounterMixin {
	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void cpextendedTest$click(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		TestCounters.VANILLA_CLICKS.incrementAndGet();
	}

	@Inject(method = "mouseScrolled", at = @At("HEAD"))
	private void cpextendedTest$scroll(double x, double y, double h, double v, CallbackInfoReturnable<Boolean> cir) {
		TestCounters.VANILLA_SCROLLS.incrementAndGet();
	}
}
