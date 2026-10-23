package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.compat.ComplementaryHdr;
import com.bambooz.reflexium.compat.ComplementaryMotionSource;
import com.bambooz.reflexium.compat.ComplementaryTemporalSource;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.debug.DebugSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;

@Pseudo
@Mixin(targets = "dev.vitrail.pack.source.ShaderPackSource", remap = false)
public abstract class ShaderPackSourceMixin {
	@Shadow public abstract String packName();
	@Shadow public abstract String rel(Path file);

	@Inject(method = "readLines", at = @At("RETURN"), cancellable = true)
	private void reflexium$temporalSource(Path file, CallbackInfoReturnable<List<String>> cir) {
		if (!VitrailCompat.ownsTemporalSampling() || !DebugSettings.packResolveBypass()) return;
		cir.setReturnValue(ComplementaryTemporalSource.adapt(packName(), rel(file), cir.getReturnValue(), true, DebugSettings.packPreFilters()));
		cir.setReturnValue(ComplementaryHdr.adapt(packName(), rel(file), cir.getReturnValue()));
		cir.setReturnValue(ComplementaryMotionSource.adapt(packName(), rel(file), cir.getReturnValue()));
	}
}
