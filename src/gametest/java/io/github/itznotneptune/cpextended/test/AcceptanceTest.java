// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.compat.ChatPatchesBackend;
import io.github.itznotneptune.cpextended.compat.SearchFieldHandles;
import io.github.itznotneptune.cpextended.core.Capture;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.Origin;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.filter.LiteralMatcher;
import io.github.itznotneptune.cpextended.filter.Matcher;
import io.github.itznotneptune.cpextended.model.RoutingConfig;
import io.github.itznotneptune.cpextended.model.TabConfig;
import io.github.itznotneptune.cpextended.model.WindowConfig;
import io.github.itznotneptune.cpextended.test.Evidence.Outcome;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Milestone 1a acceptance tests against the production code. Each launch runs one mode from {@code cpx-mode.txt};
 * evidence goes to {@code cpx-evidence/<mode>.jsonl}. All chat content is synthetic.
 */
public final class AcceptanceTest implements FabricClientGameTest {
	private static final Pattern TOKEN = Pattern.compile("cpx#[A-Za-z0-9_-]+");

	private ClientGameTestContext ctx;
	private Evidence ev;
	private String mode;

	@Override
	public void runTest(ClientGameTestContext context) {
		ctx = context;
		mode = Mode.read();
		ev = new Evidence(mode);
		ev.data("mode", "mode", mode, "time", Instant.now().toString(), "diagnosticsEnabled", Diagnostics.ENABLED,
				"devDisabled", DevHooks.disabled());
		try {
			ctx.runOnClient(c -> resetChatPatchesConfig());
			switch (mode) {
				case "live" -> live();
				case "restore" -> restore();
				case "clear" -> clear();
				case "faults" -> faults();
				case "conflict_bool", "conflict_int" -> conflict();
				case "structural_off", "structural_base", "structural_tabs" -> structural();
				default -> ev.data("error", "msg", "unknown mode " + mode);
			}
		} catch (Throwable t) {
			ev.data("error", "msg", t.toString());
			Evidence.LOG.error("scenario failed", t);
		} finally {
			ev.write();
		}
	}

	// =================================================================== helpers

	static void resetChatPatchesConfig() {
		var c = ChatPatches.config;
		c.chatlog = true;
		c.vanillaClearing = false;
		c.counter = true;
		c.compactChat = false;
		c.compactDistance = 0;
	}

	record Rec(long seq, String text, Origin origin, boolean ambiguous, List<String> tabs, GuiMessage msg, Component original, MessageRecord ref) {
		String token() {
			var m = TOKEN.matcher(text);
			return m.find() ? m.group() : null;
		}
	}

	void waitT(int n) { ctx.waitTicks(n); }

	void sys(TestSingleplayerContext sp, String text) {
		sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(Component.literal(text), false));
	}

	void sysComponent(TestSingleplayerContext sp, Component c) {
		sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(c, false));
	}

	void local(String text) {
		ctx.runOnClient(c -> c.gui.getChat().addMessage(Component.literal(text)));
	}

	List<Rec> recs() {
		return ctx.computeOnClient(c -> {
			List<Rec> out = new ArrayList<>();
			for (MessageRecord r : SessionMirror.get().records()) {
				out.add(new Rec(r.seq(), r.original().getString(), r.origin(), r.ambiguous(), List.copyOf(r.tabIds()), r.message(), r.original(), r));
			}
			return out;
		});
	}

	/** Records of this launch's own traffic: history restored from an earlier launch may repeat a token. */
	List<Rec> recsWith(String token) {
		return recs().stream().filter(r -> r.origin() != Origin.RESTORED && token.equals(r.token())).toList();
	}

	Rec one(String token) {
		List<Rec> l = recsWith(token);
		return l.size() == 1 ? l.get(0) : null;
	}

	List<MessageRecord> tabEntries(String id) {
		return ctx.computeOnClient(c -> {
			TabState t = SessionMirror.get().tab(id);
			return t == null ? List.<MessageRecord>of() : t.entries();
		});
	}

	boolean tabSuspended(String id) {
		return ctx.computeOnClient(c -> {
			TabState t = SessionMirror.get().tab(id);
			return t != null && t.isRuntimeSuspended();
		});
	}

	@SuppressWarnings("unchecked")
	static List<GuiMessage> allMessagesReflective(ChatComponent chat) {
		try {
			Field f = ChatComponent.class.getDeclaredField("allMessages");
			f.setAccessible(true);
			return (List<GuiMessage>) f.get(chat);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	List<GuiMessage> allMessages() {
		return ctx.computeOnClient(c -> new ArrayList<>(allMessagesReflective(c.gui.getChat())));
	}

	boolean inMainChat(GuiMessage m) {
		return ctx.computeOnClient(c -> {
			for (GuiMessage g : allMessagesReflective(c.gui.getChat())) if (g == m) return true;
			return false;
		});
	}

	boolean mainChatContains(String text) {
		return ctx.computeOnClient(c -> {
			for (GuiMessage g : allMessagesReflective(c.gui.getChat())) if (g.content().getString().contains(text)) return true;
			return false;
		});
	}

	void install(RoutingConfig cfg) {
		ctx.runOnClient(c -> Router.get().install(cfg));
	}

	static TabConfig tab(String id, List<FilterSpec> include, List<FilterSpec> exclude) {
		return new TabConfig(id, id, true, false, true, false, include, exclude);
	}

	static TabConfig tab(String id, String... includes) {
		List<FilterSpec> inc = new ArrayList<>();
		for (String s : includes) inc.add(FilterSpec.literal(s));
		return tab(id, inc, List.of());
	}

	static TabConfig matchAll(String id) {
		return new TabConfig(id, id, true, false, true, true, List.of(), List.of());
	}

	static RoutingConfig config(TabConfig... tabs) {
		return RoutingConfig.of(List.of(new WindowConfig("w-test", true, List.of(tabs))));
	}

	/** Chat Patches' modified-message shape: empty root, three siblings, digit insertion on sibling 0 (S3, S4). */
	static boolean cpStructure(Component c) {
		if (c.getSiblings().size() != 3) return false;
		if (!(c.getContents() instanceof PlainTextContents p) || !p.text().isEmpty()) return false;
		String ins = c.getSiblings().get(0).getStyle().getInsertion();
		return ins != null && !ins.isEmpty() && ins.chars().allMatch(ch -> ch >= '0' && ch <= '9');
	}

	int toasts() { return ctx.computeOnClient(c -> Warnings.toastsShown()); }

	// =================================================================== live (first launch)

	void live() {
		adapterStartupChecks();
		literalUnitChecks();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			waitT(40);
			install(liveConfig());
			v01(sp);
			v01b(sp);
			v24(sp);
			v29(sp);
			v47(sp);
			v05();
			v25b(sp);
			List<String> run1 = v22Script(sp);
			ctx.runOnClient(c -> c.gui.getChat().clearMessages(false));
			waitT(5);
			List<String> run2 = v22Script(sp);
			ev.data("v22_run", "run", "live-1", "routing", run1);
			ev.result("V-22.sameLaunch", run1.equals(run2) && !run1.isEmpty(), "entries", run1.size(), "run1", run1, "run2", run2);
			v37();
			structuralNoTabsVsTabs(sp);
			retentionCheck(sp);

			// Bulk history for V-02/V-03 on the next launch.
			install(liveConfig());
			sp.getServer().runOnServer(s -> {
				for (int i = 0; i < 3000; i++) s.getPlayerList().broadcastSystemMessage(Component.literal("bulk entry " + i + " cpx#B" + i), false);
			});
			waitT(120);
			int logged = ctx.computeOnClient(c -> ChatLog.messageCount());
			int mirrored = ctx.computeOnClient(c -> SessionMirror.get().size());
			int mainChat = allMessages().size();
			ev.data("bulk", "chatLogMessageCount", logged, "mirrorRecords", mirrored, "mainChat", mainChat);
			ev.result("mirror.matchesMainChat", mirrored == mainChat, "mirrorRecords", mirrored, "mainChat", mainChat,
					"fullReconciles", ctx.computeOnClient(c -> SessionMirror.get().fullReconciles()));
		}
		waitT(40); // disconnect: Chat Patches writes logs/chatlog.json
	}

	RoutingConfig liveConfig() {
		return config(
				matchAll("all"),
				tab("tok", "cpx#"),
				tab("sc", "[SC]"),
				tab("scCase", List.of(FilterSpec.literal("[SC]", true)), List.of()),
				tab("lit-dot", "a.c"), tab("lit-star", "x*y"), tab("lit-bs", "a\\b"),
				tab("lit-caret", "^start"), tab("lit-dollar", "end$"), tab("lit-paren", "f(x"),
				tab("ws", "double  space"), tab("sect", "§l"), tab("emoji", "😀"), tab("lone", "\uD800"),
				tab("tripleA", "cpx#TRIPLE"), tab("tripleB", "cpx#TRIPLE"), tab("tripleC", "cpx#TRIPLE"),
				tab("trade", List.of(FilterSpec.literal("[Trade]")), List.of(FilterSpec.literal("[bot]"))),
				tab("invalid-regex", List.of(new FilterSpec("regex", "cpx#", false)), List.of()),
				tab("invalid-empty", List.of(FilterSpec.literal("")), List.of()));
	}

	void adapterStartupChecks() {
		Map<String, Object> st = ctx.computeOnClient(c -> {
			Map<String, Object> m = new LinkedHashMap<>();
			ChatPatchesAdapter a = ChatPatchesAdapter.get();
			m.put("selfChecked", a.isSelfChecked());
			m.put("degraded", a.isDegraded());
			m.put("searchIntegrationOff", a.isSearchIntegrationOff());
			m.put("failedOps", a.failedOps().toString());
			m.put("warnings", Warnings.counts().toString());
			m.put("toasts", Warnings.toastsShown());
			m.put("searchBarShown", String.valueOf(a.isSearchBarShown()));
			m.put("queryNoScreen", a.searchQuery(null).status().name());
			m.put("queryBeforeInit", a.searchQuery(new ChatScreen("", false)).status().name());
			return m;
		});
		boolean ok = Boolean.TRUE.equals(st.get("selfChecked")) && Boolean.FALSE.equals(st.get("degraded"))
				&& Boolean.FALSE.equals(st.get("searchIntegrationOff")) && "[]".equals(st.get("failedOps")) && "{}".equals(st.get("warnings"));
		ev.result("V-15.selfCheck", ok, st);
		ev.result("V-59.1", ok && !"null".equals(st.get("searchBarShown")), "searchBarShown", st.get("searchBarShown"), "warnings", st.get("warnings"));
		ev.result("V-59.2", "NOT_AVAILABLE".equals(st.get("queryNoScreen")) && "NOT_AVAILABLE".equals(st.get("queryBeforeInit")) && ok,
				"queryNoScreen", st.get("queryNoScreen"), "queryBeforeInit", st.get("queryBeforeInit"));

		// V-59.3: wrong name, wrong type, wrong static-ness are refused by the resolver (no guessing).
		Map<String, String> v3 = new LinkedHashMap<>();
		v3.put("missing", SearchFieldHandles.resolve(ChatScreen.class, "cpxNoSuchField", true, boolean.class).error());
		v3.put("wrongTypeVisibility", SearchFieldHandles.resolve(ChatScreen.class, "showSearchBar", true, int.class).error());
		v3.put("visibilityNotStatic", SearchFieldHandles.resolve(ChatScreen.class, "showSearchBar", false, boolean.class).error());
		v3.put("queryStatic", SearchFieldHandles.resolve(ChatScreen.class, "searchField", true, EditBox.class).error());
		v3.put("wrongTypeQuery", SearchFieldHandles.resolve(ChatScreen.class, "searchField", false, String.class).error());
		boolean allRefused = v3.values().stream().allMatch(e -> e != null);
		ev.result("V-59.3.resolver", allRefused, "errors", v3,
				"note", "resolver-level variants; end-to-end Class Q fallback is exercised by conflict_bool/conflict_int");
	}

	void literalUnitChecks() {
		record C(String pattern, boolean cs, String text, boolean expected) {}
		List<C> cases = List.of(
				new C("[SC]", false, "[SC] hi", true), new C("[SC]", false, "x[SC]y", true), new C("[SC]", false, "[sc] hi", true),
				new C("[SC]", true, "[SC] hi", true), new C("[SC]", true, "x[SC]y", true), new C("[SC]", true, "[sc] hi", false),
				new C(".", false, "abc", false), new C("a.c", false, "abc", false), new C("a.c", false, "a.c", true),
				new C("*", false, "abc", false), new C("x*y", false, "xxy", false), new C("x*y", false, "x*y", true),
				new C("\\", false, "a\\b", true), new C("\\d", false, "5", false), new C("^a", false, "ab", false), new C("^a", false, "x^ab", true),
				new C("a$", false, "ba", false), new C("a$", false, "ba$", true), new C("(", false, "f(x)", true), new C("(a)", false, "a", false),
				new C("abc", false, "ab", false), new C("ÄBC", false, "äbc", true), new C("ÄBC", true, "äbc", false),
				new C("😀", false, "x😀y", true), new C("\uD800", false, "a\uD800b", true), new C("\uD800", false, "a😀b", false),
				new C("  ", false, "a  b", true), new C("  ", false, "a b", false), new C("§c", false, "§cred", true));
		List<String> wrong = new ArrayList<>();
		for (C c : cases) {
			boolean got = new LiteralMatcher(c.pattern(), c.cs()).matches(c.text());
			if (got != c.expected()) wrong.add(c.toString());
		}
		ev.result("V-24.unit", wrong.isEmpty(), "cases", cases.size(), "wrong", wrong);
	}

	void v01(TestSingleplayerContext sp) {
		ctx.runOnClient(c -> c.player.connection.sendChat("hello player cpx#V01-player"));
		sys(sp, "system message cpx#V01-system");
		local("client local cpx#V01-local");
		sysComponent(sp, Component.translatable("chat.type.announcement", "Server", "translated cpx#V01-trans"));
		ctx.runOnClient(c -> c.player.connection.sendCommand("list"));
		waitT(20);
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		for (String t : List.of("cpx#V01-player", "cpx#V01-system", "cpx#V01-local", "cpx#V01-trans")) {
			List<Rec> l = recsWith(t);
			Rec r = l.size() == 1 ? l.get(0) : null;
			boolean rowOk = r != null && r.origin() == Origin.LIVE && !cpStructure(r.original()) && cpStructure(r.msg().content())
					&& r.tabs().contains("tok") && r.tabs().contains("all") && inMainChat(r.msg());
			ok &= rowOk;
			rows.add(row("token", t, "captures", l.size(), "origin", r == null ? null : r.origin(),
					"originalHasCpStructure", r != null && cpStructure(r.original()), "processedHasCpStructure", r != null && cpStructure(r.msg().content()),
					"tabs", r == null ? null : r.tabs(), "ok", rowOk));
		}
		// Command feedback has no token: find it by its translation key.
		List<Rec> feedback = recs().stream().filter(r -> r.original().getContents() instanceof TranslatableContents tc && tc.getKey().startsWith("commands.list")).toList();
		Rec f = feedback.size() == 1 ? feedback.get(0) : null;
		boolean fOk = f != null && f.origin() == Origin.LIVE && !cpStructure(f.original()) && cpStructure(f.msg().content()) && f.tabs().contains("all");
		ok &= fOk;
		rows.add(row("token", "(command feedback commands.list.*)", "captures", feedback.size(), "ok", fOk));
		ev.result("V-01", ok, "rows", rows);
	}

	void v01b(TestSingleplayerContext sp) {
		List<String> texts = List.of(
				"  leading spaces cpx#V01B-a",
				"trailing spaces cpx#V01B-b   ",
				"double  space cpx#V01B-c",
				"§cred §lbold literal codes cpx#V01B-d",
				"emoji 😀 pair cpx#V01B-e",
				"lone \uD800 surrogate cpx#V01B-f");
		for (String t : texts) local(t);
		waitT(5);
		List<String> networkTexts = List.of("  net leading cpx#V01B-na", "net trailing cpx#V01B-nb  ", "net double  space cpx#V01B-nc",
				"net §lcode cpx#V01B-nd", "net emoji 😀 cpx#V01B-ne");
		for (String t : networkTexts) sys(sp, t);
		waitT(10);
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		Map<String, String> expectTab = Map.of("cpx#V01B-c", "ws", "cpx#V01B-d", "sect", "cpx#V01B-e", "emoji", "cpx#V01B-f", "lone",
				"cpx#V01B-nc", "ws", "cpx#V01B-nd", "sect", "cpx#V01B-ne", "emoji");
		List<String> all = new ArrayList<>(texts);
		all.addAll(networkTexts);
		for (String t : all) {
			var m = TOKEN.matcher(t);
			m.find();
			String token = m.group();
			Rec r = one(token);
			boolean exact = r != null && r.text().equals(t);
			boolean tabOk = !expectTab.containsKey(token) || (r != null && r.tabs().contains(expectTab.get(token)));
			ok &= exact && tabOk;
			rows.add(row("token", token, "exact", exact, "expectedTab", expectTab.get(token), "tabOk", tabOk,
					"lengthIn", t.length(), "lengthCaptured", r == null ? null : r.text().length()));
		}
		ev.result("V-01b", ok, "rows", rows);
	}

	void v24(TestSingleplayerContext sp) {
		String[][] msgs = {
				{"[SC] hi cpx#V24-1", "sc,scCase"}, {"x[SC]y cpx#V24-2", "sc,scCase"}, {"[sc] hi cpx#V24-3", "sc"},
				{"a.c cpx#V24-dot1", "lit-dot"}, {"abc cpx#V24-dot2", ""}, {"x*y cpx#V24-star1", "lit-star"}, {"xxy cpx#V24-star2", ""},
				{"a\\b cpx#V24-bs1", "lit-bs"}, {"ab cpx#V24-bs2", ""}, {"^start here cpx#V24-c1", "lit-caret"}, {"start here cpx#V24-c2", ""},
				{"the end$ cpx#V24-d1", "lit-dollar"}, {"the end cpx#V24-d2", ""}, {"f(x) cpx#V24-p1", "lit-paren"}, {"fx cpx#V24-p2", ""},
				{"[Trade] selling cpx#V24-t1", "trade"}, {"[Trade] [bot] spam cpx#V24-t2", ""}};
		for (String[] m : msgs) sys(sp, m[0]);
		waitT(15);
		Set<String> literalTabs = Set.of("sc", "scCase", "lit-dot", "lit-star", "lit-bs", "lit-caret", "lit-dollar", "lit-paren", "trade");
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		for (String[] m : msgs) {
			var tm = TOKEN.matcher(m[0]);
			tm.find();
			Rec r = one(tm.group());
			List<String> expected = m[1].isEmpty() ? List.of() : List.of(m[1].split(","));
			List<String> got = r == null ? null : r.tabs().stream().filter(literalTabs::contains).sorted().toList();
			boolean rowOk = got != null && got.equals(expected.stream().sorted().toList());
			ok &= rowOk;
			rows.add(row("token", tm.group(), "expected", expected, "got", got, "ok", rowOk));
		}
		// Invalid filters (regex mode, empty) suspend their tab and are never executed (Q14, Q15, Q28).
		Map<String, List<String>> errors = ctx.computeOnClient(c -> Router.get().config().configErrorSummary());
		boolean invalidOk = errors.containsKey("invalid-regex") && errors.containsKey("invalid-empty")
				&& tabEntries("invalid-regex").isEmpty() && tabEntries("invalid-empty").isEmpty();
		ev.result("V-24", ok, "rows", rows);
		ev.result("Q14.invalidFiltersSuspendTab", invalidOk, "configErrors", errors);
	}

	void v29(TestSingleplayerContext sp) {
		sys(sp, "three tabs cpx#TRIPLE");
		waitT(8);
		Rec r = one("cpx#TRIPLE");
		List<MessageRecord> a = tabEntries("tripleA"), b = tabEntries("tripleB"), c = tabEntries("tripleC");
		boolean shared = r != null && a.size() == 1 && b.size() == 1 && c.size() == 1
				&& a.get(0) == r.ref() && b.get(0) == r.ref() && c.get(0) == r.ref()
				&& a.get(0).message() == r.msg() && inMainChat(r.msg());
		ev.result("V-29.routing", shared, "tabsOfRecord", r == null ? null : r.tabs(), "entries", List.of(a.size(), b.size(), c.size()),
				"note", "routing part only; hiding is not in Milestone 1a");
	}

	void v47(TestSingleplayerContext sp) {
		Diagnostics.clear();
		sys(sp, "[NEST] outer cpx#NEST-O");
		waitT(8);
		Rec outer = one("cpx#NEST-O"), inner = one("cpx#NEST-I");
		List<Map<String, Object>> delivers = Diagnostics.events("deliver");
		Integer outerDepth = null, innerDepth = null;
		for (Map<String, Object> e : delivers) {
			if (outer != null && Long.valueOf(outer.seq()).equals(e.get("seq"))) outerDepth = (Integer) e.get("depth");
			if (inner != null && Long.valueOf(inner.seq()).equals(e.get("seq"))) innerDepth = (Integer) e.get("depth");
		}
		int partial = ctx.computeOnClient(c -> Warnings.counts().getOrDefault("capture:partial_admission", 0));
		boolean ok = outer != null && inner != null && Integer.valueOf(1).equals(outerDepth) && Integer.valueOf(2).equals(innerDepth)
				&& partial == 0 && inMainChat(outer.msg()) && inMainChat(inner.msg()) && outer.tabs().contains("tok") && inner.tabs().contains("tok");
		ev.result("V-47", ok, "outerCaptures", recsWith("cpx#NEST-O").size(), "innerCaptures", recsWith("cpx#NEST-I").size(),
				"outerDepth", outerDepth, "innerDepth", innerDepth, "partialAdmissionWarnings", partial,
				"m2EqualsM3", "always admit in 1a (hiding not implemented); frames with unequal admissions would raise capture:partial_admission",
				"diagnosticsEnabled", Diagnostics.ENABLED);
	}

	void v05() {
		int recordsBefore = ctx.computeOnClient(c -> SessionMirror.get().size());
		int frameless0 = TestCounters.FRAMELESS_DISPLAY.get();
		ctx.runOnClient(c -> c.gui.getChat().rescaleChat());
		waitT(2);
		int afterRescale = TestCounters.FRAMELESS_DISPLAY.get();
		ctx.runOnClient(c -> {
			int old = c.options.guiScale().get();
			c.options.guiScale().set(old == 2 ? 3 : 2);
			c.resizeDisplay();
			c.options.guiScale().set(old);
			c.resizeDisplay();
		});
		waitT(4);
		int afterScale = TestCounters.FRAMELESS_DISPLAY.get();
		// Chat Patches search: type into its search field on an open chat screen.
		String searchNote;
		ctx.setScreen(() -> new ChatScreen("", false));
		waitT(4);
		searchNote = ctx.computeOnClient(c -> {
			try {
				Field f = ChatScreen.class.getDeclaredField("searchField");
				f.setAccessible(true);
				EditBox box = (EditBox) f.get(c.screen);
				if (box == null) return "search field null";
				box.setValue("cpx#V24");
				box.setValue("");
				return "typed";
			} catch (ReflectiveOperationException | RuntimeException e) {
				return "unavailable: " + e;
			}
		});
		waitT(4);
		ctx.setScreen(() -> null);
		waitT(2);
		int framelessTotal = TestCounters.FRAMELESS_DISPLAY.get() - frameless0;
		int recordsAfter = ctx.computeOnClient(c -> SessionMirror.get().size());
		ev.result("V-05", framelessTotal > 0 && recordsAfter == recordsBefore,
				"framelessDisplayCalls", framelessTotal, "byRescale", afterRescale - frameless0, "byGuiScale", afterScale - afterRescale,
				"search", searchNote, "recordsBefore", recordsBefore, "recordsAfter", recordsAfter);
	}

	void v25b(TestSingleplayerContext sp) {
		ctx.runOnClient(c -> {
			DevHooks.setMatcherDecorator((tabId, m) -> !tabId.equals("A") ? m : (Matcher) text -> {
				if (text.contains("cpx#V25-m")) throw new IllegalStateException("test double: matcher failure");
				return m.matches(text);
			});
			Router.get().install(config(tab("A", "BOOM"), tab("B", "cpx#V25"), tab("C", "cpx#V25")));
			DevHooks.setMatcherDecorator(null);
		});
		sys(sp, "BOOM before cpx#V25-pre");
		waitT(5);
		int toasts0 = toasts();
		sys(sp, "BOOM throws cpx#V25-m");
		waitT(5);
		sys(sp, "BOOM only A would match cpx#X25-m2");
		sys(sp, "BOOM later cpx#X25-later");
		waitT(8);
		Rec m = one("cpx#V25-m"), m2 = one("cpx#X25-m2"), later = one("cpx#X25-later"), pre = one("cpx#V25-pre");
		List<MessageRecord> a = tabEntries("A"), b = tabEntries("B"), c = tabEntries("C");
		long mInB = b.stream().filter(r -> m != null && r == m.ref()).count();
		long mInC = c.stream().filter(r -> m != null && r == m.ref()).count();
		int toastDelta = toasts() - toasts0;
		int keyCount = ctx.computeOnClient(cl -> Warnings.counts().getOrDefault("tab:A:matcher:java.lang.IllegalStateException", 0));
		boolean ok = tabSuspended("A") && !tabSuspended("B") && !tabSuspended("C")
				&& m != null && mInB == 1 && mInC == 1 && !m.tabs().contains("A") && inMainChat(m.msg())
				&& m2 != null && m2.tabs().isEmpty() && inMainChat(m2.msg())
				&& later != null && !later.tabs().contains("A")
				&& pre != null && a.size() == 1 && a.get(0) == pre.ref()
				&& toastDelta == 1 && keyCount == 1;
		ev.result("V-25b", ok, "suspended", Map.of("A", tabSuspended("A"), "B", tabSuspended("B"), "C", tabSuspended("C")),
				"mDeliveredToB", mInB, "mDeliveredToC", mInC, "mTabs", m == null ? null : m.tabs(), "m2Tabs", m2 == null ? null : m2.tabs(),
				"aHistory", a.size(), "toastDelta", toastDelta, "warningKeyCount", keyCount,
				"notApplicable", "hiding (m hidden via B) and edit-and-save are later phases; relaunch part runs in mode 'restore'");
		install(liveConfig());
	}

	List<String> v22Script(TestSingleplayerContext sp) {
		String[] script = {"[SC] alpha", "[Trade] beta", "[Trade] [bot] gamma", "a.c delta", "plain epsilon", "[SC] [Trade] zeta",
				"x*y eta", "dup theta", "dup theta", "f(x) iota", "BOOM kappa", "double  space lambda", "§lmu", "😀 nu"};
		for (int i = 0; i < script.length; i++) sys(sp, script[i] + " cpx#V22-" + i);
		waitT(20);
		List<String> out = new ArrayList<>();
		for (Rec r : recs()) {
			String t = r.token();
			if (t != null && t.startsWith("cpx#V22-")) out.add(t + "=" + r.tabs().stream().sorted().toList());
		}
		return out;
	}

	void v37() {
		Random rnd = new Random(37);
		Map<String, Object> results = new LinkedHashMap<>();
		double worstRealistic = 0;
		for (String variant : List.of("bmp", "emoji", "adversarial")) {
			String pattern = switch (variant) {
				case "bmp" -> randomLetters(rnd, 1024);
				case "emoji" -> "😀".repeat(511) + "zz";
				default -> "a".repeat(1023) + "b";
			};
			List<Matcher> tabs = new ArrayList<>();
			for (int t = 0; t < 20; t++) tabs.add(new LiteralMatcher(pattern, false));
			for (int len : new int[]{256, 4096, 32768}) {
				String text = switch (variant) {
					case "bmp" -> randomLetters(rnd, len);
					case "emoji" -> emojiHeavy(rnd, len);
					default -> "a".repeat(len);
				};
				int iters = variant.equals("adversarial") && len == 32768 ? 3 : 20;
				long worst = 0, total = 0;
				for (int i = 0; i < iters; i++) {
					long t0 = System.nanoTime();
					for (Matcher m : tabs) m.matches(text);
					long dt = System.nanoTime() - t0;
					worst = Math.max(worst, dt);
					total += dt;
				}
				double worstMs = worst / 1e6, meanMs = total / 1e6 / iters;
				results.put(variant + "/" + len, Map.of("meanMs", round(meanMs), "worstMs", round(worstMs)));
				if (!variant.equals("adversarial")) worstRealistic = Math.max(worstRealistic, worstMs);
			}
		}
		// End-to-end: the routing overhead inside a real addMessage call, 20 tabs vs none.
		Map<String, Object> e2e = new LinkedHashMap<>();
		RoutingConfig twenty = twentyTabs(randomLetters(new Random(1), 1024));
		for (int len : new int[]{256, 4096, 32768}) {
			String text = randomLetters(new Random(len), len) + " cpx#V37";
			long none = timeAdd(RoutingConfig.EMPTY, text), with = timeAdd(twenty, text);
			e2e.put(String.valueOf(len), Map.of("addMessageNoTabsMs", round(none / 1e6), "addMessage20TabsMs", round(with / 1e6)));
		}
		install(liveConfig());
		double adversarialWorst = ((Number) ((Map<?, ?>) results.get("adversarial/32768")).get("worstMs")).doubleValue();
		ev.result("V-37", worstRealistic < 50, "matcherCost20Tabs", results, "endToEnd", e2e, "worstRealisticMs", round(worstRealistic),
				"adversarialWorstMs", round(adversarialWorst), "criterion", "no frame stall over 50 ms (realistic variants); adversarial reported separately");
	}

	RoutingConfig twentyTabs(String pattern) {
		List<TabConfig> tabs = new ArrayList<>();
		for (int i = 0; i < 20; i++) tabs.add(tab("p" + i, pattern));
		return config(tabs.toArray(new TabConfig[0]));
	}

	long timeAdd(RoutingConfig cfg, String text) {
		install(cfg);
		long best = Long.MAX_VALUE;
		for (int i = 0; i < 5; i++) {
			long dt = ctx.computeOnClient(c -> {
				long t0 = System.nanoTime();
				c.gui.getChat().addMessage(Component.literal(text));
				return System.nanoTime() - t0;
			});
			best = Math.min(best, dt);
		}
		return best;
	}

	static String randomLetters(Random r, int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) sb.append((char) ('a' + r.nextInt(26)));
		return sb.toString();
	}

	static String emojiHeavy(Random r, int n) {
		StringBuilder sb = new StringBuilder(n);
		while (sb.length() + 2 <= n) sb.append(r.nextInt(4) == 0 ? "ab" : "😀");
		while (sb.length() < n) sb.append('x');
		return sb.toString();
	}

	static double round(double v) { return Math.round(v * 1000) / 1000.0; }

	/** Routing must not change what the main chat holds: same messages, same order, no tabs vs routing tabs. */
	void structuralNoTabsVsTabs(TestSingleplayerContext sp) {
		Function<String, List<String>> run = suffix -> {
			ctx.runOnClient(c -> c.gui.getChat().clearMessages(false));
			waitT(3);
			for (int i = 0; i < 6; i++) sys(sp, "struct " + i + " cpx#ST-" + i);
			sys(sp, "struct dup cpx#ST-dup");
			sys(sp, "struct dup cpx#ST-dup");
			waitT(10);
			List<String> out = new ArrayList<>();
			for (GuiMessage g : allMessages()) out.add(maskTimes(g.content().getString()) + "|tag=" + (g.tag() != null) + "|sig=" + (g.signature() != null));
			return out;
		};
		install(RoutingConfig.EMPTY);
		List<String> noTabs = run.apply("none");
		install(config(matchAll("s-all"), tab("s-tok", "cpx#ST")));
		List<String> withTabs = run.apply("tabs");
		install(liveConfig());
		ev.result("structural.mainChatSameWithAndWithoutRouting", noTabs.equals(withTabs) && !noTabs.isEmpty(),
				"entries", noTabs.size(), "noTabs", noTabs, "withTabs", withTabs);
	}

	/** Liveness (§4.7): an entry removed from the main chat outside addMessage leaves every tab on the next tick. */
	void retentionCheck(TestSingleplayerContext sp) {
		sys(sp, "to be deleted cpx#RET-1");
		waitT(5);
		Rec r = one("cpx#RET-1");
		boolean inTabBefore = r != null && tabEntries("tok").stream().anyMatch(e -> e == r.ref());
		ctx.runOnClient(c -> {
			List<GuiMessage> all = allMessagesReflective(c.gui.getChat());
			all.removeIf(g -> g == r.msg());
			c.gui.getChat().rescaleChat();
		});
		waitT(3);
		boolean goneFromTab = r != null && tabEntries("tok").stream().noneMatch(e -> e == r.ref());
		boolean goneFromMirror = r != null && recs().stream().noneMatch(x -> x.ref() == r.ref());
		ev.result("mirror.liveness", inTabBefore && goneFromTab && goneFromMirror, "inTabBefore", inTabBefore, "goneFromTab", goneFromTab,
				"goneFromMirror", goneFromMirror);
	}

	static String maskTimes(String s) {
		return s.replaceAll("\\d{1,2}:\\d{2}(:\\d{2})?", "HH:MM:SS");
	}

	static Map<String, Object> row(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
		return m;
	}

	// =================================================================== restore (second launch)

	void restore() throws Exception {
		Integer expected = null;
		List<String> liveRun = null;
		for (String line : Files.readAllLines(Path.of("cpx-evidence", "live.jsonl"))) {
			JsonObject o = JsonParser.parseString(line).getAsJsonObject();
			String t = o.get("t").getAsString();
			if (t.equals("bulk")) expected = o.get("chatLogMessageCount").getAsInt();
			if (t.equals("v22_run")) {
				liveRun = new ArrayList<>();
				for (var e : o.getAsJsonArray("routing")) liveRun.add(e.getAsString());
			}
		}
		install(liveConfig());
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			// Live traffic right at join, racing Chat Patches' asynchronous log load.
			sp.getServer().runOnServer(s -> {
				for (int i = 0; i < 20; i++) s.getPlayerList().broadcastSystemMessage(Component.literal("live early cpx#L" + i), false);
			});
			waitT(160);
			List<Rec> all = recs();
			Map<String, Integer> liveCounts = new HashMap<>();
			for (Rec r : all) if (r.origin() == Origin.LIVE && r.token() != null && r.token().startsWith("cpx#L")) liveCounts.merge(r.token(), 1, Integer::sum);
			int wrong = 0;
			for (int i = 0; i < 20; i++) if (!Integer.valueOf(1).equals(liveCounts.get("cpx#L" + i))) wrong++;
			ev.result("V-02", wrong == 0, "liveTokensNotExactlyOnce", wrong, "chatLogLoadingAtEnd", ctx.computeOnClient(c -> ChatLog.isLoading()));

			// Count captures, not surviving records: Chat Patches re-condenses duplicates during the restore, which removes
			// the earlier copy from the main chat (and so, by liveness, from the mirror).
			long restoredCaptures = Diagnostics.events("deliver").stream().filter(e -> "RESTORED".equals(e.get("origin"))).count();
			List<Rec> restored = all.stream().filter(r -> r.origin() == Origin.RESTORED).toList();
			long mainChatRestored = ctx.computeOnClient(c -> {
				var tag = ChatPatchesAdapter.get().restoredTag();
				return allMessagesReflective(c.gui.getChat()).stream().filter(g -> g.tag() == tag).count();
			});
			long liveTaggedRestored = restored.stream().filter(r -> r.token() != null && r.token().startsWith("cpx#L")).count();
			long bulkAsLive = all.stream().filter(r -> r.origin() == Origin.LIVE && r.token() != null && r.token().startsWith("cpx#B")).count();
			long restoredRouted = restored.stream().filter(r -> !r.tabs().isEmpty()).count();
			boolean liveRouted = all.stream().filter(r -> r.token() != null && r.token().startsWith("cpx#L")).allMatch(r -> r.tabs().contains("tok"));
			ev.result("V-03", expected != null && restoredCaptures == expected && restored.size() == mainChatRestored && liveTaggedRestored == 0 && bulkAsLive == 0,
					"restoredCaptures", restoredCaptures, "chatLogCountAtSave", expected, "restoredRecordsAlive", restored.size(),
					"restoredEntriesInMainChat", mainChatRestored, "liveTaggedRestored", liveTaggedRestored, "bulkRecordedAsLive", bulkAsLive);
			ev.result("restored.notRoutedIn1a", restoredRouted == 0 && liveRouted, "restoredWithTabs", restoredRouted, "liveRouted", liveRouted,
					"note", "restored history is routed with the P2 reconstruction in Phase 6");

			// V-25b relaunch: tab A is active again (suspension is session state only).
			install(config(tab("A", "BOOM")));
			sys(sp, "BOOM after relaunch cpx#X25-relaunch");
			waitT(8);
			Rec r = one("cpx#X25-relaunch");
			ev.result("V-25b.relaunch", r != null && r.tabs().contains("A") && !tabSuspended("A"), "tabs", r == null ? null : r.tabs());

			// V-22 across launches.
			install(liveConfig());
			List<String> run = v22Script(sp);
			ev.result("V-22.acrossLaunches", liveRun != null && liveRun.equals(run), "liveLaunch", liveRun, "thisLaunch", run);
		}
		waitT(20);
	}

	// =================================================================== clear (V-06)

	void clear() {
		install(config(tab("tok", "cpx#")));
		List<Map<String, Object>> cases = new ArrayList<>();
		boolean ok = true;
		for (boolean vc : new boolean[]{false, true}) {
			try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
				waitT(40);
				ctx.runOnClient(c -> ChatPatches.config.vanillaClearing = vc);
				for (int i = 0; i < 5; i++) sys(sp, "before f3d " + i + " cpx#K" + i);
				waitT(10);
				Diagnostics.clear();
				ctx.getInput().holdKey(InputConstants.KEY_F3);
				ctx.getInput().pressKey(InputConstants.KEY_D);
				ctx.getInput().releaseKey(InputConstants.KEY_F3);
				waitT(10);
				Map<String, Object> f3d = clearCase("f3d", vc, false);
				ok &= Boolean.TRUE.equals(f3d.get("ok"));
				cases.add(f3d);
				for (int i = 0; i < 5; i++) sys(sp, "before disconnect " + i + " cpx#J" + i);
				waitT(10);
				Diagnostics.clear();
			}
			waitT(30);
			Map<String, Object> disc = clearCase("disconnect", vc, true);
			ok &= Boolean.TRUE.equals(disc.get("ok"));
			cases.add(disc);
		}
		ev.result("V-06", ok, "cases", cases);
	}

	Map<String, Object> clearCase(String name, boolean vc, boolean history) {
		List<Map<String, Object>> events = Diagnostics.events("clear").stream().filter(e -> Boolean.valueOf(history).equals(e.get("history"))).toList();
		boolean expectedCleared = vc || !history;
		int mirror = ctx.computeOnClient(c -> SessionMirror.get().size());
		int main = allMessages().size();
		int tab = tabEntries("tok").size();
		long tokensAlive = ctx.computeOnClient(c -> allMessagesReflective(c.gui.getChat()).stream().filter(g -> g.content().getString().contains("cpx#")).count());
		boolean eventsOk = !events.isEmpty() && events.stream().allMatch(e -> Boolean.valueOf(expectedCleared).equals(e.get("cleared"))
				&& Boolean.valueOf(expectedCleared).equals(e.get("observedEmpty")));
		boolean ok = eventsOk && mirror == main && tab == tokensAlive && (expectedCleared ? main == 0 && tab == 0 : main > 0);
		return row("case", name, "vanillaClearing", vc, "expectedCleared", expectedCleared, "events", events, "mirrorRecords", mirror,
				"mainChat", main, "tabEntries", tab, "tokenMessagesInMainChat", tokensAlive, "ok", ok);
	}

	// =================================================================== faults (V-36)

	enum Fault {
		RESTORED_TAG_MISSING, RESTORED_TAG_NULL, VANILLA_CLEARING_MISSING, CHAT_MAX_MISSING, CHAT_MAX_ZERO, BOUNDARY_MISSING,
		CONDENSE_MISSING, SAFE_POINT_MISSING, CHAT_MAX_40000, COMPACT_NEGATIVE, COMPACT_ZERO, COMPACT_500, COUNTER_OFF
	}

	void faults() {
		install(config(tab("tok", "cpx#")));
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean classS = true, classL = true, unusual = true;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			waitT(40);
			for (Fault f : Fault.values()) {
				ChatPatchesBackend real = ctx.computeOnClient(c -> ChatPatchesAdapter.get().devRealBackend());
				ctx.runOnClient(c -> {
					Warnings.devReset();
					ChatPatchesAdapter.get().devReplaceBackend(faulty(real, f));
					ChatPatchesAdapter.get().selfCheck();
				});
				waitT(2);
				String token = "cpx#F-" + f.name();
				sys(sp, "fault case " + token);
				waitT(6);
				Rec r = one(token);
				Map<String, Object> st = ctx.computeOnClient(c -> {
					ChatPatchesAdapter a = ChatPatchesAdapter.get();
					return row("degraded", a.isDegraded(), "failedOps", a.failedOps().toString(), "toasts", Warnings.toastsShown(),
							"condenseWindow(30,10)", a.condenseWindow(30, 10), "safePoint", a.isSafePoint());
				});
				boolean degraded = Boolean.TRUE.equals(st.get("degraded"));
				int toasts = (Integer) st.get("toasts");
				boolean routed = r != null && r.tabs().contains("tok") && inMainChat(r.msg());
				boolean expectS = f.ordinal() <= Fault.BOUNDARY_MISSING.ordinal();
				boolean expectL = f == Fault.CONDENSE_MISSING || f == Fault.SAFE_POINT_MISSING;
				boolean ambiguousExpected = f == Fault.RESTORED_TAG_MISSING || f == Fault.RESTORED_TAG_NULL;
				boolean rowOk;
				if (expectS) {
					rowOk = degraded && toasts == 1 && routed && r.ambiguous() == ambiguousExpected
							&& (ambiguousExpected ? r.origin() == Origin.UNKNOWN : r.origin() == Origin.LIVE);
					classS &= rowOk;
				} else if (expectL) {
					int cw = (Integer) st.get("condenseWindow(30,10)");
					boolean fallbackOk = f == Fault.CONDENSE_MISSING ? cw == 10 : Boolean.FALSE.equals(st.get("safePoint"));
					rowOk = !degraded && toasts == 0 && routed && fallbackOk;
					classL &= rowOk;
				} else {
					int cw = (Integer) st.get("condenseWindow(30,10)");
					int expectedWindow = switch (f) {
						case COMPACT_NEGATIVE -> 10;  // min(-5, size) <= 0 -> P3 superset max(1, linesPerPage=10, |-5|)
						case COMPACT_ZERO -> 10;      // distance 0 -> linesPerPage
						case COMPACT_500 -> 30;
						case COUNTER_OFF -> 0;
						default -> 1;
					};
					rowOk = !degraded && toasts == 0 && routed && cw == expectedWindow;
					unusual &= rowOk;
				}
				rows.add(row("fault", f.name(), "expected", expectS ? "S" : expectL ? "L" : "valid-unusual", "state", st,
						"routed", routed, "origin", r == null ? null : r.origin(), "ambiguous", r == null ? null : r.ambiguous(), "ok", rowOk));

				if (f == Fault.VANILLA_CLEARING_MISSING) {
					// Clearing while vanillaClearing is unreadable follows the observed main chat.
					ctx.runOnClient(c -> c.gui.getChat().clearMessages(false));
					waitT(3);
					int mirror = ctx.computeOnClient(c -> SessionMirror.get().size());
					int main = allMessages().size();
					rows.add(row("fault", "VANILLA_CLEARING_MISSING+F3D", "mirrorRecords", mirror, "mainChat", main, "ok", mirror == main));
					classS &= mirror == main;
				}
			}
			ctx.runOnClient(c -> {
				Warnings.devReset();
				ChatPatchesAdapter.get().devReplaceBackend(null);
				ChatPatchesAdapter.get().selfCheck();
			});
			boolean restoredHealthy = ctx.computeOnClient(c -> !ChatPatchesAdapter.get().isDegraded());
			ev.result("V-36.classS", classS, "rows", rows.stream().filter(m -> "S".equals(m.get("expected")) || String.valueOf(m.get("fault")).contains("+")).toList(),
					"note", "fault injection through a substituted backend; stub-jar variant deferred to Phase 8; 'restored messages AMBIGUOUS' is"
							+ " checked as origin UNKNOWN when the restored tag is unreadable (restored routing is Phase 6)");
			ev.result("V-36.classL", classL, "rows", rows.stream().filter(m -> "L".equals(m.get("expected"))).toList());
			ev.result("V-36.validUnusualValues", unusual, "rows", rows.stream().filter(m -> "valid-unusual".equals(m.get("expected"))).toList());
			ev.result("V-36.backendRestored", restoredHealthy);
		}
		waitT(20);
	}

	static ChatPatchesBackend faulty(ChatPatchesBackend real, Fault f) {
		return new ChatPatchesBackend() {
			@Override public GuiMessageTag restoredIndicator() {
				if (f == Fault.RESTORED_TAG_MISSING) throw new NoSuchFieldError("RESTORED_INDICATOR");
				if (f == Fault.RESTORED_TAG_NULL) return null;
				return real.restoredIndicator();
			}
			@Override public boolean vanillaClearing() {
				if (f == Fault.VANILLA_CLEARING_MISSING) throw new NoSuchFieldError("vanillaClearing");
				return real.vanillaClearing();
			}
			@Override public int chatMaxMessages() {
				if (f == Fault.CHAT_MAX_MISSING) throw new NoSuchFieldError("chatMaxMessages");
				if (f == Fault.CHAT_MAX_ZERO) return 0;
				if (f == Fault.CHAT_MAX_40000) return 40000;
				return real.chatMaxMessages();
			}
			@Override public boolean isBoundaryLine(Component component) {
				if (f == Fault.BOUNDARY_MISSING) throw new NoSuchMethodError("isBoundaryLine");
				return real.isBoundaryLine(component);
			}
			@Override public boolean counter() {
				if (f == Fault.CONDENSE_MISSING) throw new NoSuchFieldError("counter");
				if (f == Fault.COUNTER_OFF) return false;
				return real.counter();
			}
			@Override public boolean compactChat() {
				return f == Fault.COMPACT_NEGATIVE || f == Fault.COMPACT_ZERO || f == Fault.COMPACT_500 || real.compactChat();
			}
			@Override public int compactDistance() {
				return switch (f) {
					case COMPACT_NEGATIVE -> -5;
					case COMPACT_ZERO -> 0;
					case COMPACT_500 -> 500;
					default -> real.compactDistance();
				};
			}
			@Override public boolean isLoading() {
				if (f == Fault.SAFE_POINT_MISSING) throw new NoSuchMethodError("isLoading");
				return real.isLoading();
			}
			@Override public boolean isRestoring() { return real.isRestoring(); }
			@Override public boolean searchEnabled() { return real.searchEnabled(); }
		};
	}

	// =================================================================== conflict (V-59.4)

	void conflict() {
		Map<String, Object> st = ctx.computeOnClient(c -> {
			ChatPatchesAdapter a = ChatPatchesAdapter.get();
			List<String> fields = new ArrayList<>();
			for (Field f : ChatScreen.class.getDeclaredFields()) if (f.getName().contains("showSearchBar")) fields.add(f.getName() + ":" + f.getType().getName());
			return row("degraded", a.isDegraded(), "searchIntegrationOff", a.isSearchIntegrationOff(), "failedOps", a.failedOps().toString(),
					"toasts", Warnings.toastsShown(), "warnings", Warnings.counts().toString(), "searchBarShown", String.valueOf(a.isSearchBarShown()),
					"showSearchBarFields", fields);
		});
		boolean routed;
		install(config(tab("tok", "cpx#")));
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			waitT(40);
			sys(sp, "conflict routing cpx#CF-1");
			waitT(8);
			Rec r = one("cpx#CF-1");
			routed = r != null && r.tabs().contains("tok") && inMainChat(r.msg());
		}
		boolean ok = Boolean.FALSE.equals(st.get("degraded")) && Boolean.TRUE.equals(st.get("searchIntegrationOff"))
				&& String.valueOf(st.get("failedOps")).contains("SEARCH_VISIBILITY") && Integer.valueOf(1).equals(st.get("toasts"))
				&& "null".equals(st.get("searchBarShown")) && routed;
		ev.result("V-59.4." + mode, ok, "state", st, "routingUnaffected", routed);
		waitT(20);
	}

	// =================================================================== structural (Extended installed but inert)

	void structural() throws Exception {
		boolean off = mode.equals("structural_off");
		if (mode.equals("structural_tabs")) install(config(matchAll("s-all"), tab("s-tok", "cpx#")));
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("devDisabled", DevHooks.disabled());
		state.put("diagnosticsEnabled", Diagnostics.ENABLED);
		// By name: in structural_off the accessor isn't applied, and Mixin forbids loading a mixin-package class directly.
		state.put("accessorApplied", ctx.computeOnClient(c -> java.util.Arrays.stream(c.gui.getChat().getClass().getInterfaces())
				.anyMatch(i -> i.getName().endsWith("ChatComponentAccessor"))));
		List<String> main = new ArrayList<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			waitT(60);
			for (int i = 0; i < 8; i++) sys(sp, "structural message " + i + " cpx#SM-" + i);
			sys(sp, "structural duplicate cpx#SM-dup");
			sys(sp, "structural duplicate cpx#SM-dup");
			sysComponent(sp, Component.translatable("chat.type.announcement", "Server", "structural translated cpx#SM-tr"));
			local("structural local cpx#SM-local");
			waitT(20);
			main = ctx.computeOnClient(c -> {
				List<String> out = new ArrayList<>();
				var ops = RegistryOps.create(JsonOps.INSTANCE, c.level.registryAccess());
				for (GuiMessage g : allMessagesReflective(c.gui.getChat())) {
					String json = ComponentSerialization.CODEC.encodeStart(ops, g.content()).getOrThrow().toString();
					out.add(json + "|tag=" + (g.tag() == null ? "none" : "present") + "|sig=" + (g.signature() != null));
				}
				return out;
			});
			if (mode.equals("structural_tabs")) {
				int routed = tabEntries("s-all").size();
				state.put("routedToAllTab", routed);
			}
			state.put("chatLogCount", ctx.computeOnClient(c -> ChatLog.messageCount()));
		}
		waitT(40);
		Files.createDirectories(Path.of("cpx-evidence"));
		Path log = Path.of("logs", "chatlog.json");
		if (Files.exists(log)) Files.copy(log, Path.of("cpx-evidence", mode + "-chatlog.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.write(Path.of("cpx-evidence", mode + "-mainchat.txt"), main);
		boolean setupOk = off ? Boolean.TRUE.equals(state.get("devDisabled")) && Boolean.FALSE.equals(state.get("accessorApplied"))
				: Boolean.FALSE.equals(state.get("devDisabled")) && Boolean.TRUE.equals(state.get("accessorApplied"));
		if (mode.equals("structural_base")) {
			ev.result("diagnostics.offByDefault", !Diagnostics.ENABLED && Diagnostics.events("deliver").isEmpty(), "enabled", Diagnostics.ENABLED);
		}
		ev.result("structural.setup." + mode, setupOk, "state", state, "mainChatEntries", main.size(),
				"note", "main chat and chatlog.json are compared across structural_off/base/tabs by tools/compare_structural.py");
	}
}
