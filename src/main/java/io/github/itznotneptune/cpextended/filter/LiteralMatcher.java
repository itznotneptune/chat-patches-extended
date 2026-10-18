// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

/**
 * Literal mode (D6.1, Q12, Q15): the pattern matches if it occurs anywhere in the text as an exact sequence of UTF-16
 * units; no character is special. Case-insensitive equality is that of {@link String#regionMatches(boolean, int,
 * String, int, int)}, which is locale-independent. The text is never normalized.
 *
 * <p>R-1: candidates are found with a Knuth-Morris-Pratt scan (linear in the text length) over raw units, or over
 * {@link CaseFold} keys when case-insensitive, and each candidate is confirmed with {@code startsWith} /
 * {@code regionMatches}. Results are identical to the plain scan that tests every position.
 */
public final class LiteralMatcher implements Matcher {
	private final String pattern;
	private final boolean caseSensitive;
	private final int[] key;
	private final int[] failure;
	private final boolean plainScan;
	private final boolean patternHasSurrogate;

	public LiteralMatcher(String pattern, boolean caseSensitive) {
		this.pattern = pattern;
		this.caseSensitive = caseSensitive;
		int m = pattern.length();
		this.key = new int[m];
		boolean surrogate = false;
		for (int i = 0; i < m; i++) surrogate |= Character.isSurrogate(pattern.charAt(i));
		this.patternHasSurrogate = surrogate;
		boolean patternLone = false;
		if (caseSensitive) {
			for (int i = 0; i < m; i++) key[i] = pattern.charAt(i);
		} else {
			patternLone = CaseFold.fold(pattern, key);
		}
		// Case-insensitive with a lone surrogate in the pattern: the key argument doesn't hold (see CaseFold).
		this.plainScan = m == 0 || patternLone;
		this.failure = failureFunction(key);
	}

	@Override
	public boolean matches(String text) {
		return matches(MatchText.of(text));
	}

	@Override
	public boolean matches(MatchText t) {
		String text = t.text();
		int n = text.length(), m = pattern.length();
		if (plainScan) return plainScan(text);
		if (m > n) return false;
		if (!caseSensitive && patternHasSurrogate && t.hasLoneSurrogate()) return plainScan(text);
		int[] tk = caseSensitive ? null : t.folded();
		int q = 0;
		for (int i = 0; i < n; i++) {
			int c = caseSensitive ? text.charAt(i) : tk[i];
			while (q > 0 && key[q] != c) q = failure[q - 1];
			if (key[q] == c) q++;
			if (q == m) {
				int start = i - m + 1;
				boolean confirmed = caseSensitive ? text.startsWith(pattern, start) : text.regionMatches(true, start, pattern, 0, m);
				if (confirmed) return true;
				q = failure[q - 1];
			}
		}
		return false;
	}

	/** The original scan (every start position), for the lone-surrogate cases the key argument doesn't cover. */
	private boolean plainScan(String text) {
		if (caseSensitive) return text.contains(pattern);
		int len = pattern.length();
		int last = text.length() - len;
		for (int i = 0; i <= last; i++) {
			if (text.regionMatches(true, i, pattern, 0, len)) return true;
		}
		return false;
	}

	private static int[] failureFunction(int[] p) {
		int[] f = new int[p.length];
		int k = 0;
		for (int i = 1; i < p.length; i++) {
			while (k > 0 && p[k] != p[i]) k = f[k - 1];
			if (p[k] == p[i]) k++;
			f[i] = k;
		}
		return f;
	}
}
