// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import java.util.List;

/**
 * The single per-message decision (P1 condition 2). {@code hidden}: the message is kept out of the main chat (both
 * admissions refused) and held by its tabs and the session's hidden list (§4.6, D2).
 */
public record RoutingDecision(List<TabState> deliverTo, boolean hidden, boolean ambiguous, boolean exempt) {
	static final RoutingDecision NONE = new RoutingDecision(List.of(), false, false, false);
	static final RoutingDecision EXEMPT = new RoutingDecision(List.of(), false, false, true);
}
