package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Bias only the material atlas passed to geometry; pack depth and composite samplers keep their states. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.GeometryProgram", remap = false)
public abstract class GeometryProgramMixin {
	@ModifyVariable(method = "sampler", at = @At("HEAD"), argsOnly = true)
	private GpuSampler reflexium$materialMipBias(GpuSampler sampler) {
		return UpscalingRenderer.get().materialSampler(sampler);
	}
}
