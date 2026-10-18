// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.mixin.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;

/**
 * P1 capture logic behind the M1-M4 injectors (§4.1). Guarantees: one decision per frame, computed by whichever of M2
 * or M3 runs first; M2 and M3 return the same value; delivery exactly once (at M3, or at M1 exit if M3 never ran);
 * M2/M3 outside a frame do nothing. Any internal error is contained: the message is admitted normally.
 */
public final class Capture {
	// Per thread, so a stray off-thread addMessage can never see the client thread's frame.
	private static final ThreadLocal<ArrayDeque<CaptureFrame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

	private Capture() {}

	/** M1 enter. Returns false if no frame was opened (the call then runs untouched). */
	public static boolean enter(ChatComponent chat, Component message, @Nullable GuiMessageTag tag) {
		try {
			if (!Minecraft.getInstance().isSameThread()) {
				Warnings.report("capture:off_thread", Warnings.Toast.NONE, "addMessage called off the client thread; not captured", null);
				return false;
			}
			ArrayDeque<CaptureFrame> frames = STACK.get();
			frames.push(new CaptureFrame(message, tag, classify(tag), frames.size() + 1));
			return true;
		} catch (RuntimeException e) {
			internalError("enter", e);
			return false;
		}
	}

	/** M2: returns whether vanilla may add the message to the visible line list. */
	public static boolean admitDisplay(GuiMessage message) {
		CaptureFrame f = STACK.get().peek();
		if (f == null) return true; // rescale, refresh, Chat Patches search: no frame, no effect (V-05)
		try {
			f.displayAdmissions++;
			return !decide(f, message).hidden();
		} catch (RuntimeException e) {
			f.failed = true;
			internalError("admitDisplay", e);
			return true;
		}
	}

	/** M3: returns whether vanilla may add the message to the message list; delivers to tabs once. */
	public static boolean admitMessages(GuiMessage message) {
		CaptureFrame f = STACK.get().peek();
		if (f == null) return true;
		try {
			f.messageAdmissions++;
			RoutingDecision d = decide(f, message);
			deliver(f, message);
			return !d.hidden();
		} catch (RuntimeException e) {
			f.failed = true;
			internalError("admitMessages", e);
			return true;
		}
	}

	/** M1 exit: delivers if M3 never ran, closes the frame, then checks liveness. */
	public static void exit(ChatComponent chat) {
		ArrayDeque<CaptureFrame> frames = STACK.get();
		CaptureFrame f = frames.poll();
		if (f == null) return;
		try {
			if (f.decision != null && !f.delivered && !f.failed && f.message != null) deliver(f, f.message);
			if (f.decision != null && f.displayAdmissions != f.messageAdmissions) {
				Warnings.report("capture:partial_admission", Warnings.Toast.NONE,
						"Another mod suppressed only one of the two main-chat admissions (outside Extended's control; architecture §8.4)", null);
				Diagnostics.event("partial_admission", "display", f.displayAdmissions, "messages", f.messageAdmissions);
			}
			if (frames.isEmpty()) SessionMirror.get().prune(allMessages(chat));
		} catch (RuntimeException e) {
			internalError("exit", e);
		}
	}

	/** M4, after the wrapped call: applies {@code cleared = vanillaClearing || !history} and checks it (§4.8, V-06). */
	public static void afterClear(ChatComponent chat, boolean history) {
		try {
			Boolean vanillaClearing = ChatPatchesAdapter.get().vanillaClearing();
			List<GuiMessage> all = allMessages(chat);
			boolean observedEmpty = all.isEmpty();
			// Degraded (rule input unreadable): follow what actually happened to the main chat.
			boolean cleared = vanillaClearing != null ? (vanillaClearing || !history) : observedEmpty;
			if (cleared) SessionMirror.get().clear();
			if (vanillaClearing != null && cleared != observedEmpty) {
				Warnings.report("clear:postcondition", Warnings.Toast.NONE,
						"Main-chat clear did not match the expected rule (cleared=" + cleared + ", empty=" + observedEmpty + ")", null);
			}
			SessionMirror.get().prune(all);
			Diagnostics.event("clear", "history", history, "vanillaClearing", vanillaClearing, "cleared", cleared, "observedEmpty", observedEmpty,
					"records", SessionMirror.get().size());
		} catch (RuntimeException e) {
			internalError("afterClear", e);
		}
	}

	/** Tick check (§4.7): catches removals made outside {@code addMessage}, such as Chat Patches' delete. */
	public static void onTick(ChatComponent chat) {
		if (!STACK.get().isEmpty()) return;
		try {
			SessionMirror.get().pruneIfChanged(allMessages(chat));
		} catch (RuntimeException e) {
			internalError("tick", e);
		}
	}

	/** Depth of the current frame stack (0 outside {@code addMessage}). */
	public static int depth() {
		return STACK.get().size();
	}

	private static RoutingDecision decide(CaptureFrame f, GuiMessage message) {
		if (f.decision == null) {
			f.message = message;
			f.decision = Router.get().decide(f);
		}
		return f.decision;
	}

	private static void deliver(CaptureFrame f, GuiMessage message) {
		if (f.delivered) return;
		f.delivered = true;
		MessageRecord r = SessionMirror.get().record(f, message);
		if (Diagnostics.ENABLED) {
			Diagnostics.event("deliver", "seq", r.seq(), "origin", r.origin(), "ambiguous", r.ambiguous(), "depth", f.depth,
					"exempt", f.decision != null && f.decision.exempt(), "tabs", r.tabIds());
		}
	}

	private static Origin classify(@Nullable GuiMessageTag tag) {
		GuiMessageTag restored = ChatPatchesAdapter.get().restoredTag();
		if (restored == null) return Origin.UNKNOWN;
		return tag == restored ? Origin.RESTORED : Origin.LIVE;
	}

	static List<GuiMessage> allMessages(ChatComponent chat) {
		return ((ChatComponentAccessor) chat).cpextended$getAllMessages();
	}

	private static void internalError(String where, RuntimeException e) {
		Warnings.report("internal:" + where + ":" + e.getClass().getName(), Warnings.Toast.INTERNAL,
				"Internal error in capture (" + where + "); the message was handled normally", e);
	}
}
