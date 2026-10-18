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
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The P10 gameplay path: a Fabric HUD element attached after vanilla's chat element, gated explicitly on F1, Chat
 * Visibility: Hidden and disabled windows, and inactive while the chat screen is open (the chat-open path,
 * {@link ChatOpenRenderer}, owns those frames). Each enabled window shows its selected tab's newest lines with vanilla's
 * fade. Geometry is clamped only when drawn; the stored values are never changed by drawing.
 */
public final class WindowRenderer {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("cpextended", "windows");

	// Verification counters shared by both paths (frame identity = the GuiGraphics instance, created once per frame).
	private static Object lastFrame;
	private static final Map<String, Integer> drawsThisFrame = new HashMap<>();
	private static long framesWithDraws, duplicateDraws, hudPathDraws;
	private static final Map<String, int[]> lastRects = new HashMap<>();
	private static final Map<String, Long> totalDraws = new HashMap<>();
	private static final Map<String, Integer> hudLines = new HashMap<>();

	private WindowRenderer() {}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, ID, WindowRenderer::render);
	}

	static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		if (mc.screen instanceof ChatScreen) return; // P10: the chat-open path draws these frames
		ExtendedConfig cfg = ConfigManager.get().config();
		if (!cfg.enabled()) return;
		Options o = mc.options;
		if (o.hideGui) return; // F1 hides windows in gameplay (Q26); explicit, not relying on Fabric's own gating
		if (o.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
		RenderSupport.Look look = RenderSupport.look(cfg.appearance());
		for (ExtendedConfig.Window w : cfg.windows()) { // array order = stacking order, back to front (Q34)
			if (w.suspended() || !w.enabled()) continue;
			countDraw(g, w.id());
			hudPathDraws++;
			drawWindow(g, mc, look, w);
		}
	}

	private static void drawWindow(GuiGraphics g, Minecraft mc, RenderSupport.Look look, ExtendedConfig.Window w) {
		Font font = mc.font;
		double scale = look.scale();
		int lineHeight = look.lineHeight();
		int[] r = RenderSupport.rect(g.guiWidth(), g.guiHeight(), w, (int) Math.ceil(lineHeight * scale) * 2 + RenderSupport.PADDING * 2);
		lastRects.put(w.id(), r);

		// A suspended tab keeps showing its read-only history; its error marker is in the chat-open tab strip (Q17).
		ExtendedConfig.Tab tab = w.shownTab();
		TabState state = tab == null || tab.id() == null ? null : SessionMirror.get().tab(tab.id());

		g.pose().pushMatrix();
		g.pose().translate(r[0], r[1]);
		g.pose().scale((float) scale, (float) scale);
		int width = (int) (r[2] / scale);
		int innerW = Math.max(1, width - RenderSupport.PADDING * 2);
		int innerH = (int) (r[3] / scale) - RenderSupport.PADDING * 2;
		int maxLines = Math.max(0, innerH / lineHeight);
		int y = innerH + RenderSupport.PADDING;
		List<MessageRecord> entries = state == null ? List.of() : state.entries();
		int guiTicks = mc.gui.getGuiTicks();
		int drawn = 0;
		for (int i = entries.size() - 1; i >= 0 && drawn < maxLines; i--) {
			MessageRecord rec = entries.get(i);
			double alpha = RenderSupport.fade(guiTicks - rec.message().addedTime());
			if (alpha * look.textOpacity() * 255 <= 3) break; // older lines are only fainter (vanilla stops here too)
			List<FormattedCharSequence> lines = RenderSupport.wrap(rec, font, innerW);
			for (int j = lines.size() - 1; j >= 0 && drawn < maxLines; j--) {
				y -= lineHeight;
				int pad = (lineHeight - 9) / 2;
				g.fill(0, y - pad - 1, width, y + 9 + pad, RenderSupport.argb(look.bgOpacity() * alpha, 0));
				g.drawString(font, lines.get(j), RenderSupport.PADDING, y + pad, RenderSupport.argb(look.textOpacity() * alpha, 0xFFFFFF), true);
				drawn++;
			}
		}
		hudLines.put(w.id(), drawn);
		g.pose().popMatrix();
	}

	static void countDraw(GuiGraphics g, String windowId) {
		if (g != lastFrame) {
			lastFrame = g;
			drawsThisFrame.clear();
		}
		int n = drawsThisFrame.merge(windowId, 1, Integer::sum);
		totalDraws.merge(windowId, 1L, Long::sum);
		if (n > 1) duplicateDraws++;
		if (drawsThisFrame.size() == 1 && n == 1) framesWithDraws++;
	}

	static void recordRect(String windowId, int[] r) {
		lastRects.put(windowId, r.clone());
	}

	// ---------------------------------------------------------------- verification counters

	public static long duplicateDraws() { return duplicateDraws; }
	public static long framesWithDraws() { return framesWithDraws; }
	/** Text lines the gameplay path drew for a window in its latest frame (fading, V-11). */
	public static int hudLinesDrawn(String windowId) { return hudLines.getOrDefault(windowId, 0); }
	/** Draws made by the gameplay (HUD) path only. */
	public static long hudPathDraws() { return hudPathDraws; }
	public static long cacheHits() { return RenderSupport.cacheHits; }
	public static long cacheMisses() { return RenderSupport.cacheMisses; }
	public static int[] lastRect(String windowId) { return lastRects.get(windowId); }
	/** Draws of a window since startup (monotonic), by either path. */
	public static long totalDraws(String windowId) { return totalDraws.getOrDefault(windowId, 0L); }
	/** Draws of each window in the most recent frame that drew anything. */
	public static Map<String, Integer> drawsInLastFrame() { return Map.copyOf(drawsThisFrame); }

	public static void resetCounters() {
		framesWithDraws = duplicateDraws = hudPathDraws = RenderSupport.cacheHits = RenderSupport.cacheMisses = 0;
		lastRects.clear();
	}
}
