package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.BslFoliageMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear the terrain-only motion attachment before any terrain coverage is written. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class FoliageSurfaceMixin {
	@Inject(method = "beginLevelRendering", at = @At("RETURN"))
	private void reflexium$clearFoliage(CallbackInfo ci) {
		BslFoliageMotion.beginFrame();
	}
}
