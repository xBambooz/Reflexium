package com.nvidiareflex.client;

/**
 * Platform and GPU vendor helpers for Vulkan-specific Reflex support.
 */
public class GpuDetector {

	private static final int NVIDIA_VENDOR_ID = 0x10DE;

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public static boolean isNvidiaVendor(int vendorID) {
		return vendorID == NVIDIA_VENDOR_ID;
	}
}
