// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

/**
 * Where a mirrored message came from.
 * <ul>
 * <li>UNKNOWN: captured, but Chat Patches' restored tag couldn't be read (degraded mode).</li>
 * <li>UNOBSERVED: found in the main chat by the reconciler without ever passing through {@code addMessage} capture
 * (inserted by another mod). Its original text, origin and routing are unknown and are never guessed.</li>
 * </ul>
 */
public enum Origin { LIVE, RESTORED, UNKNOWN, UNOBSERVED }
