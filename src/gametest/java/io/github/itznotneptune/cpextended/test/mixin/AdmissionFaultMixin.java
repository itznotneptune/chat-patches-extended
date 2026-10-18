// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEST ONLY (1b): plays foreign mods that interfere with admission after Extended's wrappers allowed it. Markers in
 * the message text: [CANCEL-Q] cancels the message-list add, [CANCEL-D] cancels the visible-line add, [THROW-Q] throws
 * from the message-list add.
 */
@Mixin(ChatComponent.class)
public abstract class AdmissionFaultMixin {
	@Inject(method = "addMessageToQueue", at = @At("HEAD"), cancellable = true)
	private void cpextendedTest$faultQueue(GuiMessage message, CallbackInfo ci) {
		String s = message.content().getString();
		if (s.contains("[CANCEL-Q]")) ci.cancel();
		if (s.contains("[THROW-Q]")) throw new IllegalStateException("test: foreign failure during admission");
	}

	@Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
	private void cpextendedTest$faultDisplay(GuiMessage message, CallbackInfo ci) {
		if (message.content().getString().contains("[CANCEL-D]")) ci.cancel();
	}
}
