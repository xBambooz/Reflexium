package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.BslFoliageMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class CommonUniformsMixin {
	@Inject(method = "generalCommonUniforms", at = @At("RETURN"))
	private static void reflexium$foliage(@Coerce Object holder, @Coerce Object notifier, @Coerce Object directives, CallbackInfo ci) {
		BslFoliageMotion.install(holder);
	}
}
