package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SamplerCache.class)
public abstract class SamplerCacheMixin {
	@Inject(method = "getSampler", at = @At("RETURN"), cancellable = true)
	private void reflexium$mipBiasSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, boolean useMipmaps, CallbackInfoReturnable<GpuSampler> cir) {
		if (useMipmaps && UpscalingRenderer.get().isUpscalingThisFrame()) {
			cir.setReturnValue(UpscalingRenderer.get().materialSampler(cir.getReturnValue()));
		}
	}
}
