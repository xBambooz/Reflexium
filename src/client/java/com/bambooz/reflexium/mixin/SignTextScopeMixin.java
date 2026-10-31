package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.DebugSettings;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignRenderer.class)
public abstract class SignTextScopeMixin {
	@Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
	private void reflexium$diagnoseGlyphs(CallbackInfo ci) {
		if (DebugSettings.omitSignText) ci.cancel();
	}
}
