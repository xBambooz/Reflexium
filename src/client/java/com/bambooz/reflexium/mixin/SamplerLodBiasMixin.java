package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.AppliedLodBias;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGpuSampler.class)
public abstract class SamplerLodBiasMixin implements AppliedLodBias {
	@Unique private float reflexium$appliedBias;

	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"), index = 0)
	private float reflexium$lodBias(float original) {
		Float replacement = UpscalingRenderer.creatingSamplerBias();
		reflexium$appliedBias = replacement == null ? original : replacement;
		return reflexium$appliedBias;
	}

	@Override
	public float reflexium$appliedLodBias() {
		return reflexium$appliedBias;
	}
}
