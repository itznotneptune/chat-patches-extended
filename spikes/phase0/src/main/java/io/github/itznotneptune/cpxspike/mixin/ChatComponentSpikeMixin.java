package io.github.itznotneptune.cpxspike.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.itznotneptune.cpxspike.s1.Capture;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** M1-M4 on the approved targets (P1). Bodies are probe-only. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentSpikeMixin {
	private static final String ADD = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V";

	// M1
	@WrapMethod(method = ADD)
	private void cpx$wrapAddMessage(Component message, MessageSignature signature, GuiMessageTag tag, Operation<Void> original) {
		Capture.enter((ChatComponent) (Object) this, message, tag);
		try {
			original.call(message, signature, tag);
		} finally {
			Capture.exit();
		}
	}

	// M2
	@WrapWithCondition(method = ADD, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V"))
	private boolean cpx$m2(ChatComponent self, GuiMessage message) {
		return Capture.m2(self, message);
	}

	// M3
	@WrapWithCondition(method = ADD, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToQueue(Lnet/minecraft/client/GuiMessage;)V"))
	private boolean cpx$m3(ChatComponent self, GuiMessage message) {
		return Capture.m3(self, message);
	}

	// M4
	@WrapMethod(method = "clearMessages")
	private void cpx$wrapClear(boolean history, Operation<Void> original) {
		ChatComponent self = (ChatComponent) (Object) this;
		Capture.beforeClear(self, history);
		original.call(history);
		Capture.afterClear(self, history);
	}

	// SPIKE-ONLY instrumentation (V-05): count display-queue calls with and without an active frame.
	@Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"))
	private void cpx$countDisplayQueue(GuiMessage message, CallbackInfo ci) {
		if (Capture.current() == null) Capture.displayQueueCallsNoFrame.incrementAndGet();
		else Capture.displayQueueCallsInFrame.incrementAndGet();
	}
}
