// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import net.minecraft.client.Minecraft;

/** 26.x port: F1 ("hide GUI") is no longer a writable option field; the HUD only exposes isHidden() and toggle(). */
final class HudState {
	private HudState() {}

	static void setHidden(Minecraft mc, boolean hidden) {
		if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
	}
}
