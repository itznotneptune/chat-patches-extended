// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import io.github.itznotneptune.cpextended.dev.DevHooks;
import io.github.itznotneptune.cpextended.model.RoutingConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a configuration change would orphan hidden messages (Q2). Orphan restoration (P5 prepare and Q36
 * reference-swap commit, P6, Q27, C-1) isn't implemented yet, so every orphan-producing change is <b>refused</b>, never
 * approximated: by edits, by Apply file, and by Restore from backup alike.
 *
 * <p>Message hiding is disabled in this phase, so no tab holds hidden messages and nothing can orphan in practice. The
 * check still runs on every path; development builds can mark tabs as holding hidden messages to verify the refusal.
 */
public final class OrphanGuard {
	private static final Set<String> DEV_TABS_WITH_HIDDEN = new HashSet<>();

	private OrphanGuard() {}

	/** Tab ids holding hidden messages. Always empty while hiding is disabled (Phase 2), apart from the dev hook. */
	static Set<String> tabsHoldingHidden() {
		return DevHooks.isDev() ? Set.copyOf(DEV_TABS_WITH_HIDDEN) : Set.of();
	}

	/** Ids of tabs holding hidden messages that {@code to} no longer has as an active (non-suspended) tab. */
	public static List<String> orphaningTabs(RoutingConfig from, RoutingConfig to) {
		Set<String> hidden = tabsHoldingHidden();
		if (hidden.isEmpty()) return List.of();
		Set<String> kept = new HashSet<>();
		for (RoutingConfig.CompiledTab t : to.tabs()) if (!t.suspended()) kept.add(t.id());
		return hidden.stream().filter(id -> from.tabs().stream().anyMatch(t -> t.id().equals(id)) && !kept.contains(id)).sorted().toList();
	}

	/** Development builds only: pretend {@code tabId} holds hidden messages (or clear all with null). */
	public static void devMarkHoldingHidden(String tabId) {
		DevHooks.requireDev();
		if (tabId == null) DEV_TABS_WITH_HIDDEN.clear();
		else DEV_TABS_WITH_HIDDEN.add(tabId);
	}
}
