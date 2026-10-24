package com.bambooz.reflexium.mixin.compat.dh;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** DH binds its own LOD atlas sampler; give it the same upscaling mip bias as Bedrock/Sodium terrain. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper", remap = false)
public abstract class BlazeDhRenderPassMixin {
	@WrapOperation(method = "bindTexture", at = @At(value = "INVOKE",
			target = "Lcom/seibel/distanthorizons/common/render/blaze/wrappers/texture/IDhBlazeTexture;getTextureSampler()Lcom/mojang/renderpearl/api/textures/GpuSampler;"))
	private GpuSampler reflexium$biasDhSampler(@Coerce Object texture, Operation<GpuSampler> original) {
		GpuSampler sampler = original.call(texture);
		// The LOD atlas is the mipmapped DH texture; non-mipmapped lightmap and UI samplers are
		// returned unchanged by the mip-bias sampler cache.
		return UpscalingRenderer.get().distantHorizonsSampler(sampler);
	}
}
