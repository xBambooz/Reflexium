package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.upscale.MaterialBinding;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Albedo reaches Iris through the biased Sampler0/u_BlockTex binding. PBR normal and specular atlases that
 * cannot interpolate get Iris-created native samplers instead, read by the "normals" and "specular" bindings.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPbrSamplerMixin {
	@Inject(method = {"normalNativeSampler", "specularNativeSampler"}, at = @At("RETURN"), cancellable = true)
	private void reflexium$pbrMipBias(CallbackInfoReturnable<GpuSampler> cir) {
		GpuSampler sampler = cir.getReturnValue();
		if (sampler != null) cir.setReturnValue(UpscalingRenderer.get().materialSampler(sampler, MaterialBinding.PACK_PBR));
	}
}
