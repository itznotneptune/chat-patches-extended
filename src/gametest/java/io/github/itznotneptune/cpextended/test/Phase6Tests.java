// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.Origin;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.filter.RestoredText;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.render.WindowRenderer;
import io.github.itznotneptune.cpextended.search.TabSearch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.entity.player.ChatVisiblity;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Phase 6 acceptance tests: restored history in tabs (P2, Q1, Q4, Q16a, Q16b, Q23). Synthetic chat only. */
final class Phase6Tests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase3Tests p3;
	static final Path LIVE_DUMP = Path.of("cpx-evidence", "restore_seed-live.json");
	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** Tokens of the fixture messages sent live in restore_seed and expected back as restored history. */
	static final String[] STRUCTURED = {"cpx#R-player", "cpx#R-say", "cpx#R-team", "cpx#R-sys", "cpx#R-plugin", "cpx#R-legacy", "cpx#R-trans",
			"cpx#R-style", "cpx#R-gen1", "cpx#R-gen2", "cpx#R-gen3", "cpx#R-custom", "cpx#R-dupe", "cpx#R-after"};
	static final String[] EDGE = {"cpx#R-old1", "cpx#R-old2", "cpx#R-old3", "cpx#R-old4", "cpx#R-old5", "cpx#R-old6", "cpx#R-old7", "cpx#R-old8"};

	Phase6Tests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p3 = new Phase3Tests(t);
	}

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	static String token(String s) {
		int i = s.indexOf("cpx#R-");
		if (i < 0) return null;
		int j = i + 6;
		while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)))) j++;
		return s.substring(i, j);
	}

	/** A component tree for comparison (V-27f): contents type and text, style, siblings, recursively. */
	static Object tree(Component c) {
		Map<String, Object> m = new LinkedHashMap<>();
		var contents = c.getContents();
		m.put("type", contents.getClass().getSimpleName());
		if (contents instanceof PlainTextContents p) m.put("text", p.text());
		else m.put("text", c.plainCopy().getString());
		Style s = c.getStyle();
		if (!s.isEmpty()) m.put("style", s.toString().replaceAll("@[0-9a-f]+", "@<id>")); // identity hashes differ per instance
		if (!c.getSiblings().isEmpty()) {
			List<Object> sib = new ArrayList<>();
			for (Component x : c.getSiblings()) sib.add(tree(x));
			m.put("siblings", sib);
		}
		return m;
	}

	void command(TestSingleplayerContext sp, String cmd) {
		sp.getServer().runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
	}

	// =================================================================== restore_seed (first launch)

	/** Sends every fixture message live, records its live matching text and processed tree, and lets Chat Patches save. */
	void restoreSeed() throws Exception {
		Phase2Tests.write(Phase2Tests.cfg(), "{\"schemaVersion\": 1, \"windows\": []}");
		ctx.runOnClient(c -> ConfigManager.get().devSimulateRestart());
		String name = onClient(() -> Minecraft.getInstance().getUser().getName());
		Map<String, Object> live = new LinkedHashMap<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			ctx.runOnClient(c -> { ChatPatches.config.boundary = true; ChatPatches.config.counter = true; ChatPatches.config.nameFormat = "<$>"; });
			ctx.runOnClient(c -> c.player.connection.sendChat("hello restored cpx#R-player"));
			command(sp, "say say restored cpx#R-say");
			command(sp, "team add cpxblue");
			command(sp, "team join cpxblue " + name);
			command(sp, "execute as " + name + " run teammsg team restored cpx#R-team");
			t.sys(sp, "system restored cpx#R-sys");
			t.sysComponent(sp, Component.empty()
					.append(Component.literal("[Shop] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
					.append(Component.literal(name).withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(Component.literal("Rank: VIP")))
							.withClickEvent(new ClickEvent.SuggestCommand("/msg " + name + " "))))
					.append(Component.literal(" » bought 3 apples cpx#R-plugin").withStyle(ChatFormatting.GRAY)));
			t.sysComponent(sp, Component.literal("§6[VIP] §f" + name + "§7: hi with legacy codes cpx#R-legacy"));
			t.sysComponent(sp, Component.translatable("chat.type.announcement", "Server", "translated restored cpx#R-trans"));
			t.sysComponent(sp, Component.literal("styled ").append(Component.literal("bold red").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
					.append(Component.literal(" and ").append(Component.literal("italic").withStyle(ChatFormatting.ITALIC))).append(" cpx#R-style"));
			t.sys(sp, "[12:00:00] genuine stamp cpx#R-gen1");
			t.sys(sp, "genuine counter cpx#R-gen2 (x2)");
			t.sys(sp, "genuine x3 cpx#R-gen3 x3");
			// each copy as Chat Patches built it (insertion = its own timestamp): the restored survivor is compared with
			// the live copy that has the same insertion (V-27f)
			Map<String, Object> dupeCopies = new LinkedHashMap<>();
			for (int i = 0; i < 3; i++) {
				t.sys(sp, "dupe restored cpx#R-dupe");
				t.waitT(2);
				Map<String, Object> copy = onClient(() -> {
					for (MessageRecord r : SessionMirror.get().records().reversed()) {
						if (r.origin() == Origin.LIVE && "cpx#R-dupe".equals(token(r.message().content().getString()))) {
							Component c = r.message().content();
							return row("insertion", c.getSiblings().size() == 3 ? c.getSiblings().get(0).getStyle().getInsertion() : null,
									"sibling1", c.getSiblings().size() == 3 ? c.getSiblings().get(1).getString() : null,
									"sibling2", c.getSiblings().size() == 3 ? c.getSiblings().get(2).getString() : null, "tree", tree(c));
						}
					}
					return null;
				});
				if (copy != null) dupeCopies.put(String.valueOf(copy.get("insertion")), copy);
			}
			live.put("dupeCopies", dupeCopies);
			t.waitT(4);
			ctx.runOnClient(c -> ChatPatches.config.nameFormat = "[$]");
			ctx.runOnClient(c -> c.player.connection.sendChat("custom format cpx#R-custom"));
			t.waitT(10);
			ctx.runOnClient(c -> ChatPatches.config.nameFormat = "<$>");
			for (String tok : STRUCTURED) {
				if (tok.equals("cpx#R-after")) continue;
				Map<String, Object> e = onClient(() -> {
					for (MessageRecord r : SessionMirror.get().records()) {
						if (r.origin() != Origin.LIVE || r.original() == null || !tok.equals(token(r.original().getString()))) continue;
						return row("liveMatchText", r.original().getString(), "tree", tree(r.message().content()), "display", r.message().content().getString());
					}
					return null;
				});
				live.put(tok, e);
			}
		}
		t.waitT(20);
		// a second world: Chat Patches adds its boundary line on join
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			Map<String, Object> boundary = onClient(() -> {
				for (MessageRecord r : SessionMirror.get().records().reversed()) {
					if (Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(r.message().content()))) {
						return row("tree", tree(r.message().content()), "display", r.message().content().getString());
					}
				}
				return null;
			});
			live.put("boundary", boundary);
			t.sys(sp, "after boundary cpx#R-after");
			t.waitT(6);
			live.put("cpx#R-after", onClient(() -> {
				for (MessageRecord r : SessionMirror.get().records().reversed()) {
					if (r.original() != null && "cpx#R-after".equals(token(r.original().getString()))) {
						return row("liveMatchText", r.original().getString(), "tree", tree(r.message().content()), "display", r.message().content().getString());
					}
				}
				return null;
			}));
			live.put("playerName", name);
			live.put("chatLogMessageCount", onClient(ChatLog::messageCount));
		}
		Files.writeString(LIVE_DUMP, GSON.toJson(live));
		boolean all = live.values().stream().allMatch(v -> v != null);
		ev.result("restore_seed.fixturesSent", all, "captured", live.keySet(), "missing", live.entrySet().stream().filter(e -> e.getValue() == null).map(Map.Entry::getKey).toList());
		t.waitT(20);
	}

	// =================================================================== restore6 (relaunch)

	String config(String name) {
		String q = "\"";
		return "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
				+ "\"windows\": [\n"
				+ " {\"id\": \"r-main\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.02, \"width\": 0.45, \"height\": 0.30, \"selectedTab\": \"rAll\", \"tabs\": ["
				+ "{\"id\": \"rAll\", \"matchAll\": true, \"restoreHistory\": true},"
				+ "{\"id\": \"rNone\", \"matchAll\": true, \"restoreHistory\": false},"
				+ "{\"id\": \"rTok\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#R-\"}]}]},\n"
				+ " {\"id\": \"r-filters\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.36, \"width\": 0.45, \"height\": 0.20, \"tabs\": ["
				+ "{\"id\": \"rAngle\", \"include\": [{\"mode\": \"literal\", \"pattern\": " + q + "<" + name + "> hello restored" + q + "}]},"
				+ "{\"id\": \"rBracket\", \"include\": [{\"mode\": \"literal\", \"pattern\": " + q + "[" + name + "] custom format" + q + "}]},"
				+ "{\"id\": \"rAngleCustom\", \"include\": [{\"mode\": \"literal\", \"pattern\": " + q + "<" + name + "> custom format" + q + "}]},"
				+ "{\"id\": \"rGen\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"[12:00:00] genuine\"}, {\"mode\": \"literal\", \"pattern\": \"cpx#R-gen2 (x2)\"}, {\"mode\": \"literal\", \"pattern\": \"cpx#R-gen3 x3\"}]},"
				+ "{\"id\": \"rStampNeg\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"] system restored\"}]},"
				+ "{\"id\": \"rLegacy\", \"caseSensitive\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"§6[VIP] §f\"}]}]}\n"
				+ "]\n}\n";
	}

	List<MessageRecord> restoredWith(String tok) {
		return onClient(() -> SessionMirror.get().records().stream()
				.filter(r -> r.origin() == Origin.RESTORED && tok.equals(token(r.message().content().getString()))).toList());
	}

	List<String> tabTokens(String tab) {
		return onClient(() -> {
			TabState s = SessionMirror.get().tab(tab);
			if (s == null) return List.<String>of();
			List<String> out = new ArrayList<>();
			for (MessageRecord r : s.entries()) { String k = token(r.message().content().getString()); if (k != null) out.add(k); }
			return out;
		});
	}

	int unread(String tab) { return onClient(() -> { TabState s = SessionMirror.get().tab(tab); return s == null ? 0 : s.unreadCount(); }); }

	void restore6() throws Exception {
		JsonObject live = JsonParser.parseString(Files.readString(LIVE_DUMP)).getAsJsonObject();
		String name = live.get("playerName").getAsString();
		Phase2Tests.write(Phase2Tests.cfg(), config(name));
		ctx.runOnClient(c -> {
			ConfigManager.get().devSimulateRestart();
			c.options.hideGui = false;
			c.options.chatVisibility().set(ChatVisiblity.FULL);
		});
		restoredTextUnit();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			// live traffic racing the asynchronous log load (V-02 style): each delivered exactly once
			sp.getServer().runOnServer(s -> {
				for (int i = 0; i < 5; i++) s.getPlayerList().broadcastSystemMessage(Component.literal("live during load cpx#R-live" + i), false);
			});
			// V-11: as soon as the restore has finished, while vanilla's main chat still shows the restored lines
			int waited = 0;
			while (waited < 400 && (onClient(ChatLog::isLoading) || onClient(ChatLog::isRestoring) || restoredWith("cpx#R-after").isEmpty())) { t.waitT(1); waited++; }
			t.waitT(2);
			v11(sp, waited);
			t.waitT(40);
			Map<String, Object> m = new LinkedHashMap<>();
			v03(live);
			v27(live, name);
			v27f(live);
			routingQ4();
			noDuplicatesAndOrder();
			v33c(sp);
			searchRestored(sp);
		}
		t.waitT(20);
		v31AndPrune();
		t.waitT(20);
	}

	/** P2 structural rules on constructed components (no game state). */
	void restoredTextUnit() {
		Map<String, Object> m = new LinkedHashMap<>();
		Style stamp = Style.EMPTY.withInsertion("1792295553552");
		MutableComponent ok = Component.empty().append(Component.literal("[10:00:00] ").withStyle(stamp)).append(Component.literal("<Steve> hi")).append(Component.literal(" x3"));
		MutableComponent noTime = Component.empty().append(Component.empty().withStyle(stamp)).append(Component.literal("system text")).append(Component.empty());
		Map<String, Component> cases = new LinkedHashMap<>();
		cases.put("recognized", ok);
		cases.put("recognized.timestampsOff", noTime);
		cases.put("plainString", Component.literal("[10:00:00] <Steve> hi"));
		cases.put("twoSiblings", Component.empty().append(Component.literal("[10:00:00] ").withStyle(stamp)).append(Component.literal("x")));
		cases.put("fourSiblings", Component.empty().append(Component.literal("[t] ").withStyle(stamp)).append(Component.literal("x")).append(Component.empty()).append(Component.literal("y")));
		cases.put("nonDigitInsertion", Component.empty().append(Component.literal("[t] ").withStyle(Style.EMPTY.withInsertion("12a"))).append(Component.literal("x")).append(Component.empty()));
		cases.put("emptyInsertion", Component.empty().append(Component.literal("[t] ").withStyle(Style.EMPTY.withInsertion(""))).append(Component.literal("x")).append(Component.empty()));
		cases.put("noInsertion", Component.empty().append(Component.literal("[t] ")).append(Component.literal("x")).append(Component.empty()));
		cases.put("rootText", Component.literal("r").append(Component.literal("[t] ").withStyle(stamp)).append(Component.literal("x")).append(Component.empty()));
		cases.put("translatableRoot", Component.translatable("chat.type.announcement", "a", "b").append(Component.literal("[t] ").withStyle(stamp)).append(Component.literal("x")).append(Component.empty()));
		cases.put("unicodeDigitInsertion", Component.empty().append(Component.literal("[t] ").withStyle(Style.EMPTY.withInsertion("١٢٣"))).append(Component.literal("x")).append(Component.empty()));
		Map<String, String> expect = new LinkedHashMap<>();
		expect.put("recognized", "RECOGNIZED:<Steve> hi");
		expect.put("recognized.timestampsOff", "RECOGNIZED:system text");
		expect.put("plainString", "AMBIGUOUS:[10:00:00] <Steve> hi");
		expect.put("twoSiblings", "AMBIGUOUS:x");
		expect.put("fourSiblings", "AMBIGUOUS:x");
		expect.put("nonDigitInsertion", "AMBIGUOUS:x");
		expect.put("emptyInsertion", "AMBIGUOUS:x");
		expect.put("noInsertion", "AMBIGUOUS:x");
		expect.put("rootText", "AMBIGUOUS:r[t] x");
		expect.put("translatableRoot", "AMBIGUOUS:" + Component.translatable("chat.type.announcement", "a", "b").getString() + "[t] x");
		expect.put("unicodeDigitInsertion", "AMBIGUOUS:x");
		boolean all = true;
		for (var e : cases.entrySet()) {
			RestoredText.Result r = RestoredText.reconstruct(e.getValue());
			String got = r.status() + ":" + r.matchText();
			boolean pass = got.equals(expect.get(e.getKey()));
			all &= pass;
			m.put(e.getKey(), row("got", got, "reason", r.reason(), "ok", pass));
		}
		// the component itself is never modified
		String before = ok.getString();
		RestoredText.reconstruct(ok);
		boolean unmodified = ok.getString().equals(before) && ok.getSiblings().size() == 3;
		ev.result("P2.unit", all && unmodified, "cases", m, "inputUnmodified", unmodified);
	}

	void v11(TestSingleplayerContext sp, int waitedTicks) {
		// r-filters shows rAngle, which holds only the restored player message: nothing may be drawn while chat is closed
		Map<String, Object> before = onClient(() -> {
			TabState s = SessionMirror.get().tab("rAngle");
			long restored = s == null ? 0 : s.entries().stream().filter(r -> r.origin() == Origin.RESTORED).count();
			long live = s == null ? 0 : s.entries().stream().filter(r -> r.origin() != Origin.RESTORED).count();
			int now = Minecraft.getInstance().gui.getGuiTicks();
			long inFadeWindow = s == null ? 0 : s.entries().stream().filter(r -> now - r.message().addedTime() < 200).count();
			return row("restoredEntries", restored, "liveEntries", live, "entriesInsideVanillaFadeWindow", inFadeWindow, "hudLines", WindowRenderer.hudLinesDrawn("r-filters"),
					"selectedTab", ConfigManager.get().config().window("r-filters").selectedTab());
		});
		ctx.takeScreenshot("p6-hud-after-restore");
		String name = onClient(() -> Minecraft.getInstance().getUser().getName());
		ctx.runOnClient(c -> c.player.connection.sendChat("hello restored live cpx#R-fresh"));
		t.waitT(5);
		int afterLive = onClient(() -> WindowRenderer.hudLinesDrawn("r-filters"));
		boolean ok = ((Long) before.get("restoredEntries")) >= 1 && ((Long) before.get("liveEntries")) == 0 && ((Long) before.get("entriesInsideVanillaFadeWindow")) >= 1
				&& ((Integer) before.get("hudLines")) == 0 && afterLive >= 1;
		ev.result("V-11.restored", ok, "ticksUntilRestoreDone", waitedTicks, "rightAfterRestore", before, "hudLinesAfterMatchingLiveMessage", afterLive,
				"note", "restored lines start faded in Extended's HUD (§6.1) although still inside vanilla's 10 s fade window; vanilla's own chat shows them (documented); live lines fade as vanilla does (Phase 2 hud checks)");
	}

	void v03(JsonObject live) {
		long restoredRecords = onClient(() -> SessionMirror.get().records().stream().filter(r -> r.origin() == Origin.RESTORED).count());
		long mainRestored = onClient(() -> {
			var tag = ChatPatchesAdapter.get().restoredTag();
			return AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().filter(g -> g.tag() == tag).count();
		});
		long liveMislabelled = onClient(() -> SessionMirror.get().records().stream().filter(r -> r.origin() == Origin.RESTORED && r.message().content().getString().contains("cpx#R-live")).count());
		long liveDuring = onClient(() -> SessionMirror.get().records().stream().filter(r -> r.origin() == Origin.LIVE && r.message().content().getString().contains("cpx#R-live")).count());
		ev.result("V-03", restoredRecords == mainRestored && restoredRecords > 0 && liveMislabelled == 0 && liveDuring == 5,
				"restoredRecords", restoredRecords, "restoredInMainChat", mainRestored, "liveTaggedRestored", liveMislabelled, "liveDuringLoadRecordedLive", liveDuring,
				"chatLogCountAtSave", live.get("chatLogMessageCount").getAsInt());
	}

	void v27(JsonObject live, String name) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok27 = true;
		// V-27: default nameFormat, player/team/say/system/plugin/translated/styled: matching text = live text, recognized
		for (String tok : new String[]{"cpx#R-player", "cpx#R-say", "cpx#R-team", "cpx#R-sys", "cpx#R-plugin", "cpx#R-legacy", "cpx#R-trans", "cpx#R-style", "cpx#R-after"}) {
			List<MessageRecord> rs = restoredWith(tok);
			String liveText = live.getAsJsonObject(tok).get("liveMatchText").getAsString();
			MessageRecord r = rs.isEmpty() ? null : rs.get(rs.size() - 1);
			String mt = r == null ? null : r.matchText();
			boolean pass = r != null && !r.ambiguous() && liveText.equals(mt);
			ok27 &= pass;
			m.put(tok, row("restoredCopies", rs.size(), "matchText", mt, "liveText", liveText, "ambiguous", r != null && r.ambiguous(), "ok", pass));
		}
		boolean angle = tabTokens("rAngle").contains("cpx#R-player");
		boolean noStamp = !tabTokens("rStampNeg").contains("cpx#R-sys");
		boolean legacy = tabTokens("rLegacy").contains("cpx#R-legacy");
		ev.result("V-27", ok27 && angle && noStamp && legacy, "messages", m, "angleFilterMatchesRestoredPlayer", angle, "timestampNotInMatchingText", noStamp,
				"legacySectionCodesKept", legacy);
		// V-27b: custom nameFormat recognized, formatted name kept; older structures ambiguous, routed, never hidden
		MessageRecord custom = last(restoredWith("cpx#R-custom"));
		boolean customOk = custom != null && !custom.ambiguous() && custom.matchText().contains("[" + name + "]");
		Map<String, Object> edges = new LinkedHashMap<>();
		boolean edgesOk = true;
		for (String tok : EDGE) {
			MessageRecord r = last(restoredWith(tok));
			boolean routed = r != null && r.tabIds().contains("rTok") && r.tabIds().contains("rAll");
			boolean inMain = r != null && t.inMainChat(r.message());
			boolean pass = r != null && r.ambiguous() && routed && inMain;
			edgesOk &= pass;
			edges.put(tok, row("ambiguous", r != null && r.ambiguous(), "reason", r == null ? null : r.reconstructionNote(), "matchText", r == null ? null : r.matchText(),
					"routedToMatchingTabs", routed, "stillInMainChat", inMain, "ok", pass));
		}
		ev.result("V-27b", customOk && edgesOk, "customFormat", row("ambiguous", custom != null && custom.ambiguous(), "matchText", custom == null ? null : custom.matchText()),
				"olderStructures", edges, "notRun", "Chat Heads (not installed) and logs written by an actual older Chat Patches release (synthetic older shapes used)");
		// V-27c: genuine look-alike text is never stripped
		Map<String, Object> c = new LinkedHashMap<>();
		boolean cOk = true;
		for (String tok : new String[]{"cpx#R-gen1", "cpx#R-gen2", "cpx#R-gen3"}) {
			MessageRecord r = last(restoredWith(tok));
			String liveText = live.getAsJsonObject(tok).get("liveMatchText").getAsString();
			boolean pass = r != null && liveText.equals(r.matchText()) && tabTokens("rGen").contains(tok);
			cOk &= pass;
			c.put(tok, row("matchText", r == null ? null : r.matchText(), "inFilterTab", tabTokens("rGen").contains(tok), "ok", pass));
		}
		ev.result("V-27c", cOk, "messages", c);
		// V-27d: dupe counter removed from matching text, kept in display
		List<MessageRecord> dupes = restoredWith("cpx#R-dupe");
		MessageRecord d = last(dupes);
		String liveDisplay = live.getAsJsonObject("cpx#R-dupe").get("display").getAsString();
		boolean dOk = d != null && d.matchText().equals("dupe restored cpx#R-dupe") && !d.ambiguous() && d.message().content().getString().length() > d.matchText().length()
				&& d.message().content().getString().contains("dupe restored cpx#R-dupe");
		ev.result("V-27d", dOk, "restoredCopiesAlive", dupes.size(), "matchText", d == null ? null : d.matchText(), "display", d == null ? null : d.message().content().getString(),
				"liveDisplay", liveDisplay);
		// V-27e: no reversal of name formatting
		boolean bracket = tabTokens("rBracket").contains("cpx#R-custom");
		boolean notAngle = !tabTokens("rAngleCustom").contains("cpx#R-custom");
		ev.result("V-27e", bracket && notAngle && customOk, "bracketFilterMatches", bracket, "angleFilterDoesNotMatch", notAngle);
	}

	/** Client thread only. */
	static boolean inMain(GuiMessage g) {
		for (GuiMessage x : AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())) if (x == g) return true;
		return false;
	}

	static MessageRecord last(List<MessageRecord> l) { return l.isEmpty() ? null : l.get(l.size() - 1); }

	void v27f(JsonObject live) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		// dupe-counted: Chat Patches' log can hold several counted copies (§4.3); compare the restored survivor with the
		// live copy carrying the same timestamp insertion, by the V-27f criteria
		{
			JsonObject copies = live.getAsJsonObject("dupeCopies");
			Map<String, Object> d = onClient(() -> {
				MessageRecord r = last(SessionMirror.get().records().stream()
						.filter(x -> x.origin() == Origin.RESTORED && "cpx#R-dupe".equals(token(x.message().content().getString()))).toList());
				if (r == null) return null;
				Component c = r.message().content();
				boolean structure = AcceptanceTest.cpStructure(c);
				String ins = structure ? c.getSiblings().get(0).getStyle().getInsertion() : null;
				return row("insertion", ins, "structure", structure, "sibling1", structure ? c.getSiblings().get(1).getString() : null,
						"sibling2", structure ? c.getSiblings().get(2).getString() : null);
			});
			JsonObject liveCopy = d == null || d.get("insertion") == null ? null : copies.getAsJsonObject(String.valueOf(d.get("insertion")));
			boolean dOk = d != null && Boolean.TRUE.equals(d.get("structure")) && liveCopy != null
					&& liveCopy.get("sibling1").getAsString().equals(d.get("sibling1"));
			ok &= dOk;
			m.put("cpx#R-dupe", row("restored", d, "liveCopyWithSameInsertion", liveCopy == null ? null : GSON.fromJson(liveCopy, Map.class),
					"liveCopies", copies.size(), "ok", dOk, "criteria", "empty root, three siblings, same digit insertion as a live copy, identical sibling 1"));
		}
		for (String tok : new String[]{"cpx#R-player", "cpx#R-sys", "cpx#R-plugin", "cpx#R-style", "boundary"}) {
			JsonObject l = live.getAsJsonObject(tok);
			String liveTree = GSON.toJson(l.get("tree"));
			String restoredTree = onClient(() -> {
				for (MessageRecord r : SessionMirror.get().records().reversed()) {
					if (r.origin() != Origin.RESTORED) continue;
					boolean match = tok.equals("boundary") ? Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(r.message().content()))
							: tok.equals(token(r.message().content().getString()));
					if (match) return GSON.toJson(tree(r.message().content()));
				}
				return null;
			});
			boolean same = liveTree.equals(restoredTree);
			// the timestamp insertion is restored as saved; the structure check itself is the P2 rule
			boolean structure = restoredTree != null && onClient(() -> {
				for (MessageRecord r : SessionMirror.get().records().reversed()) {
					if (r.origin() == Origin.RESTORED && GSON.toJson(tree(r.message().content())).equals(restoredTree)) return AcceptanceTest.cpStructure(r.message().content());
				}
				return false;
			});
			if (tok.equals("boundary")) structure = true; // boundary lines are exempt before any structure check (Q10)
			ok &= same && structure;
			m.put(tok, row("identicalTree", same, "p2Structure", structure, "live", liveTree, "restored", restoredTree));
		}
		ev.result("V-27f", ok, "messages", m);
	}

	void routingQ4() {
		List<String> all = tabTokens("rAll"), none = tabTokens("rNone"), tok = tabTokens("rTok");
		long restoredInNone = onClient(() -> SessionMirror.get().tab("rNone").entries().stream().filter(r -> r.origin() == Origin.RESTORED).count());
		long liveInNone = onClient(() -> SessionMirror.get().tab("rNone").entries().stream().filter(r -> r.origin() == Origin.LIVE).count());
		Set<String> expected = new HashSet<>(List.of(STRUCTURED));
		expected.addAll(List.of(EDGE));
		boolean allHas = all.containsAll(expected) && tok.containsAll(expected);
		long boundaryRouted = onClient(() -> SessionMirror.get().records().stream()
				.filter(r -> Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(r.message().content())) && !r.tabIds().isEmpty()).count());
		long hiddenMissing = onClient(() -> SessionMirror.get().records().stream().filter(r -> r.origin() == Origin.RESTORED)
				.filter(r -> !inMain(r.message())).count());
		Set<String> missingAll = new HashSet<>(expected); missingAll.removeAll(all);
		Set<String> missingTok = new HashSet<>(expected); missingTok.removeAll(tok);
		ev.result("Q4.perTabRestore", allHas && restoredInNone == 0 && liveInNone >= 5 && boundaryRouted == 0 && hiddenMissing == 0,
				"restoreOnTabsHaveEveryFixture", allHas, "restoreOffTab.restoredEntries", restoredInNone, "restoreOffTab.liveEntries", liveInNone,
				"boundaryLinesRouted", boundaryRouted, "restoredRecordsMissingFromMainChat", hiddenMissing, "missingFromRAll", missingAll, "missingFromRTok", missingTok, "note", "rTok has hideFromMainChat on: nothing is hidden (hiding is Phase 7)");
	}

	void noDuplicatesAndOrder() {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = onClient(() -> {
			boolean good = true;
			List<GuiMessage> main = new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())).reversed();
			Map<GuiMessage, Integer> pos = new java.util.IdentityHashMap<>();
			for (int i = 0; i < main.size(); i++) pos.put(main.get(i), i);
			for (TabState s : SessionMirror.get().tabStates()) {
				List<MessageRecord> e = s.entries();
				Set<MessageRecord> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
				int last = -1;
				for (MessageRecord r : e) {
					if (!seen.add(r)) { m.put(s.id() + ".duplicateEntry", r.seq()); good = false; }
					Integer p = pos.get(r.message());
					if (p == null) { m.put(s.id() + ".notInMainChat", r.seq()); good = false; continue; }
					if (p < last) { m.put(s.id() + ".outOfOrder", r.seq()); good = false; }
					last = p;
				}
			}
			// live messages sent during loading: exactly once in rAll and rNone
			for (int i = 0; i < 5; i++) {
				String k = "cpx#R-live" + i;
				for (String tab : List.of("rAll", "rNone")) {
					long n = SessionMirror.get().tab(tab).entries().stream().filter(r -> k.equals(token(r.message().content().getString()))).count();
					if (n != 1) { m.put(tab + "." + k, n); good = false; }
				}
			}
			// a restored message's tab entries match its main-chat copies exactly
			for (String k : STRUCTURED) {
				long inMain = main.stream().filter(g -> g.tag() == ChatPatchesAdapter.get().restoredTag() && k.equals(token(g.content().getString()))).count();
				long inTab = SessionMirror.get().tab("rAll").entries().stream().filter(r -> r.origin() == Origin.RESTORED && k.equals(token(r.message().content().getString()))).count();
				if (inMain != inTab) { m.put("rAll." + k, inTab + " vs main " + inMain); good = false; }
			}
			return good;
		});
		ev.result("Restore.noDuplicatesOrder", ok, "problems", m, "checks", "every tab: no repeated entry, every entry in the main chat, main-chat order; live messages during loading exactly once; restored copies per token equal in tab and main chat");
	}

	void v33c(TestSingleplayerContext sp) {
		Map<String, Integer> afterRestore = new LinkedHashMap<>();
		for (String tab : List.of("rAll", "rNone", "rTok", "rAngle", "rBracket", "rGen")) afterRestore.put(tab, unread(tab));
		long flaggedRestored = onClient(() -> SessionMirror.get().tabStates().stream()
				.mapToLong(s -> s.entries().stream().filter(r -> r.origin() == Origin.RESTORED && s.isUnread(r)).count()).sum());
		// live messages during loading went to unselected tabs (rNone, rTok): flagged per the delivery rule
		int noneBefore = unread("rNone");
		t.sys(sp, "unread check cpx#R-u1");
		t.waitT(4);
		int noneAfter = unread("rNone");
		int allAfter = unread("rAll"); // selected in a visible window: read on delivery
		ev.result("V-33c.restored", flaggedRestored == 0 && noneAfter == noneBefore + 1 && allAfter == 0, "unreadAfterRestore", afterRestore,
				"restoredEntriesFlagged", flaggedRestored, "liveToUnselectedTab", noneBefore + "->" + noneAfter, "selectedTabUnread", allAfter);
	}

	void searchRestored(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		p3.openChat("");
		EditBox f = onClient(() -> ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen));
		ctx.runOnClient(c -> f.setValue("system restored"));
		t.waitT(2);
		List<String> plain = onClient(() -> ChatOpenRenderer.viewOf("r-main").stream().map(r -> token(r.message().content().getString())).toList());
		// a query that would only match through the timestamp text follows Chat Patches' semantics (processed text)
		ctx.runOnClient(c -> { ChatPatches.config.regex = true; f.setValue(""); f.setValue(".*system restored cpx#R-sys.*"); });
		t.waitT(2);
		List<String> regex = onClient(() -> ChatOpenRenderer.viewOf("r-main").stream().map(r -> token(r.message().content().getString())).toList());
		ctx.takeScreenshot("p6-search-restored");
		ctx.runOnClient(c -> { f.setValue(""); ChatPatches.config.regex = false; });
		t.waitT(2);
		int full = onClient(() -> ChatOpenRenderer.viewOf("r-main").size());
		int tab = onClient(() -> SessionMirror.get().tab("rAll").size());
		p3.closeChat();
		boolean ok = plain.contains("cpx#R-sys") && regex.contains("cpx#R-sys") && full == tab;
		m.put("plainView", plain);
		m.put("regexView", regex);
		m.put("clearedViewSize", full);
		m.put("tabSize", tab);
		ev.result("Search.restored", ok, "observed", m, "note", "regex results are Chat Patches' own (Q31); restored entries take part like live ones");
	}

	/** Boundary lines across world switches (V-31), then pruning and clearing (liveness, nothing invented). */
	void v31AndPrune() {
		Map<String, Object> m = new LinkedHashMap<>();
		int boundariesBefore = onClient(Phase6Tests::boundaryCount);
		Map<String, Object> second;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			second = boundaryState();
		}
		t.waitT(20);
		Map<String, Object> third;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			third = boundaryState();
			m.put("boundariesInMainChat", boundariesBefore + " -> " + second.get("count") + " -> " + third.get("count"));
			m.put("afterSecondSwitch", second);
			m.put("afterThirdSwitch", third);
			boolean v31 = Boolean.TRUE.equals(second.get("newestIsBoundary")) && Boolean.TRUE.equals(second.get("exemptNoTabs"))
					&& Boolean.TRUE.equals(third.get("newestIsBoundary")) && Boolean.TRUE.equals(third.get("exemptNoTabs"))
					&& third.get("count").equals(second.get("count")) && Boolean.TRUE.equals(third.get("allBoundaryRecordsUnrouted"));
			ev.result("V-31", v31, "observed", m, "note", "the catch-all hiding tab rTok (hideFromMainChat on) never takes a boundary line; Chat Patches replaces its own line on the next switch (count unchanged)");

			// pruning: a small main-chat cap trims the oldest (restored) entries; tabs follow by liveness, nothing invented
			int restoredBefore = onClient(() -> (int) SessionMirror.get().tab("rAll").entries().stream().filter(r -> r.origin() == Origin.RESTORED).count());
			ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 40);
			for (int i = 0; i < 45; i++) t.sys(sp, "prune filler " + i + " cpx#R-p" + i);
			t.waitT(10);
			Map<String, Object> pr = onClient(() -> {
				int main = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).size();
				long restoredLeft = SessionMirror.get().tab("rAll").entries().stream().filter(r -> r.origin() == Origin.RESTORED).count();
				long notInMain = SessionMirror.get().tabStates().stream().flatMap(s -> s.entries().stream()).filter(r -> !inMain(r.message())).count();
				return row("mainChatSize", main, "restoredLeftInRAll", restoredLeft, "tabEntriesNotInMainChat", notInMain, "rAllSize", SessionMirror.get().tab("rAll").size());
			});
			ctx.runOnClient(c -> c.gui.getChat().clearMessages(false));
			t.waitT(10);
			long afterClear = onClient(() -> SessionMirror.get().tabStates().stream().mapToLong(TabState::size).sum());
			ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 16384);
			boolean pruneOk = restoredBefore > 0 && ((Integer) pr.get("mainChatSize")) <= 40 && ((Long) pr.get("restoredLeftInRAll")) == 0
					&& ((Long) pr.get("tabEntriesNotInMainChat")) == 0 && afterClear == 0;
			ev.result("Restore.pruneAndClear", pruneOk, "restoredInRAllBefore", restoredBefore, "afterPruning", pr, "tabEntriesAfterClear", afterClear);
		}
	}

	/** Client thread only. */
	static int boundaryCount() {
		int n = 0;
		for (GuiMessage g : AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()))
			if (Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(g.content()))) n++;
		return n;
	}

	Map<String, Object> boundaryState() {
		return onClient(() -> {
			List<GuiMessage> main = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
			GuiMessage newest = main.isEmpty() ? null : main.get(0);
			boolean isB = newest != null && Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(newest.content()));
			MessageRecord rec = null;
			for (MessageRecord r : SessionMirror.get().records()) if (r.message() == newest) rec = r;
			boolean allUnrouted = SessionMirror.get().records().stream()
					.filter(r -> Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(r.message().content()))).allMatch(r -> r.tabIds().isEmpty());
			return row("newestIsBoundary", isB, "exemptNoTabs", rec != null && rec.tabIds().isEmpty(), "count", boundaryCount(), "allBoundaryRecordsUnrouted", allUnrouted);
		});
	}
}
