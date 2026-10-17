package io.github.itznotneptune.cpxspike;

import io.github.itznotneptune.cpxspike.s1.SpikeAdapter;
import io.github.itznotneptune.cpxspike.s2.InputSpike;
import io.github.itznotneptune.cpxspike.s2.SpikeWindow;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;

/** Phase 0 spike entrypoint (architecture §13). */
public final class SpikeMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SpikeAdapter.selfCheck();

		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("cpx_spike", "window"),
				(graphics, delta) -> SpikeWindow.renderHud(graphics));

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen chat)) return;
			ScreenEvents.beforeRender(screen).register((s, g, mx, my, d) -> SpikeWindow.renderScreen(g));
			ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> SpikeWindow.renderTooltip(chat, g, mx, my));
			ScreenMouseEvents.allowMouseClick(screen).register(InputSpike::allowClick);
			ScreenMouseEvents.allowMouseScroll(screen).register(InputSpike::allowScroll);
			ScreenMouseEvents.allowMouseDrag(screen).register(InputSpike::allowDrag);
			ScreenMouseEvents.allowMouseRelease(screen).register(InputSpike::allowRelease);
			ScreenEvents.remove(screen).register(s -> {
				if (InputSpike.dragging) {
					InputSpike.dragging = false;
					SpikeLog.ev("drag_cancelled_on_close");
				}
			});
		});
	}
}
