// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import io.github.itznotneptune.cpextended.core.OrphanRestore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a configuration change would orphan hidden messages (Q2): a hidden message is orphaned when every
 * tab holding it is gone from the new configuration. Only <b>deletion</b> orphans: a tab that stays in the file
 * (disabled, moved, in a disabled window, suspended by an invalid filter or a duplicate id, or with the whole mod
 * disabled) keeps its hidden messages (Q17, Q24, Q32). Every path that changes the configuration asks this first;
 * orphan-producing changes then go through {@link OrphanRestore} after explicit confirmation, or are refused.
 */
public final class OrphanGuard {
	private OrphanGuard() {}

	/** Every tab id present in the configuration, understood or not. */
	public static Set<String> tabIds(ExtendedConfig c) {
		Set<String> ids = new LinkedHashSet<>();
		for (ExtendedConfig.Window w : c.windows()) for (ExtendedConfig.Tab t : w.tabs()) if (t.id() != null) ids.add(t.id());
		return ids;
	}

	/** Ids of tabs in {@code from} whose absence from {@code to} would orphan hidden messages. */
	public static List<String> orphaningTabs(ExtendedConfig from, ExtendedConfig to) {
		return List.copyOf(OrphanRestore.tabsThatWouldOrphan(tabIds(from), tabIds(to)));
	}
}
