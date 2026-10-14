package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The bob validation must compare the jittered camera with the jittered drawn projection. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.CameraBob", remap = false)
public abstract class CameraBobMixin {
	@ModifyVariable(method = "agrees", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static Matrix4fc reflexium$jitterCamera(Matrix4fc camera) {
		return UpscalingRenderer.get().isUpscalingThisFrame()
				? UpscalingRenderer.get().jitterShaderProjection(camera) : camera;
	}
}
