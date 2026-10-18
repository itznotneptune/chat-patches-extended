// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import net.minecraft.client.GuiMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session-only mirror of the main chat (D3, F1): one {@link MessageRecord} per captured message, oldest first, and the
 * per-tab histories. A record lives while its {@link GuiMessage} is in {@code allMessages} (identity, §4.7); with no
 * hiding in Milestone 1a every record is visible. Client thread only.
 */
public final class SessionMirror {
	private static final SessionMirror INSTANCE = new SessionMirror();

	private final ArrayDeque<MessageRecord> records = new ArrayDeque<>();
	private final Map<String, TabState> tabs = new LinkedHashMap<>();
	private long nextSeq = 1;
	private int lastSize = -1;
	private GuiMessage lastNewest;
	private GuiMessage lastOldest;
	private int fullReconciles;

	private SessionMirror() {}

	public static SessionMirror get() {
		return INSTANCE;
	}

	public int size() { return records.size(); }
	public List<MessageRecord> records() { return new ArrayList<>(records); }
	public List<TabState> tabStates() { return new ArrayList<>(tabs.values()); }
	public TabState tab(String id) { return tabs.get(id); }
	public int fullReconciles() { return fullReconciles; }

	TabState state(String id) {
		return tabs.computeIfAbsent(id, TabState::new);
	}

	MessageRecord record(CaptureFrame f, GuiMessage message) {
		RoutingDecision d = f.decision;
		MessageRecord r = new MessageRecord(nextSeq++, message, f.original, f.origin, d != null && d.ambiguous());
		if (d != null) {
			for (TabState t : d.deliverTo()) {
				t.add(r);
				r.addTab(t.id());
			}
		}
		records.addLast(r);
		return r;
	}

	/** Drops every record and tab entry (F3+D, or disconnect with vanillaClearing; §4.8). Suspensions stay. */
	void clear() {
		records.clear();
		for (TabState t : tabs.values()) t.clear();
		remember(List.of());
	}

	/** Liveness check run on ticks: only does work when the main chat changed. */
	void pruneIfChanged(List<GuiMessage> all) {
		int n = all.size();
		GuiMessage newest = n == 0 ? null : all.get(0);
		GuiMessage oldest = n == 0 ? null : all.get(n - 1);
		if (n == lastSize && newest == lastNewest && oldest == lastOldest) return;
		prune(all);
	}

	/** Removes records whose message is no longer in {@code all} (newest first, as in {@code ChatComponent}). */
	void prune(List<GuiMessage> all) {
		int n = all.size();
		if (n == 0) {
			if (!records.isEmpty()) removeAll();
			remember(all);
			return;
		}
		// Common case: vanilla trimmed the oldest entries at the cap. Drop oldest records until they line up.
		GuiMessage oldest = all.get(n - 1);
		while (records.size() > n && records.peekFirst().message() != oldest) remove(records.pollFirst());
		boolean aligned = records.size() == n && records.peekFirst().message() == oldest && records.peekLast().message() == all.get(0);
		if (!aligned) reconcile(all);
		remember(all);
	}

	private void reconcile(List<GuiMessage> all) {
		fullReconciles++;
		Set<GuiMessage> alive = Collections.newSetFromMap(new IdentityHashMap<>(all.size() * 2));
		alive.addAll(all);
		for (Iterator<MessageRecord> it = records.iterator(); it.hasNext(); ) {
			MessageRecord r = it.next();
			if (!alive.contains(r.message())) {
				it.remove();
				detach(r);
			}
		}
	}

	private void remove(MessageRecord r) {
		detach(r);
	}

	private void removeAll() {
		for (MessageRecord r : records) detach(r);
		records.clear();
	}

	private void detach(MessageRecord r) {
		for (String id : r.tabIds()) {
			TabState t = tabs.get(id);
			if (t != null) t.remove(r);
		}
	}

	private void remember(List<GuiMessage> all) {
		lastSize = all.size();
		lastNewest = all.isEmpty() ? null : all.get(0);
		lastOldest = all.isEmpty() ? null : all.get(all.size() - 1);
	}
}
