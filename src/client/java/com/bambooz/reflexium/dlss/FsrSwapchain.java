package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static java.lang.foreign.ValueLayout.*;

/** FSR's vendor-neutral presentation scheduler. A proxy owns all five Vulkan swapchain operations. */
public final class FsrSwapchain {
	private static int family = -1, firstPrivate;
	private static FsrSwapchain instance;
	private static long proxy;
	private final MethodHandle init, create, destroy, images, acquire, present, schedule, wait, count;
	private final MemorySegment image = Arena.global().allocate(32, 8);

	private FsrSwapchain() throws IOException {
		SymbolLookup lib = SymbolLookup.libraryLookup(NgxBridge.extract("reflexium_fsr"), Arena.global());
		Linker linker = Linker.nativeLinker();
		init = bind(linker, lib, "init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
		create = bind(linker, lib, "create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		destroy = bind(linker, lib, "destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		images = bind(linker, lib, "images", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		acquire = bind(linker, lib, "acquire", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
		present = bind(linker, lib, "present", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		schedule = bind(linker, lib, "schedule", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
		wait = bind(linker, lib, "wait", FunctionDescriptor.of(JAVA_INT));
		count = bind(linker, lib, "present_count", FunctionDescriptor.of(JAVA_LONG));
	}
	private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor signature) {
		return linker.downcallHandle(lib.find("rx_fsr_sc_" + name).orElseThrow(), signature);
	}
	private static boolean wanted(int vendor) { return vendor != VulkanSession.VENDOR_NVIDIA || Boolean.getBoolean("reflexium.fsr.forceSwapchain"); }

	/** Runs before vkCreateDevice. Never request more queues than the physical family supports. */
	public static VkDeviceCreateInfo reserveQueues(VkPhysicalDevice physical, VkDeviceCreateInfo info) {
		family = -1;
		if (!ReflexiumClient.isSupportedPlatform()) return info;
		try (MemoryStack query = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(query);
			VK10.vkGetPhysicalDeviceProperties(physical, props);
			if (!wanted(props.vendorID())) return info;
			IntBuffer size = query.callocInt(1);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(physical, size, null);
			VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(size.get(0), query);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(physical, size, properties);
			VkDeviceQueueCreateInfo.Buffer queues = info.pQueueCreateInfos();
			for (int i = 0; i < queues.remaining(); i++) {
				VkDeviceQueueCreateInfo entry = queues.get(i);
				VkQueueFamilyProperties available = properties.get(entry.queueFamilyIndex());
				int needed = VK10.VK_QUEUE_GRAPHICS_BIT | VK10.VK_QUEUE_COMPUTE_BIT;
				if ((available.queueFlags() & needed) != needed || available.queueCount() - entry.queueCount() < 3) continue;
				family = entry.queueFamilyIndex(); firstPrivate = entry.queueCount();
				// Allocate in vanilla's outer stack frame, which survives until vkCreateDevice returns.
				break;
			}
		}
		if (family >= 0) {
			VkDeviceQueueCreateInfo.Buffer queues = info.pQueueCreateInfos();
			for (int i = 0; i < queues.remaining(); i++) {
				VkDeviceQueueCreateInfo entry = queues.get(i);
				if (entry.queueFamilyIndex() != family) continue;
				var priorities = MemoryStack.stackGet().callocFloat(firstPrivate + 3);
				for (int q = 0; q < firstPrivate; q++) priorities.put(q, entry.pQueuePriorities().get(q));
				for (int q = firstPrivate; q < firstPrivate + 3; q++) priorities.put(q, 1);
				entry.pQueuePriorities(priorities);
				ReflexiumClient.LOGGER.info("Reserved FSR presentation queues {}..{} in Vulkan family {}", firstPrivate, firstPrivate + 2, family);
			}
		}
		return info;
	}

	static void onDeviceCreated(VulkanDevice device, VulkanSession session) {
		instance = null; proxy = 0;
		if (family < 0 || !wanted(session.vendorId()) || family != device.graphicsQueue().queueFamilyIndex()) return;
		try {
			FsrSwapchain bridge = new FsrSwapchain();
			bridge.init.invokeExact(MemorySegment.ofAddress(session.device().address()), MemorySegment.ofAddress(session.physicalDevice().address()),
					MemorySegment.ofAddress(VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr")),
					MemorySegment.ofAddress(device.graphicsQueue().vkQueue().address()), family, firstPrivate);
			instance = bridge;
		} catch (Throwable e) { ReflexiumClient.LOGGER.warn("FSR presentation scheduler unavailable", e); }
	}
	public static boolean active() { return proxy != 0; }
	public static boolean owns(long swapchain) { return proxy != 0 && proxy == swapchain; }
	public static boolean canCreate(VkSwapchainCreateInfoKHR info) {
		if (instance == null) return false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer supported = stack.callocInt(1);
			VulkanSession session = VulkanSession.current();
			return KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(session.physicalDevice(), family, info.surface(), supported) == VK10.VK_SUCCESS && supported.get(0) != 0;
		}
	}
	public static int create(VkSwapchainCreateInfoKHR info, VkAllocationCallbacks allocator, LongBuffer out) {
		try {
			MemorySegment alloc = allocator == null ? MemorySegment.NULL : MemorySegment.ofAddress(allocator.address());
			int result = (int) instance.create.invokeExact(MemorySegment.ofAddress(info.address()), alloc, MemorySegment.ofAddress(MemoryUtil.memAddress(out)));
			if (result == VK10.VK_SUCCESS) { proxy = out.get(out.position()); ReflexiumClient.LOGGER.info("FSR presentation scheduler created"); }
			return result;
		} catch (Throwable e) { throw error(e); }
	}
	public static void destroy(VkAllocationCallbacks allocator) {
		try {
			MemorySegment alloc = allocator == null ? MemorySegment.NULL : MemorySegment.ofAddress(allocator.address());
			int result = (int) instance.destroy.invokeExact(alloc);
			if (result != 0) throw new IllegalStateException("FSR swapchain drain/destroy failed: " + result);
			proxy = 0;
		} catch (Throwable e) { throw error(e); }
	}
	public static int images(IntBuffer count, LongBuffer images) {
		try {
			MemorySegment target = images == null ? MemorySegment.NULL : MemorySegment.ofAddress(MemoryUtil.memAddress(images));
			return (int) instance.images.invokeExact(MemorySegment.ofAddress(MemoryUtil.memAddress(count)), target);
		} catch (Throwable e) { throw error(e); }
	}
	public static int acquire(long timeout, long semaphore, long fence, IntBuffer index) {
		try { return (int) instance.acquire.invokeExact(timeout, semaphore, fence, MemorySegment.ofAddress(MemoryUtil.memAddress(index))); }
		catch (Throwable e) { throw error(e); }
	}
	public static int present(VkPresentInfoKHR info) {
		try { return (int) instance.present.invokeExact(MemorySegment.ofAddress(info.address())); }
		catch (Throwable e) { throw error(e); }
	}
	static void schedule(NgxBridge.Image generated, long frameId) {
		if (!active()) return;
		try {
			MemorySegment resource = generated == null ? MemorySegment.NULL : generated.write(instance.image, 0);
			int result = (int) instance.schedule.invokeExact(resource, frameId);
			if (result != 0) throw new IllegalStateException("FSR presentation scheduling failed: " + result);
		} catch (Throwable e) { throw error(e); }
	}
	static void waitForPresents() {
		if (!active()) return;
		try { int result = (int) instance.wait.invokeExact(); if (result != 0) throw new IllegalStateException("FSR presentation drain failed: " + result); }
		catch (Throwable e) { throw error(e); }
	}
	public static long presentCount() {
		if (!active()) return 0;
		try { return (long) instance.count.invokeExact(); }
		catch (Throwable e) { throw error(e); }
	}
	private static RuntimeException error(Throwable e) { return e instanceof RuntimeException r ? r : new IllegalStateException(e); }
}
