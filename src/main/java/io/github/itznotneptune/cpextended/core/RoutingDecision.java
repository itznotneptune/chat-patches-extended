// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import java.util.List;

/**
 * The single per-message decision (P1 condition 2). {@code hidden} is always false until Phase 7: messages are never
 * removed from the main chat yet.
 */
public record RoutingDecision(List<TabState> deliverTo, boolean hidden, boolean ambiguous, boolean exempt) {
	static final RoutingDecision NONE = new RoutingDecision(List.of(), false, false, false);
	static final RoutingDecision EXEMPT = new RoutingDecision(List.of(), false, false, true);
}
