// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.config.OrphanGuard;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.filter.PatternValidator;
import io.github.itznotneptune.cpextended.render.WindowRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.ChatVisiblity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Phase 2 acceptance tests: configuration persistence (P14, Q28, P8, Q36) and read-only HUD windows (P10). */
final class Phase2Tests {
	private final AcceptanceTest t;
	private final ClientGameTestContext ctx;
	private final Evidence ev;

	Phase2Tests(AcceptanceTest t) {
		this.t = t;
		this.ctx = t.ctx;
		this.ev = t.ev;
	}

	// =================================================================== helpers

	static Path cfgDir() { return FabricLoader.getInstance().getConfigDir(); }
	static Path cfg() { return cfgDir().resolve("cpextended.json"); }
	static Path bak() { return cfgDir().resolve("cpextended.json.bak"); }

	static void write(Path p, String s) {
		try {
			Files.writeString(p, s, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static byte[] bytes(Path p) {
		try {
			return Files.exists(p) ? Files.readAllBytes(p) : null;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static JsonObject json(Path p) {
		return JsonParser.parseString(new String(bytes(p), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	static List<Path> corruptCopies() {
		try (Stream<Path> s = Files.list(cfgDir())) {
			return s.filter(p -> p.getFileName().toString().startsWith("cpextended.json.corrupt-")).sorted().toList();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static void cleanConfigFiles() {
		try (Stream<Path> s = Files.list(cfgDir())) {
			for (Path p : s.filter(p -> p.getFileName().toString().startsWith("cpextended.json")).toList()) Files.deleteIfExists(p);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	ConfigManager mgr() { return ConfigManager.get(); }

	void restart() { ctx.runOnClient(c -> mgr().devSimulateRestart()); }

	<T> T onClient(java.util.function.Supplier<T> s) { return ctx.computeOnClient(c -> s.get()); }

	ConfigManager.EditOutcome edit(ConfigEdit e) { return onClient(() -> mgr().edit(e)); }

	ConfigManager.SaveOutcome save() { return onClient(() -> mgr().attemptSave("test")); }

	Map<String, List<String>> configErrors() { return onClient(() -> Router.get().config().configErrorSummary()); }

	static JsonObject filterOf(JsonObject root, String tabId, String list, int index) {
		for (JsonElement w : root.getAsJsonArray("windows")) {
			if (!w.isJsonObject() || !w.getAsJsonObject().has("tabs")) continue;
			for (JsonElement te : w.getAsJsonObject().getAsJsonArray("tabs")) {
				JsonObject tab = te.getAsJsonObject();
				if (tab.has("id") && tabId.equals(tab.get("id").getAsString())) return tab.getAsJsonArray(list).get(index).getAsJsonObject();
			}
		}
		return null;
	}

	static JsonObject tabOf(JsonObject root, String windowId, String tabId) {
		for (JsonElement w : root.getAsJsonArray("windows")) {
			if (!w.isJsonObject()) continue;
			JsonObject wo = w.getAsJsonObject();
			if (windowId != null && !(wo.has("id") && windowId.equals(wo.get("id").getAsString()))) continue;
			if (!wo.has("tabs")) continue;
			for (JsonElement te : wo.getAsJsonArray("tabs")) {
				JsonObject tab = te.getAsJsonObject();
				if (tab.has("id") && tabId.equals(tab.get("id").getAsString())) return tab;
			}
		}
		return null;
	}

	static String jsonStr(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') sb.append('\\').append(c);
			else if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04x", (int) c));
			else sb.append(c);
		}
		return sb.append('"').toString();
	}

	static String filterJson(String mode, String pattern) {
		return "{\"mode\": " + jsonStr(mode) + ", \"pattern\": " + jsonStr(pattern) + "}";
	}

	static String tabJson(String id, String includes) {
		return "{\"id\": \"" + id + "\", \"name\": \"" + id + "\", \"include\": [" + includes + "]}";
	}

	static String doc(String windows) {
		return "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
				+ "\"appearance\": {\"backgroundOpacity\": null, \"textOpacity\": null, \"scale\": null, \"lineSpacing\": null},\n"
				+ "\"windows\": [" + windows + "]\n}\n";
	}

	static String window(String id, String tabs) {
		return "{\"id\": \"" + id + "\", \"enabled\": true, \"anchor\": \"TOP_LEFT\", \"x\": 0.6, \"y\": 0.05, \"width\": 0.35, \"height\": 0.3, \"tabs\": [" + tabs + "]}";
	}

	long tokInTab(String tab, String token) {
		return t.tabEntries(tab).stream().filter(r -> r.original() != null && r.original().getString().contains(token)).count();
	}

	static Map<String, Object> row(Object... kv) { return AcceptanceTest.row(kv); }

	// =================================================================== config (in-session)

	void config() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			firstStartAndDefaults();
			loadAndRoute(sp);
			v25c(sp);
			v25d();
			v25f();
			v42(sp);
			v21cDuplicates();
			v21d();
			v63();
			v21b();
			v61();
			v60();
			v21e();
			v62();
			noOrphanRestoration();
			noMessageDataInFile();
		}
		t.waitT(20);
	}

	void firstStartAndDefaults() {
		// File created by the first start of this run directory (structural_base was the first launch with Extended active).
		boolean existsNow = Files.exists(cfg());
		cleanConfigFiles();
		restart();
		boolean createdOnStart = Files.exists(cfg());
		JsonObject j = createdOnStart ? json(cfg()) : null;
		boolean defaults = j != null && j.get("schemaVersion").getAsInt() == 1 && j.getAsJsonArray("windows").isEmpty();
		ev.result("V-61f.missingAtStartup", existsNow && createdOnStart && defaults && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL,
				"fileExistedFromEarlierLaunch", existsNow, "createdOnStart", createdOnStart, "defaults", defaults);
	}

	void loadAndRoute(TestSingleplayerContext sp) {
		write(cfg(), doc(window("w-a", tabJson("staff", filterJson("literal", "[SC]")) + ","
				+ "{\"id\": \"trade\", \"name\": \"Trade\", \"include\": [" + filterJson("literal", "[Trade]") + "], \"exclude\": [" + filterJson("literal", "[bot]") + "]}")
				+ "," + window("w-b", "{\"id\": \"all\", \"name\": \"All\", \"matchAll\": true}")));
		restart();
		t.sys(sp, "[SC] hello cpx#P2-1");
		t.sys(sp, "[Trade] wts cpx#P2-2");
		t.sys(sp, "[Trade] [bot] spam cpx#P2-3");
		t.waitT(10);
		boolean ok = tokInTab("staff", "cpx#P2-1") == 1 && tokInTab("trade", "cpx#P2-2") == 1 && tokInTab("trade", "cpx#P2-3") == 0
				&& tokInTab("all", "cpx#P2-1") == 1 && tokInTab("all", "cpx#P2-3") == 1 && tokInTab("staff", "cpx#P2-2") == 0;
		ev.result("P2.loadAndRoute", ok && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL, "windows",
				onClient(() -> mgr().config().windows().size()), "routingTabs", onClient(() -> Router.get().config().tabs().size()));
	}

	void v25c(TestSingleplayerContext sp) {
		String p1025 = "x".repeat(1025);
		write(cfg(), doc(window("w-c", tabJson("t-empty", filterJson("literal", "")) + "," + tabJson("t-glob", filterJson("glob", "a*"))
				+ "," + tabJson("t-long", filterJson("literal", p1025)) + "," + tabJson("t-regex", filterJson("regex", "^\\[SC\\]"))
				+ "," + tabJson("t-ok", filterJson("literal", "cpx#P2C")))));
		restart();
		Map<String, List<String>> errs = configErrors();
		t.sys(sp, "valid tab still works cpx#P2C-1");
		t.waitT(8);
		boolean flagged = errs.containsKey("t-empty") && errs.containsKey("t-glob") && errs.containsKey("t-long") && errs.containsKey("t-regex") && !errs.containsKey("t-ok");
		boolean otherWorks = tokInTab("t-ok", "cpx#P2C-1") == 1;
		// Unrelated edit, then save: the stored invalid values must come back exactly.
		edit(new ConfigEdit.SetGeometry("w-c", 0.5, 0.1, 0.3, 0.3));
		ConfigManager.SaveOutcome s = save();
		JsonObject saved = json(cfg());
		boolean exact = filterOf(saved, "t-empty", "include", 0).get("pattern").getAsString().isEmpty()
				&& filterOf(saved, "t-glob", "include", 0).get("mode").getAsString().equals("glob")
				&& filterOf(saved, "t-long", "include", 0).get("pattern").getAsString().equals(p1025)
				&& filterOf(saved, "t-regex", "include", 0).get("mode").getAsString().equals("regex")
				&& filterOf(saved, "t-regex", "include", 0).get("pattern").getAsString().equals("^\\[SC\\]");
		restart();
		boolean stillSuspended = configErrors().keySet().containsAll(List.of("t-empty", "t-glob", "t-long", "t-regex"));
		ev.result("V-25c", flagged && otherWorks && s == ConfigManager.SaveOutcome.SAVED && exact && stillSuspended, "configErrors", errs,
				"otherTabWorks", otherWorks, "save", s, "storedValuesExact", exact, "suspendedAfterReload", stillSuspended);
	}

	void v25d() {
		String emoji = "\uD83D\uDE00";
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("bmp1024", "a".repeat(1024));
		cases.put("bmp1023emoji", "a".repeat(1023) + emoji);
		cases.put("bmp1022emoji", "a".repeat(1022) + emoji);
		cases.put("combining1024", "e\u0301".repeat(512));
		cases.put("combining1026", "e\u0301".repeat(513));
		cases.put("supplementary1024", "\uD835\uDC00".repeat(512));
		cases.put("supplementary1026", "\uD835\uDC00".repeat(513));
		StringBuilder tabs = new StringBuilder();
		for (var e : cases.entrySet()) tabs.append(tabs.isEmpty() ? "" : ",").append(tabJson("d-" + e.getKey(), filterJson("literal", e.getValue())));
		write(cfg(), doc(window("w-d", tabs.toString())));
		restart();
		Map<String, List<String>> errs = configErrors();
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean ok = true;
		for (var e : cases.entrySet()) {
			boolean expectedValid = e.getValue().length() <= 1024;
			boolean fileValid = !errs.containsKey("d-" + e.getKey());
			boolean editValid = PatternValidator.validate(FilterSpec.literal(e.getValue())) == null;
			ConfigManager.EditOutcome add = edit(new ConfigEdit.AddFilter("d-bmp1024", "exclude", FilterSpec.literal(e.getValue())));
			boolean editApiValid = add.applied();
			if (add.applied()) edit(new ConfigEdit.DeleteFilter("d-bmp1024", "exclude", 0));
			boolean rowOk = fileValid == expectedValid && editValid == expectedValid && editApiValid == expectedValid;
			ok &= rowOk;
			rows.add(row("case", e.getKey(), "utf16Units", e.getValue().length(), "expectedValid", expectedValid, "fileVerdict", fileValid,
					"editVerdict", editApiValid, "ok", rowOk));
		}
		ev.result("V-25d", ok, "rows", rows, "note", "GUI field verdicts are Phase 4; the edit API (what the GUI will call) is checked here");
	}

	void v25f() {
		String sect = "\u00A7a", control = "a\tb", lone = "x\uD83Dy";
		write(cfg(), doc(window("w-f", tabJson("f-sect", filterJson("literal", sect)) + "," + tabJson("f-ctl", filterJson("literal", control))
				+ "," + tabJson("f-lone", filterJson("literal", lone)))));
		restart();
		boolean loadedExact = onClient(() -> {
			ExtendedConfig c = mgr().config();
			return c.windows().get(0).tabs().get(0).config().include().get(0).pattern().equals(sect)
					&& c.windows().get(0).tabs().get(1).config().include().get(0).pattern().equals(control)
					&& c.windows().get(0).tabs().get(2).config().include().get(0).pattern().equals(lone);
		});
		t.local("message with \u00A7a code cpx#F25-1");
		t.local("message with a\tb control cpx#F25-2");
		t.local("message with x\uD83Dy lone cpx#F25-3");
		t.local("message with x\uD83D\uDE00y pair cpx#F25-4");
		t.local("message with plain a b cpx#F25-5");
		t.waitT(5);
		boolean matching = tokInTab("f-sect", "cpx#F25-1") == 1 && tokInTab("f-ctl", "cpx#F25-2") == 1 && tokInTab("f-lone", "cpx#F25-3") == 1
				&& tokInTab("f-lone", "cpx#F25-4") == 0 && tokInTab("f-ctl", "cpx#F25-5") == 0 && tokInTab("f-sect", "cpx#F25-5") == 0;
		// Chat Patches itself can't write a log containing a lone surrogate (UnmappableCharacterException in its save);
		// remove that synthetic message through Chat Patches so its log keeps saving for the rest of the run.
		ctx.runOnClient(c -> {
			for (var g : AcceptanceTest.allMessagesReflective(c.gui.getChat())) {
				if (g.content().getString().contains("cpx#F25-3")) { obro1961.chatpatches.util.ChatUtil.deleteMessage(g, false); break; }
			}
		});
		// "Edit another part of the same pattern": replace it with old + suffix through the edit API, then save.
		ConfigManager.EditOutcome e1 = edit(new ConfigEdit.SetFilter("f-lone", "include", 0, FilterSpec.literal(lone + "!")));
		ConfigManager.EditOutcome e2 = edit(new ConfigEdit.RenameTab("f-sect", "renamed"));
		JsonObject saved = json(cfg());
		String raw = new String(bytes(cfg()), StandardCharsets.UTF_8);
		boolean savedExact = filterOf(saved, "f-sect", "include", 0).get("pattern").getAsString().equals(sect)
				&& filterOf(saved, "f-ctl", "include", 0).get("pattern").getAsString().equals(control)
				&& filterOf(saved, "f-lone", "include", 0).get("pattern").getAsString().equals(lone + "!")
				&& raw.contains("\\uD83D") && !raw.contains("?y!");
		ev.result("V-25f", loadedExact && matching && e1.applied() && e2.applied() && savedExact, "loadedExact", loadedExact,
				"unitByUnitMatching", matching, "savedExact", savedExact, "loneSurrogateWrittenAsEscape", raw.contains("\\uD83D"),
				"note", "client-local messages (the network can't carry a lone surrogate); GUI display is Phase 4");
	}

	void v42(TestSingleplayerContext sp) {
		write(cfg(), doc(window("w-r", tabJson("r-catastrophic", filterJson("regex", "(a+)+$")) + "," + tabJson("r-valid", filterJson("regex", "\\[SC\\]"))
				+ "," + tabJson("r-ok", filterJson("literal", "cpx#R42")))));
		restart();
		long t0 = System.nanoTime();
		t.sys(sp, "a".repeat(40) + "! cpx#R42-1");
		t.sys(sp, "[SC] regex-looking cpx#R42-2");
		t.waitT(8);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		Map<String, List<String>> errs = configErrors();
		boolean ok = errs.getOrDefault("r-catastrophic", List.of()).toString().contains("regex_unsupported")
				&& errs.getOrDefault("r-valid", List.of()).toString().contains("regex_unsupported")
				&& t.tabEntries("r-catastrophic").isEmpty() && t.tabEntries("r-valid").isEmpty() && tokInTab("r-ok", "cpx#R42-1") == 1;
		ev.result("V-42", ok, "configErrors", errs, "elapsedMsIncludingTicks", ms,
				"note", "regex filters are never compiled: the only matcher in the build is LiteralMatcher (no java.util.regex use in routing)");
	}

	void v21cDuplicates() {
		String text = doc(window("w-dup", tabJson("dup", filterJson("literal", "cpx#DUP1")) + "," + tabJson("dup", filterJson("literal", "cpx#DUP2")))
				+ "," + "{\"id\": \"w-dup\", \"tabs\": [" + tabJson("other", filterJson("literal", "x")) + "]}"
				+ "," + "{\"id\": \"w-sel\", \"selectedTab\": \"nope\", \"tabs\": [" + tabJson("first", filterJson("literal", "y")) + "," + tabJson("second", filterJson("literal", "z")) + "]}");
		write(cfg(), text);
		byte[] before = bytes(cfg());
		restart();
		t.waitT(10);
		byte[] after = bytes(cfg());
		Map<String, Object> st = onClient(() -> {
			ExtendedConfig c = mgr().config();
			ExtendedConfig.Window sel = c.window("w-sel");
			return row("routingTabs", Router.get().config().tabs().stream().map(x -> x.id() + (x.suspended() ? "(suspended:" + x.errors() + ")" : "")).toList(),
					"secondWindowSuspended", c.windows().get(1).suspended(), "shownTab", sel == null || sel.shownTab() == null ? null : sel.shownTab().id(),
					"issues", c.issues());
		});
		String routing = String.valueOf(st.get("routingTabs"));
		boolean ok = routing.contains("dup(suspended:[duplicate_tab_id])") && Boolean.TRUE.equals(st.get("secondWindowSuspended"))
				&& "first".equals(st.get("shownTab")) && Arrays.equals(before, after) && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
		ev.result("V-21c.c", ok, "state", st, "fileUnchanged", Arrays.equals(before, after));
	}

	void v21d() {
		String extra = "{\"mode\": \"regex\", \"pattern\": \"(a|b\", \"note\": \"keep me\", \"weight\": 3}";
		write(cfg(), doc(window("w-1", "{\"id\": \"inv\", \"name\": \"Invalid\", \"include\": [" + extra + ", " + filterJson("glob", "*") + "]},"
				+ tabJson("other", filterJson("literal", "cpx#OTHER"))) + "," + window("w-2", tabJson("t2", filterJson("literal", "q")))));
		restart();
		List<String> outcomes = new ArrayList<>();
		outcomes.add("geometry:" + edit(new ConfigEdit.SetGeometry("w-1", 0.2, 0.2, 0.3, 0.3)).edit().outcome());
		outcomes.add("front:" + edit(new ConfigEdit.BringToFront("w-1")).edit().outcome());
		outcomes.add("renameOther:" + edit(new ConfigEdit.RenameTab("other", "Other!")).edit().outcome());
		outcomes.add("moveInvalid:" + edit(new ConfigEdit.MoveTab("inv", "w-2")).edit().outcome());
		ConfigManager.SaveOutcome s = save();
		JsonObject saved = json(cfg());
		JsonObject inv = tabOf(saved, "w-2", "inv");
		boolean preserved = inv != null && inv.getAsJsonArray("include").get(0).getAsJsonObject().equals(JsonParser.parseString(extra).getAsJsonObject())
				&& inv.getAsJsonArray("include").get(1).getAsJsonObject().get("mode").getAsString().equals("glob");
		restart();
		boolean stillSuspended = configErrors().containsKey("inv");
		ConfigManager.EditOutcome stillInvalid = edit(new ConfigEdit.SetFilter("inv", "include", 0, new FilterSpec("regex", "(a|b)", false)));
		ConfigManager.EditOutcome addEmpty = edit(new ConfigEdit.AddFilter("other", "include", FilterSpec.literal("")));
		ConfigManager.EditOutcome fixSecond = edit(new ConfigEdit.SetFilter("inv", "include", 1, FilterSpec.literal("fixed")));
		ConfigManager.EditOutcome deleteFirst = edit(new ConfigEdit.DeleteFilter("inv", "include", 0));
		boolean unsuspended = !configErrors().containsKey("inv");
		JsonObject afterFix = json(cfg());
		boolean fixSaved = tabOf(afterFix, "w-2", "inv").getAsJsonArray("include").size() == 1
				&& tabOf(afterFix, "w-2", "inv").getAsJsonArray("include").get(0).getAsJsonObject().get("pattern").getAsString().equals("fixed");
		// Non-debounced edits save as they happen; the explicit save then has nothing left (or saves the debounced rest).
		boolean savedAll = s == ConfigManager.SaveOutcome.SAVED || s == ConfigManager.SaveOutcome.NOTHING_TO_SAVE;
		boolean ok = outcomes.stream().allMatch(o -> o.endsWith("APPLIED")) && savedAll && preserved && stillSuspended
				&& !stillInvalid.applied() && !addEmpty.applied() && fixSecond.applied() && deleteFirst.applied() && unsuspended && fixSaved;
		ev.result("V-21d", ok, "unrelatedEdits", outcomes, "save", s, "untouchedInvalidFilterWrittenBackUnchanged", preserved,
				"suspendedAfterReload", stillSuspended, "editStillInvalid", stillInvalid.edit(), "addEmptyFilter", addEmpty.edit(),
				"fixApplied", fixSecond.applied(), "deleteApplied", deleteFirst.applied(), "unsuspendedAfterFix", unsuspended, "fixSaved", fixSaved,
				"note", "edits via the model API the Phase 3/4 GUI will call; inline GUI errors are Phase 4");
	}

	void v63() {
		String text = "{\n\"schemaVersion\": 1,\n\"topUnknown\": {\"nested\": [1, 2.50, {\"x\": null}], \"s\": \"\\u00e9\"},\n"
				+ "\"general\": {\"enabled\": true, \"hidingEnabled\": true, \"generalUnknown\": 7},\n"
				+ "\"appearance\": {\"backgroundOpacity\": null, \"textOpacity\": null, \"scale\": null, \"lineSpacing\": null},\n"
				+ "\"windows\": [\n"
				+ "{\"id\": \"u-1\", \"windowUnknown\": [\"a\", {\"b\": false}], \"x\": 0.1, \"y\": 0.1, \"width\": 0.3, \"height\": 0.3, \"tabs\": [\n"
				+ "  {\"id\": \"mover\", \"name\": \"Mover\", \"tabUnknown\": {\"deep\": [true]}, \"include\": [{\"mode\": \"literal\", \"pattern\": \"m\", \"filterUnknown\": \"f\"}]},\n"
				+ "  {\"id\": \"bad\", \"include\": [{\"mode\": \"regex\", \"pattern\": \"[\"}]},\n"
				+ "  {\"id\": \"mover\", \"name\": \"Duplicate\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"dupe\"}]},\n"
				+ "  {\"id\": \"plain\", \"name\": \"Plain\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"p\"}]}\n"
				+ "]},\n"
				+ "{\"id\": \"u-2\", \"tabs\": []},\n"
				+ "42,\n"
				+ "{\"weird\": true, \"tabs\": \"not an array\"}\n"
				+ "]\n}\n";
		write(cfg(), text);
		JsonObject original = JsonParser.parseString(text).getAsJsonObject();
		restart();
		edit(new ConfigEdit.SetGeometry("u-1", 0.15, 0.12, 0.31, 0.32));
		edit(new ConfigEdit.RenameTab("plain", "Plain renamed"));
		edit(new ConfigEdit.MoveTab("mover", "u-2"));
		ConfigManager.SaveOutcome s = save();
		boolean dirtyAfter = onClient(() -> mgr().dirty());
		JsonObject saved = json(cfg());
		JsonArray ws = saved.getAsJsonArray("windows");
		JsonObject u1 = ws.get(0).getAsJsonObject(), u2 = ws.get(1).getAsJsonObject();
		JsonObject moved = tabOf(saved, "u-2", "mover");
		JsonArray origTabs = original.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("tabs");
		Map<String, Object> checks = new LinkedHashMap<>();
		checks.put("topUnknown", saved.get("topUnknown").equals(original.get("topUnknown")));
		checks.put("generalUnknown", saved.getAsJsonObject("general").get("generalUnknown").equals(original.getAsJsonObject("general").get("generalUnknown")));
		checks.put("windowUnknown", u1.get("windowUnknown").equals(original.getAsJsonArray("windows").get(0).getAsJsonObject().get("windowUnknown")));
		checks.put("movedTabCarriesUnknownKeys", moved != null && moved.get("tabUnknown").equals(origTabs.get(0).getAsJsonObject().get("tabUnknown"))
				&& moved.getAsJsonArray("include").get(0).getAsJsonObject().get("filterUnknown").getAsString().equals("f"));
		checks.put("invalidUnchanged", tabOf(saved, "u-1", "bad").equals(origTabs.get(1).getAsJsonObject()));
		checks.put("duplicateUnchanged", u1.getAsJsonArray("tabs").get(1).getAsJsonObject().equals(origTabs.get(2).getAsJsonObject()));
		checks.put("nonObjectWindowKept", ws.get(2).equals(original.getAsJsonArray("windows").get(2)));
		checks.put("unrecognizedWindowKept", ws.get(3).equals(original.getAsJsonArray("windows").get(3)));
		checks.put("changedOnlyIntended", u1.get("x").getAsDouble() == 0.15 && tabOf(saved, "u-1", "plain").get("name").getAsString().equals("Plain renamed")
				&& u2.getAsJsonArray("tabs").size() == 1);
		restart();
		Map<String, List<String>> errs = configErrors();
		checks.put("invalidStillSuspended", errs.containsKey("bad"));
		checks.put("duplicateStillSuspended", onClient(() -> Router.get().config().tabs().stream().anyMatch(x -> x.id().equals("mover") && x.suspended())));
		checks.put("noMessageOrUnreadData", noMessageData(saved));
		boolean ok = (s == ConfigManager.SaveOutcome.SAVED || s == ConfigManager.SaveOutcome.NOTHING_TO_SAVE) && !dirtyAfter
				&& checks.values().stream().allMatch(Boolean.TRUE::equals);
		ev.result("V-63", ok, "save", s, "dirtyAfterSave", dirtyAfter, "checks", checks, "note", "formatting may differ (pretty-printed); unrelated changes made through the edit API");
	}

	static boolean noMessageData(JsonObject saved) {
		String s = saved.toString();
		return !s.contains("cpx#") && !s.contains("\"messages\"") && !s.contains("\"unread\"") && !s.contains("\"hidden\"") && !s.contains("\"history\"");
	}

	void v21b() {
		write(cfg(), doc(window("b-1", tabJson("b-tab", filterJson("literal", "cpx#B")))));
		restart();
		// (1) external edit while an automatic (debounced) save is pending
		edit(new ConfigEdit.SetGeometry("b-1", 0.33, 0.22, 0.3, 0.3));
		JsonObject j = json(cfg());
		tabOf(j, "b-1", "b-tab").addProperty("name", "Hand edited");
		write(cfg(), j.toString());
		t.waitT(30); // debounce elapses -> save attempt -> content check detects the hand edit
		JsonObject after = json(cfg());
		boolean both = "Hand edited".equals(tabOf(after, "b-1", "b-tab").get("name").getAsString())
				&& after.getAsJsonArray("windows").get(0).getAsJsonObject().get("x").getAsDouble() == 0.33;
		boolean appliedInMemory = onClient(() -> "Hand edited".equals(mgr().config().windows().get(0).tabs().get(0).config().name()));
		// (2) the check the config screen will run when it opens (Phase 4): applies without saving
		JsonObject j2 = json(cfg());
		tabOf(j2, "b-1", "b-tab").addProperty("name", "Second edit");
		write(cfg(), j2.toString());
		byte[] before = bytes(cfg());
		ConfigManager.SaveOutcome check = onClient(() -> mgr().checkForExternalChanges());
		boolean checkApplied = onClient(() -> "Second edit".equals(mgr().config().windows().get(0).tabs().get(0).config().name()));
		ev.result("V-21b", both && appliedInMemory && check == ConfigManager.SaveOutcome.APPLIED_EXTERNAL_EDIT && checkApplied && Arrays.equals(before, bytes(cfg())),
				"handEditKeptAndPendingGeometryReapplied", both, "appliedInMemory", appliedInMemory, "screenOpenCheck", check, "checkDidNotWrite", Arrays.equals(before, bytes(cfg())),
				"note", "detected by content fingerprint before the automatic save; the config-screen trigger itself is Phase 4");
	}

	void v61() {
		Map<String, Object> rows = new LinkedHashMap<>();
		try {
			// (a) two edits inside one modification-time tick, same length, time restored
			write(cfg(), doc(window("m-1", tabJson("m-tab", filterJson("literal", "AAAA")))));
			restart();
			FileTime mt = Files.getLastModifiedTime(cfg());
			write(cfg(), doc(window("m-1", tabJson("m-tab", filterJson("literal", "BBBB")))));
			write(cfg(), doc(window("m-1", tabJson("m-tab", filterJson("literal", "CCCC")))));
			Files.setLastModifiedTime(cfg(), mt);
			ConfigManager.SaveOutcome a = onClient(() -> mgr().checkForExternalChanges());
			rows.put("a.sameTickSameLength", row("outcome", a, "applied", onClient(() -> mgr().config().windows().get(0).tabs().get(0).config().include().get(0).pattern())));
			// (b) edit, then restore the original modification time (touch -r)
			FileTime mt2 = Files.getLastModifiedTime(cfg());
			write(cfg(), doc(window("m-1", tabJson("m-tab", filterJson("literal", "DDDD")))));
			Files.setLastModifiedTime(cfg(), mt2);
			ConfigManager.SaveOutcome b = onClient(() -> mgr().checkForExternalChanges());
			rows.put("b.mtimeRestored", row("outcome", b, "applied", onClient(() -> mgr().config().windows().get(0).tabs().get(0).config().include().get(0).pattern())));
			// (c) an editor's atomic replace
			Path tmp = cfgDir().resolve("editor-tmp.json");
			write(tmp, doc(window("m-1", tabJson("m-tab", filterJson("literal", "EEEE")))));
			Files.move(tmp, cfg(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			ConfigManager.SaveOutcome c = onClient(() -> mgr().checkForExternalChanges());
			rows.put("c.atomicReplace", row("outcome", c, "applied", onClient(() -> mgr().config().windows().get(0).tabs().get(0).config().include().get(0).pattern())));
			// (e) unreadable: save blocked, no lock; retried later
			Files.setPosixFilePermissions(cfg(), PosixFilePermissions.fromString("---------"));
			ConfigManager.EditOutcome eEdit = edit(new ConfigEdit.RenameTab("m-tab", "while unreadable"));
			ConfigManager.SaveOutcome e = eEdit.save();
			ConfigManager.State eState = onClient(() -> mgr().state());
			Files.setPosixFilePermissions(cfg(), PosixFilePermissions.fromString("rw-r--r--"));
			ConfigManager.SaveOutcome eRetry = save();
			rows.put("e.unreadable", row("outcome", e, "stateWhileUnreadable", eState, "retryAfterReadable", eRetry,
					"saved", "while unreadable".equals(tabOf(json(cfg()), "m-1", "m-tab").get("name").getAsString())));
			// (d) deleted mid-session -> divergence lock; Apply adopts defaults; separately Overwrite recreates
			Files.delete(cfg());
			edit(new ConfigEdit.RenameTab("m-tab", "after delete"));
			ConfigManager.SaveOutcome d = save();
			ConfigManager.State dState = onClient(() -> mgr().state());
			ConfigManager.ActionResult over = onClient(() -> mgr().overwriteFile(true));
			boolean recreated = Files.exists(cfg()) && "after delete".equals(tabOf(json(cfg()), "m-1", "m-tab").get("name").getAsString());
			Files.delete(cfg());
			save();
			ConfigManager.ActionResult apply = onClient(() -> mgr().applyFile());
			boolean defaultsAdopted = onClient(() -> mgr().config().windows().isEmpty() && mgr().state() == ConfigManager.State.NORMAL);
			ConfigManager.SaveOutcome recreate = save();
			rows.put("d.deleted", row("saveOutcome", d, "state", dState, "overwrite", over, "recreated", recreated, "apply", apply,
					"defaultsAdopted", defaultsAdopted, "nextSave", recreate, "fileBack", Files.exists(cfg())));
			boolean ok = String.valueOf(rows.get("a.sameTickSameLength")).contains("CCCC") && String.valueOf(rows.get("b.mtimeRestored")).contains("DDDD")
					&& String.valueOf(rows.get("c.atomicReplace")).contains("EEEE")
					&& e == ConfigManager.SaveOutcome.BLOCKED_UNREADABLE && eState == ConfigManager.State.NORMAL && eRetry == ConfigManager.SaveOutcome.SAVED
					&& d == ConfigManager.SaveOutcome.BLOCKED_DIVERGENCE && dState == ConfigManager.State.DIVERGENCE && over.ok() && recreated
					&& apply.ok() && defaultsAdopted && recreate == ConfigManager.SaveOutcome.SAVED && Files.exists(cfg());
			ev.result("V-61", ok, "cases", rows, "note", "(f) is V-61f.missingAtStartup");
		} catch (IOException ex) {
			ev.result("V-61", Evidence.Outcome.INCONCLUSIVE, "error", ex.toString(), "cases", rows);
		}
	}

	void v60() {
		write(cfg(), doc(window("l-1", tabJson("keep", filterJson("literal", "cpx#KEEP")) + "," + tabJson("other", filterJson("literal", "o")))));
		restart();
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden("keep"));
		JsonObject j = json(cfg());
		JsonArray tabs = j.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("tabs");
		tabs.remove(0); // hand-edit: delete the tab that holds hidden messages
		write(cfg(), j.toString());
		byte[] external = bytes(cfg());
		ConfigManager.SaveOutcome manual = save();
		edit(new ConfigEdit.SetGeometry("l-1", 0.4, 0.4, 0.3, 0.3));
		t.waitT(30); // automatic (debounced) save attempt
		ConfigManager.SaveOutcome auto = onClient(() -> mgr().lastSave());
		boolean filePreserved = Arrays.equals(external, bytes(cfg()));
		boolean memoryKept = onClient(() -> mgr().config().windows().get(0).tabs().stream().anyMatch(x -> "keep".equals(x.id())));
		ConfigManager.ActionResult refusedApply = onClient(() -> mgr().applyFile());
		boolean stillLocked = onClient(() -> mgr().state()) == ConfigManager.State.DIVERGENCE && Arrays.equals(external, bytes(cfg()))
				&& onClient(() -> mgr().config().windows().get(0).tabs().stream().anyMatch(x -> "keep".equals(x.id())));
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden(null)); // conditions fixed: no hidden messages any more
		ConfigManager.ActionResult apply = onClient(() -> mgr().applyFile());
		boolean applied = onClient(() -> mgr().state() == ConfigManager.State.NORMAL && mgr().config().windows().get(0).tabs().stream().noneMatch(x -> "keep".equals(x.id())));
		// Separately: Overwrite file with current settings
		write(cfg(), "{ not json");
		edit(new ConfigEdit.RenameTab("other", "mine"));
		ConfigManager.State locked2 = onClient(() -> mgr().state());
		ConfigManager.ActionResult noConfirm = onClient(() -> mgr().overwriteFile(false));
		ConfigManager.ActionResult over = onClient(() -> mgr().overwriteFile(true));
		boolean overwritten = onClient(() -> mgr().state()) == ConfigManager.State.NORMAL && "mine".equals(tabOf(json(cfg()), "l-1", "other").get("name").getAsString());
		int toasts = onClient(() -> (int) Warnings.counts().keySet().stream().filter(k -> k.startsWith("config:DIVERGENCE")).count());
		boolean ok = manual == ConfigManager.SaveOutcome.BLOCKED_DIVERGENCE && auto == ConfigManager.SaveOutcome.BLOCKED_DIVERGENCE && filePreserved && memoryKept
				&& !refusedApply.ok() && stillLocked && apply.ok() && applied && locked2 == ConfigManager.State.DIVERGENCE && !noConfirm.ok() && over.ok() && overwritten;
		ev.result("V-60", ok, "manualSave", manual, "automaticSave", auto, "externalFilePreserved", filePreserved, "memoryUnchanged", memoryKept,
				"refusedApply", refusedApply, "lockUnchangedAfterRefusal", stillLocked, "applyAfterFix", apply, "applied", applied,
				"overwriteWithoutConfirmation", noConfirm, "overwrite", over, "overwritten", overwritten, "divergenceWarnings", toasts,
				"note", "hidden messages simulated with a development hook (hiding is off in Phase 2); P5 commit not implemented, so orphaning Apply is refused, never approximated; banner and lock icon are Phase 3/4");
	}

	void v21e() {
		write(cfg(), doc(window("e-1", tabJson("e-tab", filterJson("literal", "cpx#E")))));
		restart();
		mgr().storage().devSetNoAtomicMove(true);
		try {
			byte[] beforeSave = bytes(cfg());
			ConfigManager.EditOutcome g = edit(new ConfigEdit.RenameTab("e-tab", "fallback save"));
			boolean bakIsPrevious = Arrays.equals(beforeSave, bytes(bak()));
			boolean primaryNew = "fallback save".equals(tabOf(json(cfg()), "e-1", "e-tab").get("name").getAsString());
			// The fallback runs the content check first.
			write(cfg(), doc(window("e-1", tabJson("e-tab", filterJson("literal", "external")))));
			ConfigManager.EditOutcome g2 = edit(new ConfigEdit.RenameTab("e-tab", "after external"));
			boolean externalSeen = onClient(() -> mgr().config().windows().get(0).tabs().get(0).config().include().get(0).pattern().equals("external"));
			// (c) transient lock: one retry, then "not saved", model stays dirty
			mgr().storage().devSetTransientFailures(2);
			ConfigManager.EditOutcome c1 = edit(new ConfigEdit.RenameTab("e-tab", "locked"));
			boolean dirty = onClient(() -> mgr().dirty());
			mgr().storage().devSetTransientFailures(1);
			ConfigManager.SaveOutcome c2 = save();
			// (a) corrupt between saves; (b) process killed mid-write (simulated: the new content truncated in place)
			byte[] goodBak = bytes(bak());
			write(cfg(), "{\"schemaVersion\": 1, \"windows\": [ {\"id\": \"e-1\", \"tab");
			byte[] corrupt = bytes(cfg());
			restart();
			Map<String, Object> a = onClient(() -> row("state", mgr().state(), "runningOnBackup", mgr().runningOnBackup(),
					"corruptCopy", mgr().corruptCopy() == null ? null : mgr().corruptCopy().getFileName().toString(), "detail", mgr().stateDetail()));
			boolean originalKept = Arrays.equals(corrupt, bytes(cfg()));
			boolean copyMatches = !corruptCopies().isEmpty() && Arrays.equals(corrupt, bytes(corruptCopies().get(corruptCopies().size() - 1)));
			ConfigManager.SaveOutcome blocked = save();
			ConfigManager.ActionResult restore = onClient(() -> mgr().restoreFromBackup(true));
			boolean restored = Arrays.equals(goodBak, bytes(cfg())) && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
			boolean ok = g.applied() && bakIsPrevious && primaryNew && g2.applied() && externalSeen && c1.save() == ConfigManager.SaveOutcome.NOT_SAVED && dirty
					&& c2 == ConfigManager.SaveOutcome.SAVED && a.get("state") == ConfigManager.State.NO_SAVE && Boolean.TRUE.equals(a.get("runningOnBackup"))
					&& originalKept && copyMatches && blocked == ConfigManager.SaveOutcome.BLOCKED_NO_SAVE && restore.ok() && restored;
			ev.result("V-21e", ok, "fallbackSave", g.save(), "bakHoldsPreviousVersion", bakIsPrevious, "contentCheckBeforeFallback", externalSeen,
					"transientTwice", c1.save(), "dirtyAfterNotSaved", dirty, "transientOnceThenRetry", c2, "corruptAtLoad", a, "originalPreserved", originalKept,
					"corruptCopyMatches", copyMatches, "saveWhileNoSave", blocked, "restoreFromBackup", restore, "restored", restored,
					"note", "atomic-move failure simulated with a development hook; (b) simulated by truncating the file in place; corruption also tested by relaunch (config_corrupt)");
		} finally {
			mgr().storage().devSetNoAtomicMove(false);
			mgr().storage().devSetTransientFailures(0);
		}
	}

	void v62() {
		write(cfg(), doc(window("r-1", tabJson("r-tab", filterJson("literal", "cpx#R")))));
		restart();
		String external = doc(window("r-1", tabJson("r-tab", filterJson("literal", "EXTERNAL")) ));
		// (1) external write just before the final check: detected, adopted (valid), then our edit is re-applied and saved
		mgr().storage().devSetBeforeFinalCheck(() -> write(cfg(), external));
		ConfigManager.EditOutcome e1 = edit(new ConfigEdit.RenameTab("r-tab", "ours-1"));
		mgr().storage().devSetBeforeFinalCheck(null);
		JsonObject s1 = json(cfg());
		boolean beforeDetected = "EXTERNAL".equals(filterOf(s1, "r-tab", "include", 0).get("pattern").getAsString())
				&& "ours-1".equals(tabOf(s1, "r-1", "r-tab").get("name").getAsString());
		// (2) external write inside the check-to-replace window: expected to be overwritten (residual race)
		String external2 = doc(window("r-1", tabJson("r-tab", filterJson("literal", "INSIDE-WINDOW"))));
		mgr().storage().devSetBetweenCheckAndReplace(() -> write(cfg(), external2));
		ConfigManager.EditOutcome e2 = edit(new ConfigEdit.RenameTab("r-tab", "ours-2"));
		mgr().storage().devSetBetweenCheckAndReplace(null);
		boolean insideOverwritten = !new String(bytes(cfg()), StandardCharsets.UTF_8).contains("INSIDE-WINDOW");
		long windowNanos = mgr().storage().lastCheckToReplaceNanos();
		// timing without a hook in the window
		edit(new ConfigEdit.RenameTab("r-tab", "ours-3"));
		long cleanWindowNanos = mgr().storage().lastCheckToReplaceNanos();
		// (3) external write after the rename: detected at the next save
		String external3 = doc(window("r-1", tabJson("r-tab", filterJson("literal", "AFTER"))));
		write(cfg(), external3);
		ConfigManager.EditOutcome e3 = edit(new ConfigEdit.RenameTab("r-tab", "ours-4"));
		JsonObject s3 = json(cfg());
		boolean afterDetected = "AFTER".equals(filterOf(s3, "r-tab", "include", 0).get("pattern").getAsString())
				&& "ours-4".equals(tabOf(s3, "r-1", "r-tab").get("name").getAsString());
		ev.result("V-62", beforeDetected && afterDetected && insideOverwritten, "writeBeforeFinalCheck", row("detectedAndMerged", beforeDetected, "save", e1.save()),
				"writeInsideCheckToReplaceWindow", row("overwrittenByOurSave", insideOverwritten, "save", e2.save()),
				"checkToReplaceWindowMicros", row("withInjectedWrite", windowNanos / 1000, "clean", cleanWindowNanos / 1000),
				"writeAfterReplace", row("detectedAtNextSave", afterDetected, "save", e3.save()),
				"note", "RESIDUAL RACE REPRODUCED, NOT ELIMINATED: an external write inside the check-to-rename window is overwritten. Results go to the owner.");
	}

	void noOrphanRestoration() {
		write(cfg(), doc(window("o-1", tabJson("o-hidden", filterJson("literal", "h")) + "," + tabJson("o-free", filterJson("literal", "f")))));
		restart();
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden("o-hidden"));
		ConfigManager.EditOutcome del = edit(new ConfigEdit.DeleteTab("o-hidden"));
		ConfigManager.EditOutcome delFree = edit(new ConfigEdit.DeleteTab("o-free"));
		ConfigManager.EditOutcome move = edit(new ConfigEdit.MoveTab("o-hidden", "o-1"));
		boolean kept = onClient(() -> mgr().config().windows().get(0).tabs().stream().anyMatch(x -> "o-hidden".equals(x.id())));
		ctx.runOnClient(c -> OrphanGuard.devMarkHoldingHidden(null));
		ev.result("P2.noOrphanRestoration", !del.applied() && delFree.applied() && move.applied() && kept, "deleteTabHoldingHidden", del.edit(),
				"deleteOtherTab", delFree.edit().outcome(), "tabKept", kept,
				"note", "orphan-producing changes are refused on every path (edit, Apply file in V-60, Restore from backup); nothing is restored to the main chat");
	}

	void noMessageDataInFile() {
		ev.result("P2.onlyConfigurationPersisted", noMessageData(json(cfg())), "note", "no message history, hidden references or unread state in cpextended.json");
	}

	// =================================================================== config_corrupt / config_newer (real relaunches)

	void configCorrupt() {
		byte[] seededPrimary = bytes(cfgDir().resolve("cpx-seed-primary.bin"));
		byte[] seededBak = bytes(cfgDir().resolve("cpx-seed-bak.bin"));
		Map<String, Object> st = onClient(() -> row("state", mgr().state(), "runningOnBackup", mgr().runningOnBackup(), "detail", mgr().stateDetail(),
				"corruptCopy", mgr().corruptCopy() == null ? null : mgr().corruptCopy().getFileName().toString(),
				"windows", mgr().config().windows().stream().map(ExtendedConfig.Window::id).toList(),
				"noSaveWarnings", (int) Warnings.counts().keySet().stream().filter(k -> k.startsWith("config:NO_SAVE")).count()));
		boolean originalUntouched = Arrays.equals(seededPrimary, bytes(cfg()));
		List<Path> copies = corruptCopies();
		boolean copyOk = copies.size() == 1 && Arrays.equals(seededPrimary, bytes(copies.get(0)));
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			t.sys(sp, "routed from the backup cpx#BAK-1");
			t.waitT(8);
			boolean readOnlyRouting = tokInTab("bak-tab", "cpx#BAK-1") == 1;
			ConfigManager.EditOutcome e = edit(new ConfigEdit.RenameTab("bak-tab", "edited while no-save"));
			ConfigManager.SaveOutcome manual = save();
			boolean stillUntouched = Arrays.equals(seededPrimary, bytes(cfg()));
			ConfigManager.ActionResult noConfirm = onClient(() -> mgr().restoreFromBackup(false));
			ConfigManager.ActionResult restore = onClient(() -> mgr().restoreFromBackup(true));
			boolean restored = Arrays.equals(seededBak, bytes(cfg())) && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
			ConfigManager.EditOutcome afterEdit = edit(new ConfigEdit.RenameTab("bak-tab", "saved again"));
			boolean savesWork = afterEdit.save() == ConfigManager.SaveOutcome.SAVED;
			// Repair in place: corrupt again (restart), then fix the file itself
			write(cfg(), "[1, 2");
			restart();
			ConfigManager.State broken = onClient(() -> mgr().state());
			write(cfg(), doc(window("fixed", tabJson("fixed-tab", filterJson("literal", "cpx#FIX")))));
			ConfigManager.SaveOutcome repairCheck = save();
			boolean repaired = onClient(() -> mgr().state() == ConfigManager.State.NORMAL && mgr().config().window("fixed") != null);
			boolean ok = st.get("state") == ConfigManager.State.NO_SAVE && Boolean.TRUE.equals(st.get("runningOnBackup")) && originalUntouched && copyOk
					&& readOnlyRouting && e.applied() && e.save() == ConfigManager.SaveOutcome.BLOCKED_NO_SAVE && manual == ConfigManager.SaveOutcome.BLOCKED_NO_SAVE
					&& stillUntouched && !noConfirm.ok() && restore.ok() && restored && savesWork && broken == ConfigManager.State.NO_SAVE && repaired
					&& Integer.valueOf(1).equals(st.get("noSaveWarnings"));
			ev.result("V-21c.a", ok, "atStartup", st, "originalUntouched", originalUntouched, "corruptCopies", copies.stream().map(p -> p.getFileName().toString()).toList(),
					"corruptCopyMatchesOriginal", copyOk, "readOnlyRoutingFromBak", readOnlyRouting, "editSave", e.save(), "manualSave", manual,
					"restoreWithoutConfirmation", noConfirm, "restoreFromBackup", restore, "restoredBytesEqualBak", restored, "savesAfterRestore", savesWork,
					"repairInPlace", row("stateBefore", broken, "check", repairCheck, "leftNoSave", repaired),
					"note", "real relaunch with seeded files; banner is Phase 4");
		}
		t.waitT(20);
	}

	void configNewer() {
		byte[] seeded = bytes(cfgDir().resolve("cpx-seed-primary.bin"));
		Map<String, Object> st = onClient(() -> row("state", mgr().state(), "detail", mgr().stateDetail(),
				"windows", mgr().config().windows().stream().map(ExtendedConfig.Window::id).toList(), "corruptCopy", String.valueOf(mgr().corruptCopy())));
		boolean untouched = Arrays.equals(seeded, bytes(cfg()));
		boolean noCopy = corruptCopies().isEmpty();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			t.sys(sp, "understood fields route cpx#NEW-1");
			t.waitT(8);
			boolean routes = tokInTab("new-tab", "cpx#NEW-1") == 1;
			ConfigManager.EditOutcome e = edit(new ConfigEdit.SetGeometry("new-w", 0.3, 0.3, 0.3, 0.3));
			t.waitT(30);
			ConfigManager.SaveOutcome auto = onClient(() -> mgr().lastSave());
			ConfigManager.SaveOutcome manual = save();
			boolean stillUntouched = Arrays.equals(seeded, bytes(cfg()));
			ConfigManager.ActionResult replace = onClient(() -> mgr().replaceWithCurrentSettings(true));
			JsonObject now = json(cfg());
			boolean replaced = replace.ok() && now.get("schemaVersion").getAsInt() == 1 && now.has("futureField") && onClient(() -> mgr().state()) == ConfigManager.State.NORMAL;
			boolean ok = st.get("state") == ConfigManager.State.NO_SAVE && untouched && noCopy && routes && e.applied()
					&& auto == ConfigManager.SaveOutcome.BLOCKED_NO_SAVE && manual == ConfigManager.SaveOutcome.BLOCKED_NO_SAVE && stillUntouched && replaced;
			ev.result("V-21c.b", ok, "atStartup", st, "originalUntouched", untouched, "noCorruptCopy", noCopy, "understoodFieldsRoute", routes,
					"automaticSave", auto, "manualSave", manual, "stillUntouched", stillUntouched, "replaceWithCurrentSettings", replace, "replaced", replaced,
					"note", "real relaunch with a seeded schemaVersion 99 file; explicit replacement writes schemaVersion 1 and keeps unknown keys");
		}
		t.waitT(20);
	}

	// =================================================================== hud (read-only windows)

	static final int MAGENTA = 0xFF00FF;

	void hud() throws Exception {
		write(cfg(), "{\n\"schemaVersion\": 1,\n\"general\": {\"enabled\": true, \"hidingEnabled\": true},\n"
				+ "\"appearance\": {\"backgroundOpacity\": 1.0, \"textOpacity\": 1.0, \"scale\": 1.0, \"lineSpacing\": 0.0},\n"
				+ "\"windows\": [\n"
				+ " {\"id\": \"h-main\", \"anchor\": \"TOP_RIGHT\", \"x\": 0.02, \"y\": 0.05, \"width\": 0.40, \"height\": 0.30, \"tabs\": [{\"id\": \"h-tab\", \"name\": \"HUD\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#HUD\"}]}]},\n"
				+ " {\"id\": \"h-off\", \"enabled\": false, \"x\": 0.0, \"y\": 0.0, \"width\": 0.2, \"height\": 0.2, \"tabs\": [{\"id\": \"h-tab2\", \"matchAll\": true}]},\n"
				+ " {\"id\": \"h-clamp\", \"anchor\": \"TOP_LEFT\", \"x\": 1.5, \"y\": -0.5, \"width\": 2.0, \"height\": 0.10, \"tabs\": [{\"id\": \"h-tab3\", \"matchAll\": true}]},\n"
				+ " {\"id\": \"h-overchat\", \"anchor\": \"BOTTOM_LEFT\", \"x\": 0.0, \"y\": 0.0, \"width\": 0.45, \"height\": 0.35, \"tabs\": [{\"id\": \"h-tab4\", \"include\": [{\"mode\": \"literal\", \"pattern\": \"cpx#HUD\"}]}]}\n"
				+ "]\n}\n");
		restart();
		byte[] fileBefore = bytes(cfg());
		Map<String, Object> results = new LinkedHashMap<>();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			t.waitT(40);
			ctx.runOnClient(c -> {
				c.options.hideGui = false;
				c.options.chatVisibility().set(ChatVisiblity.FULL);
			});
			for (int i = 0; i < 6; i++) t.sys(sp, "main chat filler line " + i + " lorem ipsum dolor sit amet");
			for (int i = 0; i < 12; i++) {
				int n = i;
				sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(
						Component.literal("HUDTEXT magenta routed line " + n + " cpx#HUD-" + n).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(MAGENTA))), false));
			}
			t.waitT(10);

			// Visibility (V-39, Phase 2 portion) and duplicate draws (V-55)
			ctx.runOnClient(c -> WindowRenderer.resetCounters());
			t.waitT(10);
			long visibleFrames = WindowRenderer.framesWithDraws();
			Map<String, Integer> lastFrame = WindowRenderer.drawsInLastFrame();
			ctx.runOnClient(c -> { c.options.hideGui = true; WindowRenderer.resetCounters(); });
			t.waitT(10);
			long f1Frames = WindowRenderer.framesWithDraws();
			ctx.runOnClient(c -> { c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.HIDDEN); WindowRenderer.resetCounters(); });
			t.waitT(10);
			long hiddenFrames = WindowRenderer.framesWithDraws();
			ctx.setScreen(() -> new ChatScreen("", false));
			t.waitT(5);
			long hiddenChatOpen = WindowRenderer.framesWithDraws();
			ctx.setScreen(() -> null);
			ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.FULL));
			t.waitT(5);
			boolean disabledNeverDrawn = WindowRenderer.totalDraws("h-off") == 0;
			results.put("V-39", row("gameplayFramesDrawn", visibleFrames, "lastFrameDraws", lastFrame, "f1Frames", f1Frames, "chatHiddenFrames", hiddenFrames,
					"chatHiddenWithChatOpen", hiddenChatOpen, "disabledWindowDraws", WindowRenderer.totalDraws("h-off")));
			ev.result("V-39.phase2", visibleFrames > 0 && lastFrame.getOrDefault("h-main", 0) == 1 && !lastFrame.containsKey("h-off") && f1Frames == 0
							&& hiddenFrames == 0 && hiddenChatOpen == 0 && disabledNeverDrawn, "observed", results.get("V-39"),
					"notApplicable", "chat-open drawing, including under F1 (Q26), is the Phase 3 chat-open path");

			// V-55: 75 transitions; every frame draws the tracked window exactly once when visible, never otherwise
			TestCounters.trackedWindow = "h-main";
			TestCounters.expectedVisible = () -> {
				Minecraft c = Minecraft.getInstance();
				return c.level != null && !c.options.hideGui && c.options.chatVisibility().get() != ChatVisiblity.HIDDEN && !(c.screen instanceof ChatScreen);
			};
			t.waitT(1);
			TestCounters.ANOMALY_SAMPLES.clear();
			int a0 = TestCounters.ANOMALIES.get(), f0 = TestCounters.FRAMES.get();
			for (int i = 0; i < 40; i++) { ctx.setScreen(() -> new ChatScreen("", false)); t.waitT(1 + i % 2); ctx.setScreen(() -> null); t.waitT(1 + i % 3); }
			ctx.runOnClient(c -> c.options.hideGui = true);
			for (int i = 0; i < 20; i++) { ctx.setScreen(() -> new ChatScreen("", false)); t.waitT(2); ctx.setScreen(() -> null); t.waitT(2); }
			ctx.runOnClient(c -> { c.options.hideGui = false; c.options.chatVisibility().set(ChatVisiblity.HIDDEN); });
			for (int i = 0; i < 10; i++) { ctx.setScreen(() -> new ChatScreen("", false)); t.waitT(2); ctx.setScreen(() -> null); t.waitT(2); }
			ctx.runOnClient(c -> c.options.chatVisibility().set(ChatVisiblity.FULL));
			for (int i = 0; i < 5; i++) { ctx.setScreen(() -> new ChatScreen("", false)); t.waitT(2); ctx.setScreen(() -> null); t.waitT(2); }
			TestCounters.trackedWindow = null;
			TestCounters.expectedVisible = null;
			int frames = TestCounters.FRAMES.get() - f0, anomalies = TestCounters.ANOMALIES.get() - a0;
			ev.result("V-55.phase2", frames > 200 && anomalies == 0 && WindowRenderer.duplicateDraws() == 0, "framesChecked", frames, "anomalies", anomalies,
					"samples", TestCounters.ANOMALY_SAMPLES, "duplicateDraws", WindowRenderer.duplicateDraws(),
					"note", "Phase 2 has the gameplay path only (inactive while chat is open, P10); the gameplay/chat-open path split is re-tested in Phase 3");

			// Clamping without file writes (V-12 portion), wrap cache
			int[] clamp = WindowRenderer.lastRect("h-clamp");
			int[] screen = ctx.computeOnClient(c -> new int[]{c.getWindow().getGuiScaledWidth(), c.getWindow().getGuiScaledHeight()});
			boolean clamped = clamp != null && clamp[0] >= 0 && clamp[1] >= 0 && clamp[0] + clamp[2] <= screen[0] && clamp[1] + clamp[3] <= screen[1];
			t.waitT(20);
			boolean fileUnchanged = Arrays.equals(fileBefore, bytes(cfg()));
			ev.result("P2.renderClampingNoWrite", clamped && fileUnchanged, "clampedRect", clamp == null ? null : Arrays.toString(clamp), "screen", Arrays.toString(screen),
					"fileUnchanged", fileUnchanged, "wrapCache", row("hits", WindowRenderer.cacheHits(), "misses", WindowRenderer.cacheMisses()));

			// V-53 gameplay portion: windows are drawn after vanilla's chat HUD
			for (int i = 12; i < 20; i++) {
				int n = i;
				sp.getServer().runOnServer(s -> s.getPlayerList().broadcastSystemMessage(
						Component.literal("HUDTEXT magenta routed line " + n + " cpx#HUD-" + n).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(MAGENTA))), false));
				t.sys(sp, "main chat white line " + n + " lorem ipsum dolor sit amet consectetur");
			}
			t.waitT(6);
			BufferedImage gameplay = ImageIO.read(ctx.takeScreenshot("p2-hud-gameplay").toFile());
			int[] over = WindowRenderer.lastRect("h-overchat");
			double scale = gameplay.getWidth() / (double) screen[0];
			int[] mainChat = ctx.computeOnClient(c -> new int[]{0, screen[1] - 40 - (int) (c.gui.getChat().getLinesPerPage() * 9 * c.options.chatScale().get()), 320, screen[1] - 40});
			int pureInOverlap = countPure(gameplay, scale, intersect(over, mainChat));
			ev.result("V-53.gameplayAboveChatHud", pureInOverlap > 0, "overlapRect", Arrays.toString(intersect(over, mainChat)), "pureMagentaWindowTextPixels", pureInOverlap,
					"screenshot", "p2-hud-gameplay");

			// The gameplay path is inactive while chat is open (P10): nothing drawn, nothing over the chat screen's UI
			int[] mainWin = WindowRenderer.lastRect("h-main");
			ctx.runOnClient(c -> WindowRenderer.resetCounters());
			ctx.setScreen(() -> new ChatScreen("", false));
			t.waitT(6);
			BufferedImage open = ImageIO.read(ctx.takeScreenshot("p2-hud-chatopen").toFile());
			long chatOpenFrames = WindowRenderer.framesWithDraws();
			int[] input = {2, screen[1] - 14, screen[0] - 4, 12}; // the chat input box's fill (vanilla layout)
			int pureInInput = countPure(open, scale, intersect(over, input));
			// The routed lines are magenta in the main chat too, so check the top-right window's area, which the main chat never reaches.
			// Only the part right of the main chat's maximum width (320 GUI px plus margins); the open chat grows upward into this window.
			int[] beyondChat = intersect(mainWin, new int[]{340, 0, screen[0] - 340, screen[1]});
			int pureMainWindowGameplay = countPure(gameplay, scale, beyondChat);
			int pureMainWindowChatOpen = countPure(open, scale, beyondChat);
			ctx.setScreen(() -> null);
			ev.result("P2.notDrawnWhileChatOpen", chatOpenFrames == 0 && pureInInput == 0 && pureMainWindowGameplay > 0 && pureMainWindowChatOpen == 0,
					"framesDrawnWithChatOpen", chatOpenFrames, "pureInInputBox", pureInInput,
					"windowAreaPureMagenta", row("area", Arrays.toString(beyondChat), "gameplay", pureMainWindowGameplay, "chatOpen", pureMainWindowChatOpen), "screenshot", "p2-hud-chatopen",
					"note", "an earlier Phase 2 build that kept drawing via the HUD path while chat was open put window text above the main-chat lines;"
							+ " removed in favour of P10's rule. Full V-53 (chat-open layering) is Phase 3");

			// general.enabled = false draws nothing and routes nothing
			JsonObject j = json(cfg());
			j.getAsJsonObject("general").addProperty("enabled", false);
			write(cfg(), j.toString());
			onClient(() -> mgr().checkForExternalChanges());
			ctx.runOnClient(c -> WindowRenderer.resetCounters());
			t.waitT(10);
			ev.result("P2.globalDisable", WindowRenderer.framesWithDraws() == 0 && onClient(() -> Router.get().config().tabs().isEmpty()),
					"framesDrawn", WindowRenderer.framesWithDraws());
			j.getAsJsonObject("general").addProperty("enabled", true);
			write(cfg(), j.toString());
			onClient(() -> mgr().checkForExternalChanges());
		}
		t.waitT(20);
	}

	static int[] intersect(int[] a, int[] b) {
		if (a == null || b == null) return new int[]{0, 0, 0, 0};
		int x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
		int x2 = Math.min(a[0] + a[2], b[0] + b[2]), y2 = Math.min(a[1] + a[3], b[1] + b[3]);
		return new int[]{x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1)};
	}

	/** Pure magenta pixels (window text drawn unblended, i.e. on top) inside a GUI-space rectangle. */
	static int countPure(BufferedImage img, double scale, int[] r) {
		int n = 0;
		for (int y = (int) (r[1] * scale); y < (int) ((r[1] + r[3]) * scale) && y < img.getHeight(); y++) {
			for (int x = (int) (r[0] * scale); x < (int) ((r[0] + r[2]) * scale) && x < img.getWidth(); x++) {
				int c = img.getRGB(x, y) & 0xFFFFFF;
				int rr = c >> 16 & 0xFF, gg = c >> 8 & 0xFF, bb = c & 0xFF;
				if (rr > 245 && gg < 10 && bb > 245) n++;
			}
		}
		return n;
	}
}
