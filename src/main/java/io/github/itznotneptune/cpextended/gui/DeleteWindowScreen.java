// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.config.Labels;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deleting a nonempty window (P5): for each tab the player chooses to move it to another window (Q21/Q25, never
 * orphans) or delete it. Moves and the window's deletion are one change; tabs deleted while holding hidden messages go
 * through the orphan-restoration confirmation (Q2/Q22/Q27) as part of that same atomic operation.
 */
public final class DeleteWindowScreen extends BaseScreen {
	private final WindowScreen owner;
	private final String windowId;
	/** Tab id -> destination window id, or null for "delete". */
	private final Map<String, String> choice = new HashMap<>();

	public DeleteWindowScreen(WindowScreen owner, String windowId) {
		super(Component.translatable("cpextended.gui.delete_window.choose", Labels.window(windowId)), owner);
		this.owner = owner;
		this.windowId = windowId;
	}

	private List<String> destinations() {
		List<String> out = new ArrayList<>();
		for (ExtendedConfig.Window w : ConfigManager.get().config().windows()) if (w.id() != null && !w.suspended() && !w.id().equals(windowId)) out.add(w.id());
		return out;
	}

	private List<ExtendedConfig.Tab> tabs() {
		ExtendedConfig.Window w = ConfigManager.get().config().window(windowId);
		return w == null ? List.of() : w.tabs().stream().filter(t -> t.id() != null).toList();
	}

	@Override
	protected void init() {
		List<String> dest = destinations();
		List<ExtendedConfig.Tab> tabs = tabs();
		int per = rowsPerPage();
		for (int i = page * per; i < Math.min(tabs.size(), (page + 1) * per); i++) {
			ExtendedConfig.Tab t = tabs.get(i);
			int ry = listTop() + (i - page * per) * ROW;
			String id = t.id();
			addRenderableWidget(Button.builder(Component.literal(WindowScreen.label(t)), b -> {}).bounds(width / 2 - 154, ry, 150, 20).build()).active = false;
			String current = choice.get(id);
			Component label = current == null ? Component.translatable("cpextended.gui.delete") : Component.literal("-> " + Labels.window(current));
			addRenderableWidget(Button.builder(label, b -> {
				// cycle: delete -> each destination -> delete
				String c = choice.get(id);
				int k = c == null ? -1 : dest.indexOf(c);
				choice.put(id, k + 1 < dest.size() ? dest.get(k + 1) : null);
				rebuildWidgets();
			}).bounds(width / 2 - 2, ry, 156, 20).build());
		}
		pager(tabs.size());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.delete_window"), b -> deleteNow()).bounds(width / 2 - 154, height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 4, height - 28, 150, 20).build());
	}

	/** Builds the single change: moves first, then the window's deletion (its remaining tabs with it). */
	List<ConfigEdit> edits() {
		List<ConfigEdit> edits = new ArrayList<>();
		for (ExtendedConfig.Tab t : tabs()) {
			String d = choice.get(t.id());
			if (d != null) edits.add(new ConfigEdit.MoveTab(t.id(), d));
		}
		edits.add(new ConfigEdit.DeleteWindow(windowId));
		return edits;
	}

	private void deleteNow() {
		runEdits(edits(), owner::afterWindowDeletion);
	}

	/** Test and automation hook: choose a destination (or null to delete) for one tab. */
	public void choose(String tabId, String destination) {
		choice.put(tabId, destination);
		rebuildWidgets();
	}
}
