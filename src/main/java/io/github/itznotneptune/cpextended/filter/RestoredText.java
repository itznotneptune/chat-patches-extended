// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.util.List;

/**
 * P2 restored-message reconstruction (§4.3, Q1, Q16a, Q16b): structural recognition only, never text heuristics.
 *
 * <p>Chat Patches saves processed components built as an empty root with exactly three siblings
 * {@code [timestamp, message, dupeSlot]} (S3); the timestamp sibling always carries a digit-only insertion (S4).
 * A restored message is RECOGNIZED only if all of that holds: the matching text is then sibling 1's text, unchanged
 * (saved username formatting kept, Q16a), with the timestamp and the duplicate-counter slot dropped. Anything else is
 * AMBIGUOUS: it may route with best-effort text but is never hidden (Q16b). Boundary lines are handled before this
 * (Q10). The component itself is never modified.
 */
public final class RestoredText {
	public enum Status { RECOGNIZED, AMBIGUOUS }

	/** Outcome: the text filters match against, and whether the structure was recognized. */
	public record Result(Status status, String matchText, String reason) {
		public boolean ambiguous() { return status == Status.AMBIGUOUS; }
	}

	private RestoredText() {}

	public static Result reconstruct(Component restored) {
		if (!emptyContents(restored)) return ambiguous(restored, "root has contents");
		List<Component> siblings = restored.getSiblings();
		if (siblings.size() != 3) return ambiguous(restored, "root has " + siblings.size() + " siblings");
		String insertion = siblings.get(0).getStyle().getInsertion();
		if (insertion == null || insertion.isEmpty() || !asciiDigits(insertion)) return ambiguous(restored, "no digit-only timestamp insertion");
		return new Result(Status.RECOGNIZED, siblings.get(1).getString(), "");
	}

	/** Best-effort text for an ambiguous message: sibling 1 when there is one, else the whole text (§4.3). */
	private static Result ambiguous(Component restored, String reason) {
		List<Component> siblings = restored.getSiblings();
		String text = emptyContents(restored) && siblings.size() >= 2 ? siblings.get(1).getString() : restored.getString();
		return new Result(Status.AMBIGUOUS, text, reason);
	}

	/** Empty by content, not identity: the log is decoded by vanilla's codec, so the root is a fresh empty literal. */
	private static boolean emptyContents(Component c) {
		return c.getContents() instanceof PlainTextContents p && p.text().isEmpty();
	}

	private static boolean asciiDigits(String s) {
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch < '0' || ch > '9') return false;
		}
		return true;
	}
}
