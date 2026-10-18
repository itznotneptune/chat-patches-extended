// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Player-facing names for tabs and windows in dialogs, refusals and navigation (RC.2, S2/M2). Presentation only: tabs
 * and windows are still identified internally by id. A tab is shown by its saved name; the id is added only to tell
 * identically named tabs apart or to identify an unnamed or unreadable entry. Windows have no saved name (none is
 * added to the schema), so they're labelled by the number in their id ({@code window-3} is "Window 3").
 */
public final class Labels {
	private Labels() {}

	/** {@code "Smoke"}, {@code "Smoke" (tab-1)} when another tab has the same name, or the id for an unnamed or unreadable entry. */
	public static String tab(ExtendedConfig c, String id) {
		ExtendedConfig.Tab found = null;
		for (ExtendedConfig.Window w : c.windows()) for (ExtendedConfig.Tab t : w.tabs()) if (id.equals(t.id()) && found == null) found = t;
		if (found == null) return "tab " + id;
		if (found.config() == null) return "unreadable tab entry (" + id + ")";
		String name = found.config().name();
		if (name == null || name.isBlank()) return "unnamed tab (" + id + ")";
		return sameName(c, name) > 1 ? "\"" + name + "\" (" + id + ")" : "\"" + name + "\"";
	}

	/** Several tabs, in order: {@code "Smoke", "Trade"}. */
	public static String tabs(ExtendedConfig c, Collection<String> ids) {
		List<String> out = new ArrayList<>(ids.size());
		for (String id : ids) out.add(tab(c, id));
		return String.join(", ", out);
	}

	private static int sameName(ExtendedConfig c, String name) {
		int n = 0;
		for (ExtendedConfig.Window w : c.windows()) for (ExtendedConfig.Tab t : w.tabs()) if (t.config() != null && name.equals(t.config().name())) n++;
		return n;
	}

	/** "Window 3" for {@code window-3}; another id is shown quoted; an unreadable entry says so. */
	public static String window(@Nullable String id) {
		if (id == null) return "Unreadable window entry";
		// plain parsing: production code never uses java.util.regex (V-46d)
		String prefix = "window-";
		String digits = id.startsWith(prefix) ? id.substring(prefix.length()) : "";
		boolean numbered = !digits.isEmpty() && digits.length() <= 9 && digits.chars().allMatch(c -> c >= '0' && c <= '9');
		return numbered ? "Window " + Integer.parseInt(digits) : "Window \"" + id + "\"";
	}

	/** The window label followed by its tab names: {@code Window 1: Smoke, Everything}. */
	public static String windowWithTabs(ExtendedConfig.Window w) {
		StringBuilder sb = new StringBuilder(window(w.id()));
		List<String> names = new ArrayList<>();
		for (ExtendedConfig.Tab t : w.tabs()) {
			if (t.config() == null) names.add("(unreadable tab)");
			else names.add(t.config().name() == null || t.config().name().isBlank() ? "(unnamed)" : t.config().name());
		}
		sb.append(names.isEmpty() ? ": no tabs" : ": " + String.join(", ", names));
		return sb.toString();
	}
}
