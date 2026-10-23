package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** GUI preparation/upload happens once. Replay its draw list before GuiRenderer clears it. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@Shadow @Final private List<?> draws;
	@Shadow private int firstDrawIndexAfterBlur;

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw()V"))
	private void reflexium$sharpGeneratedGui(GuiRenderer renderer, Operation<Void> original) {
		original.call(renderer);
		// draw() runs the menu blur post chain, whose ring buffer cannot be reused within one submit.
		// Behind a blurred screen the world is static, so present only the rendered frame.
		if (draws.size() > firstDrawIndexAfterBlur) {
			FrameGenerationRenderer.get().skipGeneratedFrames();
			return;
		}
		FrameGenerationRenderer.get().drawGuiCopies(Minecraft.getInstance().gameRenderer, () -> original.call(renderer));
	}
}
