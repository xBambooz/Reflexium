package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Retain Vitrail's bob split and let its normal history track the actual jittered volume. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.FrameState", remap = false)
public abstract class FrameStateMixin {
	@ModifyArg(method = "advanceView", at = @At(value = "INVOKE",
			target = "Ldev/vitrail/render/ViewMatrices;advance(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;FI)V"), index = 2)
	private Matrix4fc reflexium$jitterPublishedProjection(Matrix4fc projection,
			@Local(argsOnly = true) CameraRenderState camera) {
		// The fallback is CapturedProjection, which already includes our offset.
		return projection == camera.projectionMatrix && UpscalingRenderer.get().isUpscalingThisFrame()
				? UpscalingRenderer.get().jitterShaderProjection(projection) : projection;
	}
}
