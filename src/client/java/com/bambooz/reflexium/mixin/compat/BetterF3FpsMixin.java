package com.bambooz.reflexium.mixin.compat;

import com.bambooz.reflexium.debug.FpsText;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** BetterF3's FPS module formats "%s fps / %s fps %s" and trims it; add the rendered rate after the first number. */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.modules.FpsModule", remap = false)
public abstract class BetterF3FpsMixin {
	@WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/String;trim()Ljava/lang/String;"))
	private String reflexium$renderedFps(String line, Operation<String> original, Minecraft minecraft) {
		return FpsText.withRendered(original.call(line), minecraft.getFps());
	}
}
