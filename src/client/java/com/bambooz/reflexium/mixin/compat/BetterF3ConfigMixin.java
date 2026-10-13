package com.bambooz.reflexium.mixin.compat;

import com.bambooz.reflexium.compat.betterf3.BetterF3Compat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BetterF3 rebuilds its module layout from its config file and turns unknown module ids into blank spacers. */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.config.ModConfigFile", remap = false)
public abstract class BetterF3ConfigMixin {
	@Inject(method = "load", at = @At("HEAD"))
	private static void reflexium$registerModule(CallbackInfo ci) {
		BetterF3Compat.registerModule();
	}

	@Inject(method = "load", at = @At("RETURN"))
	private static void reflexium$placeModule(CallbackInfo ci) {
		BetterF3Compat.placeOnce();
	}
}
