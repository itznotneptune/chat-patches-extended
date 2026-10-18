// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.model;

import io.github.itznotneptune.cpextended.filter.FilterSpec;

import java.util.List;

/**
 * A tab as configured (schema §7.2). In Milestone 1a {@code hideFromMainChat} is carried but has no effect: hiding is
 * not implemented yet, so every message stays in the main chat.
 */
public record TabConfig(String id, String name, boolean enabled, boolean hideFromMainChat, boolean restoreHistory,
		boolean matchAll, List<FilterSpec> include, List<FilterSpec> exclude) {
	public TabConfig {
		include = List.copyOf(include);
		exclude = List.copyOf(exclude);
	}
}
