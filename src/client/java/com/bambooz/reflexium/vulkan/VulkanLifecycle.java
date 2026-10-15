package com.bambooz.reflexium.vulkan;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.bambooz.reflexium.dlss.DlssSupport;
import com.bambooz.reflexium.reflex.ReflexController;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkLatencySubmissionPresentIdNV;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;

/**
 * Entry points for the Vulkan backend mixins. The pNext helpers allocate on the calling thread's
 * {@link MemoryStack}: vanilla pushed a frame around each of these calls, so the structs live exactly
 * as long as the Vulkan call that reads them.
 */
public final class VulkanLifecycle {
	private static org.lwjgl.vulkan.VkQueue graphicsQueue;
	private static boolean queueTagged;
	static final int PREFERRED_IMAGE_COUNT = 8;
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
				extensions.presentMetering(),
				extensions.ngxExtensions());
		VulkanSession.publish(session);
		graphicsQueue = vulkanDevice.graphicsQueue().vkQueue();
		queueTagged = false;
		ReflexiumClient.LOGGER.info("Vulkan device {} ({}): low_latency2={}, present_id={}, present_metering={}",
				session.deviceName(), session.driverInfo(), session.lowLatency2(), session.presentId(), session.presentMetering());

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
		graphicsQueue = null;
		queueTagged = false;
	}

	public static void onSwapchainCreated(long swapchain) {
		ReflexController.get().onSwapchainCreated(swapchain);
	}

	public static void onSwapchainDestroyed() {
		com.bambooz.reflexium.dlss.FrameGenerationRenderer.get().invalidate();
		ReflexController.get().onSwapchainDestroyed();
	}

	public static VkSwapchainCreateInfoKHR chainSwapchainLatency(VkSwapchainCreateInfoKHR info) {
		VulkanSession session = VulkanSession.current();
		if (session != null) {
			var caps = org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR.calloc(MemoryStack.stackGet());
			if (org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(session.physicalDevice(), info.surface(), caps) == 0) {
				// Eight images measured +10% uncapped shaderless native FPS versus the default count (2026-10-15);
				// surface limits still bound it, so a smaller maxImageCount stays valid.
				info.minImageCount(validImageCount(Math.max(info.minImageCount(), PREFERRED_IMAGE_COUNT), caps.minImageCount(), caps.maxImageCount()));
			}
		}
		if (session == null || !session.lowLatency2() || com.bambooz.reflexium.dlss.FsrSwapchain.canCreate(info)) {
			return info;
		}
		VkSwapchainLatencyCreateInfoNV latency = VkSwapchainLatencyCreateInfoNV.calloc(MemoryStack.stackGet())
				.sType$Default()
				.pNext(info.pNext())
				.latencyModeEnable(true);
		return info.pNext(latency.address());
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

	public static VkSubmitInfo2.Buffer chainSubmissionIds(org.lwjgl.vulkan.VkQueue queue, VkSubmitInfo2.Buffer submits) {
		long id = ReflexController.get().currentFrameId();
		VulkanSession session = VulkanSession.current();
		if (session == null || !session.explicitAttributionSupported() || graphicsQueue == null || queue.address() != graphicsQueue.address()) {
			return submits;
		}
		// Only this queue is ever explicitly tagged. Clearing it returns the entire device to implicit
		// attribution; zero alone would not exclude work if another queue still held a nonzero ID.
		if (!com.bambooz.reflexium.debug.DebugSettings.reflexSubmission
				|| com.bambooz.reflexium.dlss.FrameGenerationRenderer.get().inPresentBatch()) id = 0;
		if (id == 0 && !queueTagged) return submits;
		queueTagged = id != 0;
		MemoryStack stack = MemoryStack.stackGet();
		for (int i = submits.position(); i < submits.limit(); i++) {
			VkSubmitInfo2 submit = submits.get(i);
			VkLatencySubmissionPresentIdNV tag = VkLatencySubmissionPresentIdNV.calloc(stack)
					.sType$Default()
					.pNext(submit.pNext())
					.presentID(id);
			submit.pNext(tag.address());
		}
		return submits;
	}
	public static void clearSubmissionAttribution() {
		if (!queueTagged || graphicsQueue == null) return;
		try (var stack = MemoryStack.stackPush()) {
			var clear = VkSubmitInfo2.calloc(1, stack).sType$Default();
			clear.pNext(VkLatencySubmissionPresentIdNV.calloc(stack).sType$Default().presentID(0).address());
			org.lwjgl.vulkan.KHRSynchronization2.vkQueueSubmit2KHR(graphicsQueue, clear, 0);
			queueTagged = false;
		}
	}
}
