package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Let Vitrail's own standDown path run, retaining its user's scale for frames we do not own. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.RenderScale", remap = false)
public abstract class RenderScaleMixin {
	@ModifyExpressionValue(method = "beginWorld", at = @At(value = "FIELD",
			target = "Ldev/vitrail/render/RenderScale;percent:I"))
	private static int reflexium$oneScale(int requested) {
		return UpscalingRenderer.get().isUpscalingRequestedThisFrame() ? 100 : requested;
	}
}
