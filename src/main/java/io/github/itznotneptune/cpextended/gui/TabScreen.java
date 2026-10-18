// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.gui;

import io.github.itznotneptune.cpextended.config.ConfigEdit;
import io.github.itznotneptune.cpextended.config.ConfigManager;
import io.github.itznotneptune.cpextended.config.ConfigParser;
import io.github.itznotneptune.cpextended.config.ExtendedConfig;
import io.github.itznotneptune.cpextended.filter.FilterSpec;
import io.github.itznotneptune.cpextended.filter.PatternValidator;
import io.github.itznotneptune.cpextended.model.TabConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One tab: name, enabled / hide-from-main-chat / restore-history / match-all, and its include and exclude filters
 * (Literal only, Q15). Rules (P8, Q14, Q14.1, Q28):
 * <ul>
 * <li>patterns are edited in vanilla {@link EditBox}es limited to 1,024 UTF-16 units (vanilla's own input filtering of
 * {@code §} and control characters applies to typing and pasting; no custom editor);</li>
 * <li>a stored pattern over the limit is never put into a truncating field: it's shown read-only with its error and an
 * explicit Replace action;</li>
 * <li>a stored regex or unknown-mode filter is shown as unsupported and never executed; "Switch to Literal" keeps the
 * pattern exactly and revalidates it;</li>
 * <li>Save validates only new or changed filters: an invalid one blocks the save with an inline error. Untouched invalid
 * filters are written back unchanged. Edits apply to future messages only (Q9).</li>
 * </ul>
 */
public final class TabScreen extends BaseScreen {
	/** Editing state of one filter row. {@code originalIndex} is -1 for a row added here. */
	public static final class FilterRow {
		public final String list;
		public final int originalIndex;
		final @Nullable FilterSpec original;
		@Nullable String mode;
		@Nullable String pattern;
		boolean caseSensitive;
		boolean replacing;
		boolean deleted;
		@Nullable String error;
		@Nullable EditBox box;

		FilterRow(String list, int originalIndex, @Nullable FilterSpec original) {
			this.list = list;
			this.originalIndex = originalIndex;
			this.original = original;
			this.mode = original == null ? FilterSpec.LITERAL : original.mode();
			this.pattern = original == null ? "" : original.pattern();
			this.caseSensitive = original != null && original.caseSensitive();
		}

		public boolean isNew() { return originalIndex < 0; }

		public boolean modified() {
			return isNew() || replacing || original == null || !java.util.Objects.equals(original.mode(), mode)
					|| !java.util.Objects.equals(original.pattern(), pattern) || original.caseSensitive() != caseSensitive;
		}

		FilterSpec spec() { return new FilterSpec(mode, pattern, caseSensitive); }

		boolean literal() { return mode == null || FilterSpec.LITERAL.equals(mode); }

		/** Editable in a field: Literal with a stored value that fits, or being replaced. */
		public boolean editable() { return replacing || literal() && pattern != null && pattern.length() <= PatternValidator.MAX_LENGTH && !ConfigParser.MALFORMED_FILTER.equals(mode); }

		public @Nullable EditBox box() { return box; }
		public @Nullable String error() { return error; }
		public @Nullable String pattern() { return pattern; }
	}

	private final String tabId;
	private final List<FilterRow> rows = new ArrayList<>();
	private String name;
	private boolean enabled, hide, restore, matchAll;
	private @Nullable EditBox nameBox;

	public TabScreen(Screen parent, String tabId) {
		super(Component.translatable("cpextended.gui.tab", tabId), parent);
		this.tabId = tabId;
		TabConfig c = tabConfig();
		if (c != null) {
			name = c.name();
			enabled = c.enabled();
			hide = c.hideFromMainChat();
			restore = c.restoreHistory();
			matchAll = c.matchAll();
			for (int i = 0; i < c.include().size(); i++) rows.add(new FilterRow("include", i, c.include().get(i)));
			for (int i = 0; i < c.exclude().size(); i++) rows.add(new FilterRow("exclude", i, c.exclude().get(i)));
		}
	}

	/** Below the two rows of settings (name/enabled/all, hide/restore). */
	@Override
	protected int listTop() {
		return 32 + 2 * ROW + 2;
	}

	@Override
	protected int rowsPerPage() {
		return Math.max(2, (height - listTop() - 70) / ROW);
	}

	private @Nullable TabConfig tabConfig() {
		for (ExtendedConfig.Window w : ConfigManager.get().config().windows()) for (ExtendedConfig.Tab t : w.tabs()) if (tabId.equals(t.id())) return t.config();
		return null;
	}

	@Override
	protected void init() {
		if (tabConfig() == null) {
			setStatus("This tab no longer exists", true);
			addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
			return;
		}
		int y = 32;
		nameBox = new EditBox(font, width / 2 - 154, y, 150, 20, Component.translatable("cpextended.gui.name"));
		nameBox.setMaxLength(64);
		nameBox.setValue(name);
		nameBox.setResponder(v -> name = v);
		addRenderableWidget(nameBox);
		addRenderableWidget(toggle("cpextended.gui.enabled", enabled, width / 2 + 4, y, () -> enabled = !enabled));
		addRenderableWidget(toggle("cpextended.gui.match_all", matchAll, width / 2 + 81, y, () -> matchAll = !matchAll));
		addRenderableWidget(toggle("cpextended.gui.hide", hide, width / 2 - 154, y + ROW, () -> hide = !hide));
		addRenderableWidget(toggle("cpextended.gui.restore", restore, width / 2 + 4, y + ROW, () -> restore = !restore));

		List<FilterRow> visible = rows.stream().filter(r -> !r.deleted).toList();
		int per = Math.max(1, rowsPerPage() - 1);
		int top = listTop() + ROW;
		page = Math.min(page, Math.max(0, (visible.size() - 1) / per));
		for (int i = page * per; i < Math.min(visible.size(), (page + 1) * per); i++) {
			FilterRow r = visible.get(i);
			int ry = top + (i - page * per) * ROW;
			int x = width / 2 - 154;
			r.box = null;
			if (r.editable()) {
				EditBox box = new EditBox(font, x + 16, ry, 186, 20, Component.translatable("cpextended.gui.pattern"));
				box.setMaxLength(PatternValidator.MAX_LENGTH);
				box.setValue(r.pattern == null ? "" : r.pattern); // fits by construction: setValue truncates, so over-length values never get here
				box.setResponder(v -> { r.pattern = v; r.error = null; });
				r.box = addRenderableWidget(box);
			} else {
				Button action = addRenderableWidget(Button.builder(Component.translatable(r.literal() ? "cpextended.gui.replace" : "cpextended.gui.switch_literal"), b -> {
					if (r.literal() || ConfigParser.MALFORMED_FILTER.equals(r.mode)) {
						r.replacing = true; // explicit Replace: an empty field; the stored value is only overwritten on a valid save
						r.mode = FilterSpec.LITERAL;
						r.pattern = "";
					} else {
						r.mode = FilterSpec.LITERAL; // Q14.1(4): keep the pattern text exactly, revalidate
					}
					rebuildWidgets();
				}).bounds(x + 130, ry, 72, 20).build());
				action.active = true;
			}
			addRenderableWidget(Button.builder(Component.literal(r.caseSensitive ? "Aa" : "aa"), b -> { r.caseSensitive = !r.caseSensitive; rebuildWidgets(); })
					.bounds(x + 206, ry, 24, 20).build());
			addRenderableWidget(Button.builder(Component.literal("x"), b -> { r.deleted = true; rebuildWidgets(); }).bounds(x + 232, ry, 20, 20).build());
		}
		pager(visible.size());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.add_include"), b -> { rows.add(new FilterRow("include", -1, null)); rebuildWidgets(); })
				.bounds(width / 2 - 154, listTop(), 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.add_exclude"), b -> { rows.add(new FilterRow("exclude", -1, null)); rebuildWidgets(); })
				.bounds(width / 2 - 50, listTop(), 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("cpextended.gui.save"), b -> save()).bounds(width / 2 - 154, height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 4, height - 28, 150, 20).build());
	}

	private Button toggle(String key, boolean value, int x, int y, Runnable flip) {
		return Button.builder(Component.translatable(key, Component.translatable(value ? "cpextended.gui.on" : "cpextended.gui.off")), b -> { flip.run(); rebuildWidgets(); })
				.bounds(x, y, key.endsWith("hide") ? 150 : 73, 20).build();
	}

	/** Validates new and changed filters, then applies every change atomically. Returns whether it saved. */
	public boolean save() {
		TabConfig c = tabConfig();
		if (c == null) return false;
		boolean blocked = false;
		for (FilterRow r : rows) {
			r.error = null;
			if (r.deleted || !r.modified()) continue;
			String err = PatternValidator.validate(r.spec());
			if (err != null) {
				r.error = switch (err) {
					case "empty" -> "Pattern is empty";
					case "too_long" -> "Pattern is longer than 1,024 characters";
					case "regex_unsupported" -> "Regex mode isn't available in this version";
					default -> "Unsupported filter mode";
				};
				blocked = true;
			}
		}
		if (blocked) {
			setStatus("Not saved: fix the filters marked in red", true);
			rebuildWidgets();
			return false;
		}
		List<ConfigEdit> edits = new ArrayList<>();
		if (!name.equals(c.name())) edits.add(new ConfigEdit.RenameTab(tabId, name));
		if (enabled != c.enabled()) edits.add(new ConfigEdit.SetTabFlag(tabId, "enabled", enabled));
		if (hide != c.hideFromMainChat()) edits.add(new ConfigEdit.SetTabFlag(tabId, "hideFromMainChat", hide));
		if (restore != c.restoreHistory()) edits.add(new ConfigEdit.SetTabFlag(tabId, "restoreHistory", restore));
		if (matchAll != c.matchAll()) edits.add(new ConfigEdit.SetTabFlag(tabId, "matchAll", matchAll));
		for (FilterRow r : rows) if (!r.isNew() && !r.deleted && r.modified()) edits.add(new ConfigEdit.SetFilter(tabId, r.list, r.originalIndex, r.spec()));
		rows.stream().filter(r -> !r.isNew() && r.deleted).sorted(Comparator.comparingInt((FilterRow r) -> r.originalIndex).reversed())
				.forEach(r -> edits.add(new ConfigEdit.DeleteFilter(tabId, r.list, r.originalIndex)));
		for (FilterRow r : rows) if (r.isNew() && !r.deleted) edits.add(new ConfigEdit.AddFilter(tabId, r.list, r.spec()));
		if (edits.isEmpty()) {
			setStatus("Nothing changed", false);
			return true;
		}
		ConfigManager.EditOutcome o = ConfigManager.get().editAll(edits);
		if (!o.applied()) {
			setStatus(String.valueOf(o.edit().reason()), true);
			rebuildWidgets();
			return false;
		}
		if (parent instanceof BaseScreen b) b.setStatus(o.save() == ConfigManager.SaveOutcome.SAVED ? "Saved" : "Changed in game, not saved: " + o.save(), o.save() != ConfigManager.SaveOutcome.SAVED);
		minecraft.setScreen(parent);
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		List<FilterRow> visible = rows.stream().filter(r -> !r.deleted).toList();
		int per = Math.max(1, rowsPerPage() - 1);
		int top = listTop() + ROW;
		for (int i = page * per; i < Math.min(visible.size(), (page + 1) * per); i++) {
			FilterRow r = visible.get(i);
			int ry = top + (i - page * per) * ROW;
			int x = width / 2 - 154;
			g.drawString(font, r.list.equals("include") ? "+" : "-", x + 4, ry + 6, r.list.equals("include") ? 0xFF80FF80 : 0xFFFF8080, true);
			if (!r.editable()) {
				String text;
				if (ConfigParser.MALFORMED_FILTER.equals(r.mode)) text = "Malformed entry (kept as stored)";
				else if (!r.literal()) text = (FilterSpec.REGEX.equals(r.mode) ? "Regex (unsupported)" : "Unknown mode \"" + r.mode + "\"") + ": " + preview(r.pattern);
				else if (r.pattern == null) text = "No pattern (kept as stored)";
				else text = "Too long (" + r.pattern.length() + " characters), shown read-only";
				g.drawString(font, font.plainSubstrByWidth(text, 112), x + 16, ry + 6, 0xFFFFAA55, true);
			}
			if (r.error != null) g.drawString(font, r.error, x + 256, ry + 6, 0xFFFF6060, true);
		}
	}

	private static String preview(@Nullable String p) {
		if (p == null) return "";
		return p.length() > 24 ? p.substring(0, 24) + "..." : p;
	}

	public List<FilterRow> rows() { return rows; }
	public @Nullable EditBox nameBox() { return nameBox; }

	/** Automation hooks matching the row buttons. */
	public void replace(FilterRow r) {
		r.replacing = true;
		r.mode = FilterSpec.LITERAL;
		r.pattern = "";
		rebuildWidgets();
	}

	public void switchToLiteral(FilterRow r) {
		r.mode = FilterSpec.LITERAL;
		rebuildWidgets();
	}

	public void toggleCase(FilterRow r) {
		r.caseSensitive = !r.caseSensitive;
		rebuildWidgets();
	}

	public void delete(FilterRow r) {
		r.deleted = true;
		rebuildWidgets();
	}

	public FilterRow addFilter(String list) {
		FilterRow r = new FilterRow(list, -1, null);
		rows.add(r);
		rebuildWidgets();
		return r;
	}
}
