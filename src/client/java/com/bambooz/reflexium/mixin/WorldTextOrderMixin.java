package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.DeferredWorldText;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SubmitNodeStorage.class)
public abstract class WorldTextOrderMixin {
	@Inject(method = "order(I)Lnet/minecraft/client/renderer/SubmitNodeCollection;", at = @At("RETURN"))
	private void reflexium$order(int order, CallbackInfoReturnable<SubmitNodeCollection> cir) {
		DeferredWorldText.register((SubmitNodeStorage) (Object) this, cir.getReturnValue(), order);
	}
}
