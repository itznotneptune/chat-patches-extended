// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.filter.PatternValidator;
import io.github.itznotneptune.cpextended.model.TabConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Import and export of windows and tabs as text (clipboard or file).
 * <p>
 * An export is a JSON object: {@code {"cpextendedExport": 1, "kind": "layout" | "window" | "tab", "windows": [...]}}
 * ({@code "tabs": [...]} instead of {@code "windows"} for a tab), holding the windows and tabs exactly as stored in the
 * settings file. Only valid entries can be exported, so an export always imports.
 * <p>
 * Importing only <b>adds</b>: windows and tabs get fresh ids and are added at the front; nothing existing is changed or
 * removed, so hidden messages are never affected. An import is validated with the settings file's own parser and
 * filter validation, and refused as a whole on the first problem.
 */
public final class LayoutTransfer {
	public static final String MARKER = "cpextendedExport";
	public static final int FORMAT = 1;
	/** Limits on what an import may contain (a paste can be anything). */
	public static final int MAX_TEXT = 256 * 1024, MAX_WINDOWS = 32, MAX_TABS = 256;
	/** Where a tab imported on its own is placed (a new window, like "Add window"). */
	static final double NEW_X = 0.05, NEW_Y = 0.10, NEW_W = 0.30, NEW_H = 0.25;

	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private LayoutTransfer() {}

	/** An export's text, or why it can't be made. */
	public record Export(@Nullable String text, @Nullable String error, int windows, int tabs) {
		static Export fail(String why) { return new Export(null, why, 0, 0); }
	}

	/** Everything: all windows with their tabs. */
	public static Export exportLayout(ExtendedConfig c) {
		List<ExtendedConfig.Window> ws = c.windows();
		if (ws.isEmpty()) return Export.fail("There are no windows to export");
		JsonArray arr = new JsonArray();
		int tabs = 0;
		for (ExtendedConfig.Window w : ws) {
			String err = exportable(w);
			if (err != null) return Export.fail(err);
			arr.add(w.raw().deepCopy());
			tabs += w.tabs().size();
		}
		return new Export(document("layout", "windows", arr), null, ws.size(), tabs);
	}

	/** One window with its tabs. */
	public static Export exportWindow(ExtendedConfig c, String windowId) {
		ExtendedConfig.Window w = c.window(windowId);
		if (w == null) return Export.fail("This window no longer exists");
		String err = exportable(w);
		if (err != null) return Export.fail(err);
		JsonArray arr = new JsonArray();
		arr.add(w.raw().deepCopy());
		return new Export(document("window", "windows", arr), null, 1, w.tabs().size());
	}

	/** One tab; it imports into a new window. */
	public static Export exportTab(ExtendedConfig c, String tabId) {
		ExtendedConfig.Window w = c.windowOfTab(tabId);
		ExtendedConfig.Tab t = w == null ? null : w.tabs().stream().filter(x -> tabId.equals(x.id())).findFirst().orElse(null);
		if (t == null) return Export.fail("This tab no longer exists");
		String err = exportable(t);
		if (err != null) return Export.fail(err);
		JsonArray arr = new JsonArray();
		arr.add(t.raw().deepCopy());
		return new Export(document("tab", "tabs", arr), null, 0, 1);
	}

	private static @Nullable String exportable(ExtendedConfig.Window w) {
		if (w.suspended()) return "A window in the settings file can't be read; fix the file first";
		for (ExtendedConfig.Tab t : w.tabs()) {
			String err = exportable(t);
			if (err != null) return err;
		}
		return null;
	}

	private static @Nullable String exportable(ExtendedConfig.Tab t) {
		if (t.config() == null) return "A tab in the settings file can't be read; fix the file first";
		String bad = invalidFilter(t.config());
		return bad == null ? null : "Tab \"" + t.config().name() + "\": " + bad + ". Fix it before exporting";
	}

	private static String document(String kind, String key, JsonArray entries) {
		JsonObject o = new JsonObject();
		o.addProperty(MARKER, FORMAT);
		o.addProperty("kind", kind);
		o.addProperty("exportedBy", "Chat Patches Extended " + FabricLoader.getInstance().getModContainer("cpextended")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		o.add(key, entries);
		return PRETTY.toJson(o) + "\n";
	}

	// ---------------------------------------------------------------- import

	/** A validated import, ready to apply: the windows (fresh ids) and a summary for the confirmation. */
	public record Plan(String kind, List<JsonObject> windows, int windowCount, int tabCount, int hidingTabs, List<String> tabNames) {
		public ConfigEdit edit() { return new ConfigEdit.ImportWindows(windows); }
	}

	public record Parsed(@Nullable Plan plan, @Nullable String error) {
		static Parsed fail(String why) { return new Parsed(null, why); }
	}

	/**
	 * Parses and validates an export against the current settings (for fresh ids). Never changes anything; apply the
	 * plan's {@link Plan#edit()} to import.
	 */
	public static Parsed parse(@Nullable String input, ExtendedConfig current) {
		if (input == null || input.isBlank()) return Parsed.fail("Nothing to import (the clipboard or file is empty)");
		if (input.length() > MAX_TEXT) return Parsed.fail("Too large to be an export (over " + MAX_TEXT / 1024 + " KB)");
		String text = stripFences(input.strip());
		JsonObject root;
		try {
			JsonElement e = JsonParser.parseString(text);
			if (!e.isJsonObject()) return Parsed.fail("Not a Chat Patches Extended export");
			root = e.getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			return Parsed.fail("Not a Chat Patches Extended export (not valid JSON)");
		}
		JsonElement marker = root.get(MARKER);
		if (marker == null || !marker.isJsonPrimitive() || !marker.getAsJsonPrimitive().isNumber()) return Parsed.fail("Not a Chat Patches Extended export");
		if (marker.getAsInt() > FORMAT) return Parsed.fail("Made by a newer version of Chat Patches Extended; update to import it");
		if (marker.getAsInt() != FORMAT) return Parsed.fail("Unsupported export format " + marker);
		String kind = root.has("kind") && root.get("kind").isJsonPrimitive() ? root.get("kind").getAsString() : "";

		// The windows to add, as stored (deep copies).
		List<JsonObject> windows = new ArrayList<>();
		switch (kind) {
			case "layout", "window" -> {
				JsonArray arr = array(root, "windows");
				if (arr == null || arr.isEmpty()) return Parsed.fail("The export has no windows");
				if (kind.equals("window") && arr.size() != 1) return Parsed.fail("A window export must hold exactly one window");
				for (JsonElement e : arr) {
					if (!e.isJsonObject()) return Parsed.fail("The export has an entry that isn't a window");
					windows.add(e.getAsJsonObject().deepCopy());
				}
			}
			case "tab" -> {
				JsonArray arr = array(root, "tabs");
				if (arr == null || arr.isEmpty()) return Parsed.fail("The export has no tab");
				JsonObject w = ConfigEdit.newWindow("import", NEW_X, NEW_Y, NEW_W, NEW_H);
				for (JsonElement e : arr) {
					if (!e.isJsonObject()) return Parsed.fail("The export has an entry that isn't a tab");
					w.getAsJsonArray("tabs").add(e.getAsJsonObject().deepCopy());
				}
				windows.add(w);
			}
			default -> { return Parsed.fail("Unknown export kind \"" + kind + "\""); }
		}
		if (windows.size() > MAX_WINDOWS) return Parsed.fail("Too many windows in one import (over " + MAX_WINDOWS + ")");
		int tabTotal = 0;
		for (JsonObject w : windows) {
			JsonArray tabs = array(w, "tabs");
			if (w.has("tabs") && tabs == null) return Parsed.fail("A window's tabs aren't a list");
			tabTotal += tabs == null ? 0 : tabs.size();
		}
		if (tabTotal > MAX_TABS) return Parsed.fail("Too many tabs in one import (over " + MAX_TABS + ")");

		// Validate with the settings file's own parser: any issue refuses the import.
		String structural = validate(windows);
		if (structural != null) return Parsed.fail(structural);

		// Fresh ids (never colliding with the current settings or with each other); selections follow their tabs.
		Set<String> taken = new HashSet<>();
		Predicate<String> used = id -> taken.contains(id) || current.window(id) != null || current.windowOfTab(id) != null;
		int hiding = 0;
		List<String> names = new ArrayList<>();
		for (JsonObject w : windows) {
			w.addProperty("id", fresh("window", used, taken));
			if (!w.has("tabs")) w.add("tabs", new JsonArray());
			Map<String, String> renamed = new HashMap<>();
			for (JsonElement te : w.getAsJsonArray("tabs")) {
				JsonObject t = te.getAsJsonObject();
				String newId = fresh("tab", used, taken);
				renamed.putIfAbsent(t.get("id").getAsString(), newId);
				t.addProperty("id", newId);
				if (t.has("hideFromMainChat") && t.get("hideFromMainChat").getAsBoolean()) hiding++;
				names.add(t.has("name") && t.get("name").isJsonPrimitive() ? t.get("name").getAsString() : newId);
			}
			JsonElement sel = w.get("selectedTab");
			if (sel != null && sel.isJsonPrimitive() && renamed.containsKey(sel.getAsString())) w.addProperty("selectedTab", renamed.get(sel.getAsString()));
			else w.remove("selectedTab");
		}
		return new Parsed(new Plan(kind, List.copyOf(windows), windows.size(), tabTotal, hiding, List.copyOf(names)), null);
	}

	/** Runs the settings parser over the windows alone; returns the first problem, or null if they are fully valid. */
	private static @Nullable String validate(List<JsonObject> windows) {
		JsonObject doc = new JsonObject();
		doc.addProperty("schemaVersion", ExtendedConfig.SCHEMA_VERSION);
		JsonObject general = new JsonObject();
		general.addProperty("enabled", true);
		general.addProperty("hidingEnabled", true);
		doc.add("general", general);
		JsonObject appearance = new JsonObject();
		for (String k : new String[]{"backgroundOpacity", "textOpacity", "scale", "lineSpacing"}) appearance.add(k, JsonNull.INSTANCE);
		doc.add("appearance", appearance);
		JsonArray arr = new JsonArray();
		for (JsonObject w : windows) arr.add(w.deepCopy());
		doc.add("windows", arr);
		ConfigParser.Result r = ConfigParser.interpret(doc);
		if (r.config() == null || r.status() != ConfigParser.Status.OK) return "The export can't be read" + (r.error() == null ? "" : ": " + r.error());
		ExtendedConfig c = r.config();
		if (!c.issues().isEmpty()) return "The export has a problem: " + issue(c.issues().get(0));
		for (ExtendedConfig.Window w : c.windows()) {
			if (w.suspended()) return "The export has a window that can't be read";
			Set<String> tabIds = new HashSet<>();
			for (ExtendedConfig.Tab t : w.tabs()) {
				if (t.config() == null) return "The export has a tab that can't be read";
				if (!tabIds.add(t.id())) return "The export has two tabs with the same id in one window";
				String bad = invalidFilter(t.config());
				if (bad != null) return "Tab \"" + t.config().name() + "\": " + bad;
			}
		}
		return null;
	}

	/** The first invalid filter of a tab, described for players; null if all are valid. */
	private static @Nullable String invalidFilter(TabConfig t) {
		for (FilterSpec f : t.include()) { String e = describe(f); if (e != null) return e; }
		for (FilterSpec f : t.exclude()) { String e = describe(f); if (e != null) return e; }
		return null;
	}

	private static @Nullable String describe(FilterSpec f) {
		String code = PatternValidator.validate(f);
		if (code == null) return null;
		return switch (code) {
			case "empty" -> "empty pattern";
			case "too_long" -> "pattern longer than " + PatternValidator.MAX_LENGTH + " characters";
			case "unknown_mode" -> "unknown filter mode";
			case io.github.itznotneptune.cpextended.filter.RegexSupport.INVALID -> "invalid regex: " + PatternValidator.regexDetail(f);
			case io.github.itznotneptune.cpextended.filter.RegexSupport.TOO_COMPLEX -> "regex too complex";
			case io.github.itznotneptune.cpextended.filter.RegexSupport.UNAVAILABLE -> "regex filters unavailable";
			default -> code;
		};
	}

	/** A settings-parser issue without its file-repair note ("...; true used (value kept)"). */
	private static String issue(String parserIssue) {
		int cut = parserIssue.length();
		for (String sep : new String[]{";", " (kept", " (value kept", " (tab suspended"}) {
			int i = parserIssue.indexOf(sep);
			if (i > 0) cut = Math.min(cut, i);
		}
		return parserIssue.substring(0, cut);
	}

	private static String fresh(String prefix, Predicate<String> used, Set<String> taken) {
		for (int i = 1; ; i++) {
			String id = prefix + "-" + i;
			if (!used.test(id)) {
				taken.add(id);
				return id;
			}
		}
	}

	private static @Nullable JsonArray array(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	/** Accepts a paste wrapped in a Markdown code block (``` or ```json), as chat apps often add. */
	static String stripFences(String s) {
		if (!s.startsWith("```")) return s;
		int firstLine = s.indexOf('\n');
		int end = s.lastIndexOf("```");
		if (firstLine < 0 || end <= firstLine) return s;
		return s.substring(firstLine + 1, end).strip();
	}
}
