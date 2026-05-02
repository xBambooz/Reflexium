package com.nvidiareflex.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.nvidiareflex.NvidiaReflexMod;
import com.nvidiareflex.client.GpuDetector;
import com.nvidiareflex.client.NvidiaReflexModClient;
import com.nvidiareflex.client.VulkanStateTracker;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Hooks into VulkanBackend to:
 * 1. Add VK_NV_low_latency2 to device extensions if NVIDIA GPU is detected
 * 2. Mark Vulkan as active when device creation succeeds
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {

	/**
	 * Inject into the private createDevice(Collection, VulkanPhysicalDevice) method
	 * at HEAD to add VK_NV_low_latency2 to the extension set.
	 */
	@Inject(
		method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
		at = @At("HEAD")
	)
	private static void nvidia_reflex$addLowLatencyExtension(
			Collection<String> deviceExtensions,
			VulkanPhysicalDevice physicalDevice,
			CallbackInfoReturnable<VkDevice> cir) {

		VulkanStateTracker.reset();

		if (!NvidiaReflexModClient.isModEnabled()) {
			return;
		}

		int vendorId = physicalDevice.vkPhysicalDeviceProperties().vendorID();
		if (!GpuDetector.isNvidiaVendor(vendorId)) {
			NvidiaReflexMod.LOGGER.info(
				"Selected Vulkan device is not NVIDIA: {} ({})",
				physicalDevice.deviceName(),
				physicalDevice.vendorName()
			);
			return;
		}

		// Check if the physical device supports VK_NV_low_latency2
		if (physicalDevice.hasDeviceExtension(NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME)) {
			deviceExtensions.add(NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
			VulkanStateTracker.setLowLatencyExtensionEnabled(true);
			NvidiaReflexMod.LOGGER.info("VK_NV_low_latency2 extension enabled");
		} else {
			NvidiaReflexMod.LOGGER.info("VK_NV_low_latency2 not supported by this device");
			VulkanStateTracker.setLowLatencyExtensionEnabled(false);
		}
	}

	@Inject(
		method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
		at = @At("RETURN")
	)
	private static void nvidia_reflex$markVulkanActive(
			Collection<String> deviceExtensions,
			VulkanPhysicalDevice physicalDevice,
			CallbackInfoReturnable<VkDevice> cir) {

		if (NvidiaReflexModClient.isModEnabled() && cir.getReturnValue() != null) {
			VulkanStateTracker.setVulkanActive(true);
		}
	}
}
