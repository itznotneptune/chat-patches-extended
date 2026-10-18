// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import com.google.gson.JsonObject;
import io.github.itznotneptune.cpextended.model.RoutingConfig;
import io.github.itznotneptune.cpextended.model.TabConfig;
import io.github.itznotneptune.cpextended.model.WindowConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The configuration as understood from {@code config/cpextended.json} (schema §7.2), always derived from the raw JSON
 * tree it came from ({@link #raw}). The raw tree is the source of truth on save (P14 raw preservation): this view only
 * reads it. Entries that can't be understood (no usable id, wrong shape, duplicate id) are kept in the raw tree,
 * flagged and suspended, and never reactivated.
 */
public record ExtendedConfig(JsonObject raw, int schemaVersion, boolean newerSchema, boolean enabled, boolean hidingEnabled,
		Appearance appearance, List<Window> windows, List<String> issues) {
	public static final int SCHEMA_VERSION = 1;

	public enum Anchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	/** Optional overrides of the vanilla chat settings; null means "follow vanilla" (schema §7.2). */
	public record Appearance(@Nullable Double backgroundOpacity, @Nullable Double textOpacity, @Nullable Double scale, @Nullable Double lineSpacing) {
		public static final Appearance VANILLA = new Appearance(null, null, null, null);
	}

	/**
	 * A window. Geometry is in screen fractions relative to {@code anchor}; it is clamped only when drawn, never in the
	 * file. {@code stackIndex} is the window's position in the file's array, which is its stacking order (Q34, back to
	 * front).
	 */
	public record Window(JsonObject raw, @Nullable String id, boolean enabled, Anchor anchor, double x, double y, double width, double height,
			@Nullable String selectedTab, List<Tab> tabs, List<String> errors, int stackIndex) {
		/** Unreadable (no usable id) or a later duplicate: kept as stored, never drawn, its tabs never route. */
		public boolean suspended() { return id == null || errors.contains(ConfigParser.DUPLICATE_WINDOW); }

		/** The tab shown: the selected one, or the first tab when the selection is unknown (§7.3). */
		public @Nullable Tab shownTab() {
			for (Tab t : tabs) if (t.id() != null && t.id().equals(selectedTab)) return t;
			return tabs.isEmpty() ? null : tabs.get(0);
		}
	}

	/** A tab; {@code config} is null when the entry couldn't be understood (then it's preserved and suspended). */
	public record Tab(JsonObject raw, @Nullable String id, @Nullable TabConfig config, List<String> errors) {}

	/** Routing view (Literal filters, Q15); a disabled configuration routes nothing. */
	public RoutingConfig routing() {
		if (!enabled) return RoutingConfig.EMPTY;
		List<WindowConfig> ws = new ArrayList<>();
		for (Window w : windows) {
			List<TabConfig> tabs = new ArrayList<>();
			for (Tab t : w.tabs()) if (t.config() != null) tabs.add(t.config());
			if (w.id() == null) continue; // unreadable window: its tabs stay suspended (never routed), preserved in the file
			ws.add(new WindowConfig(w.id(), w.enabled(), tabs));
		}
		return RoutingConfig.of(ws);
	}

	public @Nullable Window window(String id) {
		for (Window w : windows) if (id.equals(w.id())) return w;
		return null;
	}

	/** First window holding a tab with this id (duplicates beyond the first are suspended anyway). */
	public @Nullable Window windowOfTab(String tabId) {
		for (Window w : windows) for (Tab t : w.tabs()) if (tabId.equals(t.id())) return w;
		return null;
	}
}
