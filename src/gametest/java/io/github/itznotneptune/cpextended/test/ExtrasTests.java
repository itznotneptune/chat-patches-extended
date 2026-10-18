// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.LinkedHashMap;
import java.util.Map;

/** V-14 with Mod Menu and No Chat Reports installed (-Pextras=modmenu,ncr). Only loaded in that run: it references Mod Menu types. */
final class ExtrasTests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;

	ExtrasTests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
	}

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	Screen screen() { return onClient(() -> Minecraft.getInstance().screen); }

	void restart(String config) {
		Phase2Tests.write(Phase2Tests.cfg(), config);
		ctx.runOnClient(c -> ConfigManager.get().devSimulateRestart());
		t.waitT(2);
	}


	void extras() throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("modMenuLoaded", FabricLoader.getInstance().isModLoaded("modmenu"));
		m.put("noChatReportsLoaded", FabricLoader.getInstance().isModLoaded("nochatreports"));
		Screen made = null;
		for (var c : FabricLoader.getInstance().getEntrypointContainers("modmenu", com.terraformersmc.modmenu.api.ModMenuApi.class)) {
			if (c.getProvider().getMetadata().getId().equals("cpextended")) made = onClient(() -> c.getEntrypoint().getModConfigScreenFactory().create(null));
		}
		m.put("modMenuFactoryScreen", made == null ? null : made.getClass().getSimpleName());
		if (made != null) {
			Screen s = made;
			ctx.setScreen(() -> s);
			t.waitT(5);
			m.put("opensSameGui", screen() instanceof ConfigScreen);
			ctx.setScreen(() -> null);
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			restart(Phase3Tests.CONFIG);
			t.sys(sp, "system with extras cpx#PA-x1");
			ctx.runOnClient(c -> c.player.connection.sendChat("player chat with extras cpx#PA-x2"));
			t.waitT(10);
			long routed = t.tabEntries("pa1").stream().filter(r -> r.original() != null && r.original().getString().contains("cpx#PA-x")).count();
			m.put("routedWithExtras", routed);
		}
		t.waitT(20);
		boolean ok = Boolean.TRUE.equals(m.get("modMenuLoaded")) && Boolean.TRUE.equals(m.get("noChatReportsLoaded")) && Boolean.TRUE.equals(m.get("opensSameGui"))
				&& Long.valueOf(2).equals(m.get("routedWithExtras"));
		ev.result("V-14", ok, "observed", m, "note", "run with -Pextras=modmenu,ncr; the absent case is V-14.modMenuAbsent in mode gui");
	}
}
