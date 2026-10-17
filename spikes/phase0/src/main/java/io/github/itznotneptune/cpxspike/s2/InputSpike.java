package io.github.itznotneptune.cpxspike.s2;

import io.github.itznotneptune.cpxspike.SpikeLog;
import io.github.itznotneptune.cpxspike.mixin.S2AccessorsSpike;
import io.github.itznotneptune.cpxspike.s1.SpikeAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import obro1961.chatpatches.ChatPatches;
import obro1961.chatpatches.accessor.ChatComponentAccess;
import obro1961.chatpatches.accessor.ChatScreenAccess;

import java.util.concurrent.atomic.AtomicInteger;

/** P11 protected-region-first input handling (S2 probe). */
public final class InputSpike {
	public static final AtomicInteger consumedClicks = new AtomicInteger();
	public static final AtomicInteger consumedScrolls = new AtomicInteger();
	public static volatile boolean dragging = false;
	private static double dragDx, dragDy;
	public static volatile int windowScroll = 0;

	private InputSpike() {}

	static boolean in(double px, double py, int x, int y, int w, int h) {
		return px >= x && px < x + w && py >= y && py < y + h;
	}

	/** Returns the protected region under the cursor, or null. Only visible/active regions count (Q18). */
	public static String protectedRegion(ChatScreen s, double x, double y) {
		Minecraft mc = Minecraft.getInstance();
		EditBox input = ((S2AccessorsSpike.ChatScreenAcc) s).cpx$input();
		if (input != null && input.isVisible() && in(x, y, input.getX(), input.getY(), input.getWidth(), input.getHeight())) return "input";
		CommandSuggestions cs = ((S2AccessorsSpike.ChatScreenAcc) s).cpx$commandSuggestions();
		if (cs != null) {
			CommandSuggestions.SuggestionsList list = ((S2AccessorsSpike.CommandSuggestionsAcc) cs).cpx$suggestions();
			if (list != null) {
				Rect2i r = ((S2AccessorsSpike.SuggestionsListAcc) list).cpx$rect();
				if (r.contains((int) x, (int) y)) return "suggestions";
			}
		}
		try {
			ChatScreenAccess cpa = (ChatScreenAccess) s;
			if (cpa.getContextMenu() != null && cpa.getContextMenu().isMouseOver(x, y)) return "cp_context_menu";
			if (cpa.isMouseOverSettingsMenu(x, y)) return "cp_settings";
		} catch (RuntimeException e) {
			return "cp_unknown_conservative";
		}
		if (ChatPatches.config.search && in(x, y, 2, s.height - 35, 16, 16)) return "cp_search_button";
		if (ChatPatches.config.search) {
			Boolean shown = SpikeAdapter.searchBarShown();
			if (shown == null || shown) {
				int fx = 22, fy = s.height - 31, fw = (int) (s.width * 0.25), fh = 12;
				try {
					EditBox f = SpikeAdapter.searchFieldOf(s);
					if (f != null) { fx = f.getX(); fy = f.getY(); fw = f.getWidth(); fh = f.getHeight(); }
				} catch (RuntimeException ignored) {
					// keep geometry fallback
				}
				if (in(x, y, fx, fy, fw, fh)) return shown == null ? "cp_search_field_reserved" : "cp_search_field";
			}
		}
		try {
			if (((ChatComponentAccess) mc.gui.getChat()).getEoEIndex(x, y) >= 0) return "main_chat_line";
		} catch (RuntimeException e) {
			return "main_chat_conservative";
		}
		return null;
	}

	static boolean contextMenuOpen(ChatScreen s) {
		try {
			var m = ((ChatScreenAccess) s).getContextMenu();
			return m != null && m.isFunctional();
		} catch (RuntimeException e) {
			return true; // conservative
		}
	}

	public static boolean allowClick(Screen screen, MouseButtonEvent e) {
		ChatScreen s = (ChatScreen) screen;
		double x = e.x(), y = e.y();
		int b = e.button();
		String decision;
		boolean allow;
		if (contextMenuOpen(s)) { decision = "pass:context_menu_open"; allow = true; }
		else {
			String region = protectedRegion(s, x, y);
			if (region != null) { decision = "pass:" + region; allow = true; }
			else if (SpikeWindow.contains(x, y)) {
				if (b == 0) {
					decision = "consume:left";
					allow = false;
					if (y < SpikeWindow.y + 10) { dragging = true; dragDx = x - SpikeWindow.x; dragDy = y - SpikeWindow.y; }
				} else if (b == 1) { decision = "consume:right_noop"; allow = false; }
				else { decision = "pass:button" + b; allow = true; }
			} else { decision = "pass:none"; allow = true; }
		}
		if (!allow) consumedClicks.incrementAndGet();
		SpikeLog.ev("click", "x", x, "y", y, "button", b, "decision", decision);
		return allow;
	}

	public static boolean allowScroll(Screen screen, double x, double y, double h, double v) {
		ChatScreen s = (ChatScreen) screen;
		String region = protectedRegion(s, x, y);
		boolean allow = region != null || !SpikeWindow.contains(x, y);
		if (!allow) { consumedScrolls.incrementAndGet(); windowScroll += (int) Math.signum(v); }
		SpikeLog.ev("scroll", "x", x, "y", y, "decision", allow ? "pass:" + region : "consume");
		return allow;
	}

	public static boolean allowDrag(Screen screen, MouseButtonEvent e, double dx, double dy) {
		if (!dragging) return true;
		SpikeWindow.x = (int) Math.max(0, Math.min(screen.width - SpikeWindow.w, e.x() - dragDx));
		SpikeWindow.y = (int) Math.max(0, Math.min(screen.height - SpikeWindow.h, e.y() - dragDy));
		return false;
	}

	public static boolean allowRelease(Screen screen, MouseButtonEvent e) {
		if (!dragging) return true;
		dragging = false;
		SpikeLog.ev("drag_end", "x", SpikeWindow.x, "y", SpikeWindow.y);
		return false;
	}
}
