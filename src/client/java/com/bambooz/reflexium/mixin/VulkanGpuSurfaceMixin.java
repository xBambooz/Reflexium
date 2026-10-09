package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VK_NV_low_latency2 needs the swapchain created with VkSwapchainLatencyCreateInfoNV and each present
 * tagged with the frame's id; without both, the driver cannot pair markers with presented frames.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	@Shadow private long swapchain;

	@ModifyArg(method = "configure", index = 1, at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private VkSwapchainCreateInfoKHR reflexium$latencySwapchain(VkSwapchainCreateInfoKHR info) {
		return VulkanLifecycle.chainSwapchainLatency(info);
	}

	@Inject(method = "configure", at = @At("TAIL"))
	private void reflexium$swapchainCreated(GpuSurface.Configuration configuration, CallbackInfo ci) {
		VulkanLifecycle.onSwapchainCreated(swapchain);
	}

	@Inject(method = "destroySwapchain", at = @At("HEAD"))
	private void reflexium$swapchainDestroyed(CallbackInfo ci) {
		VulkanLifecycle.onSwapchainDestroyed();
	}

	@ModifyArg(method = "present", index = 1, at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private VkPresentInfoKHR reflexium$tagPresent(VkPresentInfoKHR info) {
		return VulkanLifecycle.chainPresentId(info);
	}
}
