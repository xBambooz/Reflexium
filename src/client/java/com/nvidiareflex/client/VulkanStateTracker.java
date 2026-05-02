package com.nvidiareflex.client;

/**
 * Tracks whether Minecraft's Vulkan renderer is active and whether
 * the VK_NV_low_latency2 extension was successfully enabled.
 */
public class VulkanStateTracker {
	private static volatile boolean vulkanActive = false;
	private static volatile boolean lowLatencyExtensionEnabled = false;

	public static boolean isVulkanActive() {
		return vulkanActive;
	}

	public static void setVulkanActive(boolean active) {
		vulkanActive = active;
	}

	public static boolean isLowLatencyExtensionEnabled() {
		return lowLatencyExtensionEnabled;
	}

	public static void setLowLatencyExtensionEnabled(boolean enabled) {
		lowLatencyExtensionEnabled = enabled;
	}

	public static void reset() {
		vulkanActive = false;
		lowLatencyExtensionEnabled = false;
	}
}
