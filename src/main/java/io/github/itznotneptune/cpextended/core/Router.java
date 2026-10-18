// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.filter.MatchText;
import io.github.itznotneptune.cpextended.filter.RestoredText;
import io.github.itznotneptune.cpextended.model.RoutingConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless routing (§4.5) with the P9 runtime filter guard (§4.4). Every tab is evaluated before the one decision is
 * returned; a tab whose matcher throws is runtime-suspended and treated as not matching. Nothing propagates out.
 */
public final class Router {
	private static final Router INSTANCE = new Router();

	private volatile RoutingConfig config = RoutingConfig.EMPTY;

	private Router() {}

	public static Router get() {
		return INSTANCE;
	}

	public RoutingConfig config() {
		return config;
	}

	/** Replaces the routing snapshot; messages already delivered are unaffected (Q9). */
	public void install(RoutingConfig newConfig) {
		config = newConfig;
		Diagnostics.event("config_installed", "tabs", newConfig.tabs().size(), "configSuspended", newConfig.configErrorSummary().keySet().toString());
	}

	RoutingDecision decide(CaptureFrame f) {
		RoutingConfig cfg = config; // one snapshot per message
		if (cfg.tabs().isEmpty()) return RoutingDecision.NONE;

		// Chat Patches' boundary line is exempt and always stays in the main chat (Q10). Chat Patches recognizes it by the
		// insertion on sibling 1 of its processed structure, so a live boundary line (whose original lacks that structure)
		// is checked on the processed main-chat content too; a restored one carries it already.
		ChatPatchesAdapter adapter = ChatPatchesAdapter.get();
		if (Boolean.TRUE.equals(adapter.isBoundaryLine(f.original))
				|| f.message != null && Boolean.TRUE.equals(adapter.isBoundaryLine(f.message.content()))) return RoutingDecision.EXEMPT;

		// Restored history (Q1, Q4, Q16): the P2 structural reconstruction gives the matching text; an unrecognized
		// structure is AMBIGUOUS (routed best-effort, never hidden, Q16b). Only tabs with restoreHistory receive it.
		boolean restored = f.origin == Origin.RESTORED;
		boolean ambiguous = f.origin == Origin.UNKNOWN;
		String matchText;
		if (restored) {
			RestoredText.Result rt = RestoredText.reconstruct(f.original);
			matchText = rt.matchText();
			ambiguous = rt.ambiguous();
			f.reconstructionNote = rt.ambiguous() ? rt.reason() : null;
		} else {
			matchText = f.original.getString();
		}
		f.matchText = matchText;

		// Exact matching text, no normalization (Q12); prepared once and shared by every tab (R-1).
		MatchText text = MatchText.reusable(matchText);
		List<TabState> deliver = new ArrayList<>();
		SessionMirror mirror = SessionMirror.get();
		for (RoutingConfig.CompiledTab tab : cfg.tabs()) {
			if (tab.suspended()) continue; // invalid filters are never executed (Q14, Q28)
			if (restored && !tab.config().restoreHistory()) continue; // per-tab restored history (Q4)
			TabState state = mirror.state(tab.id());
			if (state.isRuntimeSuspended()) continue;
			boolean match;
			try {
				match = tab.matches(text);
			} catch (RuntimeException e) {
				match = false;
				state.suspend(e.getClass().getName());
				Warnings.report("tab:" + tab.id() + ":matcher:" + e.getClass().getName(), Warnings.Toast.TAB_SUSPENDED,
						"Tab '" + tab.id() + "' filter evaluation failed; tab suspended until relaunch", e, tab.config().name());
				Diagnostics.event("tab_suspended", "tab", tab.id(), "exception", e.getClass().getName());
			}
			if (match) deliver.add(state);
		}
		return new RoutingDecision(List.copyOf(deliver), false, ambiguous, false);
	}
}
