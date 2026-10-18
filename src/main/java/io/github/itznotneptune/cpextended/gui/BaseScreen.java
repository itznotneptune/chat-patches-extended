// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Shared layout for the custom configuration screens (Q6: vanilla {@link Screen} subclasses only, no YACL): a title, a
 * save-state line (P14: normal / no-save / divergence lock), one status line for the last action's result, and paging
 * for lists that don't fit the screen.
 */
abstract class BaseScreen extends Screen {
	static final int ROW = 22;
	protected final @Nullable Screen parent;
	protected Component status = Component.empty();
	protected int page;

	BaseScreen(Component title, @Nullable Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Rows that fit between the header and the footer. */
	protected int rowsPerPage() {
		return Math.max(1, (height - 110) / ROW);
	}

	protected int listTop() {
		return 58;
	}

	protected void pager(int total) {
		int pages = Math.max(1, (total + rowsPerPage() - 1) / rowsPerPage());
		page = Math.min(page, pages - 1);
		if (pages <= 1) return;
		addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); })
				.bounds(width / 2 - 60, height - 52, 20, 20).build()).active = page > 0;
		addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); })
				.bounds(width / 2 + 40, height - 52, 20, 20).build()).active = page < pages - 1;
	}

	protected void confirm(Component title, Component message, Runnable onYes) {
		minecraft.setScreen(new ConfirmScreen(yes -> {
			minecraft.setScreen(this); // first, so onYes may open a further confirmation
			if (yes) onYes.run();
		}, title, message));
	}

	/**
	 * Applies {@code edits}; if they would orphan hidden messages (Q2), first shows what the restoration does (Q22
	 * disclosure) and applies only on Yes, as one atomic operation (Q36). A refusal is shown as is; "No" changes nothing.
	 * A confirmation never overrides a refusal.
	 */
	protected void runEdits(List<ConfigEdit> edits, Consumer<ConfigManager.EditOutcome> after) {
		ConfigManager m = ConfigManager.get();
		ConfigManager.OrphanCheck c = m.checkEdits(edits);
		if (!c.orphaning()) {
			after.accept(m.editAll(edits));
			return;
		}
		if (c.refused()) {
			setStatus(c.refusal(), true);
			return;
		}
		confirmOrphans(c, () -> after.accept(m.editAllRestoringOrphans(edits, c)));
	}

	/** The Q2/Q22 confirmation: restored and pruned counts; "No" changes nothing. */
	protected void confirmOrphans(ConfigManager.OrphanCheck c, Runnable onYes) {
		minecraft.setScreen(new ConfirmScreen(yes -> {
			minecraft.setScreen(this);
			if (yes) onYes.run();
			else setStatus("Nothing was changed", false);
		}, Component.translatable("cpextended.gui.orphans.title"),
				Component.translatable(c.pruned() > 0 ? "cpextended.gui.orphans.message_pruned" : "cpextended.gui.orphans.message",
						c.restored(), c.tabNames(), c.pruned())));
	}

	protected void setStatus(String text, boolean error) {
		status = Component.literal(text).withColor(error ? 0xFF6060 : 0x80FF80);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
		ConfigManager m = ConfigManager.get();
		Component state = switch (m.state()) {
			case NORMAL -> Component.translatable("cpextended.gui.state.normal").withColor(0x80FF80);
			case NO_SAVE -> Component.translatable("cpextended.gui.state.no_save").withColor(0xFFAA00);
			case DIVERGENCE -> Component.translatable("cpextended.gui.state.divergence").withColor(0xFF6060);
		};
		g.drawCenteredString(font, state, width / 2, 20, 0xFFFFFFFF);
		g.drawCenteredString(font, status, width / 2, height - 64, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	public Component status() { return status; }
}
