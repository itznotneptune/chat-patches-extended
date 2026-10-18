// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.itznotneptune.cpextended.CpExtended;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * P15 keybind: {@code key.cpextended.open_config}, unbound by default, in a custom "Chat Patches Extended" Controls
 * category (K-A); if that category can't be registered, vanilla Misc (K-B). Polled on the client tick, so it only acts
 * with no screen open (never while typing in chat, A-3). There is no edit-mode keybind.
 */
public final class Keybinds {
	public static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("cpextended", "main");
	private static KeyMapping openConfig;
	private static boolean customCategory;

	private Keybinds() {}

	public static void register() {
		KeyMapping.Category category;
		try {
			category = KeyMapping.Category.register(CATEGORY_ID);
			customCategory = true;
		} catch (RuntimeException | LinkageError e) {
			CpExtended.LOGGER.warn("Custom Controls category unavailable; using Misc for the Chat Patches Extended keybind", e);
			category = KeyMapping.Category.MISC;
			customCategory = false;
		}
		openConfig = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.cpextended.open_config", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
	}

	/** Client tick. */
	public static void tick(Minecraft mc) {
		while (openConfig.consumeClick()) {
			if (mc.screen == null && mc.level != null) mc.setScreen(new ConfigScreen(null));
		}
	}

	public static KeyMapping openConfig() { return openConfig; }
	public static boolean customCategory() { return customCategory; }
}
