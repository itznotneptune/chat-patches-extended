// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
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
	/** Bumped when entries are removed (other than the oldest), cleared or reordered: search caches rebuild. */
	private long structureVersion;
	/** Entries appended and oldest entries removed (cap trimming) since creation: search caches follow these incrementally. */
	private long appended, headRemoved;
	private @Nullable List<MessageRecord> snapshot;

	TabState(String id) {
		this.id = id;
	}

	public String id() { return id; }
	public boolean isRuntimeSuspended() { return suspensionReason != null; }
	public @Nullable String suspensionReason() { return suspensionReason; }
	public int size() { return entries.size(); }
	public long structureVersion() { return structureVersion; }
	public long appendedCount() { return appended; }
	public long headRemovedCount() { return headRemoved; }
	/** Entries newest first, without copying (client thread only). */
	public Iterable<MessageRecord> newestFirst() { return entries.reversed(); }
	/** Entries oldest first: an immutable snapshot, rebuilt only after a change (no per-frame copy). */
	public List<MessageRecord> entries() {
		List<MessageRecord> s = snapshot;
		if (s == null) snapshot = s = List.copyOf(entries);
		return s;
	}
	/** Number of retained entries flagged unread (P4: derived from the flags). */
	public int unreadCount() { return unread.size(); }
	public boolean isUnread(MessageRecord r) { return unread.contains(r); }

	/** Clears every flag of this tab (tab selected, or its selected window became visible; P4.5, P4.6). */
	public void markAllRead() { unread.clear(); }

	void suspend(String reason) {
		if (suspensionReason == null) suspensionReason = reason;
	}

	void add(MessageRecord r, boolean flagged) {
		if (entries.add(r)) {
			appended++;
			snapshot = null;
			if (flagged) unread.add(r);
		}
	}
	void remove(MessageRecord r) {
		if (!entries.isEmpty() && entries.getFirst() == r) {
			entries.removeFirst();
			headRemoved++;
			snapshot = null;
		} else if (entries.remove(r)) {
			structureVersion++;
			snapshot = null;
		}
		unread.remove(r);
	}
	void clear() {
		entries.clear();
		unread.clear();
		structureVersion++;
		snapshot = null;
	}

	public boolean contains(MessageRecord r) { return entries.contains(r); }

	/**
	 * Reorders the visible entries to follow {@code order} (the mirror's order); visible entries not in {@code order} are
	 * dropped. Hidden entries aren't in the main chat, so they stay, placed by capture sequence among the visible ones.
	 */
	void reorder(Iterable<MessageRecord> order) {
		ArrayDeque<MessageRecord> hiddenEntries = new ArrayDeque<>();
		for (MessageRecord r : entries) if (r.hidden()) hiddenEntries.addLast(r);
		LinkedHashSet<MessageRecord> next = new LinkedHashSet<>();
		for (MessageRecord r : order) {
			if (!entries.contains(r)) continue;
			while (!hiddenEntries.isEmpty() && hiddenEntries.peekFirst().seq() < r.seq()) next.add(hiddenEntries.pollFirst());
			next.add(r);
		}
		next.addAll(hiddenEntries);
		entries.clear();
		entries.addAll(next);
		unread.retainAll(entries);
		structureVersion++;
		snapshot = null;
	}
}
