// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test;

import java.nio.file.Files;
import java.nio.file.Path;

/** The scenario for this launch, from {@code cpx-mode.txt} in the run directory. */
public final class Mode {
	private Mode() {}

	public static String read() {
		try {
			return Files.readString(Path.of("cpx-mode.txt")).trim();
		} catch (Exception e) {
			return "live";
		}
	}
}
