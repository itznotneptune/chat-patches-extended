// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEST ONLY (V-58): another mod's allowMouseClick/allowMouseScroll listeners, registered in a phase before or after
 * the default phase Extended uses.
 */
public final class ForeignListener implements ClientModInitializer {
	public enum Behaviour { OFF, CONSUME_EARLY, OBSERVE_EARLY, CONSUME_LATE }

	public static volatile Behaviour behaviour = Behaviour.OFF;
	public static final AtomicInteger CONSUMED = new AtomicInteger();
	public static final AtomicInteger OBSERVED = new AtomicInteger();
	static final Identifier EARLY = Identifier.fromNamespaceAndPath("cpextended_test", "early");
	static final Identifier LATE = Identifier.fromNamespaceAndPath("cpextended_test", "late");

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen)) return;
			var click = ScreenMouseEvents.allowMouseClick(screen);
			click.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
			click.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
			click.register(EARLY, (s, e) -> early());
			click.register(LATE, (s, e) -> late());
			var scroll = ScreenMouseEvents.allowMouseScroll(screen);
			scroll.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
			scroll.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
			scroll.register(EARLY, (s, x, y, hz, v) -> early());
			scroll.register(LATE, (s, x, y, hz, v) -> late());
		});
	}

	private static boolean early() {
		if (behaviour == Behaviour.CONSUME_EARLY) { CONSUMED.incrementAndGet(); return false; }
		if (behaviour == Behaviour.OBSERVE_EARLY) OBSERVED.incrementAndGet();
		return true;
	}

	private static boolean late() {
		if (behaviour == Behaviour.CONSUME_LATE) { CONSUMED.incrementAndGet(); return false; }
		return true;
	}
}
