// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.config.OrphanGuard;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import io.github.itznotneptune.cpextended.gui.Keybinds;
import io.github.itznotneptune.cpextended.gui.TabScreen;
import io.github.itznotneptune.cpextended.gui.WindowScreen;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.render.WindowRenderer;
import io.github.itznotneptune.cpextended.ui.ChatScreenInput;
import io.github.itznotneptune.cpextended.ui.HitTester;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.ChatVisiblity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Phase 4 acceptance tests: configuration GUI (Q6, P8, Q14, Q28, P14), keybind and Mod Menu (P15), tab relocation (Q21-Q33). */
final class Phase4Tests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase3Tests p3;

	Phase4Tests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p3 = new Phase3Tests(t);
	}

	// =================================================================== helpers

	ConfigManager mgr() { return ConfigManager.get(); }

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	Screen screen() { return onClient(() -> Minecraft.getInstance().screen); }

	void restart(String config) {
		Phase2Tests.write(Phase2Tests.cfg(), config);
		ctx.runOnClient(c -> mgr().devSimulateRestart());
		t.waitT(2);
	}

	void open(java.util.function.Supplier<Screen> s) {
		ctx.setScreen(s::get); // screens are built on the client thread
		t.waitT(3);
	}

	void open(Screen s) {
		ctx.setScreen(() -> s);
		t.waitT(3);
	}

	/** First widget on the current screen whose label contains {@code text}. */
	AbstractWidget widget(String text) {
		return onClient(() -> {
			Screen s = Minecraft.getInstance().screen;
			if (s == null) return null;
			for (var ch : s.children()) if (ch instanceof AbstractWidget w && w.getMessage().getString().contains(text)) return w;
			return null;
		});
	}

	/** Clicks a widget with the real mouse. Returns false if it isn't there. */
	boolean press(String text) {
		AbstractWidget w = widget(text);
		if (w == null) return false;
		p3.click(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
		t.waitT(2);
		return true;
	}

	void typeInto(EditBox box, String text) {
		p3.click(box.getX() + 4, box.getY() + box.getHeight() / 2.0, 0);
		ctx.getInput().pressKey(InputConstants.KEY_END);
		ctx.getInput().typeChars(text);
		t.waitT(2);
	}

	static JsonObject tab(JsonObject root, String tabId) { return Phase2Tests.tabOf(root, null, tabId); }

	int unread(String tabId) {
		return onClient(() -> { TabState s = SessionMirror.get().tab(tabId); return s == null ? 0 : s.unreadCount(); });
	}

	int entries(String tabId) {
		return onClient(() -> { TabState s = SessionMirror.get().tab(tabId); return s == null ? 0 : s.size(); });
	}

	String windowOf(String tabId) {
		return onClient(() -> { var w = mgr().config().windowOfTab(tabId); return w == null ? null : w.id(); });
	}

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	static String filter(String mode, String pattern) { return Phase2Tests.filterJson(mode, pattern); }

	// =================================================================== gui

	static String guiConfig() {
		return "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
				+ "\"appearance\": {\"backgroundOpacity\": null, \"textOpacity\": null, \"scale\": null, \"lineSpacing\": null},\n"
				+ "\"windows\": [\n"
				+ " {\"id\": \"g-a\", \"x\": 0.55, \"y\": 0.05, \"width\": 0.40, \"height\": 0.3, \"selectedTab\": \"ga1\", \"tabs\": [\n"
				+ "   {\"id\": \"ga1\", \"name\": \"Alpha\", \"include\": [" + filter("literal", "cpx#GA") + ", {\"mode\": \"regex\", \"pattern\": \"(a|b\", \"note\": \"keep me\"}, " + filter("literal", "") + "]},\n"
				+ "   {\"id\": \"glong\", \"name\": \"Long\", \"include\": [" + filter("literal", "x".repeat(1500)) + "]},\n"
				+ "   {\"id\": \"gsect\", \"name\": \"Codes\", \"include\": [" + filter("literal", "§a") + ", " + filter("literal", "x\uD83Dy") + "]},\n"
				+ "   {\"id\": \"gmov\", \"name\": \"Mover\", \"include\": [" + filter("literal", "cpx#GM") + "]}\n"
				+ " ]},\n"
				+ " {\"id\": \"g-b\", \"x\": 0.55, \"y\": 0.45, \"width\": 0.40, \"height\": 0.3, \"tabs\": [{\"id\": \"gb1\", \"name\": \"Bravo\", \"include\": [" + filter("literal", "cpx#GB") + "]}]},\n"
				+ " {\"id\": \"g-empty\", \"x\": 0.05, \"y\": 0.05, \"width\": 0.3, \"height\": 0.2, \"tabs\": []},\n"
				+ " {\"id\": \"g-off\", \"enabled\": false, \"tabs\": [{\"id\": \"goff1\", \"include\": [" + filter("literal", "cpx#GOFF") + "]}]}\n"
				+ "]\n}\n";
	}

	void gui() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			restart(guiConfig());
			ctx.runOnClient(c -> { c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.FULL); });
			screenOpenCheck();
			windowAndTabCreation(sp);
			v21(sp);
			v21dGui();
			v25dGui();
			v25eGui();
			v25fGui();
			v41(sp);
			orphanSafety();
			recovery();
			ev.result("V-14.modMenuAbsent", !FabricLoader.getInstance().isModLoaded("modmenu") && !classPresent("com.terraformersmc.modmenu.api.ModMenuApi"),
					"note", "no Mod Menu on this run; the mod works and nothing loads Mod Menu classes (its entrypoint is only read by Mod Menu)");
		}
		t.waitT(20);
	}

	static boolean classPresent(String name) {
		try {
			Class.forName(name, false, Phase4Tests.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	void screenOpenCheck() {
		JsonObject j = Phase2Tests.json(Phase2Tests.cfg());
		tab(j, "gb1").addProperty("name", "Bravo (hand edited)");
		Phase2Tests.writeJson(Phase2Tests.cfg(), j);
		byte[] before = Phase2Tests.bytes(Phase2Tests.cfg());
		ConfigScreen cs = onClient(() -> new ConfigScreen(null));
		open(cs);
		String result = onClient(cs::checkResult);
		boolean applied = onClient(() -> "Bravo (hand edited)".equals(mgr().config().windowOfTab("gb1").tabs().get(0).config().name()));
		boolean listed = widget("g-a") != null && widget("g-b") != null && widget("g-empty") != null;
		ev.result("V-21b.screenOpen", "APPLIED_EXTERNAL_EDIT".equals(result) && applied && Arrays.equals(before, Phase2Tests.bytes(Phase2Tests.cfg())) && listed,
				"checkOnOpen", result, "appliedInMemory", applied, "fileNotRewritten", Arrays.equals(before, Phase2Tests.bytes(Phase2Tests.cfg())), "windowsListed", listed);
		ctx.setScreen(() -> null);
	}

	void windowAndTabCreation(TestSingleplayerContext sp) {
		open(() -> new ConfigScreen(null));
		boolean addWindow = press("Add window");
		Screen ws = screen();
		boolean onWindowScreen = ws instanceof WindowScreen;
		String newWindow = onWindowScreen ? ((WindowScreen) ws).windowId() : null;
		boolean addTab = press("Add tab");
		Screen ts = screen();
		boolean onTabScreen = ts instanceof TabScreen;
		boolean saved = false;
		if (onTabScreen) {
			TabScreen tsc = (TabScreen) ts;
			EditBox name = onClient(tsc::nameBox);
			ctx.runOnClient(c -> name.setValue(""));
			typeInto(name, "Created");
			press("+ Include");
			TabScreen.FilterRow r = onClient(() -> tsc.rows().get(tsc.rows().size() - 1));
			typeInto(onClient(r::box), "cpx#NEWTAB");
			press("Save");
			saved = screen() instanceof WindowScreen;
		}
		t.sys(sp, "for the new tab cpx#NEWTAB-1");
		t.waitT(6);
		String createdId = onClient(() -> {
			var w = mgr().config().window(newWindow);
			return w == null || w.tabs().isEmpty() ? null : w.tabs().get(0).id();
		});
		boolean routed = createdId != null && t.tabEntries(createdId).size() == 1;
		ev.result("P4.createWindowAndTab", addWindow && onWindowScreen && addTab && onTabScreen && saved && routed,
				"newWindow", newWindow, "newTab", createdId, "routedAfterSave", routed);
		ctx.setScreen(() -> null);
	}

	TabScreen openTab(String tabId) {
		TabScreen ts = onClient(() -> new TabScreen(new ConfigScreen(null), tabId));
		open(ts);
		return ts;
	}

	void v21(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		TabScreen ts = openTab("gb1");
		// empty pattern -> inline error, Save blocked
		press("+ Include");
		TabScreen.FilterRow empty = onClient(() -> ts.rows().get(ts.rows().size() - 1));
		boolean blocked = !onClient(ts::save);
		m.put("emptyBlocked", blocked);
		m.put("emptyError", onClient(empty::error));
		// typing stops at 1,024 units
		EditBox box = onClient(empty::box);
		typeInto(box, "q".repeat(1030));
		int typed = onClient(() -> box.getValue().length());
		m.put("typedLength", typed);
		// pasting 515 emoji (1,030 units) truncates without splitting a pair (vanilla's paste path is insertText)
		ctx.runOnClient(c -> { box.setValue(""); box.insertText("😀".repeat(515)); });
		String pasted = onClient(box::getValue);
		boolean noSplit = !pasted.isEmpty() && !Character.isHighSurrogate(pasted.charAt(pasted.length() - 1));
		m.put("pastedLength", pasted.length());
		m.put("pasteKeepsPairs", noSplit);
		// a valid pattern saves; it applies to future messages only (Q9)
		t.sys(sp, "before the new filter cpx#Q9-early");
		t.waitT(4);
		ctx.runOnClient(c -> box.setValue("cpx#Q9"));
		boolean saved = onClient(ts::save);
		t.sys(sp, "after the new filter cpx#Q9-late");
		t.waitT(6);
		long early = t.tabEntries("gb1").stream().filter(r -> r.original() != null && r.original().getString().contains("cpx#Q9-early")).count();
		long late = t.tabEntries("gb1").stream().filter(r -> r.original() != null && r.original().getString().contains("cpx#Q9-late")).count();
		m.put("saved", saved);
		m.put("futureOnly", early == 0 && late == 1);
		ev.result("V-21", blocked && typed == 1024 && pasted.length() <= 1024 && noSplit && saved && early == 0 && late == 1, "observed", m,
				"note", "paste exercised through EditBox.insertText, the method vanilla's paste shortcut calls");
		ctx.setScreen(() -> null);
	}

	void v21dGui() {
		Map<String, Object> m = new LinkedHashMap<>();
		TabScreen ts = openTab("ga1");
		// rows: [0] literal cpx#GA, [1] regex (a|b + note, [2] empty literal (invalid)
		TabScreen.FilterRow regex = onClient(() -> ts.rows().get(1));
		TabScreen.FilterRow emptyStored = onClient(() -> ts.rows().get(2));
		m.put("regexShownReadOnly", onClient(() -> !regex.editable() && regex.box() == null));
		// edit the stored invalid (empty) filter but leave it invalid -> blocked
		ctx.runOnClient(c -> ts.toggleCase(emptyStored)); // a modification that leaves the filter invalid (still empty)
		boolean blockedInvalidEdit = !onClient(ts::save);
		m.put("editStillInvalidBlocked", blockedInvalidEdit);
		ctx.setScreen(() -> null);
		// unrelated change only (rename) -> saves; untouched invalid filters written back unchanged
		TabScreen ts2 = openTab("ga1");
		EditBox name = onClient(ts2::nameBox);
		typeInto(name, "!");
		boolean savedUnrelated = onClient(ts2::save);
		JsonObject f = Phase2Tests.json(Phase2Tests.cfg());
		JsonObject regexStored = tab(f, "ga1").getAsJsonArray("include").get(1).getAsJsonObject();
		boolean preserved = regexStored.equals(JsonParser.parseString("{\"mode\": \"regex\", \"pattern\": \"(a|b\", \"note\": \"keep me\"}").getAsJsonObject())
				&& tab(f, "ga1").getAsJsonArray("include").get(2).getAsJsonObject().get("pattern").getAsString().isEmpty();
		m.put("unrelatedSaved", savedUnrelated);
		m.put("untouchedInvalidPreserved", preserved);
		boolean stillSuspended = onClient(() -> io.github.itznotneptune.cpextended.core.Router.get().config().configErrorSummary().containsKey("ga1"));
		m.put("tabStillSuspended", stillSuspended);
		// a new empty filter is blocked
		TabScreen ts3 = openTab("ga1");
		onClient(() -> ts3.addFilter("exclude"));
		boolean newEmptyBlocked = !onClient(ts3::save);
		m.put("newEmptyBlocked", newEmptyBlocked);
		ctx.setScreen(() -> null);
		// deleting the regex filter and fixing the empty one saves and unsuspends the tab
		TabScreen ts4 = openTab("ga1");
		TabScreen.FilterRow r1 = onClient(() -> ts4.rows().get(1));
		TabScreen.FilterRow r2 = onClient(() -> ts4.rows().get(2));
		ctx.runOnClient(c -> ts4.delete(r1));
		EditBox fixBox = onClient(r2::box);
		if (fixBox != null) ctx.runOnClient(c -> fixBox.setValue("fixed"));
		boolean fixed = onClient(ts4::save);
		boolean unsuspended = !onClient(() -> io.github.itznotneptune.cpextended.core.Router.get().config().configErrorSummary().containsKey("ga1"));
		m.put("deleteAndFixSaved", fixed);
		m.put("unsuspended", unsuspended);
		ev.result("V-21d.gui", Boolean.TRUE.equals(m.get("regexShownReadOnly")) && blockedInvalidEdit && savedUnrelated && preserved && stillSuspended
				&& newEmptyBlocked && fixed && unsuspended, "observed", m);
		ctx.setScreen(() -> null);
	}

	void v25dGui() {
		String emoji = "😀";
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("bmp1024", "a".repeat(1024));
		cases.put("bmp1023emoji", "a".repeat(1023) + emoji);
		cases.put("bmp1022emoji", "a".repeat(1022) + emoji);
		cases.put("combining1024", "é".repeat(512));
		cases.put("supplementary1024", "𝐀".repeat(512));
		cases.put("supplementary1026", "𝐀".repeat(513));
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		for (var e : cases.entrySet()) {
			TabScreen ts = openTab("gb1");
			TabScreen.FilterRow r = onClient(() -> ts.addFilter("exclude"));
			EditBox box = onClient(r::box);
			ctx.runOnClient(c -> { box.setValue(""); box.insertText(e.getValue()); });
			String entered = onClient(box::getValue);
			boolean saved = onClient(ts::save);
			boolean fileValid = e.getValue().length() <= 1024;
			// the field never holds more than 1,024 units: over-limit input arrives truncated at a pair boundary, which is then valid
			boolean rowOk = entered.length() <= 1024 && saved && (fileValid ? entered.equals(e.getValue()) : entered.length() < e.getValue().length());
			ok &= rowOk;
			rows.add(row("case", e.getKey(), "units", e.getValue().length(), "fileVerdictValid", fileValid, "fieldAccepted", entered.length(),
					"savedFromGui", saved, "ok", rowOk));
			ctx.setScreen(() -> null);
			// remove the added exclude again
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.DeleteFilter("gb1", "exclude", 0)));
		}
		ev.result("V-25d.gui", ok, "rows", rows, "note", "valid exactly when String.length() <= 1024 in both; the GUI field can't hold an over-limit value (vanilla truncation keeps pairs)");
	}

	void v25eGui() {
		Map<String, Object> m = new LinkedHashMap<>();
		TabScreen ts = openTab("glong");
		TabScreen.FilterRow r = onClient(() -> ts.rows().get(0));
		m.put("shownReadOnly", onClient(() -> !r.editable() && r.box() == null));
		m.put("replaceOffered", widget("Replace") != null);
		// (a) unrelated change, save: the stored 1,500 units survive
		EditBox name = onClient(ts::nameBox);
		typeInto(name, "+");
		boolean a = onClient(ts::save);
		String stored = Phase2Tests.filterOf(Phase2Tests.json(Phase2Tests.cfg()), "glong", "include", 0).get("pattern").getAsString();
		m.put("unrelatedSaveKeeps1500", a && stored.equals("x".repeat(1500)));
		// (c) Replace, leave empty -> Save blocked with an inline error
		TabScreen ts2 = openTab("glong");
		press("Replace");
		TabScreen.FilterRow r2 = onClient(() -> ts2.rows().get(0));
		boolean c = !onClient(ts2::save) && onClient(r2::error) != null;
		m.put("replaceEmptyBlocked", c);
		// (b) Replace with a valid pattern -> saves, tab unsuspends
		EditBox rb = onClient(r2::box);
		typeInto(rb, "cpx#LONGFIXED");
		boolean b = onClient(ts2::save);
		boolean unsuspended = !onClient(() -> io.github.itznotneptune.cpextended.core.Router.get().config().configErrorSummary().containsKey("glong"));
		m.put("replaceValidSaved", b && unsuspended);
		ev.result("V-25e", Boolean.TRUE.equals(m.get("shownReadOnly")) && Boolean.TRUE.equals(m.get("replaceOffered")) && Boolean.TRUE.equals(m.get("unrelatedSaveKeeps1500"))
				&& c && b && unsuspended, "observed", m, "note", "an over-length replacement can't be entered (the field stops at 1,024 units)");
		ctx.setScreen(() -> null);
	}

	void v25fGui() {
		Map<String, Object> m = new LinkedHashMap<>();
		TabScreen ts = openTab("gsect");
		TabScreen.FilterRow sect = onClient(() -> ts.rows().get(0));
		TabScreen.FilterRow lone = onClient(() -> ts.rows().get(1));
		m.put("sectShownExactly", onClient(() -> "§a".equals(sect.box().getValue())));
		m.put("loneShownExactly", onClient(() -> "x\uD83Dy".equals(lone.box().getValue())));
		typeInto(onClient(lone::box), "!");          // edit another part of the same pattern
		ctx.getInput().typeChars("§");          // vanilla filters a typed section sign (documented limitation)
		t.waitT(2);
		String afterTyping = onClient(() -> lone.box().getValue());
		boolean saved = onClient(ts::save);
		JsonObject f = Phase2Tests.json(Phase2Tests.cfg());
		String s0 = Phase2Tests.filterOf(f, "gsect", "include", 0).get("pattern").getAsString();
		String s1 = Phase2Tests.filterOf(f, "gsect", "include", 1).get("pattern").getAsString();
		m.put("valueAfterTyping", AcceptanceTest.row("units", afterTyping.length(), "endsWithBang", afterTyping.endsWith("!")));
		m.put("storedSect", s0.equals("§a"));
		m.put("storedLonePlusEdit", s1.equals("x\uD83Dy!"));
		ev.result("V-25f.gui", Boolean.TRUE.equals(m.get("sectShownExactly")) && Boolean.TRUE.equals(m.get("loneShownExactly")) && saved
				&& s0.equals("§a") && s1.equals("x\uD83Dy!") && afterTyping.equals("x\uD83Dy!"), "observed", m,
				"note", "existing section signs and lone surrogates survive display and an edit elsewhere in the pattern; typing a section sign is filtered by vanilla");
		ctx.setScreen(() -> null);
	}

	void v41(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		// gmov is unselected in g-a: its deliveries are unread
		t.sys(sp, "for mover cpx#GM-1");
		t.waitT(4);
		int u0 = unread("gmov");
		int e0 = entries("gmov");
		WindowScreen ws = onClient(() -> new WindowScreen(new ConfigScreen(null), "g-a"));
		open(ws);
		// (1) into a populated visible window: stays unselected, unread kept
		onClient(() -> ws.moveTab("gmov", "g-b"));
		t.waitT(3);
		boolean one = "g-b".equals(windowOf("gmov")) && unread("gmov") == u0 && !"gmov".equals(onClient(() -> mgr().config().window("g-b").selectedTab()))
				&& entries("gmov") == e0;
		m.put("1.populated", AcceptanceTest.row("unread", unread("gmov"), "entries", entries("gmov"), "ok", one));
		// (2) into an empty visible window: auto-selected, unread cleared
		onClient(() -> ws.moveTab("gmov", "g-empty"));
		t.waitT(3);
		boolean two = "g-empty".equals(windowOf("gmov")) && unread("gmov") == 0 && "gmov".equals(onClient(() -> mgr().config().window("g-empty").selectedTab()));
		m.put("2.emptyVisible", AcceptanceTest.row("unread", unread("gmov"), "ok", two));
		// back into g-b to collect unread again (g-empty stays, now empty: Q30e), then (3) into a disabled window: unread kept
		onClient(() -> ws.moveTab("gmov", "g-b"));
		m.put("Q30e.emptiedWindowKept", onClient(() -> mgr().config().window("g-empty") != null && mgr().config().window("g-empty").tabs().isEmpty()));
		t.sys(sp, "for mover cpx#GM-2");
		t.waitT(4);
		int u3 = unread("gmov");
		onClient(() -> ws.moveTab("gmov", "g-off"));
		t.waitT(3);
		boolean three = "g-off".equals(windowOf("gmov")) && u3 > 0 && unread("gmov") == u3;
		m.put("3.disabled", AcceptanceTest.row("unread", unread("gmov"), "ok", three));
		// (4) into an empty window while F1 is on and chat is closed (config GUI): hidden, kept; cleared once visible
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.AddWindow("g-empty2", 0.05, 0.4, 0.3, 0.2)));
		ctx.runOnClient(c -> c.options.hideGui = true);
		WindowScreen ws2 = onClient(() -> new WindowScreen(new ConfigScreen(null), "g-off"));
		open(ws2);
		onClient(() -> ws2.moveTab("gmov", "g-empty2"));
		t.waitT(3);
		int u4 = unread("gmov");
		ctx.setScreen(() -> null);
		ctx.runOnClient(c -> c.options.hideGui = false);
		t.waitT(3);
		int u4after = unread("gmov");
		boolean four = u4 == u3 && u4after == 0;
		m.put("4.emptyUnderF1", AcceptanceTest.row("unreadAfterMove", u4, "unreadOnceVisible", u4after, "ok", four));
		ev.result("V-41", one && two && three && four && Boolean.TRUE.equals(m.get("Q30e.emptiedWindowKept")), "observed", m, "note", "moves made in the config screen; history and unread travel with the tab id");
	}

	void orphanSafety() {
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden("gb1"));
		WindowScreen ws = onClient(() -> new WindowScreen(new ConfigScreen(null), "g-b"));
		open(ws);
		boolean pressed = press("Delete");
		boolean confirmShown = screen() instanceof net.minecraft.client.gui.screens.ConfirmScreen;
		press("Yes");
		boolean tabKept = "g-b".equals(windowOf("gb1"));
		String status = onClient(() -> ((WindowScreen) Minecraft.getInstance().screen).status().getString());
		// deleting the whole window is refused too
		press("Delete window");
		press("Yes");
		boolean windowKept = onClient(() -> mgr().config().window("g-b") != null);
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden(null));
		// "No" in a confirmation changes nothing; an empty window can be deleted
		WindowScreen we = onClient(() -> new WindowScreen(new ConfigScreen(null), "g-empty"));
		open(we);
		press("Delete window");
		press("No");
		boolean keptAfterNo = onClient(() -> mgr().config().window("g-empty") != null);
		press("Delete window");
		press("Yes");
		boolean deleted = onClient(() -> mgr().config().window("g-empty") == null);
		ev.result("P4.confirmationsNeverBypassOrphanSafety", pressed && confirmShown && tabKept && status.contains("orphan") && windowKept && keptAfterNo && deleted,
				"confirmDialogShown", confirmShown, "tabKeptAfterYes", tabKept, "status", status, "windowDeleteRefused", windowKept,
				"noChangesNothing", keptAfterNo, "emptyWindowDeleted", deleted, "note", "hidden messages simulated with the development hook (hiding is off)");
		ctx.setScreen(() -> null);
	}

	void recovery() {
		Map<String, Object> m = new LinkedHashMap<>();
		// normal: no recovery actions shown
		open(() -> new ConfigScreen(null));
		m.put("normalNoActions", widget("Apply file") == null && widget("Restore from backup") == null);
		ctx.setScreen(() -> null);
		// divergence: Apply refused while the file is still broken, Overwrite (confirmed) works
		Phase2Tests.write(Phase2Tests.cfg(), "{ broken");
		open(() -> new ConfigScreen(null));
		m.put("divergenceShown", onClient(() -> mgr().state()) == ConfigManager.State.DIVERGENCE && widget("Apply file") != null);
		press("Apply file");
		press("Yes");
		m.put("applyRefusedStillLocked", onClient(() -> mgr().state()) == ConfigManager.State.DIVERGENCE);
		m.put("applyStatus", onClient(() -> ((ConfigScreen) Minecraft.getInstance().screen).status().getString()));
		press("Overwrite file");
		press("No");
		m.put("overwriteNoKeepsLock", onClient(() -> mgr().state()) == ConfigManager.State.DIVERGENCE);
		press("Overwrite file");
		press("Yes");
		m.put("overwriteYesCleared", onClient(() -> mgr().state()) == ConfigManager.State.NORMAL);
		ctx.setScreen(() -> null);
		// divergence caused by an orphaning hand edit: Apply refused (orphan rule), never approximated
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden("gb1"));
		JsonObject j = Phase2Tests.json(Phase2Tests.cfg());
		for (var w : j.getAsJsonArray("windows")) if (w.getAsJsonObject().get("id").getAsString().equals("g-b")) w.getAsJsonObject().add("tabs", new com.google.gson.JsonArray());
		Phase2Tests.writeJson(Phase2Tests.cfg(), j);
		open(() -> new ConfigScreen(null));
		press("Apply file");
		press("Yes");
		m.put("orphanApplyRefused", onClient(() -> mgr().state()) == ConfigManager.State.DIVERGENCE && "g-b".equals(windowOf("gb1")));
		press("Overwrite file");
		press("Yes");
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden(null));
		ctx.setScreen(() -> null);
		// no-save: Restore from backup and Replace file, each confirmed
		byte[] good = Phase2Tests.bytes(Phase2Tests.cfg());
		Phase2Tests.write(Phase2Tests.bak(), new String(good, StandardCharsets.UTF_8));
		Phase2Tests.write(Phase2Tests.cfg(), "[1, 2");
		ctx.runOnClient(c -> mgr().devSimulateRestart());
		open(() -> new ConfigScreen(null));
		m.put("noSaveShown", onClient(() -> mgr().state()) == ConfigManager.State.NO_SAVE && widget("Restore from backup") != null);
		press("Restore from backup");
		press("Yes");
		m.put("restoreWorked", onClient(() -> mgr().state()) == ConfigManager.State.NORMAL && Arrays.equals(good, Phase2Tests.bytes(Phase2Tests.cfg())));
		ctx.setScreen(() -> null);
		Phase2Tests.write(Phase2Tests.cfg(), "[1, 2");
		ctx.runOnClient(c -> mgr().devSimulateRestart());
		open(() -> new ConfigScreen(null));
		press("Replace file");
		press("Yes");
		m.put("replaceWorked", onClient(() -> mgr().state()) == ConfigManager.State.NORMAL);
		ctx.setScreen(() -> null);
		boolean ok = m.values().stream().filter(v -> v instanceof Boolean).allMatch(Boolean.TRUE::equals);
		ev.result("V-60.gui", ok, "observed", m, "note", "banner and actions in normal, divergence and no-save states, all behind confirmation dialogs; refusals change nothing");
	}

	// =================================================================== drag

	static String dragConfig() {
		StringBuilder many = new StringBuilder();
		for (int i = 1; i <= 12; i++) {
			if (i > 1) many.append(",");
			String mode = i == 9 ? "regex" : "literal";
			many.append("{\"id\": \"da").append(i).append("\", \"name\": \"Tab number ").append(i).append("\", \"include\": [")
					.append(filter(mode, "cpx#DA" + i + "-")).append("]}");
		}
		return "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
				+ "\"appearance\": {\"backgroundOpacity\": 1.0, \"textOpacity\": 1.0, \"scale\": 1.0, \"lineSpacing\": 0.0},\n"
				+ "\"windows\": [\n"
				+ " {\"id\": \"d-off\", \"enabled\": false, \"anchor\": \"TOP_LEFT\", \"x\": 0.40, \"y\": 0.02, \"width\": 0.12, \"height\": 0.15, \"tabs\": []},\n"
				+ " {\"id\": \"d-chat\", \"anchor\": \"BOTTOM_LEFT\", \"x\": 0.0, \"y\": 0.0, \"width\": 0.5, \"height\": 0.5, \"tabs\": [{\"id\": \"dch1\", \"include\": [" + filter("literal", "cpx#DCH") + "]}]},\n"
				+ " {\"id\": \"d-b\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.45, \"width\": 0.42, \"height\": 0.25, \"tabs\": [{\"id\": \"db1\", \"name\": \"Bee\", \"include\": [" + filter("literal", "cpx#DB") + "]}, {\"id\": \"db2\", \"name\": \"Bee2\", \"include\": [" + filter("literal", "cpx#DQ") + "]}]},\n"
				+ " {\"id\": \"d-cover\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.40, \"width\": 0.14, \"height\": 0.20, \"tabs\": [{\"id\": \"dcv1\", \"name\": \"C\", \"include\": [" + filter("literal", "cpx#DCV") + "]}]},\n"
				+ " {\"id\": \"d-a\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.04, \"width\": 0.42, \"height\": 0.30, \"selectedTab\": \"da1\", \"tabs\": [" + many + "]}\n"
				+ "]\n}\n";
	}

	/** Scrolls a window's tab strip (with its arrows) until the tab is visible. */
	void revealTab(String windowId, String tabId) {
		ChatOpenRenderer.Layout l = p3.layout(windowId);
		if (l == null || l.tabs().stream().anyMatch(s -> s.tabId().equals(tabId))) return;
		int index = indexOfTab(windowId, tabId);
		ctx.runOnClient(c -> ChatOpenRenderer.scrollTabs(windowId, index - l.firstTab()));
		t.waitT(2);
	}

	int indexOfTab(String windowId, String tabId) {
		return onClient(() -> {
			var w = mgr().config().window(windowId);
			for (int i = 0; i < w.tabs().size(); i++) if (tabId.equals(w.tabs().get(i).id())) return i;
			return -1;
		});
	}

	/** Drags a tab slot to a GUI point with the real mouse, crossing {@code via} first (capture check). */
	ChatScreenInput.Drop dragTab(String windowId, String tabId, double tx, double ty, double[] via) {
		revealTab(windowId, tabId);
		ChatOpenRenderer.Layout l = p3.layout(windowId);
		ChatOpenRenderer.TabSlot slot = l == null ? null : l.tabs().stream().filter(s -> s.tabId().equals(tabId)).findFirst().orElse(null);
		if (slot == null) return null;
		ctx.runOnClient(c -> ChatScreenInput.resetLastDrop());
		p3.cursor(slot.x() + 3, slot.y() + 5);
		ctx.getInput().holdMouse(0);
		t.waitT(2);
		p3.cursor(slot.x() + 15, slot.y() + 15);
		t.waitT(2);
		if (via != null) { p3.cursor(via[0], via[1]); t.waitT(2); }
		p3.cursor(tx, ty);
		t.waitT(2);
		ctx.getInput().releaseMouse(0);
		t.waitT(3);
		return onClient(ChatScreenInput::lastDrop);
	}

	Set<String> allTabIds() {
		return onClient(() -> {
			Set<String> ids = new HashSet<>();
			List<String> list = new ArrayList<>();
			for (var w : mgr().config().windows()) for (var tb : w.tabs()) if (tb.id() != null) list.add(tb.id());
			ids.addAll(list);
			return list.size() == ids.size() ? ids : null; // null = a duplicated tab
		});
	}

	void drag() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			restart(dragConfig());
			ctx.runOnClient(c -> {
				c.options.hideGui = false;
				c.options.chatVisibility().set(ChatVisiblity.FULL);
				c.options.chatWidth().set(0.4);
				c.gui.getChat().rescaleChat();
			});
			for (int i = 1; i <= 12; i++) t.sys(sp, "tab " + i + " message cpx#DA" + i + "-1");
			for (int i = 0; i < 3; i++) t.sys(sp, "bee message cpx#DB-" + i);
			for (int i = 0; i < 2; i++) t.sys(sp, "bee two message cpx#DQ-" + i);
			t.sys(sp, "chat window cpx#DCH-1");
			for (int i = 0; i < 25; i++) t.sys(sp, "filler " + i);
			t.waitT(8);
			Set<String> before = allTabIds();
			overflow();
			drops(sp);
			Set<String> after = allTabIds();
			ev.result("P4.noLostOrDuplicatedTabs", before != null && before.equals(after), "tabsBefore", before == null ? null : before.size(), "tabsAfter", after == null ? "duplicated" : after.size());
		}
		t.waitT(20);
	}

	void overflow() {
		p3.openChat("");
		ChatOpenRenderer.Layout a = p3.layout("d-a");
		Map<String, Object> m = new LinkedHashMap<>();
		boolean overflows = a.allTabs() == 12 && a.tabs().size() < 12 && a.rightArrow() != null;
		m.put("visibleBefore", a.tabs().size());
		m.put("rightArrowColor", Integer.toHexString(a.rightArrowColor()));
		// right arrow until the end: every tab is shown at some point
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 14; i++) {
			ChatOpenRenderer.Layout l = p3.layout("d-a");
			l.tabs().forEach(s -> seen.add(s.tabId()));
			if (l.firstTab() + l.tabs().size() >= l.allTabs()) break;
			p3.click(l.rightArrow()[0] + 3, l.rightArrow()[1] + 5, 0);
		}
		m.put("reachableViaArrows", seen.size());
		ChatOpenRenderer.Layout end = p3.layout("d-a");
		m.put("leftArrowColorAtEnd", Integer.toHexString(end.leftArrowColor()));
		// wheel over the strip scrolls back
		int f0 = end.firstTab();
		p3.scrollAt(end.strip()[0] + end.strip()[2] / 2.0, end.strip()[1] + 5, 1);
		int f1 = p3.layout("d-a").firstTab();
		m.put("wheelScrollsStrip", f1 < f0);
		// selecting a hidden tab (from the config screen) brings it into view
		p3.closeChat();
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("d-a", "da12")));
		p3.openChat("");
		boolean revealed = p3.layout("d-a").tabs().stream().anyMatch(s -> s.tabId().equals("da12"));
		m.put("selectedRevealed", revealed);
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.SelectTab("d-a", "da1")));
		p3.closeChat();
		p3.openChat("");
		// unread and error indicators for tabs scrolled out of view: da2..da12 unread (unselected), da9 suspended
		ChatOpenRenderer.Layout start = p3.layout("d-a");
		boolean indicator = start.rightArrowColor() == 0xFFFF5555 || start.rightArrowColor() == 0xFFFFFF55;
		m.put("hiddenIndicatorColor", Integer.toHexString(start.rightArrowColor()));
		p3.closeChat();
		ev.result("P4.stripOverflow", overflows && seen.size() == 12 && Boolean.TRUE.equals(m.get("wheelScrollsStrip")) && revealed && indicator, "observed", m,
				"note", "arrows show red when a tab scrolled out that way is suspended, yellow when one has unread");
	}

	void drops(TestSingleplayerContext sp) {
		Map<String, Object> m = new LinkedHashMap<>();
		int[] sc = p3.screen();
		p3.openChat("");
		ChatOpenRenderer.Layout a = p3.layout("d-a"), b = p3.layout("d-b"), cover = p3.layout("d-cover"), ch = p3.layout("d-chat");
		// (1) move db2 onto d-a's strip, crossing the input box and the main chat on the way (capture, V-09e(1))
		int e0 = entries("db2"), u0 = unread("db2");
		JsonObject raw0 = tab(Phase2Tests.json(Phase2Tests.cfg()), "db2");
		double[] stripPoint = {a.strip()[0] + a.strip()[2] - 15, a.strip()[1] + 6};
		ChatScreenInput.Drop d1 = dragTab("d-b", "db2", stripPoint[0], stripPoint[1], new double[]{60, sc[1] - 8});
		boolean moved = d1 == ChatScreenInput.Drop.MOVED && "d-a".equals(windowOf("db2")) && entries("db2") == e0 && unread("db2") == u0
				&& !"db2".equals(onClient(() -> mgr().config().window("d-a").selectedTab()));
		JsonObject raw1 = tab(Phase2Tests.json(Phase2Tests.cfg()), "db2");
		m.put("1.moveToTopmostStrip", AcceptanceTest.row("outcome", d1, "historyKept", entries("db2") == e0, "unreadKept", unread("db2") == u0,
				"configUnchanged", raw0.equals(raw1), "ok", moved));
		p3.closeChat();
		p3.openChat("");
		// (2) drop on the source window: no-op
		ChatOpenRenderer.Layout a2 = p3.layout("d-a");
		ChatScreenInput.Drop d2 = dragTab("d-a", "db2", a2.body()[0] + a2.body()[2] - 10, a2.body()[1] + 20, null);
		m.put("2.sourceNoOp", AcceptanceTest.row("outcome", d2, "ok", d2 == ChatScreenInput.Drop.NO_OP_SOURCE && "d-a".equals(windowOf("db2"))));
		// (3) over a protected region: cancel (input box; and the covered part of d-chat's strip)
		ChatScreenInput.Drop d3 = dragTab("d-a", "db2", 60, sc[1] - 8, null);
		ChatOpenRenderer.Layout ch2 = p3.layout("d-chat");
		double[] coveredStrip = onClient(() -> {
			ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
			for (int x = ch2.strip()[0] + 2; x < ch2.strip()[0] + ch2.strip()[2]; x += 2) {
				if (HitTester.protectedRegion(s, x + 0.5, ch2.strip()[1] + 6.5) != null) return new double[]{x + 0.5, ch2.strip()[1] + 6.5};
			}
			return null;
		});
		ChatScreenInput.Drop d3b = coveredStrip == null ? null : dragTab("d-a", "db2", coveredStrip[0], coveredStrip[1], null);
		m.put("3.protectedCancel", AcceptanceTest.row("inputBox", d3, "coveredStripPart", coveredStrip == null ? "none found" : d3b,
				"ok", d3 == ChatScreenInput.Drop.CANCELLED_PROTECTED && (coveredStrip == null || d3b == ChatScreenInput.Drop.CANCELLED_PROTECTED) && "d-a".equals(windowOf("db2"))));
		// uncovered part of the same strip accepts (V-41c)
		double[] freeStrip = onClient(() -> {
			ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
			for (int x = ch2.strip()[0] + ch2.strip()[2] - 4; x > ch2.strip()[0]; x -= 2) {
				var top = ChatOpenRenderer.topAt(x + 0.5, ch2.strip()[1] + 6.5);
				if (top != null && top.windowId().equals("d-chat") && HitTester.protectedRegion(s, x + 0.5, ch2.strip()[1] + 6.5) == null) return new double[]{x + 0.5, ch2.strip()[1] + 6.5};
			}
			return null;
		});
		ChatScreenInput.Drop d3c = freeStrip == null ? null : dragTab("d-a", "db2", freeStrip[0], freeStrip[1], null);
		m.put("V-41c.uncoveredStripPartAccepts", AcceptanceTest.row("outcome", d3c, "ok", d3c == ChatScreenInput.Drop.MOVED && "d-chat".equals(windowOf("db2"))));
		// (4) another window's body, and an obscured strip (d-b's strip under d-cover): cancel
		p3.closeChat();
		ctx.runOnClient(c -> mgr().edit(new ConfigEdit.BringToFront("d-cover"))); // case (1) brought d-b to the front
		p3.openChat("");
		ChatOpenRenderer.Layout bb = p3.layout("d-b");
		ChatScreenInput.Drop d4 = dragTab("d-a", "da6", bb.body()[0] + 20, bb.body()[1] + bb.body()[3] - 6, null);
		ChatOpenRenderer.Layout cv = p3.layout("d-cover");
		double[] obscured = onClient(() -> {
			for (int x = bb.strip()[0]; x < bb.strip()[0] + bb.strip()[2]; x += 2) {
				var top = ChatOpenRenderer.topAt(x + 0.5, bb.strip()[1] + 6.5);
				if (top != null && top.windowId().equals("d-cover")) return new double[]{x + 0.5, bb.strip()[1] + 6.5};
			}
			return null;
		});
		ChatScreenInput.Drop d4b = obscured == null ? null : dragTab("d-a", "da6", obscured[0], obscured[1], null);
		m.put("4.otherBodyAndObscuredStripCancel", AcceptanceTest.row("body", d4, "obscuredStrip", obscured == null ? "none found" : d4b,
				"ok", d4 == ChatScreenInput.Drop.CANCELLED_OTHER_WINDOW && obscured != null && d4b == ChatScreenInput.Drop.CANCELLED_OTHER_WINDOW && "d-a".equals(windowOf("da6"))));
		// (5) empty space: a new on-screen window every time (edges, corners, over the disabled window's area)
		List<Map<String, Object>> created = new ArrayList<>();
		boolean allCreated = true;
		double[][] spots = {{sc[0] * 0.46, sc[1] * 0.08}, {sc[0] / 2.0 + 20, sc[1] - 20}, {sc[0] - 3, sc[1] / 2.0 + 30}, {sc[0] - 2, sc[1] - 18}};
		String[] names = {"overDisabledWindow", "bottomEdge", "rightEdge", "bottomRightCorner (above the input row)"};
		for (int i = 0; i < spots.length; i++) {
			String tabId = "da" + (2 + i);
			String fromWin = windowOf(tabId);
			p3.closeChat();
			ctx.runOnClient(c -> mgr().edit(new ConfigEdit.BringToFront("d-a"))); // earlier new windows may overlap d-a's strip
			p3.openChat("");
			int ent = entries(tabId);
			int unreadBefore = unread(tabId);
			ChatScreenInput.Drop d5 = dragTab(fromWin, tabId, spots[i][0], spots[i][1], null);
			String newWin = windowOf(tabId);
			ExtendedConfig.Window nw = onClient(() -> mgr().config().window(newWin));
			int[] r = p3.layout(newWin) == null ? null : p3.layout(newWin).rect();
			boolean onScreen = r != null && r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= sc[0] && r[1] + r[3] <= sc[1];
			boolean ok = d5 == ChatScreenInput.Drop.NEW_WINDOW && nw != null && nw.enabled() && tabId.equals(nw.selectedTab()) && unread(tabId) == 0 && entries(tabId) == ent && onScreen;
			allCreated &= ok;
			created.add(AcceptanceTest.row("spot", names[i], "outcome", d5, "newWindow", newWin, "rect", r == null ? null : Arrays.toString(r),
					"unreadBeforeAfter", unreadBefore + "->" + unread(tabId), "ok", ok));
		}
		m.put("5.newWindow", created);
		// (6) cancellations: Esc and a forced screen change mid-drag; never a lost tab
		ChatOpenRenderer.Layout la = p3.layout("d-a");
		ChatOpenRenderer.TabSlot s6 = la.tabs().get(0);
		p3.cursor(s6.x() + 3, s6.y() + 5);
		ctx.getInput().holdMouse(0);
		t.waitT(2);
		p3.cursor(s6.x() + 40, s6.y() + 40);
		t.waitT(2);
		boolean draggingTab = onClient(ChatScreenInput::draggingTab);
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		t.waitT(2);
		ctx.getInput().releaseMouse(0);
		t.waitT(2);
		ChatScreenInput.Drop esc = onClient(ChatScreenInput::lastDrop);
		boolean escKept = "d-a".equals(windowOf(s6.tabId()));
		p3.openChat("");
		ChatOpenRenderer.TabSlot s7 = p3.layout("d-a").tabs().get(0);
		p3.cursor(s7.x() + 3, s7.y() + 5);
		ctx.getInput().holdMouse(0);
		t.waitT(2);
		p3.cursor(s7.x() + 40, s7.y() + 40);
		t.waitT(2);
		ctx.setScreen(() -> new PauseScreen(true)); // a screen change the player didn't make (server-forced in real play)
		t.waitT(2);
		ctx.getInput().releaseMouse(0);
		t.waitT(2);
		ChatScreenInput.Drop forced = onClient(ChatScreenInput::lastDrop);
		boolean forcedKept = "d-a".equals(windowOf(s7.tabId()));
		ctx.setScreen(() -> null);
		m.put("6.cancellations", AcceptanceTest.row("draggingBeforeEsc", draggingTab, "esc", esc, "escTabKept", escKept, "forcedScreenChange", forced, "forcedTabKept", forcedKept));
		// Q30e: d-b lost both tabs over the run? keep it if emptied
		boolean ok = Boolean.TRUE.equals(((Map<?, ?>) m.get("1.moveToTopmostStrip")).get("ok")) && Boolean.TRUE.equals(((Map<?, ?>) m.get("2.sourceNoOp")).get("ok"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("3.protectedCancel")).get("ok")) && Boolean.TRUE.equals(((Map<?, ?>) m.get("4.otherBodyAndObscuredStripCancel")).get("ok"))
				&& allCreated && draggingTab && esc == ChatScreenInput.Drop.CANCELLED_CLOSED && escKept && forced == ChatScreenInput.Drop.CANCELLED_CLOSED && forcedKept;
		ev.result("V-41e", ok, "cases", m);
		ev.result("V-41c", Boolean.TRUE.equals(((Map<?, ?>) m.get("V-41c.uncoveredStripPartAccepts")).get("ok")) && Boolean.TRUE.equals(((Map<?, ?>) m.get("3.protectedCancel")).get("ok"))
				&& Boolean.TRUE.equals(((Map<?, ?>) m.get("4.otherBodyAndObscuredStripCancel")).get("ok")), "note",
				"covered strip parts refuse, the uncovered part of the same strip accepts, an obscured strip refuses; hidden or disabled windows aren't drawn and never accept (case 5 over d-off creates a new window)");
		ev.result("V-41d", allCreated, "created", created);
		ev.result("V-09e.tabDrag", Boolean.TRUE.equals(((Map<?, ?>) m.get("1.moveToTopmostStrip")).get("ok")) && Boolean.TRUE.equals(((Map<?, ?>) m.get("3.protectedCancel")).get("ok")),
				"note", "(1) the tab drag kept capture while crossing the input box and main chat; (4) release over a protected region cancels");
	}

	// =================================================================== keys

	void keys() {
		Map<String, Object> m = new LinkedHashMap<>();
		KeyMapping k = Keybinds.openConfig();
		m.put("unboundByDefault", k.getDefaultKey().equals(InputConstants.UNKNOWN));
		m.put("currentlyUnbound", k.isUnbound());
		m.put("customCategory", Keybinds.customCategory());
		m.put("category", k.getCategory().id().toString());
		m.put("categoryLabel", k.getCategory().label().getString());
		boolean registered = onClient(() -> Arrays.asList(Minecraft.getInstance().options.keyMappings).contains(k));
		m.put("inOptionsKeyMappings", registered);
		boolean editModeKey = onClient(() -> Arrays.stream(Minecraft.getInstance().options.keyMappings).filter(x -> x.getName().startsWith("key.cpextended")).count()) == 1;
		m.put("onlyOneExtendedKey", editModeKey);
		ctx.setScreen(() -> new KeyBindsScreen(null, Minecraft.getInstance().options));
		t.waitT(10);
		boolean controlsOpen = screen() instanceof KeyBindsScreen;
		ctx.takeScreenshot("p4-controls");
		m.put("controlsScreenOpens", controlsOpen);
		ctx.setScreen(() -> null);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			ctx.runOnClient(c -> { k.setKey(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_K)); KeyMapping.resetMapping(); });
			ctx.getInput().pressKey(InputConstants.KEY_K);
			t.waitT(3);
			m.put("opensInWorld", screen() instanceof ConfigScreen);
			ctx.setScreen(() -> null);
			t.waitT(2);
			p3.openChat("");
			ctx.getInput().pressKey(InputConstants.KEY_K); // the bound key's press...
			ctx.getInput().typeChars("k");                 // ...and the character it types
			t.waitT(3);
			m.put("typingNotIntercepted", screen() instanceof ChatScreen && "k".equals(p3.inputState().get(0)));
			p3.closeChat();
			ctx.setScreen(() -> new PauseScreen(true));
			t.waitT(2);
			ctx.getInput().pressKey(InputConstants.KEY_K);
			t.waitT(3);
			m.put("pauseScreen", screen() instanceof PauseScreen ? "stays on the pause screen (A-3: key mappings act only with no screen open)" : String.valueOf(screen()));
			ctx.setScreen(() -> null);
		}
		t.waitT(20);
		ctx.getInput().pressKey(InputConstants.KEY_K);
		t.waitT(3);
		m.put("titleScreen", screen() instanceof ConfigScreen ? "opened" : "not opened on the title screen (A-3; documented)");
		ctx.runOnClient(c -> { k.setKey(InputConstants.UNKNOWN); KeyMapping.resetMapping(); c.options.save(); });
		boolean ok = Boolean.TRUE.equals(m.get("unboundByDefault")) && Boolean.TRUE.equals(m.get("customCategory")) && registered && editModeKey && controlsOpen
				&& Boolean.TRUE.equals(m.get("opensInWorld")) && Boolean.TRUE.equals(m.get("typingNotIntercepted"));
		ev.result("V-38", ok, "observed", m, "note", "custom category K-A worked, so no Misc fallback was needed; Mod Menu parts are V-14 (extras run)");
	}

	// =================================================================== scales

	void scales() throws Exception {
		ctx.getInput().resizeWindow(1280, 960);
		t.waitT(5);
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			restart(Phase3Tests.CONFIG);
			ctx.runOnClient(c -> { c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.FULL); c.options.chatWidth().set(0.4); c.gui.getChat().rescaleChat(); });
			for (int i = 0; i < 8; i++) p3.magenta(sp, "scale window line " + i + " cpx#PC-" + i);
			// the newest line of p3-c (bottom-left, under the input box) is hover-enabled over its whole text
			sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(Component.literal("HOVER UNDER INPUT cpx#PC-hover")
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF00FF)).withHoverEvent(new HoverEvent.ShowText(Component.literal("should not show")))), false));
			for (int i = 0; i < 30; i++) t.sys(sp, "white filler " + i);
			t.waitT(10);
			int old = onClient(() -> Minecraft.getInstance().options.guiScale().get());
			for (int gs = 1; gs <= 4; gs++) {
				int g = gs;
				ctx.runOnClient(c -> { c.options.guiScale().set(g); c.resizeDisplay(); WindowRenderer.resetCounters(); });
				t.waitT(4);
				int actual = onClient(() -> (int) Minecraft.getInstance().getWindow().getGuiScale());
				int[] hud = WindowRenderer.lastRect("p3-c");
				ctx.runOnClient(cl -> ChatOpenRenderer.setScroll("p3-c", 0));
				p3.openChat("");
				ChatOpenRenderer.Layout c = p3.layout("p3-c");
				int[] sc = p3.screen();
				BufferedImage img = ImageIO.read(ctx.takeScreenshot("p4-scale-" + gs).toFile());
				double scale = actual; // physical pixels per GUI pixel (the GUI width is rounded, so width ratios drift)
				// pixel check: no window text over drawn protected UI (main chat lines, input)
				int[] r = c.rect();
				boolean[][] prot = ctx.computeOnClient(cl -> {
					boolean[][] p = new boolean[r[2]][r[3]];
					ChatScreen s = (ChatScreen) cl.screen;
					for (int x = 0; x < r[2]; x++) for (int y = 0; y < r[3]; y++) p[x][y] = HitTester.protectedRegion(s, r[0] + x + 0.5, r[1] + y + 0.5) != null;
					return p;
				});
				int coveredDrawn = 0, visibleElsewhere = 0;
				List<int[]> coveredAt = new ArrayList<>();
				for (int x = 0; x < r[2]; x++) for (int y = 0; y < r[3]; y++) {
					boolean pure = Phase3Tests.pureMagenta(img, scale, r[0] + x, r[1] + y);
					int gy = r[1] + y;
					boolean band = gy >= sc[1] - 33 && gy < sc[1] - 19 || gy >= sc[1] - 2; // search-field / input click-only rows (Phase 3)
					if (prot[x][y] && pure && !band) { coveredDrawn++; if (coveredAt.size() < 10) coveredAt.add(new int[]{r[0] + x, gy}); }
					if (!prot[x][y] && pure) visibleElsewhere++;
				}
				List<String> coveredDetail = ctx.computeOnClient(cl -> coveredAt.stream().map(pt -> pt[0] + "," + pt[1] + ":"
						+ HitTester.protectedRegion((ChatScreen) cl.screen, pt[0] + 0.5, pt[1] + 0.5)).toList());
				// input matches visual layering: scroll at sample points
				int mism = 0;
				for (int gy2 = 0; gy2 < 4; gy2++) for (int gx = 0; gx < 6; gx++) {
					int px = r[0] + 4 + gx * (r[2] - 8) / 5, py = r[1] + ChatOpenRenderer.STRIP_H + 3 + gy2 * (r[3] - ChatOpenRenderer.STRIP_H - 6) / 3;
					boolean expected = !prot[px - r[0]][py - r[1]];
					long before = ChatScreenInput.consumedScrolls();
					p3.scrollAt(px + 0.5, py + 0.5, 1);
					if ((ChatScreenInput.consumedScrolls() > before) != expected) mism++;
				}
				ctx.runOnClient(cl -> { cl.gui.getChat().resetChatScroll(); ChatOpenRenderer.setScroll("p3-c", 0); });
				t.waitT(2);
				// tooltip under the vanilla input box with a real hover-enabled message: suppressed
				ChatOpenRenderer.Layout c2 = p3.layout("p3-c");
				double[] hp = onClient(() -> {
					var font = Minecraft.getInstance().font;
					ChatScreen s = (ChatScreen) Minecraft.getInstance().screen;
					for (ChatOpenRenderer.LineSlot l : c2.lines()) for (int x = l.x(); x < c2.rect()[0] + c2.rect()[2]; x++) {
						Style st = ChatOpenRenderer.styleAt(font, l, x + 1.5);
						if (st != null && st.getHoverEvent() != null && "input".equals(HitTester.protectedRegion(s, x + 1.5, l.y() + l.h() / 2.0)))
							return new double[]{x + 1.5, l.y() + l.h() / 2.0};
					}
					return null;
				});
				long tf = ChatOpenRenderer.tooltipFrames();
				if (hp != null) { p3.cursor(hp[0], hp[1]); t.waitT(3); }
				boolean suppressed = hp != null && ChatOpenRenderer.tooltipFrames() == tf;
				p3.closeChat();
				boolean same = hud != null && Arrays.equals(hud, c.rect());
				boolean rowOk = actual == gs && coveredDrawn == 0 && visibleElsewhere > 0 && mism == 0 && suppressed && same && WindowRenderer.duplicateDraws() == 0;
				ok &= rowOk;
				rows.add(row("guiScale", gs, "actualScale", actual, "screen", Arrays.toString(sc), "windowTextOverDrawnProtected", coveredDrawn,
						"windowTextVisibleElsewhere", visibleElsewhere, "coveredDetail", coveredDetail, "inputMismatches", mism, "tooltipUnderInputFound", hp != null, "tooltipSuppressed", suppressed,
						"hudAndChatOpenRectEqual", same, "ok", rowOk));
			}
			ctx.runOnClient(c -> { c.options.guiScale().set(old); c.resizeDisplay(); });
		}
		ctx.getInput().resizeWindow(854, 480);
		t.waitT(20);
		ev.result("V-12", ok, "window", "1280x960", "rows", rows, "note", "GUI scales 1-4 actually in effect; layering, input consistency and both paths' geometry");
		ev.result("V-08.inputBox", rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("tooltipSuppressed"))), "note",
				"a message with a hover event over its whole text, drawn under the vanilla input box: no Extended tooltip at any GUI scale");
	}

}
