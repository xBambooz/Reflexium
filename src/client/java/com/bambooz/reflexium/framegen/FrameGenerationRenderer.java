package com.bambooz.reflexium.framegen;

import com.bambooz.reflexium.vulkan.VulkanInterop;
import com.bambooz.reflexium.vulkan.ImageRef;
import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.upscale.dlss.NgxBridge;
import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.bambooz.reflexium.upscale.fsr.FsrBridge;
import com.bambooz.reflexium.upscale.fsr.FsrSwapchain;
import com.bambooz.reflexium.vulkan.FrameTrace;
import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Owns interpolation images and full-resolution GUI composition. No generated image advances simulation. */
public final class FrameGenerationRenderer {
	private static final FrameGenerationRenderer INSTANCE = new FrameGenerationRenderer();
	private static final Logger LOG = LoggerFactory.getLogger("Reflexium");
	/** Seeded into presentConfigFeedback. Surviving unchanged means the driver did not report, not success or rejection. */
	public static final int FEEDBACK_UNREPORTED = Integer.MAX_VALUE;
	private enum MeteringConfig { NONE, RESET, BATCH }
	private final MemorySegment constants = Arena.global().allocate(FrameGenerationConstants.FLOATS * 4L, 4);
	private VulkanDevice device;
	private VulkanSession session;
	private NgxBridge ngx;
	private FsrBridge fsr;
	private NgxBridge.FrameGenerationSupport support;
	private FrameGeneration built = FrameGeneration.OFF;
	private TextureTarget[] generated = new TextureTarget[0];
	private TextureTarget hudlessTarget, uiColorAlphaTarget, uiAlphaFlippedTarget;
	private TextureTarget fgDepthTarget, fgMotionTarget;
	private com.bambooz.reflexium.upscale.DepthResolvePass fgDepthFlip;
	private RenderTarget streamlineMotion;
	private boolean streamlineActive;
	private int lastStreamlinePresentResult;
	private long streamlineGeneratedPresents;
	private int streamlineNoGenerateLogs;
	private int streamlineNoGenerateFrames;
	private boolean streamlineUnfocused;
	private String streamlineProblem = "";
	private final VulkanCommandEncoder[] presentEncoders = new VulkanCommandEncoder[7];
	private final GpuFence[] presentCompletions = new GpuFence[7];
	private boolean failed, resetNext = true;
	private int pendingWorldFrames, resetsInWindow, resetsPerSecond;
	private long resetWindow;
	public int resetsPerSecond() { rollResetWindow(System.nanoTime()); return resetsPerSecond; }
	private void rollResetWindow(long now) {
		if (now - resetWindow >= 1_000_000_000L) {
			resetsPerSecond = now - resetWindow < 2_000_000_000L ? resetsInWindow : 0;
			resetsInWindow = 0; resetWindow = now;
		}
	}
	private int ready, width, height;
	private long frameId, evaluated, presented;
	private long realPresented;
	private final PresentationStats delivery = new PresentationStats();
	private String problem = "";
	private int presentBatch, presentIndex;
	private boolean generatedPresent;
	private int lastMeteringFeedback = FEEDBACK_UNREPORTED, lastMeteringResult;
	private boolean batchRejected, resetMetering;
	private MeteringConfig chained = MeteringConfig.NONE;
	private long batchConfigs, resetConfigs, feedbackUnreported, feedbackStatus, feedbackAccepted, feedbackRejected, rejectionStreak;
	private long batchesStarted, batchesCompleted, configLogs;
	private String meteringProblem = "";
	private long fsrFrameId;
	private final GenerationLedger ledger = new GenerationLedger();
	private final FrameGenerationRefreshPolicy refreshPolicy = new FrameGenerationRefreshPolicy();
	public FrameGenerationRefreshPolicy refreshPolicy() { return refreshPolicy; }

	public static FrameGenerationRenderer get() { return INSTANCE; }

	public void onDeviceCreated(VulkanDevice device, VulkanSession session, FsrBridge fsr) {
		this.device = device; this.session = session; this.fsr = fsr;
		resetMetering = false; presentBatch = 0; generatedPresent = false;
		streamlineActive = false;
		if (session != null && session.streamline()) {
			StreamlineSupport.refreshFrameGenerationState();
			StreamlineSupport.invalidateFrameGenerationOptions();
			ReflexiumConfig.get().limitFrameGenerationMultiplier(maximumMultiplier());
			ReflexiumClient.LOGGER.info("Streamline frame generation: {}", StreamlineSupport.status());
		}
		retry();
		if (fsr != null) FsrSwapchain.onDeviceCreated(device, session);
	}
	public void onNgxReady(NgxBridge bridge) {
		ngx = bridge; support = bridge.frameGenerationSupport();
		ReflexiumConfig.get().limitFrameGenerationMultiplier(maximumMultiplier());
		ReflexiumClient.LOGGER.info("DLSS frame generation on Minecraft device: {}", support);
	}
	public void onDeviceClosing() {
		release();
		// Surface acquire fences can retain these encoders across FG toggles. Keep them until device teardown.
		for (int i = 0; i < presentEncoders.length; i++) {
			if (presentCompletions[i] != null) presentCompletions[i].close();
			presentCompletions[i] = null;
			if (presentEncoders[i] != null) presentEncoders[i].destroy();
			presentEncoders[i] = null;
		}
		device = null; session = null; fsr = null; ngx = null; support = null;
	}

	public int maximumMultiplier() {
		if (streamlinePath()) return StreamlineSupport.maximumMultiplier();
		return support != null && support.available() ? Math.min(8, support.maxGenerated() + 1) : 1;
	}
	public boolean isDlssFgSupported() {
		if (session == null || !session.isNvidia()) return false;
		if (session.streamline()) return StreamlineSupport.dlssgAvailable();
		return support != null && support.available();
	}
	public boolean isDlssMfgSupported() { return isDlssFgSupported() && maximumMultiplier() >= 3; }
	public boolean wantsMotion() { return ReflexiumConfig.get().frameGeneration() != FrameGeneration.OFF && !failed && unavailableReason().isEmpty(); }
	public boolean needsReflex() {
		FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
		return mode != FrameGeneration.OFF && mode.nvidia() && !failed;
	}
	/** Streamline owns interpolated presents for this frame; no app-side generated targets are used. */
	public boolean streamlineFrameGeneration() { return streamlinePath(); }
	/**
	 * Frame generation owns Streamline's per-frame constants in this configuration. DLSS SR then runs
	 * on the raw NGX path: the two features need opposite Y orientations for Minecraft's presented
	 * backbuffer and Streamline allows only one common-constants set per frame.
	 */
	public boolean streamlineFrameGenerationActive() {
		return streamlinePath() && ReflexiumConfig.get().frameGeneration().nvidia();
	}
	public boolean streamlineActive() { return streamlineActive; }
	public long streamlineGeneratedPresents() { return streamlineGeneratedPresents; }
	public String streamlineProblem() { return streamlineProblem; }
	public int lastStreamlinePresentResult() { return lastStreamlinePresentResult; }
	private boolean streamlinePath() { return session != null && session.streamline() && StreamlineSupport.dlssgAvailable(); }
	public void onSwapchainCreated() {
		if (session != null && session.streamline()) {
			StreamlineSupport.refreshFrameGenerationState();
			StreamlineSupport.invalidateFrameGenerationOptions();
			resetNext = true;
		}
	}
	public void retry() {
		failed = false; problem = ""; streamlineProblem = ""; resetNext = true;
		streamlineUnfocused = false; streamlineNoGenerateFrames = 0;
		delivery.reset(); refreshPolicy.reset();
	}
	public void invalidate() { ready = 0; resetNext = true; delivery.reset(); disableStreamlineFrameGeneration(); }

	private String unavailableReason() {
		FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
		if (mode == FrameGeneration.OFF) return "";
		if (session == null) return "needs Windows x64 and Vulkan";
		if (mode == FrameGeneration.FSR && fsr == null) return "FSR runtime unavailable";
		if (mode == FrameGeneration.FSR && session.streamline()) return "FSR frame generation needs a launch without Streamline";
		if (mode == FrameGeneration.FSR && FsrSwapchain.active()) return "";
		if (Minecraft.getInstance().options.enableVsync().get()) return "disable VSync to enable Vulkan frame generation";
		if (session.streamline()) {
			// Streamline owns the presenter; raw VK_NV_present_metering and present IDs are not required.
			if (!StreamlineSupport.dlssgAvailable()) return "Streamline DLSS Frame Generation unavailable";
			if (!StreamlineSupport.reflexAvailable()) return "DLSS Frame Generation needs Streamline Reflex";
			if (mode == FrameGeneration.DLSS_MULTI && maximumMultiplier() < 3) return "Multi Frame Generation requires a supported RTX 50 series GPU/runtime";
			int status = StreamlineSupport.frameGenerationStatus();
			if (status != 0) return "DLSS Frame Generation runtime status " + status;
			return "";
		}
		if (!session.presentMetering() || FsrSwapchain.active()) return "presentation scheduler unavailable";
		if (mode == FrameGeneration.FSR) return "";
		if (support == null || !support.available()) return "DLSS Frame Generation unavailable on this GPU/runtime";
		if (mode == FrameGeneration.DLSS_MULTI && maximumMultiplier() < 3) return "Multi Frame Generation requires a supported RTX 50 series GPU/runtime";
		if (!session.lowLatency2() || !session.presentId()) return "DLSS Frame Generation needs Reflex present attribution";
		return "";
	}

	public void beginFrame() {
		// Completion of SDK presentation consumers precedes overwriting the generated target.
		FsrSwapchain.waitForPresents();
		streamlineActive = false;
		frameId++;
		Minecraft mc = Minecraft.getInstance();
		boolean worldFrame = mc.level != null && mc.gui.screen() == null && !mc.isPaused();
		pendingWorldFrames = resetNext && worldFrame ? pendingWorldFrames + 1 : 0;
		ready = 0;
		if (streamlinePath() && (!worldFrame || !wantsMotion())) disableStreamlineFrameGeneration();
		ledger.begin(configuredGeneratedCount(), failed || !unavailableReason().isEmpty() ? GenerationLedger.Shortfall.UNAVAILABLE : GenerationLedger.Shortfall.NOT_EVALUATED);
		if (!wantsMotion()) resetNext = true;
	}

	/**
	 * Generated presents each real frame should deliver for the requested NVIDIA mode; 0 when off or on the
	 * FSR proxy route. A fixed multiplier is the user's explicit request, so it is used directly and never
	 * throttled. Auto delegates to the refresh-output policy, which picks a multiplier only when the
	 * measured real rate is below the target refresh.
	 */
	public int configuredGeneratedCount() {
		if (!ReflexiumConfig.get().frameGeneration().nvidia()) return 0;
		int requested = ReflexiumConfig.get().frameGenerationMultiplier();
		refreshPolicy.configure(requested, maximumMultiplier());
		refreshPolicy.force(requested != 0);
		return Math.max(0, refreshPolicy.multiplier() - 1);
	}
	public int requestedGeneratedCount() {
		FrameGeneration requested = ReflexiumConfig.get().frameGeneration();
		if (!requested.nvidia()) return 0;
		int multiplier = ReflexiumConfig.get().frameGenerationMultiplier();
		return multiplier == 0 ? Math.max(1, maximumMultiplier() - 1) : Math.max(1, Math.min(multiplier, Math.max(2, maximumMultiplier())) - 1);
	}

	public void sceneReady(RenderTarget scene, RenderTarget motion, VkCommandBuffer commands, Matrix4fc projection,
			Matrix4fc clipToPrevious, Quaternionfc orientation, float x, float y, float z, float jitterX, float jitterY,
			float near, float far, float fov, float frameMs, boolean reset) {
		if (!wantsMotion()) return;
		Minecraft mc = Minecraft.getInstance();
		// Real-scene frames only: generated UI is a still panel and tagging it as gameplay wastes GPU time.
		if (mc.gui.screen() != null || mc.isPaused()) {
			ledger.reason(GenerationLedger.Shortfall.MENU);
			invalidate();
			return;
		}
		if (ReflexiumConfig.get().frameGeneration().nvidia() && !ReflexController.get().isSleeping()) {
			ledger.reason(GenerationLedger.Shortfall.REFLEX_NOT_SLEEPING);
			invalidate(); return;
		}
		try {
			FrameGeneration requested = ReflexiumConfig.get().frameGeneration();
			int count = requested.nvidia() ? configuredGeneratedCount() : 1;
			if (count < 1) { resetNext = true; return; }
			// DLSS MFG at 2x asks NGX for one generated frame, the same call as DLSS FG, so it shares that path and feature instance.
			FrameGeneration mode = requested.nvidia() ? (count == 1 ? FrameGeneration.DLSS : FrameGeneration.DLSS_MULTI) : requested;
			if (built != mode || generated.length != count || width != scene.width || height != scene.height) {
				UpscalingRenderer.get().waitForGpu();
				release();
				commands = UpscalingRenderer.get().commandBuffer();
				width = scene.width; height = scene.height;
				generated = new TextureTarget[count];
				for (int i = 0; i < count; i++) {
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
			rollResetWindow(System.nanoTime());
			if (resetting) resetsInWindow++;
			VulkanInterop.barrier(commands);
			ImageRef color = VulkanInterop.image(scene.getColorTexture(), scene.getColorTextureView());
			ImageRef depth = VulkanInterop.image(motion.getDepthTexture(), motion.getDepthTextureView());
			ImageRef vectors = VulkanInterop.image(motion.getColorTexture(), motion.getColorTextureView());
			long evaluationId = mode == FrameGeneration.FSR ? ++fsrFrameId : frameId;
			for (int i = 0; i < count; i++) {
				ImageRef outputImg = VulkanInterop.image(generated[i].getColorTexture(), generated[i].getColorTextureView());
				long timing = FrameProfiler.start(FrameProfiler.FG_EVALUATE, commands);
				int result = mode == FrameGeneration.FSR ? fsr.evaluateFrameGeneration(commands.address(), color, depth, vectors,
						outputImg, constants, evaluationId, resetting)
						: ngx.evaluateFrameGeneration(commands.address(), color, depth, vectors, outputImg, constants, frameId, count, i + 1, resetting);
				FrameProfiler.end(FrameProfiler.FG_EVALUATE, timing, commands);
				check(result, "evaluation");
				VulkanInterop.barrier(commands);
			}
			VulkanInterop.barrier(commands);
			evaluated++;
			resetNext = false;
			if (!resetting) { ready = count; ledger.reason(GenerationLedger.Shortfall.UNPRESENTED); }
			else ledger.reason(GenerationLedger.Shortfall.HISTORY_RESET);
		} catch (RuntimeException e) {
			failed = true; invalidate(); problem = e.getMessage(); ledger.reason(GenerationLedger.Shortfall.UNAVAILABLE);
			try { UpscalingRenderer.get().waitForGpu(); release(); }
			catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
			ReflexiumClient.LOGGER.error("Frame generation stopped; continuing with rendered frames", e);
			if (ngx != null) ReflexiumClient.LOGGER.info("[NGX] {}", ngx.takeLog());
		}
	}

	/**
	 * Streamline DLSS-G owns the generated presents. The application supplies display-resolution hudless
	 * color, depth, motion vectors and UI color/alpha, and one real present per frame.
	 */
	public void streamlineSceneReady(RenderTarget scene, RenderTarget motion, VkCommandBuffer commands,
			Matrix4fc projection, Matrix4fc clipToPrevious, Quaternionfc orientation, float x, float y, float z,
			float jitterX, float jitterY, float near, float far, float fov, float frameMs, boolean reset) {
		if (!streamlinePath()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null || mc.isPaused()) {
			ledger.reason(GenerationLedger.Shortfall.MENU);
			disableStreamlineFrameGeneration();
			return;
		}
		if (!ReflexController.get().isSleeping()) {
			ledger.reason(GenerationLedger.Shortfall.REFLEX_NOT_SLEEPING);
			disableStreamlineFrameGeneration();
			return;
		}
		FrameGeneration requested = ReflexiumConfig.get().frameGeneration();
		int count = requested.nvidia() ? configuredGeneratedCount() : 0;
		if (count < 1) { resetNext = true; disableStreamlineFrameGeneration(); return; }
		int maximum = maximumMultiplier();
		if (count < 1 || count + 1 > maximum) {
			streamlineProblem = "multiplier " + (count + 1) + " exceeds DLSS-G maximum " + maximum;
			ledger.reason(GenerationLedger.Shortfall.UNAVAILABLE);
			disableStreamlineFrameGeneration();
			return;
		}
		FrameGeneration mode = count == 1 ? FrameGeneration.DLSS : FrameGeneration.DLSS_MULTI;
		if (built != mode || hudlessTarget == null || uiColorAlphaTarget == null || uiAlphaFlippedTarget == null
				|| fgDepthTarget == null || fgMotionTarget == null || width != scene.width || height != scene.height
				|| fgMotionTarget.width != motion.width || fgMotionTarget.height != motion.height) {
			UpscalingRenderer.get().waitForGpu();
			releaseStreamlineTargets();
			width = scene.width;
			height = scene.height;
			hudlessTarget = new TextureTarget("Reflexium FG hudless", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			uiColorAlphaTarget = new TextureTarget("Reflexium FG UI", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			uiAlphaFlippedTarget = new TextureTarget("Reflexium FG UI flipped", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			fgDepthTarget = new TextureTarget("Reflexium FG depth flipped", motion.width, motion.height, null, GpuFormat.D32_FLOAT);
			fgMotionTarget = new TextureTarget("Reflexium FG motion flipped", motion.width, motion.height,
					motion.getColorTexture().getFormat(), GpuFormat.D32_FLOAT);
			built = mode;
			resetNext = true;
		}
		streamlineMotion = motion;
		boolean resetting = reset || resetNext;
		rollResetWindow(System.nanoTime());
		if (resetting) resetsInWindow++;
		try {
			StreamlineSupport.setConstants(projection, clipToPrevious, orientation, x, y, z, jitterX, jitterY,
					near, far, fov, (float) width / height, resetting, motion.width, motion.height, true);
			int optionResult = StreamlineSupport.setFrameGeneration(1, count, true);
			if (optionResult != 0) {
				streamlineProblem = "DLSS-G options failed " + Integer.toHexString(optionResult);
				ledger.reason(GenerationLedger.Shortfall.UNAVAILABLE);
				disableStreamlineFrameGeneration();
				return;
			}
			streamlineActive = true;
			streamlineProblem = "";
			evaluated++;
			resetNext = false;
			ready = 0;
			if (resetting) ledger.reason(GenerationLedger.Shortfall.HISTORY_RESET);
			else ledger.reason(GenerationLedger.Shortfall.UNPRESENTED);
		} catch (RuntimeException e) {
			failed = true; problem = e.getMessage(); ledger.reason(GenerationLedger.Shortfall.UNAVAILABLE);
			ReflexiumClient.LOGGER.error("Streamline frame generation stopped; continuing with rendered frames", e);
			disableStreamlineFrameGeneration();
			if (StreamlineSupport.initialized()) {
				String log = StreamlineSupport.problem();
				if (!log.isEmpty()) ReflexiumClient.LOGGER.info("[Streamline] {}", log);
			}
		}
	}

	/**
	 * Copies the finished scene (post effects and scene particles, before the GUI) into the hudless
	 * target and tags depth, motion vectors and hudless for this frame's present.
	 */
	public void streamlineHudless(RenderTarget scene, VkCommandBuffer commands) {
		if (!streamlineActive || hudlessTarget == null || fgDepthTarget == null || fgMotionTarget == null
				|| streamlineMotion == null || failed) return;
		long timing = FrameProfiler.start(FrameProfiler.FG_HUDLESS, commands);
		VulkanInterop.barrier(commands);
		// Minecraft's final swapchain blit mirrors Y. DLSS-G composes straight into that backbuffer, so
		// hudless, depth and motion must be mirrored into the same orientation before they are tagged.
		VulkanInterop.blitFlip(VulkanInterop.image(scene.getColorTexture(), scene.getColorTextureView()),
				VulkanInterop.image(hudlessTarget.getColorTexture(), hudlessTarget.getColorTextureView()), commands);
		if (fgDepthFlip == null) fgDepthFlip = new com.bambooz.reflexium.upscale.DepthResolvePass();
		fgDepthFlip.render(streamlineMotion.getDepthTextureView(), fgDepthTarget.getDepthTextureView(),
				streamlineMotion.width, streamlineMotion.height, 1.0f, 0.0f, 0.0f, 0.0f, true);
		VulkanInterop.barrier(commands);
		VulkanInterop.blitFlip(VulkanInterop.image(streamlineMotion.getColorTexture(), streamlineMotion.getColorTextureView()),
				VulkanInterop.image(fgMotionTarget.getColorTexture(), fgMotionTarget.getColorTextureView()), commands);
		VulkanInterop.barrier(commands);
		FrameProfiler.end(FrameProfiler.FG_HUDLESS, timing, commands);
		int result = StreamlineSupport.tagFrameGeneration(commands.address(),
				VulkanInterop.image(fgDepthTarget.getDepthTexture(), fgDepthTarget.getDepthTextureView()),
				VulkanInterop.image(fgMotionTarget.getColorTexture(), fgMotionTarget.getColorTextureView()),
				VulkanInterop.image(hudlessTarget.getColorTexture(), hudlessTarget.getColorTextureView()), null,
				StreamlineSupport.TAG_DEPTH | StreamlineSupport.TAG_MOTION | StreamlineSupport.TAG_HUDLESS);
		if (result != 0) {
			streamlineProblem = "DLSS-G input tags failed " + Integer.toHexString(result);
			ReflexiumClient.LOGGER.warn(streamlineProblem);
		}
	}

	/** Draws the GUI into a cleared UI alpha target so DLSS-G can recompose HUD pixels separately. */
	public void captureUiAlpha(GameRenderer renderer, Runnable draw) {
		if (!streamlineActive || uiColorAlphaTarget == null || uiAlphaFlippedTarget == null || failed) return;
		VkCommandBuffer commands = UpscalingRenderer.get().commandBuffer();
		long timing = FrameProfiler.start(FrameProfiler.FG_UI, commands);
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(uiColorAlphaTarget.getColorTexture(),
				new org.joml.Vector4f(0.0f, 0.0f, 0.0f, 0.0f), uiColorAlphaTarget.getDepthTexture(), 0.0);
		UpscalingRenderer.swap(renderer.mainRenderTarget(), uiColorAlphaTarget);
		try { draw.run(); }
		finally { UpscalingRenderer.swap(renderer.mainRenderTarget(), uiColorAlphaTarget); }
		VulkanInterop.barrier(commands);
		VulkanInterop.blitFlip(VulkanInterop.image(uiColorAlphaTarget.getColorTexture(), uiColorAlphaTarget.getColorTextureView()),
				VulkanInterop.image(uiAlphaFlippedTarget.getColorTexture(), uiAlphaFlippedTarget.getColorTextureView()), commands);
		VulkanInterop.barrier(commands);
		int result = StreamlineSupport.tagFrameGenerationUi(commands.address(),
				VulkanInterop.image(uiAlphaFlippedTarget.getColorTexture(), uiAlphaFlippedTarget.getColorTextureView()));
		FrameProfiler.end(FrameProfiler.FG_UI, timing, commands);
		if (result != 0) {
			streamlineProblem = "DLSS-G UI tag failed " + Integer.toHexString(result);
			ReflexiumClient.LOGGER.warn(streamlineProblem);
		}
	}

	private void releaseStreamlineTargets() {
		if (hudlessTarget != null) { hudlessTarget.destroyBuffers(); hudlessTarget = null; }
		if (uiColorAlphaTarget != null) { uiColorAlphaTarget.destroyBuffers(); uiColorAlphaTarget = null; }
		if (uiAlphaFlippedTarget != null) { uiAlphaFlippedTarget.destroyBuffers(); uiAlphaFlippedTarget = null; }
		if (fgDepthTarget != null) { fgDepthTarget.destroyBuffers(); fgDepthTarget = null; }
		if (fgMotionTarget != null) { fgMotionTarget.destroyBuffers(); fgMotionTarget = null; }
		if (fgDepthFlip != null) { fgDepthFlip.close(); fgDepthFlip = null; }
		streamlineMotion = null;
	}

	private void disableStreamlineFrameGeneration() {
		streamlineActive = false;
		if (session != null && session.streamline() && StreamlineSupport.dlssgAvailable()) {
			StreamlineSupport.setFrameGeneration(0, 1, false);
		}
	}

	/** Replays already prepared GUI draws against each scene, preserving destination-dependent blends such as the crosshair. */
	public void drawGuiCopies(GameRenderer renderer, Runnable draw) {
		for (int i = 0; i < ready; i++) {
			VkCommandBuffer commands = UpscalingRenderer.get().commandBuffer();
			long timing = FrameProfiler.start(FrameProfiler.FG_GUI, commands);
			RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(generated[i].getDepthTexture(), 0);
			UpscalingRenderer.swap(renderer.mainRenderTarget(), generated[i]);
			try { draw.run(); }
			finally {
				UpscalingRenderer.swap(renderer.mainRenderTarget(), generated[i]);
				FrameProfiler.end(FrameProfiler.FG_GUI, timing, commands);
			}
		}
	}

	/** Drops this frame's generated images; history stays valid for the next world frame. */
	public void skipGeneratedFrames() { ready = 0; delivery.reset(); ledger.reason(GenerationLedger.Shortfall.GUI_BLUR); }
	/** Present-loop outcome that cut this frame's batch short; the reason is accounted when the frame closes. */
	public void noteShortfall(GenerationLedger.Shortfall reason) { ledger.reason(reason); }
	public GenerationLedger ledger() { return ledger; }
	public int generatedCount() { return ready; }
	public int submittedFps() { return delivery.totalRate(System.nanoTime()); }
	public int submittedRealFps() { return delivery.realRate(System.nanoTime()); }
	public long realPresents() { return realPresented; }
	public void scheduleProxyPresent() {
		if (FsrSwapchain.active() && built == FrameGeneration.FSR) {
			FsrSwapchain.schedule(ready == 0 ? null : VulkanInterop.image(generated[0].getColorTexture(), generated[0].getColorTextureView()), fsrFrameId);
		}
	}
	public GpuTextureView generatedView(int index) { return generated[index].getColorTextureView(); }
	public GpuTextureView generatedDepthView(int index) { return generated[index].getDepthTextureView(); }
	public GpuTexture generatedDepthTexture(int index) { return generated[index].getDepthTexture(); }
	/** One encoder per replacement slot keeps RenderPearl's two-submit reuse wait per real frame, not per output. */
	public CommandEncoderBackend presentEncoder(int index) {
		if (presentEncoders[index] == null) presentEncoders[index] = new VulkanCommandEncoder(device);
		return presentEncoders[index];
	}
	/** Move the replacement encoders' previous-submit reuse waits ahead of the first metered present.
	 * Their submit() still performs its native wait/reset, now already satisfied. No safety wait is removed. */
	public void awaitPreviousReplacements() {
		for (int i = 0; i < presentCompletions.length; i++) {
			var completion = presentCompletions[i];
			if (completion == null) continue;
			if (!completion.awaitCompletion(5_000_000_000L)) throw new IllegalStateException("Previous FG replacement submit has not completed");
			completion.close(); presentCompletions[i] = null;
		}
	}
	public void trackReplacement(int index) { presentCompletions[index] = presentEncoders[index].createFence(); }
	public long evaluatedFrames() { return evaluated; }
	public long generatedPresents() { return presented + streamlineGeneratedPresents; }
	/** Last evaluated output resources survive endPresentBatch(), which consumes ready before debug tasks run. */
	public int debugGeneratedCount() { return resetNext || failed ? 0 : generated.length; }
	public RenderTarget debugTarget(int index) {
		if (index < 0 || index >= generated.length) throw new IllegalArgumentException("No generated frame at index " + index);
		return generated[index];
	}
	public int meteringFeedback() { return lastMeteringFeedback; }
	public String meteringFeedbackRaw() { return "0x" + Integer.toHexString(lastMeteringFeedback); }
	public int meteringResult() { return lastMeteringResult; }
	public long meteringBatchConfigs() { return batchConfigs; }
	public long meteringResetConfigs() { return resetConfigs; }
	public long meteringUnreported() { return feedbackUnreported; }
	public long meteringAccepted() { return feedbackAccepted; }
	/** Positive non-sentinel feedback, such as the 0x1 NVIDIA writes; neither confirmed success nor rejection. */
	public long meteringStatus() { return feedbackStatus; }
	public long meteringRejected() { return feedbackRejected; }
	public long meteringRejectionStreak() { return rejectionStreak; }
	public boolean meteringResetPending() { return resetMetering; }
	public String meteringProblem() { return meteringProblem; }
	public long batchesStarted() { return batchesStarted; }
	public long batchesCompleted() { return batchesCompleted; }
	public boolean failed() { return failed; }
	public String problem() { return problem; }
	public String statusLine() {
		FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
		if (mode == FrameGeneration.OFF) return "Frame generation: off";
		String reason = failed ? problem : unavailableReason();
		String resets = resetsPerSecond() > 0 ? ", resets " + resetsPerSecond() + "/s" : "";
		if (reason.isEmpty() && mode.nvidia() && refreshPolicy.bypass()) return "Frame generation: rendered only (frame target "
				+ refreshPolicy.cap() + " FPS, Auto output budget)" + (refreshPolicy.probing() ? " (measuring real FPS probe)" : "") + resets;
		String engine = mode.nvidia() && streamlinePath() ? " (Streamline)" : "";
		String issue = !streamlineProblem.isEmpty() ? " (" + streamlineProblem + ")"
				: streamlineUnfocused ? " (DLSS-G inserted no generated frames; see the SDK log)"
				: !meteringProblem.isEmpty() ? " (" + meteringProblem + ")" : "";
		return "Frame generation: " + (reason.isEmpty() ? (mode.nvidia() && !resetNext ? built : mode).label().getString()
				+ (pendingWorldFrames > 30 ? " (waiting for consecutive world frames: " + ledger.reasonText() + ")" : mode.nvidia() ? " " + refreshPolicy.multiplier() + "x" : "")
				+ engine + issue : reason) + resets;
	}

	public void beginPresentBatch(int frames) {
		if (frames < 2 || frames > 8) throw new IllegalArgumentException("FG batch must contain 2..8 presents");
		presentBatch = frames; presentIndex = 0; batchRejected = false; batchesStarted++;
		long traceId = ReflexController.get().currentFrameId();
		if (FrameTrace.on(traceId)) FrameTrace.event(traceId, "metering batch begin presents=" + frames);
		VulkanLifecycle.clearSubmissionAttribution();
	}
	public boolean batchRejected() { return batchRejected; }
	public boolean inPresentBatch() { return presentBatch > 1; }
	public void beginPresent(boolean generated) { generatedPresent = generated; }
	public boolean isGeneratedPresent() { return generatedPresent; }
	public void endPresentBatch() {
		if (presentBatch > 1 && !batchRejected && presentIndex == presentBatch) batchesCompleted++;
		if (presentBatch > 1) ledger.closeIfOpen(); // ended before its real present
		long traceId = ReflexController.get().currentFrameId();
		if (presentBatch > 1 && FrameTrace.on(traceId)) FrameTrace.event(traceId,
				"metering batch end presented=" + presentIndex + "/" + presentBatch + " rejected=" + batchRejected);
		presentBatch = 0; generatedPresent = false; ready = 0;
	}
	/**
	 * A pending reset goes out on the next present as a one-frame configuration. When that present opens a
	 * batch, the batch ends at its real image, so a reset is never consumed as a generation batch's configuration.
	 */
	public VkPresentInfoKHR chainMetering(VkPresentInfoKHR info) {
		chained = MeteringConfig.NONE;
		boolean first = presentBatch > 1 && presentIndex++ == 0;
		if (!resetMetering && !first) return info;
		chained = resetMetering ? MeteringConfig.RESET : MeteringConfig.BATCH;
		if (resetMetering) resetConfigs++; else batchConfigs++;
		VkSetPresentConfigNV metering = VkSetPresentConfigNV.calloc(MemoryStack.stackGet()).sType$Default()
				.numFramesPerBatch(resetMetering ? 1 : presentBatch).presentConfigFeedback(FEEDBACK_UNREPORTED).pNext(info.pNext());
		return info.pNext(metering.address());
	}
	public void onPresentResult(VkPresentInfoKHR info, int result) {
		if (streamlineActive) {
			onStreamlinePresentResult(result);
			return;
		}
		boolean delivered = result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR;
		if (delivered) {
			if (generatedPresent) presented++;
			else {
				realPresented++;
				Minecraft mc = Minecraft.getInstance();
				if (mc != null) {
					double fgCost = FrameProfiler.gpuMeanMs(FrameProfiler.FG_EVALUATE) * generated.length;
					refreshPolicy.present(System.nanoTime(), mc.level != null && mc.gui.screen() == null && !mc.isPaused(),
							delivery.realRate(System.nanoTime()), ReflexController.get().frameGenerationRenderCostMs(fgCost), fgCost);
				}
			}
			delivery.record(System.nanoTime(), generatedPresent ? 0 : 1, generatedPresent ? 1 : 0);
		}
		if (generatedPresent) ledger.generatedPresent(delivered);
		else ledger.closeFrame();
		MeteringConfig kind = chained;
		chained = MeteringConfig.NONE;
		if (kind != MeteringConfig.NONE) onMeteringFeedback(kind, readFeedback(info), result, delivered);
		if (result != VK10.VK_SUCCESS) {
			batchRejected = resetMetering = true;
			invalidate();
		}
	}
	private static int readFeedback(VkPresentInfoKHR info) {
		for (long next = info.pNext(); next != 0; ) {
			VkBaseOutStructure header = VkBaseOutStructure.create(next);
			if (header.sType() == NVPresentMetering.VK_STRUCTURE_TYPE_SET_PRESENT_CONFIG_NV) return VkSetPresentConfigNV.create(next).presentConfigFeedback();
			next = header.pNext() == null ? 0 : header.pNext().address();
		}
		return FEEDBACK_UNREPORTED;
	}
	/** Metering responses are transient: they abandon the batch and reset pacing, never set the NGX failure latch. */
	private void onMeteringFeedback(MeteringConfig kind, int raw, int result, boolean delivered) {
		lastMeteringFeedback = raw; lastMeteringResult = result;
		// NVIDIA 617.42 writes 0x1 synchronously for every config, including one-frame resets, while ac1e339 ignored
		// feedback and sustained generation. Positive values are counted as status; only negative values reject.
		boolean rejected = delivered && raw < 0;
		if (configLogs < 4 || rejected && (feedbackRejected < 5 || feedbackRejected % 1000 == 0)) {
			configLogs++;
			LOG.info("Present metering {} config: frames={}, seeded=0x{}, feedback=0x{}, result={}, rejections={}", kind,
					kind == MeteringConfig.RESET ? 1 : presentBatch, Integer.toHexString(FEEDBACK_UNREPORTED), Integer.toHexString(raw), result, feedbackRejected);
		}
		boolean reset = kind == MeteringConfig.RESET;
		if (reset && presentBatch > 1 && presentIndex == 1) batchRejected = true;
		if (!delivered) { resetMetering = true; return; }
		if (rejected) {
			feedbackRejected++; rejectionStreak++;
			meteringProblem = "present metering rejected: 0x" + Integer.toHexString(raw) + ", " + rejectionStreak + " consecutive";
			batchRejected = resetMetering = true;
			invalidate();
			return;
		}
		if (raw == 0) feedbackAccepted++; else if (raw == FEEDBACK_UNREPORTED) feedbackUnreported++; else feedbackStatus++;
		if (reset) resetMetering = false;
		else { rejectionStreak = 0; meteringProblem = ""; }
	}
	/** One host present; Streamline has already presented the real frame plus N interpolated frames. */
	private void onStreamlinePresentResult(int result) {
		boolean delivered = result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR;
		lastStreamlinePresentResult = result;
		int[] state = StreamlineSupport.refreshFrameGenerationState();
		int actual = state == null ? 0 : state[3];
		int generated = Math.max(0, actual - 1);
		if (generated > 0) {
			streamlineUnfocused = false;
			streamlineNoGenerateFrames = 0;
		} else if (++streamlineNoGenerateFrames > 120) {
			// No interpolated frames for two seconds. DLSS-G uses this path both for the documented
			// unfocused-window pause and for a rejected integration; the SDK log says which.
			streamlineNoGenerateFrames = 0;
			streamlineUnfocused = true;
			if (streamlineNoGenerateLogs < 8) {
				streamlineNoGenerateLogs++;
				String log = StreamlineSupport.takeLog();
				ReflexiumClient.LOGGER.info("DLSS-G no generation: state {} (max {}, actual {}, status {}, min {}), options {}x applied mode {}, generated {}; log: {}",
						state == null ? "null" : java.util.Arrays.toString(state),
						StreamlineSupport.maximumGeneratedFrames(), actual, StreamlineSupport.frameGenerationStatus(),
						StreamlineSupport.minimumFrameGenerationDimension(), configuredGeneratedCount(),
						StreamlineSupport.appliedMode(), StreamlineSupport.appliedGenerated(),
						log.isBlank() ? "(empty)" : log.trim());
			}
		}
		if (delivered) {
			realPresented++;
			streamlineGeneratedPresents += generated;
			delivery.record(System.nanoTime(), 1, generated);
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				refreshPolicy.present(System.nanoTime(), mc.level != null && mc.gui.screen() == null && !mc.isPaused(),
						delivery.realRate(System.nanoTime()), ReflexController.get().frameGenerationRenderCostMs(0.0), 0.0);
			}
		}
		if (actual <= 1 && streamlineNoGenerateLogs < 3) {
			streamlineNoGenerateLogs++;
			String log = StreamlineSupport.takeLog();
			if (!log.isBlank()) ReflexiumClient.LOGGER.info("[Streamline] {}", log.trim());
			ReflexiumClient.LOGGER.info("Streamline DLSS-G presented {} frame(s) for this real frame; options {}x",
					Math.max(0, actual), ReflexiumConfig.get().frameGenerationMultiplier());
		}
		for (int i = 0; i < generated; i++) ledger.generatedPresent(delivered);
		if (state != null && state[2] != 0) {
			streamlineProblem = "DLSS-G runtime status " + state[2];
			ReflexiumClient.LOGGER.warn("DLSS-G runtime status {}; turning frame generation off", state[2]);
			failed = true;
			problem = streamlineProblem;
		}
		ledger.closeFrame();
		streamlineActive = false;
		// SUBOPTIMAL still delivers the image; treating it as a failure disabled DLSS-G after every
		// present and toggled the options off/on each frame, so it could never interpolate.
		if (!delivered || failed) invalidate();
	}

	public void onProxyPresentResult(int result) {
		// SDK present-count telemetry is exposed separately. Its callbacks are asynchronous and do
		// not identify generated scanout; keep the overlay's rate unmultiplied on this route.
		if (result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR) realPresented++;
		if (result != VK10.VK_SUCCESS) invalidate();
	}

	public void release() {
		invalidate();
		if (ngx != null) ngx.releaseFrameGeneration();
		if (fsr != null) fsr.releaseFrameGeneration();
		for (TextureTarget target : generated) if (target != null) target.destroyBuffers();
		generated = new TextureTarget[0];
		releaseStreamlineTargets();
		built = FrameGeneration.OFF;
	}
	private static void check(int result, String operation) {
		if (result != 0) throw new IllegalStateException("FG " + operation + " failed: 0x" + Integer.toHexString(result));
	}
}

