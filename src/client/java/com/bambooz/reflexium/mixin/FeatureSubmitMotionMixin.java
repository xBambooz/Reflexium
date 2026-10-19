package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.ObjectMotion;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Skin hands use entityTranslucent even when the visible texel is opaque.
@Mixin({SimpleFeatureRenderPhase.class, TranslucentFeatureRenderPhase.class})
public abstract class FeatureSubmitMotionMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V", at = @At("HEAD"))
	private void reflexium$attribute(SubmitNode node, CallbackInfo ci) {
		ObjectMotion.get().onSubmit(node);
	}
}
