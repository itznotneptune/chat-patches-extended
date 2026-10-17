package io.github.itznotneptune.cpxspike.s2;

import io.github.itznotneptune.cpxspike.SpikeLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One fake Extended window (S2). Magenta background (0xFFFF00FF) and pure-green text (0xFF00FF00) make
 * unblended window pixels detectable in screenshots (V-53).
 */
public final class SpikeWindow {
	public static final int BG = 0xFFFF00FF;
	public static final int TEXT = 0xFF00FF00;
	public static final String HOVER_WORD = "HOVERME";

	public static volatile boolean enabled = true;
	public static volatile boolean stratum = true;
	public static volatile int x = 4, y = 30, w = 220, h = 60;

	public static final AtomicInteger vanillaClicks = new AtomicInteger();
	public static final AtomicInteger vanillaScrolls = new AtomicInteger();
	public static final AtomicInteger frameAnomalies = new AtomicInteger();
	public static final AtomicInteger framesChecked = new AtomicInteger();
	public static final Set<Long> deferredTooltipFrames = Collections.synchronizedSet(new LinkedHashSet<>());
	public static final Set<Long> tooltipDrawFrames = Collections.synchronizedSet(new LinkedHashSet<>());
	public static volatile boolean trackFrames = false;
	/** "screen" = beforeRender path (P10); "hud" = HUD element also while chat is open (diagnostic alternative). */
	public static volatile String path = "screen";
	public static volatile int windowStratum = -1, chatRenderStratum = -1;

	static int stratum(GuiGraphics g) {
		return ((io.github.itznotneptune.cpxspike.mixin.StrataProbeSpike.GuiRenderStateAcc)
				((io.github.itznotneptune.cpxspike.mixin.StrataProbeSpike.GuiGraphicsAcc) g).cpx$state()).cpx$strata().size();
	}

	private static long frame;
	private static int hudDraws, screenDraws;
	private static Boolean expected;

	private SpikeWindow() {}

	public static long frame() { return frame; }

	public static boolean contains(double px, double py) {
		return px >= x && px < x + w && py >= y && py < y + h;
	}

	static boolean visibleNow(Minecraft mc) {
		if (!enabled || mc.level == null) return false;
		if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return false;
		if (mc.screen instanceof ChatScreen) return true;
		return !mc.options.hideGui;
	}

	/** Called at the start of every frame (Minecraft.runTick HEAD): checks the previous frame had exactly one draw path. */
	public static void onFrameStart() {
		expected = visibleNow(Minecraft.getInstance()); // state that was in effect while the previous frame rendered
		if (trackFrames && frame > 0) {
			int draws = hudDraws + screenDraws;
			boolean bad = expected ? draws != 1 : draws != 0;
			framesChecked.incrementAndGet();
			if (bad) {
				frameAnomalies.incrementAndGet();
				SpikeLog.ev("frame_anomaly", "frame", frame, "expectedVisible", expected, "hudDraws", hudDraws, "screenDraws", screenDraws);
			}
		}
		frame++;
		hudDraws = 0;
		screenDraws = 0;
	}

	/** Gameplay path (P10): HUD element after VanillaHudElements.CHAT, explicit gating. */
	public static void renderHud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof ChatScreen && !"hud".equals(path)) return; // the screen path owns this frame
		if (!enabled || mc.options.hideGui || mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
		windowStratum = stratum(g);
		draw(g, mc.font);
		hudDraws++;
	}

	/** Chat-open path (P10): ScreenEvents.beforeRender, then nextStratum(). */
	public static void renderScreen(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if ("hud".equals(path)) return;
		if (!enabled || mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
		windowStratum = stratum(g);
		draw(g, mc.font);
		screenDraws++;
		if (stratum) g.nextStratum();
	}

	static void draw(GuiGraphics g, Font font) {
		g.fill(x, y, x + w, y + h, BG);
		String fillLine = "G".repeat(60);
		for (int i = 0; i < 5; i++) g.drawString(font, fillLine, x + 2, y + 2 + i * 10, TEXT, false);
		g.drawString(font, HOVER_WORD, x + 2, y + 52, TEXT, false);
	}

	static boolean overHoverWord(Font font, double mx, double my) {
		int hx = x + 2, hy = y + 52;
		return mx >= hx && mx < hx + font.width(HOVER_WORD) && my >= hy && my < hy + font.lineHeight;
	}

	/** Extended-side hover detection + tooltip drawn in afterRender (deferred elements already rendered by then). */
	public static void renderTooltip(ChatScreen screen, GuiGraphics g, int mx, int my) {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled || !overHoverWord(mc.font, mx, my)) return;
		if (InputSpike.protectedRegion(screen, mx, my) != null) return; // suppressed over protected UI
		g.nextStratum();
		g.setTooltipForNextFrame(mc.font, Component.literal("cpx tooltip"), mx, my);
		g.renderDeferredElements();
		tooltipDrawFrames.add(frame);
	}
}
