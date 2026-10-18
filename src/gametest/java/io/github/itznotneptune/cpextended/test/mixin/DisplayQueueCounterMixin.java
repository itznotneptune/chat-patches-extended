// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import io.github.itznotneptune.cpextended.core.Capture;
import io.github.itznotneptune.cpextended.test.TestCounters;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEST ONLY (V-05): counts display-queue calls made outside any addMessage frame. */
@Mixin(ChatComponent.class)
public abstract class DisplayQueueCounterMixin {
	@Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"))
	private void cpextendedTest$count(GuiMessage message, CallbackInfo ci) {
		if (Capture.depth() == 0) TestCounters.FRAMELESS_DISPLAY.incrementAndGet();
	}
}
