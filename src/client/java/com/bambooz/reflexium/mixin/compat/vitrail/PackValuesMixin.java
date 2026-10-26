package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.compat.PackMotionState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vitrail.render.PackValues", remap = false)
public abstract class PackValuesMixin {
	@Unique private PackMotionState reflexium$motion;
	@Inject(method = "declare", at = @At("RETURN"))
	private void reflexium$previousDeformationUniforms(CallbackInfo ci) {
		reflexium$motion = new PackMotionState(); reflexium$motion.install(this);
	}
	@Inject(method = "advance", at = @At("RETURN"))
	private void reflexium$advanceDeformation(CallbackInfo ci) { if (reflexium$motion != null) reflexium$motion.advance(); }
}
