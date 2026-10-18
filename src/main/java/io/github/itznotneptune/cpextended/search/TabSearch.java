// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.search;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.screens.ChatScreen;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Global search over Extended tabs (Q29a, P13, Q31), driven by Chat Patches' own search field and settings (P12).
 * Display only: it never changes routing, hiding, history, tab ownership, unread or configuration.
 *
 * <p>Active exactly when Chat Patches' search is: chat screen open, {@code config.search} on, the bar shown, and a
 * non-empty query. Each tab is searched in its own retained history:
 * <ul>
 * <li>plain text: Chat Patches' semantics (S24): {@code stripFormatting(content)} contains the query, case per
 * {@code caseSensitive}. Evaluated by Extended; new arrivals are checked incrementally against the current query;</li>
 * <li>regex mode (Q31): an entry matches only if Chat Patches' own result list holds its message (identity). Extended
 * never runs a regex. Entries Chat Patches didn't evaluate (later arrivals) are reported as partial results;</li>
 * <li>zero matches: the tab's full history if {@code searchEmptyShowsFullChat}, else an empty view with "No matches".</li>
 * <li>invalid regex (Chat Patches' {@code searchError}, P12 amendment): like Chat Patches' own chat, every tab shows its
 * full history, marked "Invalid search expression"; Chat Patches' stale result list is never used as matches.</li>
 * </ul>
 */
public final class TabSearch {
	public enum Indicator { NONE, NO_MATCHES, PARTIAL, INVALID }

	/** What a window shows for one tab this frame. {@code entries} is oldest first. */
	public record View(boolean searching, List<MessageRecord> entries, Indicator indicator) {}

	/** The search state read once per frame. */
	public record State(boolean active, String query, boolean caseSensitive, boolean regex, boolean emptyShowsFull,
			@Nullable Set<GuiMessage> regexResults, boolean invalidRegex, long signature) {
		static final State INACTIVE = new State(false, "", false, false, false, null, false, 0);
	}

	private static final class Cache {
		long signature;
		long structureVersion = -1;
		long appended, headRemoved;
		final ArrayDeque<MessageRecord> matches = new ArrayDeque<>(); // oldest first
		@Nullable List<MessageRecord> snapshot;
	}

	private static State state = State.INACTIVE;
	private static final Map<String, Cache> CACHES = new HashMap<>();
	private static long rebuilds, incrementalChecks, lastChange;
	/** Highest record sequence present when Chat Patches last (re)evaluated its results (regex partial indicator). */
	private static long evaluatedUpTo;
	private static long lastRegexSignature;
	private static String lastRegexQuery = "";

	private TabSearch() {}

	/** Reads Chat Patches' search state for this frame (client thread, chat-open path). */
	public static State update(@Nullable ChatScreen screen) {
		State prev = state;
		state = read(screen);
		if (state.signature() != prev.signature()) {
			lastChange++;
			Diagnostics.event("search_state", "active", state.active(), "regex", state.regex(), "queryLength", state.query().length());
		}
		return state;
	}

	private static State read(@Nullable ChatScreen screen) {
		if (screen == null) return State.INACTIVE;
		ChatPatchesAdapter a = ChatPatchesAdapter.get();
		if (a.isSearchIntegrationOff()) return State.INACTIVE;
		ChatPatchesAdapter.SearchSettings s = a.searchSettings();
		if (s == null || !s.enabled()) return State.INACTIVE;
		if (!Boolean.TRUE.equals(a.isSearchBarShown())) return State.INACTIVE;
		ChatPatchesAdapter.SearchRead q = a.searchQuery(screen);
		if (q.status() != ChatPatchesAdapter.SearchRead.Status.AVAILABLE || q.query() == null || q.query().isEmpty()) return State.INACTIVE;
		Set<GuiMessage> results = null;
		long sig = Objects.hash(q.query(), s.caseSensitive(), s.regex(), s.emptyShowsFullChat());
		if (s.regex()) {
			switch (a.regexState(screen)) {
				case FAILED: return State.INACTIVE; // Class Q, already reported: search integration off
				case INVALID:
					// Chat Patches' results are stale for this query; the next valid query must count as a fresh evaluation.
					lastRegexSignature = Long.MIN_VALUE;
					return new State(true, q.query(), s.caseSensitive(), true, s.emptyShowsFullChat(), null, true, sig * 31 + 7);
				case VALID: break;
			}
			List<GuiMessage> list = a.searchResults(screen);
			if (list == null) return State.INACTIVE; // regex-mode tab results unavailable (Class Q, already reported)
			long rs = list.size();
			for (GuiMessage g : list) rs = rs * 31 + System.identityHashCode(g);
			if (rs == lastRegexSignature && state.regexResults() != null) {
				results = state.regexResults(); // unchanged results: no per-frame copy
			} else {
				results = Collections.newSetFromMap(new IdentityHashMap<>(list.size() * 2 + 1));
				results.addAll(list);
			}
			if (rs != lastRegexSignature || !q.query().equals(lastRegexQuery)) {
				// Chat Patches re-evaluates on every field change: everything present now was evaluated by it.
				lastRegexSignature = rs;
				lastRegexQuery = q.query();
				evaluatedUpTo = maxSeq();
			}
			sig = sig * 31 + rs;
		}
		return new State(true, q.query(), s.caseSensitive(), s.regex(), s.emptyShowsFullChat(), results, false, sig == 0 ? 1 : sig);
	}

	private static long maxSeq() {
		var recs = SessionMirror.get().records();
		return recs.isEmpty() ? 0 : recs.get(recs.size() - 1).seq();
	}

	public static State state() { return state; }

	/** Bumped whenever the search state changes (query, settings, Chat Patches' regex results); used for scroll resets. */
	public static long changeCount() { return lastChange; }

	/** The tab's view this frame: unfiltered while no search is active. */
	public static View view(String tabId, @Nullable TabState tab) {
		List<MessageRecord> all = tab == null ? List.of() : tab.entries();
		State s = state;
		if (!s.active()) return new View(false, all, Indicator.NONE);
		if (s.invalidRegex()) return new View(true, all, Indicator.INVALID); // no matches claimed; the cache is left alone
		Cache c = CACHES.computeIfAbsent(tabId, k -> new Cache());
		if (c.signature != s.signature() || tab == null || c.structureVersion != tab.structureVersion()) {
			rebuild(c, s, all, tab);
		} else {
			extend(c, s, tab, all);
		}
		// Regex mode: entries Chat Patches never evaluated (later arrivals) make the result partial (Q31).
		boolean unevaluated = s.regex() && !all.isEmpty() && all.get(all.size() - 1).seq() > evaluatedUpTo;
		if (c.matches.isEmpty()) {
			if (s.emptyShowsFull()) return new View(true, all, Indicator.NONE);
			return new View(true, List.of(), unevaluated ? Indicator.PARTIAL : Indicator.NO_MATCHES);
		}
		if (c.snapshot == null) c.snapshot = List.copyOf(c.matches);
		return new View(true, c.snapshot, unevaluated ? Indicator.PARTIAL : Indicator.NONE);
	}

	private static void rebuild(Cache c, State s, List<MessageRecord> all, @Nullable TabState tab) {
		c.matches.clear();
		for (MessageRecord r : all) if (matches(s, r)) c.matches.add(r);
		c.signature = s.signature();
		c.structureVersion = tab == null ? -1 : tab.structureVersion();
		c.appended = tab == null ? 0 : tab.appendedCount();
		c.headRemoved = tab == null ? 0 : tab.headRemovedCount();
		c.snapshot = null;
		rebuilds++;
	}

	/**
	 * Same query, settings and (regex mode) Chat Patches results, same structure: drops matches trimmed from the oldest end and checks only the entries
	 * appended since the last frame (P13 live arrivals, V-13).
	 */
	private static void extend(Cache c, State s, TabState tab, List<MessageRecord> all) {
		long trimmed = tab.headRemovedCount() - c.headRemoved, added = tab.appendedCount() - c.appended;
		if (trimmed == 0 && added == 0) return;
		if (added > all.size()) { rebuild(c, s, all, tab); return; } // can't happen; stay correct anyway
		if (trimmed > 0) {
			// The trimmed entries were the oldest ones, so any trimmed match is at the front of the deque.
			MessageRecord oldest = all.isEmpty() ? null : all.getFirst();
			while (!c.matches.isEmpty() && c.matches.peekFirst() != oldest && !tab.contains(c.matches.peekFirst())) c.matches.pollFirst();
		}
		for (int i = all.size() - (int) added; i < all.size(); i++) {
			MessageRecord r = all.get(i);
			incrementalChecks++;
			if (matches(s, r)) c.matches.addLast(r);
		}
		c.appended = tab.appendedCount();
		c.headRemoved = tab.headRemovedCount();
		c.snapshot = null;
	}

	static boolean matches(State s, MessageRecord r) {
		if (s.regex()) return s.regexResults() != null && s.regexResults().contains(r.message());
		String text = ChatFormatting.stripFormatting(r.message().content().getString());
		if (text == null) return false;
		return s.caseSensitive() ? text.contains(s.query()) : Strings.CI.contains(text, s.query());
	}

	public static long rebuilds() { return rebuilds; }
	public static long incrementalChecks() { return incrementalChecks; }
}
