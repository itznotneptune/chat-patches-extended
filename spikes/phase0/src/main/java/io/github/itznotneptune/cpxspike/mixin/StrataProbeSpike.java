package io.github.itznotneptune.cpxspike.mixin;

import io.github.itznotneptune.cpxspike.s2.SpikeWindow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** SPIKE-ONLY: exposes GuiRenderState strata so the V-53 layering failure can be diagnosed. */
public final class StrataProbeSpike {
	private StrataProbeSpike() {}

	@Mixin(GuiGraphics.class)
	public interface GuiGraphicsAcc {
		@Accessor("guiRenderState")
		GuiRenderState cpx$state();
	}

	@Mixin(GuiRenderState.class)
	public interface GuiRenderStateAcc {
		@Accessor("strata")
		List<?> cpx$strata();
	}

	@Mixin(ChatScreen.class)
	public abstract static class ChatScreenRenderHead {
		@Inject(method = "render", at = @At("HEAD"))
		private void cpx$renderHead(GuiGraphics g, int mx, int my, float d, CallbackInfo ci) {
			SpikeWindow.chatRenderStratum = ((GuiRenderStateAcc) ((GuiGraphicsAcc) g).cpx$state()).cpx$strata().size();
		}
	}
}
