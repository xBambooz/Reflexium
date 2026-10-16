package com.bambooz.reflexium.mixin.compat.dh;

import com.bambooz.reflexium.compat.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.DHCompatInternal", remap = false)
public abstract class IrisDhTerrainMixin {
	@Inject(method = "renderTerrain", at = @At("TAIL"))
	private void reflexium$captureDepth(CallbackInfo ci) {
		DistantHorizonsCompat.captureIris(this);
	}
}
