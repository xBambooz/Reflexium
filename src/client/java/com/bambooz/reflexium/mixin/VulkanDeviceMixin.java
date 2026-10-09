package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void reflexium$created(VulkanInstance instance, VulkanPhysicalDevice physicalDevice, FeatureSet features,
			VkDevice device, long vma, CheckpointExtension checkpoints, CallbackInfo ci) {
		VulkanLifecycle.onDeviceCreated(instance, physicalDevice, device);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void reflexium$closing(CallbackInfo ci) {
		VulkanLifecycle.onDeviceClosing();
	}
}
