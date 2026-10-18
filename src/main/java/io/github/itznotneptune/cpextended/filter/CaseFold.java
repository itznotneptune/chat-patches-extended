// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

/**
 * Per-UTF-16-unit keys used to find match candidates quickly for case-insensitive Literal matching (R-1).
 *
 * <p>{@link String#regionMatches(boolean, int, String, int, int)} with {@code ignoreCase} (JDK 21) treats two units as
 * equal when they are identical, or their {@code toUpperCase} or {@code toLowerCase(toUpperCase)} forms are; surrogate
 * pairs are compared as whole code points with the same rule, and a surrogate never equals a non-surrogate. So when it
 * reports a match, every position agrees on these keys:
 * <ul>
 * <li>a non-surrogate: {@code toLowerCase(toUpperCase(c))} (never negative);</li>
 * <li>a high surrogate: {@link #HIGH};</li>
 * <li>a low surrogate preceded by a high surrogate: {@code -3 - toLowerCase(toUpperCase(codePoint))};</li>
 * <li>any other low surrogate: {@link #LONE_LOW}.</li>
 * </ul>
 * The keys are a necessary condition only. Every candidate they produce is confirmed with {@code regionMatches}
 * itself, so matching results are exactly those of the original matcher.
 *
 * <p>The agreement argument needs both sides to step through the region together. {@code regionMatches} can fall out of
 * step when a lone surrogate meets a surrogate pair (one side advances two units, the other one; the comparison can
 * then stop before the end of the pattern and still report a match). That needs a lone surrogate in the pattern, or a
 * lone surrogate in the text while the pattern contains surrogates. Those cases use the original scan; text received
 * from a server can't contain lone surrogates (UTF-8 on the wire).
 */
final class CaseFold {
	static final int HIGH = -1;
	static final int LONE_LOW = -2;

	private CaseFold() {}

	/** Writes the key of each unit of {@code s} into {@code out[0..s.length())}; returns whether {@code s} has a lone surrogate. */
	static boolean fold(String s, int[] out) {
		int n = s.length();
		char prev = 0;
		boolean lone = false;
		for (int j = 0; j < n; j++) {
			char c = s.charAt(j);
			int k;
			if (Character.isHighSurrogate(c)) {
				k = HIGH;
				if (j + 1 >= n || !Character.isLowSurrogate(s.charAt(j + 1))) lone = true;
			} else if (Character.isLowSurrogate(c)) {
				if (j > 0 && Character.isHighSurrogate(prev)) {
					k = -3 - Character.toLowerCase(Character.toUpperCase(Character.toCodePoint(prev, c)));
				} else {
					k = LONE_LOW;
					lone = true;
				}
			} else k = Character.toLowerCase(Character.toUpperCase((int) c));
			out[j] = k;
			prev = c;
		}
		return lone;
	}
}
