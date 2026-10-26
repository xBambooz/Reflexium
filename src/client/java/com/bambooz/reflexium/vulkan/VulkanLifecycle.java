package com.bambooz.reflexium.vulkan;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.upscale.dlss.DlssSupport;
import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

/**
 * Entry points for the Vulkan backend mixins. The pNext helpers allocate on the calling thread's
 * {@link MemoryStack}: vanilla pushed a frame around each of these calls, so the structs live exactly
 * as long as the Vulkan call that reads them.
 */
public final class VulkanLifecycle {
	static final int PREFERRED_IMAGE_COUNT = 8;
	/** Last native swapchain: requested count is not proof, so the created count is read back from the driver. */
	public record SwapchainState(int requested, int created, int presentMode, int surfaceMin, int surfaceMax, int width, int height, int format) { }
	private static volatile SwapchainState swapchainState;
	private static int pendingRequested = -1, pendingPresentMode = -1, pendingMin = -1, pendingMax = -1, pendingWidth, pendingHeight, pendingFormat;
	public static SwapchainState swapchainState() { return swapchainState; }
	private VulkanLifecycle() {
	}

	public static void onDeviceCreated(VulkanInstance instance, VulkanPhysicalDevice physicalDevice, VkDevice device, VulkanDevice vulkanDevice) {
		VulkanSession.PendingExtensions extensions = VulkanSession.takePending();
		VulkanSession session = new VulkanSession(
				instance.vkInstance(),
				physicalDevice.vkPhysicalDevice(),
				device,
				physicalDevice.vkPhysicalDeviceProperties().vendorID(),
				physicalDevice.deviceName(),
				physicalDevice.driverInfo(),
				extensions.lowLatency2(),
				extensions.lowLatencyRevision(),
				extensions.presentId(),
				StreamlineSupport.initialized(),
				extensions.dlssExtensions());
		VulkanSession.publish(session);
		ReflexiumClient.LOGGER.info("Vulkan device {} ({}): low_latency2={}, present_id={}",
				session.deviceName(), session.driverInfo(), session.lowLatency2(), session.presentId());

		if (StreamlineSupport.initialized()) {
			StreamlineSupport.onDeviceCreated(session, vulkanDevice.graphicsQueue().queueFamilyIndex(), 0);
		}
		ReflexController.get().onDeviceCreated(session);
		if (ReflexiumClient.isSupportedPlatform()) {
			DlssSupport.probe(session);
		}
		UpscalingRenderer.get().onDeviceCreated(vulkanDevice, session);
	}

	public static void onDeviceClosing() {
		UpscalingRenderer.get().onDeviceClosing();
		ReflexController.get().shutdown();
		VulkanSession.clear();
	}

	public static void onSwapchainCreated(long swapchain) {
		VulkanSession session = VulkanSession.current();
		if (session != null) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var count = stack.mallocInt(1);
				int created = KHRSwapchain.vkGetSwapchainImagesKHR(session.device(), swapchain, count, null) == 0 ? count.get(0) : -1;
				swapchainState = new SwapchainState(pendingRequested, created, pendingPresentMode, pendingMin, pendingMax, pendingWidth, pendingHeight, pendingFormat);
				ReflexiumClient.LOGGER.info("Swapchain created: {} images (requested {}, surface {}..{}), present mode {}, {}x{}", created, pendingRequested,
						pendingMin, pendingMax == 0 ? "unbounded" : pendingMax, presentModeName(pendingPresentMode), pendingWidth, pendingHeight);
			}
		}
		FrameGenerationRenderer.get().onSwapchainCreated();
		ReflexController.get().onSwapchainCreated(swapchain);
		ReflexController.get().suspendMarkers(4);
	}

	public static String presentModeName(int mode) {
		return switch (mode) {
			case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
			case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
			case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
			case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
			default -> "mode " + mode;
		};
	}

	public static void onSwapchainDestroyed() {
		FrameGenerationRenderer.get().invalidate();
		ReflexController.get().onSwapchainDestroyed();
		ReflexController.get().suspendMarkers(4);
	}

	public static VkSwapchainCreateInfoKHR prepareSwapchain(VkSwapchainCreateInfoKHR info) {
		VulkanSession session = VulkanSession.current();
		if (session != null) {
			var caps = VkSurfaceCapabilitiesKHR.calloc(MemoryStack.stackGet());
			if (KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(session.physicalDevice(), info.surface(), caps) == 0) {
				// Eight images measured +10% uncapped shaderless native FPS versus the default count (2026-10-15);
				// surface limits still bound it, so a smaller maxImageCount stays valid.
				info.minImageCount(validImageCount(Math.max(info.minImageCount(), PREFERRED_IMAGE_COUNT), caps.minImageCount(), caps.maxImageCount()));
				pendingMin = caps.minImageCount(); pendingMax = caps.maxImageCount();
			}
			pendingRequested = info.minImageCount(); pendingPresentMode = info.presentMode();
			pendingWidth = info.imageExtent().width(); pendingHeight = info.imageExtent().height();
			pendingFormat = info.imageFormat();
		}
		return info;
	}

	public static VkPresentInfoKHR chainPresentId(VkPresentInfoKHR info) {
		long id = ReflexController.get().takePresentId();
		if (id == 0 || info.swapchainCount() != 1) {
			return info;
		}
		MemoryStack stack = MemoryStack.stackGet();
		VkPresentIdKHR presentId = VkPresentIdKHR.calloc(stack)
				.sType$Default()
				.pNext(info.pNext())
				.swapchainCount(1)
				.pPresentIds(stack.longs(id));
		return info.pNext(presentId.address());
	}

	public static int validImageCount(int original, int minimum, int maximum) {
		int count = Math.max(original, minimum);
		return maximum == 0 ? count : Math.min(count, maximum);
	}
}
