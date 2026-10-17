package io.github.itznotneptune.cpxspike.test.mixin;

import io.github.itznotneptune.cpxspike.test.Mode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies the V-59 conflict mixins only in modes s1conflict_bool / s1conflict_int. */
public final class ConflictPlugin implements IMixinConfigPlugin {
	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String mode = Mode.read();
		if (mixinClassName.endsWith("ConflictBooleanMixin")) return mode.equals("s1conflict_bool");
		if (mixinClassName.endsWith("ConflictIntMixin")) return mode.equals("s1conflict_int");
		return true;
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
