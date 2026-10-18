// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.compat.ChatPatchesBackend;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.filter.Matcher;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.search.TabSearch;
import io.github.itznotneptune.cpextended.ui.HitTester;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.ChatVisiblity;
import obro1961.chatpatches.ChatPatches;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Phase 5 acceptance tests: global search over tabs (Q29a, P13, Q31, P12, Q29b). */
final class Phase5Tests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase3Tests p3;

	Phase5Tests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p3 = new Phase3Tests(t);
	}

	static final String CONFIG = "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
			+ "\"appearance\": {\"backgroundOpacity\": 1.0, \"textOpacity\": 1.0, \"scale\": 1.0, \"lineSpacing\": 0.0},\n"
			+ "\"windows\": [\n"
			+ " {\"id\": \"s-v\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.02, \"width\": 0.40, \"height\": 0.22, \"tabs\": [{\"id\": \"sv\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SV\"}]}]},\n"
			+ " {\"id\": \"s-m\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.26, \"width\": 0.40, \"height\": 0.22, \"selectedTab\": \"sm\", \"tabs\": [{\"id\": \"sm\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SM\"}]}, {\"id\": \"sm2\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SN\"}]}]},\n"
			+ " {\"id\": \"s-z\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.50, \"width\": 0.40, \"height\": 0.20, \"tabs\": [{\"id\": \"sz\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SZ\"}]}]},\n"
			+ " {\"id\": \"s-low\", \"anchor\": \"BOTTOM_LEFT\", \"x\": 0.0, \"y\": 0.06, \"width\": 0.40, \"height\": 0.10, \"tabs\": [{\"id\": \"slow\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#SL\"}]}]}\n"
			+ "]\n}\n";

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	ConfigManager mgr() { return ConfigManager.get(); }

	EditBox field() {
		return onClient(() -> Minecraft.getInstance().screen instanceof ChatScreen s ? ChatPatchesAdapter.get().searchFieldWidget(s) : null);
	}

	/** Sets Chat Patches' search field as typing would (its responder runs). */
	void query(String q) {
		EditBox f = field();
		ctx.runOnClient(c -> f.setValue(q));
		t.waitT(2);
	}

	List<String> view(String windowId) {
		return onClient(() -> ChatOpenRenderer.viewOf(windowId).stream().map(r -> token(r)).toList());
	}

	static String token2(String s) {
		int i = s.indexOf("cpx#");
		return i < 0 ? s : s.substring(i);
	}

	static String token(MessageRecord r) {
		String s = r.original() == null ? "" : r.original().getString();
		int i = s.indexOf("cpx#");
		return i < 0 ? s : s.substring(i);
	}

	TabSearch.Indicator indicator(String windowId) { return onClient(() -> ChatOpenRenderer.indicatorOf(windowId)); }

	int unread(String tab) { return onClient(() -> { TabState s = SessionMirror.get().tab(tab); return s == null ? 0 : s.unreadCount(); }); }

	boolean showSearchBarField() {
		return onClient(() -> {
			try {
				Field f = ChatScreen.class.getDeclaredField("showSearchBar");
				f.setAccessible(true);
				return f.getBoolean(null);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	void setShowSearchBar(boolean v) {
		ctx.runOnClient(c -> {
			try {
				Field f = ChatScreen.class.getDeclaredField("showSearchBar");
				f.setAccessible(true);
				f.setBoolean(null, v);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	void resetCp() {
		ctx.runOnClient(c -> {
			var cfg = ChatPatches.config;
			cfg.search = true;
			cfg.caseSensitive = true;
			cfg.regex = false;
			cfg.searchEmptyShowsFullChat = false;
			cfg.searchDrafting = true;
		});
		setShowSearchBar(true);
	}

	void setup(TestSingleplayerContext sp) {
		Phase2Tests.write(Phase2Tests.cfg(), CONFIG);
		ctx.runOnClient(c -> {
			mgr().devSimulateRestart();
			c.options.hideGui = false;
			c.options.chatVisibility().set(ChatVisiblity.FULL);
			c.options.chatWidth().set(0.4);
			c.gui.getChat().rescaleChat();
		});
		resetCp();
		for (String m : new String[]{"apple one cpx#SV-1", "banana two cpx#SV-2", "apple three cpx#SM-1", "Apple four cpx#SM-2", "grape five cpx#SM-3",
				"kiwi six cpx#SZ-1", "apple other tab cpx#SN-1", "low apple cpx#SL-1"}) t.sys(sp, m);
		for (int i = 0; i < 12; i++) t.sys(sp, "plain sm filler " + i + " cpx#SM-f" + i);
		t.waitT(8);
	}

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	// =================================================================== search

	void search() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			setup(sp);
			v46(sp);
			v46c(sp);
			v46b(sp);
			v46d(sp);
			v40();
			v46e(sp);
			v51(sp);
			v52(sp);
		}
		t.waitT(20);
	}

	void v46(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		p3.openChat("");
		// typed query, case-sensitive by default (Chat Patches' default)
		EditBox f = field();
		p3.click(f.getX() + 4, f.getY() + 4, 0);
		ctx.getInput().typeChars("apple");
		t.waitT(2);
		m.put("typed.sv", view("s-v"));
		m.put("typed.sm", view("s-m"));
		m.put("typed.sz", view("s-z") + " " + indicator("s-z"));
		boolean typed = view("s-v").equals(List.of("cpx#SV-1")) && view("s-m").equals(List.of("cpx#SM-1")) && view("s-z").isEmpty()
				&& indicator("s-z") == TabSearch.Indicator.NO_MATCHES;
		// case-insensitive (Chat Patches' setting)
		ctx.runOnClient(c -> ChatPatches.config.caseSensitive = false);
		t.waitT(2);
		boolean ci = view("s-m").equals(List.of("cpx#SM-1", "cpx#SM-2"));
		m.put("caseInsensitive.sm", view("s-m"));
		ctx.runOnClient(c -> ChatPatches.config.caseSensitive = true);
		// scroll the s-m search view up, then change the query: reset to newest match, no selection or unread change
		query("filler");
		ChatOpenRenderer.Layout lm = p3.layout("s-m");
		double[] fp = p3.freePoint("s-m");
		if (fp != null) p3.scrollAt(fp[0], fp[1], 1);
		int scrolled = onClient(() -> ChatOpenRenderer.scrollOf("s-m"));
		String selBefore = onClient(() -> mgr().config().window("s-m").selectedTab());
		int unreadBefore = unread("sm2");
		// paste
		EditBox pf = field();
		ctx.runOnClient(c -> { pf.setValue(""); pf.insertText("grape"); });
		t.waitT(2);
		boolean pasted = view("s-m").equals(List.of("cpx#SM-3"));
		int afterChange = onClient(() -> ChatOpenRenderer.scrollOf("s-m"));
		boolean scrollReset = scrolled > 0 && afterChange == 0 && selBefore.equals(onClient(() -> mgr().config().window("s-m").selectedTab())) && unread("sm2") == unreadBefore;
		m.put("scrolledBeforeChange", scrolled);
		m.put("scrollAfterQueryChange", afterChange);
		// clear restores each tab's normal view
		query("");
		boolean cleared = view("s-m").size() == onClient(() -> SessionMirror.get().tab("sm").size()) && view("s-v").size() == 2 && indicator("s-z") == TabSearch.Indicator.NONE;
		// drafting: a query re-applies on reopen (searchDrafting on), not with it off
		query("apple");
		p3.closeChat();
		p3.openChat("");
		boolean drafted = onClient(() -> TabSearch.state().active()) && view("s-v").equals(List.of("cpx#SV-1"));
		ctx.runOnClient(c -> ChatPatches.config.searchDrafting = false);
		p3.closeChat();
		p3.openChat("");
		boolean notDrafted = !onClient(() -> TabSearch.state().active());
		ctx.runOnClient(c -> ChatPatches.config.searchDrafting = true);
		query("");
		p3.closeChat();
		m.put("drafted", drafted);
		m.put("notDraftedWhenOff", notDrafted);
		ev.result("V-46", typed && ci && pasted && scrollReset && cleared && drafted && notDrafted, "observed", m, "scrollResetWithoutSelectionOrUnread", scrollReset,
				"note", "three tabs with different matches; each from its own history; the 'hidden entries only' tab variant needs hiding (Phase 7)");
	}

	void v46c(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		p3.openChat("");
		query("apple");
		// (a) zero matches, setting off
		boolean a = view("s-z").isEmpty() && indicator("s-z") == TabSearch.Indicator.NO_MATCHES && onClient(() -> ChatOpenRenderer.scrollOf("s-z")) == 0;
		ctx.takeScreenshot("p5-search-plain-apple");
		// (b)/(c) setting on during the search: full history, no indicator, next cycle
		ctx.runOnClient(c -> ChatPatches.config.searchEmptyShowsFullChat = true);
		t.waitT(2);
		boolean b = view("s-z").equals(List.of("cpx#SZ-1")) && indicator("s-z") == TabSearch.Indicator.NONE && view("s-v").equals(List.of("cpx#SV-1"));
		ctx.runOnClient(c -> ChatPatches.config.searchEmptyShowsFullChat = false);
		t.waitT(2);
		boolean c2 = view("s-z").isEmpty() && indicator("s-z") == TabSearch.Indicator.NO_MATCHES;
		// (d) a matching arrival appears immediately; (e) a non-matching one stays out until the search clears
		t.sys(sp, "apple late cpx#SV-3");
		t.sys(sp, "melon late cpx#SV-4");
		t.waitT(4);
		boolean d = view("s-v").equals(List.of("cpx#SV-1", "cpx#SV-3"));
		query("");
		boolean e = view("s-v").contains("cpx#SV-4");
		p3.closeChat();
		// (f) no filtering, no indicator, no failure: bar hidden, search off, no ChatScreen, before init
		int warn0 = onClient(() -> Warnings.counts().size());
		setShowSearchBar(false);
		p3.openChat("");
		query("apple");
		boolean hidden = !onClient(() -> TabSearch.state().active()) && indicator("s-z") == TabSearch.Indicator.NONE && view("s-v").size() == 4;
		query("");
		p3.closeChat();
		setShowSearchBar(true);
		ctx.runOnClient(c -> ChatPatches.config.search = false);
		p3.openChat("");
		boolean off = !onClient(() -> TabSearch.state().active());
		p3.closeChat();
		ctx.runOnClient(c -> ChatPatches.config.search = true);
		boolean noScreen = !onClient(() -> TabSearch.update(null).active());
		boolean beforeInit = !onClient(() -> TabSearch.update(new ChatScreen("", false)).active());
		boolean noFailure = onClient(() -> Warnings.counts().size()) == warn0 && !onClient(() -> ChatPatchesAdapter.get().isSearchIntegrationOff());
		m.put("a", a); m.put("b", b); m.put("c", c2); m.put("d", d); m.put("e", e);
		m.put("f.barHidden", hidden); m.put("f.searchOff", off); m.put("f.noScreen", noScreen); m.put("f.beforeInit", beforeInit); m.put("f.noFailure", noFailure);
		ev.result("V-46c", a && b && c2 && d && e && hidden && off && noScreen && beforeInit && noFailure, "observed", m,
				"note", "the invalid-regex part of (f) is reported separately (V-46c.invalidRegex)");
	}

	void v46b(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		// (1) display-only: compare everything before and after a burst of searches
		Map<String, List<Long>> entriesBefore = entries();
		Map<String, Integer> unreadBefore = unreadAll();
		byte[] cfgBefore = Phase2Tests.bytes(Phase2Tests.cfg());
		List<GuiMessage> mainBefore = p3.onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())));
		p3.openChat("");
		for (String q : new String[]{"a", "ap", "app", "apple", "zzz", "grape", ""}) query(q);
		ctx.runOnClient(c -> ChatPatches.config.caseSensitive = false);
		query("APPLE");
		ctx.runOnClient(c -> ChatPatches.config.searchEmptyShowsFullChat = true);
		query("nothing matches");
		ctx.runOnClient(c -> { ChatPatches.config.caseSensitive = true; ChatPatches.config.searchEmptyShowsFullChat = false; });
		query("");
		p3.closeChat();
		boolean same = entries().equals(entriesBefore) && unreadAll().equals(unreadBefore) && Arrays.equals(cfgBefore, Phase2Tests.bytes(Phase2Tests.cfg()))
				&& AcceptanceTest.sameIdentity(mainBefore, p3.onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()))));
		t.sys(sp, "routing after searches cpx#SM-r1");
		t.waitT(4);
		boolean routing = t.tabEntries("sm").stream().anyMatch(r -> token(r).equals("cpx#SM-r1"));
		m.put("1.identicalHistoryOrderUnreadConfigMainChat", same);
		m.put("1.routingAfterSearch", routing);
		// (2) search active; the selected tab's window becomes visible while some unread entries are filtered out
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("s-m", false)));
		t.sys(sp, "apple hidden-window one cpx#SM-h1");
		t.sys(sp, "melon hidden-window two cpx#SM-h2");
		t.waitT(4);
		int flagged = unread("sm");
		p3.openChat("");
		query("apple");
		int control = unread("sm"); // search alone: no change
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("s-m", true)));
		t.waitT(3);
		int afterVisible = unread("sm");
		m.put("2.flaggedWhileDisabled", flagged);
		m.put("2.controlSearchOnly", control);
		m.put("2.afterBecomingVisible", afterVisible);
		// (3) a non-matching message to the selected tab of a visible window: read, not shown, present after clearing
		t.sys(sp, "melon visible cpx#SM-v1");
		t.waitT(4);
		boolean notShown = !view("s-m").contains("cpx#SM-v1");
		int unreadV = unread("sm");
		query("");
		boolean presentAfter = view("s-m").contains("cpx#SM-v1");
		p3.closeChat();
		m.put("3.notShownWhileSearching", notShown);
		m.put("3.notUnread", unreadV == 0);
		m.put("3.presentAfterClear", presentAfter);
		ev.result("V-46b", same && routing && flagged == 2 && control == 2 && afterVisible == 0 && notShown && unreadV == 0 && presentAfter, "observed", m,
				"note", "hidden flags can't be compared yet (hiding is Phase 7)");
	}

	Map<String, List<Long>> entries() {
		return onClient(() -> {
			Map<String, List<Long>> m = new LinkedHashMap<>();
			for (TabState s : SessionMirror.get().tabStates()) m.put(s.id(), s.entries().stream().map(MessageRecord::seq).toList());
			return m;
		});
	}

	Map<String, Integer> unreadAll() {
		return onClient(() -> {
			Map<String, Integer> m = new LinkedHashMap<>();
			for (TabState s : SessionMirror.get().tabStates()) m.put(s.id(), s.unreadCount());
			return m;
		});
	}

	void v46d(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		ctx.runOnClient(c -> ChatPatches.config.regex = true);
		p3.openChat("");
		query(".*apple.*");
		List<String> sv = view("s-v");
		List<GuiMessage> cpResults = onClient(() -> new ArrayList<>(ChatPatchesAdapter.get().searchResults((ChatScreen) Minecraft.getInstance().screen)));
		boolean onlyCpResults = onClient(() -> ChatOpenRenderer.viewOf("s-v").stream().allMatch(r -> cpResults.stream().anyMatch(g -> g == r.message())));
		m.put("validQuery.sv", sv);
		m.put("onlyEntriesInChatPatchesResults", onlyCpResults);
		// a later arrival whose text would match isn't shown as a match; the partial indicator appears
		t.sys(sp, "apple after the regex query cpx#SV-5");
		t.waitT(4);
		boolean lateNotShown = !view("s-v").contains("cpx#SV-5");
		TabSearch.Indicator partial = indicator("s-v");
		ctx.takeScreenshot("p5-search-regex-partial");
		m.put("laterArrivalNotShown", lateNotShown);
		m.put("indicator", partial);
		// catastrophic pattern: Chat Patches runs it (its own engine); Extended only reads the result list
		Map<String, List<Long>> before = entries();
		Map<String, Integer> unreadBefore = unreadAll();
		long t0 = System.nanoTime();
		query("(a+)+\\1b");
		long ms = (System.nanoTime() - t0) / 1_000_000;
		boolean unchanged = entries().equals(before) && unreadAll().equals(unreadBefore);
		m.put("catastrophicQueryMs", ms);
		m.put("historyAndUnreadUnchanged", unchanged);
		query("");
		p3.closeChat();
		ctx.runOnClient(c -> ChatPatches.config.regex = false);
		ev.result("V-46d", onlyCpResults && lateNotShown && partial == TabSearch.Indicator.PARTIAL && unchanged, "observed", m,
				"note", "no regex in Extended: the build contains no java.util.regex reference (metadata-check.json, regexReferences); hidden-entry part needs hiding (Phase 7)");
		v46cInvalidRegex(sp);
	}

	/** V-46c (f), invalid regex, with the P12 searchError handle: no stale results, histories intact, recovery exact. */
	void v46cInvalidRegex(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		ctx.runOnClient(c -> ChatPatches.config.regex = true);
		p3.openChat("");
		query(".*apple.*");
		List<String> validView = view("s-v");
		Map<String, List<Long>> entriesBefore = entries();
		Map<String, Integer> unreadBefore = unreadAll();
		byte[] cfgBefore = Phase2Tests.bytes(Phase2Tests.cfg());
		List<GuiMessage> mainBefore = onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())));
		query("([unclosed");
		boolean cpReportsError = onClient(() -> cpSearchError() != null);
		var regexState = onClient(() -> ChatPatchesAdapter.get().regexState((ChatScreen) Minecraft.getInstance().screen));
		List<String> invalidView = view("s-v");
		List<String> fullSv = onClient(() -> SessionMirror.get().tab("sv").entries().stream().map(Phase5Tests::token).toList());
		boolean noStale = invalidView.equals(fullSv) && !invalidView.equals(validView);
		TabSearch.Indicator indInvalid = indicator("s-v");
		TabSearch.Indicator indInvalidZ = indicator("s-z");
		ctx.takeScreenshot("p5-search-invalid-regex");
		// a message arriving while the expression is invalid: routed normally, shown in the (full) view
		t.sys(sp, "apple during invalid cpx#SV-6");
		t.waitT(4);
		boolean arrivalShown = view("s-v").contains("cpx#SV-6");
		boolean routedNormally = t.tabEntries("sv").stream().anyMatch(r -> token(r).equals("cpx#SV-6")) && t.mainChatContains("cpx#SV-6");
		// another invalid expression: still no stale results
		query("(?<bad");
		boolean secondInvalid = indicator("s-v") == TabSearch.Indicator.INVALID && view("s-v").size() == fullSv.size() + 1;
		// corrected to the previous valid expression: Chat Patches' current results, the new arrival included, not partial
		query(".*apple.*");
		List<String> corrected = view("s-v");
		List<Long> correctedSeqs = onClient(() -> ChatOpenRenderer.viewOf("s-v").stream().map(MessageRecord::seq).toList());
		boolean noDup = correctedSeqs.size() == new java.util.HashSet<>(correctedSeqs).size();
		boolean correctedOk = corrected.equals(List.of("cpx#SV-1", "cpx#SV-3", "cpx#SV-5", "cpx#SV-6")) && indicator("s-v") == TabSearch.Indicator.NONE && noDup;
		// corrected to a different valid expression
		query("([unclosed");
		query(".*banana.*");
		boolean differentOk = view("s-v").equals(List.of("cpx#SV-2")) && indicator("s-v") == TabSearch.Indicator.NONE && indicator("s-z") == TabSearch.Indicator.NO_MATCHES;
		query("");
		p3.closeChat();
		// the same text in plain-text mode is an ordinary literal query (no regex anywhere)
		ctx.runOnClient(c -> ChatPatches.config.regex = false);
		p3.openChat("");
		query("([unclosed");
		boolean plainLiteral = view("s-v").isEmpty() && indicator("s-v") == TabSearch.Indicator.NO_MATCHES;
		query("");
		p3.closeChat();
		Map<String, List<Long>> entriesAfter = entries();
		// histories untouched apart from the one new arrival (appended to sv only)
		entriesAfter.computeIfPresent("sv", (k, v) -> v.subList(0, Math.max(0, v.size() - 1)));
		boolean preserved = entriesAfter.equals(entriesBefore) && unreadAll().equals(unreadBefore) && Arrays.equals(cfgBefore, Phase2Tests.bytes(Phase2Tests.cfg()));
		List<GuiMessage> mainAfter = onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())));
		// search never removes or reorders main-chat messages: every earlier message is still there, in order
		java.util.Set<GuiMessage> beforeSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		beforeSet.addAll(mainBefore);
		List<GuiMessage> keptInOrder = mainAfter.stream().filter(beforeSet::contains).toList();
		List<String> added = mainAfter.stream().filter(g -> !beforeSet.contains(g)).map(g -> token2(g.content().getString())).toList();
		// at the main-chat cap the oldest entries (the end of the newest-first list) may be trimmed by the arrival
		int removed = mainBefore.size() - keptInOrder.size();
		boolean onlyOldestTrimmed = AcceptanceTest.sameIdentity(mainBefore.subList(0, keptInOrder.size()), keptInOrder);
		boolean mainOk = onlyOldestTrimmed && removed <= added.size() && added.contains("cpx#SV-6");
		m.put("mainChat.sizes", mainBefore.size() + "->" + mainAfter.size());
		m.put("mainChat.removedFromOldestEnd", removed);
		m.put("mainChat.capChatMaxMessages", onClient(() -> ChatPatches.config.chatMaxMessages));
		boolean noFailure = !onClient(() -> ChatPatchesAdapter.get().isSearchIntegrationOff()) && !onClient(() -> ChatPatchesAdapter.get().isDegraded());
		m.put("chatPatchesReportsError", cpReportsError);
		m.put("adapterRegexState", String.valueOf(regexState));
		m.put("viewBeforeInvalid", validView);
		m.put("viewWithInvalid", invalidView);
		m.put("indicator.sv", indInvalid);
		m.put("indicator.sz", indInvalidZ);
		m.put("noStaleResults.fullHistoryShown", noStale);
		m.put("arrivalWhileInvalid.shown", arrivalShown);
		m.put("arrivalWhileInvalid.routedAndInMainChat", routedNormally);
		m.put("secondInvalidExpression", secondInvalid);
		m.put("correctedSameExpression", corrected);
		m.put("correctedNoDuplicates", noDup);
		m.put("correctedDifferentExpression", differentOk);
		m.put("plainModeLiteral", plainLiteral);
		m.put("historiesUnreadConfigPreserved", preserved);
		m.put("mainChatEarlierMessagesKeptInOrder", mainOk);
		m.put("mainChat.addedDuringTest", added);
		m.put("noFailureOrDegraded", noFailure);
		ev.result("V-46c.invalidRegex", cpReportsError && regexState == ChatPatchesAdapter.RegexState.INVALID && noStale && indInvalid == TabSearch.Indicator.INVALID
				&& indInvalidZ == TabSearch.Indicator.INVALID && arrivalShown && routedNormally && secondInvalid && correctedOk && differentOk && plainLiteral && preserved && mainOk && noFailure,
				"observed", m, "fallback", "invalid expression: every tab shows its full history (as Chat Patches' own chat does) marked 'Invalid search expression'; stale results never shown");
	}

	/** Chat Patches' own error field, read directly by the test (not through Extended). */
	static Object cpSearchError() {
		try {
			Field f = ChatScreen.class.getDeclaredField("searchError");
			f.setAccessible(true);
			return f.get(Minecraft.getInstance().screen);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	void v40() {
		Map<String, Object> m = new LinkedHashMap<>();
		int[] sc = p3.screen();
		p3.openChat("");
		int[] field = onClient(() -> ChatPatchesAdapter.get().searchFieldRect((ChatScreen) Minecraft.getInstance().screen));
		double[] pt = {field[0] + 30, field[1] + 5};
		boolean shownMatches = onClient(() -> Boolean.TRUE.equals(ChatPatchesAdapter.get().isSearchBarShown())) == showSearchBarField();
		String regionShown = onClient(() -> HitTester.protectedRegion((ChatScreen) Minecraft.getInstance().screen, pt[0], pt[1]));
		// hide via the mouse (search button), reopen: still hidden, and the window under the field now gets input
		p3.click(10, sc[1] - 27, 0);
		boolean hiddenNow = !showSearchBarField() && Boolean.FALSE.equals(onClient(() -> ChatPatchesAdapter.get().isSearchBarShown()));
		p3.closeChat();
		p3.openChat("");
		String regionHidden = onClient(() -> HitTester.protectedRegion((ChatScreen) Minecraft.getInstance().screen, pt[0], pt[1]));
		var top = onClient(() -> ChatOpenRenderer.topAt(pt[0], pt[1]));
		boolean windowGets = regionHidden == null && top != null && top.windowId().equals("s-low");
		// keyboard: Tab doesn't reach the button in chat (command suggestions consume it), so focus it as keyboard
		// navigation would, then press the real Enter key
		boolean keyboard = onClient(() -> {
			var s = Minecraft.getInstance().screen;
			for (var ch : s.children()) if (ch.getClass().getSimpleName().equals("SearchButton")) { s.setFocused(ch); return true; }
			return false;
		});
		if (keyboard) { ctx.getInput().pressKey(InputConstants.KEY_RETURN); t.waitT(2); }
		boolean stillOpen = onClient(() -> Minecraft.getInstance().screen instanceof ChatScreen);
		boolean keyboardToggled = keyboard && stillOpen && showSearchBarField() && Boolean.TRUE.equals(onClient(() -> ChatPatchesAdapter.get().isSearchBarShown()));
		p3.closeChat();
		setShowSearchBar(true);
		// visibility read forced to fail: the field region is reserved while config.search is on, one warning
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		ctx.runOnClient(c -> { Warnings.devReset(); ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) {
			@Override public boolean searchEnabled() { throw new IllegalStateException("test: visibility unreadable"); }
		}); ChatPatchesAdapter.get().selfCheck(); });
		setShowSearchBar(false);
		p3.openChat("");
		String reserved = onClient(() -> HitTester.protectedRegion((ChatScreen) Minecraft.getInstance().screen, pt[0], pt[1]));
		int toasts = onClient(Warnings::toastsShown);
		boolean degraded = onClient(() -> ChatPatchesAdapter.get().isDegraded());
		p3.closeChat();
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		setShowSearchBar(true);
		m.put("shownReportMatchesChatPatches", shownMatches);
		m.put("shownRegion", regionShown);
		m.put("hiddenByMouse", hiddenNow);
		m.put("hiddenAfterReopen.windowGetsInput", windowGets);
		m.put("keyboardFocusedSearchButton", keyboard);
		m.put("keyboardToggled", keyboardToggled);
		m.put("readFailure.region", reserved);
		m.put("readFailure.toasts", toasts);
		m.put("readFailure.degraded", degraded);
		boolean ok = "cp_search_field".equals(reserved)
				&& shownMatches && "cp_search_field".equals(regionShown) && hiddenNow && windowGets && toasts == 1 && !degraded;
		ev.result("V-40", ok && keyboardToggled, "observed", m,
				"note", "keyboard toggle: Enter pressed through the real input on the focused search button; focus set programmatically because Tab in chat goes to command suggestions");
	}

	void v46e(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		String[] cases = {"a.queryOrSettingsRead", "b.visibilityRead", "c.searchResults", "d.searchErrorRead", "e.searchErrorUninterpretable"};
		for (String cs : cases) {
			ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
			ChatPatchesBackend faulty = switch (cs) {
				case "a.queryOrSettingsRead" -> new Phase3Tests.DelegatingBackend(real) { @Override public boolean searchCaseSensitive() { throw new IllegalStateException("test: query side unreadable"); } };
				case "b.visibilityRead" -> new Phase3Tests.DelegatingBackend(real) { @Override public boolean searchEnabled() { throw new IllegalStateException("test: visibility unreadable"); } };
				case "c.searchResults" -> new Phase3Tests.DelegatingBackend(real) { @Override public java.util.List<GuiMessage> searchResults(ChatScreen s) { throw new IllegalStateException("test: results unreadable"); } };
				case "d.searchErrorRead" -> new Phase3Tests.DelegatingBackend(real) { @Override public Object searchError(Field h, ChatScreen s) { throw new IllegalStateException("test: searchError unreadable"); } };
				default -> new Phase3Tests.DelegatingBackend(real) { @Override public Object searchError(Field h, ChatScreen s) { return "not an exception"; } };
			};
			ctx.runOnClient(c -> { Warnings.devReset(); ChatPatchesAdapter.get().devReplaceBackend(faulty); ChatPatchesAdapter.get().selfCheck(); });
			boolean regexCase = cs.startsWith("c") || cs.startsWith("d") || cs.startsWith("e");
			if (regexCase) ctx.runOnClient(c -> ChatPatches.config.regex = true);
			p3.openChat("");
			query(regexCase ? ".*apple.*" : "apple");
			boolean searchOff = onClient(() -> !TabSearch.state().active()) && view("s-v").size() >= 2;
			boolean integrationOff = onClient(() -> ChatPatchesAdapter.get().isSearchIntegrationOff());
			String region = onClient(() -> {
				ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
				int[] r = ChatPatchesAdapter.get().searchFieldRect(s);
				return r == null ? null : HitTester.protectedRegion(s, r[0] + 30, r[1] + 5);
			});
			boolean cpStillSearches = onClient(() -> {
				var list = ((obro1961.chatpatches.accessor.ChatScreenAccess) Minecraft.getInstance().screen).getSearchResults();
				return list != null && !list.isEmpty();
			});
			query("");
			p3.closeChat();
			ctx.runOnClient(c -> ChatPatches.config.regex = false);
			t.sys(sp, "routing during " + cs + " cpx#SV-e");
			t.waitT(4);
			boolean routed = t.tabEntries("sv").stream().anyMatch(r -> token(r).equals("cpx#SV-e"));
			int toasts = onClient(Warnings::toastsShown);
			boolean degraded = onClient(() -> ChatPatchesAdapter.get().isDegraded());
			boolean rowOk = searchOff && integrationOff && region != null && routed && toasts == 1 && !degraded && cpStillSearches;
			ok &= rowOk;
			m.put(cs, row("tabSearchOff", searchOff, "integrationOff", integrationOff, "searchFieldRegion", region, "routing", routed, "toasts", toasts,
					"degraded", degraded, "chatPatchesOwnSearchWorks", cpStillSearches, "ok", rowOk));
			ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		}
		ev.result("V-46e", ok, "cases", m, "note", "(a) query-side read (Chat Patches settings) failing while visibility stays readable: the field region stays accurately protected; hiding tabs aren't active yet (Phase 7)");
	}

	void v51(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		ctx.runOnClient(c -> Warnings.devReset());
		// (a) the same failure repeatedly
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		ChatPatchesBackend failingResults = new Phase3Tests.DelegatingBackend(real) { @Override public java.util.List<GuiMessage> searchResults(ChatScreen s) { throw new IllegalStateException("test: results"); } };
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(failingResults); ChatPatchesAdapter.get().selfCheck(); ChatPatches.config.regex = true; });
		for (int i = 0; i < 3; i++) {
			p3.openChat("");
			query("apple" + i);
			p3.closeChat();
			// re-arm the same failure (the adapter turned search off after the first one)
			ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(failingResults); ChatPatchesAdapter.get().selfCheck(); });
		}
		Map<String, Integer> counts = onClient(Warnings::counts);
		int sameKeyCount = counts.entrySet().stream().filter(e -> e.getKey().contains("results read error")).mapToInt(Map.Entry::getValue).sum();
		int toastsA = onClient(Warnings::toastsShown);
		m.put("a.sameFailureOccurrences", sameKeyCount);
		m.put("a.toasts", toastsA);
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); ChatPatches.config.regex = false; Warnings.devReset(); });
		// (b) distinct failures: tab A runtime suspension, tab B, a search failure, degraded mode
		ctx.runOnClient(c -> {
			DevHooks.setMatcherDecorator((tabId, mm) -> (tabId.equals("sv") || tabId.equals("sz")) ? (Matcher) text -> { if (text.contains("BOOM-" + tabId)) throw new IllegalStateException("test"); return mm.matches(text); } : mm);
			Router.get().install(mgr().config().routing());
			DevHooks.setMatcherDecorator(null);
		});
		t.sys(sp, "BOOM-sv cpx#SV-boom");
		t.waitT(3);
		int afterA = onClient(Warnings::toastsShown);
		t.sys(sp, "BOOM-sz cpx#SZ-boom");
		t.waitT(3);
		int afterB = onClient(Warnings::toastsShown);
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(failingResults); ChatPatchesAdapter.get().selfCheck(); ChatPatches.config.regex = true; });
		p3.openChat("");
		query("x");
		p3.closeChat();
		int afterSearch = onClient(Warnings::toastsShown);
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) {
			@Override public net.minecraft.client.GuiMessageTag restoredIndicator() { return null; }
		}); ChatPatchesAdapter.get().selfCheck(); ChatPatches.config.regex = false; });
		int afterDegraded = onClient(Warnings::toastsShown);
		for (int i = 0; i < 3; i++) { p3.openChat(""); p3.closeChat(); }
		int afterReopens = onClient(Warnings::toastsShown);
		m.put("b.toastsAfterTabA", afterA);
		m.put("b.afterTabB", afterB);
		m.put("b.afterSearchFailure", afterSearch);
		m.put("b.afterDegraded", afterDegraded);
		m.put("b.afterThreeReopens", afterReopens);
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); Router.get().install(mgr().config().routing()); });
		boolean ok = sameKeyCount >= 3 && toastsA == 1 && afterA == 1 && afterB == 2 && afterSearch == 3 && afterDegraded == 4 && afterReopens == 4;
		ev.result("V-51", ok, "observed", m, "note", "one toast and log entry per distinct failure, repeats counted at debug level; the optional extra reminder on the next chat opening isn't implemented (P9 allows at most one)");
		// runtime-suspended tabs stay suspended until relaunch; restart the configuration's routing for later checks
		ctx.runOnClient(c -> mgr().devSimulateRestart());
	}

	void v52(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		ctx.runOnClient(c -> Warnings.devReset());
		String[] cases = {"compactDistance=-5", "compactDistance=0", "compactDistance=500", "counter=false", "chatMaxMessages=1", "chatMaxMessages=100000",
				"vanillaClearing=true", "chatlog=false", "search=false", "searchBarHidden", "emptyQuery", "invalidRegex"};
		for (String cs : cases) {
			ctx.runOnClient(c -> {
				AcceptanceTest.resetChatPatchesConfig();
				var cfg = ChatPatches.config;
				switch (cs) {
					case "compactDistance=-5" -> { cfg.compactChat = true; cfg.compactDistance = -5; }
					case "compactDistance=0" -> { cfg.compactChat = true; cfg.compactDistance = 0; }
					case "compactDistance=500" -> { cfg.compactChat = true; cfg.compactDistance = 500; }
					case "counter=false" -> cfg.counter = false;
					case "chatMaxMessages=1" -> cfg.chatMaxMessages = 1;
					case "chatMaxMessages=100000" -> cfg.chatMaxMessages = 100000;
					case "vanillaClearing=true" -> cfg.vanillaClearing = true;
					case "chatlog=false" -> cfg.chatlog = false;
					case "search=false" -> cfg.search = false;
					default -> {}
				}
				ChatPatchesAdapter.get().selfCheck();
			});
			if (cs.equals("searchBarHidden")) setShowSearchBar(false);
			p3.openChat("");
			if (cs.equals("emptyQuery")) query("");
			if (cs.equals("invalidRegex")) { ctx.runOnClient(c -> ChatPatches.config.regex = true); query("([bad"); }
			p3.closeChat();
			t.sys(sp, "unusual " + cs + " cpx#SV-u");
			t.waitT(3);
			boolean degraded = onClient(() -> ChatPatchesAdapter.get().isDegraded());
			int warnings = onClient(() -> Warnings.counts().size());
			ok &= !degraded && warnings == 0;
			m.put(cs, row("degraded", degraded, "failureWarnings", warnings));
			setShowSearchBar(true);
		}
		ctx.runOnClient(c -> { AcceptanceTest.resetChatPatchesConfig(); ChatPatches.config.chatMaxMessages = 16384; ChatPatches.config.regex = false; ChatPatchesAdapter.get().selfCheck(); });
		ctx.runOnClient(c -> { ChatPatches.config.chatMaxMessages = 0; ChatPatchesAdapter.get().selfCheck(); });
		boolean negative = onClient(() -> ChatPatchesAdapter.get().isDegraded());
		ctx.runOnClient(c -> { ChatPatches.config.chatMaxMessages = 16384; ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		m.put("negative.chatMaxMessages=0.degraded", negative);
		ev.result("V-52", ok && negative, "observed", m);
	}

	// =================================================================== searchperf (V-13)

	/** V-13; with {@code hiding}, the even-numbered tabs hide (mixed hiding traffic: half the messages leave the main chat). */
	void searchperf(boolean hiding) {
		String cfg;
		StringBuilder ws = new StringBuilder();
		double[][] pos = {{0.02, 0.02}, {0.02, 0.36}, {0.52, 0.02}, {0.52, 0.36}, {0.27, 0.66}};
		for (int w = 0; w < 5; w++) {
			if (w > 0) ws.append(",");
			ws.append("{\"id\": \"pw").append(w).append("\", \"anchor\": \"TOP_LEFT\", \"x\": ").append(pos[w][0]).append(", \"y\": ").append(pos[w][1])
					.append(", \"width\": 0.45, \"height\": 0.32, \"tabs\": [");
			for (int k = 0; k < 2; k++) {
				int tab = w * 2 + k;
				if (k > 0) ws.append(",");
				ws.append("{\"id\": \"pt").append(tab).append("\", \"hideFromMainChat\": ").append(hiding && tab % 2 == 0)
						.append(", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#P").append(tab % 10).append("-\"}]}");
			}
			ws.append("]}");
		}
		cfg = "{\"schemaVersion\": 1, \"windows\": [" + ws + "]}";
		Map<String, Object> m = new LinkedHashMap<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			Phase2Tests.write(Phase2Tests.cfg(), cfg);
			ctx.runOnClient(c -> { mgr().devSimulateRestart(); ChatPatches.config.chatMaxMessages = 16384; c.options.hideGui = false; });
			resetCp();
			// 16,384 messages over 10 tabs; even ones contain "alpha"
			for (int chunk = 0; chunk < 16; chunk++) {
				int base = chunk * 1024;
				ctx.runOnClient(c -> {
					for (int i = 0; i < 1024; i++) {
						int n = base + i;
						c.gui.getChat().addMessage(Component.literal((n % 2 == 0 ? "alpha " : "beta ") + "flood " + n + " cpx#P" + (n % 10) + "-" + n));
					}
				});
			}
			t.waitT(10);
			p3.openChat("");
			query("alpha");
			t.waitT(5);
			ctx.runOnClient(c -> ChatOpenRenderer.resetRenderStats());
			long rebuilds0 = TabSearch.rebuilds(), checks0 = TabSearch.incrementalChecks();
			var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long clientThread = onClient(() -> Thread.currentThread().threadId());
			long alloc0 = mx.getThreadAllocatedBytes(clientThread);
			// 10 s at ~50 messages/s (2-3 per tick), no query change
			int arrivals = 0;
			for (int tick = 0; tick < 200; tick++) {
				int burst = tick % 2 == 0 ? 2 : 3;
				for (int b = 0; b < burst; b++) {
					int n = 20000 + arrivals++;
					t.sys(sp, (n % 2 == 0 ? "alpha " : "beta ") + "live " + n + " cpx#P" + (n % 10) + "-" + n);
				}
				t.waitT(1);
			}
			long alloc1 = mx.getThreadAllocatedBytes(clientThread);
			long rebuildsDuringArrivals = TabSearch.rebuilds() - rebuilds0;
			long checks = TabSearch.incrementalChecks() - checks0;
			double renderMaxArrivals = onClient(ChatOpenRenderer::renderNanosMax) / 1e6;
			double renderMeanArrivals = onClient(ChatOpenRenderer::renderNanosMean) / 1e6;
			long framesArrivals = onClient(ChatOpenRenderer::renderFrames);
			long extAllocArrivals = onClient(ChatOpenRenderer::renderAllocatedBytes);
			// rapid typing while arrivals continue: one character per tick
			ctx.runOnClient(c -> ChatOpenRenderer.resetRenderStats());
			long rebuilds1 = TabSearch.rebuilds();
			String[] typing = {"a", "al", "alp", "alph", "alpha", "alpha ", "alpha l", "alpha li", "alpha liv", "alpha live", "alpha liv", "alpha li", "alpha l", "alpha", "alp", "a", ""};
			for (String q : typing) {
				EditBox f = field();
				ctx.runOnClient(c -> f.setValue(q));
				int n = 30000 + arrivals++;
				t.sys(sp, "alpha typing " + n + " cpx#P" + (n % 10) + "-" + n);
				t.waitT(1);
			}
			long rebuildsTyping = TabSearch.rebuilds() - rebuilds1;
			double renderMaxTyping = onClient(ChatOpenRenderer::renderNanosMax) / 1e6;
			double renderMeanTyping = onClient(ChatOpenRenderer::renderNanosMean) / 1e6;
			// regex mode: Chat Patches' results, arrivals continue (they stay unevaluated: partial results)
			ctx.runOnClient(c -> ChatPatches.config.regex = true);
			query(".*alpha.*");
			t.waitT(3);
			ctx.runOnClient(c -> ChatOpenRenderer.resetRenderStats());
			long rebuildsR0 = TabSearch.rebuilds();
			for (int tick = 0; tick < 100; tick++) {
				for (int b = 0; b < (tick % 2 == 0 ? 2 : 3); b++) {
					int n = 40000 + arrivals++;
					t.sys(sp, (n % 2 == 0 ? "alpha " : "beta ") + "regex " + n + " cpx#P" + (n % 10) + "-" + n);
				}
				t.waitT(1);
			}
			long rebuildsRegex = TabSearch.rebuilds() - rebuildsR0;
			double renderMaxRegex = onClient(ChatOpenRenderer::renderNanosMax) / 1e6;
			double renderMeanRegex = onClient(ChatOpenRenderer::renderNanosMean) / 1e6;
			long framesRegex = onClient(ChatOpenRenderer::renderFrames);
			long allocRegex = onClient(ChatOpenRenderer::renderAllocatedBytes);
			TabSearch.Indicator regexIndicator = indicator("pw0");
			query("");
			ctx.runOnClient(c -> ChatPatches.config.regex = false);
			p3.closeChat();
			m.put("retained", onClient(() -> SessionMirror.get().size()));
			m.put("arrivals", arrivals);
			m.put("arrivalPhase", row("renderMaxMs", round(renderMaxArrivals), "renderMeanMs", round(renderMeanArrivals), "fullRebuilds", rebuildsDuringArrivals,
					"incrementalChecks", checks, "frames", framesArrivals, "extendedRenderAllocatedMb", round(extAllocArrivals / 1e6),
					"extendedRenderAllocatedKbPerFrame", round(framesArrivals == 0 ? 0 : extAllocArrivals / 1e3 / framesArrivals),
					"wholeClientThreadAllocatedMb", round((alloc1 - alloc0) / 1e6)));
			m.put("regexPhase", row("renderMaxMs", round(renderMaxRegex), "renderMeanMs", round(renderMeanRegex), "fullRebuilds", rebuildsRegex,
					"extendedRenderAllocatedKbPerFrame", round(framesRegex == 0 ? 0 : allocRegex / 1e3 / framesRegex), "indicator", regexIndicator));
			m.put("typingPhase", row("renderMaxMs", round(renderMaxTyping), "renderMeanMs", round(renderMeanTyping), "fullRebuilds", rebuildsTyping, "queryChanges", typing.length));
			boolean ok = rebuildsDuringArrivals == 0 && renderMaxArrivals < 16.7 && renderMaxTyping < 16.7 && rebuildsTyping <= typing.length * 5L
					&& rebuildsRegex == 0 && renderMaxRegex < 16.7 && regexIndicator == TabSearch.Indicator.PARTIAL;
			if (hiding) {
				m.put("hiddenRetained", onClient(() -> SessionMirror.get().hiddenSize()));
				m.put("mainChatSize", onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).size()));
			}
			ev.result(hiding ? "V-13.mixedHiding" : "V-13", ok, "observed", m, "criteria", "Extended's chat-open render under one 60 fps frame (16.7 ms) at every frame; no full rebuilds from arrivals (incremental only); rebuilds only on query changes (<= visible windows per change)",
					"note", "extendedRenderAllocated* covers Extended's whole chat-open render call (vanilla text drawing included); wholeClientThread* is everything on the client thread over the 10 s (vanilla and Chat Patches included); mixed hiding is Phase 7");
		}
		t.waitT(20);
	}

	static double round(double v) { return Math.round(v * 1000) / 1000.0; }
}
