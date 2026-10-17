package io.github.itznotneptune.cpxspike.mixin;

import io.github.itznotneptune.cpxspike.s2.SpikeWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SPIKE-ONLY instrumentation (never in production, architecture §13.2). */
public final class InstrumentationSpikeMixin {
	private InstrumentationSpikeMixin() {}

	@Mixin(ChatScreen.class)
	public abstract static class ChatScreenCounters {
		@Inject(method = "mouseClicked", at = @At("HEAD"))
		private void cpx$countClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
			SpikeWindow.vanillaClicks.incrementAndGet();
		}

		@Inject(method = "mouseScrolled", at = @At("HEAD"))
		private void cpx$countScroll(double x, double y, double h, double v, CallbackInfoReturnable<Boolean> cir) {
			SpikeWindow.vanillaScrolls.incrementAndGet();
		}
	}

	@Mixin(Minecraft.class)
	public abstract static class FrameCounter {
		@Inject(method = "runTick", at = @At("HEAD"))
		private void cpx$frameStart(boolean renderLevel, CallbackInfo ci) {
			SpikeWindow.onFrameStart();
		}
	}

	@Mixin(GuiGraphics.class)
	public abstract static class DeferredProbe {
		@Shadow private Runnable deferredTooltip;

		@Inject(method = "renderDeferredElements", at = @At("HEAD"))
		private void cpx$deferred(CallbackInfo ci) {
			if (deferredTooltip != null) SpikeWindow.deferredTooltipFrames.add(SpikeWindow.frame());
		}
	}
}
