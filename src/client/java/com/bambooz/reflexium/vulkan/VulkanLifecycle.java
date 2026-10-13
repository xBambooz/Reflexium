package com.bambooz.reflexium.vulkan;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
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
				extensions.presentId(),
				extensions.ngxExtensions());
		VulkanSession.publish(session);
		ReflexiumClient.LOGGER.info("Vulkan device {} ({}): low_latency2={}, present_id={}",
				session.deviceName(), session.driverInfo(), session.lowLatency2(), session.presentId());

		ReflexController.get().onDeviceCreated(session);
		if (ReflexiumConfig.get().dlssDetection() && ReflexiumClient.isSupportedPlatform()) {
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
		ReflexController.get().onSwapchainCreated(swapchain);
	}

	public static void onSwapchainDestroyed() {
		ReflexController.get().onSwapchainDestroyed();
	}

	public static VkSwapchainCreateInfoKHR chainSwapchainLatency(VkSwapchainCreateInfoKHR info) {
		VulkanSession session = VulkanSession.current();
		if (session == null || !session.lowLatency2()) {
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

	public static VkSubmitInfo2.Buffer chainSubmissionIds(VkSubmitInfo2.Buffer submits) {
		long id = ReflexController.get().currentFrameId();
		VulkanSession session = VulkanSession.current();
		if (id == 0 || session == null || !session.lowLatency2()) {
			return submits;
		}
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
}
