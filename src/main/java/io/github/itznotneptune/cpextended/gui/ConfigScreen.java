// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Entry screen of the configuration GUI (Q6), opened by the keybind (P15) or Mod Menu. Runs the external-edit check
 * when it opens (P14), shows the save state with the reconciliation actions for it, and lists the windows.
 * Every action goes through {@link ConfigManager}; none can bypass the orphan rules (refused while orphan restoration
 * isn't available) or overwrite a hand-edited file without explicit confirmation.
 */
public final class ConfigScreen extends BaseScreen {
	private boolean checked;
	private @Nullable String checkResult;

	public ConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("cpextended.gui.title"), parent);
	}

	@Override
	protected void init() {
		if (!checked) {
			checked = true;
			ConfigManager.SaveOutcome o = ConfigManager.get().checkForExternalChanges();
			if (o == ConfigManager.SaveOutcome.APPLIED_EXTERNAL_EDIT) setStatus("cpextended.json was edited outside the game; the edit is now in use", false);
			else if (o == ConfigManager.SaveOutcome.BLOCKED_UNREADABLE) setStatus("cpextended.json can't be read right now", true);
			checkResult = o.name();
		}
		ConfigManager m = ConfigManager.get();
		int y = 32;
		switch (m.state()) {
			case DIVERGENCE -> {
				addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.apply_file"), b -> confirm(
						Component.translatable("cpextended.gui.apply_file"), Component.translatable("cpextended.gui.apply_file.confirm"), this::applyFile))
						.bounds(width / 2 - 154, y, 150, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.overwrite_file"), b -> confirm(
						Component.translatable("cpextended.gui.overwrite_file"), Component.translatable("cpextended.gui.overwrite_file.confirm"), this::overwriteFile))
						.bounds(width / 2 + 4, y, 150, 20).build());
			}
			case NO_SAVE -> {
				Button restore = addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.restore_backup"), b -> confirm(
						Component.translatable("cpextended.gui.restore_backup"), Component.translatable("cpextended.gui.restore_backup.confirm"), this::restoreBackup))
						.bounds(width / 2 - 154, y, 150, 20).build());
				restore.active = m.storage().readBackup().bytes() != null;
				addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.replace_file"), b -> confirm(
						Component.translatable("cpextended.gui.replace_file"), Component.translatable("cpextended.gui.replace_file.confirm"), this::replaceFile))
						.bounds(width / 2 + 4, y, 150, 20).build());
			}
			case NORMAL -> {}
		}

		List<ExtendedConfig.Window> windows = m.config().windows();
		int per = rowsPerPage();
		int top = listTop();
		for (int i = page * per; i < Math.min(windows.size(), (page + 1) * per); i++) {
			ExtendedConfig.Window w = windows.get(i);
			int ry = top + (i - page * per) * ROW;
			String id = w.id();
			Button open = addRenderableWidget(Button.builder(Component.literal(label(w)), b -> {
				if (id != null) minecraft.setScreen(new WindowScreen(this, id));
			}).bounds(width / 2 - 154, ry, 230, 20).build());
			open.active = id != null && !w.suspended();
			Button toggle = addRenderableWidget(Button.builder(Component.translatable(w.enabled() ? "cpextended.gui.on" : "cpextended.gui.off"), b -> {
				if (id != null) apply(new ConfigEdit.SetWindowEnabled(id, !w.enabled()));
			}).bounds(width / 2 + 80, ry, 74, 20).build());
			toggle.active = open.active;
		}
		pager(windows.size());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.add_window"), b -> addWindow())
				.bounds(width / 2 - 154, height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 4, height - 28, 150, 20).build());
	}

	static String label(ExtendedConfig.Window w) {
		if (w.id() == null) return "(unreadable window entry, kept as stored)";
		return w.id() + " - " + w.tabs().size() + " tab(s)" + (w.suspended() ? " - suspended: " + w.errors() : "");
	}

	private void apply(ConfigEdit e) {
		ConfigManager.EditOutcome o = ConfigManager.get().edit(e);
		report(o);
		rebuildWidgets();
	}

	void report(ConfigManager.EditOutcome o) {
		if (!o.applied()) setStatus(String.valueOf(o.edit().reason()), true);
		else if (o.save() == null || o.save() == ConfigManager.SaveOutcome.SAVED || o.save() == ConfigManager.SaveOutcome.NOTHING_TO_SAVE) setStatus("Saved", false);
		else setStatus("Changed in game, not saved: " + o.save(), true);
	}

	private void addWindow() {
		ConfigManager m = ConfigManager.get();
		String id = m.unusedId("window");
		ConfigManager.EditOutcome o = m.edit(new ConfigEdit.AddWindow(id, 0.05, 0.10, 0.30, 0.25));
		report(o);
		if (o.applied()) minecraft.setScreen(new WindowScreen(this, id));
		else rebuildWidgets();
	}

	void applyFile() {
		ConfigManager m = ConfigManager.get();
		ConfigManager.OrphanCheck c = m.checkApplyFile();
		if (!c.orphaning()) result(m.applyFile());
		else if (c.refused()) setStatus(c.refusal(), true);
		else confirmOrphans(c, () -> result(m.applyFileRestoringOrphans(c)));
	}
	void overwriteFile() { result(ConfigManager.get().overwriteFile(true)); }
	void restoreBackup() {
		ConfigManager m = ConfigManager.get();
		ConfigManager.OrphanCheck c = m.checkRestoreFromBackup();
		if (!c.orphaning()) result(m.restoreFromBackup(true));
		else if (c.refused()) setStatus(c.refusal(), true);
		else confirmOrphans(c, () -> result(m.restoreFromBackupRestoringOrphans(c)));
	}
	void replaceFile() { result(ConfigManager.get().replaceWithCurrentSettings(true)); }

	private void result(ConfigManager.ActionResult r) {
		setStatus(r.message(), !r.ok());
		rebuildWidgets();
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		ConfigManager m = ConfigManager.get();
		if (m.state() != ConfigManager.State.NORMAL) {
			// Persistent banner (P14): what's wrong, and which file and backup are involved.
			String detail = m.stateDetail();
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(detail), width - 20);
			for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawCenteredString(font, lines.get(i), width / 2, height - 86 + i * 10, 0xFFFFCC66);
		}
	}

	/** Result of the external-edit check run when the screen opened (for diagnostics and tests). */
	public @Nullable String checkResult() { return checkResult; }
}
