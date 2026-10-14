package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.bambooz.reflexium.dlss.FrameGenerationRenderer;
import com.bambooz.reflexium.dlss.FsrSwapchain;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VK_NV_low_latency2 needs the swapchain created with VkSwapchainLatencyCreateInfoNV and each present
 * tagged with the frame's id; without both, the driver cannot pair markers with presented frames.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	@Shadow private long swapchain;
	@Shadow @Final private VulkanDevice device;
	@Shadow private com.mojang.renderpearl.api.device.SurfaceException eatenException;
	@Shadow private boolean swapchainOutOfDate;
	@Unique private GpuTextureView reflexium$realView;
	@Unique private boolean reflexium$presenting;

	@WrapMethod(method = "blitFromTexture")
	private void reflexium$firstGenerated(CommandEncoderBackend encoder, GpuTextureView view, Operation<Void> original) {
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		if (FsrSwapchain.owns(swapchain) || reflexium$presenting || fg.generatedCount() == 0) { original.call(encoder, view); return; }
		reflexium$realView = view;
		original.call(encoder, fg.generatedView(0));
	}

	@WrapMethod(method = "present")
	private void reflexium$presentFrames(Operation<Void> original) {
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		int count = reflexium$realView == null ? 0 : fg.generatedCount();
		if (count == 0) { fg.scheduleProxyPresent(); original.call(); return; }
		VulkanGpuSurface surface = (VulkanGpuSurface) (Object) this;
		reflexium$presenting = true;
		fg.beginPresentBatch(count + 1);
		try {
			fg.beginPresent(true);
			original.call();
			for (int i = 1; i <= count && !swapchainOutOfDate; i++) {
				surface.acquireNextTexture();
				fg.beginPresent(i < count);
				surface.blitFromTexture(device.createCommandEncoder(), i < count ? fg.generatedView(i) : reflexium$realView);
				device.createCommandEncoder().submit();
				original.call();
			}
		} catch (com.mojang.renderpearl.api.device.SurfaceException resized) {
			eatenException = resized;
			fg.invalidate();
		} finally {
			fg.endPresentBatch();
			reflexium$realView = null;
			reflexium$presenting = false;
		}
	}

	@ModifyArg(method = "configure", index = 1, at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private VkSwapchainCreateInfoKHR reflexium$latencySwapchain(VkSwapchainCreateInfoKHR info) {
		return VulkanLifecycle.chainSwapchainLatency(info);
	}

	@Inject(method = "configure", at = @At("TAIL"))
	private void reflexium$swapchainCreated(GpuSurface.Configuration configuration, CallbackInfo ci) {
		if (!FsrSwapchain.owns(swapchain)) VulkanLifecycle.onSwapchainCreated(swapchain);
	}

	@Inject(method = "destroySwapchain", at = @At("HEAD"))
	private void reflexium$swapchainDestroyed(CallbackInfo ci) {
		VulkanLifecycle.onSwapchainDestroyed();
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int reflexium$tagPresent(org.lwjgl.vulkan.VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		if (FsrSwapchain.owns(swapchain)) return FsrSwapchain.present(info);
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		info = fg.chainMetering(info);
		if (!fg.isGeneratedPresent()) info = VulkanLifecycle.chainPresentId(info);
		com.bambooz.reflexium.reflex.ReflexController reflex = com.bambooz.reflexium.reflex.ReflexController.get();
		if (fg.inPresentBatch()) reflex.beforeBatchPresent(fg.isGeneratedPresent());
		int result;
		try { result = original.call(queue, info); }
		finally { if (fg.inPresentBatch()) reflex.afterBatchPresent(fg.isGeneratedPresent()); }
		fg.onPresentResult(info, result);
		return result;
	}

	@WrapOperation(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int reflexium$fsrCreate(org.lwjgl.vulkan.VkDevice device, VkSwapchainCreateInfoKHR info, org.lwjgl.vulkan.VkAllocationCallbacks allocator,
			java.nio.LongBuffer out, Operation<Integer> original) {
		if (FsrSwapchain.canCreate(info)) {
			int result = FsrSwapchain.create(info, allocator, out);
			if (result == org.lwjgl.vulkan.VK10.VK_SUCCESS) return result;
			com.bambooz.reflexium.ReflexiumClient.LOGGER.warn("FSR swapchain creation failed ({}); using vanilla presentation", result);
		}
		return original.call(device, info, allocator, out);
	}
	@WrapOperation(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"))
	private int reflexium$fsrImages(org.lwjgl.vulkan.VkDevice device, long swapchain, java.nio.IntBuffer count,
			java.nio.LongBuffer images, Operation<Integer> original) {
		return FsrSwapchain.owns(swapchain) ? FsrSwapchain.images(count, images) : original.call(device, swapchain, count, images);
	}
	@WrapOperation(method = "destroySwapchain", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
	private void reflexium$fsrDestroy(org.lwjgl.vulkan.VkDevice device, long swapchain, org.lwjgl.vulkan.VkAllocationCallbacks allocator, Operation<Void> original) {
		if (FsrSwapchain.owns(swapchain)) FsrSwapchain.destroy(allocator);
		else original.call(device, swapchain, allocator);
	}
	@WrapOperation(method = "acquireNextTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int reflexium$fsrAcquire(org.lwjgl.vulkan.VkDevice device, long swapchain, long timeout, long semaphore, long fence,
			java.nio.IntBuffer index, Operation<Integer> original) {
		return FsrSwapchain.owns(swapchain) ? FsrSwapchain.acquire(timeout, semaphore, fence, index) : original.call(device, swapchain, timeout, semaphore, fence, index);
	}
	@ModifyArg(method = "blitFromTexture", index = 1, at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkCmdPipelineBarrier2KHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkDependencyInfo;)V"))
	private org.lwjgl.vulkan.VkDependencyInfo reflexium$fsrImageLayout(org.lwjgl.vulkan.VkDependencyInfo dependency) {
		if (!FsrSwapchain.owns(swapchain) || dependency.pImageMemoryBarriers() == null) return dependency;
		for (org.lwjgl.vulkan.VkImageMemoryBarrier2 barrier : dependency.pImageMemoryBarriers()) {
			if (barrier.newLayout() == org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
				barrier.newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL)
						.dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_READ_BIT | org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_WRITE_BIT);
			}
		}
		return dependency;
	}
}
