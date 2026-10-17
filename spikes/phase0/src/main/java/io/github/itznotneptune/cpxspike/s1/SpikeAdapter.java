package io.github.itznotneptune.cpxspike.s1;

import io.github.itznotneptune.cpxspike.SpikeLog;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import obro1961.chatpatches.Boundary;
import obro1961.chatpatches.ChatLog;
import obro1961.chatpatches.ChatPatches;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Set;

/**
 * Spike version of the compatibility adapter (architecture §8.2). Every Chat Patches reference lives here.
 * Fault injection (V-36 partial) is driven by {@link #FAULTS}.
 */
public final class SpikeAdapter {
	public enum Member { RESTORED_TAG, VANILLA_CLEARING, CHAT_MAX, BOUNDARY, CONDENSE, SEARCH_VISIBILITY, SEARCH_QUERY }

	public static final Set<Member> FAULTS = EnumSet.noneOf(Member.class);
	public static volatile boolean degraded = false;
	public static volatile boolean searchIntegrationOff = false;

	private static Field showSearchBar;
	private static Field searchField;
	public static String reflectionStatus = "unresolved";

	private SpikeAdapter() {}

	static void fault(Member m) {
		if (FAULTS.contains(m)) throw new IllegalStateException("injected fault: " + m);
	}

	public static GuiMessageTag restoredTag() { fault(Member.RESTORED_TAG); return ChatLog.RESTORED_INDICATOR; }
	public static boolean vanillaClearing() { fault(Member.VANILLA_CLEARING); return ChatPatches.config.vanillaClearing; }
	public static int chatMaxMessages() {
		fault(Member.CHAT_MAX);
		int v = ChatPatches.config.chatMaxMessages;
		if (v <= 0) throw new IllegalStateException("chatMaxMessages <= 0");
		return v;
	}
	public static boolean isBoundary(Component c) { fault(Member.BOUNDARY); return Boundary.isBoundaryLine(c); }
	public static boolean isLoading() { return ChatLog.isLoading(); }
	public static boolean isRestoring() { return ChatLog.isRestoring(); }

	/** Chat Patches' duplicate-search window (ChatUtil.tryCondenseDupes L656-665), with the P3 conservative superset. */
	public static int condenseWindow(int size, int linesPerPage) {
		int k;
		try {
			fault(Member.CONDENSE);
			var c = ChatPatches.config;
			if (!c.counter) return 0;
			int raw = c.compactChat ? Math.abs(c.compactDistance) : 1;
			k = switch (raw) {
				case 0 -> linesPerPage;
				case 1 -> 1;
				default -> Math.min(c.compactDistance, size);
			};
			if (k <= 0) k = Math.max(1, Math.max(linesPerPage, Math.abs(c.compactDistance))); // unusual value -> superset
		} catch (RuntimeException e) {
			SpikeLog.ev("adapter_fallback", "member", "CONDENSE", "error", e.getMessage());
			k = Math.max(1, linesPerPage);
		}
		return Math.min(k, size);
	}

	/** P12 exact-name reflection self-check; safe with no ChatScreen instance. */
	public static void selfCheck() {
		try {
			Field f1 = unambiguous("showSearchBar");
			Field f2 = unambiguous("searchField");
			boolean ok1 = Modifier.isStatic(f1.getModifiers()) && f1.getType() == boolean.class;
			boolean ok2 = !Modifier.isStatic(f2.getModifiers()) && EditBox.class.isAssignableFrom(f2.getType());
			if (!ok1 || !ok2) throw new IllegalStateException("type/static mismatch ok1=" + ok1 + " ok2=" + ok2);
			f1.setAccessible(true);
			f2.setAccessible(true);
			showSearchBar = f1;
			searchField = f2;
			reflectionStatus = "ok";
		} catch (ReflectiveOperationException | RuntimeException e) {
			reflectionStatus = "failed: " + e;
			searchIntegrationOff = true;
		}
		boolean classS = true;
		String err = null;
		try { restoredTag(); vanillaClearing(); chatMaxMessages(); isBoundary(Component.empty()); }
		catch (RuntimeException e) { classS = false; err = e.toString(); degraded = true; }
		SpikeLog.ev("adapter_selfcheck", "reflection", reflectionStatus, "classS_ok", classS, "classS_error", err,
				"degraded", degraded, "searchIntegrationOff", searchIntegrationOff);
	}

	/**
	 * Exact-name lookup that refuses ambiguity (finding V-59.4): if another mod adds a same-named field, Mixin renames
	 * Chat Patches' own @Unique field (e.g. "<hash>$chatpatches$showSearchBar$0") or the JVM keeps two same-named
	 * fields of different types. In either case we must not bind; the Class Q fallback applies.
	 */
	static Field unambiguous(String name) throws NoSuchFieldException {
		Field exact = null;
		int exactCount = 0;
		boolean renamedVariant = false;
		for (Field f : ChatScreen.class.getDeclaredFields()) {
			if (f.getName().equals(name)) { exact = f; exactCount++; }
			else if (f.getName().contains("$chatpatches$" + name + "$")) renamedVariant = true;
		}
		if (exactCount == 0) throw new NoSuchFieldException(name);
		if (exactCount > 1 || renamedVariant) throw new IllegalStateException("ambiguous field '" + name + "': exactCount=" + exactCount + " renamedChatPatchesVariant=" + renamedVariant);
		return exact;
	}

	/** null = unknown (fallback applies). */
	public static Boolean searchBarShown() {
		try {
			fault(Member.SEARCH_VISIBILITY);
			if (showSearchBar == null) return null;
			return ChatPatches.config.search && showSearchBar.getBoolean(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Optional.empty-like: returns null if not available (no screen / before init), throws on genuine failure. */
	public static EditBox searchFieldOf(ChatScreen s) {
		fault(Member.SEARCH_QUERY);
		if (searchField == null) throw new IllegalStateException("searchField unresolved");
		try {
			return (EditBox) searchField.get(s);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}
}
