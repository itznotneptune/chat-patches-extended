// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.model;

import java.util.List;

/** A window and the ordered tabs it owns (Q8). Geometry and appearance arrive with the windows phase. */
public record WindowConfig(String id, boolean enabled, List<TabConfig> tabs) {
	public WindowConfig {
		tabs = List.copyOf(tabs);
	}
}
