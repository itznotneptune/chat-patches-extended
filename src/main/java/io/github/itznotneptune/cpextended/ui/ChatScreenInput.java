// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.ui;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.core.SessionMirror;
import io.github.itznotneptune.cpextended.core.TabState;
import io.github.itznotneptune.cpextended.mixin.ChatScreenAccessor;
import io.github.itznotneptune.cpextended.render.ChatOpenRenderer;
import io.github.itznotneptune.cpextended.render.RenderSupport;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

/**
 * Chat-open rendering hooks and P11 input for the {@link ChatScreen}, through Fabric's per-screen events only.
 * Precedence: an open Chat Patches context menu passes every click; then any visible protected region passes; then the
 * topmost unobstructed Extended window may consume (and comes to the front, Q30a); otherwise the event passes.
 * Left button: tab select, window move (strip background), resize (grip), scrollbar, text clicks via vanilla's handler.
 * Right button over a window: consumed, no action. Other buttons pass. A consumed event never reaches the screen, so the
 * chat input keeps focus, text, selection and cursor. Drags are captured until release and cancelled when the screen
 * closes (the window keeps its last committed geometry). Moving tabs between windows is not part of this phase.
 */
public final class ChatScreenInput {
	enum Kind { MOVE, RESIZE, SCROLLBAR }

	private record Drag(Kind kind, String windowId, double grabX, double grabY, int[] startRect, int[] layoutBody, int maxScroll) {}

	private static @Nullable Drag drag;
	private static int[] dragRect;
	private static long consumedClicks, consumedScrolls;

	private ChatScreenInput() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen chat)) return;
			ScreenEvents.beforeRender(screen).register((s, g, mx, my, delta) -> ChatOpenRenderer.render(chat, g, mx, my));
			ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> ChatOpenRenderer.renderTooltip(chat, g, mx, my));
			ScreenMouseEvents.allowMouseClick(screen).register(ChatScreenInput::allowClick);
			ScreenMouseEvents.allowMouseScroll(screen).register(ChatScreenInput::allowScroll);
			ScreenMouseEvents.allowMouseDrag(screen).register(ChatScreenInput::allowDrag);
			ScreenMouseEvents.allowMouseRelease(screen).register(ChatScreenInput::allowRelease);
			ScreenEvents.remove(screen).register(s -> cancelDrag("screen closed"));
		});
	}

	static boolean allowClick(Screen screen, MouseButtonEvent e) {
		ChatScreen s = (ChatScreen) screen;
		double x = e.x(), y = e.y();
		if (ChatPatchesAdapter.get().isContextMenuOpen(s)) return true; // Chat Patches closes or uses its menu
		if (HitTester.protectedRegion(s, x, y) != null) return true;
		ChatOpenRenderer.Layout top = ChatOpenRenderer.topAt(x, y);
		if (top == null) return true;
		int button = e.button();
		if (button == 1) { // right: consumed, no action (no Chat Patches context menu for a window area)
			consumedClicks++;
			return false;
		}
		if (button != 0) return true; // middle and side buttons pass through
		consumedClicks++;
		bringToFront(top.windowId());
		if (ChatOpenRenderer.in(top.grip(), x, y)) {
			startDrag(Kind.RESIZE, top, x, y);
			return false;
		}
		if (ChatOpenRenderer.in(top.scrollbar(), x, y)) {
			startDrag(Kind.SCROLLBAR, top, x, y);
			scrollbarTo(top.windowId(), top.body(), top.maxScroll(), y);
			return false;
		}
		for (ChatOpenRenderer.TabSlot slot : top.tabs()) {
			if (slot.contains(x, y)) {
				selectTab(top.windowId(), slot.tabId());
				return false;
			}
		}
		if (ChatOpenRenderer.in(top.strip(), x, y)) {
			startDrag(Kind.MOVE, top, x, y);
			return false;
		}
		for (ChatOpenRenderer.LineSlot line : top.lines()) {
			if (y >= line.y() && y < line.y() + line.h()) {
				Style style = ChatOpenRenderer.styleAt(Minecraft.getInstance().font, line, x);
				if (style != null && style.getClickEvent() != null) ((ChatScreenAccessor) s).cpextended$handleComponentClicked(style, e.hasShiftDown());
				break;
			}
		}
		return false;
	}

	static boolean allowScroll(Screen screen, double x, double y, double horizontal, double vertical) {
		ChatScreen s = (ChatScreen) screen;
		if (ChatPatchesAdapter.get().isContextMenuOpen(s)) return true;
		if (HitTester.protectedRegion(s, x, y) != null) return true;
		ChatOpenRenderer.Layout top = ChatOpenRenderer.topAt(x, y);
		if (top == null || vertical == 0) return true;
		consumedScrolls++;
		bringToFront(top.windowId());
		int lines = (int) Math.signum(vertical) * (Minecraft.getInstance().hasShiftDown() ? 1 : 7); // vanilla's chat step
		ChatOpenRenderer.scroll(top.windowId(), lines);
		return false;
	}

	static boolean allowDrag(Screen screen, MouseButtonEvent e, double dx, double dy) {
		Drag d = drag;
		if (d == null) return true;
		if (e.button() != 0) return false; // captured: nothing else acts while the left-button drag lasts
		int sw = screen.width, sh = screen.height;
		switch (d.kind()) {
			case MOVE -> {
				int[] r = {(int) Math.round(e.x() - d.grabX()), (int) Math.round(e.y() - d.grabY()), d.startRect()[2], d.startRect()[3]};
				dragRect = clampOnScreen(r, sw, sh);
				RenderSupport.setOverride(d.windowId(), dragRect);
			}
			case RESIZE -> {
				int[] r = {d.startRect()[0], d.startRect()[1], (int) Math.round(d.startRect()[2] + e.x() - d.grabX()), (int) Math.round(d.startRect()[3] + e.y() - d.grabY())};
				r[2] = Math.max(40, Math.min(r[2], sw - r[0]));
				r[3] = Math.max(ChatOpenRenderer.STRIP_H + 20, Math.min(r[3], sh - r[1]));
				dragRect = r;
				RenderSupport.setOverride(d.windowId(), dragRect);
			}
			case SCROLLBAR -> scrollbarTo(d.windowId(), d.layoutBody(), d.maxScroll(), e.y());
		}
		return false;
	}

	static boolean allowRelease(Screen screen, MouseButtonEvent e) {
		Drag d = drag;
		if (d == null) return true;
		drag = null;
		if (d.kind() != Kind.SCROLLBAR && dragRect != null) commitGeometry(d.windowId(), dragRect, screen.width, screen.height);
		RenderSupport.setOverride(d.windowId(), null);
		dragRect = null;
		return false;
	}

	/** Esc, the chat key or any other screen change mid-drag: the window keeps its last committed geometry. */
	static void cancelDrag(String why) {
		Drag d = drag;
		if (d == null) return;
		drag = null;
		dragRect = null;
		RenderSupport.setOverride(d.windowId(), null);
		Diagnostics.event("drag_cancelled", "kind", d.kind(), "window", d.windowId(), "why", why);
	}

	private static void startDrag(Kind kind, ChatOpenRenderer.Layout top, double x, double y) {
		int[] r = top.rect().clone();
		double grabX = kind == Kind.MOVE ? x - r[0] : x;
		double grabY = kind == Kind.MOVE ? y - r[1] : y;
		drag = new Drag(kind, top.windowId(), grabX, grabY, r, top.body().clone(), top.maxScroll());
		dragRect = kind == Kind.SCROLLBAR ? null : r;
	}

	private static void scrollbarTo(String windowId, int[] body, int maxScroll, double y) {
		double f = 1.0 - Math.max(0, Math.min(1, (y - body[1]) / Math.max(1.0, body[3])));
		ChatOpenRenderer.setScroll(windowId, (int) Math.round(f * maxScroll));
	}

	private static int[] clampOnScreen(int[] r, int sw, int sh) {
		r[0] = Math.max(0, Math.min(r[0], sw - r[2]));
		r[1] = Math.max(0, Math.min(r[1], sh - r[3]));
		return r;
	}

	private static void commitGeometry(String windowId, int[] r, int sw, int sh) {
		ExtendedConfig.Window w = ConfigManager.get().config().window(windowId);
		if (w == null) return;
		double[] f = RenderSupport.fractions(w.anchor(), r, sw, sh);
		ConfigManager.get().edit(new ConfigEdit.SetGeometry(windowId, f[0], f[1], f[2], f[3])); // debounced; P14 locks apply
	}

	/** Q30a: the interacted window comes to the front; the order is saved with the geometry debounce (Q34). */
	private static void bringToFront(String windowId) {
		var windows = ConfigManager.get().config().windows();
		if (!windows.isEmpty() && windowId.equals(windows.get(windows.size() - 1).id())) return;
		ConfigManager.get().edit(new ConfigEdit.BringToFront(windowId));
	}

	private static void selectTab(String windowId, String tabId) {
		ConfigManager.get().edit(new ConfigEdit.SelectTab(windowId, tabId));
		TabState st = SessionMirror.get().tab(tabId);
		if (st != null) st.markAllRead(); // P4.5
		ChatOpenRenderer.setScroll(windowId, 0);
	}

	public static boolean dragging() { return drag != null; }
	public static long consumedClicks() { return consumedClicks; }
	public static long consumedScrolls() { return consumedScrolls; }
}
