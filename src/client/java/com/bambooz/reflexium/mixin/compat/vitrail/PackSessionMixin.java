package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.compat.VitrailCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Apply the known pack's TAA override in memory; the user's saved file stays authoritative off. */
@Pseudo
@Mixin(targets = "dev.vitrail.settings.PackSession", remap = false)
public abstract class PackSessionMixin {
	@Inject(method = "read", at = @At("RETURN"), cancellable = true)
	private static void reflexium$packTemporalOwner(CallbackInfoReturnable<Object> cir) {
		cir.setReturnValue(VitrailCompat.withTemporalOwner(cir.getReturnValue()));
	}
}
