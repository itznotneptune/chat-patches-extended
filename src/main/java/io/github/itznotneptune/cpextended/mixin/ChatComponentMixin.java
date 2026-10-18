// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.itznotneptune.cpextended.core.Capture;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * P1 capture injectors M1-M4. Chat Patches' own HEAD injectors on {@code addMessage} and {@code clearMessages} run
 * inside these wrappers, so M1 sees the component before Chat Patches modifies it (V-01).
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	private static final String ADD_MESSAGE = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V";

	/** M1: opens a capture frame around the whole call. */
	@WrapMethod(method = ADD_MESSAGE)
	private void cpextended$captureFrame(Component message, MessageSignature signature, GuiMessageTag tag, Operation<Void> original) {
		boolean pushed = Capture.enter((ChatComponent) (Object) this, message, tag);
		try {
			original.call(message, signature, tag);
		} finally {
			if (pushed) Capture.exit((ChatComponent) (Object) this);
		}
	}

	/** M2: admission to the visible line list; only acts inside an M1 frame. */
	@WrapWithCondition(method = ADD_MESSAGE, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V"))
	private boolean cpextended$admitToDisplay(ChatComponent self, GuiMessage message) {
		return Capture.admitDisplay(message);
	}

	/** M3: admission to the message list; delivers to tabs once per frame. */
	@WrapWithCondition(method = ADD_MESSAGE, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToQueue(Lnet/minecraft/client/GuiMessage;)V"))
	private boolean cpextended$admitToMessages(ChatComponent self, GuiMessage message) {
		return Capture.admitMessages(message);
	}

	/** M4: synchronizes tabs with main-chat clearing (architecture §4.8). */
	@WrapMethod(method = "clearMessages")
	private void cpextended$syncClear(boolean history, Operation<Void> original) {
		original.call(history);
		Capture.afterClear((ChatComponent) (Object) this, history);
	}
}
