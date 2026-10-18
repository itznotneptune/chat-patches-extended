// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.core;

import io.github.itznotneptune.cpextended.CpExtended;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded P9 warnings: per distinct failure key, one log entry (and at most one toast) on first occurrence; repeats
 * are only counted, at debug level. The "next chat-screen opening" reminder P9 allows is not part of Milestone 1a.
 */
public final class Warnings {
	public enum Toast { NONE, DEGRADED, TAB_SUSPENDED, SEARCH_UNAVAILABLE, INTERNAL, CONFIG_NO_SAVE, CONFIG_DIVERGENCE, CONFIG_NOT_SAVED }

	private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
	private static int toastsShown;

	private Warnings() {}

	/** Reports a failure; returns true the first time {@code key} is seen. {@code toastArg} fills a tab name. */
	public static boolean report(String key, Toast toast, String logMessage, @Nullable Throwable error, Object... toastArgs) {
		boolean first;
		synchronized (Warnings.class) {
			int n = COUNTS.merge(key, 1, Integer::sum);
			first = n == 1;
			if (first && toast != Toast.NONE) toastsShown++;
		}
		if (!first) {
			CpExtended.LOGGER.debug("{} (repeat of [{}])", logMessage, key);
			return false;
		}
		if (error != null) CpExtended.LOGGER.warn("{} [{}]", logMessage, key, error);
		else CpExtended.LOGGER.warn("{} [{}]", logMessage, key);
		if (toast != Toast.NONE) showToast(toast, toastArgs);
		return true;
	}

	private static void showToast(Toast toast, Object... args) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return;
		Component title = Component.translatable("cpextended.warning.title");
		Component body = Component.translatable("cpextended.warning." + toast.name().toLowerCase(java.util.Locale.ROOT), args);
		mc.execute(() -> SystemToast.add(mc.getToastManager(), new SystemToast.SystemToastId(), title, body));
	}

	public static synchronized Map<String, Integer> counts() { return Map.copyOf(COUNTS); }
	public static synchronized int toastsShown() { return toastsShown; }

	/** Development builds only (tests that reset adapter state within one launch). */
	public static synchronized void devReset() {
		io.github.itznotneptune.cpextended.dev.DevHooks.requireDev();
		COUNTS.clear();
		toastsShown = 0;
	}
}
