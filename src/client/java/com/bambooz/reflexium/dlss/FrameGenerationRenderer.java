package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Owns interpolation images and full-resolution GUI composition. No generated image advances simulation. */
public final class FrameGenerationRenderer {
	private static final FrameGenerationRenderer INSTANCE = new FrameGenerationRenderer();
	private final MemorySegment constants = Arena.global().allocate(FrameGenerationConstants.FLOATS * 4L, 4);
	private VulkanDevice device;
	private VulkanSession session;
	private NgxBridge ngx;
	private FsrBridge fsr;
	private NgxBridge.FrameGenerationSupport support;
	private FrameGeneration built = FrameGeneration.OFF;
	private OutputImage[] outputs = new OutputImage[0];
	private TextureTarget[] generated = new TextureTarget[0];
	private boolean failed, resetNext = true;
	private int ready, width, height;
	private long frameId, evaluated, presented;
	private String problem = "";
	private int presentBatch, presentIndex;
	private boolean generatedPresent;
	private int lastMeteringFeedback;

	public static FrameGenerationRenderer get() { return INSTANCE; }

	void onDeviceCreated(VulkanDevice device, VulkanSession session, FsrBridge fsr) {
		this.device = device; this.session = session; this.fsr = fsr;
		retry();
		if (fsr != null) FsrSwapchain.onDeviceCreated(device, session);
	}
	void onNgxReady(NgxBridge bridge) {
		ngx = bridge; support = bridge.frameGenerationSupport();
		ReflexiumClient.LOGGER.info("DLSS frame generation on Minecraft device: {}", support);
	}
	void onDeviceClosing() {
		release(); device = null; session = null; fsr = null; ngx = null; support = null;
	}

	public int maximumMultiplier() { return support != null && support.available() ? Math.min(8, support.maxGenerated() + 1) : 1; }
	public boolean wantsMotion() { return ReflexiumConfig.get().frameGeneration() != FrameGeneration.OFF && !failed && unavailableReason().isEmpty(); }
	public boolean needsReflex() { return wantsMotion() && ReflexiumConfig.get().frameGeneration().nvidia(); }
	public void retry() { failed = false; problem = ""; resetNext = true; }
	public void invalidate() { ready = 0; resetNext = true; }

	private String unavailableReason() {
		FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
		if (mode == FrameGeneration.OFF) return "";
		if (session == null) return "needs Windows x64 and Vulkan";
		if (mode == FrameGeneration.FSR && fsr == null) return "FSR runtime unavailable";
		if (mode == FrameGeneration.FSR && FsrSwapchain.active()) return "";
		if (!session.presentMetering() || FsrSwapchain.active()) return "presentation scheduler unavailable";
		if (Minecraft.getInstance().options.enableVsync().get()) return "disable VSync to enable Vulkan frame generation";
		if (mode == FrameGeneration.FSR) return "";
		if (support == null || !support.available()) return "DLSS Frame Generation unavailable on this GPU/runtime";
		if (mode == FrameGeneration.DLSS_MULTI && maximumMultiplier() < 3) return "Multi Frame Generation requires a supported RTX 50 series GPU/runtime";
		if (!session.lowLatency2() || !session.presentId()) return "DLSS Frame Generation needs Reflex present attribution";
		return "";
	}

	void beginFrame() {
		frameId++;
		ready = 0;
		if (!wantsMotion() || Minecraft.getInstance().gui.screen() != null) resetNext = true;
	}

	void sceneReady(RenderTarget scene, RenderTarget motion, VkCommandBuffer commands, Matrix4fc projection,
			Matrix4fc clipToPrevious, Quaternionfc orientation, float x, float y, float z, float jitterX, float jitterY,
			float near, float far, float fov, float frameMs, boolean reset) {
		if (!wantsMotion() || Minecraft.getInstance().gui.screen() != null) return;
		try {
			FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
			int count = mode == FrameGeneration.DLSS_MULTI ? Math.min(ReflexiumConfig.get().frameGenerationMultiplier(), maximumMultiplier()) - 1 : 1;
			if (built != mode || outputs.length != count || width != scene.width || height != scene.height) {
				UpscalingRenderer.get().waitForGpu();
				release();
				commands = UpscalingRenderer.get().commandBuffer();
				width = scene.width; height = scene.height;
				outputs = new OutputImage[count]; generated = new TextureTarget[count];
				for (int i = 0; i < count; i++) {
					outputs[i] = new OutputImage(device, commands, width, height);
					generated[i] = new TextureTarget("Reflexium generated frame " + i, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
				}
				int result = mode == FrameGeneration.FSR ? fsr.createFrameGeneration(session.device().address(), session.physicalDevice().address(),
						VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr"), motion.width, motion.height, width, height)
						: ngx.createFrameGeneration(commands.address(), width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM);
				check(result, "creation");
				built = mode;
				resetNext = true;
			}
			FrameGenerationConstants.write(constants, projection, clipToPrevious, orientation, x, y, z, jitterX, jitterY,
					near, far, fov, (float) width / height, frameMs);
			boolean resetting = reset || resetNext;
			UpscalingRenderer.barrier(commands);
			NgxBridge.Image color = UpscalingRenderer.ngxImage(scene.getColorTexture(), scene.getColorTextureView());
			NgxBridge.Image depth = UpscalingRenderer.ngxImage(motion.getDepthTexture(), motion.getDepthTextureView());
			NgxBridge.Image vectors = UpscalingRenderer.ngxImage(motion.getColorTexture(), motion.getColorTextureView());
			for (int i = 0; i < count; i++) {
				int result = mode == FrameGeneration.FSR ? fsr.evaluateFrameGeneration(commands.address(), color, depth, vectors,
						outputs[i].asNgx(), constants, frameId, resetting)
						: ngx.evaluateFrameGeneration(commands.address(), color, depth, vectors, outputs[i].asNgx(), constants, frameId, count, i + 1, resetting);
				check(result, "evaluation");
				UpscalingRenderer.barrier(commands);
				copy(commands, outputs[i], generated[i]);
			}
			UpscalingRenderer.barrier(commands);
			evaluated++;
			resetNext = false;
			if (!resetting) ready = count;
		} catch (RuntimeException e) {
			failed = true; invalidate(); problem = e.getMessage();
			try { UpscalingRenderer.get().waitForGpu(); release(); }
			catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
			ReflexiumClient.LOGGER.error("Frame generation stopped; continuing with rendered frames", e);
			if (ngx != null) ReflexiumClient.LOGGER.info("[NGX] {}", ngx.takeLog());
		}
	}

	/** Replays already prepared GUI draws against each scene, preserving destination-dependent blends such as the crosshair. */
	public void drawGuiCopies(GameRenderer renderer, Runnable draw) {
		for (int i = 0; i < ready; i++) {
			RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(generated[i].getDepthTexture(), 0);
			UpscalingRenderer.swap(renderer.mainRenderTarget(), generated[i]);
			try { draw.run(); }
			finally { UpscalingRenderer.swap(renderer.mainRenderTarget(), generated[i]); }
		}
	}

	public int generatedCount() { return ready; }
	public void scheduleProxyPresent() {
		if (FsrSwapchain.active() && built == FrameGeneration.FSR) {
			FsrSwapchain.schedule(ready == 0 ? null : UpscalingRenderer.ngxImage(generated[0].getColorTexture(), generated[0].getColorTextureView()), frameId);
		}
	}
	public GpuTextureView generatedView(int index) { return generated[index].getColorTextureView(); }
	public long evaluatedFrames() { return evaluated; }
	public long generatedPresents() { return presented; }
	public RenderTarget debugTarget(int index) {
		if (index < 0 || index >= generated.length) throw new IllegalArgumentException("No generated frame at index " + index);
		return generated[index];
	}
	public int meteringFeedback() { return lastMeteringFeedback; }
	public String statusLine() {
		FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
		if (mode == FrameGeneration.OFF) return "Frame generation: off";
		String reason = failed ? problem : unavailableReason();
		return "Frame generation: " + (reason.isEmpty() ? mode.label().getString()
				+ (resetNext ? " (waiting for consecutive world frames)" : mode == FrameGeneration.DLSS_MULTI ? " " + (outputs.length + 1) + "x" : "") : reason);
	}

	public void beginPresentBatch(int frames) { presentBatch = frames; presentIndex = 0; }
	public boolean inPresentBatch() { return presentBatch > 1; }
	public void beginPresent(boolean generated) { generatedPresent = generated; }
	public boolean isGeneratedPresent() { return generatedPresent; }
	public void endPresentBatch() { presentBatch = 0; generatedPresent = false; ready = 0; }
	public VkPresentInfoKHR chainMetering(VkPresentInfoKHR info) {
		if (presentBatch <= 1 || presentIndex++ != 0) return info;
		VkSetPresentConfigNV metering = VkSetPresentConfigNV.calloc(MemoryStack.stackGet()).sType$Default()
				.numFramesPerBatch(presentBatch).pNext(info.pNext());
		return info.pNext(metering.address());
	}
	public void onPresentResult(VkPresentInfoKHR info, int result) {
		if (result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
			if (generatedPresent) presented++;
		}
		if (presentBatch > 1 && presentIndex == 1) {
			long next = info.pNext();
			while (next != 0) {
				VkBaseOutStructure header = VkBaseOutStructure.create(next);
				if (header.sType() == NVPresentMetering.VK_STRUCTURE_TYPE_SET_PRESENT_CONFIG_NV) {
					lastMeteringFeedback = VkSetPresentConfigNV.create(next).presentConfigFeedback(); break;
				}
				next = header.pNext() == null ? 0 : header.pNext().address();
			}
		}
	}

	void release() {
		invalidate();
		if (ngx != null) ngx.releaseFrameGeneration();
		if (fsr != null) fsr.releaseFrameGeneration();
		for (OutputImage output : outputs) if (output != null) output.destroy();
		for (TextureTarget target : generated) if (target != null) target.destroyBuffers();
		outputs = new OutputImage[0]; generated = new TextureTarget[0]; built = FrameGeneration.OFF;
	}
	private static void check(int result, String operation) {
		if (result != 0) throw new IllegalStateException("FG " + operation + " failed: 0x" + Integer.toHexString(result));
	}
	private static void copy(VkCommandBuffer commands, OutputImage source, TextureTarget target) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.extent().set(target.width, target.height, 1);
			VK10.vkCmdCopyImage(commands, source.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
					((VulkanGpuTexture) target.getColorTexture()).vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, region);
		}
	}
}
