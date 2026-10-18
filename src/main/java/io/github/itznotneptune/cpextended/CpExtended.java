// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.Capture;
import io.github.itznotneptune.cpextended.core.OrphanRestore;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.gui.Keybinds;
import io.github.itznotneptune.cpextended.render.WindowRenderer;
import io.github.itznotneptune.cpextended.ui.ChatScreenInput;
import io.github.itznotneptune.cpextended.ui.UnreadTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint. Phase 4: adds the configuration GUI, keybind, optional Mod Menu entry and tab relocation. */
public final class CpExtended implements ClientModInitializer {
	public static final String MOD_ID = "cpextended";
	public static final Logger LOGGER = LoggerFactory.getLogger("Chat Patches Extended");

	@Override
	public void onInitializeClient() {
		if (DevHooks.disabled()) {
			LOGGER.warn("Dev kill switch set (cpextended.dev.disable): mod inactive, no mixins applied");
			return;
		}
		// Chat Patches loads its config in its own initializer; the self-check runs once every mod has initialized.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			ChatPatchesAdapter.get().selfCheck();
			ConfigManager.get().startup(); // config/cpextended.json: the routing configuration (P14)
			Diagnostics.event("startup", "degraded", ChatPatchesAdapter.get().isDegraded());
		});
		// Liveness pruning also runs on a tick check, never inside screen input handlers (architecture §4.7).
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Capture.onTick(client.gui.getChat());
			UnreadTracker.tick();
			ConfigManager.get().tick();
			OrphanRestore.tick(client);
			Keybinds.tick(client);
		});
		Keybinds.register(); // P15: unbound by default
		UnreadTracker.register();
		WindowRenderer.register(); // gameplay path (P10)
		ChatScreenInput.register(); // chat-open path and P11 input
	}
}
