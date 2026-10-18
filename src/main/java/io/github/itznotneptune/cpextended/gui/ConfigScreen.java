// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ConfigStorage;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.config.Labels;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
				// The backup is validated before the button is offered; why it can't be used is shown on the button (P14 amendment).
				ConfigStorage.BackupInfo bak = m.storage().inspectBackup();
				Button restore = addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.restore_backup"), b -> confirm(
						Component.translatable("cpextended.gui.restore_backup"), restoreConfirmation(m, bak), this::restoreBackup))
						.bounds(width / 2 - 154, y, 150, 20).build());
				restore.active = bak.state() == ConfigStorage.BackupState.VALID;
				restore.setTooltip(Tooltip.create(restore.active
						? Component.translatable("cpextended.gui.restore_backup.tooltip", m.storage().backup().getFileName().toString(), when(bak))
						: Component.literal(m.backupUnusable(bak))));
				addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.replace_file"), b -> confirm(
						Component.translatable("cpextended.gui.replace_file"), Component.translatable("cpextended.gui.replace_file.confirm"), this::replaceFile))
						.bounds(width / 2 + 4, y, 150, 20).build());
			}
			case NORMAL -> {}
		}

		List<ExtendedConfig.Window> windows = m.config().windows();
		if (bannerTruncated()) {
			// M3: the full explanation when the banner can't show all of it
			addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.details"), b -> minecraft.setScreen(new AlertScreen(
					() -> minecraft.setScreen(this), Component.translatable(m.state() == ConfigManager.State.NO_SAVE ? "cpextended.gui.state.no_save" : "cpextended.gui.state.divergence"),
					Component.literal(m.stateDetail())))).bounds(width / 2 - 50, 56 + BANNER_LINES * 10 + 2, 100, 20).build());
		}
		int per = rowsPerPage();
		int top = listTop();
		for (int i = page * per; i < Math.min(windows.size(), (page + 1) * per); i++) {
			ExtendedConfig.Window w = windows.get(i);
			int ry = top + (i - page * per) * ROW;
			String id = w.id();
			Button open = addRenderableWidget(Button.builder(Component.literal(label(w)), b -> {
				if (id != null) minecraft.setScreen(new WindowScreen(this, id));
			}).bounds(width / 2 - 154, ry, 230, 20).build());
			open.setTooltip(Tooltip.create(Component.literal(label(w) + (id != null ? "\nInternal id: " + id : ""))));
			open.active = id != null && !w.suspended();
			Button toggle = addRenderableWidget(Button.builder(Component.translatable(w.enabled() ? "cpextended.gui.on" : "cpextended.gui.off"), b -> {
				if (id != null) apply(new ConfigEdit.SetWindowEnabled(id, !w.enabled()));
			}).bounds(width / 2 + 80, ry, 74, 20).build());
			toggle.active = open.active;
		}
		pager(windows.size());
		// The actual hiding state: the global switch, and whether hiding is paused by a compatibility problem (degraded, P9)
		boolean on = m.config().hidingEnabled();
		boolean paused = on && io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter.get().isDegraded();
		Button hiding = addRenderableWidget(Button.builder(Component.translatable(!on ? "cpextended.gui.hiding.off" : paused ? "cpextended.gui.hiding.paused" : "cpextended.gui.hiding.on"),
				b -> apply(new ConfigEdit.SetHidingEnabled(!on))).bounds(width / 2 + 64, height - 52, 90, 20).build());
		hiding.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(paused ? "cpextended.gui.hiding.paused.tooltip" : "cpextended.gui.hiding.tooltip")));
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.add_window"), b -> addWindow())
				.bounds(width / 2 - 154, height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 4, height - 28, 150, 20).build());
	}

	/** "Window 1: Smoke, Everything" (M2: a derived label; the id is in the tooltip). */
	static String label(ExtendedConfig.Window w) {
		if (w.id() == null) return "(unreadable window entry, kept as stored)";
		return Labels.windowWithTabs(w) + (w.suspended() ? " - suspended: " + w.errors() : "");
	}

	private static final int BANNER_LINES = 3;

	/** Below the recovery buttons, the save-state explanation, wrapped (M3); the list starts after it. */
	@Override
	protected int listTop() {
		if (ConfigManager.get().state() == ConfigManager.State.NORMAL) return 58;
		return 56 + Math.min(BANNER_LINES, bannerLines().size()) * 10 + (bannerTruncated() ? 24 : 4);
	}

	/** Rows end above the status line (height - 64). */
	@Override
	protected int rowsPerPage() {
		return Math.max(1, (height - 66 - listTop()) / ROW);
	}

	private int bannerWidth() {
		return Math.min(width - 20, 308);
	}

	private List<FormattedCharSequence> bannerLines() {
		return font.split(Component.literal(ConfigManager.get().stateDetail()), bannerWidth());
	}

	private boolean bannerTruncated() {
		return ConfigManager.get().state() != ConfigManager.State.NORMAL && bannerLines().size() > BANNER_LINES;
	}

	private static Component when(ConfigStorage.BackupInfo b) {
		return Component.literal(b.modified() == null ? "unknown time"
				: DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.ofInstant(b.modified().toInstant(), ZoneId.systemDefault())));
	}

	private static Component restoreConfirmation(ConfigManager m, ConfigStorage.BackupInfo b) {
		Component kept = m.corruptCopy() != null
				? Component.translatable("cpextended.gui.restore_backup.corrupt_kept", m.corruptCopy().getFileName().toString())
				: Component.translatable("cpextended.gui.restore_backup.replaced");
		return Component.translatable("cpextended.gui.restore_backup.confirm", m.storage().backup().getFileName().toString(), when(b), kept);
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
			// Persistent banner (P14): what's wrong, and which file and backup are involved. Wrapped below the recovery
			// buttons and above the list (M3); when it doesn't fit, the last line ends with "..." and Details shows all of it.
			List<FormattedCharSequence> lines = bannerLines();
			boolean cut = lines.size() > BANNER_LINES;
			for (int i = 0; i < Math.min(BANNER_LINES, lines.size()); i++) {
				FormattedCharSequence line = lines.get(i);
				if (cut && i == BANNER_LINES - 1) {
					String text = font.plainSubstrByWidth(plain(line), bannerWidth() - font.width("..."));
					line = Component.literal(text + "...").getVisualOrderText();
				}
				g.drawCenteredString(font, line, width / 2, 56 + i * 10, 0xFFFFCC66);
			}
		}
		if (m.config().windows().isEmpty()) {
			// M4: what windows and tabs are, and the next action.
			List<FormattedCharSequence> help = font.split(Component.translatable("cpextended.gui.empty"), Math.min(width - 40, 300));
			int y0 = listTop() + 6;
			for (int i = 0; i < help.size(); i++) g.drawCenteredString(font, help.get(i), width / 2, y0 + i * 10, 0xFFCCCCCC);
		}
	}

	private static String plain(FormattedCharSequence seq) {
		StringBuilder sb = new StringBuilder();
		seq.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
		return sb.toString();
	}

	/** Result of the external-edit check run when the screen opened (for diagnostics and tests). */
	public @Nullable String checkResult() { return checkResult; }
}
