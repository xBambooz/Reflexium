package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Startup, reload keybind, pack switch and shader toggle all load through loadShaderpack. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisLoadMixin {
	@Inject(method = "loadShaderpack", at = @At("HEAD"))
	private static void reflexium$packLoading(CallbackInfo ci) {
		IrisCompat.onPackLoading();
	}
}
