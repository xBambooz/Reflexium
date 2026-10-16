package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.DHCompat", remap = false)
public abstract class DhProjectionMixin {
	@Inject(method = "getProjection(FF)Lorg/joml/Matrix4f;", at = @At("RETURN"), cancellable = true)
	private static void reflexium$jitterProjection(float near, float far, CallbackInfoReturnable<Matrix4f> cir) {
		UpscalingRenderer renderer = UpscalingRenderer.get();
		if (renderer.isUpscalingThisFrame()) cir.setReturnValue(renderer.jitterShaderProjection(cir.getReturnValue()));
	}
}
