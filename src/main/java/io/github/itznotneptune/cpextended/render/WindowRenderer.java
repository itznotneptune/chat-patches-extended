// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.render;

import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Read-only Extended windows (Phase 2), drawn by the P10 gameplay path: a Fabric HUD element attached after vanilla's
 * chat element, gated explicitly on F1, Chat Visibility: Hidden and disabled windows. Each enabled window shows its
 * selected tab's newest lines (the first tab if the selection is unknown), with the vanilla chat look unless the
 * configuration's appearance overrides it. Geometry is clamped only here; the stored values are never changed.
 *
 * <p>As P10 specifies for this path, it is inactive while the chat screen is open. With chat open, 1.21.11 draws the
 * main-chat lines in the HUD's chat layer, so anything this element drew would sit above them (measured in Phase 2).
 * Windows therefore don't show with chat open until Phase 3 adds the approved chat-open path ({@code beforeRender}
 * plus {@code nextStratum()}), with drawing under F1 (Q26), the tab strip, scrolling and interaction.
 */
public final class WindowRenderer {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("cpextended", "windows");
	static final int MIN_WIDTH = 40;
	static final int PADDING = 2;

	/**
	 * Wrapped lines per (record, wrap width) (§6.1). A record can show in several windows of different widths, so each
	 * record keeps one entry per width; records die with the mirror, so the outer map is weak.
	 */
	private static final Map<MessageRecord, Map<Integer, List<FormattedCharSequence>>> LINES = new WeakHashMap<>();

	// Verification counters (frame identity = the GuiGraphics instance, created once per frame).
	private static Object lastFrame;
	private static final Map<String, Integer> drawsThisFrame = new HashMap<>();
	private static long framesWithDraws, duplicateDraws, cacheHits, cacheMisses;
	private static final Map<String, int[]> lastRects = new HashMap<>();
	private static final Map<String, Long> totalDraws = new HashMap<>();

	private WindowRenderer() {}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, ID, WindowRenderer::render);
	}

	static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		ExtendedConfig cfg = ConfigManager.get().config();
		if (!cfg.enabled()) return;
		Options o = mc.options;
		if (o.hideGui) return; // F1 hides windows in gameplay (Q26); explicit, not relying on Fabric's own gating
		if (o.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
		if (mc.screen instanceof ChatScreen) return; // P10: the gameplay path is inactive while chat is open (Phase 3 path)
		if (g != lastFrame) {
			lastFrame = g;
			drawsThisFrame.clear();
		}
		for (ExtendedConfig.Window w : cfg.windows()) { // array order = stacking order, back to front (Q34)
			if (w.suspended() || !w.enabled()) continue;
			int n = drawsThisFrame.merge(w.id(), 1, Integer::sum);
			totalDraws.merge(w.id(), 1L, Long::sum);
			if (n > 1) duplicateDraws++;
			if (drawsThisFrame.size() == 1 && n == 1) framesWithDraws++;
			drawWindow(g, mc, cfg.appearance(), w);
		}
	}

	private static void drawWindow(GuiGraphics g, Minecraft mc, ExtendedConfig.Appearance ap, ExtendedConfig.Window w) {
		Options o = mc.options;
		Font font = mc.font;
		double scale = Mth.clamp(ap.scale() != null ? ap.scale() : o.chatScale().get(), 0.25, 4.0);
		double spacing = Mth.clamp(ap.lineSpacing() != null ? ap.lineSpacing() : o.chatLineSpacing().get(), 0.0, 1.0);
		double textOpacity = Mth.clamp(ap.textOpacity() != null ? ap.textOpacity() : o.chatOpacity().get() * 0.9 + 0.1, 0.0, 1.0);
		double bgOpacity = Mth.clamp(ap.backgroundOpacity() != null ? ap.backgroundOpacity() : o.textBackgroundOpacity().get(), 0.0, 1.0);
		int lineHeight = Math.max(1, (int) (9.0 * (spacing + 1.0)));

		int[] r = rect(g.guiWidth(), g.guiHeight(), w, (int) Math.ceil(lineHeight * scale) * 2 + PADDING * 2);
		lastRects.put(w.id(), r);
		int left = r[0], top = r[1], width = r[2], height = r[3];

		// A suspended tab keeps showing its read-only history; its error marker belongs to the Phase 3 tab strip (Q17).
		ExtendedConfig.Tab tab = w.shownTab();
		TabState state = tab == null || tab.id() == null ? null : SessionMirror.get().tab(tab.id());

		g.pose().pushMatrix();
		g.pose().translate(left, top);
		g.pose().scale((float) scale, (float) scale);
		int innerW = Math.max(1, (int) (width / scale) - PADDING * 2);
		int innerH = (int) (height / scale) - PADDING * 2;
		int y = innerH + PADDING;

		int maxLines = Math.max(0, innerH / lineHeight);

		List<MessageRecord> entries = state == null ? List.of() : state.entries();
		int guiTicks = mc.gui.getGuiTicks();
		int drawn = 0;
		for (int i = entries.size() - 1; i >= 0 && drawn < maxLines; i--) {
			MessageRecord rec = entries.get(i);
			double alpha = fade(guiTicks - rec.message().addedTime());
			if (alpha * textOpacity * 255 <= 3) break; // older lines are only fainter (vanilla stops here too)
			List<FormattedCharSequence> lines = wrap(rec, font, innerW);
			for (int j = lines.size() - 1; j >= 0 && drawn < maxLines; j--) {
				y -= lineHeight;
				g.fill(0, y - (lineHeight - 9) / 2 - 1, (int) (width / scale), y + 9 + (lineHeight - 9) / 2, argb(bgOpacity * alpha, 0));
				g.drawString(font, lines.get(j), PADDING, y + (lineHeight - 9) / 2, argb(textOpacity * alpha, 0xFFFFFF), true);
				drawn++;
			}
		}
		g.pose().popMatrix();
	}

	/** Window rectangle in GUI pixels {left, top, width, height}, clamped fully on-screen and to a minimum size. */
	static int[] rect(int sw, int sh, ExtendedConfig.Window w, int minHeight) {
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
		return new int[]{Mth.clamp(left, 0, sw - width), Mth.clamp(top, 0, sh - height), width, height};
	}

	private static List<FormattedCharSequence> wrap(MessageRecord rec, Font font, int width) {
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

	private static int argb(double alpha, int rgb) {
		int a = (int) Math.round(Mth.clamp(alpha, 0.0, 1.0) * 255);
		return a << 24 | rgb & 0xFFFFFF;
	}

	// ---------------------------------------------------------------- verification counters

	public static long duplicateDraws() { return duplicateDraws; }
	public static long framesWithDraws() { return framesWithDraws; }
	public static long cacheHits() { return cacheHits; }
	public static long cacheMisses() { return cacheMisses; }
	public static int[] lastRect(String windowId) { return lastRects.get(windowId); }
	/** Draws of a window since startup (monotonic). */
	public static long totalDraws(String windowId) { return totalDraws.getOrDefault(windowId, 0L); }
	/** Draws of each window in the most recent frame that drew anything. */
	public static Map<String, Integer> drawsInLastFrame() { return Map.copyOf(drawsThisFrame); }

	public static void resetCounters() {
		framesWithDraws = duplicateDraws = cacheHits = cacheMisses = 0;
		lastRects.clear();
	}
}
