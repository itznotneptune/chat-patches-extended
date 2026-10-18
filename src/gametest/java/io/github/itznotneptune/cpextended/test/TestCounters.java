// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import java.util.concurrent.atomic.AtomicInteger;

/** Counters fed by test mixins (mixin classes can't hold public static state). */
public final class TestCounters {
	/** V-05: display-queue calls made outside any addMessage frame. */
	public static final AtomicInteger FRAMELESS_DISPLAY = new AtomicInteger();

	private TestCounters() {}
}
