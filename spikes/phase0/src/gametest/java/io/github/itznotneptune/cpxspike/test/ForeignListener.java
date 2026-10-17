package io.github.itznotneptune.cpxspike.test;

import io.github.itznotneptune.cpxspike.SpikeLog;
import io.github.itznotneptune.cpxspike.s2.SpikeWindow;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;

/** V-58: another mod's allowMouseClick listener, registered before (early phase) or after (late phase) Extended's. */
public final class ForeignListener implements ClientModInitializer {
	public enum Behaviour { OFF, CONSUME_EARLY, OBSERVE_EARLY, CONSUME_LATE }
	public static volatile Behaviour behaviour = Behaviour.OFF;
	public static int observed = 0;
	static final Identifier EARLY = Identifier.fromNamespaceAndPath("cpx_spike_test", "early");
	static final Identifier LATE = Identifier.fromNamespaceAndPath("cpx_spike_test", "late");

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen)) return;
			var ev = ScreenMouseEvents.allowMouseClick(screen);
			ev.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
			ev.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
			ev.register(EARLY, (s, e) -> {
				if (!SpikeWindow.contains(e.x(), e.y())) return true;
				if (behaviour == Behaviour.CONSUME_EARLY) { SpikeLog.ev("foreign_consumed", "phase", "early"); return false; }
				if (behaviour == Behaviour.OBSERVE_EARLY) { observed++; SpikeLog.ev("foreign_observed", "phase", "early"); }
				return true;
			});
			ev.register(LATE, (s, e) -> {
				if (!SpikeWindow.contains(e.x(), e.y())) return true;
				if (behaviour == Behaviour.CONSUME_LATE) { SpikeLog.ev("foreign_consumed", "phase", "late"); return false; }
				return true;
			});
		});
	}
}
