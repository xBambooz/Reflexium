package com.bambooz.reflexium.vulkan;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.Set;

/**
 * The Vulkan device Minecraft created, plus which of Reflexium's extensions made it into the device.
 * Published once per device on the render thread; read from anywhere.
 */
public record VulkanSession(
		VkInstance instance,
		VkPhysicalDevice physicalDevice,
		VkDevice device,
		int vendorId,
		String deviceName,
		String driverInfo,
		boolean lowLatency2,
		int lowLatencyRevision,
		boolean presentId,
		boolean presentMetering,
		Set<String> ngxExtensions
) {
	public static final int VENDOR_NVIDIA = 0x10DE;

	private static volatile VulkanSession current;
	private static volatile PendingExtensions pending = PendingExtensions.NONE;

	public static VulkanSession current() {
		return current;
	}

	public boolean isNvidia() {
		return vendorId == VENDOR_NVIDIA;
	}
	public boolean explicitAttributionSupported() { return lowLatency2 && lowLatencyRevision >= 3; }

	/** Recorded by the device-creation hook, consumed when the VulkanDevice object is constructed. */
	public record PendingExtensions(boolean lowLatency2, int lowLatencyRevision, boolean presentId, boolean presentMetering, Set<String> ngxExtensions) {
		public static final PendingExtensions NONE = new PendingExtensions(false, 0, false, false, Set.of());
	}

	public static void setPending(PendingExtensions extensions) {
		pending = extensions;
	}

	public static PendingExtensions takePending() {
		PendingExtensions value = pending;
		pending = PendingExtensions.NONE;
		return value;
	}

	public static void publish(VulkanSession session) {
		current = session;
	}

	public static void clear() {
		current = null;
	}
}
