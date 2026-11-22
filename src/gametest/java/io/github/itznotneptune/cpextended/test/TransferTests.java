// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.config.LayoutFiles;
import io.github.itznotneptune.cpextended.config.LayoutTransfer;
import io.github.itznotneptune.cpextended.gui.ConfigScreen;
import io.github.itznotneptune.cpextended.gui.ExportScreen;
import io.github.itznotneptune.cpextended.gui.TransferScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.itznotneptune.cpextended.test.Phase2Tests.cfg;
import static io.github.itznotneptune.cpextended.test.Phase2Tests.doc;
import static io.github.itznotneptune.cpextended.test.Phase2Tests.write;

/** Import and export of windows and tabs (clipboard and files; import only adds). Mode {@code transfer}. */
final class TransferTests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;
	private final Phase4Tests p4;

	TransferTests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
		this.p4 = new Phase4Tests(t);
	}

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	ConfigManager mgr() { return ConfigManager.get(); }

	ExtendedConfig config() { return onClient(() -> mgr().config()); }

	Screen screen() { return onClient(() -> Minecraft.getInstance().screen); }

	void restart() { ctx.runOnClient(c -> mgr().devSimulateRestart()); }

	long tokInTab(String tab, String token) {
		return t.tabEntries(tab).stream().filter(r -> r.original() != null && r.original().getString().contains(token)).count();
	}

	/** Two windows: a top-right one with a hiding regex tab and a filtered tab (selected), and a disabled one. */
	static final String CONFIG = doc(
			"{\"id\": \"w-a\", \"enabled\": true, \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.2, \"width\": 0.33, \"height\": 0.28, \"selectedTab\": \"ta2\", \"tabs\": ["
			+ "{\"id\": \"ta1\", \"name\": \"Shop\", \"enabled\": true, \"hideFromMainChat\": true, \"restoreHistory\": false, \"matchAll\": false,"
			+ " \"include\": [{\"mode\": \"regex\", \"pattern\": \"^\\\\[Shop\\\\] \"}], \"exclude\": []},"
			+ "{\"id\": \"ta2\", \"name\": \"Staff\", \"enabled\": true, \"hideFromMainChat\": false, \"restoreHistory\": true, \"matchAll\": false,"
			+ " \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#TS\", \"caseSensitive\": true}], \"exclude\": [{\"mode\": \"literal\", \"pattern\": \"ignore\"}]}]},"
			+ "{\"id\": \"w-b\", \"enabled\": false, \"anchor\": \"TOP_LEFT\", \"x\": 0.01, \"y\": 0.5, \"width\": 0.3, \"height\": 0.25, \"tabs\": ["
			+ "{\"id\": \"tb1\", \"name\": \"All\", \"enabled\": true, \"hideFromMainChat\": false, \"restoreHistory\": true, \"matchAll\": true, \"include\": [], \"exclude\": []}]}");

	void transfer() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			ctx.runOnClient(c -> c.options.hideGui = false);
			roundTripThroughScreens(sp);
			windowAndTab();
			invalidInputs();
			exportRefusedWhenInvalid();
			collisionAtApplyIsAtomic();
		}
		t.waitT(20);
	}

	// ---------------------------------------------------------------- helpers

	static JsonObject canonical(JsonObject raw) {
		JsonObject o = raw.deepCopy();
		o.remove("id");
		o.remove("selectedTab");
		if (o.has("tabs")) for (JsonElement te : o.getAsJsonArray("tabs")) te.getAsJsonObject().remove("id");
		return o;
	}

	static String rawOf(ExtendedConfig c, String windowId) {
		ExtendedConfig.Window w = c.window(windowId);
		return w == null ? null : w.raw().toString();
	}

	// ---------------------------------------------------------------- tests

	/** Export everything -> clipboard -> paste -> confirm, all through the screens; the copy matches field by field. */
	void roundTripThroughScreens(TestSingleplayerContext sp) {
		write(cfg(), CONFIG);
		restart();
		Map<String, Object> m = new LinkedHashMap<>();
		String beforeA = rawOf(config(), "w-a"), beforeB = rawOf(config(), "w-b");
		p4.open(() -> new ConfigScreen(null));
		m.put("openedTransfer", p4.press("Import / Export") && screen() instanceof TransferScreen);
		m.put("openedExport", p4.press("Export everything") && screen() instanceof ExportScreen);
		ExportScreen es = (ExportScreen) screen();
		m.put("copied", p4.press("Copy to clipboard") && onClient(() -> es.status().getString()).startsWith("Copied 2 windows with 3 tabs"));
		String clip = onClient(() -> Minecraft.getInstance().keyboardHandler.getClipboard());
		m.put("clipboardIsExport", clip != null && clip.contains("\"cpextendedExport\": 1") && clip.contains("\"kind\": \"layout\""));
		p4.press("Back");
		TransferScreen ts = (TransferScreen) screen();
		p4.press("Paste from clipboard");
		Screen confirm = screen();
		String message = confirm == null ? "" : onClient(() -> confirm.getNarrationMessage().getString());
		m.put("confirmShown", confirm instanceof net.minecraft.client.gui.screens.ConfirmScreen && message.contains("Add 2 windows with 3 tabs")
				&& message.contains("nothing you have now is changed") && message.contains("1 imported tab hides"));
		p4.press("Yes");
		m.put("statusImported", onClient(() -> ts.status().getString()).startsWith("Imported 2 windows with 3 tabs"));
		ctx.setScreen(() -> null);

		ExtendedConfig after = config();
		m.put("windowCount", after.windows().size());
		m.put("originalsUnchanged", beforeA.equals(rawOf(after, "w-a")) && beforeB.equals(rawOf(after, "w-b")));
		List<ExtendedConfig.Window> added = after.windows().stream().filter(w -> !List.of("w-a", "w-b").contains(w.id())).toList();
		boolean sameContent = added.size() == 2 && canonical(added.get(0).raw()).equals(canonical(after.window("w-a").raw()))
				&& canonical(added.get(1).raw()).equals(canonical(after.window("w-b").raw()));
		m.put("copyMatchesFieldByField", sameContent);
		boolean freshIds = added.stream().allMatch(w -> w.id().startsWith("window-") && w.tabs().stream().allMatch(x -> x.id().startsWith("tab-")));
		m.put("freshIds", freshIds);
		ExtendedConfig.Window copyA = added.isEmpty() ? null : added.get(0);
		boolean selectionFollows = copyA != null && copyA.selectedTab() != null && copyA.tabs().size() == 2 && copyA.selectedTab().equals(copyA.tabs().get(1).id());
		m.put("selectedTabFollowsCopy", selectionFollows);
		m.put("disabledWindowStaysDisabled", added.size() == 2 && !added.get(1).enabled());
		m.put("savedToFile", Phase2Tests.json(cfg()).getAsJsonArray("windows").size() == 4);

		// The imported tabs route like the originals.
		t.sys(sp, "[Shop] apples cpx#TR-1");
		t.sys(sp, "staff note cpx#TS-1");
		t.waitT(8);
		String shopCopy = copyA == null ? "?" : copyA.tabs().get(0).id(), staffCopy = copyA == null ? "?" : copyA.tabs().get(1).id();
		m.put("importedTabsRoute", tokInTab("ta1", "cpx#TR-1") == 1 && tokInTab(shopCopy, "cpx#TR-1") == 1
				&& tokInTab("ta2", "cpx#TS-1") == 1 && tokInTab(staffCopy, "cpx#TS-1") == 1 && tokInTab(staffCopy, "cpx#TR-1") == 0);
		boolean ok = m.values().stream().allMatch(v -> !(v instanceof Boolean b) || b) && Integer.valueOf(4).equals(m.get("windowCount"));
		ev.result("TR.roundTrip", ok, "observed", m);
	}

	/** One window (API) and one tab (screen, through a saved file): the tab lands in a new default window. */
	void windowAndTab() {
		write(cfg(), CONFIG);
		restart();
		Map<String, Object> m = new LinkedHashMap<>();
		LayoutTransfer.Export we = LayoutTransfer.exportWindow(config(), "w-b");
		LayoutTransfer.Parsed wp = onClient(() -> LayoutTransfer.parse(we.text(), mgr().config()));
		boolean windowOk = wp.plan() != null && wp.plan().windowCount() == 1 && wp.plan().tabCount() == 1
				&& onClient(() -> mgr().editAll(List.of(wp.plan().edit())).applied());
		m.put("windowImported", windowOk && config().windows().size() == 3);

		// Tab: export from the tab screen to a file, then import that file from the Import / Export screen.
		var tabScreen = p4.openTab("ta2");
		m.put("openedTabExport", p4.press("Export tab") && screen() instanceof ExportScreen);
		ExportScreen es = (ExportScreen) screen();
		Path saved = onClient(es::saveToFile);
		m.put("fileSaved", saved != null && Files.isRegularFile(saved) && saved.getParent().equals(LayoutFiles.folder())
				&& saved.getFileName().toString().startsWith("tab-Staff-"));
		ctx.setScreen(() -> null);
		TransferScreen ts = onClient(() -> new TransferScreen(new ConfigScreen(null)));
		p4.open(() -> ts);
		m.put("fileListed", saved != null && onClient(LayoutFiles::list).contains(saved));
		m.put("fileButtonShown", saved != null && p4.widget(saved.getFileName().toString()) != null);
		if (saved != null) p4.press(saved.getFileName().toString());
		p4.press("Yes");
		ctx.setScreen(() -> null);
		ExtendedConfig after = config();
		ExtendedConfig.Window added = after.windows().get(after.windows().size() - 1);
		boolean tabOk = after.windows().size() == 4 && added.tabs().size() == 1 && "Staff".equals(added.tabs().get(0).config().name())
				&& added.x() == 0.05 && added.y() == 0.10 && added.width() == 0.30 && added.height() == 0.25 && added.anchor() == ExtendedConfig.Anchor.TOP_LEFT
				&& added.tabs().get(0).config().include().equals(after.windowOfTab("ta2").tabs().get(1).config().include())
				&& added.tabs().get(0).config().exclude().equals(after.windowOfTab("ta2").tabs().get(1).config().exclude());
		m.put("tabImportedIntoNewWindow", tabOk);
		ev.result("TR.windowAndTab", m.values().stream().allMatch(Boolean.TRUE::equals), "observed", m);
	}

	/** Broken, tampered and oversized input is refused with a reason and never changes anything. */
	void invalidInputs() {
		write(cfg(), CONFIG);
		restart();
		String good = LayoutTransfer.exportLayout(config()).text();
		JsonObject g = JsonParser.parseString(good).getAsJsonObject();
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("empty", "");
		cases.put("plain text", "hello there");
		cases.put("other json", "{\"windows\": []}");
		JsonObject newer = g.deepCopy(); newer.addProperty("cpextendedExport", 2);
		cases.put("newer format", newer.toString());
		JsonObject badKind = g.deepCopy(); badKind.addProperty("kind", "everything");
		cases.put("unknown kind", badKind.toString());
		JsonObject badRegex = g.deepCopy();
		badRegex.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("tabs").get(0).getAsJsonObject()
				.getAsJsonArray("include").get(0).getAsJsonObject().addProperty("pattern", "(a|b");
		cases.put("tampered regex", badRegex.toString());
		JsonObject bomb = g.deepCopy();
		bomb.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("tabs").get(0).getAsJsonObject()
				.getAsJsonArray("include").get(0).getAsJsonObject().addProperty("pattern", "((a{1000}){1000}){1000}");
		cases.put("regex bomb", bomb.toString());
		JsonObject badBool = g.deepCopy();
		badBool.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("tabs").get(0).getAsJsonObject().addProperty("enabled", "yes");
		cases.put("wrong type", badBool.toString());
		JsonObject twoInWindow = g.deepCopy(); twoInWindow.addProperty("kind", "window");
		cases.put("window kind with two windows", twoInWindow.toString());
		JsonObject many = g.deepCopy(); JsonArray ws = new JsonArray();
		for (int i = 0; i < LayoutTransfer.MAX_WINDOWS + 1; i++) ws.add(g.getAsJsonArray("windows").get(1).deepCopy());
		many.add("windows", ws);
		cases.put("too many windows", many.toString());
		cases.put("too large", good + " ".repeat(LayoutTransfer.MAX_TEXT));
		JsonObject dupTabs = g.deepCopy();
		JsonArray tabs = dupTabs.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("tabs");
		tabs.get(1).getAsJsonObject().addProperty("id", "ta1");
		cases.put("duplicate tab ids", dupTabs.toString());

		String before = config().raw().toString();
		Map<String, String> errors = new LinkedHashMap<>();
		boolean allRefused = true;
		for (Map.Entry<String, String> e : cases.entrySet()) {
			LayoutTransfer.Parsed p = onClient(() -> LayoutTransfer.parse(e.getValue(), mgr().config()));
			errors.put(e.getKey(), p.error());
			if (p.error() == null || p.plan() != null) allRefused = false;
		}
		// The same refusal through the screen: status explains it, nothing is opened or changed.
		TransferScreen ts = onClient(() -> new TransferScreen(new ConfigScreen(null)));
		p4.open(() -> ts);
		onClient(() -> ts.importText("{\"cpextendedExport\": 2, \"kind\": \"layout\", \"windows\": []}", "the clipboard"));
		boolean screenRefusal = screen() == ts && onClient(() -> ts.status().getString()).startsWith("Can't import from the clipboard: Made by a newer version");
		ctx.setScreen(() -> null);
		boolean unchanged = before.equals(config().raw().toString());
		// A paste wrapped in a Markdown code block (as chat apps do) still imports.
		LayoutTransfer.Parsed fenced = onClient(() -> LayoutTransfer.parse("```json\n" + good + "```", mgr().config()));
		// Refusals are written for players: no internal codes or settings-file repair notes.
		boolean readable = errors.get("tampered regex").endsWith("invalid regex: missing closing )") && errors.get("regex bomb").endsWith("regex too complex")
				&& errors.values().stream().noneMatch(e -> e == null || e.contains("regex_") || e.contains("value kept") || e.contains("_mode"));
		ev.result("TR.invalidInputs", allRefused && screenRefusal && unchanged && fenced.plan() != null && readable, "readableMessages", readable, "errors", errors,
				"screenRefusal", screenRefusal, "settingsUnchanged", unchanged, "fencedPasteAccepted", fenced.plan() != null);
	}

	/** A selection with an invalid filter can't be exported (so every export imports). */
	void exportRefusedWhenInvalid() {
		write(cfg(), doc("{\"id\": \"w-x\", \"tabs\": [{\"id\": \"tx\", \"name\": \"Broken\", \"include\": [{\"mode\": \"regex\", \"pattern\": \"[\"}]},"
				+ "{\"id\": \"ty\", \"name\": \"Fine\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"ok\"}]}]}"));
		restart();
		LayoutTransfer.Export all = LayoutTransfer.exportLayout(config());
		LayoutTransfer.Export broken = LayoutTransfer.exportTab(config(), "tx");
		LayoutTransfer.Export fine = LayoutTransfer.exportTab(config(), "ty");
		boolean ok = all.text() == null && all.error() != null && all.error().contains("Broken") && broken.text() == null && fine.text() != null;
		ev.result("TR.exportRefusedWhenInvalid", ok, "layoutError", all.error(), "tabError", broken.error(), "validTabExported", fine.text() != null);
	}

	/** If an id became taken between preparing and applying, the whole import is rejected and nothing changes. */
	void collisionAtApplyIsAtomic() {
		write(cfg(), CONFIG);
		restart();
		LayoutTransfer.Plan plan = onClient(() -> LayoutTransfer.parse(LayoutTransfer.exportLayout(mgr().config()).text(), mgr().config()).plan());
		String takenId = plan.windows().get(1).get("id").getAsString();
		onClient(() -> mgr().edit(new ConfigEdit.AddWindow(takenId, 0.1, 0.1, 0.2, 0.2)).applied());
		String before = config().raw().toString();
		ConfigManager.EditOutcome o = onClient(() -> mgr().editAll(List.of(plan.edit())));
		boolean ok = !o.applied() && String.valueOf(o.edit().reason()).contains("already exists") && before.equals(config().raw().toString());
		ev.result("TR.collisionAtomic", ok, "reason", o.edit().reason(), "settingsUnchanged", before.equals(config().raw().toString()));
	}
}
