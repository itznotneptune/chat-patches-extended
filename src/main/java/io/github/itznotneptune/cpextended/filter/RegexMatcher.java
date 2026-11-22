// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

import com.google.re2j.Pattern;

/**
 * A Regex filter: matches when the pattern is found anywhere in the message's original text (like Literal, no implicit
 * anchors; use {@code ^} and {@code $} to anchor). Case-insensitive unless the filter is case-sensitive (Q12). Only
 * built from a {@link RegexSupport#compile} result, so the program is bounded.
 */
public final class RegexMatcher implements Matcher {
	private final Pattern pattern;

	RegexMatcher(Pattern pattern) {
		this.pattern = pattern;
	}

	/** Compiles a validated Regex filter; throws if it doesn't compile (callers validate first). */
	public static RegexMatcher of(String regex, boolean caseSensitive) {
		RegexSupport.Result r = RegexSupport.compile(regex, caseSensitive);
		if (r.pattern() == null) throw new IllegalArgumentException("invalid regex filter: " + r.error());
		return new RegexMatcher(r.pattern());
	}

	@Override
	public boolean matches(String text) {
		return pattern.matcher(text).find();
	}
}
