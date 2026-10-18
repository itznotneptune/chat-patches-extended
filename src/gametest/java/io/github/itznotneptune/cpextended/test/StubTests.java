// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V-36 stub-jar variant (Phase 8): Chat Patches is present by id and an accepted version ({@code 8.0.0+stub.1}) but has
 * no classes at all. Nothing here references Chat Patches. Extended must start, enter degraded mode (Class S reads fail
 * with linkage errors), keep routing, never hide, and keep its GUI working.
 */
final class StubTests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;

	StubTests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
	}

	static final String CONFIG = "{\"schemaVersion\": 1, \"general\": {\"enabled\": true, \"hidingEnabled\": true}, \"windows\": ["
			+ "{\"id\": \"st\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.02, \"width\": 0.4, \"height\": 0.3, \"tabs\": ["
			+ "{\"id\": \"stHide\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#ST\"}]}]}]}";

	void stub36() {
		Map<String, Object> m = new LinkedHashMap<>();
		Phase2Tests.write(Phase2Tests.cfg(), CONFIG);
		ctx.runOnClient(c -> ConfigManager.get().devSimulateRestart());
		Map<String, Object> adapter = ctx.computeOnClient(c -> {
			ChatPatchesAdapter a = ChatPatchesAdapter.get();
			a.selfCheck();
			return AcceptanceTest.row("degraded", a.isDegraded(), "searchIntegrationOff", a.isSearchIntegrationOff(), "failedOps", a.failedOps().toString(),
					"warnings", Warnings.counts().keySet().toString(), "toasts", Warnings.toastsShown());
		});
		m.put("adapter", adapter);
		boolean routed, notHidden, chatOpens, guiOpens;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			t.sys(sp, "stub routing cpx#ST-1");
			t.waitT(6);
			MessageRecord r = ctx.computeOnClient(c -> {
				for (MessageRecord x : SessionMirror.get().records()) if (x.original() != null && x.original().getString().contains("cpx#ST-1")) return x;
				for (MessageRecord x : SessionMirror.get().hiddenRecords()) if (x.original() != null && x.original().getString().contains("cpx#ST-1")) return x;
				return null;
			});
			routed = r != null && r.tabIds().contains("stHide");
			notHidden = r != null && !r.hidden() && t.inMainChat(r.message());
			ctx.setScreen(() -> new ChatScreen("", false));
			t.waitT(5);
			chatOpens = ctx.computeOnClient(c -> c.screen instanceof ChatScreen);
			ctx.setScreen(() -> null);
			ctx.setScreen(() -> new ConfigScreen(null));
			t.waitT(5);
			guiOpens = ctx.computeOnClient(c -> c.screen instanceof ConfigScreen);
			ctx.setScreen(() -> null);
			t.local("local after stub checks cpx#ST-2");
			t.waitT(5);
		}
		t.waitT(20);
		m.put("routedToTab", routed);
		m.put("notHiddenInDegradedMode", notHidden);
		m.put("chatScreenOpens", chatOpens);
		m.put("configScreenOpens", guiOpens);
		boolean ok = Boolean.TRUE.equals(adapter.get("degraded")) && Boolean.TRUE.equals(adapter.get("searchIntegrationOff")) && routed && notHidden && chatOpens && guiOpens;
		ev.result("V-36.stubJar", ok, "observed", m, "stub", "chatpatches 8.0.0+stub.1: fabric.mod.json only, no classes",
				"note", "the dependency pin (=8.0.0) accepts SemVer build metadata, so a classless jar with that version loads; Extended then runs degraded (no hiding, routing continues) instead of crashing");
	}

	static String text(Component c) { return c.getString(); }
	static Minecraft mc() { return Minecraft.getInstance(); }
}
