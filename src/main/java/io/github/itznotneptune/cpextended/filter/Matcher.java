// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.filter;

/** A compiled, valid filter. Implementations may throw; the router's P9 guard contains it. */
@FunctionalInterface
public interface Matcher {
	boolean matches(String text);
}
