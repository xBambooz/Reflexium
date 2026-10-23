package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.upscale.MaterialBinding;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Bias only material samplers handed to geometry. Pack depth, shadow and composite samplers keep their
 * states: the atlas sampler Vitrail binds for albedo and interpolated PBR maps, and the mipmapped
 * nearest sampler it picks for non-interpolated PBR material maps.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.render.GeometryProgram", remap = false)
public abstract class GeometryProgramMixin {
	@ModifyVariable(method = "sampler", at = @At("HEAD"), argsOnly = true)
	private GpuSampler reflexium$materialMipBias(GpuSampler sampler) {
		return UpscalingRenderer.get().materialSampler(sampler, MaterialBinding.PACK_ATLAS);
	}

	/** passSampler's only two-argument getClampToEdge call is the PBR material branch. */
	@ModifyExpressionValue(method = "passSampler", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/SamplerCache;getClampToEdge(Lcom/mojang/renderpearl/api/textures/FilterMode;Z)Lcom/mojang/renderpearl/api/textures/GpuSampler;"))
	private GpuSampler reflexium$pbrMaterialMipBias(GpuSampler sampler) {
		return UpscalingRenderer.get().materialSampler(sampler, MaterialBinding.PACK_PBR);
	}
}
