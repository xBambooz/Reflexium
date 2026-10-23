package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineMixin {
	@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true)
	private float reflexium$scaleOutlineWidth(float width) {
		return UpscalingRenderer.get().scaleLineWidth(width);
	}
}
