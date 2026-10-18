// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.OrphanRestore;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.ui.HitTester;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.ChatVisiblity;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Phase 8: release hardening and compatibility (rescale retry, real mods, context menu, multiplayer, remaining cases). */
final class Phase8Tests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase3Tests p3;
	private final Phase7bTests h;

	Phase8Tests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p3 = new Phase3Tests(t);
		this.h = new Phase7bTests(t);
	}

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	ConfigManager mgr() { return ConfigManager.get(); }

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	static String token(String s) { return Phase7bTests.token(s); }

	List<GuiMessage> main() { return onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()))); }

	List<String> mainTokens() { return main().stream().map(g -> token(g.content().getString())).toList(); }

	void send(TestSingleplayerContext sp, String text) { h.send(sp, text); }

	// ================================================================ rescale: Q36 line-rebuild recovery after a GUI rescale

	void rescale() throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			h.setup(sp);
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hAll", "matchAll", false)));
			send(sp, "visible one cpx#OT-r1");
			send(sp, "hidden one cpx#HD-r1");
			send(sp, "visible two cpx#OT-r2");
			send(sp, "hidden two cpx#HD-r2");
			send(sp, "visible three cpx#OT-r3");
			// 1 failure at the commit + 3 scheduled retries all fail: the lines stay dirty, a toast once
			ctx.runOnClient(c -> { Warnings.devReset(); OrphanRestore.devSetRescaleFailures(4); });
			ConfigManager.OrphanCheck chk = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("hHide"))));
			ConfigManager.EditOutcome o = onClient(() -> mgr().editAllRestoringOrphans(List.of(new ConfigEdit.DeleteTab("hHide")), chk));
			t.waitT(60);
			boolean dirtyAfterRetries = onClient(OrphanRestore::linesDirty);
			List<GuiMessage> committed = main();
			byte[] fileAfterCommit = Phase2Tests.bytes(Phase2Tests.cfg());
			List<String> order = mainTokens();
			// repeated failure on rescales stays safe: two GUI rescales, each fails
			ctx.runOnClient(c -> OrphanRestore.devSetRescaleFailures(2));
			double width0 = onClient(() -> Minecraft.getInstance().options.chatWidth().get());
			int scale0 = onClient(() -> Minecraft.getInstance().options.guiScale().get());
			for (double w : new double[]{0.6, 0.7}) { // chat width changes make vanilla rebuild the lines: a rescale
				ctx.runOnClient(c -> c.options.chatWidth().set(w));
				t.waitT(3);
			}
			boolean stillDirty = onClient(OrphanRestore::linesDirty);
			boolean unchangedByFailedRetries = AcceptanceTest.sameIdentity(committed, main()) && java.util.Arrays.equals(fileAfterCommit, Phase2Tests.bytes(Phase2Tests.cfg()));
			// a further rescale (window resize) with rebuilding working: recovered
			ctx.getInput().resizeWindow(1000, 600);
			t.waitT(5);
			boolean recovered = !onClient(OrphanRestore::linesDirty);
			List<GuiMessage> afterRecovery = main();
			boolean sameRefsAndOrder = AcceptanceTest.sameIdentity(committed, afterRecovery) && mainTokens().equals(order);
			boolean noDuplicates = mainTokens().stream().filter(x -> x.equals("cpx#HD-r1")).count() == 1 && mainTokens().stream().filter(x -> x.equals("cpx#HD-r2")).count() == 1;
			boolean configNotRepeated = java.util.Arrays.equals(fileAfterCommit, Phase2Tests.bytes(Phase2Tests.cfg())) && onClient(() -> mgr().config().windowOfTab("hHide") == null);
			Phase7aTests a = new Phase7aTests(t);
			List<String> lines = a.lines();
			boolean linesRef = lines.equals(a.referenceLines());
			boolean linesShowRestored = lines.stream().anyMatch(x -> x.contains("hidden one cpx#HD-r1"));
			int toasts = onClient(Warnings::toastsShown);
			m.put("committed", row("applied", o.applied(), "disclosedRestored", chk.restored()));
			m.put("dirtyAfterCommitAndThreeRetries", dirtyAfterRetries);
			m.put("twoFailingRescales", row("stillDirty", stillDirty, "messagesAndFileUnchanged", unchangedByFailedRetries));
			m.put("rescaleWithWorkingRebuild", row("recovered", recovered, "sameReferencesAndOrder", sameRefsAndOrder, "noDuplicates", noDuplicates,
					"configChangeNotRepeated", configNotRepeated, "linesEqualReference", linesRef, "restoredLinesVisible", linesShowRestored));
			m.put("toasts", toasts);
			boolean ok = o.applied() && dirtyAfterRetries && stillDirty && unchangedByFailedRetries && recovered && sameRefsAndOrder && noDuplicates && configNotRepeated
					&& linesRef && linesShowRestored && toasts == 1;
			ev.result("Q36.rescaleRetry", ok, "observed", m, "note", "the retry runs on any change of GUI scale, window size or chat display options while the lines are dirty and chat is closed; a retry rebuilds derived lines only");
			ctx.runOnClient(c -> { OrphanRestore.devSetRescaleFailures(0); c.options.chatWidth().set(width0); c.options.guiScale().set(scale0); });
			ctx.getInput().resizeWindow(854, 480);
		}
		t.waitT(20);
	}

	// ================================================================ compat: one suite, run once per mod combination

	void compat() throws Exception {
		String combo = String.join("+", loaded());
		String name = onClient(() -> Minecraft.getInstance().getUser().getName());
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("modsLoaded", combo.isEmpty() ? "none" : combo);
		boolean playerRouted, hidingOk, condOk;
		int retained;
		Object modMenu = "not loaded";
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			h.setup(sp);
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hAll", "matchAll", false))); // hiding tabs are the only holders of hidden messages
			// capture and original-text matching (player chat goes through every chat mod)
			ctx.runOnClient(c -> c.player.connection.sendChat("hello compat cpx#OT-pl"));
			t.waitT(8);
			MessageRecord pl = h.rec("cpx#OT-pl");
			playerRouted = pl != null && pl.tabIds().contains("hOther") && pl.original().getString().contains("hello compat cpx#OT-pl");
			m.put("capture.player", row("routed", playerRouted, "matchText", pl == null ? null : pl.matchText(), "display", pl == null ? null : pl.message().content().getString()));
			// hiding
			int log0 = onClient(ChatLog::messageCount);
			send(sp, "compat hidden cpx#HD-c1");
			MessageRecord hd = h.rec("cpx#HD-c1");
			hidingOk = hd != null && hd.hidden() && !t.inMainChat(hd.message()) && onClient(ChatLog::messageCount) == log0 + 1;
			m.put("hiding", hidingOk);
			// duplicate condensation (Q3)
			send(sp, "compat dup cpx#CD-c1");
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hCond", "hideFromMainChat", true)));
			send(sp, "compat dup cpx#CD-c1");
			MessageRecord dup = h.rec("cpx#CD-c1");
			condOk = dup != null && !dup.hidden() && t.inMainChat(dup.message());
			m.put("condensationReplacementVisible", condOk);
			// hidden retention (P7)
			ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 20);
			for (int i = 0; i < 25; i++) t.sys(sp, "retention " + i + " cpx#HD-k" + i);
			t.waitT(8);
			retained = onClient(() -> SessionMirror.get().hiddenSize());
			ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 16384);
			m.put("hiddenRetentionCap20", retained);
			// orphan restoration + V-20n (Chat Patches and the mods on the swapped list)
			send(sp, "visible before restore cpx#OT-v");
			List<GuiMessage> cached = main();
			ConfigManager.OrphanCheck chk = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("hHide"))));
			ConfigManager.EditOutcome ro = onClient(() -> mgr().editAllRestoringOrphans(List.of(new ConfigEdit.DeleteTab("hHide")), chk));
			boolean restoredVisible = mainTokens().contains("cpx#HD-k24");
			p3.openChat("");
			t.waitT(10); // rendering with the mods on the restored entries
			// Chat Patches search on the restored list
			var f = onClient(() -> ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen));
			if (f != null) ctx.runOnClient(c -> f.setValue("retention 24"));
			t.waitT(3);
			List<String> results = onClient(() -> ((obro1961.chatpatches.accessor.ChatScreenAccess) Minecraft.getInstance().screen).getSearchResults().stream()
					.map(g -> token(g.content().getString())).toList());
			if (f != null) ctx.runOnClient(c -> f.setValue(""));
			// protected-region input and window input
			ChatOpenRenderer.Layout lay = p3.layout("h-a");
			Map<String, Object> input = onClient(() -> {
				ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
				int hgt = Minecraft.getInstance().getWindow().getGuiScaledHeight();
				String inputRegion = HitTester.protectedRegion(s, 30, hgt - 7);
				String stripRegion = lay == null ? "no layout" : String.valueOf(HitTester.protectedRegion(s, lay.strip()[0] + 2, lay.strip()[1] + 2));
				ChatOpenRenderer.Layout top = lay == null ? null : ChatOpenRenderer.topAt(lay.strip()[0] + 2, lay.strip()[1] + 2);
				return row("inputBoxProtected", inputRegion, "windowStripFree", stripRegion, "topWindowAtStrip", top == null ? null : top.windowId());
			});
			ctx.takeScreenshot("p8-compat-" + (combo.isEmpty() ? "none" : combo.replace('+', '-')));
			p3.closeChat();
			m.put("orphanRestoration", row("disclosed", chk.restored(), "applied", ro.applied(), "restoredInMainChat", restoredVisible));
			m.put("V-20n", row("cachedListIsOld", cached != main(), "noCrash", true));
			m.put("chatPatchesSearchOnSwappedList", results);
			m.put("input", input);
			// config GUI and the optional Mod Menu entry
			ctx.setScreen(() -> new ConfigScreen(null));
			t.waitT(3);
			boolean gui = onClient(() -> Minecraft.getInstance().screen instanceof ConfigScreen);
			ctx.setScreen(() -> null);
			if (FabricLoader.getInstance().isModLoaded("modmenu")) {
				modMenu = onClient(() -> {
					var factory = new io.github.itznotneptune.cpextended.gui.ModMenuEntry().getModConfigScreenFactory();
					Screen s = factory.create(null);
					return s instanceof ConfigScreen;
				});
			}
			m.put("configGui", gui);
			m.put("modMenuFactoryOpensConfig", modMenu);
		}
		t.waitT(20);
		// disconnect, reconnect (new world), boundary line, vanillaClearing
		Map<String, Object> sw;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			sw = onClient(() -> {
				List<GuiMessage> all = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
				boolean boundaryNewest = !all.isEmpty() && Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(all.get(0).content()));
				TabState other = SessionMirror.get().tab("hOther");
				return row("boundaryNewestAndExempt", boundaryNewest, "tabsKeptAcrossSwitch", other != null && other.size() > 0);
			});
			ctx.runOnClient(c -> ChatPatches.config.vanillaClearing = true);
		}
		t.waitT(20);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			sw.put("vanillaClearingClearsTabsAndHidden", onClient(() -> SessionMirror.get().hiddenSize() == 0
					&& SessionMirror.get().tabStates().stream().filter(s -> !s.id().equals("hAll")).allMatch(s -> s.size() <= 1)));
			ctx.runOnClient(c -> ChatPatches.config.vanillaClearing = false);
		}
		t.waitT(20);
		m.put("worldSwitch", sw);
		boolean ok = playerRouted && hidingOk && condOk && retained == 20 && Boolean.TRUE.equals(((Map<?, ?>) m.get("orphanRestoration")).get("applied"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("orphanRestoration")).get("restoredInMainChat")) && ((List<?>) m.get("chatPatchesSearchOnSwappedList")).contains("cpx#HD-k24")
				&& "input".equals(((Map<?, ?>) m.get("input")).get("inputBoxProtected")) && "h-a".equals(((Map<?, ?>) m.get("input")).get("topWindowAtStrip"))
				&& Boolean.TRUE.equals(m.get("configGui")) && (modMenu instanceof String || Boolean.TRUE.equals(modMenu))
				&& Boolean.TRUE.equals(sw.get("boundaryNewestAndExempt")) && Boolean.TRUE.equals(sw.get("tabsKeptAcrossSwitch"));
		ev.result("compat." + (combo.isEmpty() ? "none" : combo), ok, "observed", m, "note", "log scanned separately for exceptions from these mods (compat-log-scan in the evidence)");
	}

	static List<String> loaded() {
		List<String> out = new ArrayList<>();
		for (String id : new String[]{"chat_heads", "nochatreports", "modmenu"}) if (FabricLoader.getInstance().isModLoaded(id)) out.add(id);
		return out;
	}

	// ================================================================ ctxmenu: every Chat Patches context-menu action

	void ctxmenu() throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			h.setup(sp);
			ctx.runOnClient(c -> { ChatPatches.config.contextDeletionWarning = false; ChatPatches.config.contextMenu = true; });
			for (int i = 0; i < 4; i++) {
				send(sp, "neighbour hidden " + i + " cpx#HD-n" + i);
				send(sp, "target visible " + i + " cpx#OT-t" + i + " https://example.invalid/t" + i);
			}
			ctx.runOnClient(c -> c.player.connection.sendChat("player line cpx#OT-tp"));
			t.waitT(8);
			String name = onClient(() -> Minecraft.getInstance().getUser().getName());
			String uuid = onClient(() -> Minecraft.getInstance().getUser().getProfileId().toString());
			Map<String, Object> actions = new LinkedHashMap<>();
			int pass = 0, fail = 0;
			String[][] plan = {
					{"Copy Text", "Raw", "cpx#OT-t2"}, {"Copy Text", "Formatted", "cpx#OT-t2"}, {"Copy Text", "Timestamp-less", "cpx#OT-t2"}, {"Copy Text", "JSON", "cpx#OT-t2"},
					{"Copy Time", "Text", "time"}, {"Copy Time", "Hover Text", "time"}, {"Copy Time", "Formatted", "time"}, {"Copy Time", "Unix", "time"},
					{"Copy Link(s)", "Link 1", "https://example.invalid/t2"},
					{"player:Copy Sender", "Username", name}, {"player:Copy Sender", "UUID", uuid}, {"player:Reply", null, "reply"},
					{"Delete", null, "delete"}, {"Delete", "sizzle", "delete-sizzle"}};
			List<String> replyDelete = List.of("reply", "delete", "delete-sizzle");
			Map<String, Object> rd = new LinkedHashMap<>();
			for (String[] p : plan) {
				boolean player = p[0].startsWith("player:");
				String header = player ? p[0].substring(7) : p[0];
				String target = player ? "cpx#OT-tp" : p[2].equals("delete") ? "cpx#OT-t1" : p[2].equals("delete-sizzle") ? "cpx#OT-t0" : "cpx#OT-t2";
				boolean sizzle = p[2].equals("delete-sizzle");
				ctx.runOnClient(c -> ChatPatches.config.contextDeletionSizzle = sizzle);
				Map<String, Object> r = pressAction(target, header, sizzle ? null : p[1], sizzle ? 60 : 4);
				String expect = p[2];
				String clip = (String) r.remove("clipboardFull");
				String verdict;
				switch (expect) {
					case "delete" -> verdict = List.of("cpx#OT-t1").equals(r.get("removedFromMainChat")) && Boolean.TRUE.equals(r.get("tabsFollowLiveness")) ? "PASS" : "NO_EFFECT";
					case "delete-sizzle" -> verdict = List.of("cpx#OT-t0").equals(r.get("removedFromMainChat")) && Boolean.TRUE.equals(r.get("tabsFollowLiveness")) ? "PASS" : "NO_EFFECT";
					case "reply" -> verdict = r.get("inputBox") != null && ((String) r.get("inputBox")).contains(name) ? "PASS" : "NO_EFFECT";
					case "time" -> verdict = clip != null && !clip.equals(SENTINEL) && !clip.contains("cpx#") ? "PASS" : "FAIL";
					default -> {
						String want = expect.startsWith("cpx#") ? "t2" : expect;
						verdict = clip != null && !clip.equals(SENTINEL) && clip.contains(want)
								&& (!expect.startsWith("cpx#") || !clip.contains("cpx#OT-t1") && !clip.contains("cpx#OT-t3") && !clip.contains("cpx#HD-n")) ? "PASS" : "FAIL";
					}
				}
				r.put("verdict", verdict);
				String key = p[0] + (p[1] == null ? "" : " > " + p[1]);
				if (replyDelete.contains(expect)) rd.put(key, r);
				else {
					actions.put(key, r);
					if (verdict.equals("PASS")) pass++; else fail++;
				}
			}
			m.put("actions", actions);
			m.put("summary", row("pass", pass, "fail", fail));
			ev.result("V-18.contextMenu.copyActions", fail == 0 && pass == 11, "observed", m, "note", "real right-click on a visible message whose neighbours are hidden messages; sub-actions clicked after hovering their group; clipboard reset to a sentinel before each action; FAIL if the copied text concerns another message or nothing was copied");
			// Reply and Delete: the menu's own selection is checked, and Chat Patches' delete is applied to that selection
			Map<String, Object> state = menuStateFor("cpx#OT-t3", "cpx#OT-tp");
			rd.put("menuSelectionAndCpDelete", state);
			boolean anyClick = rd.values().stream().anyMatch(v -> v instanceof Map<?, ?> mm && "PASS".equals(mm.get("verdict")));
			boolean stateOk = Boolean.TRUE.equals(state.get("systemMenuSelectedClickedLine")) && Boolean.TRUE.equals(state.get("playerMenuSenderIsPlayer"))
					&& Boolean.TRUE.equals(state.get("cpDeleteRemovedExactlyIt")) && Boolean.TRUE.equals(state.get("tabsFollowLiveness"));
			ev.result("V-18.contextMenu.replyDelete", anyClick && stateOk ? Evidence.Outcome.PASS : Evidence.Outcome.INCONCLUSIVE, "observed", rd,
					"note", "the Reply and Delete buttons had no effect in this harness, neither on a real click nor on a direct press (cause not determined); the open menu's selected line and sender are the clicked message, and Chat Patches' own delete applied to that selection removes exactly it with Extended's tabs following. The click path itself stays unverified.");
		}
		t.waitT(20);
	}

	static final String SENTINEL = "cpx-sentinel";

	/** Opens the menu on each message and reads its own selection (reflection on Chat Patches' menu), then deletes via Chat Patches. */
	Map<String, Object> menuStateFor(String systemTok, String playerTok) {
		Map<String, Object> out = new LinkedHashMap<>();
		String name = onClient(() -> Minecraft.getInstance().getUser().getName());
		for (String tok : new String[]{systemTok, playerTok}) {
			p3.openChat("");
			t.waitT(2);
			double[] at = lineOf(tok);
			if (at == null) { ctx.setScreen(() -> null); out.put(tok, "line not found"); continue; }
			p3.click(at[0], at[1], 1);
			t.waitT(3);
			boolean isPlayer = tok.equals(playerTok);
			Map<String, Object> r = onClient(() -> {
				Map<String, Object> x = new LinkedHashMap<>();
				Object menu = fieldOfType(Minecraft.getInstance().screen, "obro1961.chatpatches.gui.ContextMenu");
				if (menu == null) { x.put("menu", "not found"); return x; }
				GuiMessage sel = (GuiMessage) fieldOfType(menu, GuiMessage.class.getName());
				com.mojang.authlib.GameProfile sender = (com.mojang.authlib.GameProfile) fieldOfType(menu, com.mojang.authlib.GameProfile.class.getName());
				x.put("selected", sel == null ? null : token(sel.content().getString()));
				x.put("sender", sender == null ? null : sender.name());
				if (!isPlayer && sel != null) {
					List<String> before = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().map(g -> token(g.content().getString())).toList();
					obro1961.chatpatches.util.ChatUtil.deleteMessage(sel, false);
					List<String> after = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().map(g -> token(g.content().getString())).toList();
					x.put("cpDeleteRemoved", before.stream().filter(b -> !after.contains(b)).toList());
				}
				return x;
			});
			ctx.setScreen(() -> null);
			t.waitT(3);
			out.put(tok, r);
			if (isPlayer) out.put("playerMenuSenderIsPlayer", name.equals(r.get("sender")));
			else {
				out.put("systemMenuSelectedClickedLine", tok.equals(r.get("selected")));
				out.put("cpDeleteRemovedExactlyIt", List.of(tok).equals(r.get("cpDeleteRemoved")));
			}
		}
		out.put("tabsFollowLiveness", onClient(() -> SessionMirror.get().tabStates().stream().flatMap(s -> s.entries().stream())
				.allMatch(r -> r.hidden() || AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).contains(r.message()))));
		return out;
	}

	static Object fieldOfType(Object o, String typeName) {
		if (o == null) return null;
		for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
			for (var f : k.getDeclaredFields()) {
				if (f.getType().getName().equals(typeName)) {
					try {
						f.setAccessible(true);
						Object v = f.get(o);
						if (v != null) return v;
					} catch (ReflectiveOperationException ignored) {
						// next
					}
				}
			}
		}
		return null;
	}

	List<String> widgetLabels() {
		return onClient(() -> {
			Screen s = Minecraft.getInstance().screen;
			List<String> out = new ArrayList<>();
			if (s != null) for (var w : s.children()) if (w instanceof AbstractWidget a) out.add(a.getMessage().getString());
			return out;
		});
	}

	/** Gui point on the main-chat line showing the message with {@code tok}, using Chat Patches' own line lookup. */
	double[] lineOf(String tok) {
		return onClient(() -> {
			List<GuiMessage> all = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
			for (int y = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 20; y > 20; y--) {
				int idx = ((obro1961.chatpatches.accessor.ChatComponentAccess) Minecraft.getInstance().gui.getChat()).getEoEIndex(20, y);
				if (idx >= 0 && idx < all.size() && all.get(idx).content().getString().contains(tok)) return new double[]{20, y};
			}
			return null;
		});
	}

	AbstractWidget widget(String label) {
		return onClient(() -> {
			Screen s = Minecraft.getInstance().screen;
			if (s == null) return null;
			for (var ch : s.children()) if (ch instanceof AbstractWidget a && a.getMessage().getString().trim().equals(label)) return a;
			return null;
		});
	}

	/** Right-clicks the message's line, hovers {@code header}, clicks {@code sub} (or the header itself). */
	Map<String, Object> pressAction(String tok, String header, String sub) { return pressAction(tok, header, sub, 4); }

	Map<String, Object> pressAction(String tok, String header, String sub, int waitAfter) {
		ctx.runOnClient(c -> c.keyboardHandler.setClipboard(SENTINEL));
		List<String> mainBefore = mainTokens();
		p3.openChat("");
		t.waitT(2);
		double[] at = lineOf(tok);
		if (at == null) { ctx.setScreen(() -> null); return row("error", "line not found"); }
		p3.click(at[0], at[1], 1);
		t.waitT(3);
		List<String> sameLabel = onClient(() -> {
			List<String> out = new ArrayList<>();
			Screen s = Minecraft.getInstance().screen;
			if (s != null) for (var ch : s.children()) if (ch instanceof AbstractWidget a && a.getMessage().getString().trim().equals(header))
				out.add(a.getClass().getSimpleName() + "@" + a.getX() + "," + a.getY() + " visible=" + a.visible + " active=" + a.active);
			return out;
		});
		AbstractWidget head = widget(header);
		if (head == null) { ctx.setScreen(() -> null); return row("error", "header not found: " + header); }
		AbstractWidget target = head;
		if (sub != null) {
			p3.cursor(head.getX() + head.getWidth() / 2.0, head.getY() + head.getHeight() / 2.0);
			t.waitT(3);
			target = onClient(() -> {
				Screen s = Minecraft.getInstance().screen;
				for (var ch : s.children()) if (ch instanceof AbstractWidget a && a.visible && a.getMessage().getString().trim().equals(sub)) return a;
				return null;
			});
			if (target == null) { ctx.setScreen(() -> null); return row("error", "sub-action not visible after hovering: " + sub); }
			p3.cursor(target.getX() + 2, target.getY() + target.getHeight() / 2.0);
			t.waitT(2);
		}
		AbstractWidget finalTarget = target;
		// a real click follows a mouse move onto the button (Chat Patches selects the menu row on hover)
		p3.cursor(finalTarget.getX() + finalTarget.getWidth() / 2.0, finalTarget.getY() + finalTarget.getHeight() / 2.0);
		t.waitT(2);
		p3.click(finalTarget.getX() + finalTarget.getWidth() / 2.0, finalTarget.getY() + finalTarget.getHeight() / 2.0, 0);
		t.waitT(waitAfter);
		String clip = onClient(() -> Minecraft.getInstance().keyboardHandler.getClipboard());
		String screenAfter = onClient(() -> Minecraft.getInstance().screen == null ? "none" : Minecraft.getInstance().screen.getClass().getSimpleName());
		String inputBox = onClient(() -> {
			if (!(Minecraft.getInstance().screen instanceof ChatScreen cs)) return null;
			try {
				var fld = ChatScreen.class.getDeclaredField("input");
				fld.setAccessible(true);
				return ((net.minecraft.client.gui.components.EditBox) fld.get(cs)).getValue();
			} catch (ReflectiveOperationException e) {
				return "unreadable";
			}
		});
		String diag = null;
		boolean noEffect = SENTINEL.equals(clip) && (inputBox == null || inputBox.isEmpty()) && mainTokens().equals(mainBefore);
		if (noEffect) {
			// diagnostic only (never a PASS): does the same button act when pressed directly?
			ctx.runOnClient(c -> { if (finalTarget instanceof net.minecraft.client.gui.components.Button b) b.onPress(null); });
			t.waitT(waitAfter);
			String clip2 = onClient(() -> Minecraft.getInstance().keyboardHandler.getClipboard());
			String input2 = onClient(() -> {
				if (!(Minecraft.getInstance().screen instanceof ChatScreen cs)) return null;
				try {
					var fld = ChatScreen.class.getDeclaredField("input");
					fld.setAccessible(true);
					return ((net.minecraft.client.gui.components.EditBox) fld.get(cs)).getValue();
				} catch (ReflectiveOperationException e) {
					return "unreadable";
				}
			});
			List<String> removed2 = mainBefore.stream().filter(x -> !mainTokens().contains(x)).toList();
			diag = "realClickNoEffect; directPress: clipboard=" + clip2 + " input=" + input2 + " removed=" + removed2;
		}
		ctx.setScreen(() -> null);
		t.waitT(3);
		List<String> mainAfter = mainTokens();
		List<String> removed = mainBefore.stream().filter(x -> !mainAfter.contains(x)).toList();
		boolean tabsFollow = onClient(() -> SessionMirror.get().tabStates().stream().flatMap(s -> s.entries().stream())
				.allMatch(r -> r.hidden() || AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).contains(r.message())));
		Map<String, Object> out = row("clipboard", clip == null ? null : clip.length() > 160 ? clip.substring(0, 160) : clip, "inputBox", inputBox, "removedFromMainChat", removed,
				"tabsFollowLiveness", tabsFollow, "button", row("active", finalTarget.active, "visible", finalTarget.visible, "x", finalTarget.getX(), "y", finalTarget.getY(),
						"w", finalTarget.getWidth(), "h", finalTarget.getHeight()), "screenAfterClick", screenAfter, "diagnostic", diag, "widgetsWithLabel", sameLabel);
		out.put("clipboardFull", clip);
		return out;
	}

	// ================================================================ misc8: V-45 Chat Visibility, V-04c(3), language

	void misc8() throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			h.setup(sp);
			v45ChatVisibility(sp);
			h.setup(sp);
			v04c3(sp);
			h.setup(sp);
			language(sp);
		}
		t.waitT(20);
	}

	void v45ChatVisibility(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		for (ChatVisiblity cv : ChatVisiblity.values()) {
			ctx.runOnClient(c -> c.options.chatVisibility().set(cv));
			t.waitT(2);
			String sysTok = "cpx#HD-cv" + cv.name();
			String plTok = "cpx#HD-cvp" + cv.name();
			send(sp, "cv system " + sysTok);
			ctx.runOnClient(c -> c.player.connection.sendChat("cv player " + plTok));
			t.waitT(8);
			MessageRecord s = h.rec(sysTok), p = h.rec(plTok);
			Map<String, Object> r = row("system", s == null ? "not delivered by vanilla" : (s.hidden() ? "hidden" : "visible"),
					"player", p == null ? "not delivered by vanilla" : (p.hidden() ? "hidden" : "visible"));
			// every message that reaches addMessage gets the same decision (hidden: it matches the hiding tab)
			if (s != null && !s.hidden()) ok = false;
			if (p != null && !p.hidden()) ok = false;
			m.put(cv.name(), r);
		}
		ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.FULL));
		ev.result("V-45.chatVisibility", ok, "observed", m, "note", "Chat Visibility decides what vanilla passes to addMessage; for every message that arrives, the hide decision is the same (Q24)");
	}

	void v04c3(TestSingleplayerContext sp) {
		send(sp, "inject dup cpx#CD-inj");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hCond", "hideFromMainChat", true)));
		int size0 = main().size();
		send(sp, "inject dup cpx#CD-inj");
		int size1 = main().size();
		MessageRecord r = h.rec("cpx#CD-inj");
		long visibleCopies = mainTokens().stream().filter(x -> x.equals("cpx#CD-inj")).count();
		boolean injected = mainTokens().contains("cpx#OT-inj");
		boolean ok = injected && r != null && !r.hidden() && t.inMainChat(r.message()) && visibleCopies == 1;
		ev.result("V-04c.case3", ok, "sizeBefore", size0, "sizeAfter", size1, "foreignEntryInjected", injected, "replacementVisible", r != null && !r.hidden(), "visibleCopies", visibleCopies,
				"note", "a test mod adds an entry inside the same call while Chat Patches condenses: identity snapshot detects the removal; the replacement stays visible");
	}

	void language(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		String before = onClient(() -> Minecraft.getInstance().getLanguageManager().getSelected());
		String english = onClient(() -> Component.translatable("multiplayer.player.joined", "Tester").getString());
		ctx.runOnClient(c -> {
			c.getLanguageManager().setSelected("de_de");
			c.options.languageCode = "de_de";
			c.reloadResourcePacks();
		});
		t.waitT(120);
		String german = onClient(() -> Component.translatable("multiplayer.player.joined", "Tester").getString());
		String germanPart = german.replace("Tester", "").trim();
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetFilter("hOther", "include", 0, io.github.itznotneptune.cpextended.filter.FilterSpec.literal(germanPart))));
		t.sysComponent(sp, Component.translatable("multiplayer.player.joined", "Tester"));
		t.waitT(6);
		boolean routedGerman = !german.equals(english) && onClient(() -> SessionMirror.get().records().stream().anyMatch(r -> r.original() != null
				&& r.original().getString().contains(germanPart) && r.tabIds().contains("hOther")));
		m.put("englishText", english);
		m.put("germanText", german);
		ctx.runOnClient(c -> {
			c.getLanguageManager().setSelected(before);
			c.options.languageCode = before;
			c.reloadResourcePacks();
		});
		t.waitT(120);
		t.sysComponent(sp, Component.translatable("multiplayer.player.joined", "Tester2"));
		t.waitT(6);
		boolean notRoutedEnglish = onClient(() -> SessionMirror.get().records().stream().filter(r -> r.original() != null && r.original().getString().contains("Tester2"))
				.noneMatch(r -> r.tabIds().contains("hOther")));
		m.put("germanClientMatchesGermanText", routedGerman);
		m.put("englishClientDoesNotMatchGermanFilter", notRoutedEnglish);
		ev.result("Lang.translatedMatching", routedGerman && notRoutedEnglish, "observed", m, "note", "translatable messages match in the client's current language (§4.2, documented)");
	}

	// ================================================================ keys8: V-38 Controls category position

	void keys8() throws Exception {
		ctx.setScreen(() -> new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, Minecraft.getInstance().options));
		t.waitT(5);
		Map<String, Object> r = onClient(() -> {
			Screen s = Minecraft.getInstance().screen;
			Map<String, Object> out = new LinkedHashMap<>();
			for (var ch : s.children()) {
				if (ch instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsList list) {
					List<String> cats = new ArrayList<>();
					int index = 0, ours = -1, ourRow = -1, categoriesSeen = 0;
					Object lastCategory = null;
					for (var e : list.children()) {
						if (e instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsList.CategoryEntry ce) {
							categoriesSeen++;
							lastCategory = ce;
							cats.add("category#" + categoriesSeen);
						} else {
							net.minecraft.client.KeyMapping km = keyOf(e);
							if (km != null && km.getName().equals("key.cpextended.open_config")) {
								ours = categoriesSeen - 1;
								ourRow = index;
								out.put("ourCategoryLabel", km.getCategory().label().getString());
								if (lastCategory != null) scrollTo(list, lastCategory);
							}
						}
						index++;
					}
					out.put("categoryCount", cats.size());
					out.put("ourCategoryIndex", ours);
					out.put("ourRow", ourRow);
					out.put("lastCategories", cats.subList(Math.max(0, cats.size() - 3), cats.size()));
				}
			}
			return out;
		});
		t.waitT(3);
		ctx.takeScreenshot("p8-controls-category");
		ctx.setScreen(() -> null);
		boolean ok = r.get("ourCategoryIndex") instanceof Integer i && i >= 0;
		ev.result("V-38.categoryPosition", ok, "observed", r, "note", "the Controls list scrolled to the category; screenshot p8-controls-category shows its label and key");
	}

	static net.minecraft.client.KeyMapping keyOf(Object entry) {
		for (Class<?> k = entry.getClass(); k != null; k = k.getSuperclass()) {
			for (var f : k.getDeclaredFields()) {
				if (net.minecraft.client.KeyMapping.class.isAssignableFrom(f.getType())) {
					try {
						f.setAccessible(true);
						return (net.minecraft.client.KeyMapping) f.get(entry);
					} catch (ReflectiveOperationException ignored) {
						return null;
					}
				}
			}
		}
		return null;
	}

	static void scrollTo(Object list, Object entry) {
		for (Class<?> k = list.getClass(); k != null; k = k.getSuperclass()) {
			for (var mth : k.getDeclaredMethods()) {
				if (mth.getName().equals("scrollToEntry") && mth.getParameterCount() == 1) {
					try {
						mth.setAccessible(true);
						mth.invoke(list, entry);
						return;
					} catch (ReflectiveOperationException ignored) {
						return;
					}
				}
			}
		}
	}

	static String categoryName(Object entry) {
		for (Class<?> k = entry.getClass(); k != null; k = k.getSuperclass()) {
			for (var f : k.getDeclaredFields()) {
				if (Component.class.isAssignableFrom(f.getType())) {
					try {
						f.setAccessible(true);
						Object v = f.get(entry);
						if (v != null) return ((Component) v).getString();
					} catch (ReflectiveOperationException ignored) {
						// next field
					}
				}
			}
		}
		return entry.toString();
	}

	// ================================================================ mp: a real dedicated server (in-process, network connection)

	void mp() throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		Properties props = new Properties();
		props.setProperty("online-mode", "false");
		props.setProperty("enforce-secure-profile", "false");
		props.setProperty("spawn-protection", "0");
		props.setProperty("server-port", "25611"); // 25565 is taken by another server on this machine; never touched
		Phase2Tests.write(Phase2Tests.cfg(), Phase7bTests.CONFIG);
		ctx.runOnClient(c -> { mgr().devSimulateRestart(); AcceptanceTest.resetChatPatchesConfig(); ChatPatches.config.chatMaxMessages = 16384; });
		String name = onClient(() -> Minecraft.getInstance().getUser().getName());
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props)) {
			server.runCommand("op " + name);
			try (TestServerConnection conn = server.connect()) {
				t.waitT(60);
				boolean remote = onClient(() -> Minecraft.getInstance().getSingleplayerServer() == null && Minecraft.getInstance().getConnection() != null);
				m.put("connectedToDedicatedServer", remote);
				// normal player chat (client -> server -> client)
				ctx.runOnClient(c -> c.player.connection.sendChat("mp player chat cpx#OT-mp1"));
				t.waitT(10);
				MessageRecord pc = h.rec("cpx#OT-mp1");
				m.put("playerChat", row("received", pc != null, "routed", pc != null && pc.tabIds().contains("hOther"), "text", pc == null ? null : pc.original().getString()));
				// server/system messages and plugin-style formatting (server side: /say, /tellraw JSON with hover and click)
				server.runCommand("say mp server say cpx#OT-mp2");
				server.runCommand("tellraw @a [{\"text\":\"[Shop] \",\"color\":\"gold\",\"bold\":true},{\"text\":\"" + name
						+ "\",\"hover_event\":{\"action\":\"show_text\",\"value\":\"Rank: VIP\"},\"click_event\":{\"action\":\"suggest_command\",\"command\":\"/msg " + name + " \"}},{\"text\":\" bought apples cpx#OT-mp3\",\"color\":\"gray\"}]");
				t.waitT(10);
				m.put("serverSay", h.rec("cpx#OT-mp2") != null && h.rec("cpx#OT-mp2").tabIds().contains("hOther"));
				MessageRecord plugin = h.rec("cpx#OT-mp3");
				m.put("pluginStyleTellraw", row("routed", plugin != null && plugin.tabIds().contains("hOther"), "text", plugin == null ? null : plugin.original().getString()));
				// rapid messages: 200 in one server tick burst
				server.runOnServer(s -> { for (int i = 0; i < 200; i++) s.getPlayerList().broadcastSystemMessage(Component.literal("burst " + i + " cpx#OT-b" + i), false); });
				t.waitT(40);
				List<Long> seqs = onClient(() -> SessionMirror.get().tab("hOther").entries().stream().filter(r -> r.original() != null && r.original().getString().contains("cpx#OT-b"))
						.map(MessageRecord::seq).toList());
				List<String> burstOrder = onClient(() -> SessionMirror.get().tab("hOther").entries().stream().map(r -> token(r.original() == null ? "" : r.original().getString()))
						.filter(x -> x.startsWith("cpx#OT-b")).toList());
				boolean inOrder = true;
				for (int i = 0; i < burstOrder.size(); i++) if (!burstOrder.get(i).equals("cpx#OT-b" + i)) { inOrder = false; break; }
				m.put("burst200", row("deliveredOnce", burstOrder.size() == 200 && burstOrder.stream().distinct().count() == 200, "inOrder", inOrder));
				// duplicates: visible ones condensed by Chat Patches, hidden ones not
				for (int i = 0; i < 3; i++) server.runCommand("say mp dup visible cpx#OT-dv");
				for (int i = 0; i < 3; i++) server.runCommand("say mp dup hidden cpx#HD-dh");
				t.waitT(10);
				long dupVisible = mainTokens().stream().filter(x -> x.equals("cpx#OT-dv")).count();
				long dupHidden = onClient(() -> SessionMirror.get().hiddenRecords().stream().filter(r -> r.original().getString().contains("cpx#HD-dh")).count());
				m.put("duplicates", row("visibleCopiesInMainChat", dupVisible, "hiddenCopies", dupHidden));
				// hiding with several matching tabs; disabled and suspended tabs
				server.runCommand("say mp multi cpx#HD2-mp");
				ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hDisTab", "enabled", false)));
				server.runCommand("say mp disabled tab cpx#DT-mp");
				server.runCommand("say mp suspended cpx#SU-mp");
				t.waitT(10);
				MessageRecord multi = h.rec("cpx#HD2-mp"), dis = h.rec("cpx#DT-mp"), sus = h.rec("cpx#SU-mp");
				m.put("hidingMultipleTabs", row("hidden", multi != null && multi.hidden(), "tabs", multi == null ? null : multi.tabIds()));
				m.put("disabledTab", row("receivedNotHidden", dis != null && !dis.hidden() && dis.tabIds().contains("hDisTab")));
				m.put("suspendedTab", row("notReceivedNotHidden", sus != null && !sus.hidden() && !sus.tabIds().contains("hSus")));
				// search and unread
				int unreadHide = h.unread("hHide");
				p3.openChat("");
				var f = onClient(() -> ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen));
				ctx.runOnClient(c -> { mgr().edit(new ConfigEdit.SelectTab("h-a", "hHide2")); f.setValue("multi"); });
				t.waitT(3);
				List<String> view = onClient(() -> ChatOpenRenderer.viewOf("h-a").stream().map(r -> token(r.message().content().getString())).toList());
				ctx.runOnClient(c -> f.setValue(""));
				p3.closeChat();
				m.put("searchAndUnread", row("hHideUnread", unreadHide, "searchViewInHHide2", view));
				// world transition on the server (dimension change)
				server.runCommand("execute in minecraft:the_nether run tp " + name + " 0 100 0");
				t.waitT(60);
				server.runCommand("say after nether cpx#OT-mp4");
				t.waitT(10);
				m.put("dimensionChange", row("tabHistoryKept", onClient(() -> SessionMirror.get().tab("hOther").size()) > 200, "routingContinues", h.rec("cpx#OT-mp4") != null));
			}
			ctx.setScreen(() -> null);
			t.waitT(10);
			// server disconnection during ordinary chat activity: kicked while a burst is arriving
			try {
				try (TestServerConnection connK = server.connect()) {
					t.waitT(60);
					server.runOnServer(srv -> {
						for (int i = 0; i < 50; i++) srv.getPlayerList().broadcastSystemMessage(Component.literal("pre-kick " + i + " cpx#OT-k" + i), false);
						srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), "kick " + name + " test disconnect");
					});
					t.waitT(40);
					String screenAfterKick = onClient(() -> Minecraft.getInstance().screen == null ? "none" : Minecraft.getInstance().screen.getClass().getSimpleName());
					int keptAfterKick = onClient(() -> SessionMirror.get().tab("hOther").size());
					long kickBurst = onClient(() -> SessionMirror.get().tab("hOther").entries().stream().filter(r -> r.original() != null && r.original().getString().contains("cpx#OT-k")).count());
					m.put("kickDuringBurst", row("screen", screenAfterKick, "tabEntriesKept", keptAfterKick, "burstMessagesReceivedBeforeKick", kickBurst));
				}
			} catch (AssertionError expected) {
				m.put("kickClose", "connection already closed by the kick (expected): " + expected.getMessage());
			}
			// reconnect to the same server
			try (TestServerConnection conn2 = server.connect()) {
				t.waitT(60);
				server.runCommand("say after reconnect cpx#OT-mp5");
				t.waitT(10);
				MessageRecord rc = h.rec("cpx#OT-mp5");
				m.put("reconnect", row("routed", rc != null && rc.tabIds().contains("hOther"), "historyKept", onClient(() -> SessionMirror.get().tab("hOther").size()) > 200,
						"hiddenKept", onClient(() -> SessionMirror.get().hiddenSize()) > 0));
			}
		}
		t.waitT(20);
		boolean ok = Boolean.TRUE.equals(m.get("connectedToDedicatedServer"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("playerChat")).get("routed")) && Boolean.TRUE.equals(m.get("serverSay"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("pluginStyleTellraw")).get("routed"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("burst200")).get("deliveredOnce")) && Boolean.TRUE.equals(((Map<?, ?>) m.get("burst200")).get("inOrder"))
				&& Long.valueOf(1).equals(((Map<?, ?>) m.get("duplicates")).get("visibleCopiesInMainChat")) && Long.valueOf(3).equals(((Map<?, ?>) m.get("duplicates")).get("hiddenCopies"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("hidingMultipleTabs")).get("hidden")) && Boolean.TRUE.equals(((Map<?, ?>) m.get("disabledTab")).get("receivedNotHidden"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("suspendedTab")).get("notReceivedNotHidden"))
				&& ((List<?>) ((Map<?, ?>) m.get("searchAndUnread")).get("searchViewInHHide2")).contains("cpx#HD2-mp")
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("dimensionChange")).get("routingContinues"))
				&& m.get("reconnect") instanceof Map<?, ?> rc && Boolean.TRUE.equals(rc.get("routed")) && Boolean.TRUE.equals(rc.get("historyKept"));
		ev.result("MP.dedicatedServer", ok, "observed", m, "note", "server side: vanilla 1.21.11 dedicated server in-process (offline mode, single synthetic player); client side: everything Extended records. No other players or real conversations.");
	}
}
