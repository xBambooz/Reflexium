package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.IrisCompat;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris draws the first-person hand twice inside the level (solid before translucents, translucent after) with
 * its own projection buffer. Jitter that projection like the level's, keep it for hand motion vectors, and
 * accumulate both halves into the hand coverage mask.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class HandRendererMixin {
	@ModifyArg(method = "setupGlState", index = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f reflexium$jitterHand(Matrix4f volume) {
		UpscalingRenderer renderer = UpscalingRenderer.get();
		if (renderer.isUpscalingThisFrame()) {
			volume.set(renderer.jitterShaderProjection(volume));
			renderer.noteHandProjectionDrawn(volume);
		}
		return volume;
	}

	@Inject(method = {"renderSolid", "renderTranslucent"}, at = @At("HEAD"))
	private void reflexium$handBefore(CallbackInfo ci) {
		if (UpscalingRenderer.get().isUpscalingThisFrame() && IrisCompat.isDrawingPack()) UpscalingRenderer.get().capturePackHandBefore();
	}

	@Inject(method = {"renderSolid", "renderTranslucent"}, at = @At("RETURN"))
	private void reflexium$handAfter(CallbackInfo ci) {
		if (UpscalingRenderer.get().isUpscalingThisFrame() && IrisCompat.isDrawingPack()) UpscalingRenderer.get().capturePackHandAfter();
	}
}
