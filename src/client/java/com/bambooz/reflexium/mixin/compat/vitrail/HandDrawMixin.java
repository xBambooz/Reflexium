package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Jitter the hand's published volume before Vitrail builds its bobbed raster projection from it. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.HandDraw", remap = false)
public abstract class HandDrawMixin {
	@WrapMethod(method = "submit")
	private void reflexium$handMask(@Coerce Object half, Operation<Void> original) {
		UpscalingRenderer renderer = UpscalingRenderer.get();
		boolean capture = renderer.isUpscalingThisFrame() && VitrailCompat.isDrawingPack();
		if (capture) {
			renderer.capturePackHandBefore();
		}
		boolean complete = false;
		try {
			original.call(half);
			complete = true;
		} finally {
			if (capture && complete) {
				renderer.capturePackHandAfter();
			}
		}
	}

	/** The matrix Vitrail actually binds for the hand (modelview is identity), kept for hand motion vectors. */
	@ModifyArg(method = "submit", index = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f reflexium$noteDrawnHandProjection(Matrix4f drawn) {
		UpscalingRenderer.get().noteHandProjectionDrawn(drawn);
		return drawn;
	}

	@ModifyExpressionValue(method = "submit", at = @At(value = "INVOKE",
			target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0))
	private Matrix4f reflexium$jitterHand(Matrix4f volume) {
		if (UpscalingRenderer.get().isUpscalingThisFrame()) {
			volume.set(UpscalingRenderer.get().jitterShaderProjection(volume));
		}
		return volume;
	}
}
