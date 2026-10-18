// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.compat.ChatPatchesBackend;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ConfigStorage;
import io.github.itznotneptune.cpextended.core.Capture;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.OrphanRestore;
import io.github.itznotneptune.cpextended.core.Reconciler;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import io.github.itznotneptune.cpextended.gui.WindowScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 7 Stage A: the Q36 orphan-restoration transaction (Q2, Q22, Q27, P5, P6, C-1), verified with synthetic hidden
 * fixtures. Normal hiding stays disabled: every hidden message here is created by the development fixture, exactly as
 * a hidden capture would leave it.
 */
final class Phase7aTests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase4Tests p4;
	private final Phase3Tests p3;

	Phase7aTests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p4 = new Phase4Tests(t);
		this.p3 = new Phase3Tests(t);
	}

	static final String CONFIG = "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n\"windows\": [\n"
			+ " {\"id\": \"q-a\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.02, \"width\": 0.4, \"height\": 0.3, \"selectedTab\": \"qv\", \"tabs\": ["
			+ "{\"id\": \"qv\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#V\"}]},"
			+ "{\"id\": \"qh\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#H1\"}]},"
			+ "{\"id\": \"qs\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#S\"}]},"
			+ "{\"id\": \"qx\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#X\"}]}]},\n"
			+ " {\"id\": \"q-b\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.4, \"width\": 0.4, \"height\": 0.2, \"tabs\": ["
			+ "{\"id\": \"qm\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#M\"}]},"
			+ "{\"id\": \"qd\", \"hideFromMainChat\": true, \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#D\"}]}]},\n"
			+ " {\"id\": \"q-empty\", \"anchor\": \"TOP_LEFT\", \"x\": 0.5, \"y\": 0.5, \"width\": 0.2, \"height\": 0.2, \"tabs\": []}\n"
			+ "]\n}\n";

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	ConfigManager mgr() { return ConfigManager.get(); }

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	// ---------------------------------------------------------------- state dumps

	/** Everything a refusal or rollback must leave unchanged (P6(6), V-20g, V-20l). */
	record Dump(byte[] config, List<GuiMessage> all, Object allListObject, Map<String, List<Long>> tabs, List<Long> hidden, Map<Long, String> hiddenFlags,
			Map<String, Integer> unread, List<Long> records, int chatLog) {
		boolean same(Dump o) {
			return Arrays.equals(config, o.config) && identical(all, o.all) && allListObject == o.allListObject && tabs.equals(o.tabs)
					&& hidden.equals(o.hidden) && hiddenFlags.equals(o.hiddenFlags) && unread.equals(o.unread) && records.equals(o.records) && chatLog == o.chatLog;
		}

		Map<String, Object> diff(Dump o) {
			return row("configBytes", Arrays.equals(config, o.config), "allMessagesIdentity", identical(all, o.all), "allMessagesListObject", allListObject == o.allListObject,
					"tabHistories", tabs.equals(o.tabs), "hiddenList", hidden.equals(o.hidden), "hiddenFlagsAndHolders", hiddenFlags.equals(o.hiddenFlags),
					"unread", unread.equals(o.unread), "mirror", records.equals(o.records), "chatLogCount", chatLog == o.chatLog);
		}
	}

	static boolean identical(List<GuiMessage> a, List<GuiMessage> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
		return true;
	}

	Dump dump() {
		byte[] cfg = Phase2Tests.bytes(Phase2Tests.cfg());
		return onClient(() -> {
			ChatComponent chat = Minecraft.getInstance().gui.getChat();
			List<GuiMessage> live = AcceptanceTest.allMessagesReflective(chat);
			Map<String, List<Long>> tabs = new LinkedHashMap<>();
			Map<String, Integer> unread = new LinkedHashMap<>();
			for (TabState s : SessionMirror.get().tabStates()) {
				tabs.put(s.id(), s.entries().stream().map(MessageRecord::seq).toList());
				unread.put(s.id(), s.unreadCount());
			}
			Map<Long, String> flags = new LinkedHashMap<>();
			for (MessageRecord r : SessionMirror.get().hiddenRecords()) flags.put(r.seq(), r.hidden() + ":" + r.tabIds());
			return new Dump(cfg, new ArrayList<>(live), live, tabs, SessionMirror.get().hiddenRecords().stream().map(MessageRecord::seq).toList(), flags, unread,
					SessionMirror.get().records().stream().map(MessageRecord::seq).toList(), ChatLog.messageCount());
		});
	}

	// ---------------------------------------------------------------- fixtures

	void setup(TestSingleplayerContext sp) {
		Phase2Tests.write(Phase2Tests.cfg(), CONFIG);
		ctx.runOnClient(c -> {
			mgr().devSimulateRestart();
			ChatPatches.config.chatMaxMessages = 60;
			c.gui.getChat().clearMessages(false); // a known main chat (restored history from earlier modes dropped)
		});
		t.waitT(3);
	}

	/** Resets the session to the configuration and an empty main chat between scenarios. */
	void reset(TestSingleplayerContext sp) {
		t.clearHiddenFixtures();
		ctx.runOnClient(c -> {
			ConfigStorage st = mgr().storage();
			st.devSetFailTempWrite(false);
			st.devSetProbeUnsupported(false);
			st.devSetRenameFailures(0, false, false);
			st.devResetAtomicProbe();
			OrphanRestore.devSetCommitProbe(null);
			OrphanRestore.devSetBeforeSwapProbe(null);
			OrphanRestore.devSetAfterReplaceProbe(null);
			OrphanRestore.devSetRescaleFailures(0);
			OrphanRestore.devSetForceBusy(false);
			OrphanRestore.devResetLines();
			ChatPatchesAdapter.get().devReplaceBackend(null);
			ChatPatchesAdapter.get().selfCheck();
			ChatPatches.config.chatMaxMessages = 60;
		});
		setup(sp);
	}

	void visible(TestSingleplayerContext sp, String text) {
		t.sys(sp, text);
		t.waitT(2);
	}

	MessageRecord hidden(String text, String... tabs) {
		MessageRecord r = t.hideFixture(text, tabs);
		t.waitT(1);
		return r;
	}

	ConfigManager.OrphanCheck check(ConfigEdit... edits) { return onClient(() -> mgr().checkEdits(List.of(edits))); }

	ConfigManager.EditOutcome confirm(ConfigManager.OrphanCheck c, ConfigEdit... edits) { return onClient(() -> mgr().editAllRestoringOrphans(List.of(edits), c)); }

	/** Main chat, newest first, as fixture tokens. */
	List<String> mainTokens() {
		return onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().map(g -> token(g.content().getString())).toList());
	}

	static String token(String s) {
		int i = s.indexOf("cpx#");
		if (i < 0) return s;
		int j = i + 4;
		while (j < s.length() && !Character.isWhitespace(s.charAt(j))) j++;
		return s.substring(i, j);
	}

	boolean tabInFile(String id) { return Phase2Tests.tabOf(Phase2Tests.json(Phase2Tests.cfg()), null, id) != null; }

	List<String> lines() {
		return onClient(() -> {
			try {
				Field f = ChatComponent.class.getDeclaredField("trimmedMessages");
				f.setAccessible(true);
				@SuppressWarnings("unchecked") List<GuiMessage.Line> l = (List<GuiMessage.Line>) f.get(Minecraft.getInstance().gui.getChat());
				List<String> out = new ArrayList<>();
				for (GuiMessage.Line line : l) out.add(line.addedTime() + "|" + text(line.content()) + "|" + line.endOfEntry());
				return out;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	static String text(FormattedCharSequence seq) {
		StringBuilder sb = new StringBuilder();
		seq.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
		return sb.toString();
	}

	/** Lines as a fresh rebuild of the same main chat would produce them (reference for V-20o). */
	List<String> referenceLines() {
		ctx.runOnClient(c -> c.gui.getChat().rescaleChat());
		return lines();
	}

	long deliveries() { return onClient(() -> (long) Diagnostics.events("deliver").size()); }

	List<Path> tempFiles() throws java.io.IOException {
		try (var s = Files.list(Phase2Tests.cfg().getParent())) {
			return s.filter(p -> p.getFileName().toString().startsWith("cpextended.json.") && p.getFileName().toString().endsWith(".tmp")).toList();
		}
	}

	static boolean namesSafeAlternatives(String msg) {
		String m = msg.toLowerCase(java.util.Locale.ROOT);
		return m.contains("keep, disable or move") && !m.contains("wait") && !m.contains("later");
	}

	// ================================================================ mode q36

	void q36() throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			setup(sp);
			v20(sp);
			v20b(sp);
			sharedHidden(sp);
			v20g(sp);
			c1(sp);
			v20h(sp);
			v20l(sp);
			v20o(sp);
			v20p(sp);
			v20k(sp);
			v20d(sp);
			external(sp);
			v20m(sp);
			afterRefusal(sp);
			v20i(sp);
		}
		t.waitT(20);
	}

	/** V-20 (success), V-20j, no duplication, reconciler, Chat Patches log, lines. */
	void v20(TestSingleplayerContext sp) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		visible(sp, "visible one cpx#V1");
		MessageRecord h1 = hidden("hidden one cpx#H1-a", "qh");
		visible(sp, "visible two cpx#V2");
		MessageRecord h2 = hidden("hidden two cpx#H1-b", "qh");
		visible(sp, "visible three cpx#V3");
		int logBefore = onClient(ChatLog::messageCount);
		long deliveriesBefore = deliveries();
		ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qh"));
		m.put("disclosed", row("restored", c.restored(), "pruned", c.pruned(), "tabs", c.tabs()));
		ConfigManager.EditOutcome o = confirm(c, new ConfigEdit.DeleteTab("qh"));
		List<String> main = mainTokens();
		boolean order = main.subList(0, 5).equals(List.of("cpx#V3", "cpx#H1-b", "cpx#V2", "cpx#H1-a", "cpx#V1"));
		boolean identity = onClient(() -> {
			List<GuiMessage> all = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
			return all.get(1) == h2.message() && all.get(3) == h1.message();
		});
		boolean flags = onClient(() -> !h1.hidden() && !h2.hidden() && h1.tabIds().isEmpty() && SessionMirror.get().hiddenSize() == 0
				&& SessionMirror.get().tab("qh") == null && SessionMirror.get().records().contains(h1) && SessionMirror.get().records().contains(h2));
		long once = main.stream().filter(x -> x.equals("cpx#H1-a")).count() + main.stream().filter(x -> x.equals("cpx#H1-b")).count();
		boolean file = !tabInFile("qh");
		boolean log = onClient(ChatLog::messageCount) == logBefore;
		boolean noAddMessage = deliveries() == deliveriesBefore;
		List<String> lines = lines();
		boolean linesHaveOrphans = lines.stream().anyMatch(x -> x.contains("hidden one cpx#H1-a")) && lines.stream().anyMatch(x -> x.contains("hidden two cpx#H1-b"));
		boolean linesEqualReference = lines.equals(referenceLines());
		t.waitT(3);
		var rep = onClient(Reconciler::last);
		// the line under each restored message maps back to that message (S13: lines and entries 1:1)
		p4.open(() -> new ChatScreen("", false));
		t.waitT(2);
		Map<String, Object> hit = onClient(() -> {
			List<GuiMessage> all = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
			Map<String, Object> r = new LinkedHashMap<>();
			int found = 0;
			for (int y = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 30; y > 20 && found < 5; y -= 9) {
				int idx = ((obro1961.chatpatches.accessor.ChatComponentAccess) Minecraft.getInstance().gui.getChat()).getEoEIndex(10, y);
				if (idx >= 0 && idx < all.size()) { r.put("y" + y, token(all.get(idx).content().getString())); found++; }
			}
			return r;
		});
		ctx.setScreen(() -> null);
		boolean hitOk = hit.values().containsAll(List.of("cpx#V3", "cpx#H1-b", "cpx#V2", "cpx#H1-a"));
		m.put("applied", o.applied());
		m.put("mainChatNewestFirst", main.subList(0, Math.min(6, main.size())));
		m.put("captureOrder", order);
		m.put("sameGuiMessageObjects", identity);
		m.put("recordsVisibleNoTabNoHiddenRef", flags);
		m.put("eachOrphanOnce", once == 2);
		m.put("tabRemovedFromFile", file);
		m.put("chatLogUnchanged", log);
		m.put("neverThroughAddMessage", noAddMessage);
		m.put("linesShowOrphans", linesHaveOrphans);
		m.put("linesEqualReferenceRebuild", linesEqualReference);
		m.put("reconcilerNextTick", row("aligned", rep.aligned(), "stale", rep.stale(), "unobserved", rep.unobserved()));
		m.put("lineUnderCursorMapsToMessage", hit);
		boolean ok = c.restored() == 2 && c.pruned() == 0 && o.applied() && order && identity && flags && once == 2 && file && log && noAddMessage && linesHaveOrphans
				&& linesEqualReference && rep.aligned() && rep.unobserved() == 0 && hitOk;
		ev.result("V-20", ok, "observed", m, "note", "restored within seconds of joining (V-20j): restored lines keep their original addedTime; context-menu targeting checked through Chat Patches' own line lookup (getEoEIndex)");
		reset(sp);
	}

	/** V-20b: Cancel (GUI) changes nothing. */
	void v20b(TestSingleplayerContext sp) {
		visible(sp, "visible cpx#V1");
		hidden("hidden cpx#X1", "qx");
		Dump before = dump();
		WindowScreen ws = onClient(() -> new WindowScreen(new ConfigScreen(null), "q-a"));
		p4.open(ws);
		boolean shown = false;
		// the Delete button of the qx row (rows are in tab order qv, qh, qs, qx), clicked with the real mouse
		List<net.minecraft.client.gui.components.AbstractWidget> dels = onClient(() -> Minecraft.getInstance().screen.children().stream()
				.filter(w -> w instanceof net.minecraft.client.gui.components.AbstractWidget a && a.getMessage().getString().equals("Delete"))
				.map(w -> (net.minecraft.client.gui.components.AbstractWidget) w).toList());
		if (dels.size() >= 4) {
			var b = dels.get(3);
			p3.click(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0);
			t.waitT(2);
			p4.press("Yes");
			shown = onClient(() -> Minecraft.getInstance().screen instanceof ConfirmScreen cs && cs.getTitle().getString().contains("hidden messages"));
			p4.press("No");
		}
		Dump after = dump();
		ctx.setScreen(() -> null);
		ev.result("V-20b", shown && before.same(after), "disclosureShown", shown, "unchanged", after.diff(before));
		reset(sp);
	}

	/** A hidden message held by another remaining tab isn't orphaned: an ordinary deletion. */
	void sharedHidden(TestSingleplayerContext sp) {
		MessageRecord h = hidden("shared hidden cpx#S1", "qs", "qx");
		ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qs"));
		ConfigManager.EditOutcome o = onClient(() -> mgr().edit(new ConfigEdit.DeleteTab("qs")));
		boolean stillHidden = onClient(() -> h.hidden() && SessionMirror.get().tab("qx").entries().contains(h)) && !mainTokens().contains("cpx#S1");
		ev.result("Q36.sharedHiddenNotOrphaned", !c.orphaning() && o.applied() && stillHidden, "orphaning", c.orphaning(), "applied", o.applied(), "stillHiddenInOtherTab", stillHidden);
		reset(sp);
	}

	/** V-20g / V-20c: capacity safety and disclosed pruning. */
	void v20g(TestSingleplayerContext sp) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		// (1) one old orphan, main chat at the cap: refused, nothing changed
		hidden("old hidden cpx#X-old", "qx");
		for (int i = 0; i < 60; i++) t.sys(sp, "filler " + i + " cpx#V-f" + i);
		t.waitT(5);
		Dump before = dump();
		ConfigManager.OrphanCheck c1 = check(new ConfigEdit.DeleteTab("qx"));
		ConfigManager.EditOutcome forced = confirm(new ConfigManager.OrphanCheck(true, null, 1, 0, List.of("qx"), "\"qx\""), new ConfigEdit.DeleteTab("qx"));
		Dump after = dump();
		m.put("1.oneOrphan", row("refused", c1.refused(), "message", c1.refusal(), "confirmedAttemptRefused", !forced.applied(), "unchanged", before.same(after)));
		boolean ok1 = c1.refused() && namesSafeAlternatives(c1.refusal()) && !forced.applied() && before.same(after);
		// many orphans, some older than the window
		for (int i = 0; i < 5; i++) hidden("newer hidden " + i + " cpx#X-n" + i, "qx");
		ConfigManager.OrphanCheck cMany = check(new ConfigEdit.DeleteTab("qx"));
		m.put("1.manyOrphans", row("refused", cMany.refused(), "message", cMany.refusal()));
		ok1 &= cMany.refused() && namesSafeAlternatives(cMany.refusal());
		// (3) more traffic: still refused
		for (int i = 0; i < 10; i++) t.sys(sp, "more traffic " + i + " cpx#V-t" + i);
		t.waitT(4);
		ConfigManager.OrphanCheck c3 = check(new ConfigEdit.DeleteTab("qx"));
		m.put("3.afterMoreTraffic", row("refused", c3.refused()));
		ok1 &= c3.refused();
		reset(sp);
		// (2) orphans all survive; ordinary visible pruning disclosed and equal to actual
		for (int i = 0; i < 57; i++) t.sys(sp, "base " + i + " cpx#V-b" + i);
		t.waitT(4);
		hidden("survivor a cpx#X-a", "qx");
		visible(sp, "after a cpx#V-x1");
		hidden("survivor b cpx#X-b", "qx");
		visible(sp, "after b cpx#V-x2");
		hidden("survivor c cpx#X-c", "qx");
		visible(sp, "after c cpx#V-x3");
		t.waitT(3);
		List<String> beforeMain = mainTokens();
		ConfigManager.OrphanCheck c2 = check(new ConfigEdit.DeleteTab("qx"));
		ConfigManager.EditOutcome o2 = confirm(c2, new ConfigEdit.DeleteTab("qx"));
		List<String> afterMain = mainTokens();
		List<String> prunedActual = beforeMain.stream().filter(x -> !afterMain.contains(x)).toList();
		boolean survivors = afterMain.containsAll(List.of("cpx#X-a", "cpx#X-b", "cpx#X-c"));
		boolean capKept = afterMain.size() <= 60;
		t.waitT(3);
		boolean tabsFollow = onClient(() -> SessionMirror.get().tabStates().stream().flatMap(s -> s.entries().stream())
				.allMatch(r -> r.hidden() || AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).stream().anyMatch(g -> g == r.message())));
		m.put("2.survive", row("disclosedPruned", c2.pruned(), "actualPruned", prunedActual.size(), "prunedTokens", prunedActual, "applied", o2.applied(),
				"orphansInMainChat", survivors, "mainChatSize", afterMain.size(), "tabsFollowLiveness", tabsFollow));
		boolean ok2 = !c2.refused() && c2.restored() == 3 && o2.applied() && c2.pruned() == prunedActual.size() && c2.pruned() == 3 && survivors && capKept && tabsFollow;
		ev.result("V-20g", ok1 && ok2, "observed", m);
		ev.result("V-20c", ok2, "observed", m.get("2.survive"), "note", "pruned entries were never removed from Chat Patches' log by Extended (log count unchanged in V-20); whether they come back after relaunch is Chat Patches' log retention (V-20f)");
		reset(sp);
	}

	/** C-1 / V-49: cap unreadable (degraded) refuses orphan-producing deletions only. */
	void c1(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		MessageRecord h = hidden("hidden for c1 cpx#X-c1", "qx");
		visible(sp, "visible c1 cpx#V-c1");
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		ctx.runOnClient(c -> {
			ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) {
				@Override public int chatMaxMessages() { throw new IllegalStateException("test: cap unreadable"); }
			});
			ChatPatchesAdapter.get().selfCheck();
		});
		boolean degraded = onClient(() -> ChatPatchesAdapter.get().isDegraded());
		Dump before = dump();
		ConfigManager.OrphanCheck delTab = check(new ConfigEdit.DeleteTab("qx"));
		ConfigManager.OrphanCheck delWin = check(new ConfigEdit.DeleteWindow("q-a"));
		Dump after = dump();
		ConfigManager.EditOutcome geo = onClient(() -> mgr().edit(new ConfigEdit.SetGeometry("q-b", 0.1, 0.5, 0.3, 0.2)));
		t.waitT(25);
		ConfigManager.EditOutcome filt = onClient(() -> mgr().edit(new ConfigEdit.SetFilter("qm", "include", 0, FilterSpec.literal("cpx#M2"))));
		ConfigManager.OrphanCheck moveCheck = check(new ConfigEdit.MoveTab("qx", "q-b"));
		ConfigManager.EditOutcome move = onClient(() -> mgr().edit(new ConfigEdit.MoveTab("qx", "q-b")));
		ConfigManager.EditOutcome disable = onClient(() -> mgr().edit(new ConfigEdit.SetTabFlag("qx", "enabled", false)));
		boolean stillThere = onClient(() -> h.hidden() && SessionMirror.get().tab("qx").entries().contains(h));
		m.put("degraded", degraded);
		m.put("1.deleteTab", row("refused", delTab.refused(), "message", delTab.refusal()));
		m.put("2.deleteWindow", row("refused", delWin.refused()));
		m.put("unchanged", before.same(after));
		m.put("3.unrelatedSaves", row("geometry", geo.applied(), "filterOnOtherTab", filt.applied(), "lastSave", onClient(() -> mgr().lastSave())));
		m.put("4.moveAndDisableNoPrompt", row("moveOrphaning", moveCheck.orphaning(), "move", move.applied(), "disable", disable.applied()));
		m.put("5.hiddenStillInTab", stillThere);
		boolean ok = degraded && delTab.refused() && namesSafeAlternatives(delTab.refusal()) && delWin.refused() && before.same(after) && geo.applied() && filt.applied()
				&& !moveCheck.orphaning() && move.applied() && disable.applied() && stillThere;
		ev.result("V-49", ok, "observed", m, "note", "new matching messages still route there (not hidden) is a Stage B check (hiding off here)");
		reset(sp);
	}

	/** V-20h: each safe-point condition fails in turn; refused, nothing deferred. */
	void v20h(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		hidden("hidden for v20h cpx#X-h", "qx");
		visible(sp, "visible v20h cpx#V-h");
		Dump before = dump();
		boolean ok = true;
		ChatPatchesBackend real = onClient(() -> ChatPatchesAdapter.get().devRealBackend());
		for (String cond : new String[]{"isLoading", "isRestoring", "chatScreenOpen", "captureFrame"}) {
			ConfigManager.OrphanCheck c;
			switch (cond) {
				case "isLoading" -> {
					ctx.runOnClient(x -> ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) { @Override public boolean isLoading() { return true; } }));
					c = check(new ConfigEdit.DeleteTab("qx"));
					ctx.runOnClient(x -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
				}
				case "isRestoring" -> {
					ctx.runOnClient(x -> ChatPatchesAdapter.get().devReplaceBackend(new Phase3Tests.DelegatingBackend(real) { @Override public boolean isRestoring() { return true; } }));
					c = check(new ConfigEdit.DeleteTab("qx"));
					ctx.runOnClient(x -> { ChatPatchesAdapter.get().devReplaceBackend(null); ChatPatchesAdapter.get().selfCheck(); });
				}
				case "chatScreenOpen" -> {
					ctx.setScreen(() -> new ChatScreen("", false));
					t.waitT(2);
					c = check(new ConfigEdit.DeleteTab("qx"));
					ctx.setScreen(() -> null);
				}
				default -> c = onClient(() -> {
					// a real capture frame: Extended is processing an incoming message
					Capture.enter(Minecraft.getInstance().gui.getChat(), Component.literal("frame"), null);
					try {
						return mgr().checkEdits(List.of(new ConfigEdit.DeleteTab("qx")));
					} finally {
						Capture.exit(Minecraft.getInstance().gui.getChat());
					}
				});
			}
			boolean refused = c.refused() && c.refusal().contains("busy");
			ok &= refused;
			m.put(cond, row("refused", refused, "message", c.refusal()));
		}
		t.waitT(10); // conditions cleared: nothing happens unless the player repeats the change
		Dump after = dump();
		m.put("unchangedAfterConditionsCleared", after.diff(before));
		ev.result("V-20h", ok && before.same(after), "observed", m);
		reset(sp);
	}

	/** V-20l: atomicity, failure and rollback. */
	void v20l(TestSingleplayerContext sp) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		String[] cases = {"2.tempWriteFails", "3a.atomicProbeUnsupported", "3b.renameAtomicNotSupported", "4a.renameTransientCleared", "4b.renameTransientPersisting",
				"4c.renamePermanent", "4d.fileChangedBeforeRename"};
		for (String cs : cases) {
			hidden("hidden for " + cs + " cpx#X-l", "qx");
			visible(sp, "visible for " + cs + " cpx#V-l");
			ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qx"));
			ctx.runOnClient(x -> {
				ConfigStorage st = mgr().storage();
				switch (cs) {
					case "2.tempWriteFails" -> st.devSetFailTempWrite(true);
					case "3a.atomicProbeUnsupported" -> st.devSetProbeUnsupported(true);
					case "3b.renameAtomicNotSupported" -> st.devSetRenameFailures(0, false, true);
					case "4a.renameTransientCleared" -> st.devSetRenameFailures(1, false, false);
					case "4b.renameTransientPersisting" -> st.devSetRenameFailures(2, false, false);
					case "4c.renamePermanent" -> st.devSetRenameFailures(0, true, false);
					default -> OrphanRestore.devSetCommitProbe(() -> {
						try {
							Files.writeString(Phase2Tests.cfg(), Files.readString(Phase2Tests.cfg()) + "\n");
						} catch (java.io.IOException e) {
							throw new IllegalStateException(e);
						}
					});
				}
			});
			Dump before = dump();
			ConfigManager.EditOutcome o = confirm(c, new ConfigEdit.DeleteTab("qx"));
			Dump after = dump();
			List<Path> temps = tempFiles();
			boolean expectSuccess = cs.startsWith("4a");
			boolean pass;
			Map<String, Object> r = row("applied", o.applied(), "message", o.edit().reason(), "tempFilesLeft", temps.size());
			if (expectSuccess) {
				pass = o.applied() && !tabInFile("qx") && mainTokens().contains("cpx#X-l");
			} else if (cs.startsWith("4d")) {
				// the external edit itself changed the file: compare everything but the bytes, which must be the edited version
				byte[] edited = Phase2Tests.bytes(Phase2Tests.cfg());
				pass = !o.applied() && identical(before.all, after.all) && before.allListObject == after.allListObject && before.tabs.equals(after.tabs)
						&& before.hidden.equals(after.hidden) && tabInFile("qx") && new String(edited).endsWith("\n");
				r.put("stateExceptFile", row("allMessagesIdentity", identical(before.all, after.all), "listObject", before.allListObject == after.allListObject,
						"tabs", before.tabs.equals(after.tabs), "hidden", before.hidden.equals(after.hidden)));
			} else {
				pass = !o.applied() && before.same(after) && temps.isEmpty();
				r.put("unchanged", after.diff(before));
				if (cs.startsWith("3")) pass &= String.valueOf(o.edit().reason()).contains("file system");
			}
			r.put("ok", pass);
			m.put(cs, r);
			ok &= pass;
			reset(sp);
		}
		ev.result("V-20l", ok, "cases", m, "note", "(1) projection failure is V-20g(1); (5) rebuild failure is V-20o; (6) process kill is q36_kill_*; a JVM Error thrown inside the commit is V-20l.error");
	}

	/** V-20o: rebuild failures after the commit; retries only with chat closed; reference rebuild comparison. */
	void v20o(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		boolean ok = true;
		for (int fails = 1; fails <= 4; fails++) {
			visible(sp, "visible o" + fails + " cpx#V-o");
			hidden("hidden o" + fails + " cpx#X-o", "qx");
			visible(sp, "visible o" + fails + "b cpx#V-ob");
			int f = fails;
			ctx.runOnClient(x -> { OrphanRestore.devSetRescaleFailures(f); Warnings.devReset(); });
			ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qx"));
			boolean chatReopened = fails == 2;
			if (chatReopened) {
				ConfigManager.EditOutcome o = confirm(c, new ConfigEdit.DeleteTab("qx"));
				ctx.setScreen(() -> new ChatScreen("", false));
				t.waitT(40);
				boolean stillDirtyWhileOpen = onClient(OrphanRestore::linesDirty);
				ctx.setScreen(() -> null);
				t.waitT(60);
				boolean clean = !onClient(OrphanRestore::linesDirty);
				List<String> lines = lines();
				boolean ref = lines.equals(referenceLines());
				boolean pass = o.applied() && stillDirtyWhileOpen && clean && ref;
				m.put("fails=" + fails + ",chatOpenMidRetry", row("committed", o.applied(), "noRetryWhileChatOpen", stillDirtyWhileOpen, "rebuiltAfterClose", clean, "linesEqualReference", ref, "ok", pass));
				ok &= pass;
			} else {
				ConfigManager.EditOutcome o = confirm(c, new ConfigEdit.DeleteTab("qx"));
				boolean dirty = onClient(OrphanRestore::linesDirty);
				t.waitT(60);
				boolean clean = !onClient(OrphanRestore::linesDirty);
				int toasts = onClient(Warnings::toastsShown);
				Map<String, Object> r = row("committed", o.applied(), "dirtyAfterCommit", dirty, "cleanAfterRetries", clean, "toasts", toasts);
				boolean pass;
				if (fails < 4) {
					boolean ref = lines().equals(referenceLines());
					r.put("linesEqualReference", ref);
					pass = o.applied() && dirty && clean && ref && toasts == 0;
				} else {
					// 1 failure at commit + 3 retries: final failure, one toast, flag kept; the next chat close rebuilds
					ctx.setScreen(() -> new ChatScreen("", false));
					t.waitT(3);
					ctx.setScreen(() -> null);
					t.waitT(3);
					boolean afterClose = !onClient(OrphanRestore::linesDirty);
					boolean ref = lines().equals(referenceLines());
					r.put("rebuiltAtNextChatClose", afterClose);
					r.put("linesEqualReference", ref);
					pass = o.applied() && dirty && !clean && toasts == 1 && afterClose && ref;
				}
				r.put("ok", pass);
				m.put("fails=" + fails, r);
				ok &= pass;
			}
			reset(sp);
		}
		ev.result("V-20o", ok, "cases", m, "note", "a rebuild failure never undoes the commit (no data change); 'or rescale' retry trigger not implemented: retries are the 3 scheduled attempts and every later chat close");
	}

	/** V-20p: no observable partial state; probes can't interleave with the synchronous commit. */
	void v20p(TestSingleplayerContext sp) {
		visible(sp, "visible p cpx#V-p");
		MessageRecord h = hidden("hidden p cpx#X-p", "qx");
		java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
		ctx.runOnClient(c -> net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(cl -> ticks.incrementAndGet()));
		List<String> observedAtSwap = new ArrayList<>();
		ctx.runOnClient(c -> OrphanRestore.devSetCommitProbe(() -> {
			// inside the commit, between the swaps and the rename: the new state must already be complete
			boolean inMain = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()).contains(h.message());
			observedAtSwap.add("inMain=" + inMain + ",hidden=" + h.hidden() + ",tabGone=" + (SessionMirror.get().tab("qx") == null)
					+ ",configHasTab=" + (mgr().config().windowOfTab("qx") != null) + ",tick=" + ticks.get());
		}));
		ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qx"));
		int[] around = onClient(() -> {
			int before = ticks.get();
			int deliveries = Diagnostics.events("deliver").size();
			mgr().editAllRestoringOrphans(List.of(new ConfigEdit.DeleteTab("qx")), c);
			return new int[]{before, ticks.get(), deliveries, Diagnostics.events("deliver").size()};
		});
		boolean ok = observedAtSwap.size() == 1 && observedAtSwap.get(0).startsWith("inMain=true,hidden=false,tabGone=true,configHasTab=false")
				&& around[0] == around[1] && around[2] == around[3];
		ev.result("V-20p", ok, "observationInsideCommit", observedAtSwap, "ticksDuringCommit", around[1] - around[0], "addMessageCallsDuringCommit", around[3] - around[2],
				"note", "the commit is one synchronous call on the client thread: no tick listener, HUD frame or addMessage hook can run inside it; the probe at the swap point already sees the complete new state");
		ctx.runOnClient(x -> OrphanRestore.devSetCommitProbe(null));
		reset(sp);
	}

	/** V-20k: nonempty window, per-tab move-or-delete in one atomic operation; empty window deleted directly. */
	void v20k(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		visible(sp, "for qm cpx#M1");
		MessageRecord h = hidden("hidden in qd cpx#D1", "qd");
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("q-b", "qd")));
		t.sys(sp, "unread for qm cpx#M2");
		t.waitT(3);
		int unreadQm = onClient(() -> SessionMirror.get().tab("qm").unreadCount());
		var qmState = onClient(() -> SessionMirror.get().tab("qm"));
		WindowScreen ws = onClient(() -> new WindowScreen(new ConfigScreen(null), "q-b"));
		p4.open(ws);
		p4.press("Delete window");
		boolean choice = onClient(() -> Minecraft.getInstance().screen instanceof io.github.itznotneptune.cpextended.gui.DeleteWindowScreen);
		ctx.runOnClient(c -> ((io.github.itznotneptune.cpextended.gui.DeleteWindowScreen) Minecraft.getInstance().screen).choose("qm", "q-a"));
		p4.press("Delete window");
		boolean disclosed = onClient(() -> Minecraft.getInstance().screen instanceof ConfirmScreen);
		p4.press("Yes");
		boolean windowGone = onClient(() -> mgr().config().window("q-b") == null);
		boolean moved = "q-a".equals(onClient(() -> { var w = mgr().config().windowOfTab("qm"); return w == null ? null : w.id(); }));
		boolean sameState = onClient(() -> SessionMirror.get().tab("qm")) == qmState && onClient(() -> SessionMirror.get().tab("qm").unreadCount()) == unreadQm;
		boolean restored = mainTokens().contains("cpx#D1") && onClient(() -> !h.hidden());
		ctx.setScreen(() -> null);
		// empty window: deleted directly after the ordinary confirmation, no orphan step
		WindowScreen we = onClient(() -> new WindowScreen(new ConfigScreen(null), "q-empty"));
		p4.open(we);
		p4.press("Delete window");
		p4.press("Yes");
		boolean emptyDeleted = onClient(() -> mgr().config().window("q-empty") == null);
		ctx.setScreen(() -> null);
		m.put("perTabChoiceScreen", choice);
		m.put("disclosureShown", disclosed);
		m.put("windowDeleted", windowGone);
		m.put("movedTabKeepsIdentityAndUnread", row("moved", moved, "sameStateAndUnread", sameState, "unread", unreadQm));
		m.put("deletedHidingTabRestored", restored);
		m.put("emptyWindowDeletedDirectly", emptyDeleted);
		ev.result("V-20k", choice && disclosed && windowGone && moved && sameState && unreadQm > 0 && restored && emptyDeleted, "observed", m);
		reset(sp);
	}

	/** V-20d / V-20e / V-45c negatives: nothing but deletion triggers the flow. */
	void v20d(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		MessageRecord h = hidden("hidden for v20d cpx#X-d", "qx");
		Map<String, ConfigEdit> negatives = new LinkedHashMap<>();
		negatives.put("3.disableWindow", new ConfigEdit.SetWindowEnabled("q-a", false));
		negatives.put("4.disableTab", new ConfigEdit.SetTabFlag("qx", "enabled", false));
		negatives.put("5a.moveTab", new ConfigEdit.MoveTab("qx", "q-b"));
		negatives.put("5b.moveToNewWindow", new ConfigEdit.MoveTabToNewWindow("qx", "q-new", 0.3, 0.3, 0.2, 0.2));
		negatives.put("e.hideToggleOff", new ConfigEdit.SetTabFlag("qx", "hideFromMainChat", false));
		boolean ok = true;
		for (var e : negatives.entrySet()) {
			ConfigManager.OrphanCheck c = check(e.getValue());
			ConfigManager.EditOutcome o = onClient(() -> mgr().edit(e.getValue()));
			boolean kept = onClient(() -> h.hidden() && SessionMirror.get().tab("qx") != null && SessionMirror.get().tab("qx").entries().contains(h));
			boolean pass = !c.orphaning() && o.applied() && kept;
			ok &= pass;
			m.put(e.getKey(), row("prompt", c.orphaning(), "applied", o.applied(), "hiddenStaysInTab", kept, "ok", pass));
		}
		// (6) suspension through a hand-edited invalid filter: the tab stays in the file, nothing orphans
		var j = Phase2Tests.json(Phase2Tests.cfg());
		var qx = Phase2Tests.tabOf(j, null, "qx");
		qx.getAsJsonArray("include").get(0).getAsJsonObject().addProperty("pattern", "");
		Phase2Tests.writeJson(Phase2Tests.cfg(), j);
		ConfigManager.SaveOutcome adopted = onClient(() -> mgr().checkForExternalChanges());
		boolean suspendedKept = onClient(() -> h.hidden() && SessionMirror.get().tab("qx").entries().contains(h)) && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
		m.put("6.suspendedByInvalidFilter", row("external", adopted, "hiddenStaysInTab", suspendedKept));
		ok &= suspendedKept;
		ev.result("V-20d", ok, "observed", m, "note", "triggers (1)(2) are V-20 and V-20k");
		ev.result("V-20e", Boolean.TRUE.equals(((Map<?, ?>) m.get("e.hideToggleOff")).get("ok")), "observed", m.get("e.hideToggleOff"));
		ev.result("V-45c", ok, "note", "deletion runs the flow (V-20, V-20k); disabling, moving and suspending never do (this check)");
		reset(sp);
	}

	/** External edits and P14 locks: an orphaning hand edit locks; Apply file runs the confirmed transaction. */
	void external(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		MessageRecord h = hidden("hidden for external cpx#X-e", "qx");
		visible(sp, "visible external cpx#V-e");
		var j = Phase2Tests.json(Phase2Tests.cfg());
		var tabs = j.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("tabs");
		for (int i = 0; i < tabs.size(); i++) if ("qx".equals(tabs.get(i).getAsJsonObject().get("id").getAsString())) { tabs.remove(i); break; }
		Phase2Tests.writeJson(Phase2Tests.cfg(), j);
		ConfigManager.SaveOutcome s = onClient(() -> mgr().attemptSave("test"));
		boolean locked = onClient(() -> mgr().state()) == ConfigManager.State.DIVERGENCE && onClient(() -> h.hidden());
		// while locked, an in-game orphaning deletion is refused (the lock prevents the commit)
		ConfigManager.OrphanCheck delOther = check(new ConfigEdit.DeleteTab("qx"));
		ConfigManager.EditOutcome lockedDelete = confirm(delOther, new ConfigEdit.DeleteTab("qx"));
		ConfigManager.ActionResult unconfirmed = onClient(() -> mgr().applyFile());
		ConfigManager.OrphanCheck c = onClient(() -> mgr().checkApplyFile());
		ConfigManager.ActionResult applied = onClient(() -> mgr().applyFileRestoringOrphans(c));
		boolean restored = mainTokens().contains("cpx#X-e") && onClient(() -> !h.hidden()) && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
		m.put("handEditLocks", row("save", s, "locked", locked));
		m.put("deleteWhileLockedRefused", row("applied", lockedDelete.applied(), "message", lockedDelete.edit().reason()));
		m.put("applyFileUnconfirmedRefused", row("ok", unconfirmed.ok(), "message", unconfirmed.message()));
		m.put("applyFileConfirmed", row("disclosed", c.restored(), "ok", applied.ok(), "message", applied.message(), "restoredAndUnlocked", restored));
		ev.result("Q36.externalEditsAndLocks", locked && !lockedDelete.applied() && !unconfirmed.ok() && c.restored() == 1 && applied.ok() && restored, "observed", m,
				"note", "a file change between the check and the rename is V-20l(4d); V-62's residual check-to-rename race is unchanged and not claimed closed");
		reset(sp);
	}

	/** V-20m (subset), V-20n: Chat Patches after the list swap; a mod caching the old list. */
	void v20m(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		visible(sp, "before m cpx#V-m1");
		hidden("restored target cpx#X-m", "qx");
		visible(sp, "after m cpx#V-m2");
		List<GuiMessage> cached = onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()));
		ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qx"));
		confirm(c, new ConfigEdit.DeleteTab("qx"));
		List<GuiMessage> now = onClient(() -> AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat()));
		m.put("V-20n.cachedListIsOldObject", cached != now);
		m.put("V-20n.cachedListStale", cached.stream().noneMatch(g -> g.content().getString().contains("cpx#X-m")));
		// Chat Patches search on the new list (plain), drafted on reopen
		p4.open(() -> new ChatScreen("", false));
		t.waitT(2);
		ctx.runOnClient(x -> {
			var f = ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen);
			if (f != null) f.setValue("restored target");
		});
		t.waitT(2);
		List<String> results = onClient(() -> ((obro1961.chatpatches.accessor.ChatScreenAccess) Minecraft.getInstance().screen).getSearchResults().stream()
				.map(g -> token(g.content().getString())).toList());
		ctx.runOnClient(x -> {
			var f = ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen);
			if (f != null) f.setValue("");
		});
		ctx.setScreen(() -> null);
		m.put("searchFindsRestored", results);
		// duplicate condensation on the new list: the same text again condenses the restored copy away
		t.sys(sp, "after m cpx#V-m2");
		t.waitT(4);
		long copies = mainTokens().stream().filter(x -> x.equals("cpx#V-m2")).count();
		m.put("condensationOnNewList.copies", copies);
		boolean ok = cached != now && results.contains("cpx#X-m") && copies == 1;
		ev.result("V-20m", ok, "observed", m, "note", "subset: search and condensation on the swapped list; every context-menu action and the world switch are covered by V-20 targeting and V-20i");
		ev.result("V-20n", cached != now, "observed", row("cachedIsOld", cached != now, "cachedStaleNoCrash", m.get("V-20n.cachedListStale")),
				"note", "a mod holding the old list sees stale data (documented, architecture §8.4); no crash; real-mod matrix (Chat Heads etc.) is Phase 8");
		reset(sp);
	}

	/** Recovery after refused and cancelled operations: a later valid restoration succeeds. */
	void afterRefusal(TestSingleplayerContext sp) {
		hidden("hidden after refusal cpx#X-r", "qx");
		visible(sp, "visible r cpx#V-r");
		ctx.runOnClient(c -> mgr().storage().devSetFailTempWrite(true));
		ConfigManager.OrphanCheck c1 = check(new ConfigEdit.DeleteTab("qx"));
		ConfigManager.EditOutcome refused = confirm(c1, new ConfigEdit.DeleteTab("qx"));
		ctx.runOnClient(c -> mgr().storage().devSetFailTempWrite(false));
		ConfigManager.OrphanCheck c2 = check(new ConfigEdit.DeleteTab("qx"));
		ConfigManager.EditOutcome ok = confirm(c2, new ConfigEdit.DeleteTab("qx"));
		ev.result("Q36.recoveryAfterRefusal", !refused.applied() && ok.applied() && mainTokens().contains("cpx#X-r"), "refused", refused.edit().reason(), "laterApplied", ok.applied());
		reset(sp);
	}

	/** V-20i: orphans newer than the newest boundary line restored, then a world switch. Run last (leaves the world). */
	void v20i(TestSingleplayerContext sp) {
		// recorded in q36_boundary (needs world switches); placeholder kept for order
	}

	// ================================================================ mode q36_boundary (V-20i, V-20l.error)

	void q36Boundary() throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			setup(sp);
			ctx.runOnClient(c -> ChatPatches.config.boundary = true);
			visible(sp, "visible i cpx#V-i");
		}
		t.waitT(20);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			int boundariesBefore = onClient(Phase6Tests::boundaryCount);
			hidden("newer than boundary cpx#X-i", "qx");
			ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qx"));
			ConfigManager.EditOutcome o = confirm(c, new ConfigEdit.DeleteTab("qx"));
			List<String> top = mainTokens().subList(0, 2);
			m.put("restored", o.applied());
			m.put("newestEntriesAfterRestore", top);
			m.put("boundariesBefore", boundariesBefore);
		}
		t.waitT(20);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			int after = onClient(Phase6Tests::boundaryCount);
			boolean newestIsBoundary = onClient(() -> {
				var all = AcceptanceTest.allMessagesReflective(Minecraft.getInstance().gui.getChat());
				return !all.isEmpty() && Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(all.get(0).content()));
			});
			m.put("boundariesAfterSwitch", after);
			m.put("newestIsBoundaryAfterSwitch", newestIsBoundary);
			boolean ok = Boolean.TRUE.equals(m.get("restored")) && newestIsBoundary && after >= (Integer) m.get("boundariesBefore");
			ev.result("V-20i", ok, "observed", m, "note", "expected: the old boundary is no longer the newest entry after the restore, so Chat Patches adds a new one at the next switch; boundary lines stay in the main chat (Q10)");
			// V-20l(6) in-session part: a JVM Error inside the commit (not caught by design)
			setup(sp);
			hidden("hidden for error cpx#X-err", "qx");
			visible(sp, "visible err cpx#V-err");
			byte[] before = Phase2Tests.bytes(Phase2Tests.cfg());
			ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qx"));
			ctx.runOnClient(x -> OrphanRestore.devSetCommitProbe(() -> { throw new AssertionError("test: JVM error inside the commit"); }));
			String thrown = null;
			try {
				confirm(c, new ConfigEdit.DeleteTab("qx"));
			} catch (Throwable e) {
				thrown = e.toString();
			}
			ctx.runOnClient(x -> OrphanRestore.devSetCommitProbe(null));
			byte[] fileAfter = Phase2Tests.bytes(Phase2Tests.cfg());
			boolean fileOld = Arrays.equals(before, fileAfter);
			boolean memorySwapped = mainTokens().contains("cpx#X-err");
			ev.result("V-20l.error", thrown != null && fileOld, "thrown", thrown, "configFileStillOld", fileOld, "inMemoryStateSwapped", memorySwapped,
					"note", "by design (architecture §4.9) JVM errors are not caught: the session's in-memory state is not trusted after one; the file is the old version (the rename never ran); a relaunch rebuilds from it (process kills: q36_kill_*)");
		}
		t.waitT(20);
	}

	// ================================================================ process kills (V-20l(6))

	static final Path KILL_RECORD = Path.of("cpx-evidence", "q36-kill.json");

	/** Kills the JVM at {@code phase} of a confirmed orphan restoration, after recording the old file. */
	void q36Kill(String phase) throws Exception {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			setup(sp);
			hidden("hidden for kill cpx#X-k", "qx");
			visible(sp, "visible kill cpx#V-k");
			byte[] old = Phase2Tests.bytes(Phase2Tests.cfg());
			Files.writeString(KILL_RECORD, "{\"phase\": \"" + phase + "\", \"oldSha\": \"" + sha(old) + "\", \"oldLength\": " + old.length + "}");
			ConfigManager.OrphanCheck c = check(new ConfigEdit.DeleteTab("qx"));
			Runnable halt = () -> Runtime.getRuntime().halt(3);
			ctx.runOnClient(x -> {
				switch (phase) {
					case "beforeSwap" -> OrphanRestore.devSetBeforeSwapProbe(halt);
					case "betweenSwapAndRename" -> OrphanRestore.devSetCommitProbe(halt);
					default -> OrphanRestore.devSetAfterReplaceProbe(halt);
				}
			});
			confirm(c, new ConfigEdit.DeleteTab("qx"));
			ev.result("q36_kill." + phase, Evidence.Outcome.FAIL, "reason", "the JVM was expected to halt inside the commit");
		}
	}

	/** After a kill: the file is exactly the old version or exactly the new one (parseable, only the tab removed). */
	void q36KillCheck() throws Exception {
		var rec = com.google.gson.JsonParser.parseString(Files.readString(KILL_RECORD)).getAsJsonObject();
		byte[] now = Phase2Tests.bytes(Phase2Tests.cfg());
		boolean isOld = sha(now).equals(rec.get("oldSha").getAsString());
		var parsed = io.github.itznotneptune.cpextended.config.ConfigParser.parse(now);
		boolean parseable = parsed.status() == io.github.itznotneptune.cpextended.config.ConfigParser.Status.OK;
		boolean isNew = parseable && parsed.config().windowOfTab("qx") == null && parsed.config().windowOfTab("qv") != null && parsed.config().windowOfTab("qm") != null;
		List<Path> temps = tempFiles();
		String phase = rec.get("phase").getAsString();
		String expect = phase.equals("afterReplace") ? "new" : "old";
		boolean ok = parseable && (isOld || isNew) && (expect.equals("old") ? isOld : isNew);
		// this launch's startup already ran: count again after another startup pass, which deletes leftovers
		ctx.runOnClient(c -> mgr().devSimulateRestart());
		boolean startsNormally = onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
		int tempsAfterStartup = tempFiles().size();
		ev.result("V-20l.kill." + phase, ok && startsNormally && tempsAfterStartup == 0, "fileIsOld", isOld, "fileIsNew", isNew, "parseable", parseable, "expected", expect,
				"tempFilesAtCheck", temps.size(), "tempFilesAfterStartup", tempsAfterStartup, "relaunchState", onClient(() -> mgr().state()),
				"note", "the process was halted (Runtime.halt) at that point; this launch reads what was left on disk; session state is rebuilt from config plus Chat Patches' restore");
		Files.deleteIfExists(KILL_RECORD);
		for (Path p : temps) Files.deleteIfExists(p);
	}

	static String sha(byte[] b) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
	}

	static boolean same(Object a, Object b) { return Objects.equals(a, b); }
	static IdentityHashMap<Object, Boolean> unused() { return new IdentityHashMap<>(); }
}
