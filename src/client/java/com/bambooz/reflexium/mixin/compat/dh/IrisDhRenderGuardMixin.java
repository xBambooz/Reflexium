package com.bambooz.reflexium.mixin.compat.dh;

import com.bambooz.reflexium.compat.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Check before DH calls setup, which can resize and clear depth inside Minecraft's open terrain pass. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.LodRenderer", remap = false)
public abstract class IrisDhRenderGuardMixin {
	@Inject(method = "renderTerrain(Lcom/seibel/distanthorizons/core/render/RenderParam;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V",
			at = @At("HEAD"), cancellable = true)
	private void reflexium$deferLateDepthResize(CallbackInfo ci) {
		if (!DistantHorizonsCompat.irisDepthReady()) ci.cancel();
	}
}
