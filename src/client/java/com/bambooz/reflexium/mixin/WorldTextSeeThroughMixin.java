package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.DeferredWorldText;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TranslucentFeatureRenderPhase.class)
public abstract class WorldTextSeeThroughMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V", at = @At("HEAD"), cancellable = true)
	private void reflexium$defer(TranslucentSubmit node, CallbackInfo ci) {
		if (DeferredWorldText.collect((TranslucentFeatureRenderPhase) (Object) this, node)) ci.cancel();
	}
}
