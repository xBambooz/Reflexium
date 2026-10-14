package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.ObjectMotion;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Only opaque phase submissions: translucent geometry is handled by the reactive mask.
@Mixin(SimpleFeatureRenderPhase.class)
public abstract class FeatureSubmitMotionMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void reflexium$attribute(SubmitNode node, CallbackInfo ci) {
		ObjectMotion.get().onSubmit(node);
	}
}
