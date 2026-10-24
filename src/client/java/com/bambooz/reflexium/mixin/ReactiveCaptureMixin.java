package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps world depth before pack gizmos draw to it. */
@Mixin(LevelRenderer.class)
public abstract class ReactiveCaptureMixin {
	@Inject(method = "executeAlwaysOnTop", at = @At("HEAD"))
	private void reflexium$packDepthBeforeGizmos(FeatureRenderDispatcher.PreparedFrame features, RenderTarget target,
			boolean consistentDepthRequired, CallbackInfo ci) {
		if (ShaderPacks.isDrawingPack()) {
			UpscalingRenderer.get().captureWorldDepth(Minecraft.getInstance().gameRenderer);
		}
	}
}
