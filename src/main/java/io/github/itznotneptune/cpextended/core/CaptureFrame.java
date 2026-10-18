// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** State of one {@code addMessage} call (one M1 frame). Frames stack, so a nested call gets its own (§4.1). */
final class CaptureFrame {
	final Component original;
	final @Nullable GuiMessageTag tag;
	final Origin origin;
	final int depth;
	@Nullable GuiMessage message;
	@Nullable RoutingDecision decision;
	/** The text the filters matched (restored: the P2 reconstruction); set with the decision. */
	@Nullable String matchText;
	/** Why a restored message's structure wasn't recognized (P2), or null. */
	@Nullable String reconstructionNote;
	boolean delivered;
	boolean failed;
	int displayAdmissions;
	int messageAdmissions;

	CaptureFrame(Component original, @Nullable GuiMessageTag tag, Origin origin, int depth) {
		this.original = original;
		this.tag = tag;
		this.origin = origin;
		this.depth = depth;
	}
}
