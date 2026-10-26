package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.bambooz.reflexium.upscale.fsr.FsrSwapchain;
import com.bambooz.reflexium.vulkan.DeviceExtensions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds Reflex (and optionally NGX) device extensions to the FeatureSet vanilla creates its VkDevice with. */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
	@WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
	private static int reflexium$frameGenerationQueues(VkPhysicalDevice physical, VkDeviceCreateInfo info,
			VkAllocationCallbacks allocator, PointerBuffer output, Operation<Integer> original) {
		VkDeviceCreateInfo reserved = FsrSwapchain.reserveQueues(physical, info);
		return original.call(physical, StreamlineSupport.reserveQueues(physical, reserved), allocator, output);
	}
	@WrapOperation(
			method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"))
	private VkDevice reflexium$addExtensions(FeatureSet features, VulkanPhysicalDevice physicalDevice, Operation<VkDevice> original) {
		return original.call(DeviceExtensions.extend(features, physicalDevice), physicalDevice);
	}
}
