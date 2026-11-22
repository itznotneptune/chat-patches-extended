// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.config;

import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Export files: {@code config/cpextended-exports/*.json}. Never touches the settings file. */
public final class LayoutFiles {
	public static final String FOLDER = "cpextended-exports";
	/** Files listed for import, newest first. */
	public static final int MAX_LISTED = 100;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

	private LayoutFiles() {}

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(FOLDER);
	}

	/** Writes a new file named {@code <kind>-<label>-<timestamp>.json} (never overwrites); returns it. */
	public static Path save(String kind, String label, String text) throws IOException {
		Path dir = folder();
		Files.createDirectories(dir);
		String base = kind + (label.isEmpty() ? "" : "-" + safe(label)) + "-" + LocalDateTime.now().format(STAMP);
		for (int i = 0; ; i++) {
			Path p = dir.resolve(base + (i == 0 ? "" : "-" + i) + ".json");
			if (Files.exists(p)) continue;
			Files.writeString(p, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			return p;
		}
	}

	/** The export files, newest first (at most {@link #MAX_LISTED}); empty if the folder doesn't exist. */
	public static List<Path> list() {
		Path dir = folder();
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
			for (Path p : ds) if (Files.isRegularFile(p)) out.add(p);
		} catch (IOException e) {
			return out;
		}
		out.sort(Comparator.comparingLong(LayoutFiles::modified).reversed());
		return out.size() > MAX_LISTED ? new ArrayList<>(out.subList(0, MAX_LISTED)) : out;
	}

	/** The file's text, or null if it can't be read or is too large to be an export. */
	public static @Nullable String read(Path p) {
		try {
			if (Files.size(p) > LayoutTransfer.MAX_TEXT) return null;
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	/** File-name-safe: letters, digits, '-' and '_' only, at most 32 characters. */
	static String safe(String label) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < label.length() && sb.length() < 32; i++) {
			char c = label.charAt(i);
			if (c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_')) sb.append(c);
			else if (c == ' ' && sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
		}
		return sb.length() == 0 ? "export" : sb.toString();
	}
}
