package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.fsr.FsrSwapchain;
import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.bambooz.reflexium.framegen.GenerationLedger;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.vulkan.FrameTrace;
import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

/**
 * Streamline owns the Vulkan swapchain and present path when it is loaded. Each real present is
 * tagged with VkPresentIdKHR so Reflex can pair its markers with presented frames.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	@WrapMethod(method = "acquireNextTexture")
	private void reflexium$timeAcquire(Operation<Void> original) {
		int next = (currentAcquireSemaphore + 1) % acquireSemaphores.length;
		var completion = reflexium$acquireCompletion[next];
		if (completion != null) {
			// Outside ACQUIRE so that span keeps meaning the acquire call alone.
			long wait = FrameProfiler.start(FrameProfiler.ACQUIRE_REUSE_WAIT, null);
			try { if (!completion.awaitCompletion(5_000_000_000L)) throw new IllegalStateException("Acquire semaphore's previous wait has not completed"); }
			finally { FrameProfiler.end(FrameProfiler.ACQUIRE_REUSE_WAIT, wait, null); }
			completion.close(); reflexium$acquireCompletion[next] = null;
		}
		long timing = FrameProfiler.start(FrameProfiler.ACQUIRE, null);
		try { original.call(); }
		finally { FrameProfiler.end(FrameProfiler.ACQUIRE, timing, null); }
	}
	@Shadow private long swapchain;
	@Shadow @Final private VulkanDevice device;
	@Shadow private SurfaceException eatenException;
	@Shadow private boolean swapchainOutOfDate;
	@Shadow @Final @Mutable private long[] acquireSemaphores;
	@Shadow private int currentAcquireSemaphore;
	@Unique private GpuFence[] reflexium$acquireCompletion;
	@Unique private GpuTextureView reflexium$realView;
	@Unique private boolean reflexium$presenting;
	@Unique private boolean reflexium$streamlineSwapchain;

	/**
	 * Streamline's manual Vulkan path requires the window surface to be created through its proxy so
	 * the present hook can map the surface to the HWND and evaluate focus. SDL creates the surface via
	 * the loader, so substitute the interposer call while leaving everything else to SDL.
	 */
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/sdl/SDLVulkan;SDL_Vulkan_CreateSurface(JLorg/lwjgl/vulkan/VkInstance;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)Z"))
	private boolean reflexium$createStreamlineSurface(long window, VkInstance instance, VkAllocationCallbacks allocator,
			LongBuffer surface, Operation<Boolean> original) {
		if (StreamlineSupport.initialized()) {
			int properties = SDLVideo.SDL_GetWindowProperties(window);
			long hwnd = properties == 0 ? 0
					: SDLProperties.SDL_GetPointerProperty(properties, "SDL.window.win32.hwnd", 0L);
			if (hwnd != 0) {
				int result = StreamlineSupport.createWin32Surface(instance.address(), hwnd, MemoryUtil.memAddress(surface));
				if (result == VK10.VK_SUCCESS) return true;
				ReflexiumClient.LOGGER.warn("Streamline surface creation failed ({}); using the SDL surface", result);
			}
		}
		return original.call(window, instance, allocator, surface);
	}

	@WrapOperation(method = "close", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSurface;vkDestroySurfaceKHR(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
	private void reflexium$destroyStreamlineSurface(VkInstance instance, long surface, VkAllocationCallbacks allocator,
			Operation<Void> original) {
		if (StreamlineSupport.ownsSurface(surface)) {
			StreamlineSupport.destroySurface(instance.address(), surface, allocator == null ? 0 : allocator.address());
			return;
		}
		original.call(instance, surface, allocator);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void reflexium$trackAcquireCompletion(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		var session = VulkanSession.current();
		if (session != null && session.isNvidia()) {
			// Two real frames in flight, each with at most eight physical presents. Completion tracking below,
			// rather than ring size, proves binary semaphore reuse safe. Avoid waiting mid-batch on the world's submit.
			long[] expanded = Arrays.copyOf(acquireSemaphores, 16);
			int created = acquireSemaphores.length;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				LongBuffer out = stack.mallocLong(1);
				var info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
				for (; created < expanded.length; created++) {
					int result = VK12.vkCreateSemaphore(device.vkDevice(), info, null, out);
					if (result != VK12.VK_SUCCESS) throw new IllegalStateException("FG acquire semaphore: " + result);
					expanded[created] = out.get(0);
				}
				acquireSemaphores = expanded;
			} catch (RuntimeException failure) {
				for (int i = acquireSemaphores.length; i < created; i++) VK12.vkDestroySemaphore(device.vkDevice(), expanded[i], null);
				ReflexiumClient.LOGGER.warn("Using native acquire ring after FG allocation failure", failure);
			}
		}
		reflexium$acquireCompletion = new GpuFence[acquireSemaphores.length];
	}
	@Unique private void reflexium$blit(CommandEncoderBackend encoder, GpuTextureView view, Operation<Void> original) {
		original.call(encoder, view);
		// This fence covers the submission consuming this binary acquire signal, independent of FG
		// multiplier or ring size. RenderPearl's timeline wait takes nanoseconds despite its parameter name.
		reflexium$acquireCompletion[currentAcquireSemaphore] = encoder.createFence();
	}
	// Surface blits use a separate transient command buffer, which blitFromTexture ends before returning.
	@WrapOperation(method = "blitFromTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"))
	private void reflexium$timeBlit(VkCommandBuffer commands, long source, int sourceLayout,
			long destination, int destinationLayout, VkImageBlit.Buffer regions, int filter, Operation<Void> original) {
		long timing = FrameProfiler.start(FrameProfiler.FINAL_BLIT, commands);
		try { original.call(commands, source, sourceLayout, destination, destinationLayout, regions, filter); }
		finally { FrameProfiler.end(FrameProfiler.FINAL_BLIT, timing, commands); }
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
		try {
			fg.awaitPreviousReplacements();
			fg.beginPresentBatch(count + 1);
			fg.beginPresent(true);
			original.call();
			for (int i = 1; i <= count && !swapchainOutOfDate; i++) {
				if (fg.batchRejected()) { i = count; fg.noteShortfall(GenerationLedger.Shortfall.BATCH_ABANDONED); } // valid swapchain: abandon remaining interpolation and deliver the real image
				surface.acquireNextTexture();
				fg.beginPresent(i < count);
				var reflex = ReflexController.get();
				reflex.beginReplacementSubmit();
				CommandEncoderBackend encoder = fg.presentEncoder(i - 1);
				surface.blitFromTexture(encoder, i < count ? fg.generatedView(i) : reflexium$realView);
				fg.trackReplacement(i - 1);
				encoder.submit();
				reflex.endReplacementSubmit();
				original.call();
			}
		} catch (SurfaceException resized) {
			eatenException = resized;
			fg.invalidate();
			fg.noteShortfall(GenerationLedger.Shortfall.SURFACE_LOST);
		} finally {
			if (swapchainOutOfDate) fg.noteShortfall(GenerationLedger.Shortfall.SURFACE_LOST);
			// The batch's real image is its last present; a lost surface can end the batch before it.
			fg.endPresentBatch();
			reflexium$realView = null;
			reflexium$presenting = false;
		}
	}

	@ModifyArg(method = "configure", index = 1, at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private VkSwapchainCreateInfoKHR reflexium$prepareSwapchain(VkSwapchainCreateInfoKHR info) {
		return VulkanLifecycle.prepareSwapchain(info);
	}

	@Unique private static boolean reflexium$loggedSurfaceFormats;
	/**
	 * SDR surfaces usually expose only 8-bit UNORM formats. When a 10-bit format is offered, select it so
	 * the pack's dither survives presentation instead of being quantized to 8 bits after frame generation.
	 * Opt in with -Dreflexium.tenBit=true while this path is being measured.
	 */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("RETURN"), cancellable = true)
	private void reflexium$preferTenBit(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		if (formats == null) return;
		if (!reflexium$loggedSurfaceFormats) {
			reflexium$loggedSurfaceFormats = true;
			StringBuilder available = new StringBuilder();
			for (VkSurfaceFormatKHR format : formats) {
				if (available.length() > 0) available.append(", ");
				available.append(format.format()).append('/').append(format.colorSpace());
			}
			ReflexiumClient.LOGGER.info("Vulkan surface formats: {}", available);
		}
		boolean tenBit = Boolean.getBoolean("reflexium.tenBit")
				|| com.bambooz.reflexium.upscale.dlss.StreamlineSupport.initialized()
				&& com.bambooz.reflexium.config.ReflexiumConfig.get().frameGeneration().nvidia();
		if (!tenBit) return;
		for (VkSurfaceFormatKHR format : formats) {
			if (format.colorSpace() == 0 && format.format() == VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
				cir.setReturnValue(format);
				return;
			}
		}
		ReflexiumClient.LOGGER.info("Vulkan surface has no A2B10G10R10 UNORM format; frame generation output stays 8-bit");
	}

	/** Guide 17.0: disable frame generation and drop latency markers before any surface change. */
	@Inject(method = "configure", at = @At("HEAD"))
	private void reflexium$prepareSurfaceChange(GpuSurface.Configuration configuration, CallbackInfo ci) {
		FrameGenerationRenderer.get().beforeSurfaceChange();
		ReflexController.get().beforeSurfaceChange();
	}

	@Inject(method = "configure", at = @At("TAIL"))
	private void reflexium$swapchainCreated(GpuSurface.Configuration configuration, CallbackInfo ci) {
		if (!FsrSwapchain.owns(swapchain)) VulkanLifecycle.onSwapchainCreated(swapchain);
	}

	@Inject(method = "destroySwapchain", at = @At("HEAD"))
	private void reflexium$swapchainDestroyed(CallbackInfo ci) {
		reflexium$realView = null;
		VulkanLifecycle.onSwapchainDestroyed();
		// Guide 18.0: the DLSS-G load/unload transition happens while no swapchain exists.
		StreamlineSupport.onSwapchainDestroyed();
	}

	/**
	 * The flag must stay set while the wrapped vkDestroySwapchainKHR runs, otherwise the proxy
	 * swapchain is passed to the raw driver entry point and DLSS-G keeps its internal swapchain.
	 * The next creation is then rejected as a second swapchain, presentCommon() stops running and
	 * Reflex sleeps on a presented state that never arrives.
	 */
	@Inject(method = "destroySwapchain", at = @At("TAIL"))
	private void reflexium$swapchainDestroyedTail(CallbackInfo ci) {
		reflexium$streamlineSwapchain = false;
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int reflexium$tagPresent(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		if (FsrSwapchain.owns(swapchain)) {
			int result = FsrSwapchain.present(info);
			FrameGenerationRenderer.get().onProxyPresentResult(result);
			return result;
		}
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		if (!fg.isGeneratedPresent()) info = VulkanLifecycle.chainPresentId(info);
		// slDLSSGGetState is read after this present, once its markers have been processed.
		ReflexController reflex = ReflexController.get();
		if (fg.inPresentBatch()) reflex.beforeBatchPresent();
		int result;
		long timing = FrameProfiler.start(FrameProfiler.PRESENT, null);
		try { result = reflexium$streamlineSwapchain
				? StreamlineSupport.present(queue.address(), info.address())
				: original.call(queue, info); }
		finally {
			FrameProfiler.end(FrameProfiler.PRESENT, timing, null);
			if (fg.inPresentBatch()) reflex.afterBatchPresent();
		}
		fg.onPresentResult(info, result);
		long id = reflex.currentFrameId();
		if (FrameTrace.enabled(id)) FrameTrace.event(id,
				"present role=" + (fg.isGeneratedPresent() ? "generated" : "real") + " result=" + result);
		return result;
	}

	@WrapOperation(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int reflexium$fsrCreate(VkDevice device, VkSwapchainCreateInfoKHR info, VkAllocationCallbacks allocator,
			LongBuffer out, Operation<Integer> original) {
		if (StreamlineSupport.swapchainReady()) {
			int result = StreamlineSupport.createSwapchain(device.address(), info.address(),
					allocator == null ? 0 : allocator.address(), MemoryUtil.memAddress(out));
			if (result == VK10.VK_SUCCESS) { reflexium$streamlineSwapchain = true; return result; }
			reflexium$streamlineSwapchain = false;
			ReflexiumClient.LOGGER.warn("Streamline swapchain creation failed ({}); using the native swapchain", result);
			return original.call(device, info, allocator, out);
		}
		reflexium$streamlineSwapchain = false;
		if (FsrSwapchain.canCreate(info)) {
			int result = FsrSwapchain.create(info, allocator, out);
			if (result == VK10.VK_SUCCESS) return result;
			ReflexiumClient.LOGGER.warn("FSR swapchain creation failed ({}); using vanilla presentation", result);
		}
		return original.call(device, info, allocator, out);
	}
	/** The proxy handle is not a driver swapchain: acquisition and destruction must go through the SDK too. */
	@WrapOperation(method = "acquireNextTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int reflexium$fsrAcquire(VkDevice device, long swapchain, long timeout, long semaphore, long fence,
			IntBuffer index, Operation<Integer> original) {
		if (reflexium$streamlineSwapchain) {
			return StreamlineSupport.acquireNextImage(device.address(), swapchain, timeout, semaphore, fence, MemoryUtil.memAddress(index));
		}
		return FsrSwapchain.owns(swapchain) ? FsrSwapchain.acquire(timeout, semaphore, fence, index) : original.call(device, swapchain, timeout, semaphore, fence, index);
	}
	@WrapOperation(method = "destroySwapchain", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
	private void reflexium$fsrDestroy(VkDevice device, long swapchain, VkAllocationCallbacks allocator, Operation<Void> original) {
		if (reflexium$streamlineSwapchain) {
			StreamlineSupport.destroySwapchain(device.address(), swapchain, allocator == null ? 0 : allocator.address());
		} else if (FsrSwapchain.owns(swapchain)) FsrSwapchain.destroy(allocator);
		else original.call(device, swapchain, allocator);
	}
	@WrapOperation(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"))
	private int reflexium$fsrImages(VkDevice device, long swapchain, IntBuffer count,
			LongBuffer images, Operation<Integer> original) {
		if (reflexium$streamlineSwapchain) {
			return StreamlineSupport.getSwapchainImages(device.address(), swapchain, MemoryUtil.memAddress(count),
					images == null ? 0 : MemoryUtil.memAddress(images));
		}
		return FsrSwapchain.owns(swapchain) ? FsrSwapchain.images(count, images) : original.call(device, swapchain, count, images);
	}
}
