// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.x port: the hover-event tooltip for a text style. Minecraft 1.21.11 exposed this as the public
 * {@code GuiGraphics.renderComponentHoverEffect}; in 26.2 it is the private {@code componentHoverEffect}.
 * Used only for hover tooltips over lines drawn in Extended's windows.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
	@Invoker("componentHoverEffect")
	void cpextended$componentHoverEffect(Font font, Style style, int mouseX, int mouseY);
}
