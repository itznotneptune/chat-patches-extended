// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Session state of one tab: its delivered history (oldest first) and P9 runtime suspension. Suspension lasts until
 * relaunch; nothing lifts it automatically, and it is never written to configuration (Q37 open).
 */
public final class TabState {
	private final String id;
	private final LinkedHashSet<MessageRecord> entries = new LinkedHashSet<>();
	private @Nullable String suspensionReason;

	TabState(String id) {
		this.id = id;
	}

	public String id() { return id; }
	public boolean isRuntimeSuspended() { return suspensionReason != null; }
	public @Nullable String suspensionReason() { return suspensionReason; }
	public int size() { return entries.size(); }
	public List<MessageRecord> entries() { return new ArrayList<>(entries); }

	void suspend(String reason) {
		if (suspensionReason == null) suspensionReason = reason;
	}

	void add(MessageRecord r) { entries.add(r); }
	void remove(MessageRecord r) { entries.remove(r); }
	void clear() { entries.clear(); }

	boolean contains(MessageRecord r) { return entries.contains(r); }

	/** Reorders the entries to follow {@code order} (the mirror's order); entries not in {@code order} are dropped. */
	void reorder(Iterable<MessageRecord> order) {
		LinkedHashSet<MessageRecord> next = new LinkedHashSet<>();
		for (MessageRecord r : order) if (entries.contains(r)) next.add(r);
		entries.clear();
		entries.addAll(next);
	}
}
