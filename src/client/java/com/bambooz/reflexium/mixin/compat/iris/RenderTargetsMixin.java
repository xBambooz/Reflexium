package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.IrisCompat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Main-target changes must not replace DH's independently projected depth. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
public abstract class RenderTargetsMixin {
	@Shadow private GpuTexture currentDepthTexture;
	@Unique private GpuTexture reflexium$previousMainDepth;

	@Inject(method = "resizeIfNeeded", at = @At("HEAD"))
	private void reflexium$rememberMainDepth(CallbackInfoReturnable<Boolean> cir) {
		reflexium$previousMainDepth = currentDepthTexture;
	}

	@Redirect(method = "resizeIfNeeded", at = @At(value = "INVOKE", ordinal = 2, target =
			"Lnet/irisshaders/iris/targets/RenderFramebuffer;addDepthAttachment(Lcom/mojang/renderpearl/api/textures/GpuTexture;)V"))
	private void reflexium$preserveForeignDepth(@Coerce Object framebuffer, GpuTexture replacement) {
		IrisCompat.replaceMainDepth(framebuffer, replacement, reflexium$previousMainDepth);
	}
}
