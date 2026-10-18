// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import net.minecraft.client.GuiMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session-only mirror of the main chat (D3, F1): one {@link MessageRecord} per main-chat entry, oldest first, and the
 * per-tab histories. A record lives while its {@link GuiMessage} is in {@code allMessages} (identity, §4.7); the
 * {@link Reconciler} keeps the two aligned at safe points. With no hiding yet, every record is visible. Client thread
 * only.
 */
public final class SessionMirror {
	private static final SessionMirror INSTANCE = new SessionMirror();

	ArrayDeque<MessageRecord> records = new ArrayDeque<>();
	private final Map<String, TabState> tabs = new LinkedHashMap<>();
	private long nextSeq = 1;

	private SessionMirror() {}

	public static SessionMirror get() {
		return INSTANCE;
	}

	public int size() { return records.size(); }
	public List<MessageRecord> records() { return new ArrayList<>(records); }
	public List<TabState> tabStates() { return new ArrayList<>(tabs.values()); }
	public TabState tab(String id) { return tabs.get(id); }

	TabState state(String id) {
		return tabs.computeIfAbsent(id, TabState::new);
	}

	Iterable<TabState> tabsView() {
		return tabs.values();
	}

	MessageRecord record(CaptureFrame f, GuiMessage message) {
		RoutingDecision d = f.decision;
		MessageRecord r = new MessageRecord(nextSeq++, message, f.original, f.origin, d != null && d.ambiguous(), f.matchText, f.reconstructionNote);
		if (d != null) {
			for (TabState t : d.deliverTo()) {
				// P4: flagged iff the tab isn't selected or its window is hidden; restored history is never flagged (Q23).
				t.add(r, f.origin != Origin.RESTORED && !Unread.readOnDelivery(t.id()));
				r.addTab(t.id());
			}
		}
		records.addLast(r);
		return r;
	}

	/** A record for a main-chat entry that was never captured: no original, no routing (never guessed). */
	MessageRecord unobserved(GuiMessage message) {
		return new MessageRecord(nextSeq++, message, null, Origin.UNOBSERVED, false);
	}

	/** Drops every record and tab entry (F3+D, or disconnect with vanillaClearing; §4.8). Suspensions stay. */
	void clear() {
		records.clear();
		for (TabState t : tabs.values()) t.clear();
	}

	/** Removes a record from every tab holding it (its unread flags will be removed here too once unread exists, P4). */
	void detach(MessageRecord r) {
		for (String id : r.tabIds()) {
			TabState t = tabs.get(id);
			if (t != null) t.remove(r);
		}
	}
}
