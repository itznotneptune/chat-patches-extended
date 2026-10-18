// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.render;

import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Geometry, appearance and line wrapping shared by the gameplay (HUD) and chat-open paths (P10, §6.1). */
public final class RenderSupport {
	static final int MIN_WIDTH = 40;
	static final int PADDING = 2;

	/**
	 * Wrapped lines per (record, wrap width). A record can show in several windows of different widths, so each record
	 * keeps one entry per width; records die with the mirror, so the outer map is weak.
	 */
	private static final Map<MessageRecord, Map<Integer, List<FormattedCharSequence>>> LINES = new WeakHashMap<>();
	static long cacheHits, cacheMisses;

	/** Session-only geometry overrides while a window is being moved or resized (never written until committed). */
	private static final Map<String, int[]> OVERRIDES = new HashMap<>();

	private RenderSupport() {}

	/** Resolved look: the configuration's appearance where set, vanilla chat settings otherwise. */
	public record Look(double scale, int lineHeight, double textOpacity, double bgOpacity) {
		static Look of(ExtendedConfig.Appearance ap, Options o) {
			double scale = Mth.clamp(ap.scale() != null ? ap.scale() : o.chatScale().get(), 0.25, 4.0);
			double spacing = Mth.clamp(ap.lineSpacing() != null ? ap.lineSpacing() : o.chatLineSpacing().get(), 0.0, 1.0);
			double text = Mth.clamp(ap.textOpacity() != null ? ap.textOpacity() : o.chatOpacity().get() * 0.9 + 0.1, 0.0, 1.0);
			double bg = Mth.clamp(ap.backgroundOpacity() != null ? ap.backgroundOpacity() : o.textBackgroundOpacity().get(), 0.0, 1.0);
			return new Look(scale, Math.max(1, (int) (9.0 * (spacing + 1.0))), text, bg);
		}
	}

	public static Look look(ExtendedConfig.Appearance ap) {
		return Look.of(ap, Minecraft.getInstance().options);
	}

	/** Window rectangle in GUI pixels {left, top, width, height}: the drag override if any, else the stored geometry, clamped. */
	public static int[] rect(int sw, int sh, ExtendedConfig.Window w, int minHeight) {
		int[] o = w.id() == null ? null : OVERRIDES.get(w.id());
		if (o != null) return clamp(o.clone(), sw, sh, minHeight);
		int width = Mth.clamp((int) Math.round(w.width() * sw), Math.min(MIN_WIDTH, sw), sw);
		int height = Mth.clamp((int) Math.round(w.height() * sh), Math.min(minHeight, sh), sh);
		int dx = (int) Math.round(w.x() * sw), dy = (int) Math.round(w.y() * sh);
		int left = switch (w.anchor()) {
			case TOP_LEFT, BOTTOM_LEFT -> dx;
			case TOP_RIGHT, BOTTOM_RIGHT -> sw - dx - width;
		};
		int top = switch (w.anchor()) {
			case TOP_LEFT, TOP_RIGHT -> dy;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> sh - dy - height;
		};
		return clamp(new int[]{left, top, width, height}, sw, sh, minHeight);
	}

	static int[] clamp(int[] r, int sw, int sh, int minHeight) {
		r[2] = Mth.clamp(r[2], Math.min(MIN_WIDTH, sw), sw);
		r[3] = Mth.clamp(r[3], Math.min(minHeight, sh), sh);
		r[0] = Mth.clamp(r[0], 0, sw - r[2]);
		r[1] = Mth.clamp(r[1], 0, sh - r[3]);
		return r;
	}

	/** Stored-geometry fractions (relative to the window's anchor) for a pixel rectangle: what a committed move writes. */
	public static double[] fractions(ExtendedConfig.Anchor anchor, int[] r, int sw, int sh) {
		double x = switch (anchor) {
			case TOP_LEFT, BOTTOM_LEFT -> r[0];
			case TOP_RIGHT, BOTTOM_RIGHT -> sw - r[0] - r[2];
		};
		double y = switch (anchor) {
			case TOP_LEFT, TOP_RIGHT -> r[1];
			case BOTTOM_LEFT, BOTTOM_RIGHT -> sh - r[1] - r[3];
		};
		return new double[]{x / sw, y / sh, r[2] / (double) sw, r[3] / (double) sh};
	}

	public static void setOverride(String windowId, int @Nullable [] r) {
		if (r == null) OVERRIDES.remove(windowId);
		else OVERRIDES.put(windowId, r.clone());
	}

	static List<FormattedCharSequence> wrap(MessageRecord rec, Font font, int width) {
		Map<Integer, List<FormattedCharSequence>> byWidth = LINES.computeIfAbsent(rec, k -> new HashMap<>(2));
		List<FormattedCharSequence> lines = byWidth.get(width);
		if (lines != null) {
			cacheHits++;
			return lines;
		}
		cacheMisses++;
		lines = List.copyOf(rec.message().splitLines(font, width));
		byWidth.put(width, lines);
		return lines;
	}

	/** Vanilla's chat fade over 200 ticks (fully visible, then fading in the last tenth). */
	static double fade(int age) {
		double d = 1.0 - age / 200.0;
		d = Mth.clamp(d * 10.0, 0.0, 1.0);
		return d * d;
	}

	static int argb(double alpha, int rgb) {
		int a = (int) Math.round(Mth.clamp(alpha, 0.0, 1.0) * 255);
		return a << 24 | rgb & 0xFFFFFF;
	}
}
