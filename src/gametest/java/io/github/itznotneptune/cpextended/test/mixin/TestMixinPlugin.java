// SPDX-License-Identifier: LGPL-3.0-only
package io.github.itznotneptune.cpextended.test.mixin;

import io.github.itznotneptune.cpextended.test.Mode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Test mixins only where a scenario needs them; the structural modes run with none, so the chat code is untouched. */
public final class TestMixinPlugin implements IMixinConfigPlugin {
	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String mode = Mode.read();
		if (mixinClassName.endsWith("ConflictBooleanMixin")) return mode.equals("conflict_bool");
		if (mixinClassName.endsWith("ConflictIntMixin")) return mode.equals("conflict_int");
		if (mixinClassName.endsWith("ConflictErrorMixin")) return mode.equals("conflict_error");
		return !mode.startsWith("structural") && !mode.startsWith("prod"); // production validation: no test mixins
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
