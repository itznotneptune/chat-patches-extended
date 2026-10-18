// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.ui;

import io.github.itznotneptune.cpextended.compat.ChatPatchesAdapter;
import io.github.itznotneptune.cpextended.mixin.ChatScreenAccessor;
import io.github.itznotneptune.cpextended.mixin.CommandSuggestionsAccessor;
import io.github.itznotneptune.cpextended.mixin.SuggestionsListAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.Rect2i;
import org.jetbrains.annotations.Nullable;

/**
 * Protected regions of the chat screen (P11, Q11, Q18): the vanilla input, the open command-suggestion list, visible
 * main-chat lines, Chat Patches' context menu and settings panel, its search button and, when it may be shown, its
 * search field. Invisible or inactive regions never intercept; unreadable Chat Patches regions are treated as present
 * (Class L / Q29b conservative fallbacks).
 */
public final class HitTester {
	private HitTester() {}

	/** The protected region under the point, or null. */
	public static @Nullable String protectedRegion(ChatScreen s, double x, double y) {
		ChatPatchesAdapter cp = ChatPatchesAdapter.get();
		EditBox search = cp.searchFieldWidget(s);
		// Vanilla input: the drawn field (vanilla fills 2..width-2, height-14..height-2) and the widget's own bounds.
		if (in(x, y, 2, s.height - 14, s.width - 4, 12)) return "input";
		for (GuiEventListener child : s.children()) {
			if (child instanceof EditBox box && box != search && box.isVisible() && in(x, y, box.getX(), box.getY(), box.getWidth(), box.getHeight())) return "input";
		}
		CommandSuggestions cs = ((ChatScreenAccessor) s).cpextended$commandSuggestions();
		if (cs != null) {
			CommandSuggestions.SuggestionsList list = ((CommandSuggestionsAccessor) cs).cpextended$suggestions();
			if (list != null) {
				Rect2i r = ((SuggestionsListAccessor) list).cpextended$rect();
				if (r != null && r.contains((int) x, (int) y)) return "suggestions";
			}
		}
		if (cp.isOverChatPatchesUi(s, x, y)) return "cp_ui";
		int[] button = cp.searchButtonRect(s.height);
		if (button != null && in(x, y, button[0], button[1], button[2], button[3])) return "cp_search_button";
		int[] field = cp.searchFieldRect(s);
		if (field != null && in(x, y, field[0], field[1], field[2], field[3])) return "cp_search_field";
		if (cp.isOverMainChatLine(x, y, inMainChatArea(s, x, y))) return "main_chat";
		return null;
	}

	/** Approximate main-chat area (for the Class L fallback only): bottom-left, chat width by focused chat height. */
	static boolean inMainChatArea(ChatScreen s, double x, double y) {
		Minecraft mc = Minecraft.getInstance();
		double scale = mc.options.chatScale().get();
		int w = (int) Math.ceil(ChatComponent.getWidth(mc.options.chatWidth().get()) * scale) + 8;
		int h = (int) Math.ceil(ChatComponent.getHeight(mc.options.chatHeightFocused().get()) * scale);
		return in(x, y, 0, s.height - 40 - h, w, h);
	}

	static boolean in(double px, double py, int x, int y, int w, int h) {
		return px >= x && px < x + w && py >= y && py < y + h;
	}
}
