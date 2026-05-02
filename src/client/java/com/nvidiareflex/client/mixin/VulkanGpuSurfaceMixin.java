package com.nvidiareflex.client.mixin;

import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.nvidiareflex.client.NvidiaReflexModClient;
import com.nvidiareflex.client.ReflexManager;
import com.nvidiareflex.client.VulkanStateTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into VulkanGpuSurface to capture the swapchain handle
 * and VkDevice after swapchain creation, then initialize ReflexManager.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {

	@Shadow @Final private VulkanDevice device;
	@Shadow private long swapchain;

	/**
	 * After swapchain is created/recreated in configure(), initialize or update Reflex.
	 */
	@Inject(method = "configure", at = @At("TAIL"))
	private void nvidia_reflex$onSwapchainConfigured(GpuSurface.Configuration config, CallbackInfo ci) {
		if (!NvidiaReflexModClient.isModEnabled()) {
			return;
		}

		if (!VulkanStateTracker.isLowLatencyExtensionEnabled()) {
			return;
		}

		ReflexManager reflex = ReflexManager.getInstance();
		if (!reflex.isAvailable()) {
			// First-time init
			reflex.initialize(this.device.vkDevice(), this.swapchain, true);
		} else {
			// Swapchain recreated (e.g. window resize, vsync toggle)
			reflex.updateSwapchain(this.swapchain);
		}
	}

	/**
	 * Clean up Reflex on surface close.
	 */
	@Inject(method = "close", at = @At("HEAD"))
	private void nvidia_reflex$onSurfaceClose(CallbackInfo ci) {
		ReflexManager.getInstance().shutdown();
		VulkanStateTracker.reset();
	}
}
