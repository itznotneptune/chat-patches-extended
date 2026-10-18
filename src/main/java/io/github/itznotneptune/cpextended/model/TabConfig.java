// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.model;

import io.github.itznotneptune.cpextended.filter.FilterSpec;

import java.util.List;

/** A tab as configured (schema §7.2). {@code hideFromMainChat} takes effect per the visibility rules (§4.6). */
public record TabConfig(String id, String name, boolean enabled, boolean hideFromMainChat, boolean restoreHistory,
		boolean matchAll, List<FilterSpec> include, List<FilterSpec> exclude) {
	public TabConfig {
		include = List.copyOf(include);
		exclude = List.copyOf(exclude);
	}
}
