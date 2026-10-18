// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.compat.ChatPatchesBackend;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.Origin;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.filter.Matcher;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.search.TabSearch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7 Stage B: main-chat hiding (D2, F1-F3, Q3, Q10, Q16b, Q17, Q19, Q24, Q32, P3, P7, P9), hidden-reference
 * retention, and their integration with orphan restoration, search, unread and relocation. Synthetic chat only.
 */
final class Phase7bTests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase3Tests p3;

	Phase7bTests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p3 = new Phase3Tests(t);
	}

	static String tab(String id, boolean hide, String extra, String... patterns) {
		StringBuilder inc = new StringBuilder();
		for (int i = 0; i < patterns.length; i++) inc.append(i > 0 ? "," : "").append("{\"mode\": \"literal\", \"pattern\": \"").append(patterns[i]).append("\"}");
		return "{\"id\": \"" + id + "\", \"hideFromMainChat\": " + hide + extra + ", \"include\": [" + inc + "]}";
	}

	static final String CONFIG = "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n\"windows\": [\n"
			+ " {\"id\": \"h-a\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.02, \"width\": 0.45, \"height\": 0.3, \"selectedTab\": \"hAll\", \"tabs\": ["
			+ "{\"id\": \"hAll\", \"matchAll\": true},"
			+ tab("hHide", true, "", "cpx#HD") + ","
			+ tab("hHide2", true, "", "cpx#HD2") + ","
			+ tab("hMixed", false, "", "cpx#MX") + ","
			+ tab("hMixHide", true, "", "cpx#MX") + ","
			+ tab("hDisTab", true, "", "cpx#DT") + ","
			+ tab("hSus", true, "", "cpx#SU", "") + ","
			+ tab("hCond", false, "", "cpx#CD") + ","
			+ tab("hRun", true, "", "cpx#RN") + "," + tab("hRun2", true, "", "cpx#RM") + "]},\n"
			+ " {\"id\": \"h-dis\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.36, \"width\": 0.45, \"height\": 0.2, \"tabs\": ["
			+ tab("hDisWin", true, "", "cpx#DW") + "]},\n"
			+ " {\"id\": \"h-b\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.6, \"width\": 0.45, \"height\": 0.2, \"tabs\": ["
			+ tab("hOther", false, "", "cpx#OT") + "," + tab("hRest", true, "", "cpx#R-old") + "]}\n"
			+ "]\n}\n";

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	ConfigManager mgr() { return ConfigManager.get(); }

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	static String token(String s) {
		int i = s.indexOf("cpx#");
		if (i < 0) return s;
		int j = i + 4;
		while (j < s.length() && !Character.isWhitespace(s.charAt(j))) j++;
		return s.substring(i, j);
	}

	/** The newest record (visible or hidden) whose text has {@code tok}. */
	MessageRecord rec(String tok) {
		return onClient(() -> {
			MessageRecord best = null;
			for (MessageRecord r : SessionMirror.get().records()) if (r.original() != null && tok.equals(token(r.original().getString()))) best = r;
			for (MessageRecord r : SessionMirror.get().hiddenRecords()) if (r.original() != null && tok.equals(token(r.original().getString())) && (best == null || r.seq() > best.seq())) best = r;
			return best;
		});
	}

	boolean inMain(MessageRecord r) { return r != null && t.inMainChat(r.message()); }

	boolean inLines(String text) {
		return onClient(() -> {
			try {
				Field f = ChatComponent.class.getDeclaredField("trimmedMessages");
				f.setAccessible(true);
				@SuppressWarnings("unchecked") List<GuiMessage.Line> l = (List<GuiMessage.Line>) f.get(Minecraft.getInstance().gui.getChat());
				for (GuiMessage.Line line : l) if (Phase7aTests.text(line.content()).contains(text)) return true;
				return false;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	List<String> tabTokens(String tab) {
		return onClient(() -> {
			TabState s = SessionMirror.get().tab(tab);
			return s == null ? List.<String>of() : s.entries().stream().map(r -> token(r.message().content().getString())).toList();
		});
	}

	int unread(String tab) { return onClient(() -> { TabState s = SessionMirror.get().tab(tab); return s == null ? 0 : s.unreadCount(); }); }

	long deliveriesOf(String tok) {
		return onClient(() -> Diagnostics.events("deliver").stream().count()) == 0 ? 0
				: onClient(() -> SessionMirror.get().records().stream().filter(r -> r.original() != null && tok.equals(token(r.original().getString()))).count()
						+ SessionMirror.get().hiddenRecords().stream().filter(r -> r.original() != null && tok.equals(token(r.original().getString()))).count());
	}

	void send(TestSingleplayerContext sp, String text) {
		t.sys(sp, text);
		t.waitT(2);
	}

	void setup(TestSingleplayerContext sp) {
		Phase2Tests.write(Phase2Tests.cfg(), CONFIG);
		ctx.runOnClient(c -> {
			ChatPatchesAdapter.get().devReplaceBackend(null);
			ChatPatchesAdapter.get().selfCheck();
			Warnings.devReset();
			DevHooks.setMatcherDecorator(null);
			mgr().devSimulateRestart();
			AcceptanceTest.resetChatPatchesConfig();
			ChatPatches.config.chatMaxMessages = 16384;
			c.options.hideGui = false;
			c.gui.getChat().clearMessages(false);
		});
		t.waitT(3);
	}

	// ================================================================ mode hide

	void hide() throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			setup(sp);
			eligibility(sp);
			setup(sp);
			v45(sp);
			setup(sp);
			v45b(sp);
			setup(sp);
			v47(sp);
			setup(sp);
			condensation(sp);
			setup(sp);
			orphansWithRealHiding(sp);
			setup(sp);
			v48(sp);
			setup(sp);
			v50(sp);
			setup(sp);
			searchUnreadRelocation(sp);
			setup(sp);
			v18(sp);
			setup(sp);
			v22(sp);
			setup(sp);
			v44AndClear(sp);
			setup(sp);
			logDifferences(sp);
			setup(sp);
			v31(sp);
		}
		t.waitT(20);
	}

	/** Explicit checks 1-5 (and 4's runtime suspension): who may hide, who receives. */
	void eligibility(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		send(sp, "plain cpx#P1");
		send(sp, "multi cpx#HD2-1");
		send(sp, "mixed cpx#MX-1");
		send(sp, "disabled tab not yet cpx#X0");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hDisTab", "enabled", false)));
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("h-dis", false)));
		send(sp, "disabled tab cpx#DT-1");
		send(sp, "disabled window cpx#DW-1");
		send(sp, "config suspended cpx#SU-1");
		// runtime suspension of hRun: its matcher throws on BOOM
		ctx.runOnClient(c -> {
			DevHooks.setMatcherDecorator((id, mm) -> id.equals("hRun") ? (Matcher) text -> { if (text.contains("BOOM")) throw new IllegalStateException("test"); return mm.matches(text); } : mm);
			Router.get().install(mgr().config().routing());
			DevHooks.setMatcherDecorator(null);
		});
		send(sp, "BOOM cpx#RN-boom");
		send(sp, "after suspension cpx#RN-after");
		Map<String, MessageRecord> r = new LinkedHashMap<>();
		for (String k : new String[]{"cpx#P1", "cpx#HD2-1", "cpx#MX-1", "cpx#DT-1", "cpx#DW-1", "cpx#SU-1", "cpx#RN-boom", "cpx#RN-after"}) r.put(k, rec(k));
		boolean e1 = inMain(r.get("cpx#P1")) && !r.get("cpx#P1").hidden() && tabTokens("hAll").contains("cpx#P1");
		MessageRecord multi = r.get("cpx#HD2-1");
		boolean e2 = multi.hidden() && !inMain(multi) && !inLines("multi cpx#HD2-1") && multi.tabIds().containsAll(List.of("hAll", "hHide", "hHide2")) && multi.tabIds().size() == 3
				&& tabTokens("hHide").stream().filter(x -> x.equals("cpx#HD2-1")).count() == 1 && tabTokens("hHide2").stream().filter(x -> x.equals("cpx#HD2-1")).count() == 1;
		MessageRecord mixed = r.get("cpx#MX-1");
		boolean e3 = mixed.hidden() && mixed.tabIds().containsAll(List.of("hMixed", "hMixHide")) && !inMain(mixed);
		boolean e4tab = !r.get("cpx#DT-1").hidden() && inMain(r.get("cpx#DT-1")) && r.get("cpx#DT-1").tabIds().contains("hDisTab");
		boolean e4win = !r.get("cpx#DW-1").hidden() && inMain(r.get("cpx#DW-1")) && r.get("cpx#DW-1").tabIds().contains("hDisWin");
		boolean e4sus = !r.get("cpx#SU-1").hidden() && inMain(r.get("cpx#SU-1")) && !r.get("cpx#SU-1").tabIds().contains("hSus");
		boolean e4run = !r.get("cpx#RN-boom").hidden() && !r.get("cpx#RN-after").hidden() && inMain(r.get("cpx#RN-after")) && !r.get("cpx#RN-after").tabIds().contains("hRun");
		// 5: hidden messages are available in their tabs (the window's view)
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("h-a", "hHide2")));
		p3.openChat("");
		boolean e5 = onClient(() -> ChatOpenRenderer.viewOf("h-a").stream().anyMatch(x -> x == multi));
		p3.closeChat();
		m.put("1.noHidingDestination.staysInMainChat", e1);
		m.put("2.severalHidingTabs.hiddenOncePerTab", row("hidden", multi.hidden(), "tabs", multi.tabIds(), "inMainChat", inMain(multi), "ok", e2));
		m.put("3.mixedHideAndNoHide.hidden", row("hidden", mixed.hidden(), "tabs", mixed.tabIds(), "ok", e3));
		m.put("4.disabledTab.receivesNotHidden", e4tab);
		m.put("4.disabledWindow.receivesNotHidden", e4win);
		m.put("4.configSuspended.neitherReceivesNorHides", e4sus);
		m.put("4.runtimeSuspended.neitherReceivesNorHides", e4run);
		m.put("5.hiddenShownInTabView", e5);
		ev.result("Hide.eligibility", e1 && e2 && e3 && e4tab && e4win && e4sus && e4run && e5, "observed", m,
				"rule", "hidden iff a delivering tab hides, is enabled, healthy, in an enabled window, global switch on, not degraded, not ambiguous, no Q3 condensation (§4.6)");
	}

	/** V-45: hiding tab in a disabled window; re-enable; F1 never changes a decision. */
	void v45(TestSingleplayerContext sp) {
		send(sp, "before disable cpx#DW-0");
		MessageRecord h0 = rec("cpx#DW-0");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("h-dis", false)));
		send(sp, "while disabled cpx#DW-1");
		MessageRecord d1 = rec("cpx#DW-1");
		boolean noPrompt = !onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.SetWindowEnabled("h-dis", false)))).orphaning();
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("h-dis", true)));
		send(sp, "re-enabled cpx#DW-2");
		MessageRecord d2 = rec("cpx#DW-2");
		ctx.runOnClient(c -> c.options.hideGui = true);
		send(sp, "under F1 cpx#DW-3");
		MessageRecord d3 = rec("cpx#DW-3");
		ctx.runOnClient(c -> c.options.hideGui = false);
		boolean ok = h0.hidden() && d1 != null && !d1.hidden() && inMain(d1) && d1.tabIds().contains("hDisWin") && tabTokens("hDisWin").contains("cpx#DW-0")
				&& noPrompt && d2.hidden() && d3.hidden() && inMain(d1);
		ev.result("V-45", ok, "hiddenBeforeDisable", h0.hidden(), "whileDisabled", row("hidden", d1.hidden(), "inMainChat", inMain(d1), "receivedByTab", d1.tabIds()),
				"earlierHiddenStillInTab", tabTokens("hDisWin").contains("cpx#DW-0"), "afterReEnableHidden", d2.hidden(), "underF1Hidden", d3.hidden(), "noOrphanPrompt", noPrompt,
				"note", "Chat Visibility toggles not exercised (vanilla drops chat before addMessage when hidden, so no decision is made); the rule never reads it (Q24)");
	}

	/** V-45b: disabled tab with hiding on. */
	void v45b(TestSingleplayerContext sp) {
		send(sp, "before disable cpx#DT-0");
		MessageRecord h0 = rec("cpx#DT-0");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hDisTab", "enabled", false)));
		send(sp, "while disabled cpx#DT-1");
		MessageRecord d1 = rec("cpx#DT-1");
		boolean notInStrip = onClient(() -> {
			var l = ChatOpenRenderer.class;
			return mgr().config().window("h-a").tabs().stream().anyMatch(x -> "hDisTab".equals(x.id()) && x.config() != null && !x.config().enabled());
		});
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hDisTab", "enabled", true)));
		send(sp, "re-enabled cpx#DT-2");
		MessageRecord d2 = rec("cpx#DT-2");
		boolean ok = h0.hidden() && !d1.hidden() && inMain(d1) && d1.tabIds().contains("hDisTab") && tabTokens("hDisTab").contains("cpx#DT-0") && d2.hidden() && notInStrip;
		ev.result("V-45b", ok, "hiddenBeforeDisable", h0.hidden(), "whileDisabled", row("hidden", d1.hidden(), "inMainChat", inMain(d1), "received", d1.tabIds().contains("hDisTab")),
				"earlierHiddenKept", tabTokens("hDisTab").contains("cpx#DT-0"), "hidingResumes", d2.hidden(), "tabMarkedDisabled", notInStrip);
	}

	/** V-47 with hiding: one decision, both admissions refused, delivered exactly once, in neither main-chat list. */
	void v47(TestSingleplayerContext sp) {
		int logBefore = onClient(ChatLog::messageCount);
		send(sp, "admission check cpx#HD-47");
		MessageRecord r = rec("cpx#HD-47");
		long copies = onClient(() -> SessionMirror.get().hiddenRecords().stream().filter(x -> x.original() != null && x.original().getString().contains("cpx#HD-47")).count()
				+ SessionMirror.get().records().stream().filter(x -> x.original() != null && x.original().getString().contains("cpx#HD-47")).count());
		boolean ok = r.hidden() && !inMain(r) && !inLines("cpx#HD-47") && copies == 1 && onClient(ChatLog::messageCount) == logBefore + 1;
		ev.result("V-47.hidden", ok, "hidden", r.hidden(), "inAllMessages", inMain(r), "inLines", inLines("cpx#HD-47"), "records", copies,
				"chatLogGrewByOne", onClient(ChatLog::messageCount) == logBefore + 1, "note", "the visible, restored and nested cases are the Milestone 1a V-47 (live mode)");
	}

	/** Q3 (V-04, V-04b, V-04c): condensation never makes a message invisible. */
	void condensation(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		// V-04: visible X, then hiding on for its tab, then X again: the replacement stays visible and routed
		send(sp, "dup x cpx#CD-1");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hCond", "hideFromMainChat", true)));
		send(sp, "dup x cpx#CD-1");
		List<MessageRecord> copies = onClient(() -> SessionMirror.get().records().stream().filter(x -> x.original() != null && x.original().getString().contains("cpx#CD-1")).toList());
		List<MessageRecord> hiddenCopies = onClient(() -> SessionMirror.get().hiddenRecords().stream().filter(x -> x.original() != null && x.original().getString().contains("cpx#CD-1")).toList());
		MessageRecord repl = copies.isEmpty() ? null : copies.get(copies.size() - 1);
		boolean v04 = copies.size() == 1 && hiddenCopies.isEmpty() && inMain(repl) && repl.tabIds().contains("hCond") && repl.message().content().getString().contains("x");
		m.put("V-04", row("visibleCopies", copies.size(), "hiddenCopies", hiddenCopies.size(), "replacementDisplay", repl == null ? null : repl.message().content().getString(), "ok", v04));
		// V-04b: hidden X twice, no visible copy: not condensed, two tab entries
		send(sp, "dup y cpx#CD-2");
		send(sp, "dup y cpx#CD-2");
		long hiddenY = onClient(() -> SessionMirror.get().hiddenRecords().stream().filter(x -> x.original() != null && x.original().getString().contains("cpx#CD-2")).count());
		boolean v04b = hiddenY == 2 && tabTokens("hCond").stream().filter(x -> x.equals("cpx#CD-2")).count() == 2;
		m.put("V-04b", row("hiddenCopies", hiddenY, "ok", v04b));
		ev.result("V-04", v04, "observed", m.get("V-04"));
		ev.result("V-04b", v04b, "observed", m.get("V-04b"), "note", "accepted limitation: hidden duplicates aren't condensed (D2)");
		// V-04c: settings variants; whenever Chat Patches removes a visible copy, the replacement stays visible
		Map<String, Object> c = new LinkedHashMap<>();
		boolean all = true;
		int n = 0;
		String[] cases = {"compact5", "compact0", "compact500", "compactNeg5", "counterOff", "windowReadFails"};
		for (String cs : cases) {
			n++;
			String tok = "cpx#CD-v" + n;
			ctx.runOnClient(x -> {
				AcceptanceTest.resetChatPatchesConfig();
				var cfg = ChatPatches.config;
				switch (cs) {
					case "compact5" -> { cfg.compactChat = true; cfg.compactDistance = 5; }
					case "compact0" -> { cfg.compactChat = true; cfg.compactDistance = 0; }
					case "compact500" -> { cfg.compactChat = true; cfg.compactDistance = 500; }
					case "compactNeg5" -> { cfg.compactChat = true; cfg.compactDistance = -5; }
					case "counterOff" -> cfg.counter = false;
					default -> { cfg.compactChat = true; cfg.compactDistance = 5; }
				}
				mgr().edit(new ConfigEdit.SetTabFlag("hCond", "hideFromMainChat", false));
			});
			if (cs.equals("windowReadFails")) {
				ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
				ctx.runOnClient(x -> { Warnings.devReset(); ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) {
					@Override public int compactDistance() { throw new IllegalStateException("test: window settings unreadable"); }
				}); ChatPatchesAdapter.get().selfCheck(); });
			}
			send(sp, "variant " + tok);
			send(sp, "filler a cpx#OT-a" + n);
			send(sp, "filler b cpx#OT-b" + n);
			ctx.runOnClient(x -> mgr().edit(new ConfigEdit.SetTabFlag("hCond", "hideFromMainChat", true)));
			List<GuiMessage> before = onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())));
			send(sp, "variant " + tok);
			List<GuiMessage> after = onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())));
			boolean removed = before.stream().anyMatch(g -> g.content().getString().contains(tok) && after.stream().noneMatch(a -> a == g));
			MessageRecord newest = rec(tok);
			boolean newVisible = newest != null && !newest.hidden() && inMain(newest);
			boolean pass = !removed || newVisible; // no missed removal
			if (cs.equals("counterOff")) pass &= !removed && newest != null && newest.hidden(); // no condensation, no detection
			if (cs.equals("windowReadFails")) pass &= onClient(() -> Warnings.counts().keySet().stream().anyMatch(k -> k.contains("CONDENSE_WINDOW")));
			all &= pass;
			c.put(cs, row("chatPatchesRemovedOldCopy", removed, "replacementVisible", newVisible, "replacementHidden", newest != null && newest.hidden(), "ok", pass));
			ctx.runOnClient(x -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); AcceptanceTest.resetChatPatchesConfig(); });
		}
		ev.result("V-04c", all, "cases", c, "notRun", "(3) a foreign mod adding an entry inside the same call and (4) a restored message with a saved counter: not exercised; the counter is never consulted (code) and identity snapshots cover a constant-size list");
	}

	/** Explicit checks 7 and 9 with real hidden messages: deletion restores atomically or refuses; a failed write leaves nothing partial. */
	void orphansWithRealHiding(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hAll", "matchAll", false))); // only the hiding tabs hold the hidden messages
		send(sp, "visible a cpx#P-a");
		send(sp, "real hidden one cpx#HD-o1");
		send(sp, "visible b cpx#P-b");
		send(sp, "real hidden two cpx#HD-o2");
		// shared with hHide2 first: deleting hHide2 alone orphans nothing
		send(sp, "shared cpx#HD2-s");
		ConfigManager.OrphanCheck c0 = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("hHide2"))));
		ConfigManager.EditOutcome d0 = onClient(() -> mgr().edit(new ConfigEdit.DeleteTab("hHide2")));
		m.put("sharedNotOrphaned", row("orphaning", c0.orphaning(), "applied", d0.applied()));
		// failed write: nothing partial
		ctx.runOnClient(c -> mgr().storage().devSetRenameFailures(0, true, false));
		ConfigManager.OrphanCheck c1 = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("hHide"))));
		Phase7aTests a = new Phase7aTests(t);
		Phase7aTests.Dump before = a.dump();
		ConfigManager.EditOutcome failed = onClient(() -> mgr().editAllRestoringOrphans(List.of(new ConfigEdit.DeleteTab("hHide")), c1));
		Phase7aTests.Dump after = a.dump();
		ctx.runOnClient(c -> mgr().storage().devSetRenameFailures(0, false, false));
		boolean nothingPartial = !failed.applied() && before.same(after);
		m.put("9.failedWriteNothingPartial", row("applied", failed.applied(), "unchanged", after.diff(before)));
		ConfigManager.OrphanCheck c2 = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("hHide"))));
		ConfigManager.EditOutcome ok = onClient(() -> mgr().editAllRestoringOrphans(List.of(new ConfigEdit.DeleteTab("hHide")), c2));
		List<String> main = onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().map(g -> token(g.content().getString())).toList());
		int iA = main.indexOf("cpx#P-a"), i1 = main.indexOf("cpx#HD-o1"), iB = main.indexOf("cpx#P-b"), i2 = main.indexOf("cpx#HD-o2"), iS = main.indexOf("cpx#HD2-s");
		boolean order = i2 >= 0 && iS >= 0 && iS < i2 && i2 < iB && iB < i1 && i1 < iA;
		boolean noDup = main.stream().filter(x -> x.equals("cpx#HD-o1")).count() == 1 && onClient(() -> SessionMirror.get().hiddenRecords().stream().noneMatch(r -> r.original().getString().contains("cpx#HD-o")));
		m.put("7.deletionRestores", row("disclosed", c2.restored(), "applied", ok.applied(), "captureOrder", order, "noDuplicateInMainOrTab", noDup));
		boolean pass = !c0.orphaning() && d0.applied() && nothingPartial && ok.applied() && c2.restored() == 3 && order && noDup;
		ev.result("Hide.orphanRestorationRealHiding", pass, "observed", m, "note", "hAll's matchAll is off here so the hiding tabs are the only holders");
	}

	/** V-48: the hidden-reference cap (P7); V-49 (5): degraded routing. */
	void v48(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		ctx.runOnClient(c -> { ChatPatches.config.chatMaxMessages = 50; mgr().edit(new ConfigEdit.SelectTab("h-a", "hAll")); mgr().edit(new ConfigEdit.SetTabFlag("hAll", "matchAll", false)); });
		// some routed to the disabled-window tab and the disabled tab as well (they receive; hHide hides)
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hDisTab", "enabled", false)));
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("h-dis", false)));
		for (int i = 0; i < 60; i++) t.sys(sp, "cap " + i + " cpx#HD-c" + i + (i % 3 == 0 ? " cpx#DW cpx#DT" : ""));
		t.waitT(10);
		List<GuiMessage> mainBefore = onClient(() -> new ArrayList<>(AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat())));
		List<String> hidden = onClient(() -> SessionMirror.get().hiddenRecords().stream().map(r -> token(r.original().getString())).toList());
		boolean oldestFirst = hidden.size() == 50 && hidden.get(0).equals("cpx#HD-c10") && hidden.get(49).equals("cpx#HD-c59");
		boolean goneEverywhere = onClient(() -> SessionMirror.get().tabStates().stream().flatMap(s -> s.entries().stream())
				.noneMatch(r -> r.original() != null && r.original().getString().matches(".*cpx#HD-c[0-9] .*")));
		int unreadHide = unread("hHide");
		int retainedInHide = onClient(() -> SessionMirror.get().tab("hHide").size());
		boolean unreadMatches = unreadHide == retainedInHide; // unselected tab: every retained entry flagged, pruned flags removed
		m.put("1", row("retained", hidden.size(), "oldest", hidden.isEmpty() ? null : hidden.get(0), "newest", hidden.isEmpty() ? null : hidden.get(hidden.size() - 1),
				"prunedFromEveryTab", goneEverywhere, "unreadHide", unreadHide, "retainedInHide", retainedInHide, "disabledWindowTabSize", onClient(() -> SessionMirror.get().tab("hDisWin").size())));
		// (2) lower the cap, one more hidden message: trimmed to 30 on that insertion
		ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 30);
		send(sp, "after lower cpx#HD-low");
		int afterLower = onClient(() -> SessionMirror.get().hiddenSize());
		// (3) raise: retention grows, nothing comes back
		ctx.runOnClient(c -> ChatPatches.config.chatMaxMessages = 80);
		for (int i = 0; i < 40; i++) t.sys(sp, "grow " + i + " cpx#HD-g" + i);
		t.waitT(8);
		int afterRaise = onClient(() -> SessionMirror.get().hiddenSize());
		boolean noResurrection = onClient(() -> SessionMirror.get().hiddenRecords().stream().noneMatch(r -> r.original().getString().contains("cpx#HD-c1 ")
				|| r.original().getString().matches(".*cpx#HD-c(1[0-9]|2[0-9]) .*")));
		boolean mainUntouched = onClient(() -> {
			List<GuiMessage> now = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
			return mainBefore.stream().allMatch(g -> now.stream().anyMatch(x -> x == g));
		});
		boolean noPrompt = Warnings.counts().keySet().stream().noneMatch(k -> k.contains("orphan"));
		m.put("2.lowerTo30", afterLower);
		m.put("3.raiseTo80", row("retained", afterRaise, "noResurrection", noResurrection));
		m.put("mainChatUnchangedByPruning", mainUntouched);
		// (4) cap read fails: degraded, one warning, new messages visible and routed, hidden kept, fallback cap, C-1 refusal
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		ctx.runOnClient(c -> { Warnings.devReset(); ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) {
			@Override public int chatMaxMessages() { throw new IllegalStateException("test: cap unreadable"); }
		}); ChatPatchesAdapter.get().selfCheck(); });
		int hiddenBefore = onClient(() -> SessionMirror.get().hiddenSize());
		send(sp, "degraded new cpx#HD-deg");
		MessageRecord deg = rec("cpx#HD-deg");
		boolean degraded = onClient(() -> ChatPatchesAdapter.get().isDegraded());
		int toasts = onClient(Warnings::toastsShown);
		ConfigManager.OrphanCheck refusal = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("hHide"))));
		boolean keptHidden = onClient(() -> SessionMirror.get().hiddenSize()) == hiddenBefore;
		m.put("4.degraded", row("degraded", degraded, "toasts", toasts, "newVisibleAndRouted", !deg.hidden() && inMain(deg) && deg.tabIds().contains("hHide"),
				"existingHiddenKept", keptHidden, "orphanDeletionRefused", refusal.refused(), "message", refusal.refusal()));
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		boolean ok = oldestFirst && goneEverywhere && unreadMatches && afterLower == 30 && afterRaise == 70 && noResurrection && mainUntouched && noPrompt
				&& degraded && toasts == 1 && !deg.hidden() && inMain(deg) && keptHidden && refusal.refused();
		ev.result("V-48", ok, "observed", m, "note", "(5) relaunch behaviour is hide_relaunch / hide_relaunch_nolog");
		ev.result("V-49.routing", !deg.hidden() && inMain(deg) && deg.tabIds().contains("hHide") && keptHidden,
				"note", "C-1 (5): in degraded mode existing hidden messages stay visible in their tabs and new matching messages still route there, not hidden");
	}

	/** V-50: a mid-session Class S failure; degraded once and for good. */
	void v50(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		for (String op : new String[]{"restoredTag", "vanillaClearing", "chatMaxMessages", "isBoundaryLine"}) {
			setup(sp);
			send(sp, "hidden before fault cpx#HD-pre");
			MessageRecord pre = rec("cpx#HD-pre");
			ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
			int[] calls = {0};
			// fails twice, then succeeds: degraded must not flip back
			ChatPatchesBackend flaky = new Phase3Tests.DelegatingBackend(real) {
				boolean fail() { return calls[0]++ < 2; }
				@Override public net.minecraft.client.GuiMessageTag restoredIndicator() { if (op.equals("restoredTag") && fail()) throw new IllegalStateException("t"); return real.restoredIndicator(); }
				@Override public boolean vanillaClearing() { if (op.equals("vanillaClearing") && fail()) throw new IllegalStateException("t"); return real.vanillaClearing(); }
				@Override public int chatMaxMessages() { if (op.equals("chatMaxMessages") && fail()) throw new IllegalStateException("t"); return real.chatMaxMessages(); }
				@Override public boolean isBoundaryLine(Component c) { if (op.equals("isBoundaryLine") && fail()) throw new IllegalStateException("t"); return real.isBoundaryLine(c); }
			};
			ctx.runOnClient(c -> { Warnings.devReset(); ChatPatchesAdapter.get().devReplaceBackend(flaky); ChatPatchesAdapter.get().selfCheck(); });
			if (op.equals("vanillaClearing")) ctx.runOnClient(c -> ChatPatchesAdapter.get().vanillaClearing());
			for (int i = 0; i < 4; i++) send(sp, "after fault " + i + " cpx#HD-post" + i);
			boolean degraded = onClient(() -> ChatPatchesAdapter.get().isDegraded());
			MessageRecord post = rec("cpx#HD-post3");
			boolean preKept = onClient(() -> SessionMirror.get().tab("hHide").entries().contains(pre)) && pre.hidden();
			boolean postVisible = post != null && !post.hidden() && inMain(post) && post.tabIds().contains("hHide");
			int toasts = onClient(Warnings::toastsShown);
			boolean pass = degraded && preKept && postVisible && toasts == 1;
			ok &= pass;
			m.put(op, row("degradedStays", degraded, "callsMade", calls[0], "existingHiddenAccessible", preKept, "newMessagesVisibleAndRouted", postVisible, "toasts", toasts, "ok", pass));
			ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		}
		ev.result("V-50", ok, "cases", m, "note", "relaunch with the fault present (restored messages AMBIGUOUS, never hidden) is hide_relaunch");
	}

	/** Explicit check 13 and hidden parts of V-46, V-46b, V-46d. */
	void searchUnreadRelocation(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		send(sp, "apple hidden cpx#HD-s1");
		send(sp, "banana hidden cpx#HD-s2");
		send(sp, "apple visible cpx#OT-s3");
		int unreadHide = unread("hHide");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("h-a", "hHide")));
		p3.openChat("");
		var f = onClient(() -> ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen));
		ctx.runOnClient(c -> f.setValue("apple"));
		t.waitT(2);
		List<String> plain = onClient(() -> ChatOpenRenderer.viewOf("h-a").stream().map(r -> token(r.message().content().getString())).toList());
		ctx.runOnClient(c -> { ChatPatches.config.regex = true; f.setValue(""); f.setValue(".*apple.*"); });
		t.waitT(2);
		List<String> regex = onClient(() -> ChatOpenRenderer.viewOf("h-a").stream().map(r -> token(r.message().content().getString())).toList());
		TabSearch.Indicator ind = onClient(() -> ChatOpenRenderer.indicatorOf("h-a"));
		ctx.runOnClient(c -> { f.setValue(""); ChatPatches.config.regex = false; });
		p3.closeChat();
		int unreadAfterSelect = unread("hHide");
		m.put("V-46.plainIncludesHidden", plain);
		m.put("V-46d.regexOnlyChatPatchesResults", row("view", regex, "indicator", ind));
		m.put("P4.unread", row("beforeSelect", unreadHide, "afterSelect", unreadAfterSelect));
		// V-46b hidden part: a hidden unread entry filtered out by search still clears when its window becomes visible
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("h-a", false)));
		send(sp, "melon hidden cpx#HD-s4");
		p3.openChat("");
		var f2 = onClient(() -> ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen));
		ctx.runOnClient(c -> f2.setValue("apple"));
		t.waitT(2);
		int flagged = unread("hHide");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetWindowEnabled("h-a", true)));
		t.waitT(3);
		int cleared = unread("hHide");
		ctx.runOnClient(c -> f2.setValue(""));
		p3.closeChat();
		m.put("V-46b.hidden", row("flaggedWhileDisabled", flagged, "afterBecomingVisible", cleared));
		// relocation: move hHide to h-b; hidden entries and unread travel, no orphan restoration
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("h-a", "hAll")));
		send(sp, "unread before move cpx#HD-s5");
		int unreadBeforeMove = unread("hHide");
		var stateBefore = onClient(() -> SessionMirror.get().tab("hHide"));
		ConfigManager.OrphanCheck mc = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.MoveTab("hHide", "h-b"))));
		ConfigManager.EditOutcome mv = onClient(() -> mgr().edit(new ConfigEdit.MoveTab("hHide", "h-b")));
		boolean sameState = onClient(() -> SessionMirror.get().tab("hHide")) == stateBefore;
		int unreadAfterMove = unread("hHide");
		int hiddenEntries = onClient(() -> SessionMirror.get().tab("hHide").hiddenCount());
		// s1, s2, s5 are hidden; s4 arrived while h-a was disabled, so it stayed in the main chat (Q24)
		boolean s4Visible = !rec("cpx#HD-s4").hidden();
		boolean travelled = sameState && unreadAfterMove == unreadBeforeMove && hiddenEntries == 3 && s4Visible;
		m.put("relocation", row("orphaning", mc.orphaning(), "applied", mv.applied(), "sameState", sameState, "unreadBefore", unreadBeforeMove, "unreadAfter", unreadAfterMove,
				"hiddenEntries", hiddenEntries, "ok", travelled));
		boolean ok = plain.containsAll(List.of("cpx#HD-s1", "cpx#HD-s2")) == false && plain.contains("cpx#HD-s1") && !plain.contains("cpx#HD-s2")
				&& !regex.contains("cpx#HD-s1") && ind == TabSearch.Indicator.PARTIAL && unreadHide >= 2 && unreadAfterSelect == 0 && flagged >= 1 && cleared == 0
				&& !mc.orphaning() && mv.applied() && travelled;
		ev.result("Hide.searchUnreadRelocation", ok, "observed", m, "note", "regex mode: hidden entries are never in Chat Patches' results, so the tab shows 'Partial results' (Q31); search semantics unchanged");
	}

	/** V-18 (subset): with hidden and visible traffic interleaved, every main-chat line maps to its own entry. */
	void v18(TestSingleplayerContext sp) {
		for (int i = 0; i < 6; i++) {
			send(sp, "visible v18 " + i + " cpx#OT-v" + i);
			send(sp, "hidden v18 " + i + " cpx#HD-v" + i);
		}
		p3.openChat("");
		t.waitT(2);
		Map<String, Object> hit = onClient(() -> {
			List<GuiMessage> all = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
			Map<String, Object> r = new LinkedHashMap<>();
			int found = 0;
			for (int y = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 20; y > 20 && found < 6; y--) {
				int idx = ((obro1961.chatpatches.accessor.ChatComponentAccess) Minecraft.getInstance().gui.getChat()).getEoEIndex(10, y);
				if (idx < 0 || idx >= all.size()) continue;
				String tok = token(all.get(idx).content().getString());
				if (!r.containsValue(tok)) r.put("line" + found++, tok);
			}
			return r;
		});
		p3.closeChat();
		List<Object> got = new ArrayList<>(hit.values());
		boolean ok = got.equals(List.of("cpx#OT-v5", "cpx#OT-v4", "cpx#OT-v3", "cpx#OT-v2", "cpx#OT-v1", "cpx#OT-v0"));
		ev.result("V-18", ok, "linesToEntries", hit, "note", "subset: Chat Patches' own line lookup maps each visible line to its message with hidden messages interleaved (lines and entries 1:1); individual context-menu actions not driven");
	}

	/** V-22 with hiding: the same script twice gives the same routing, hidden flags and unread. */
	void v22(TestSingleplayerContext sp) {
		List<String> run1 = script(sp);
		ctx.runOnClient(c -> c.gui.getChat().clearMessages(false));
		t.waitT(3);
		List<String> run2 = script(sp);
		ev.result("V-22.hiding", run1.equals(run2), "run1", run1, "run2", run2);
	}

	List<String> script(TestSingleplayerContext sp) {
		String[] msgs = {"s cpx#HD-1", "s cpx#OT-2", "s cpx#MX-3", "s cpx#HD2-4", "s cpx#DT-5", "s cpx#P-6", "s cpx#HD-7"};
		for (String s : msgs) send(sp, s);
		List<String> out = new ArrayList<>();
		for (String s : msgs) {
			MessageRecord r = rec(token(s));
			out.add(token(s) + ":" + (r == null ? "missing" : (r.hidden() ? "hidden" : "visible") + r.tabIds()));
		}
		for (String tab : List.of("hAll", "hHide", "hHide2", "hMixed", "hMixHide", "hOther")) out.add(tab + ".unread=" + unread(tab));
		return out;
	}

	/** V-44 (suspended tab with visible and hidden entries), V-06 with hidden entries, V-33g. */
	void v44AndClear(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		send(sp, "hidden in run cpx#RM-h1");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SetTabFlag("hRun2", "hideFromMainChat", false)));
		send(sp, "visible in run cpx#RM-v1");
		send(sp, "visible in run cpx#RM-v2");
		ctx.runOnClient(c -> {
			DevHooks.setMatcherDecorator((id, mm) -> id.equals("hRun2") ? (Matcher) text -> { if (text.contains("BOOM")) throw new IllegalStateException("test"); return mm.matches(text); } : mm);
			Router.get().install(mgr().config().routing());
			DevHooks.setMatcherDecorator(null);
		});
		send(sp, "BOOM cpx#RM-boom");
		boolean suspended = onClient(() -> SessionMirror.get().tab("hRun2").isRuntimeSuspended());
		List<String> before = tabTokens("hRun2");
		// Chat Patches' context-menu delete removes the entry from the main chat (as Chat Patches does), then liveness
		ctx.runOnClient(c -> {
			var all = AcceptanceTest.allMessagesReflective(c.gui.getChat());
			all.removeIf(g -> g.content().getString().contains("cpx#RM-v1"));
			c.gui.getChat().rescaleChat();
		});
		t.waitT(3);
		List<String> afterDelete = tabTokens("hRun2");
		int unreadAll = unread("hRun2");
		ctx.runOnClient(c -> c.gui.getChat().clearMessages(false));
		t.waitT(3);
		int hiddenAfterClear = onClient(() -> SessionMirror.get().hiddenSize());
		long tabEntriesAfterClear = onClient(() -> SessionMirror.get().tabStates().stream().mapToLong(TabState::size).sum());
		int unreadAfterClear = onClient(() -> SessionMirror.get().tabStates().stream().mapToInt(TabState::unreadCount).sum());
		m.put("suspended", suspended);
		m.put("entriesBefore", before);
		m.put("afterContextMenuDelete", afterDelete);
		m.put("unreadMatchesRetained", unreadAll);
		m.put("afterClear", row("hidden", hiddenAfterClear, "tabEntries", tabEntriesAfterClear, "unread", unreadAfterClear));
		boolean v44 = suspended && before.containsAll(List.of("cpx#RM-h1", "cpx#RM-v1", "cpx#RM-v2")) && !afterDelete.contains("cpx#RM-v1") && afterDelete.contains("cpx#RM-h1")
				&& tabEntriesAfterClear == 0;
		boolean v06 = hiddenAfterClear == 0 && tabEntriesAfterClear == 0 && unreadAfterClear == 0;
		ev.result("V-44", v44, "observed", m, "note", "no restore prompt: suspension never orphans (Q17)");
		ev.result("V-06.hidden", v06, "observed", m.get("afterClear"), "note", "F3+D-equivalent clear (history=false, vanillaClearing=false): tabs, hidden list and unread cleared (§4.8)");
		ev.result("V-33g.hidden", unreadAll <= afterDelete.size(), "unread", unreadAll, "retained", afterDelete.size());
	}

	/** Explicit check 12: the main chat lacks exactly the hidden messages; Chat Patches' log has all of them. */
	void logDifferences(TestSingleplayerContext sp) {
		int log0 = onClient(ChatLog::messageCount);
		int main0 = onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).size());
		String[] msgs = {"log a cpx#HD-l1", "log b cpx#OT-l2", "log c cpx#HD-l3", "log d cpx#OT-l4"};
		for (String s : msgs) send(sp, s);
		int log1 = onClient(ChatLog::messageCount);
		int main1 = onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).size());
		boolean ok = log1 - log0 == 4 && main1 - main0 == 2;
		ev.result("Hide.mainChatVsLog", ok, "chatLogAdded", log1 - log0, "mainChatAdded", main1 - main0,
				"note", "hidden messages are logged by Chat Patches (D2) and absent from the main chat; the structural hiding-on baseline compares full contents (structural.hidingOnBaseline)");
	}

	/** V-31 under active hiding, explicit check 10: a catch-all hiding tab never takes a boundary line. */
	void v31(TestSingleplayerContext sp) {
		send(sp, "visible before catch-all cpx#OT-pre"); // Chat Patches adds no boundary line to an empty main chat
		ctx.runOnClient(c -> { ChatPatches.config.boundary = true; mgr().edit(new ConfigEdit.SetTabFlag("hAll", "hideFromMainChat", true)); });
		send(sp, "catch all hidden cpx#OT-ca");
		boolean catchAllHides = rec("cpx#OT-ca").hidden();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("catchAllHides", catchAllHides);
		ev.data("v31_hiding_pending", "catchAllHides", catchAllHides);
		Path marker = Path.of("cpx-evidence", "hide-v31.flag");
		try {
			Files.writeString(marker, "pending");
		} catch (java.io.IOException ignored) {
			// only a hint for the next part
		}
		v31Pending = catchAllHides;
	}

	private boolean v31Pending;

	/** Second part of V-31 (needs a world switch): run by the mode after leaving the first world. */
	void v31Switch() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			Map<String, Object> st = onClient(() -> {
				List<GuiMessage> all = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
				GuiMessage newest = all.isEmpty() ? null : all.get(0);
				boolean isB = newest != null && Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(newest.content()));
				boolean unrouted = SessionMirror.get().records().stream().filter(r -> Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(r.message().content())))
						.allMatch(r -> r.tabIds().isEmpty() && !r.hidden());
				boolean noneHidden = SessionMirror.get().hiddenRecords().stream().noneMatch(r -> Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(r.message().content())));
				return row("newestIsBoundary", isB, "boundaryRecordsUnroutedAndVisible", unrouted, "noBoundaryHidden", noneHidden);
			});
			boolean ok = v31Pending && st.values().stream().allMatch(Boolean.TRUE::equals);
			ev.result("V-31.hiding", ok, "catchAllHidesOrdinaryMessages", v31Pending, "afterWorldSwitch", st);
		}
		t.waitT(20);
	}

	void hideMode() throws Exception {
		hide();
		v31Switch();
	}

	// ================================================================ relaunch chains (V-48(5), V-20f, V-23, V-50 relaunch, Q16b)

	static final Path SEED = Path.of("cpx-evidence", "hide-seed.json");

	/** First launch: hidden traffic, P7 pruning, and a restore that prunes visible messages; Chat Patches saves (or not). */
	void hideSeed(boolean chatlog) throws Exception {
		Map<String, Object> rec = new LinkedHashMap<>();
		String pfx = chatlog ? "cpx#HS-" : "cpx#HN-";
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			setup(sp);
			ctx.runOnClient(c -> { ChatPatches.config.chatlog = chatlog; ChatPatches.config.chatMaxMessages = 40;
				mgr().edit(new ConfigEdit.SetTabFlag("hAll", "matchAll", false)); }); // hHide is the only holder of the hidden messages
			// visible messages first (they'll be the oldest), then hidden traffic beyond the hidden cap (P7 prunes the
			// oldest hidden), which also advances Chat Patches' log past the visible ones (its log cap is the same 40)
			for (int i = 0; i < 30; i++) t.sys(sp, "seed visible " + i + " " + pfx + "V" + i);
			t.waitT(6);
			for (int i = 0; i < 50; i++) t.sys(sp, "seed hidden " + i + " " + pfx.replace("-", "") + "HD-" + i + " cpx#HD");
			t.waitT(6);
			rec.put("hiddenRetained", onClient(() -> SessionMirror.get().hiddenRecords().stream().map(r -> token(r.original().getString())).toList()));
			// V-20f: delete hHide: the 40 orphans return, the main chat cap (40) prunes the 30 older visible messages
			List<String> before = onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().map(g -> token(g.content().getString())).toList());
			ConfigManager.OrphanCheck c = onClient(() -> mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("hHide"))));
			rec.put("restoreDisclosure", row("refused", c.refused(), "message", c.refusal(), "restored", c.restored(), "pruned", c.pruned()));
			if (!c.refused()) {
				ConfigManager.EditOutcome o = onClient(() -> mgr().editAllRestoringOrphans(List.of(new ConfigEdit.DeleteTab("hHide")), c));
				List<String> after = onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().map(g -> token(g.content().getString())).toList());
				List<String> pruned = before.stream().filter(x -> !after.contains(x)).toList();
				rec.put("restoreApplied", o.applied());
				rec.put("prunedActual", pruned);
				ev.result("V-20f.disclosure." + (chatlog ? "log" : "nolog"), o.applied() && pruned.size() == c.pruned(), "disclosedPruned", c.pruned(), "actualPruned", pruned.size());
			} else {
				ev.result("V-20f.disclosure." + (chatlog ? "log" : "nolog"), Evidence.Outcome.INCONCLUSIVE, "refusal", c.refusal(), "note", "the projection refused (an orphan would be pruned); pruning recoverability not reachable with this fixture");
			}
			rec.put("chatlog", chatlog);
			rec.put("chatLogCount", onClient(ChatLog::messageCount));
		}
		Files.writeString(SEED, new com.google.gson.Gson().toJson(rec));
		t.waitT(20);
	}

	/** Relaunch: what Chat Patches restored versus what the session held; with a Class S fault (V-50) or without. */
	void hideRelaunch(boolean fault) throws Exception {
		var seed = com.google.gson.JsonParser.parseString(Files.readString(SEED)).getAsJsonObject();
		boolean chatlog = seed.get("chatlog").getAsBoolean();
		Phase2Tests.write(Phase2Tests.cfg(), CONFIG);
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		ctx.runOnClient(c -> {
			mgr().devSimulateRestart();
			if (fault) {
				ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) {
					@Override public net.minecraft.client.GuiMessageTag restoredIndicator() { throw new IllegalStateException("test: restored tag unreadable"); }
				});
				ChatPatchesAdapter.get().selfCheck();
			}
		});
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(160);
			String pfx = chatlog ? "cpx#HS" : "cpx#HN";
			List<MessageRecord> recs = onClient(() -> {
				List<MessageRecord> l = new ArrayList<>(SessionMirror.get().records());
				l.addAll(SessionMirror.get().hiddenRecords());
				return l.stream().filter(r -> r.original() != null && r.original().getString().contains(pfx)).toList();
			});
			List<String> restoredTokens = recs.stream().map(r -> token(r.original().getString())).distinct().toList();
			long hiddenRestored = recs.stream().filter(MessageRecord::hidden).count();
			long ambiguous = recs.stream().filter(MessageRecord::ambiguous).count();
			if (!chatlog) {
				ev.result("V-23", restoredTokens.isEmpty(), "restoredFromNoLogSession", restoredTokens.size(), "note", "chatlog=false: hidden (and visible) messages of that session are gone after relaunch (documented)");
				ev.result("V-20f.relaunch.nolog", restoredTokens.isEmpty(), "pruned", seed.get("prunedActual"), "returned", restoredTokens.size());
				ev.result("V-48.relaunch.nolog", restoredTokens.isEmpty(), "note", "nothing returns with chatlog=false");
			} else if (fault) {
				boolean degraded = onClient(() -> ChatPatchesAdapter.get().isDegraded());
				ev.result("V-50.relaunch", degraded && hiddenRestored == 0 && !recs.isEmpty() && ambiguous == recs.size(), "degraded", degraded, "restoredRecords", recs.size(),
						"ambiguous", ambiguous, "hidden", hiddenRestored, "note", "restored tag unreadable: every restored message is AMBIGUOUS (unknown origin), routed, never hidden");
				var pruned = seed.getAsJsonArray("prunedActual");
				List<String> prunedBack = new ArrayList<>();
				if (pruned != null) for (var p : pruned) if (restoredTokens.contains(p.getAsString())) prunedBack.add(p.getAsString());
				ev.result("V-20f.relaunch.log", true, "prunedInSession", pruned, "prunedThatChatPatchesRestored", prunedBack,
						"note", "documentation: pruned messages come back only if inside Chat Patches' log window; Extended restores nothing itself");
				var held = seed.getAsJsonArray("hiddenRetained");
				int heldBack = 0;
				for (var h : held) if (restoredTokens.contains(h.getAsString())) heldBack++;
				ev.result("V-48.relaunch.log", true, "hiddenHeldBySession", held.size(), "ofThoseRestoredByChatPatches", heldBack, "restoredTokensTotal", restoredTokens.size(),
						"note", "documentation: session retention (P7) and Chat Patches' log retention are distinct; only what the log restores comes back (§4.7)");
			} else {
				// no fault: recognized restored messages may be hidden by a restoring hiding tab; AMBIGUOUS ones never
				List<MessageRecord> edge = onClient(() -> {
					List<MessageRecord> l = new ArrayList<>(SessionMirror.get().records());
					l.addAll(SessionMirror.get().hiddenRecords());
					return l.stream().filter(r -> r.original() != null && r.original().getString().contains("cpx#R-old") && r.origin() == Origin.RESTORED).toList();
				});
				long edgeHidden = edge.stream().filter(MessageRecord::hidden).count();
				long recognizedHidden = recs.stream().filter(r -> r.origin() == Origin.RESTORED && !r.ambiguous() && r.hidden()).count();
				ev.result("Q16b.restoredUnderHiding", !edge.isEmpty() && edgeHidden == 0 && recognizedHidden > 0, "ambiguousRestored", edge.size(), "ambiguousHidden", edgeHidden,
						"recognizedRestoredHidden", recognizedHidden, "note", "restored history in a hiding tab with restoreHistory: recognized messages are hidden (§4.6), unrecognized (AMBIGUOUS) ones never (Q16b)");
			}
		}
		ctx.runOnClient(c -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
		t.waitT(20);
	}
}
