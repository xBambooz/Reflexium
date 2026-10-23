package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Main targets have their new dimensions here, before Iris opens a terrain render pass. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class DhDepthResizeMixin {
	@Inject(method = "beginLevelRendering", at = @At("RETURN"))
	private void reflexium$resizeDhDepth(CallbackInfo ci) {
		DistantHorizonsCompat.prepareIrisDepth();
	}
}
