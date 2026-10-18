// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional Mod Menu integration (Q20, P15): the same configuration screen as the keybind. Only Mod Menu loads this
 * entrypoint, so nothing references Mod Menu classes when it isn't installed.
 */
public final class ModMenuEntry implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ConfigScreen::new;
	}
}
