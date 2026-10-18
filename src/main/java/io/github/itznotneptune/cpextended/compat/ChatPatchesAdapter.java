// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.compat;

import io.github.itznotneptune.cpextended.core.Diagnostics;
import io.github.itznotneptune.cpextended.core.Warnings;
import io.github.itznotneptune.cpextended.dev.DevHooks;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The only place Extended reads Chat Patches (F4, architecture §8.2). Every operation has a P9 failure class:
 * <ul>
 * <li><b>S</b>: a genuine failure enters session-wide degraded mode (one-way until relaunch);</li>
 * <li><b>L</b>: the approved local fallback applies, no degraded mode;</li>
 * <li><b>Q</b>: search integration is switched off for the session, nothing else changes.</li>
 * </ul>
 * Valid unusual values (the "never degrade" column) are passed through.
 */
public final class ChatPatchesAdapter {
	public enum FailureClass { S, L, Q }

	public enum Op {
		RESTORED_TAG(FailureClass.S), VANILLA_CLEARING(FailureClass.S), CHAT_MAX_MESSAGES(FailureClass.S), BOUNDARY(FailureClass.S),
		CONDENSE_WINDOW(FailureClass.L), SAFE_POINT(FailureClass.L), CHAT_PATCHES_UI(FailureClass.L), MAIN_CHAT_LINE(FailureClass.L),
		SEARCH_VISIBILITY(FailureClass.Q), SEARCH_QUERY(FailureClass.Q);

		public final FailureClass failureClass;

		Op(FailureClass c) { this.failureClass = c; }
	}

	/** Result of a search-query read: not available (no initialized screen) is not a failure. */
	public record SearchRead(Status status, @Nullable String query) {
		public enum Status { AVAILABLE, NOT_AVAILABLE, FAILED }
	}

	private static final ChatPatchesAdapter INSTANCE = new ChatPatchesAdapter();

	private volatile ChatPatchesBackend backend = new RealBackend();
	private volatile boolean selfChecked;
	private volatile boolean degraded;
	private volatile boolean searchIntegrationOff;
	private volatile Field showSearchBar;
	private volatile Field searchField;
	private final Set<Op> failedOps = new LinkedHashSet<>();

	private ChatPatchesAdapter() {}

	public static ChatPatchesAdapter get() {
		return INSTANCE;
	}

	public boolean isDegraded() { return degraded; }
	public boolean isSearchIntegrationOff() { return searchIntegrationOff; }
	public boolean isSelfChecked() { return selfChecked; }
	public synchronized Set<Op> failedOps() { return Set.copyOf(failedOps); }

	// ---------------------------------------------------------------- Class S

	/** Chat Patches' restored-message tag, or null if it can't be read (degraded). */
	public @Nullable GuiMessageTag restoredTag() {
		try {
			GuiMessageTag t = backend.restoredIndicator();
			if (t == null) { failS(Op.RESTORED_TAG, "null", null); return null; }
			return t;
		} catch (RuntimeException | LinkageError e) {
			failS(Op.RESTORED_TAG, "read error", e);
			return null;
		}
	}

	/** {@code config.vanillaClearing}, or null if it can't be read (degraded). */
	public @Nullable Boolean vanillaClearing() {
		try {
			return backend.vanillaClearing();
		} catch (RuntimeException | LinkageError e) {
			failS(Op.VANILLA_CLEARING, "read error", e);
			return null;
		}
	}

	/** {@code config.chatMaxMessages} (any value of at least 1), or empty if unreadable or not positive (degraded). */
	public OptionalInt chatMaxMessages() {
		try {
			int v = backend.chatMaxMessages();
			if (v <= 0) { failS(Op.CHAT_MAX_MESSAGES, "value <= 0", null); return OptionalInt.empty(); }
			return OptionalInt.of(v);
		} catch (RuntimeException | LinkageError e) {
			failS(Op.CHAT_MAX_MESSAGES, "read error", e);
			return OptionalInt.empty();
		}
	}

	/** Whether {@code c} is Chat Patches' boundary line (Q10), or null if the check failed (degraded). */
	public @Nullable Boolean isBoundaryLine(Component c) {
		try {
			return backend.isBoundaryLine(c);
		} catch (RuntimeException | LinkageError e) {
			failS(Op.BOUNDARY, "check error", e);
			return null;
		}
	}

	// ---------------------------------------------------------------- Class L

	/**
	 * Size of Chat Patches' duplicate-search window (ChatUtil.tryCondenseDupes), bounded by {@code size}. Unusual or
	 * unreadable settings give the P3 conservative superset {@code max(1, linesPerPage, |compactDistance|)}.
	 */
	public int condenseWindow(int size, int linesPerPage) {
		int k;
		try {
			ChatPatchesBackend b = backend;
			if (!b.counter()) return 0;
			int distance = b.compactDistance();
			int raw = b.compactChat() ? Math.abs(distance) : 1;
			k = switch (raw) {
				case 0 -> linesPerPage;
				case 1 -> 1;
				default -> Math.min(distance, size);
			};
			if (k <= 0) k = Math.max(1, Math.max(linesPerPage, Math.abs(distance)));
		} catch (RuntimeException | LinkageError e) {
			failL(Op.CONDENSE_WINDOW, e);
			k = Math.max(1, linesPerPage);
		}
		return Math.max(0, Math.min(k, size));
	}

	/** P5 safe point: Chat Patches is neither loading nor restoring. Unreadable means "not safe now" (refuse). */
	public boolean isSafePoint() {
		try {
			ChatPatchesBackend b = backend;
			return !b.isLoading() && !b.isRestoring();
		} catch (RuntimeException | LinkageError e) {
			failL(Op.SAFE_POINT, e);
			return false;
		}
	}

	/**
	 * Whether Chat Patches' context menu is open on {@code screen}. Unreadable means "open" (conservative, P11): every
	 * click then passes to Chat Patches.
	 */
	public boolean isContextMenuOpen(ChatScreen screen) {
		try {
			return backend.contextMenuOpen(screen);
		} catch (RuntimeException | LinkageError e) {
			failL(Op.CHAT_PATCHES_UI, e);
			return true;
		}
	}

	/** Whether the point is over Chat Patches' context menu or settings panel. Unreadable means "yes" (Class L). */
	public boolean isOverChatPatchesUi(ChatScreen screen, double x, double y) {
		try {
			ChatPatchesBackend b = backend;
			return b.overContextMenu(screen, x, y) || b.overSettingsMenu(screen, x, y);
		} catch (RuntimeException | LinkageError e) {
			failL(Op.CHAT_PATCHES_UI, e);
			return true;
		}
	}

	/**
	 * Whether a visible main-chat message line is under the point (Q18). Unreadable means the whole main-chat area is
	 * protected (Class L): the caller passes {@code inMainChatArea} for that fallback.
	 */
	public boolean isOverMainChatLine(double x, double y, boolean inMainChatArea) {
		try {
			return backend.mainChatLineAt(x, y) >= 0;
		} catch (RuntimeException | LinkageError e) {
			failL(Op.MAIN_CHAT_LINE, e);
			return inMainChatArea;
		}
	}

	/** Chat Patches' search button (fixed geometry in 8.0.0), when search is enabled; null otherwise. */
	public int @Nullable [] searchButtonRect(int screenHeight) {
		try {
			return backend.searchEnabled() ? new int[]{2, screenHeight - 35, 16, 16} : null;
		} catch (RuntimeException | LinkageError e) {
			failL(Op.CHAT_PATCHES_UI, e);
			return new int[]{2, screenHeight - 35, 16, 16};
		}
	}

	/**
	 * The area Chat Patches' search field occupies when it may be shown: its real bounds when readable, otherwise the
	 * Q29b conservative reservation while {@code config.search} is on; null when the bar is known to be hidden.
	 */
	public int @Nullable [] searchFieldRect(ChatScreen screen) {
		Boolean shown = isSearchBarShown();
		if (Boolean.FALSE.equals(shown)) return null;
		int[] fallback = {22, screen.height - 31, (int) (screen.width * 0.25), 12};
		Field f = searchField;
		if (f == null || shown == null) return fallback;
		try {
			EditBox box = (EditBox) f.get(screen);
			return box == null ? fallback : new int[]{box.getX(), box.getY(), box.getWidth(), box.getHeight()};
		} catch (ReflectiveOperationException | RuntimeException e) {
			failQ(Op.SEARCH_QUERY, "read error", e);
			return fallback;
		}
	}

	/** The search field widget itself (to tell it apart from the vanilla input), or null. */
	public @Nullable EditBox searchFieldWidget(ChatScreen screen) {
		Field f = searchField;
		if (f == null) return null;
		try {
			return (EditBox) f.get(screen);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- Class Q (P12)

	/** Whether Chat Patches' search bar is shown; null when unknown (the Q29b reservation then applies). */
	public @Nullable Boolean isSearchBarShown() {
		if (searchIntegrationOff && showSearchBar == null) return null;
		try {
			if (!backend.searchEnabled()) return false;
			Field f = showSearchBar;
			if (f == null) return null;
			return f.getBoolean(null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			failQ(Op.SEARCH_VISIBILITY, "read error", e);
			return null;
		}
	}

	/** Chat Patches' search settings (S24); null when they can't be read (search integration then turns off, Class Q). */
	public record SearchSettings(boolean enabled, boolean caseSensitive, boolean regex, boolean emptyShowsFullChat) {}

	public @Nullable SearchSettings searchSettings() {
		if (searchIntegrationOff) return null;
		try {
			ChatPatchesBackend b = backend;
			return new SearchSettings(b.searchEnabled(), b.searchCaseSensitive(), b.searchRegex(), b.searchEmptyShowsFullChat());
		} catch (RuntimeException | LinkageError e) {
			failQ(Op.SEARCH_QUERY, "settings read error", e);
			return null;
		}
	}

	/**
	 * Chat Patches' own regex-search result list (Q31: identity membership, never a regex in Extended); null when it
	 * can't be read (regex-mode tab results unavailable; search integration off, Class Q).
	 */
	public java.util.@Nullable List<net.minecraft.client.GuiMessage> searchResults(ChatScreen screen) {
		if (searchIntegrationOff) return null;
		try {
			java.util.List<net.minecraft.client.GuiMessage> r = backend.searchResults(screen);
			if (r == null) { failQ(Op.SEARCH_QUERY, "results null", null); return null; }
			return r;
		} catch (RuntimeException | LinkageError e) {
			failQ(Op.SEARCH_QUERY, "results read error", e);
			return null;
		}
	}

	/** Current Chat Patches search query of {@code screen}; NOT_AVAILABLE before {@code init} is not a failure. */
	public SearchRead searchQuery(@Nullable ChatScreen screen) {
		Field f = searchField;
		if (searchIntegrationOff || f == null) return new SearchRead(SearchRead.Status.FAILED, null);
		if (screen == null) return new SearchRead(SearchRead.Status.NOT_AVAILABLE, null);
		try {
			EditBox box = (EditBox) f.get(screen);
			if (box == null) return new SearchRead(SearchRead.Status.NOT_AVAILABLE, null);
			return new SearchRead(SearchRead.Status.AVAILABLE, box.getValue());
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			failQ(Op.SEARCH_QUERY, "read error", e);
			return new SearchRead(SearchRead.Status.FAILED, null);
		}
	}

	// ---------------------------------------------------------------- self-check

	/** Exercises every operation once and validates it (architecture §8.2). Safe with no ChatScreen open. */
	public synchronized void selfCheck() {
		restoredTag();
		vanillaClearing();
		chatMaxMessages();
		isBoundaryLine(Component.empty());
		condenseWindow(10, 10);
		isSafePoint();

		SearchFieldHandles.Resolution vis = SearchFieldHandles.resolveShowSearchBar();
		SearchFieldHandles.Resolution query = SearchFieldHandles.resolveSearchField();
		showSearchBar = vis.field();
		searchField = query.field();
		if (!vis.ok()) failQ(Op.SEARCH_VISIBILITY, "showSearchBar " + vis.error(), null);
		if (!query.ok()) failQ(Op.SEARCH_QUERY, "searchField " + query.error(), null);
		if (vis.ok()) isSearchBarShown();
		selfChecked = true;
		Diagnostics.event("adapter_selfcheck", "degraded", degraded, "searchIntegrationOff", searchIntegrationOff,
				"failedOps", failedOps.toString(), "showSearchBar", vis.ok() ? "ok" : vis.error(), "searchField", query.ok() ? "ok" : query.error());
	}

	// ---------------------------------------------------------------- failure handling

	private void failS(Op op, String kind, @Nullable Throwable e) {
		boolean entering;
		synchronized (this) {
			failedOps.add(op);
			entering = !degraded;
			degraded = true;
		}
		String key = "adapter:" + op + ":" + kind + ":" + (e == null ? "-" : e.getClass().getName());
		// The failure that causes degraded mode carries the degraded-mode warning, so the player sees one toast for it.
		Warnings.report(key, entering ? Warnings.Toast.DEGRADED : Warnings.Toast.NONE,
				"Chat Patches adapter: " + op + " failed (" + kind + ", class S)" + (entering ? "; entering degraded mode until relaunch" : ""), e);
		if (entering) Warnings.report("degraded", Warnings.Toast.NONE, "Degraded mode entered (no new hiding; routing continues)", null);
		Diagnostics.event("adapter_failure", "op", op.name(), "class", "S", "kind", kind, "exception", e == null ? null : e.getClass().getName());
	}

	private void failL(Op op, Throwable e) {
		synchronized (this) { failedOps.add(op); }
		Warnings.report("adapter:" + op + ":read error:" + e.getClass().getName(), Warnings.Toast.NONE,
				"Chat Patches adapter: " + op + " failed (class L); its local fallback applies", e);
		Diagnostics.event("adapter_failure", "op", op.name(), "class", "L", "exception", e.getClass().getName());
	}

	private void failQ(Op op, String kind, @Nullable Throwable e) {
		synchronized (this) {
			failedOps.add(op);
			searchIntegrationOff = true;
		}
		Warnings.report("adapter:" + op + ":" + kind + ":" + (e == null ? "-" : e.getClass().getName()), Warnings.Toast.SEARCH_UNAVAILABLE,
				"Chat Patches adapter: " + op + " failed (" + kind + ", class Q); search integration off for this session", e);
		Diagnostics.event("adapter_failure", "op", op.name(), "class", "Q", "kind", kind, "exception", e == null ? null : e.getClass().getName());
	}

	// ---------------------------------------------------------------- development only

	/** Development builds only: substitute the Chat Patches backend (fault injection, V-36) and reset session state. */
	public synchronized void devReplaceBackend(@Nullable ChatPatchesBackend replacement) {
		DevHooks.requireDev();
		backend = replacement == null ? new RealBackend() : replacement;
		degraded = false;
		searchIntegrationOff = false;
		failedOps.clear();
		selfChecked = false;
	}

	/** Development builds only: the real backend, for wrapping in a fault-injecting test double. */
	public ChatPatchesBackend devRealBackend() {
		DevHooks.requireDev();
		return new RealBackend();
	}
}
