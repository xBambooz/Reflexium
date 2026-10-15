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
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.LongBuffer;

/**
 * VK_NV_low_latency2 needs the swapchain created with VkSwapchainLatencyCreateInfoNV and each present
 * tagged with the frame's id; without both, the driver cannot pair markers with presented frames.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	@WrapMethod(method = "acquireNextTexture")
	private void reflexium$timeAcquire(Operation<Void> original) {
		int next = (currentAcquireSemaphore + 1) % acquireSemaphores.length;
		var completion = reflexium$acquireCompletion[next];
		if (completion != null) {
			// Outside ACQUIRE so that span keeps meaning the acquire call alone.
			long wait = com.bambooz.reflexium.debug.FrameProfiler.start(com.bambooz.reflexium.debug.FrameProfiler.ACQUIRE_REUSE_WAIT, null);
			try { if (!completion.awaitCompletion(5_000_000_000L)) throw new IllegalStateException("Acquire semaphore's previous wait has not completed"); }
			finally { com.bambooz.reflexium.debug.FrameProfiler.end(com.bambooz.reflexium.debug.FrameProfiler.ACQUIRE_REUSE_WAIT, wait, null); }
			completion.close(); reflexium$acquireCompletion[next] = null;
		}
		long timing = com.bambooz.reflexium.debug.FrameProfiler.start(com.bambooz.reflexium.debug.FrameProfiler.ACQUIRE, null);
		try { original.call(); }
		finally { com.bambooz.reflexium.debug.FrameProfiler.end(com.bambooz.reflexium.debug.FrameProfiler.ACQUIRE, timing, null); }
	}
	@Shadow private long swapchain;
	@Shadow @Final private VulkanDevice device;
	@Shadow private com.mojang.renderpearl.api.device.SurfaceException eatenException;
	@Shadow private boolean swapchainOutOfDate;
	@Shadow @Final private long[] acquireSemaphores;
	@Shadow private int currentAcquireSemaphore;
	@Unique private com.mojang.renderpearl.api.commands.GpuFence[] reflexium$acquireCompletion;
	@Unique private GpuTextureView reflexium$realView;
	@Unique private boolean reflexium$presenting;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void reflexium$trackAcquireCompletion(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		reflexium$acquireCompletion = new com.mojang.renderpearl.api.commands.GpuFence[acquireSemaphores.length];
	}
	@Unique private void reflexium$blit(CommandEncoderBackend encoder, GpuTextureView view, Operation<Void> original) {
		original.call(encoder, view);
		// This fence covers the submission consuming this binary acquire signal, independent of FG
		// multiplier or ring size. RenderPearl's timeline wait takes nanoseconds despite its parameter name.
		reflexium$acquireCompletion[currentAcquireSemaphore] = encoder.createFence();
	}

	@WrapMethod(method = "blitFromTexture")
	private void reflexium$firstGenerated(CommandEncoderBackend encoder, GpuTextureView view, Operation<Void> original) {
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		if (FsrSwapchain.owns(swapchain) || reflexium$presenting || fg.generatedCount() == 0) { reflexium$blit(encoder, view, original); return; }
		reflexium$realView = view;
		reflexium$blit(encoder, fg.generatedView(0), original);
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
				if (fg.batchRejected()) i = count; // valid swapchain: abandon remaining interpolation and deliver the real image
				surface.acquireNextTexture();
				fg.beginPresent(i < count);
				var reflex = com.bambooz.reflexium.reflex.ReflexController.get();
				reflex.beginReplacementSubmit();
				CommandEncoderBackend encoder = device.createCommandEncoder();
				surface.blitFromTexture(encoder, i < count ? fg.generatedView(i) : reflexium$realView);
				encoder.submit();
				reflex.endReplacementSubmit();
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
		reflexium$realView = null;
		VulkanLifecycle.onSwapchainDestroyed();
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int reflexium$tagPresent(org.lwjgl.vulkan.VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		if (FsrSwapchain.owns(swapchain)) {
			int result = FsrSwapchain.present(info);
			FrameGenerationRenderer.get().onProxyPresentResult(result);
			return result;
		}
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		info = fg.chainMetering(info);
		if (!fg.isGeneratedPresent()) info = VulkanLifecycle.chainPresentId(info);
		com.bambooz.reflexium.reflex.ReflexController reflex = com.bambooz.reflexium.reflex.ReflexController.get();
		if (fg.inPresentBatch()) reflex.beforeBatchPresent(fg.isGeneratedPresent());
		int result;
		long timing = com.bambooz.reflexium.debug.FrameProfiler.start(com.bambooz.reflexium.debug.FrameProfiler.PRESENT, null);
		try { result = original.call(queue, info); }
		finally {
			com.bambooz.reflexium.debug.FrameProfiler.end(com.bambooz.reflexium.debug.FrameProfiler.PRESENT, timing, null);
			if (fg.inPresentBatch()) reflex.afterBatchPresent(fg.isGeneratedPresent());
		}
		fg.onPresentResult(info, result);
		long id = reflex.currentFrameId();
		if (com.bambooz.reflexium.vulkan.FrameTrace.enabled(id)) com.bambooz.reflexium.vulkan.FrameTrace.event(id,
				"present role=" + (fg.isGeneratedPresent() ? "generated" : "real") + " result=" + result + " feedback=" + fg.meteringFeedbackRaw());
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
}
