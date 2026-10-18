// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

/**
 * TEST ONLY: the Milestone 1a Literal matcher, verbatim from commit bd6ccbb apart from its name and package. It is the
 * oracle for the R-1 differential tests.
 *
 * <p>Literal mode (D6.1, Q12, Q15): the pattern matches if it occurs anywhere in the text as an exact sequence of UTF-16
 * units; no character is special. Case-insensitive comparison uses {@link String#regionMatches(boolean, int, String,
 * int, int)}, which is locale-independent. The text is never normalized.
 */
public final class ReferenceLiteralMatcher {
	private final String pattern;
	private final boolean caseSensitive;

	public ReferenceLiteralMatcher(String pattern, boolean caseSensitive) {
		this.pattern = pattern;
		this.caseSensitive = caseSensitive;
	}

	public boolean matches(String text) {
		if (caseSensitive) return text.contains(pattern);
		int len = pattern.length();
		int last = text.length() - len;
		for (int i = 0; i <= last; i++) {
			if (text.regionMatches(true, i, pattern, 0, len)) return true;
		}
		return false;
	}
}
