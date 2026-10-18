// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.dev;

import io.github.itznotneptune.cpextended.filter.Matcher;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

/**
 * Development-environment hooks for verification. Each is inert unless Fabric Loader reports a development
 * environment, so a released jar behaves as if this class didn't exist. Loaded by the mixin plugin, so it must not
 * reference Minecraft or Chat Patches classes in its static state.
 */
public final class DevHooks {
	private static volatile @Nullable BiFunction<String, Matcher, Matcher> matcherDecorator;

	private DevHooks() {}

	public static boolean isDev() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	/** {@code -Dcpextended.dev.disable=true}: no mixins, no entrypoint work. The structural check's "without Extended" proxy. */
	public static boolean disabled() {
		return isDev() && Boolean.getBoolean("cpextended.dev.disable");
	}

	public static void requireDev() {
		if (!isDev()) throw new IllegalStateException("development-only hook");
	}

	/** Wraps every compiled Literal matcher of a tab (V-25b test double); null removes the wrapper. */
	public static void setMatcherDecorator(@Nullable BiFunction<String, Matcher, Matcher> decorator) {
		requireDev();
		matcherDecorator = decorator;
	}

	public static Matcher decorate(String tabId, Matcher m) {
		BiFunction<String, Matcher, Matcher> d = matcherDecorator;
		return d == null ? m : d.apply(tabId, m);
	}
}
