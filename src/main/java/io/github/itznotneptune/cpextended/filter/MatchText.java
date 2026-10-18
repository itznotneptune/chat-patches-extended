// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

/**
 * One message's matching text, prepared once and shared by every tab's matchers (R-1): the case-fold keys are computed
 * at most once per message, into a buffer reused on the client thread.
 */
public final class MatchText {
	private static final ThreadLocal<int[]> BUFFER = ThreadLocal.withInitial(() -> new int[256]);

	private final String text;
	private final boolean reuseBuffer;
	private int[] folded;
	private boolean loneSurrogate;

	private MatchText(String text, boolean reuseBuffer) {
		this.text = text;
		this.reuseBuffer = reuseBuffer;
	}

	/** A standalone instance (owns its key array). */
	public static MatchText of(String text) {
		return new MatchText(text, false);
	}

	/** An instance using this thread's shared buffer: valid only until the next {@code reusable} call on the thread. */
	public static MatchText reusable(String text) {
		return new MatchText(text, true);
	}

	public String text() {
		return text;
	}

	/** Whether the text has an unpaired surrogate (valid after {@link #folded()}). */
	boolean hasLoneSurrogate() {
		folded();
		return loneSurrogate;
	}

	int[] folded() {
		if (folded == null) {
			int n = text.length();
			int[] buf;
			if (reuseBuffer) {
				buf = BUFFER.get();
				if (buf.length < n) {
					buf = new int[Math.max(n, buf.length * 2)];
					BUFFER.set(buf);
				}
			} else buf = new int[n];
			loneSurrogate = CaseFold.fold(text, buf);
			folded = buf;
		}
		return folded;
	}
}
