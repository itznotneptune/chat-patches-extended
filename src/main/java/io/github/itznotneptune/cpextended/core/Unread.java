// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import java.util.function.Predicate;

/**
 * The P4 delivery rule's input: whether a tab is currently "read on delivery", i.e. selected in a window that is
 * visible for unread purposes (Q7, Q19, Q26). The UI layer, which knows windows and visibility, installs the policy;
 * without one every delivery is flagged.
 */
public final class Unread {
	private static volatile Predicate<String> readOnDelivery = id -> false;

	private Unread() {}

	public static void setPolicy(Predicate<String> policy) {
		readOnDelivery = policy;
	}

	static boolean readOnDelivery(String tabId) {
		return readOnDelivery.test(tabId);
	}
}
