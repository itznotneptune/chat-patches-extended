// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import io.github.itznotneptune.cpextended.test.TestCounters;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEST ONLY (V-55): marks the end of each rendered frame so the test can check how often each window was drawn. */
@Mixin(GameRenderer.class)
public abstract class FrameProbeMixin {
	@Inject(method = "render", at = @At("RETURN"))
	private void cpextendedTest$frameEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		TestCounters.onFrameEnd();
	}
}
