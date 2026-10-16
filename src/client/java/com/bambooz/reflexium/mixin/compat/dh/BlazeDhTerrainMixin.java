package com.bambooz.reflexium.mixin.compat.dh;

import com.bambooz.reflexium.compat.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer", remap = false)
public abstract class BlazeDhTerrainMixin {
	@Inject(method = "render", at = @At("RETURN"))
	private void reflexium$captureDepth(@Coerce Object params, boolean opaque, @Coerce Object buffers, CallbackInfo ci) {
		DistantHorizonsCompat.captureNative(params);
	}
}
