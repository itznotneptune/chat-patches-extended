// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.core.Capture;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint. Milestone 1a: capture, adapter, session mirror and headless routing only (no GUI, no hiding). */
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
			DevHooks.applyStartupConfiguration();
			Diagnostics.event("startup", "degraded", ChatPatchesAdapter.get().isDegraded());
		});
		// Liveness pruning also runs on a tick check, never inside screen input handlers (architecture §4.7).
		ClientTickEvents.END_CLIENT_TICK.register(client -> Capture.onTick(client.gui.getChat()));
	}
}
