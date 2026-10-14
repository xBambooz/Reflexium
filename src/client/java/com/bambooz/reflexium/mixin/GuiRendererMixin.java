package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.FrameGenerationRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** GUI preparation/upload happens once. Replay its draw list before GuiRenderer clears it. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw()V"))
	private void reflexium$sharpGeneratedGui(GuiRenderer renderer, Operation<Void> original) {
		original.call(renderer);
		FrameGenerationRenderer.get().drawGuiCopies(Minecraft.getInstance().gameRenderer, () -> original.call(renderer));
	}
}
