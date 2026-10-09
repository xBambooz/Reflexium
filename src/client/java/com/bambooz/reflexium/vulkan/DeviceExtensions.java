package com.bambooz.reflexium.vulkan;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.dlss.DlssSupport;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.KHRPresentId;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adds Reflexium's device extensions to the FeatureSet Minecraft is about to create its VkDevice with.
 * Only extensions (and features) the physical device reports are added, so this can never make
 * device creation fail on hardware that vanilla supports.
 */
public final class DeviceExtensions {
	private static final VulkanFeature PRESENT_ID_FEATURE =
			new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePresentIdFeaturesKHR.class), "presentId");

	private DeviceExtensions() {
	}

	public static FeatureSet extend(FeatureSet vanilla, VulkanPhysicalDevice physicalDevice) {
		VulkanSession.setPending(VulkanSession.PendingExtensions.NONE);
		if (!ReflexiumClient.isSupportedPlatform()
				|| physicalDevice.vkPhysicalDeviceProperties().vendorID() != VulkanSession.VENDOR_NVIDIA) {
			return vanilla;
		}

		Set<String> extensions = new LinkedHashSet<>();
		Set<VulkanFeature> features = new LinkedHashSet<>();

		boolean lowLatency2 = physicalDevice.hasDeviceExtension(NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
		boolean presentId = false;
		if (lowLatency2) {
			extensions.add(NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
			FeatureSet presentIdSet = new FeatureSet("Reflexium present id",
					Set.of(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME), Set.of(PRESENT_ID_FEATURE));
			if (physicalDevice.hasDeviceExtension(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME)
					&& presentIdSet.allFeaturesSupported(physicalDevice.vkPhysicalDevice())) {
				extensions.add(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME);
				features.add(PRESENT_ID_FEATURE);
				presentId = true;
			}
		} else {
			ReflexiumClient.LOGGER.info("{} does not expose VK_NV_low_latency2 (driver too old?)", physicalDevice.deviceName());
		}

		Set<String> ngx = new LinkedHashSet<>();
		if (ReflexiumConfig.get().dlssDetection()) {
			for (String name : DlssSupport.DEVICE_EXTENSIONS) {
				if (physicalDevice.hasDeviceExtension(name)) {
					ngx.add(name);
				} else {
					ReflexiumClient.LOGGER.info("NGX wants {} but {} does not support it", name, physicalDevice.deviceName());
				}
			}
			extensions.addAll(ngx);
		}

		VulkanSession.setPending(new VulkanSession.PendingExtensions(lowLatency2, presentId, Set.copyOf(ngx)));
		extensions.removeAll(vanilla.extensions());
		if (extensions.isEmpty() && features.isEmpty()) {
			return vanilla;
		}
		ReflexiumClient.LOGGER.info("Enabling device extensions: {}", String.join(", ", extensions));
		return vanilla.composite(new FeatureSet("Reflexium", extensions, features));
	}
}
