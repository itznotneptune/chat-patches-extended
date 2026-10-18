// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.model;

import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.filter.LiteralMatcher;
import io.github.itznotneptune.cpextended.filter.MatchText;
import io.github.itznotneptune.cpextended.filter.Matcher;
import io.github.itznotneptune.cpextended.filter.PatternValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable routing snapshot. The router reads one snapshot per message, so configuration changes affect only later
 * messages (Q9). Tabs with invalid filters or duplicate IDs are config-suspended (Q14, §7.3) and never executed.
 */
public final class RoutingConfig {
	public static final RoutingConfig EMPTY = of(List.of());

	/** A tab ready to evaluate; {@code errors} non-empty means it's config-suspended. */
	public record CompiledTab(TabConfig config, String windowId, List<Matcher> include, List<Matcher> exclude, List<String> errors) {
		public String id() { return config.id(); }
		public boolean suspended() { return !errors.isEmpty(); }

		/** Any include matches (or none and matchAll), and no exclude matches. May throw; see the router's guard. */
		public boolean matches(MatchText text) {
			boolean included;
			if (include.isEmpty()) included = config.matchAll();
			else {
				included = false;
				for (Matcher m : include) if (m.matches(text)) { included = true; break; }
			}
			if (!included) return false;
			for (Matcher m : exclude) if (m.matches(text)) return false;
			return true;
		}
	}

	private final List<WindowConfig> windows;
	private final List<CompiledTab> tabs;
	private final boolean hidingEnabled;
	private final Set<String> enabledWindows;

	private RoutingConfig(List<WindowConfig> windows, List<CompiledTab> tabs, boolean hidingEnabled) {
		this.windows = windows;
		this.tabs = tabs;
		this.hidingEnabled = hidingEnabled;
		Set<String> en = new HashSet<>();
		for (WindowConfig w : windows) if (w.enabled()) en.add(w.id());
		this.enabledWindows = Set.copyOf(en);
	}

	public static RoutingConfig of(List<WindowConfig> windows) {
		return of(windows, true);
	}

	/** {@code hidingEnabled} is the global switch ({@code general.hidingEnabled}); off, nothing is hidden (§4.6). */
	public static RoutingConfig of(List<WindowConfig> windows, boolean hidingEnabled) {
		List<CompiledTab> tabs = new ArrayList<>();
		Set<String> tabIds = new HashSet<>();
		Set<String> windowIds = new HashSet<>();
		for (WindowConfig w : windows) {
			boolean duplicateWindow = !windowIds.add(w.id());
			for (TabConfig t : w.tabs()) {
				List<String> errors = new ArrayList<>();
				if (duplicateWindow) errors.add("duplicate_window_id");
				if (!tabIds.add(t.id())) errors.add("duplicate_tab_id");
				List<Matcher> inc = compile(t, t.include(), "include", errors);
				List<Matcher> exc = compile(t, t.exclude(), "exclude", errors);
				tabs.add(new CompiledTab(t, w.id(), errors.isEmpty() ? inc : List.of(), errors.isEmpty() ? exc : List.of(), List.copyOf(errors)));
			}
		}
		return new RoutingConfig(List.copyOf(windows), List.copyOf(tabs), hidingEnabled);
	}

	private static List<Matcher> compile(TabConfig tab, List<FilterSpec> specs, String kind, List<String> errors) {
		List<Matcher> out = new ArrayList<>();
		for (int i = 0; i < specs.size(); i++) {
			FilterSpec f = specs.get(i);
			String err = PatternValidator.validate(f);
			if (err != null) {
				errors.add(kind + "[" + i + "]:" + err);
				continue;
			}
			out.add(DevHooks.decorate(tab.id(), new LiteralMatcher(f.pattern(), f.caseSensitive())));
		}
		return out;
	}

	public List<WindowConfig> windows() { return windows; }
	public boolean hidingEnabled() { return hidingEnabled; }
	public boolean windowEnabled(String windowId) { return enabledWindows.contains(windowId); }

	/** Whether any tab could hide at all (Q3 snapshot only taken then). */
	public boolean anyHidingTab() {
		if (!hidingEnabled) return false;
		for (CompiledTab t : tabs) if (t.config().hideFromMainChat() && t.config().enabled() && !t.suspended() && windowEnabled(t.windowId())) return true;
		return false;
	}
	public List<CompiledTab> tabs() { return tabs; }

	public Map<String, List<String>> configErrorSummary() {
		Map<String, List<String>> m = new LinkedHashMap<>();
		for (CompiledTab t : tabs) if (t.suspended()) m.put(t.id(), t.errors());
		return m;
	}
}
