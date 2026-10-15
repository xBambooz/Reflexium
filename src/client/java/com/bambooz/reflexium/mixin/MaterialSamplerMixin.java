package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.MaterialBinding;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The shared texture binding boundary. Vanilla binds its material as Sampler0 and Sodium binds its terrain
 * atlas as u_BlockTex with a sampler it created itself, so a SamplerCache hook alone would miss it.
 */
@Mixin(FrontendRenderPass.class)
public abstract class MaterialSamplerMixin {
	@ModifyExpressionValue(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
			at = @At(value = "NEW", target = "com/mojang/renderpearl/util/TextureViewAndSampler"))
	private TextureViewAndSampler reflexium$materialLod(TextureViewAndSampler pair, String name) {
		int path = MaterialBinding.uniformPath(name);
		if (path < 0) return pair;
		GpuTexture texture = pair.view().texture();
		if (!MaterialBinding.isMipmappedColor(texture.getMipLevels(), texture.getFormat().hasColorAspect())) return pair;
		var biased = UpscalingRenderer.get().materialSampler(pair.sampler(), path);
		return biased == pair.sampler() ? pair : new TextureViewAndSampler(pair.view(), biased);
	}
}
