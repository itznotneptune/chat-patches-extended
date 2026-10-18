// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One captured main-chat message (session-only, never serialized). {@code message} is the exact {@link GuiMessage}
 * instance in the main chat; every tab that receives the message shares this record and that instance.
 * {@code original} is the component as it reached {@code addMessage}, before Chat Patches' changes (V-01); it is null
 * for {@link Origin#UNOBSERVED} records, whose original was never seen.
 */
public final class MessageRecord {
	private final long seq;
	private final GuiMessage message;
	private final @Nullable Component original;
	private final Origin origin;
	private final boolean ambiguous;
	private final List<String> tabIds = new ArrayList<>(2);
	/** Position in the mirror before the current reconcile pass (reconciler bookkeeping). */
	int pos;

	MessageRecord(long seq, GuiMessage message, @Nullable Component original, Origin origin, boolean ambiguous) {
		this.seq = seq;
		this.message = message;
		this.original = original;
		this.origin = origin;
		this.ambiguous = ambiguous;
	}

	public long seq() { return seq; }
	public GuiMessage message() { return message; }
	public @Nullable Component original() { return original; }
	public Origin origin() { return origin; }
	/** Origin couldn't be trusted (degraded): routed best-effort, never hidden (Q16b, P9). */
	public boolean ambiguous() { return ambiguous; }
	public List<String> tabIds() { return Collections.unmodifiableList(tabIds); }

	void addTab(String id) { tabIds.add(id); }
}
