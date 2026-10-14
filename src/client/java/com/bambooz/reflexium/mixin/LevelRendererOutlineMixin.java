package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.SharpBlockOutlineRenderer;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineMixin {
	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void reflexium$captureSharpOutline(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, LevelRenderState levelRenderState, CallbackInfo ci) {
		if (UpscalingRenderer.get().isUpscalingThisFrame()) {
			SharpBlockOutlineRenderer.get().capture(levelRenderState.blockOutlineRenderState, levelRenderState.cameraRenderState);
			ci.cancel();
		}
	}
}
