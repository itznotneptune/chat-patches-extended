// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.ui;

import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;

/**
 * Tab relocation (Q21, Q25, Q30, Q33) through the configuration edit API. A move keeps the tab's raw subtree (identity,
 * filters, configuration, unknown keys); its session history and unread flags live under the tab id and so move with
 * it. Never orphan restoration: the tab isn't deleted. Unread (Q25, P4.8):
 * <ul>
 * <li>into a populated window: the tab stays unselected and keeps its flags;</li>
 * <li>into an empty, visible window: the tab is selected and its flags clear;</li>
 * <li>into a hidden destination: flags kept (they clear later by the becoming-visible rule).</li>
 * </ul>
 * Visibility is evaluated as the screen the move happens on: a drag (chat open) uses the chat-open rule; the config
 * screen uses the chat-closed rule, so F1 makes the destination hidden (P4.8, P4.9).
 */
public final class TabMoves {
	private TabMoves() {}

	public static ConfigManager.EditOutcome moveToWindow(String tabId, String windowId) {
		ConfigManager m = ConfigManager.get();
		ExtendedConfig.Window dest = m.config().window(windowId);
		boolean wasEmpty = dest != null && dest.tabs().isEmpty();
		ConfigManager.EditOutcome o = m.edit(new ConfigEdit.MoveTab(tabId, windowId));
		if (o.applied() && wasEmpty) settleIntoEmpty(windowId, tabId);
		return o;
	}

	/** Q33 rule 5 / Q30c: a new enabled window holding the tab (geometry in TOP_LEFT fractions). */
	public static ConfigManager.EditOutcome moveToNewWindow(String tabId, double x, double y, double w, double h) {
		ConfigManager m = ConfigManager.get();
		String id = m.unusedId("window");
		ConfigManager.EditOutcome o = m.edit(new ConfigEdit.MoveTabToNewWindow(tabId, id, x, y, w, h));
		if (o.applied()) settleIntoEmpty(id, tabId);
		return o;
	}

	private static void settleIntoEmpty(String windowId, String tabId) {
		ConfigManager m = ConfigManager.get();
		ExtendedConfig cfg = m.config();
		ExtendedConfig.Window w = cfg.window(windowId);
		if (w == null) return;
		if (!tabId.equals(w.selectedTab())) m.edit(new ConfigEdit.SelectTab(windowId, tabId));
		w = m.config().window(windowId);
		boolean chatOpen = net.minecraft.client.Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.ChatScreen;
		boolean visible = chatOpen ? ChatOpenRenderer.drawnWithChatOpen(m.config(), w) : UnreadTracker.visibleForUnread(m.config(), w);
		if (visible) {
			TabState st = SessionMirror.get().tab(tabId);
			if (st != null) st.markAllRead();
		}
	}
}
