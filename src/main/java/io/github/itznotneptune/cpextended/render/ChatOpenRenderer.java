// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.render;

import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.MessageRecord;
import io.github.itznotneptune.cpextended.core.Router;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.model.RoutingConfig;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.search.TabSearch;
import io.github.itznotneptune.cpextended.ui.HitTester;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.jetbrains.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The P10 chat-open path: drawn from {@code ScreenEvents.beforeRender} of the {@link ChatScreen}, followed by
 * {@code GuiGraphics.nextStratum()}, so window backgrounds and text sit beneath everything the chat screen draws (main
 * chat lines, input, suggestions, Chat Patches' UI). Drawn under F1 (Q26); not under Chat Visibility: Hidden or for
 * disabled windows. Each window shows a tab strip (unread badges, error markers, save-lock icon), its body at full
 * opacity with independent scrolling, a scrollbar and a resize grip. The layouts drawn in a frame are what input
 * hit-testing uses (V-54).
 */
public final class ChatOpenRenderer {
	public static final int STRIP_H = 12;
	static final int SCROLLBAR_W = 3;
	static final int GRIP = 6;

	/** One tab button in the strip. {@code diagnostic} is the suspension reason (error codes, never message text). */
	public record TabSlot(String tabId, int x, int y, int w, int h, boolean suspended, @Nullable String diagnostic) {
		public boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
	}

	/** One drawn text line: GUI position of its first character, height, scale and contents (for hover and click). */
	public record LineSlot(int x, int y, int h, double scale, FormattedCharSequence seq) {}

	/**
	 * Everything input needs about a window as it was drawn. Rectangles are {x, y, w, h} in GUI pixels. When the tabs
	 * don't fit, the strip scrolls: {@code leftArrow}/{@code rightArrow} are shown (null otherwise) and {@code tabs}
	 * holds the visible ones; {@code allTabs} counts every tab.
	 */
	public record Layout(String windowId, int[] rect, int[] strip, List<TabSlot> tabs, int @Nullable [] lock, int[] body,
			int @Nullable [] scrollbar, int[] grip, List<LineSlot> lines, int totalLines, int visibleLines, int maxScroll,
			int @Nullable [] leftArrow, int @Nullable [] rightArrow, int allTabs, int firstTab, int leftArrowColor, int rightArrowColor) {
		public boolean contains(double px, double py) { return in(rect, px, py); }
	}

	static final int ARROW_W = 9;
	private static final Map<String, Integer> TAB_SCROLL = new HashMap<>();
	private static final Map<String, String> LAST_SHOWN = new HashMap<>();

	private static final Map<String, Integer> SCROLL = new HashMap<>();
	/** Separate scroll for search views (P13): reset on every search change, never touching the normal view's scroll. */
	private static final Map<String, Integer> SEARCH_SCROLL = new HashMap<>();
	private static long seenSearchChange = -1;
	private static final Map<String, TabSearch.Indicator> INDICATORS = new HashMap<>();
	private static final Map<String, List<MessageRecord>> VIEWS = new HashMap<>();
	private record LineCount(List<MessageRecord> entries, int width, int total) {}
	private static final Map<String, LineCount> LINE_COUNTS = new HashMap<>();
	private static long renderNanosMax, renderNanosTotal, renderFrames, renderAllocTotal;
	/** Layouts of the last frame, front to back. */
	private static List<Layout> lastLayouts = List.of();
	private static long tooltipFrames;

	private ChatOpenRenderer() {}

	public static boolean in(int @Nullable [] r, double px, double py) {
		return r != null && px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[3];
	}

	/** Whether the chat-open path draws a window now (Q26: F1 doesn't matter here). */
	public static boolean drawnWithChatOpen(ExtendedConfig cfg, ExtendedConfig.Window w) {
		Minecraft mc = Minecraft.getInstance();
		return cfg.enabled() && !w.suspended() && w.enabled() && mc.options.chatVisibility().get() != ChatVisiblity.HIDDEN;
	}

	/** beforeRender of the ChatScreen: draws every visible window back to front, then moves to the next stratum. */
	public static void render(ChatScreen screen, GuiGraphics g, int mouseX, int mouseY) {
		long t0 = System.nanoTime();
		long a0 = Diagnostics.ENABLED ? allocatedBytes() : 0;
		Minecraft mc = Minecraft.getInstance();
		ExtendedConfig cfg = ConfigManager.get().config();
		TabSearch.update(screen);
		if (TabSearch.changeCount() != seenSearchChange) { // query or settings changed: every search view to its newest entry
			seenSearchChange = TabSearch.changeCount();
			SEARCH_SCROLL.clear();
		}
		List<Layout> drawn = new ArrayList<>();
		if (cfg.enabled() && mc.options.chatVisibility().get() != ChatVisiblity.HIDDEN) {
			RenderSupport.Look look = RenderSupport.look(cfg.appearance());
			RoutingConfig routing = Router.get().config();
			for (ExtendedConfig.Window w : cfg.windows()) { // back to front (Q30a/Q34)
				if (!drawnWithChatOpen(cfg, w)) continue;
				WindowRenderer.countDraw(g, w.id());
				drawn.add(drawWindow(g, mc.font, look, w, routing));
			}
		}
		List<Layout> frontToBack = new ArrayList<>(drawn);
		java.util.Collections.reverse(frontToBack);
		lastLayouts = List.copyOf(frontToBack);
		g.nextStratum(); // everything the chat screen draws now sits above the windows (P10)
		long dt = System.nanoTime() - t0;
		renderNanosMax = Math.max(renderNanosMax, dt);
		renderNanosTotal += dt;
		renderFrames++;
		if (Diagnostics.ENABLED) renderAllocTotal += allocatedBytes() - a0;
	}

	/** Bytes allocated so far by this thread (developer measurement only, V-13). */
	private static long allocatedBytes() {
		return ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean mx ? mx.getCurrentThreadAllocatedBytes() : 0;
	}

	private static Layout drawWindow(GuiGraphics g, Font font, RenderSupport.Look look, ExtendedConfig.Window w, RoutingConfig routing) {
		double scale = look.scale();
		int lineH = look.lineHeight();
		int[] r = RenderSupport.rect(g.guiWidth(), g.guiHeight(), w, STRIP_H + (int) Math.ceil(lineH * scale) * 2 + RenderSupport.PADDING * 2);
		WindowRenderer.recordRect(w.id(), r);
		int[] strip = {r[0], r[1], r[2], STRIP_H};
		int[] body = {r[0], r[1] + STRIP_H, r[2], r[3] - STRIP_H};

		// Body background, then strip.
		g.fill(body[0], body[1], body[0] + body[2], body[1] + body[3], RenderSupport.argb(look.bgOpacity(), 0));
		g.fill(strip[0], strip[1], strip[0] + strip[2], strip[1] + strip[3], RenderSupport.argb(Math.min(1.0, look.bgOpacity() + 0.35), 0x101010));

		// Tabs: name, unread badge, error marker. When they don't fit, the strip scrolls with arrows at its ends.
		ExtendedConfig.Tab shown = w.shownTab();
		int lockSpace = ConfigManager.get().state() == ConfigManager.State.NORMAL ? 0 : 12;
		List<ExtendedConfig.Tab> tabs = new ArrayList<>();
		List<Component> labels = new ArrayList<>();
		List<String> diagnostics = new ArrayList<>();
		List<Integer> unreadCounts = new ArrayList<>();
		int total = 0;
		for (ExtendedConfig.Tab t : w.tabs()) {
			if (t.id() == null) continue;
			TabState st = SessionMirror.get().tab(t.id());
			String diagnostic = diagnostic(t, st, routing);
			String name = t.config() != null ? t.config().name() : t.id();
			int unread = st == null ? 0 : st.unreadCount();
			Component label = Component.literal(name)
					.append(unread > 0 ? Component.literal(" " + unread).withColor(0xFFFF55) : Component.empty())
					.append(diagnostic != null ? Component.literal(" !").withColor(0xFF5555) : Component.empty());
			tabs.add(t);
			labels.add(label);
			diagnostics.add(diagnostic);
			unreadCounts.add(unread);
			total += font.width(label) + 7;
		}
		int avail = strip[2] - 4 - lockSpace;
		boolean overflow = total > avail;
		int left = strip[0] + 2 + (overflow ? ARROW_W : 0);
		int right = strip[0] + strip[2] - lockSpace - (overflow ? ARROW_W : 0);
		int first = overflow ? Math.max(0, Math.min(TAB_SCROLL.getOrDefault(w.id(), 0), tabs.size() - 1)) : 0;
		// Bring a newly selected tab into view (only when the selection changes, so the strip stays where it's scrolled).
		int shownIndex = tabs.indexOf(shown);
		String shownId = shown == null ? null : shown.id();
		if (overflow && shownIndex >= 0 && !java.util.Objects.equals(LAST_SHOWN.get(w.id()), shownId)) {
			if (shownIndex < first) first = shownIndex;
			while (first < shownIndex && fits(font, labels, first, shownIndex, right - left) == false) first++;
		}
		LAST_SHOWN.put(w.id(), shownId);
		TAB_SCROLL.put(w.id(), first);
		List<TabSlot> slots = new ArrayList<>();
		int x = left;
		int last = first - 1;
		for (int i = first; i < tabs.size(); i++) {
			int sw = font.width(labels.get(i)) + 6;
			if (x + sw > right) break;
			boolean selected = tabs.get(i) == shown;
			if (selected) g.fill(x, strip[1] + 1, x + sw, strip[1] + STRIP_H, 0x40FFFFFF);
			g.drawString(font, labels.get(i), x + 3, strip[1] + 2, selected ? 0xFFFFFFFF : 0xFFB0B0B0, true);
			slots.add(new TabSlot(tabs.get(i).id(), x, strip[1], sw, STRIP_H, diagnostics.get(i) != null, diagnostics.get(i)));
			x += sw + 1;
			last = i;
		}
		int[] leftArrow = null, rightArrow = null;
		int leftColor = 0, rightColor = 0;
		if (overflow) {
			leftArrow = new int[]{strip[0] + 1, strip[1], ARROW_W, STRIP_H};
			rightArrow = new int[]{right, strip[1], ARROW_W, STRIP_H};
			leftColor = arrowColor(first > 0, unreadCounts, diagnostics, 0, first);
			rightColor = arrowColor(last < tabs.size() - 1, unreadCounts, diagnostics, last + 1, tabs.size());
			g.drawString(font, "<", leftArrow[0] + 2, strip[1] + 2, leftColor, true);
			g.drawString(font, ">", rightArrow[0] + 2, strip[1] + 2, rightColor, true);
		}

		// Save-lock icon (P14): no-save is amber, divergence lock red.
		int[] lock = null;
		ConfigManager.State state = ConfigManager.get().state();
		if (state != ConfigManager.State.NORMAL) {
			lock = new int[]{strip[0] + strip[2] - 11, strip[1] + 1, 10, 10};
			int c = state == ConfigManager.State.NO_SAVE ? 0xFFFFAA00 : 0xFFFF4040;
			g.fill(lock[0] + 3, lock[1] + 1, lock[0] + 7, lock[1] + 2, c);
			g.fill(lock[0] + 2, lock[1] + 2, lock[0] + 3, lock[1] + 5, c);
			g.fill(lock[0] + 7, lock[1] + 2, lock[0] + 8, lock[1] + 5, c);
			g.fill(lock[0] + 1, lock[1] + 5, lock[0] + 9, lock[1] + 10, c);
		}

		// Body lines, newest at the bottom, scrolled by this window's own offset.
		TabState st = shown == null || shown.id() == null ? null : SessionMirror.get().tab(shown.id());
		TabSearch.View view = shown == null || shown.id() == null ? new TabSearch.View(false, List.of(), TabSearch.Indicator.NONE) : TabSearch.view(shown.id(), st);
		List<MessageRecord> entries = view.entries(); // the search view while searching (display only), else the full history
		INDICATORS.put(w.id(), view.indicator());
		VIEWS.put(w.id(), entries);
		Map<String, Integer> scrollMap = view.searching() ? SEARCH_SCROLL : SCROLL;
		int innerW = Math.max(1, (int) ((body[2] - RenderSupport.PADDING * 2 - SCROLLBAR_W) / scale));
		int visible = Math.max(0, (int) ((body[3] - RenderSupport.PADDING * 2) / (lineH * scale)));
		// Total wrapped lines: cached per window while its entry list (an immutable snapshot) and width are unchanged, so
		// a frame touches only the lines it draws (V-13).
		LineCount lc = LINE_COUNTS.get(w.id());
		if (lc == null || lc.entries() != entries || lc.width() != innerW) {
			int n = 0;
			for (MessageRecord e : entries) n += RenderSupport.wrap(e, font, innerW).size();
			LINE_COUNTS.put(w.id(), lc = new LineCount(entries, innerW, n));
		}
		int totalLines = lc.total();
		int maxScroll = Math.max(0, totalLines - visible);
		int scroll = Math.min(scrollMap.getOrDefault(w.id(), 0), maxScroll);
		scrollMap.put(w.id(), scroll);
		List<LineSlot> lines = new ArrayList<>();
		int textColor = RenderSupport.argb(look.textOpacity(), 0xFFFFFF);
		int pad = (lineH - 9) / 2;
		int lineNo = 0, k = 0; // lineNo counts lines newest first
		for (int i = entries.size() - 1; i >= 0 && k < visible; i--) {
			List<FormattedCharSequence> ls = RenderSupport.wrap(entries.get(i), font, innerW);
			if (lineNo + ls.size() <= scroll) { lineNo += ls.size(); continue; }
			for (int j = ls.size() - 1; j >= 0 && k < visible; j--, lineNo++) {
				if (lineNo < scroll) continue;
				int ly = body[1] + body[3] - RenderSupport.PADDING - (int) Math.round((k + 1) * lineH * scale);
				FormattedCharSequence seq = ls.get(j);
				int lx = body[0] + RenderSupport.PADDING;
				g.pose().pushMatrix();
				g.pose().translate(lx, ly);
				g.pose().scale((float) scale, (float) scale);
				g.drawString(font, seq, 0, pad, textColor, true);
				g.pose().popMatrix();
				lines.add(new LineSlot(lx, ly, (int) Math.round(lineH * scale), scale, seq));
				k++;
			}
		}

		// Search indicators (display only, never a message): "No matches" (P13) or "Partial results" (Q31).
		if (view.indicator() != TabSearch.Indicator.NONE) {
			Component note = Component.translatable(switch (view.indicator()) {
				case NO_MATCHES -> "cpextended.search.no_matches";
				case INVALID -> "cpextended.search.invalid";
				default -> "cpextended.search.partial";
			});
			int nx = body[0] + body[2] - SCROLLBAR_W - 2 - font.width(note);
			int color = view.indicator() == TabSearch.Indicator.INVALID ? 0xFFE07070 : 0xFF909090;
			g.drawString(font, note, Math.max(body[0] + 2, nx), body[1] + 2, color, true);
		}

		// Scrollbar and resize grip.
		int[] scrollbar = null;
		if (maxScroll > 0) {
			scrollbar = new int[]{body[0] + body[2] - SCROLLBAR_W, body[1], SCROLLBAR_W, body[3]};
			int thumbH = Math.max(6, body[3] * visible / Math.max(1, totalLines));
			int thumbY = body[1] + (body[3] - thumbH) * (maxScroll - scroll) / maxScroll;
			g.fill(scrollbar[0], scrollbar[1], scrollbar[0] + scrollbar[2], scrollbar[1] + scrollbar[3], 0x40FFFFFF);
			g.fill(scrollbar[0], thumbY, scrollbar[0] + scrollbar[2], thumbY + thumbH, 0xC0FFFFFF);
		}
		int[] grip = {r[0] + r[2] - GRIP, r[1] + r[3] - GRIP, GRIP, GRIP};
		for (int i = 0; i < 3; i++) g.fill(grip[0] + GRIP - 2 - i * 2, grip[1] + GRIP - 2, grip[0] + GRIP - 1 - i * 2, grip[1] + GRIP - 1, 0xC0FFFFFF);
		for (int i = 1; i < 3; i++) g.fill(grip[0] + GRIP - 2, grip[1] + GRIP - 2 - i * 2, grip[0] + GRIP - 1, grip[1] + GRIP - 1 - i * 2, 0xC0FFFFFF);
		return new Layout(w.id(), r, strip, List.copyOf(slots), lock, body, scrollbar, grip, List.copyOf(lines), totalLines, visible, maxScroll,
				leftArrow, rightArrow, tabs.size(), first, leftColor, rightColor);
	}

	private static boolean fits(Font font, List<Component> labels, int from, int to, int width) {
		int x = 0;
		for (int i = from; i <= to; i++) x += font.width(labels.get(i)) + 7;
		return x <= width;
	}

	/** Arrow colour: dim when nothing is scrolled out that way; red if a hidden tab is suspended, yellow if one has unread. */
	private static int arrowColor(boolean active, List<Integer> unread, List<String> diagnostics, int from, int to) {
		if (!active) return 0xFF505050;
		boolean err = false, any = false;
		for (int i = from; i < to; i++) {
			err |= diagnostics.get(i) != null;
			any |= unread.get(i) > 0;
		}
		return err ? 0xFFFF5555 : any ? 0xFFFFFF55 : 0xFFFFFFFF;
	}

	/** Scrolls a window's tab strip by whole tabs (overflow navigation). */
	public static void scrollTabs(String windowId, int delta) {
		TAB_SCROLL.put(windowId, Math.max(0, TAB_SCROLL.getOrDefault(windowId, 0) + delta));
	}

	private static @Nullable String diagnostic(ExtendedConfig.Tab t, @Nullable TabState st, RoutingConfig routing) {
		if (t.config() == null) return "configuration entry unreadable";
		for (RoutingConfig.CompiledTab ct : routing.tabs()) {
			if (ct.id().equals(t.id()) && ct.suspended()) return "suspended: " + String.join(", ", ct.errors());
		}
		if (st != null && st.isRuntimeSuspended()) return "suspended until restart: filter error (" + st.suspensionReason() + ")";
		return null;
	}

	/**
	 * afterRender of the ChatScreen: Extended's own hover detection over its windows. Suppressed over protected regions
	 * (P10). Fabric's afterRender runs after the deferred tooltip pass, so the tooltip is drawn here in a new stratum,
	 * in the same frame (Phase 0 F-3).
	 */
	public static void renderTooltip(ChatScreen screen, GuiGraphics g, int mx, int my) {
		String dragged = io.github.itznotneptune.cpextended.ui.ChatScreenInput.draggedTabLabel();
		if (dragged != null) { // the tab being dragged follows the cursor, above everything
			Font f = Minecraft.getInstance().font;
			g.nextStratum();
			g.fill(mx + 4, my - 2, mx + 10 + f.width(dragged), my + 10, 0xC0303030);
			g.drawString(f, dragged, mx + 7, my, 0xFFFFFFFF, true);
			return;
		}
		if (HitTester.protectedRegion(screen, mx, my) != null) return;
		Layout top = topAt(mx, my);
		if (top == null) return;
		Font font = Minecraft.getInstance().font;
		for (LineSlot l : top.lines()) {
			if (my >= l.y() && my < l.y() + l.h()) {
				Style style = styleAt(font, l, mx);
				if (style != null && style.getHoverEvent() != null) {
					g.nextStratum();
					g.renderComponentHoverEffect(font, style, mx, my);
					g.renderDeferredElements();
					tooltipFrames++;
				}
				return;
			}
		}
		for (TabSlot s : top.tabs()) {
			if (s.contains(mx, my) && s.diagnostic() != null) {
				tooltip(g, font, Component.literal(s.diagnostic()), mx, my);
				return;
			}
		}
		if (in(top.lock(), mx, my)) {
			ConfigManager m = ConfigManager.get();
			tooltip(g, font, Component.literal((m.state() == ConfigManager.State.NO_SAVE ? "Saving disabled (no-save): " : "Saving disabled (divergence lock): ")
					+ m.stateDetail()), mx, my);
		}
	}

	private static void tooltip(GuiGraphics g, Font font, Component text, int mx, int my) {
		g.nextStratum();
		g.setTooltipForNextFrame(font, font.split(text, 220), mx, my);
		g.renderDeferredElements();
		tooltipFrames++;
	}

	/** The style of the character under {@code mx} on a drawn line, or null past its end. */
	public static @Nullable Style styleAt(Font font, LineSlot line, double mx) {
		double local = (mx - line.x()) / line.scale();
		if (local < 0) return null;
		float[] acc = {0};
		Style[] hit = {null};
		line.seq().accept((index, style, codePoint) -> {
			float w = font.width(FormattedCharSequence.codepoint(codePoint, style));
			if (local < acc[0] + w) {
				hit[0] = style;
				return false;
			}
			acc[0] += w;
			return true;
		});
		return hit[0];
	}

	/** Topmost window drawn at the point in the last frame, or null. */
	public static @Nullable Layout topAt(double x, double y) {
		for (Layout l : lastLayouts) if (l.contains(x, y)) return l;
		return null;
	}

	public static List<Layout> lastLayouts() { return lastLayouts; }

	/** The scroll map in use: search views have their own while a search is active. */
	private static Map<String, Integer> scrollMap() {
		return TabSearch.state().active() ? SEARCH_SCROLL : SCROLL;
	}

	public static void scroll(String windowId, int lines) {
		Layout l = null;
		for (Layout c : lastLayouts) if (c.windowId().equals(windowId)) l = c;
		int max = l == null ? Integer.MAX_VALUE : l.maxScroll();
		Map<String, Integer> m = scrollMap();
		m.put(windowId, Math.max(0, Math.min(max, m.getOrDefault(windowId, 0) + lines)));
	}

	public static void setScroll(String windowId, int value) {
		scrollMap().put(windowId, Math.max(0, value));
	}

	public static int scrollOf(String windowId) { return scrollMap().getOrDefault(windowId, 0); }
	public static int normalScrollOf(String windowId) { return SCROLL.getOrDefault(windowId, 0); }
	public static TabSearch.Indicator indicatorOf(String windowId) { return INDICATORS.getOrDefault(windowId, TabSearch.Indicator.NONE); }
	/** Entries a window showed in the last frame (oldest first): the search view while searching. */
	public static List<MessageRecord> viewOf(String windowId) { return VIEWS.getOrDefault(windowId, List.of()); }
	public static long renderNanosMax() { return renderNanosMax; }
	public static double renderNanosMean() { return renderFrames == 0 ? 0 : renderNanosTotal / (double) renderFrames; }
	public static long renderFrames() { return renderFrames; }
	/** Bytes allocated inside {@link #render} since the last reset (diagnostics enabled only), vanilla drawing calls included. */
	public static long renderAllocatedBytes() { return renderAllocTotal; }
	public static void resetRenderStats() { renderNanosMax = renderNanosTotal = renderFrames = renderAllocTotal = 0; }
	public static long tooltipFrames() { return tooltipFrames; }
}
