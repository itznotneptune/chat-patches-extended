// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import net.minecraft.client.GuiMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session-only mirror of the main chat (D3, F1): one {@link MessageRecord} per main-chat entry, oldest first, the
 * session's hidden list (messages excluded from the main chat, oldest first, §4.7) and the per-tab histories. A visible
 * record lives while its {@link GuiMessage} is in {@code allMessages} (identity); the {@link Reconciler} keeps the two
 * aligned at safe points. A hidden record lives until hidden-list retention prunes it, the chat is cleared, or orphan
 * restoration moves it back to the main chat. Nothing here is ever written to disk. Client thread only.
 */
public final class SessionMirror {
	private static final SessionMirror INSTANCE = new SessionMirror();

	ArrayDeque<MessageRecord> records = new ArrayDeque<>();
	/** Hidden records, oldest first (F1). */
	ArrayDeque<MessageRecord> hidden = new ArrayDeque<>();
	Map<String, TabState> tabs = new LinkedHashMap<>();
	private long nextSeq = 1;

	private SessionMirror() {}

	public static SessionMirror get() {
		return INSTANCE;
	}

	public int size() { return records.size(); }
	public List<MessageRecord> records() { return new ArrayList<>(records); }
	public int hiddenSize() { return hidden.size(); }
	public List<MessageRecord> hiddenRecords() { return new ArrayList<>(hidden); }
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
		boolean hide = d != null && d.hidden();
		if (hide) r.assign(true, r.tabIds());
		if (d != null) {
			for (TabState t : d.deliverTo()) {
				// P4: flagged iff the tab isn't selected or its window is hidden; restored history is never flagged (Q23).
				t.add(r, f.origin != Origin.RESTORED && !Unread.readOnDelivery(t.id()));
				r.addTab(t.id());
			}
		}
		if (hide) {
			hidden.addLast(r);
			enforceHiddenCap();
		} else {
			records.addLast(r);
		}
		return r;
	}

	/**
	 * P7: the hidden list's own session cap, equal to Chat Patches' {@code chatMaxMessages} read through the adapter
	 * (16,384 if unreadable; the adapter is then degraded and nothing new is hidden). Oldest first; each pruned record
	 * leaves every tab holding it, with its unread flags. The main chat is never touched. Ordinary retention, not orphan
	 * restoration (Q27). Pruned records never come back.
	 */
	void enforceHiddenCap() {
		java.util.OptionalInt cap = io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter.get().chatMaxMessages();
		int limit = cap.isPresent() ? cap.getAsInt() : FALLBACK_HIDDEN_CAP;
		int pruned = 0;
		while (hidden.size() > limit) {
			MessageRecord old = hidden.pollFirst();
			detach(old);
			pruned++;
		}
		if (pruned > 0) {
			hiddenPruned += pruned;
			Diagnostics.event("hidden_pruned", "count", pruned, "cap", limit, "retained", hidden.size());
		}
	}

	static final int FALLBACK_HIDDEN_CAP = 16384;
	private long hiddenPruned;

	/** Hidden records pruned by the P7 cap this session (diagnostics, V-48). */
	public long hiddenPruned() { return hiddenPruned; }

	/** A record for a main-chat entry that was never captured: no original, no routing (never guessed). */
	MessageRecord unobserved(GuiMessage message) {
		return new MessageRecord(nextSeq++, message, null, Origin.UNOBSERVED, false);
	}

	/** Drops every record and tab entry (F3+D, or disconnect with vanillaClearing; §4.8). Suspensions stay. */
	void clear() {
		records.clear();
		hidden.clear();
		for (TabState t : tabs.values()) t.clear();
	}

	/** Removes a record from every tab holding it (its unread flags will be removed here too once unread exists, P4). */
	void detach(MessageRecord r) {
		for (String id : r.tabIds()) {
			TabState t = tabs.get(id);
			if (t != null) t.remove(r);
		}
	}

	// ---------------------------------------------------------------- development only

	/**
	 * Development builds only (Q36 verification without enabling hiding): records {@code message} as a hidden message
	 * delivered to {@code tabIds}, exactly as a hidden capture would leave it: in the hidden list and those tabs, not in
	 * the main chat. Unread follows the delivery rule.
	 */
	public MessageRecord devAddHidden(GuiMessage message, net.minecraft.network.chat.Component original, List<String> tabIds) {
		io.github.itznotneptune.cpextended.dev.DevHooks.requireDev();
		MessageRecord r = new MessageRecord(nextSeq++, message, original, Origin.LIVE, false, original.getString(), null);
		r.assign(true, List.of());
		for (String id : tabIds) {
			TabState t = state(id);
			t.add(r, !Unread.readOnDelivery(id));
			r.addTab(id);
		}
		hidden.addLast(r);
		return r;
	}

	/** Development builds only: drops every hidden record (fixture cleanup between test scenarios). */
	public void devClearHidden() {
		io.github.itznotneptune.cpextended.dev.DevHooks.requireDev();
		for (MessageRecord r : hidden) detach(r);
		hidden.clear();
	}
}
