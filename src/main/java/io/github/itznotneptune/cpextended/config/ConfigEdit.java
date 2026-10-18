// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.filter.PatternValidator;
import org.jetbrains.annotations.Nullable;

/**
 * One intentional change to the configuration (P14 dirty tracking). An edit writes only the recognized keys it
 * changes into the raw tree; everything else (unknown keys, untouched invalid filters, duplicates) stays as stored.
 * Edits are kept until saved so they can be re-applied on top of an externally edited file ("re-evaluated against the
 * new baseline"); an edit whose target no longer exists is dropped.
 */
public sealed interface ConfigEdit {
	enum Outcome { APPLIED, REJECTED, TARGET_MISSING }

	record Result(Outcome outcome, @Nullable String reason) {
		static final Result OK = new Result(Outcome.APPLIED, null);
		static Result rejected(String why) { return new Result(Outcome.REJECTED, why); }
		static Result missing(String what) { return new Result(Outcome.TARGET_MISSING, what); }
	}

	/** Applies the edit to {@code root}; {@code view} is the understood view of that same tree. */
	Result apply(JsonObject root, ExtendedConfig view);

	/** Geometry edits are saved with the debounce (§7.1). */
	default boolean debounced() { return false; }

	// ---------------------------------------------------------------- window edits

	record SetGeometry(String windowId, double x, double y, double width, double height) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window w = view.window(windowId);
			if (w == null || w.suspended()) return Result.missing("window " + windowId);
			w.raw().addProperty("x", x);
			w.raw().addProperty("y", y);
			w.raw().addProperty("width", width);
			w.raw().addProperty("height", height);
			return Result.OK;
		}
		public boolean debounced() { return true; }
	}

	record SetWindowEnabled(String windowId, boolean enabled) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window w = view.window(windowId);
			if (w == null || w.suspended()) return Result.missing("window " + windowId);
			w.raw().addProperty("enabled", enabled);
			return Result.OK;
		}
	}

	/** Brings a window to the front: the windows array order is the stacking order (Q34), back to front. */
	record BringToFront(String windowId) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window w = view.window(windowId);
			if (w == null || w.suspended()) return Result.missing("window " + windowId);
			JsonArray arr = root.getAsJsonArray("windows");
			removeIdentity(arr, w.raw());
			arr.add(w.raw());
			return Result.OK;
		}
		public boolean debounced() { return true; }
	}

	record SelectTab(String windowId, String tabId) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window w = view.window(windowId);
			if (w == null || w.suspended()) return Result.missing("window " + windowId);
			if (w.tabs().stream().noneMatch(t -> tabId.equals(t.id()))) return Result.missing("tab " + tabId + " in " + windowId);
			w.raw().addProperty("selectedTab", tabId);
			return Result.OK;
		}
	}

	// ---------------------------------------------------------------- tab edits

	record RenameTab(String tabId, String name) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			JsonObject t = tab(view, tabId);
			if (t == null) return Result.missing("tab " + tabId);
			t.addProperty("name", name);
			return Result.OK;
		}
	}

	/** Sets one of the tab's recognized booleans: enabled, hideFromMainChat, restoreHistory, matchAll. */
	record SetTabFlag(String tabId, String key, boolean value) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			if (!java.util.Set.of("enabled", "hideFromMainChat", "restoreHistory", "matchAll").contains(key)) return Result.rejected("unknown flag " + key);
			JsonObject t = tab(view, tabId);
			if (t == null) return Result.missing("tab " + tabId);
			t.addProperty(key, value);
			return Result.OK;
		}
	}

	/** Replaces filter {@code index} of {@code include}/{@code exclude}. The new filter must be valid (Q28). */
	record SetFilter(String tabId, String list, int index, FilterSpec filter) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			String err = PatternValidator.validate(filter);
			if (err != null) return Result.rejected("filter would be invalid (" + err + "); not saved");
			JsonArray arr = filters(view, tabId, list);
			if (arr == null || index < 0 || index >= arr.size()) return Result.missing("filter " + tabId + "." + list + "[" + index + "]");
			JsonElement existing = arr.get(index);
			JsonObject f = existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
			writeFilter(f, filter);
			arr.set(index, f);
			return Result.OK;
		}
	}

	record AddFilter(String tabId, String list, FilterSpec filter) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			String err = PatternValidator.validate(filter);
			if (err != null) return Result.rejected("new filter is invalid (" + err + "); not saved");
			JsonObject t = tab(view, tabId);
			if (t == null) return Result.missing("tab " + tabId);
			JsonElement e = t.get(list);
			if (e != null && !e.isJsonArray()) return Result.rejected(list + " is not an array in the file; edit the file to fix it");
			JsonArray arr = e == null ? new JsonArray() : e.getAsJsonArray();
			JsonObject f = new JsonObject();
			writeFilter(f, filter);
			arr.add(f);
			t.add(list, arr);
			return Result.OK;
		}
	}

	/** Deleting a filter is always allowed, valid or not (Q28). */
	record DeleteFilter(String tabId, String list, int index) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			JsonArray arr = filters(view, tabId, list);
			if (arr == null || index < 0 || index >= arr.size()) return Result.missing("filter " + tabId + "." + list + "[" + index + "]");
			arr.remove(index);
			return Result.OK;
		}
	}

	/** Moves a tab, with its whole raw subtree (unknown keys included), to the end of another window's tabs. */
	record MoveTab(String tabId, String targetWindowId) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window from = view.windowOfTab(tabId);
			ExtendedConfig.Window to = view.window(targetWindowId);
			if (from == null || to == null || to.suspended()) return Result.missing("tab " + tabId + " or window " + targetWindowId);
			if (from == to) return Result.OK;
			JsonObject t = tab(view, tabId);
			removeIdentity(from.raw().getAsJsonArray("tabs"), t);
			reselectAfterRemoval(from.raw(), tabId);
			JsonElement e = to.raw().get("tabs");
			if (e != null && !e.isJsonArray()) return Result.rejected("target tabs is not an array in the file");
			JsonArray arr = e == null ? new JsonArray() : e.getAsJsonArray();
			arr.add(t);
			to.raw().add("tabs", arr);
			return Result.OK;
		}
	}

	/** Deletes a tab. The manager refuses it first if it would orphan hidden messages (OrphanGuard). */
	record DeleteTab(String tabId) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window from = view.windowOfTab(tabId);
			if (from == null) return Result.missing("tab " + tabId);
			removeIdentity(from.raw().getAsJsonArray("tabs"), tab(view, tabId));
			reselectAfterRemoval(from.raw(), tabId);
			return Result.OK;
		}
	}

	/** Moves a tab (whole raw subtree) into a new enabled window, where it is selected (Q30c, Q33 rule 5). */
	record MoveTabToNewWindow(String tabId, String newWindowId, double x, double y, double width, double height) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window from = view.windowOfTab(tabId);
			if (from == null) return Result.missing("tab " + tabId);
			if (view.window(newWindowId) != null) return Result.rejected("window id " + newWindowId + " already exists");
			JsonArray ws = windows(root);
			if (ws == null) return Result.rejected("windows is not an array in the file; edit the file to fix it");
			JsonObject t = tab(view, tabId);
			removeIdentity(from.raw().getAsJsonArray("tabs"), t);
			reselectAfterRemoval(from.raw(), tabId);
			JsonObject w = newWindow(newWindowId, x, y, width, height);
			w.getAsJsonArray("tabs").add(t);
			w.addProperty("selectedTab", tabId);
			ws.add(w);
			return Result.OK;
		}
	}

	/** Adds an empty enabled window at the front (Q30e: windows stay until explicitly deleted). */
	record AddWindow(String windowId, double x, double y, double width, double height) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			if (view.window(windowId) != null) return Result.rejected("window id " + windowId + " already exists");
			JsonArray ws = windows(root);
			if (ws == null) return Result.rejected("windows is not an array in the file; edit the file to fix it");
			ws.add(newWindow(windowId, x, y, width, height));
			return Result.OK;
		}
	}

	/** Deletes a window with its tabs. The manager refuses it if that would orphan hidden messages (OrphanGuard). */
	record DeleteWindow(String windowId) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window w = view.window(windowId);
			if (w == null) return Result.missing("window " + windowId);
			removeIdentity(root.getAsJsonArray("windows"), w.raw());
			return Result.OK;
		}
	}

	/** Adds a tab with the schema defaults (enabled, not hiding, restoring history, no filters) to a window. */
	record AddTab(String windowId, String tabId, String name) implements ConfigEdit {
		public Result apply(JsonObject root, ExtendedConfig view) {
			ExtendedConfig.Window w = view.window(windowId);
			if (w == null || w.suspended()) return Result.missing("window " + windowId);
			if (view.windowOfTab(tabId) != null) return Result.rejected("tab id " + tabId + " already exists");
			JsonElement e = w.raw().get("tabs");
			if (e != null && !e.isJsonArray()) return Result.rejected("tabs is not an array in the file; edit the file to fix it");
			JsonArray arr = e == null ? new JsonArray() : e.getAsJsonArray();
			JsonObject t = new JsonObject();
			t.addProperty("id", tabId);
			t.addProperty("name", name);
			t.addProperty("enabled", true);
			t.addProperty("hideFromMainChat", false);
			t.addProperty("restoreHistory", true);
			t.addProperty("matchAll", false);
			t.add("include", new JsonArray());
			t.add("exclude", new JsonArray());
			arr.add(t);
			w.raw().add("tabs", arr);
			return Result.OK;
		}
	}

	// ---------------------------------------------------------------- helpers

	/** The windows array, created if absent; null if the file holds something else there (never repaired). */
	private static @Nullable JsonArray windows(JsonObject root) {
		JsonElement e = root.get("windows");
		if (e == null) {
			JsonArray arr = new JsonArray();
			root.add("windows", arr);
			return arr;
		}
		return e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	private static JsonObject newWindow(String id, double x, double y, double width, double height) {
		JsonObject w = new JsonObject();
		w.addProperty("id", id);
		w.addProperty("enabled", true);
		w.addProperty("anchor", "TOP_LEFT");
		w.addProperty("x", x);
		w.addProperty("y", y);
		w.addProperty("width", width);
		w.addProperty("height", height);
		w.add("tabs", new JsonArray());
		return w;
	}

	/** When the selected tab leaves a window, select its first remaining tab (or drop the selection if none is left). */
	private static void reselectAfterRemoval(JsonObject window, String removedTabId) {
		JsonElement sel = window.get("selectedTab");
		if (sel == null || !sel.isJsonPrimitive() || !removedTabId.equals(sel.getAsString())) return;
		JsonArray tabs = window.has("tabs") && window.get("tabs").isJsonArray() ? window.getAsJsonArray("tabs") : new JsonArray();
		for (JsonElement t : tabs) {
			if (t.isJsonObject() && t.getAsJsonObject().has("id") && t.getAsJsonObject().get("id").isJsonPrimitive()) {
				window.addProperty("selectedTab", t.getAsJsonObject().get("id").getAsString());
				return;
			}
		}
		window.remove("selectedTab");
	}

	private static @Nullable JsonObject tab(ExtendedConfig view, String tabId) {
		for (ExtendedConfig.Window w : view.windows()) for (ExtendedConfig.Tab t : w.tabs()) if (tabId.equals(t.id())) return t.raw();
		return null;
	}

	private static @Nullable JsonArray filters(ExtendedConfig view, String tabId, String list) {
		if (!list.equals("include") && !list.equals("exclude")) return null;
		JsonObject t = tab(view, tabId);
		if (t == null) return null;
		JsonElement e = t.get(list);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	/** Removes exactly {@code e} (JsonArray.remove(Object) compares by content and could hit an identical duplicate). */
	private static void removeIdentity(JsonArray arr, JsonElement e) {
		for (int i = 0; i < arr.size(); i++) {
			if (arr.get(i) == e) {
				arr.remove(i);
				return;
			}
		}
	}

	/** Writes only the recognized filter keys; any other keys in the filter object are kept. */
	private static void writeFilter(JsonObject f, FilterSpec spec) {
		f.add("mode", new JsonPrimitive(spec.effectiveMode()));
		f.add("pattern", new JsonPrimitive(spec.pattern()));
		f.add("caseSensitive", new JsonPrimitive(spec.caseSensitive()));
	}
}
