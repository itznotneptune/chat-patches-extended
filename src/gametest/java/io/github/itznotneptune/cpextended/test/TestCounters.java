// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import java.util.concurrent.atomic.AtomicInteger;

/** Counters fed by test mixins (mixin classes can't hold public static state). */
public final class TestCounters {
	/** V-05: display-queue calls made outside any addMessage frame. */
	public static final AtomicInteger FRAMELESS_DISPLAY = new AtomicInteger();
	/** V-57: calls into vanilla ChatScreen.mouseClicked / mouseScrolled. */
	public static final AtomicInteger VANILLA_CLICKS = new AtomicInteger();
	public static final AtomicInteger VANILLA_SCROLLS = new AtomicInteger();

	/** V-55: per-frame accounting of one window's draws against the expected visibility. */
	public static volatile java.util.function.BooleanSupplier expectedVisible;
	public static volatile String trackedWindow;
	public static final AtomicInteger FRAMES = new AtomicInteger();
	public static final AtomicInteger ANOMALIES = new AtomicInteger();
	public static final java.util.List<String> ANOMALY_SAMPLES = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
	private static long lastDraws;

	public static void onFrameEnd() {
		String w = trackedWindow;
		java.util.function.BooleanSupplier exp = expectedVisible;
		long draws = w == null ? 0 : io.github.itznotneptune.cpextended.render.WindowRenderer.totalDraws(w);
		long delta = draws - lastDraws;
		lastDraws = draws;
		if (w == null || exp == null) return;
		FRAMES.incrementAndGet();
		boolean e = exp.getAsBoolean();
		if (e ? delta != 1 : delta != 0) {
			ANOMALIES.incrementAndGet();
			if (ANOMALY_SAMPLES.size() < 20) ANOMALY_SAMPLES.add("frame " + FRAMES.get() + " expectedVisible=" + e + " draws=" + delta);
		}
	}

	private TestCounters() {}
}
