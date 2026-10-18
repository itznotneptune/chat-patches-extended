// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Keeps the session mirror and tab histories aligned with the main chat ({@code allMessages}, the authority) by
 * identity and order. Runs only at safe points on the client thread: the end of a client tick, which is never inside
 * {@code addMessage} capture or a screen input handler. It reads the main chat and never modifies it, nor Chat
 * Patches' log.
 *
 * <p>One pass:
 * <ol>
 * <li>closes capture frames left open (abandoned); a frame that had decided but not delivered is delivered now, only
 * if its message really is in the main chat;</li>
 * <li>drops the oldest records that vanilla trimmed at the cap (common case, no allocation);</li>
 * <li>if the mirror still doesn't equal the main chat entry for entry, rebuilds it in main-chat order: kept records
 * keep their routing; records with no surviving entry are removed from every tab; entries never captured become
 * {@link Origin#UNOBSERVED} records (no original, no routing, nothing guessed); tab histories follow the new order.</li>
 * </ol>
 * The result depends only on the mirror and the main chat, so a second pass with no new traffic changes nothing.
 */
public final class Reconciler {
	/** What one pass found and did. {@code aligned} means the mirror already matched (no rebuild). */
	public record Report(boolean aligned, int abandonedFrames, int recoveredFromFrames, int trimmed, int stale, int unobserved,
			boolean reordered, long nanos) {
		public boolean changed() {
			return abandonedFrames > 0 || trimmed > 0 || stale > 0 || unobserved > 0 || reordered;
		}
	}

	private static Report last = new Report(true, 0, 0, 0, 0, 0, false, 0);
	private static long maxNanos;
	private static long passes;
	private static long rebuilds;

	private Reconciler() {}

	/** Runs one pass. Call only at a safe point on the client thread (outside addMessage and screen input handling). */
	public static Report run(ChatComponent chat) {
		long t0 = System.nanoTime();
		SessionMirror mirror = SessionMirror.get();
		List<GuiMessage> all = Capture.allMessages(chat);

		int abandoned = 0, recovered = 0;
		List<CaptureFrame> open = Capture.drainFrames();
		for (CaptureFrame f : open) {
			abandoned++;
			if (f.decision != null && !f.delivered && !f.failed && f.message != null && containsIdentity(all, f.message)) {
				f.delivered = true;
				mirror.record(f, f.message);
				recovered++;
			}
		}

		int trimmed = trimOldest(mirror, all);
		Report r;
		if (aligned(mirror.records, all)) {
			r = new Report(true, abandoned, recovered, trimmed, 0, 0, false, System.nanoTime() - t0);
		} else {
			r = rebuild(mirror, all, abandoned, recovered, trimmed, t0);
			rebuilds++;
		}
		passes++;
		last = r;
		maxNanos = Math.max(maxNanos, r.nanos());
		if (r.abandonedFrames() > 0 || r.unobserved() > 0 || r.reordered() || r.stale() > 0) report(r);
		return r;
	}

	private static int trimOldest(SessionMirror mirror, List<GuiMessage> all) {
		int n = all.size();
		int trimmed = 0;
		if (n == 0) return 0;
		GuiMessage oldest = all.get(n - 1);
		ArrayDeque<MessageRecord> records = mirror.records;
		while (records.size() > n && records.peekFirst().message() != oldest) {
			mirror.detach(records.pollFirst());
			trimmed++;
		}
		return trimmed;
	}

	/** True if the mirror (oldest first) equals the main chat (newest first) entry for entry, by identity. */
	private static boolean aligned(ArrayDeque<MessageRecord> records, List<GuiMessage> all) {
		int n = all.size();
		if (records.size() != n) return false;
		Iterator<MessageRecord> it = records.iterator();
		for (int i = n - 1; i >= 0; i--) {
			if (it.next().message() != all.get(i)) return false;
		}
		return true;
	}

	private static Report rebuild(SessionMirror mirror, List<GuiMessage> all, int abandoned, int recovered, int trimmed, long t0) {
		// Pool the existing records by message identity, remembering their current order.
		Map<GuiMessage, ArrayDeque<MessageRecord>> pool = new IdentityHashMap<>(mirror.records.size() * 2);
		int pos = 0;
		for (MessageRecord rec : mirror.records) {
			rec.pos = pos++;
			pool.computeIfAbsent(rec.message(), k -> new ArrayDeque<>(1)).addLast(rec);
		}
		// Walk the main chat oldest to newest; each entry takes the earliest unused record for its message.
		ArrayDeque<MessageRecord> next = new ArrayDeque<>(Math.max(16, all.size()));
		int unobserved = 0;
		int lastPos = -1;
		boolean reordered = false;
		for (int i = all.size() - 1; i >= 0; i--) {
			GuiMessage g = all.get(i);
			ArrayDeque<MessageRecord> candidates = pool.get(g);
			MessageRecord rec = candidates == null ? null : candidates.pollFirst();
			if (rec == null) {
				rec = mirror.unobserved(g);
				unobserved++;
			} else {
				if (rec.pos < lastPos) reordered = true;
				lastPos = rec.pos;
			}
			next.addLast(rec);
		}
		int stale = 0;
		for (ArrayDeque<MessageRecord> left : pool.values()) {
			for (MessageRecord rec : left) {
				mirror.detach(rec);
				stale++;
			}
		}
		mirror.records = next;
		if (reordered) for (TabState t : mirror.tabsView()) t.reorder(next);
		return new Report(false, abandoned, recovered, trimmed, stale, unobserved, reordered, System.nanoTime() - t0);
	}

	private static boolean containsIdentity(List<GuiMessage> all, GuiMessage m) {
		for (GuiMessage g : all) if (g == m) return true;
		return false;
	}

	private static void report(Report r) {
		if (r.abandonedFrames() > 0) {
			Warnings.report("reconcile:abandoned_frames", Warnings.Toast.NONE,
					"Capture frames were left open outside addMessage; closed by the reconciler", null);
		}
		if (r.unobserved() > 0) {
			Warnings.report("reconcile:unobserved", Warnings.Toast.NONE,
					"Main-chat entries were added without passing through addMessage (another mod); kept in the main chat, not routed", null);
		}
		if (r.reordered()) {
			Warnings.report("reconcile:reordered", Warnings.Toast.NONE, "Main-chat order changed outside addMessage; tabs follow the new order", null);
		}
		Diagnostics.event("reconcile", "abandonedFrames", r.abandonedFrames(), "recoveredFromFrames", r.recoveredFromFrames(),
				"trimmed", r.trimmed(), "stale", r.stale(), "unobserved", r.unobserved(), "reordered", r.reordered(), "micros", r.nanos() / 1000);
	}

	public static Report last() { return last; }
	public static long maxNanos() { return maxNanos; }
	public static long passes() { return passes; }
	public static long rebuilds() { return rebuilds; }

	/** Resets the timing statistics (measurement windows in tests and diagnostics). */
	public static void resetStats() {
		maxNanos = 0;
		passes = 0;
		rebuilds = 0;
	}
}
