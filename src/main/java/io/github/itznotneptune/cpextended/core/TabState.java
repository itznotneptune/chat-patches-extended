// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Session state of one tab: its delivered history (oldest first), each entry's own unread flag (P4), and P9 runtime
 * suspension. The unread count is derived from the retained flagged entries; there is no separate counter, and removing
 * an entry removes its flag (P4.4). Suspension lasts until relaunch; nothing lifts it automatically, and it is never
 * written to configuration (Q37 open). Session-only.
 */
public final class TabState {
	private final String id;
	private final LinkedHashSet<MessageRecord> entries = new LinkedHashSet<>();
	/** Flagged entries; always a subset of {@link #entries}. */
	private final java.util.Set<MessageRecord> unread = new java.util.HashSet<>();
	private @Nullable String suspensionReason;

	TabState(String id) {
		this.id = id;
	}

	public String id() { return id; }
	public boolean isRuntimeSuspended() { return suspensionReason != null; }
	public @Nullable String suspensionReason() { return suspensionReason; }
	public int size() { return entries.size(); }
	public List<MessageRecord> entries() { return new ArrayList<>(entries); }
	/** Number of retained entries flagged unread (P4: derived from the flags). */
	public int unreadCount() { return unread.size(); }
	public boolean isUnread(MessageRecord r) { return unread.contains(r); }

	/** Clears every flag of this tab (tab selected, or its selected window became visible; P4.5, P4.6). */
	public void markAllRead() { unread.clear(); }

	void suspend(String reason) {
		if (suspensionReason == null) suspensionReason = reason;
	}

	void add(MessageRecord r, boolean flagged) {
		if (entries.add(r) && flagged) unread.add(r);
	}
	void remove(MessageRecord r) {
		entries.remove(r);
		unread.remove(r);
	}
	void clear() {
		entries.clear();
		unread.clear();
	}

	boolean contains(MessageRecord r) { return entries.contains(r); }

	/** Reorders the entries to follow {@code order} (the mirror's order); entries not in {@code order} are dropped. */
	void reorder(Iterable<MessageRecord> order) {
		LinkedHashSet<MessageRecord> next = new LinkedHashSet<>();
		for (MessageRecord r : order) if (entries.contains(r)) next.add(r);
		entries.clear();
		entries.addAll(next);
		unread.retainAll(entries);
	}
}
