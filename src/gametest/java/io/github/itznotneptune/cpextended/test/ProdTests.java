// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import io.github.itznotneptune.cpextended.gui.TabScreen;
import io.github.itznotneptune.cpextended.gui.WindowScreen;
import io.github.itznotneptune.cpextended.mixin.ChatComponentAccessor;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.search.TabSearch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import obro1961.chatpatches.ChatPatches;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Private validation (RC.1, repeated for RC.2): a smoke test inside a production-style client (no development environment, the obfuscated
 * game remapped to intermediary by Fabric Loader, the release jar and published dependencies in mods/). It uses no
 * development hook (they are inert in production by design): every step goes through real input or public state.
 * prod1: first launch. prod2: relaunch (restored history). prod3: relaunch with a damaged settings file (recovery).
 */
final class ProdTests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase3Tests p3;
	private final Phase4Tests p4;

	ProdTests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p3 = new Phase3Tests(t);
		this.p4 = new Phase4Tests(t);
	}

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	ConfigManager mgr() { return ConfigManager.get(); }

	/** The main chat through Extended's own accessor (Mojang field names don't exist in production). */
	List<GuiMessage> main() { return onClient(() -> new ArrayList<>(((ChatComponentAccessor) Minecraft.getInstance().gui.getChat()).cpextended$getAllMessages())); }

	boolean inMain(String tok) { return main().stream().anyMatch(g -> g.content().getString().contains(tok)); }

	MessageRecord rec(String tok) {
		return onClient(() -> {
			MessageRecord best = null;
			for (MessageRecord r : SessionMirror.get().records()) if (r.original() != null && r.original().getString().contains(tok)) best = r;
			for (MessageRecord r : SessionMirror.get().hiddenRecords()) if (r.original() != null && r.original().getString().contains(tok)) best = r;
			return best;
		});
	}

	int unread(String tab) { return onClient(() -> { TabState s = SessionMirror.get().tab(tab); return s == null ? 0 : s.unreadCount(); }); }

	String tabIdNamed(String name) {
		return onClient(() -> {
			for (var w : mgr().config().windows()) for (var tb : w.tabs()) if (tb.config() != null && name.equals(tb.config().name())) return tb.id();
			return null;
		});
	}

	String windowOfTab(String tabId) { return onClient(() -> { var w = mgr().config().windowOfTab(tabId); return w == null ? null : w.id(); }); }

	Screen screen() { return onClient(() -> Minecraft.getInstance().screen); }

	// ================================================================ prod1

	void prod1() throws Exception {
		Map<String, Object> env = new LinkedHashMap<>();
		env.put("developmentEnvironment", FabricLoader.getInstance().isDevelopmentEnvironment());
		env.put("extendedVersion", FabricLoader.getInstance().getModContainer("cpextended").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(null));
		env.put("extendedJar", FabricLoader.getInstance().getModContainer("cpextended").map(m -> m.getOrigin().toString()).orElse(null));
		env.put("javaVersion", System.getProperty("java.version"));
		env.put("configCreatedAtLaunch", Files.exists(Phase2Tests.cfg()));
		env.put("defaultConfigHasNoWindows", onClient(() -> mgr().config().windows().isEmpty()));
		env.put("otherMods", FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId()).filter(id -> List.of("chat_heads", "nochatreports", "modmenu").contains(id)).toList());
		ev.result("RC1.environment", Boolean.FALSE.equals(env.get("developmentEnvironment")) && System.getProperty("cpx.expectedVersion", "?").equals(env.get("extendedVersion")) && String.valueOf(env.get("extendedJar")).endsWith("cpextended-" + env.get("extendedVersion") + ".jar")
				&& Boolean.TRUE.equals(env.get("configCreatedAtLaunch")) && Boolean.TRUE.equals(env.get("defaultConfigHasNoWindows")), "observed", env);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			ctx.runOnClient(c -> { ChatPatches.config.boundary = true; c.options.hideGui = false; });
			createViaGui(sp);
			backupGenerated();
			routingAndHiding(sp);
			unreadAndSelection(sp);
			dragResizeRelocate(sp);
			search(sp);
			hidingSwitch(sp);
		}
		t.waitT(20);
		reconnect();
	}

	String smoke, all, windowId;

	/** Key binding, Add window, Add tab, name, include and exclude filters, hide toggle, matchAll, Save. */
	void createViaGui(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		ctx.getInput().pressKey(InputConstants.KEY_K);
		t.waitT(5);
		boolean keyOpens = screen() instanceof ConfigScreen;
		if (!keyOpens) p4.open(() -> new ConfigScreen(null));
		ctx.takeScreenshot("rc2-prod-gui-settings-empty");
		p4.press("Add window");
		windowId = screen() instanceof WindowScreen w ? onClient(w::windowId) : null;
		ctx.takeScreenshot("rc2-prod-gui-window-screen-new");
		// tab 1: "Smoke", include cpx#SM, exclude "skip", hidden from the main chat
		p4.press("Add tab");
		boolean saved1 = false;
		if (screen() instanceof TabScreen ts) {
			EditBox name = onClient(ts::nameBox);
			ctx.runOnClient(c -> name.setValue(""));
			p4.typeInto(name, "Smoke");
			p4.press("+ Include");
			p4.typeInto(onClient(() -> ts.rows().get(ts.rows().size() - 1).box()), "cpx#SM");
			p4.press("+ Exclude");
			p4.typeInto(onClient(() -> ts.rows().get(ts.rows().size() - 1).box()), "skip");
			p4.press("Hide from main chat");
			var hideToggle = p4.widget("Hide from main chat");
			if (hideToggle != null) p3.cursor(hideToggle.getX() + 10, hideToggle.getY() + 10);
			t.waitT(30); // tooltip delay
			ctx.takeScreenshot("rc2-prod-gui-tab-editor-hide-tooltip");
			p4.press("Save");
			saved1 = screen() instanceof WindowScreen;
		}
		// tab 2: "Everything", matchAll
		p4.press("Add tab");
		boolean saved2 = false;
		if (screen() instanceof TabScreen ts) {
			EditBox name = onClient(ts::nameBox);
			ctx.runOnClient(c -> name.setValue(""));
			p4.typeInto(name, "Everything");
			p4.press("All:");
			p4.press("Save");
			saved2 = screen() instanceof WindowScreen;
		}
		ctx.setScreen(() -> null);
		t.waitT(3);
		smoke = tabIdNamed("Smoke");
		all = tabIdNamed("Everything");
		var smokeCfg = onClient(() -> mgr().config().windowOfTab(smoke).tabs().stream().filter(x -> smoke.equals(x.id())).findFirst().get().config());
		m.put("keybindOpensSettings", keyOpens);
		m.put("window", windowId);
		m.put("tabs", row("smoke", smoke, "all", all));
		m.put("smokeConfig", row("include", smokeCfg.include().size(), "exclude", smokeCfg.exclude().size(), "hide", smokeCfg.hideFromMainChat()));
		m.put("savedToFile", onClient(() -> mgr().lastSave()));
		ev.result("RC1.createWindowsTabsFilters", keyOpens && windowId != null && saved1 && saved2 && smoke != null && all != null && smokeCfg.hideFromMainChat()
				&& smokeCfg.include().size() == 1 && smokeCfg.exclude().size() == 1, "observed", m);
	}

	/** RC.2: the GUI saves above maintained a last-good backup (the previous valid version, not the current one). */
	void backupGenerated() {
		byte[] active = Phase2Tests.bytes(Phase2Tests.cfg()), bak = Phase2Tests.bytes(Phase2Tests.bak());
		boolean valid = bak != null && io.github.itznotneptune.cpextended.config.ConfigParser.parse(bak).status() == io.github.itznotneptune.cpextended.config.ConfigParser.Status.OK;
		boolean previous = bak != null && !java.util.Arrays.equals(active, bak);
		var info = onClient(() -> mgr().storage().inspectBackup().state());
		ev.result("RC2.prod.backupGenerated", valid && previous && info == io.github.itznotneptune.cpextended.config.ConfigStorage.BackupState.VALID,
				"backupExists", bak != null, "backupValid", valid, "differsFromActive", previous, "lastBackupOutcome", onClient(() -> mgr().lastBackup()));
	}

	void routingAndHiding(TestSingleplayerContext sp) {
		t.sys(sp, "smoke one cpx#SM-1");
		t.sys(sp, "smoke two skip cpx#SM-2");
		t.sys(sp, "other one cpx#OT-1");
		t.waitT(6);
		MessageRecord s1 = rec("cpx#SM-1"), s2 = rec("cpx#SM-2"), o1 = rec("cpx#OT-1");
		boolean ok = s1 != null && s1.hidden() && !inMain("cpx#SM-1") && s1.tabIds().contains(smoke)
				&& s2 != null && !s2.hidden() && inMain("cpx#SM-2") && !s2.tabIds().contains(smoke)
				&& o1 != null && !o1.hidden() && o1.tabIds().contains(all) && !o1.tabIds().contains(smoke);
		ev.result("RC1.routingAndHiding", ok, "includeHidden", row("hidden", s1 != null && s1.hidden(), "inMainChat", inMain("cpx#SM-1")),
				"excludedStaysVisible", row("inMainChat", inMain("cpx#SM-2"), "inSmoke", s2 != null && s2.tabIds().contains(smoke)),
				"otherRoutedToEverything", o1 != null && o1.tabIds().contains(all));
		ctx.takeScreenshot("rc2-prod-hud-after-routing");
	}

	void unreadAndSelection(TestSingleplayerContext sp) {
		int before = unread(all);
		p3.openChat("");
		var lay = p3.layout(windowId);
		ChatOpenRenderer.TabSlot slot = lay == null ? null : lay.tabs().stream().filter(x -> all.equals(x.tabId())).findFirst().orElse(null);
		if (slot != null) p3.click(slot.x() + slot.w() / 2.0, slot.y() + slot.h() / 2.0, 0);
		t.waitT(3);
		String selected = onClient(() -> mgr().config().window(windowId).selectedTab());
		int after = unread(all);
		ctx.takeScreenshot("rc2-prod-chat-open-window");
		p3.closeChat();
		ev.result("RC1.unreadAndSelection", before >= 2 && all.equals(selected) && after == 0, "unreadBefore", before, "selectedAfterClick", selected, "unreadAfter", after);
	}

	void dragResizeRelocate(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		p3.openChat("");
		var l0 = p3.layout(windowId);
		int[] r0 = l0.rect().clone();
		// move: drag the strip background (right of the tabs)
		var lastTab = l0.tabs().get(l0.tabs().size() - 1);
		double sx = Math.min(l0.strip()[0] + l0.strip()[2] - 6, lastTab.x() + lastTab.w() + 8), sy = l0.strip()[1] + l0.strip()[3] / 2.0;
		p3.drag(sx, sy, sx + 60, sy + 30, 0);
		t.waitT(3);
		int[] r1 = p3.layout(windowId).rect().clone();
		// resize: drag the bottom-right grip
		int[] g = p3.layout(windowId).grip();
		p3.drag(g[0] + g[2] / 2.0, g[1] + g[3] / 2.0, g[0] + 40, g[1] + 25, 0);
		t.waitT(3);
		int[] r2 = p3.layout(windowId).rect().clone();
		// relocate: drag the "Everything" tab into empty space (a new window)
		var l2 = p3.layout(windowId);
		var slot = l2.tabs().stream().filter(x -> all.equals(x.tabId())).findFirst().orElse(null);
		int sw = onClient(() -> Minecraft.getInstance().getWindow().getGuiScaledWidth());
		if (slot != null) p3.drag(slot.x() + slot.w() / 2.0, slot.y() + slot.h() / 2.0, sw - 60, 40, 0);
		t.waitT(4);
		String newWindow = windowOfTab(all);
		p3.closeChat();
		t.waitT(30); // debounced geometry save
		m.put("moved", row("from", List.of(r0[0], r0[1]), "to", List.of(r1[0], r1[1])));
		m.put("resized", row("from", List.of(r1[2], r1[3]), "to", List.of(r2[2], r2[3])));
		m.put("tabMovedToNewWindow", row("window", newWindow, "historyKept", onClient(() -> SessionMirror.get().tab(all).size())));
		m.put("savedToFile", onClient(() -> mgr().dirty()) ? "pending" : "saved");
		boolean ok = (r1[0] != r0[0] || r1[1] != r0[1]) && r2[2] > r1[2] && r2[3] > r1[3] && newWindow != null && !newWindow.equals(windowId);
		ev.result("RC1.dragResizeRelocate", ok, "observed", m);
	}

	void search(TestSingleplayerContext sp) {
		String allWindow = windowOfTab(all);
		ctx.runOnClient(c -> mgr().edit(new io.github.itznotneptune.cpextended.config.ConfigEdit.SelectTab(allWindow, all)));
		t.sys(sp, "apple visible cpx#OT-2");
		t.sys(sp, "banana visible cpx#OT-3");
		t.waitT(4);
		String w = windowOfTab(all);
		p3.openChat("");
		EditBox f = onClient(() -> ChatPatchesAdapter.get().searchFieldWidget((ChatScreen) Minecraft.getInstance().screen));
		p3.click(f.getX() + 4, f.getY() + 4, 0);
		ctx.getInput().typeChars("apple");
		t.waitT(3);
		List<String> plain = onClient(() -> ChatOpenRenderer.viewOf(w).stream().map(r -> r.message().content().getString()).filter(x -> x.contains("cpx#")).toList());
		ctx.runOnClient(c -> { ChatPatches.config.regex = true; f.setValue(""); f.setValue("([bad"); });
		t.waitT(3);
		TabSearch.Indicator ind = onClient(() -> ChatOpenRenderer.indicatorOf(w));
		ctx.takeScreenshot("rc2-prod-invalid-regex");
		ctx.runOnClient(c -> { f.setValue(""); ChatPatches.config.regex = false; });
		p3.closeChat();
		boolean ok = plain.size() == 1 && plain.get(0).contains("cpx#OT-2") && ind == TabSearch.Indicator.INVALID;
		ev.result("RC1.searchAndInvalidRegex", ok, "plainView", plain, "invalidRegexIndicator", ind);
	}

	void hidingSwitch(TestSingleplayerContext sp) {
		p4.open(() -> new ConfigScreen(null));
		boolean off = p4.press("Hiding: On");
		ctx.setScreen(() -> null);
		t.sys(sp, "smoke while off cpx#SM-3");
		t.waitT(4);
		boolean visibleWhileOff = inMain("cpx#SM-3") && !rec("cpx#SM-3").hidden() && rec("cpx#SM-3").tabIds().contains(smoke);
		p4.open(() -> new ConfigScreen(null));
		boolean on = p4.press("Hiding: Off");
		ctx.setScreen(() -> null);
		t.sys(sp, "smoke while on cpx#SM-4");
		t.waitT(4);
		boolean hiddenWhileOn = !inMain("cpx#SM-4") && rec("cpx#SM-4").hidden();
		boolean earlierKept = onClient(() -> SessionMirror.get().tab(smoke).entries().stream().anyMatch(r -> r.original().getString().contains("cpx#SM-1") && r.hidden()));
		ev.result("RC1.hidingSwitch", off && on && visibleWhileOff && hiddenWhileOn && earlierKept, "pressedOff", off, "visibleWhileOff", visibleWhileOff,
				"pressedOn", on, "hiddenWhileOn", hiddenWhileOn, "earlierHiddenKeptInTab", earlierKept);
	}

	/** Usability capture: the settings screens with content, and the deletion disclosure (answered No). */
	void usabilityCaptures(TestSingleplayerContext sp) {
		smoke = tabIdNamed("Smoke");
		String everything = tabIdNamed("Everything");
		// a hidden message held only by Smoke (the catch-all tab stops matching everything), so deleting Smoke discloses it
		ctx.runOnClient(c -> mgr().edit(new io.github.itznotneptune.cpextended.config.ConfigEdit.SetTabFlag(everything, "matchAll", false)));
		t.sys(sp, "only in smoke cpx#SM-9");
		t.waitT(4);
		p4.open(() -> new ConfigScreen(null));
		List<String> listed = labels();
		ctx.takeScreenshot("rc2-prod-settings-populated");
		String w = windowOfTab(smoke);
		p4.open(() -> new WindowScreen(new ConfigScreen(null), w));
		String windowTitle = onClient(() -> Minecraft.getInstance().screen.getTitle().getString());
		ctx.takeScreenshot("rc2-prod-window-screen-populated");
		p4.press("Delete");
		String confirm = onClient(() -> Minecraft.getInstance().screen.getNarrationMessage().getString());
		ctx.takeScreenshot("rc2-prod-delete-confirm");
		p4.press("Yes");
		String disclosureText = onClient(() -> Minecraft.getInstance().screen.getNarrationMessage().getString());
		ctx.takeScreenshot("rc2-prod-orphan-disclosure");
		boolean disclosure = screen() instanceof net.minecraft.client.gui.screens.ConfirmScreen;
		p4.press("No");
		boolean kept = tabIdNamed("Smoke") != null;
		ctx.setScreen(() -> null);
		ev.data("usability_captures", "orphanDisclosureShown", disclosure, "tabKeptAfterNo", kept);
		boolean names = disclosure && kept && disclosureText.contains("\"Smoke\"") && !disclosureText.contains(smoke) && confirm.contains("Smoke");
		ev.result("RC2.prod.safetyDialogNames", names, "confirm", confirm, "disclosure", disclosureText, "tabKeptAfterNo", kept);
		// prod1 dragged "Everything" into a window of its own, so each window lists one tab
		boolean windowLabels = listed.stream().anyMatch(x -> x.matches("Window \\d+: Smoke")) && listed.stream().anyMatch(x -> x.matches("Window \\d+: Everything"))
				&& listed.stream().noneMatch(x -> x.contains("tab(s)") || x.contains("window-")) && windowTitle.matches("Window \\d+: Smoke");
		// the tab editor: case help and the Restored history label
		p4.open(() -> new TabScreen(new WindowScreen(new ConfigScreen(null), w), smoke));
		String caseTip = onClient(() -> { var b = Rc2Tests.widgetNow("aa"); return b == null ? null : Rc2Tests.tooltip(b); });
		boolean restoreFits = onClient(() -> {
			var b = Rc2Tests.widgetNow("Restored history");
			return b != null && Minecraft.getInstance().font.width(b.getMessage()) <= b.getWidth() - 4;
		});
		ctx.takeScreenshot("rc2-prod-tab-editor");
		ctx.setScreen(() -> null);
		ev.result("RC2.prod.guiLabels", windowLabels && caseTip != null && caseTip.contains("Case-insensitive") && restoreFits, "windowList", listed, "windowTitle", windowTitle,
				"caseTooltip", caseTip, "restoredHistoryLabelFits", restoreFits);
	}

	List<String> labels() {
		return onClient(() -> {
			List<String> out = new ArrayList<>();
			for (var ch : Minecraft.getInstance().screen.children()) if (ch instanceof net.minecraft.client.gui.components.AbstractWidget aw) out.add(aw.getMessage().getString());
			return out;
		});
	}

	void reconnect() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(60);
			int smokeEntries = onClient(() -> SessionMirror.get().tab(smoke).size());
			boolean boundary = onClient(() -> {
				var all0 = ((ChatComponentAccessor) Minecraft.getInstance().gui.getChat()).cpextended$getAllMessages();
				return !all0.isEmpty() && Boolean.TRUE.equals(ChatPatchesAdapter.get().isBoundaryLine(all0.get(0).content()));
			});
			t.sys(sp, "after reconnect cpx#SM-5");
			t.waitT(4);
			boolean routed = rec("cpx#SM-5") != null && rec("cpx#SM-5").hidden();
			ev.result("RC1.disconnectReconnect", smokeEntries >= 3 && boundary && routed, "smokeTabKeptEntries", smokeEntries, "boundaryLineNewest", boundary, "routingAfterReconnect", routed);
		}
		t.waitT(20);
	}

	// ================================================================ prod2: relaunch, restored history

	void prod2() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(120);
			String smokeId = tabIdNamed("Smoke");
			List<String> restored = onClient(() -> {
				List<String> out = new ArrayList<>();
				TabState s = SessionMirror.get().tab(smokeId);
				if (s != null) for (MessageRecord r : s.entries()) out.add((r.hidden() ? "hidden:" : "visible:") + r.message().content().getString().replaceAll(".*(cpx#\\S+).*", "$1"));
				return out;
			});
			boolean ok = restored.stream().anyMatch(x -> x.contains("cpx#SM-1")) && restored.stream().anyMatch(x -> x.startsWith("hidden:"));
			ev.result("RC1.restoredHistoryAfterRelaunch", ok, "smokeTabAfterRelaunch", restored,
					"note", "Chat Patches restored its log; recognized restored messages matching the hiding tab are routed and hidden again");
			usabilityCaptures(sp);
		}
		t.waitT(20);
	}

	// ================================================================ prod3: damaged settings file, recovery through the GUI

	void prod3() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("stateAtLaunch", String.valueOf(onClient(() -> mgr().state())));
		m.put("detail", onClient(() -> mgr().stateDetail()));
		m.put("corruptCopy", String.valueOf(onClient(() -> mgr().corruptCopy())));
		byte[] backup = Phase2Tests.bytes(Phase2Tests.bak());
		p4.open(() -> new ConfigScreen(null));
		boolean restoreOffered = p4.widget("Restore from backup") != null;
		boolean restoreActive = onClient(() -> { var b = Rc2Tests.widgetNow("Restore from backup"); return b != null && b.active; });
		String restoreTip = onClient(() -> { var b = Rc2Tests.widgetNow("Restore from backup"); return b == null ? null : Rc2Tests.tooltip(b); });
		boolean replaceOffered = p4.widget("Replace file") != null;
		ctx.runOnClient(c -> c.getToastManager().clear());
		t.waitT(2);
		ctx.takeScreenshot("rc2-prod-recovery-screen");
		boolean restored = false;
		String confirmText = null;
		if (restoreActive) {
			p4.press("Restore from backup");
			confirmText = onClient(() -> Minecraft.getInstance().screen.getNarrationMessage().getString());
			ctx.takeScreenshot("rc2-prod-restore-confirm");
			p4.press("Yes");
			restored = true;
		} else {
			p4.press("Replace file");
			p4.press("Yes");
		}
		ctx.setScreen(() -> null);
		m.put("restoreFromBackupActive", restoreActive);
		m.put("restoreTooltip", restoreTip);
		m.put("restoreConfirmation", confirmText);
		m.put("activeEqualsBackupAfterRestore", restored && java.util.Arrays.equals(backup, Phase2Tests.bytes(Phase2Tests.cfg())));
		m.put("restoreFromBackupOffered", restoreOffered);
		m.put("replaceFileOffered", replaceOffered);
		m.put("usedRestoreFromBackup", restored);
		m.put("stateAfter", String.valueOf(onClient(() -> mgr().state())));
		m.put("fileValidAfter", io.github.itznotneptune.cpextended.config.ConfigParser.parse(Phase2Tests.bytes(Phase2Tests.cfg())).status().toString());
		boolean ok = "NO_SAVE".equals(m.get("stateAtLaunch")) && (restoreOffered || replaceOffered) && "NORMAL".equals(m.get("stateAfter")) && "OK".equals(m.get("fileValidAfter"));
		ev.result("RC1.configRecovery", ok, "observed", m);
		ev.result("RC2.prod.recoveryFromBackup", ok && restored && Boolean.TRUE.equals(m.get("activeEqualsBackupAfterRestore")), "observed", m,
				"note", "the backup was made by the game's own saves in prod1/prod2 (no file seeded); RC.1 had no backup and could only Replace file");
	}
}
