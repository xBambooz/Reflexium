package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

/** Copy outside a render pass. Classic transparency normally shares its pass with opaque geometry. */
@Mixin(LevelRenderer.class)
public abstract class ReactiveCaptureMixin {
	@WrapMethod(method = "executeClassicTransparency")
	private void reflexium$opaqueSnapshot(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features,
			RenderPass opaquePass, Operation<Void> original) {
		UpscalingRenderer upscaler = UpscalingRenderer.get();
		if (!upscaler.supportsReactiveMask()) {
			original.call(sections, features, opaquePass);
			return;
		}
		// FrontendRenderPass.close is idempotent; the caller's eventual close is harmless.
		opaquePass.close();
		upscaler.captureOpaqueColor();
		RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		try (RenderPass transparentPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "Reflexium translucent scene", target.getColorTextureView(), Optional.empty(),
				target.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(transparentPass);
			original.call(sections, features, transparentPass);
		}
	}

	@Inject(method = "executeOit", at = @At("HEAD"))
	private void reflexium$opaqueOitSnapshot(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features, CallbackInfo ci) {
		UpscalingRenderer.get().captureOpaqueColor();
	}

	@Inject(method = "executeAlwaysOnTop", at = @At("HEAD"))
	private void reflexium$packDepthBeforeGizmos(FeatureRenderDispatcher.PreparedFrame features, RenderTarget target,
			boolean consistentDepthRequired, CallbackInfo ci) {
		if (ShaderPacks.isDrawingPack()) {
			UpscalingRenderer.get().captureWorldDepth(Minecraft.getInstance().gameRenderer);
		}
	}
}
