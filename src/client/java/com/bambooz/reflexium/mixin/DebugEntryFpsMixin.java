package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.FpsText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(DebugEntryFps.class)
public abstract class DebugEntryFpsMixin {
	@ModifyArg(method = "display", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addPriorityLine(Ljava/lang/String;)V"))
	private String reflexium$renderedFps(String line) {
		return FpsText.withRendered(line, Minecraft.getInstance().getFps());
	}
}
