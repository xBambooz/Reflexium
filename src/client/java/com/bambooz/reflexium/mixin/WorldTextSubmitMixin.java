package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.DeferredWorldText;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleFeatureRenderPhase.class)
public abstract class WorldTextSubmitMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V", at = @At("HEAD"), cancellable = true)
	private void reflexium$defer(SubmitNode node, CallbackInfo ci) {
		if (DeferredWorldText.collect((FeatureRenderPhase<?>) this, node)) ci.cancel();
	}
}
