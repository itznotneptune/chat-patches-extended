// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.dev;

import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.model.RoutingConfig;
import io.github.itznotneptune.cpextended.model.TabConfig;
import io.github.itznotneptune.cpextended.model.WindowConfig;

import java.util.List;

/**
 * Fixed developer configuration for Milestone 1a, until the configuration file exists (Phase 2). Synthetic markers
 * only; {@code hideFromMainChat} is false everywhere because hiding isn't implemented yet.
 */
public final class DevConfiguration {
	private DevConfiguration() {}

	public static RoutingConfig fixed() {
		return RoutingConfig.of(List.of(
				new WindowConfig("dev-side", true, List.of(
						tab("staff", List.of(FilterSpec.literal("[SC]")), List.of()),
						tab("trade", List.of(FilterSpec.literal("[Trade]")), List.of(FilterSpec.literal("[bot]"))))),
				new WindowConfig("dev-all", true, List.of(
						new TabConfig("all", "All", true, false, true, true, List.of(), List.of())))));
	}

	private static TabConfig tab(String id, List<FilterSpec> include, List<FilterSpec> exclude) {
		return new TabConfig(id, id, true, false, true, false, include, exclude);
	}
}
