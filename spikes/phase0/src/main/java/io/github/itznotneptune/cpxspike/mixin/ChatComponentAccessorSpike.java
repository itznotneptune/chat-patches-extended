package io.github.itznotneptune.cpxspike.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** P1 accessors incl. the Q36 @Mutable setter (reference swap only). */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessorSpike {
	@Accessor("allMessages")
	List<GuiMessage> cpx$getAllMessages();

	@Mutable
	@Accessor("allMessages")
	void cpx$setAllMessages(List<GuiMessage> list);

	@Accessor("trimmedMessages")
	List<GuiMessage.Line> cpx$getTrimmedMessages();
}
