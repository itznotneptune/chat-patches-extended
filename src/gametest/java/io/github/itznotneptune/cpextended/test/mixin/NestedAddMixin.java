// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEST ONLY (V-47): plays a foreign mod that calls addMessage while another addMessage is in progress. */
@Mixin(ChatComponent.class)
public abstract class NestedAddMixin {
	private static boolean cpextendedTest$inside;

	@Inject(method = "addMessageToQueue", at = @At("HEAD"))
	private void cpextendedTest$nest(GuiMessage message, CallbackInfo ci) {
		if (cpextendedTest$inside || !message.content().getString().contains("[NEST]")) return;
		cpextendedTest$inside = true;
		try {
			((ChatComponent) (Object) this).addMessage(Component.literal("nested inner cpx#NEST-I"));
		} finally {
			cpextendedTest$inside = false;
		}
	}
}
