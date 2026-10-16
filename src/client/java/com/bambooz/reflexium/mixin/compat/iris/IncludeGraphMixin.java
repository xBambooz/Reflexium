package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.IrisCompat;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every pack source file Iris reads, options and programs alike, passes through readFile. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.include.IncludeGraph", remap = false)
public abstract class IncludeGraphMixin {
	@Inject(method = "readFile", at = @At("RETURN"), cancellable = true)
	private static void reflexium$temporalSource(Path path, CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(IrisCompat.adapt(path, cir.getReturnValue()));
	}
}
