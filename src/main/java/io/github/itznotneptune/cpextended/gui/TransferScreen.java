// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.LayoutFiles;
import io.github.itznotneptune.cpextended.config.LayoutTransfer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Import / Export, from the main settings screen: export everything, or import from the clipboard or a saved file.
 * Importing only adds windows and tabs (fresh ids); nothing existing is changed, so hidden messages are never affected.
 * The player confirms a summary first.
 */
public final class TransferScreen extends BaseScreen {
	private LayoutTransfer.@Nullable Plan lastPlan;

	public TransferScreen(@Nullable Screen parent) {
		super(Component.translatable("cpextended.gui.transfer.title"), parent);
	}

	/** Below the two action rows. */
	@Override
	protected int listTop() {
		return 34 + 2 * ROW + 4;
	}

	@Override
	protected int rowsPerPage() {
		return Math.max(1, (height - listTop() - 70) / ROW);
	}

	@Override
	protected void init() {
		int y = 34;
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.transfer.export_all"), b -> show(new ExportScreen(this,
						Component.translatable("cpextended.gui.transfer.everything"), "layout", "all",
						() -> LayoutTransfer.exportLayout(ConfigManager.get().config()))))
				.bounds(width / 2 - 154, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.transfer.paste"), b -> importText(minecraft.keyboardHandler.getClipboard(), "the clipboard"))
				.bounds(width / 2 + 4, y, 150, 20).build())
				.setTooltip(Tooltip.create(Component.translatable("cpextended.gui.transfer.import.tooltip")));
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.transfer.open_folder"), b -> ExportScreen.openFolder(this))
				.bounds(width / 2 - 75, y + ROW, 150, 20).build());

		List<Path> files = LayoutFiles.list();
		int per = rowsPerPage();
		page = Math.min(page, Math.max(0, (files.size() - 1) / per));
		for (int i = page * per; i < Math.min(files.size(), (page + 1) * per); i++) {
			Path p = files.get(i);
			String name = p.getFileName().toString();
			int ry = listTop() + (i - page * per) * ROW;
			addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name, 296)), b -> importText(LayoutFiles.read(p), name))
					.bounds(width / 2 - 154, ry, 308, 20).build())
					.setTooltip(Tooltip.create(Component.translatable("cpextended.gui.transfer.file.tooltip", name)));
		}
		if (files.isEmpty() && status.getString().isEmpty()) setStatus("No saved exports yet in config/" + LayoutFiles.FOLDER, false);
		pager(files.size());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 - 75, height - 28, 150, 20).build());
	}

	/**
	 * Validates an export and, if it's valid, asks for confirmation before adding it. Returns the parse result
	 * (also for automation); nothing changes until the player confirms.
	 */
	public LayoutTransfer.Parsed importText(@Nullable String text, String from) {
		LayoutTransfer.Parsed parsed = LayoutTransfer.parse(text, ConfigManager.get().config());
		lastPlan = parsed.plan();
		if (parsed.error() != null) {
			setStatus("Can't import from " + from + ": " + parsed.error(), true);
			return parsed;
		}
		LayoutTransfer.Plan plan = parsed.plan();
		confirm(Component.translatable("cpextended.gui.transfer.confirm.title"), Component.literal(confirmation(plan, from)), () -> apply(plan));
		return parsed;
	}

	static String confirmation(LayoutTransfer.Plan plan, String from) {
		StringBuilder sb = new StringBuilder("Add ").append(what(plan)).append(" from ").append(from).append("? ");
		sb.append("They're added as new; nothing you have now is changed.");
		if (plan.hidingTabs() > 0) {
			sb.append(' ').append(plan.hidingTabs()).append(plan.hidingTabs() == 1 ? " imported tab hides" : " imported tabs hide")
					.append(" matching messages from the main chat.");
		}
		return sb.toString();
	}

	static String what(LayoutTransfer.Plan plan) {
		return plan.windowCount() + (plan.windowCount() == 1 ? " window" : " windows") + " with " + plan.tabCount() + (plan.tabCount() == 1 ? " tab" : " tabs");
	}

	/** Adds the plan's windows and tabs (one atomic change). */
	public void apply(LayoutTransfer.Plan plan) {
		runEdits(List.of(plan.edit()), o -> {
			if (!o.applied()) setStatus("Not imported: " + o.edit().reason(), true);
			else if (o.save() != ConfigManager.SaveOutcome.SAVED) setStatus("Imported " + what(plan) + "; changed in game, not saved: " + o.save(), true);
			else setStatus("Imported " + what(plan), false);
			rebuildWidgets();
		});
	}

	public LayoutTransfer.@Nullable Plan lastPlan() { return lastPlan; }
	public Component status() { return status; }
}
