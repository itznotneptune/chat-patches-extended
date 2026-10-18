// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

import org.jetbrains.annotations.Nullable;

/**
 * One include or exclude filter as configured (schema §7.2). {@code mode} is kept verbatim; null means the default
 * {@code "literal"}. Case-insensitive unless {@code caseSensitive} (Q12).
 */
public record FilterSpec(@Nullable String mode, @Nullable String pattern, boolean caseSensitive) {
	public static final String LITERAL = "literal";
	public static final String REGEX = "regex";

	public static FilterSpec literal(String pattern) {
		return new FilterSpec(LITERAL, pattern, false);
	}

	public static FilterSpec literal(String pattern, boolean caseSensitive) {
		return new FilterSpec(LITERAL, pattern, caseSensitive);
	}

	public String effectiveMode() {
		return mode == null ? LITERAL : mode;
	}
}
