// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.compat;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * P12 exact-name handles for Chat Patches' {@code ChatScreen} search state, with the Phase 0 F-1 safeguard: a field is
 * used only if exactly one field has the exact name <em>and</em> Mixin hasn't renamed Chat Patches' own copy
 * ({@code <hash>$chatpatches$<name>$<n>}), which happens when another mod adds a same-named field. Anything else is a
 * Class Q failure; types and positions are never guessed.
 */
public final class SearchFieldHandles {
	public static final String SHOW_SEARCH_BAR = "showSearchBar";
	public static final String SEARCH_FIELD = "searchField";

	/** Outcome of resolving one handle; {@code field} is null on failure, with the reason in {@code error}. */
	public record Resolution(Field field, String error) {
		public boolean ok() { return field != null; }
	}

	private SearchFieldHandles() {}

	public static Resolution resolve(Class<?> owner, String name, boolean expectStatic, Class<?> expectedType) {
		Field exact = null;
		int exactCount = 0;
		boolean renamedVariant = false;
		try {
			for (Field f : owner.getDeclaredFields()) {
				if (f.getName().equals(name)) {
					exact = f;
					exactCount++;
				} else if (f.getName().contains("$chatpatches$" + name + "$")) {
					renamedVariant = true;
				}
			}
		} catch (LinkageError | SecurityException e) {
			return new Resolution(null, "declared fields unreadable: " + e);
		}
		if (exactCount == 0) return new Resolution(null, "missing");
		if (exactCount > 1 || renamedVariant) {
			return new Resolution(null, "ambiguous (exact matches=" + exactCount + ", renamed Chat Patches variant=" + renamedVariant + ")");
		}
		if (Modifier.isStatic(exact.getModifiers()) != expectStatic) return new Resolution(null, expectStatic ? "not static" : "unexpectedly static");
		boolean typeOk = expectedType.isPrimitive() ? exact.getType() == expectedType : expectedType.isAssignableFrom(exact.getType());
		if (!typeOk) return new Resolution(null, "wrong type " + exact.getType().getName());
		try {
			exact.setAccessible(true);
		} catch (RuntimeException e) {
			return new Resolution(null, "inaccessible: " + e);
		}
		return new Resolution(exact, null);
	}

	static Resolution resolveShowSearchBar() {
		return resolve(ChatScreen.class, SHOW_SEARCH_BAR, true, boolean.class);
	}

	static Resolution resolveSearchField() {
		return resolve(ChatScreen.class, SEARCH_FIELD, false, EditBox.class);
	}
}
