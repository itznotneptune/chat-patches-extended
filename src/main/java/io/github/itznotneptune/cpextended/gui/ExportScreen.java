// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.LayoutFiles;
import io.github.itznotneptune.cpextended.config.LayoutTransfer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Exports everything, one window or one tab (as saved): copy the text to the clipboard or save it to a file in
 * {@code config/cpextended-exports/}. Exporting never changes the settings.
 */
public final class ExportScreen extends BaseScreen {
	private final String kind;
	private final String label;
	private final Supplier<LayoutTransfer.Export> source;
	private LayoutTransfer.@Nullable Export export;
	private @Nullable Path lastSaved;

	/** {@code kind} and {@code label} name the saved file ({@code <kind>-<label>-<time>.json}). */
	public ExportScreen(Screen parent, Component what, String kind, String label, Supplier<LayoutTransfer.Export> source) {
		super(Component.translatable("cpextended.gui.export.title", what), parent);
		this.kind = kind;
		this.label = label;
		this.source = source;
	}

	@Override
	protected void init() {
		if (export == null) {
			export = source.get();
			if (export.error() != null) setStatus(export.error(), true);
			else setStatus("Ready to export " + summary(export) + " (as saved)", false);
		}
		boolean ok = export.text() != null;
		int y = listTop();
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.export.copy"), b -> copy())
				.bounds(width / 2 - 154, y, 150, 20).build()).active = ok;
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.export.save"), b -> saveToFile())
				.bounds(width / 2 + 4, y, 150, 20).build()).active = ok;
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.transfer.open_folder"), b -> openFolder(this))
				.bounds(width / 2 - 75, y + ROW, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 - 75, height - 28, 150, 20).build());
	}

	static String summary(LayoutTransfer.Export e) {
		String tabs = e.tabs() + (e.tabs() == 1 ? " tab" : " tabs");
		if (e.windows() == 0) return tabs;
		return e.windows() + (e.windows() == 1 ? " window" : " windows") + " with " + tabs;
	}

	/** Copies the export to the clipboard; returns whether there was something to copy. */
	public boolean copy() {
		if (export == null || export.text() == null) return false;
		minecraft.keyboardHandler.setClipboard(export.text());
		setStatus("Copied " + summary(export) + " to the clipboard", false);
		return true;
	}

	/** Saves the export as a new file; returns it, or null if it couldn't be saved (the status says why). */
	public @Nullable Path saveToFile() {
		if (export == null || export.text() == null) return null;
		try {
			lastSaved = LayoutFiles.save(kind, label, export.text());
			setStatus("Saved as " + LayoutFiles.FOLDER + "/" + lastSaved.getFileName(), false);
			return lastSaved;
		} catch (IOException e) {
			setStatus("Couldn't save the file: " + e.getMessage(), true);
			return null;
		}
	}

	static void openFolder(BaseScreen screen) {
		try {
			Files.createDirectories(LayoutFiles.folder());
			Util.getPlatform().openPath(LayoutFiles.folder());
		} catch (IOException e) {
			screen.setStatus("Couldn't open the folder: " + e.getMessage(), true);
		}
	}

	public LayoutTransfer.@Nullable Export export() { return export; }
	public Component status() { return status; }
}
