package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FrontendRenderPass.class)
public abstract class MaterialSamplerMixin {
	@ModifyExpressionValue(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
			at = @At(value = "NEW", target = "com/mojang/renderpearl/util/TextureViewAndSampler"))
	private TextureViewAndSampler reflexium$materialLod(TextureViewAndSampler pair, String name) {
		if (!name.equals("Sampler0") || pair.view().texture().getMipLevels() <= 1) return pair;
		return new TextureViewAndSampler(pair.view(), UpscalingRenderer.get().materialSampler(pair.sampler()));
	}
}
