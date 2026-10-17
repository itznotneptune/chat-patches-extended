package io.github.itznotneptune.cpxspike.test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Spike run mode, read from cpx-mode.txt in the run directory (one game launch per mode). */
public final class Mode {
	private Mode() {}

	public static String read() {
		try {
			return Files.readString(Path.of("cpx-mode.txt")).trim();
		} catch (Exception e) {
			return "s1a";
		}
	}
}
