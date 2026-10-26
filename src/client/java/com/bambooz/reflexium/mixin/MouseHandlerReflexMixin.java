package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.reflex.ReflexController;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerReflexMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void reflexium$clickMarker(long handle, MouseButtonInfo button, int action, CallbackInfo ci) {
		if (action == 1 && button.button() == 1) ReflexController.get().triggerFlash();
	}
}
