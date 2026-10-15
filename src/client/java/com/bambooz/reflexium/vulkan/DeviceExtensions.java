package com.bambooz.reflexium.vulkan;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.dlss.NgxBridge;
import com.bambooz.reflexium.dlss.DlssSupport;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.KHRPresentId;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupSizeControlFeatures;
import org.lwjgl.vulkan.NVPresentMetering;
import org.lwjgl.vulkan.VkPhysicalDevicePresentMeteringFeaturesNV;

import java.util.Arrays;
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
		if (!ReflexiumClient.isSupportedPlatform()) {
			return vanilla;
		}
		// FFX queries physical support when choosing shader permutations. Enable those supported
		// features on the logical device too, including on AMD and Intel.
		vanilla = optional(vanilla, physicalDevice, "VK_KHR_shader_float16_int8",
				new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"));
		vanilla = optional(vanilla, physicalDevice, "VK_EXT_subgroup_size_control",
				new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceSubgroupSizeControlFeatures.class), "subgroupSizeControl"));
		vanilla = optional(vanilla, physicalDevice, null,
				new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageExtendedFormats"));
		if (physicalDevice.vkPhysicalDeviceProperties().vendorID() != VulkanSession.VENDOR_NVIDIA) return vanilla;

		Set<String> extensions = new LinkedHashSet<>();
		Set<VulkanFeature> features = new LinkedHashSet<>();
		VulkanFeature meteringFeature = new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePresentMeteringFeaturesNV.class), "presentMetering");
		FeatureSet metering = new FeatureSet("Reflexium frame pacing", Set.of(NVPresentMetering.VK_NV_PRESENT_METERING_EXTENSION_NAME), Set.of(meteringFeature));
		boolean presentMetering = physicalDevice.hasDeviceExtension(NVPresentMetering.VK_NV_PRESENT_METERING_EXTENSION_NAME)
				&& metering.allFeaturesSupported(physicalDevice.vkPhysicalDevice());
		if (presentMetering) {
			extensions.add(NVPresentMetering.VK_NV_PRESENT_METERING_EXTENSION_NAME);
			features.add(meteringFeature);
		}

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
		Set<String> wanted = new LinkedHashSet<>(DlssSupport.DEVICE_EXTENSIONS);
		NgxBridge.get().ifPresent(bridge -> wanted.addAll(Arrays.asList(bridge.requiredDeviceExtensions())));
		for (String name : wanted) {
			if (physicalDevice.hasDeviceExtension(name)) {
				ngx.add(name);
			} else {
				ReflexiumClient.LOGGER.info("NGX wants {} but {} does not support it", name, physicalDevice.deviceName());
			}
		}
		extensions.addAll(ngx);

		VulkanSession.setPending(new VulkanSession.PendingExtensions(lowLatency2, extensionRevision(physicalDevice), presentId, presentMetering, Set.copyOf(ngx)));
		extensions.removeAll(vanilla.extensions());
		if (extensions.isEmpty() && features.isEmpty()) {
			return vanilla;
		}
		ReflexiumClient.LOGGER.info("Enabling device extensions: {}", String.join(", ", extensions));
		return vanilla.composite(new FeatureSet("Reflexium", extensions, features));
	}

	private static FeatureSet optional(FeatureSet vanilla, VulkanPhysicalDevice device, String extension, VulkanFeature feature) {
		FeatureSet requested = new FeatureSet("FSR", extension == null ? Set.of() : Set.of(extension), Set.of(feature));
		return (extension == null || device.hasDeviceExtension(extension)) && requested.allFeaturesSupported(device.vkPhysicalDevice())
				? vanilla.composite(requested) : vanilla;
	}
	private static int extensionRevision(VulkanPhysicalDevice device) {
		try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
			var count = stack.callocInt(1);
			if (org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties(device.vkPhysicalDevice(), (String) null, count, null) != 0) return 0;
			var extensions = org.lwjgl.vulkan.VkExtensionProperties.calloc(count.get(0), stack);
			if (org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties(device.vkPhysicalDevice(), (String) null, count, extensions) != 0) return 0;
			for (var extension : extensions) if (extension.extensionNameString().equals(NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME)) return extension.specVersion();
			return 0;
		}
	}
}
