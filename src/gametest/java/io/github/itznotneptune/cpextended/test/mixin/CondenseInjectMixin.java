// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * TEST ONLY (V-04c case 3): a "foreign mod" that adds an entry to the main chat inside the same {@code addMessage}
 * call, only for messages carrying the token {@code cpx#CD-inj}. Combined with Chat Patches condensing a duplicate,
 * the list size stays the same although a visible entry was removed.
 */
@Mixin(ChatComponent.class)
public abstract class CondenseInjectMixin {
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V", at = @At("HEAD"))
	private void cpextendedTest$inject(Component message, MessageSignature signature, net.minecraft.client.multiplayer.chat.GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		if (!message.getString().contains("cpx#CD-inj")) return;
		@SuppressWarnings("unchecked")
		List<GuiMessage> all = ((io.github.itznotneptune.cpextended.mixin.ChatComponentAccessor) this).cpextended$getAllMessages();
		all.add(0, new GuiMessage(Minecraft.getInstance().gui.hud.getGuiTicks(), Component.literal("foreign injected entry cpx#OT-inj"), null, net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT, null));
	}
}
