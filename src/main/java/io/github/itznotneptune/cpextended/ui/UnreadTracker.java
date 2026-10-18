// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.ui;

import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.core.Unread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.jetbrains.annotations.Nullable;

/**
 * P4 unread rules that depend on windows (Q7, Q19, Q26). A window is hidden for unread purposes when it's disabled (or
 * unreadable), Chat Visibility is Hidden, or F1 is on with the chat screen closed. A delivery is read iff its tab is the
 * selected tab of a visible window; otherwise the entry is flagged. Each tick, the selected tab of every visible window
 * is cleared, which covers both steady state and a window becoming visible (P4.6). Search never touches flags (it
 * doesn't exist yet). Tabs that belong to no window are always "hidden".
 */
public final class UnreadTracker {
	private UnreadTracker() {}

	public static void register() {
		Unread.setPolicy(UnreadTracker::readOnDelivery);
	}

	public static boolean visibleForUnread(ExtendedConfig cfg, ExtendedConfig.Window w) {
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.enabled() || w.suspended() || !w.enabled()) return false;
		if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return false;
		return !(mc.options.hideGui && !(mc.screen instanceof ChatScreen));
	}

	static boolean readOnDelivery(String tabId) {
		ExtendedConfig cfg = ConfigManager.get().config();
		ExtendedConfig.Window w = activeWindowOf(cfg, tabId);
		if (w == null) return false;
		ExtendedConfig.Tab shown = w.shownTab();
		return shown != null && tabId.equals(shown.id()) && visibleForUnread(cfg, w);
	}

	/** Client tick: a visible window's selected tab is read (Q7). */
	public static void tick() {
		ExtendedConfig cfg = ConfigManager.get().config();
		for (ExtendedConfig.Window w : cfg.windows()) {
			if (!visibleForUnread(cfg, w)) continue;
			ExtendedConfig.Tab shown = w.shownTab();
			if (shown == null || shown.id() == null) continue;
			TabState st = SessionMirror.get().tab(shown.id());
			if (st != null && st.unreadCount() > 0) st.markAllRead();
		}
	}

	private static @Nullable ExtendedConfig.Window activeWindowOf(ExtendedConfig cfg, String tabId) {
		for (ExtendedConfig.Window w : cfg.windows()) {
			if (w.suspended()) continue;
			for (ExtendedConfig.Tab t : w.tabs()) if (tabId.equals(t.id())) return w;
		}
		return null;
	}
}
