package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.vulkan.DeviceExtensions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.bambooz.reflexium.dlss.FsrSwapchain;
import org.lwjgl.vulkan.VkDeviceCreateInfo;

/** Adds Reflex (and optionally NGX) device extensions to the FeatureSet vanilla creates its VkDevice with. */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
	@WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
	private static int reflexium$frameGenerationQueues(org.lwjgl.vulkan.VkPhysicalDevice physical, VkDeviceCreateInfo info,
			org.lwjgl.vulkan.VkAllocationCallbacks allocator, org.lwjgl.PointerBuffer output, Operation<Integer> original) {
		return original.call(physical, FsrSwapchain.reserveQueues(physical, info), allocator, output);
	}
	@WrapOperation(
			method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"))
	private VkDevice reflexium$addExtensions(FeatureSet features, VulkanPhysicalDevice physicalDevice, Operation<VkDevice> original) {
		return original.call(DeviceExtensions.extend(features, physicalDevice), physicalDevice);
	}
}
