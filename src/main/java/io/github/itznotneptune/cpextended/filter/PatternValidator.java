// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

import org.jetbrains.annotations.Nullable;

/**
 * Filter validation (Q14, Q14.1, Q15, P8). Invalid filters are never executed; their tab is suspended. Regex filters are
 * valid only when they compile within {@link RegexSupport}'s bounds (Q15 resolved 2026-11-22).
 */
public final class PatternValidator {
	/** Maximum pattern length in UTF-16 code units, counted exactly as stored (P8). */
	public static final int MAX_LENGTH = 1024;

	private PatternValidator() {}

	/** Returns an error code, or null if the filter is valid and executable. */
	public static @Nullable String validate(FilterSpec f) {
		String mode = f.effectiveMode();
		boolean regex = FilterSpec.REGEX.equals(mode);
		if (!regex && !FilterSpec.LITERAL.equals(mode)) return "unknown_mode";
		String p = f.pattern();
		if (p == null || p.isEmpty()) return "empty";
		if (p.length() > MAX_LENGTH) return "too_long";
		if (regex) return RegexSupport.compile(p, f.caseSensitive()).error();
		return null;
	}

	/** For a Regex filter with a syntax error: RE2/J's description of it (shown in the settings screen); else null. */
	public static @Nullable String regexDetail(FilterSpec f) {
		if (!FilterSpec.REGEX.equals(f.effectiveMode()) || f.pattern() == null || f.pattern().isEmpty() || f.pattern().length() > MAX_LENGTH) return null;
		return RegexSupport.compile(f.pattern(), f.caseSensitive()).detail();
	}
}
