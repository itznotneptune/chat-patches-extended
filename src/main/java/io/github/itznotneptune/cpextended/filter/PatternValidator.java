// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

import org.jetbrains.annotations.Nullable;

/** Filter validation (Q14, Q14.1, Q15, P8). Invalid filters are never executed; their tab is suspended. */
public final class PatternValidator {
	/** Maximum pattern length in UTF-16 code units, counted exactly as stored (P8). */
	public static final int MAX_LENGTH = 1024;

	private PatternValidator() {}

	/** Returns an error code, or null if the filter is valid and executable as Literal. */
	public static @Nullable String validate(FilterSpec f) {
		String mode = f.effectiveMode();
		if (FilterSpec.REGEX.equals(mode)) return "regex_unsupported";
		if (!FilterSpec.LITERAL.equals(mode)) return "unknown_mode";
		String p = f.pattern();
		if (p == null || p.isEmpty()) return "empty";
		if (p.length() > MAX_LENGTH) return "too_long";
		return null;
	}
}
