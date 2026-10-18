// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.model.RoutingConfig;
import io.github.itznotneptune.cpextended.ui.TabMoves;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One window: enabled state, the selected tab, its tabs (open, move to another window, delete) and deleting the window.
 * Moves made here are config-screen moves: the destination's visibility is judged as if chat were closed (P4.8).
 * Deletions that would orphan hidden messages are refused by the configuration manager.
 */
public final class WindowScreen extends BaseScreen {
	private final String windowId;
	/** Chosen destination per tab for the Move action. */
	private final Map<String, String> moveTargets = new HashMap<>();

	public WindowScreen(Screen parent, String windowId) {
		super(Component.translatable("cpextended.gui.window", windowId), parent);
		this.windowId = windowId;
	}

	ExtendedConfig.Window window() {
		return ConfigManager.get().config().window(windowId);
	}

	@Override
	protected void init() {
		ExtendedConfig.Window w = window();
		if (w == null) {
			addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
			setStatus("This window no longer exists", true);
			return;
		}
		int y = 32;
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.enabled", Component.translatable(w.enabled() ? "cpextended.gui.on" : "cpextended.gui.off")),
				b -> apply(new ConfigEdit.SetWindowEnabled(windowId, !w.enabled()))).bounds(width / 2 - 154, y, 150, 20).build());
		ExtendedConfig.Tab shown = w.shownTab();
		Button sel = addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.selected_tab", shown == null ? "-" : name(shown)), b -> cycleSelected())
				.bounds(width / 2 + 4, y, 150, 20).build());
		sel.active = w.tabs().size() > 1;

		List<String> others = new ArrayList<>();
		for (ExtendedConfig.Window o : ConfigManager.get().config().windows()) if (o.id() != null && !o.suspended() && !o.id().equals(windowId)) others.add(o.id());
		List<ExtendedConfig.Tab> tabs = w.tabs();
		int per = rowsPerPage();
		for (int i = page * per; i < Math.min(tabs.size(), (page + 1) * per); i++) {
			ExtendedConfig.Tab t = tabs.get(i);
			int ry = listTop() + (i - page * per) * ROW;
			String tabId = t.id();
			Button open = addRenderableWidget(Button.builder(Component.literal(label(t)), b -> minecraft.setScreen(new TabScreen(this, tabId)))
					.bounds(width / 2 - 154, ry, 150, 20).build());
			open.active = tabId != null && t.config() != null;
			String target = moveTargets.getOrDefault(String.valueOf(tabId), others.isEmpty() ? null : others.get(0));
			Button cycle = addRenderableWidget(Button.builder(Component.literal(target == null ? "-" : "-> " + target), b -> {
				int k = others.indexOf(moveTargets.getOrDefault(String.valueOf(tabId), others.get(0)));
				moveTargets.put(String.valueOf(tabId), others.get((k + 1) % others.size()));
				rebuildWidgets();
			}).bounds(width / 2 - 2, ry, 70, 20).build());
			cycle.active = tabId != null && others.size() > 1;
			Button move = addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.move"), b -> {
				String dest = moveTargets.getOrDefault(String.valueOf(tabId), others.isEmpty() ? null : others.get(0));
				if (dest != null) report(TabMoves.moveToWindow(tabId, dest));
				rebuildWidgets();
			}).bounds(width / 2 + 70, ry, 40, 20).build());
			move.active = tabId != null && !others.isEmpty();
			Button del = addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.delete"), b -> confirm(
					Component.translatable("cpextended.gui.delete_tab"), Component.translatable("cpextended.gui.delete_tab.confirm", label(t)),
					() -> report(ConfigManager.get().edit(new ConfigEdit.DeleteTab(tabId))))).bounds(width / 2 + 112, ry, 42, 20).build());
			del.active = tabId != null;
		}
		pager(tabs.size());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.add_tab"), b -> addTab()).bounds(width / 2 - 154, height - 28, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.delete_window"), b -> confirm(
				Component.translatable("cpextended.gui.delete_window"), Component.translatable("cpextended.gui.delete_window.confirm", windowId), this::deleteWindow))
				.bounds(width / 2 - 50, height - 28, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 + 54, height - 28, 100, 20).build());
	}

	static String name(ExtendedConfig.Tab t) {
		return t.config() != null ? t.config().name() : String.valueOf(t.id());
	}

	/** Name, id, unread count and error marker; error codes only, never message text. */
	static String label(ExtendedConfig.Tab t) {
		if (t.id() == null) return "(unreadable tab entry)";
		TabState st = SessionMirror.get().tab(t.id());
		StringBuilder sb = new StringBuilder(name(t));
		if (st != null && st.unreadCount() > 0) sb.append(" (").append(st.unreadCount()).append(")");
		if (isSuspended(t, st)) sb.append(" !");
		return sb.toString();
	}

	static boolean isSuspended(ExtendedConfig.Tab t, TabState st) {
		if (t.config() == null) return true;
		for (RoutingConfig.CompiledTab ct : Router.get().config().tabs()) if (ct.id().equals(t.id()) && ct.suspended()) return true;
		return st != null && st.isRuntimeSuspended();
	}

	private void cycleSelected() {
		ExtendedConfig.Window w = window();
		List<ExtendedConfig.Tab> tabs = w.tabs().stream().filter(t -> t.id() != null).toList();
		ExtendedConfig.Tab shown = w.shownTab();
		int k = Math.max(0, tabs.indexOf(shown));
		apply(new ConfigEdit.SelectTab(windowId, tabs.get((k + 1) % tabs.size()).id()));
	}

	private void addTab() {
		ConfigManager m = ConfigManager.get();
		String id = m.unusedId("tab");
		ConfigManager.EditOutcome o = m.edit(new ConfigEdit.AddTab(windowId, id, "New tab"));
		report(o);
		if (o.applied()) minecraft.setScreen(new TabScreen(this, id));
		else rebuildWidgets();
	}

	private void deleteWindow() {
		ConfigManager.EditOutcome o = ConfigManager.get().edit(new ConfigEdit.DeleteWindow(windowId));
		report(o);
		if (o.applied()) {
			if (parent instanceof BaseScreen b) b.setStatus("Window " + windowId + " deleted", false);
			minecraft.setScreen(parent);
		}
	}

	private void apply(ConfigEdit e) {
		report(ConfigManager.get().edit(e));
		rebuildWidgets();
	}

	void report(ConfigManager.EditOutcome o) {
		if (!o.applied()) setStatus(String.valueOf(o.edit().reason()), true);
		else if (o.save() == null || o.save() == ConfigManager.SaveOutcome.SAVED || o.save() == ConfigManager.SaveOutcome.NOTHING_TO_SAVE) setStatus("Saved", false);
		else setStatus("Changed in game, not saved: " + o.save(), true);
	}

	public String windowId() { return windowId; }

	/** Test and automation hook: the Move action for one tab to a chosen window. */
	public ConfigManager.EditOutcome moveTab(String tabId, String destination) {
		ConfigManager.EditOutcome o = TabMoves.moveToWindow(tabId, destination);
		report(o);
		rebuildWidgets();
		return o;
	}
}
