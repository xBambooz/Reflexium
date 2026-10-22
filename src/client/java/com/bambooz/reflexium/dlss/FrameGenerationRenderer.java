package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.FrameProfiler;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private final com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder[] presentEncoders = new com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder[7];
	private final com.mojang.renderpearl.api.commands.GpuFence[] presentCompletions = new com.mojang.renderpearl.api.commands.GpuFence[7];
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

	void onDeviceCreated(VulkanDevice device, VulkanSession session, FsrBridge fsr) {
		this.device = device; this.session = session; this.fsr = fsr;
		resetMetering = false; presentBatch = 0; generatedPresent = false;
		retry();
		if (fsr != null) FsrSwapchain.onDeviceCreated(device, session);
	}
	void onNgxReady(NgxBridge bridge) {
		ngx = bridge; support = bridge.frameGenerationSupport();
		ReflexiumConfig.get().limitFrameGenerationMultiplier(maximumMultiplier());
		ReflexiumClient.LOGGER.info("DLSS frame generation on Minecraft device: {}", support);
	}
	void onDeviceClosing() {
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

	public int maximumMultiplier() { return support != null && support.available() ? Math.min(8, support.maxGenerated() + 1) : 1; }
	public boolean isDlssFgSupported() { return session != null && session.isNvidia() && support != null && support.available(); }
	public boolean isDlssMfgSupported() { return isDlssFgSupported() && maximumMultiplier() >= 3; }
	public boolean wantsMotion() { return ReflexiumConfig.get().frameGeneration() != FrameGeneration.OFF && !failed && unavailableReason().isEmpty(); }
	public boolean needsReflex() { return wantsMotion() && ReflexiumConfig.get().frameGeneration().nvidia(); }
	public void retry() { failed = false; problem = ""; resetNext = true; delivery.reset(); refreshPolicy.reset(); }
	public void invalidate() { ready = 0; resetNext = true; delivery.reset(); }

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
		// Completion of SDK presentation consumers precedes overwriting the generated target.
		FsrSwapchain.waitForPresents();
		frameId++;
		Minecraft mc = Minecraft.getInstance();
		pendingWorldFrames = resetNext && mc.level != null && mc.gui.screen() == null && !mc.isPaused() ? pendingWorldFrames + 1 : 0;
		ready = 0;
		ledger.begin(configuredGeneratedCount(), failed || !unavailableReason().isEmpty() ? GenerationLedger.Shortfall.UNAVAILABLE : GenerationLedger.Shortfall.NOT_EVALUATED);
		if (!wantsMotion()) resetNext = true;
	}

	/** Generated presents each real frame should deliver for the requested NVIDIA mode; 0 when off or on the FSR proxy route. */
	public int configuredGeneratedCount() {
		if (!ReflexiumConfig.get().frameGeneration().nvidia()) return 0;
		refreshPolicy.force(ReflexiumConfig.get().fgForceMultiplier());
	refreshPolicy.configure(ReflexiumConfig.get().frameGenerationMultiplier(), maximumMultiplier());
		return Math.max(0, refreshPolicy.multiplier() - 1);
	}
	public int requestedGeneratedCount() {
		FrameGeneration requested = ReflexiumConfig.get().frameGeneration();
		if (!requested.nvidia()) return 0;
		int multiplier = ReflexiumConfig.get().frameGenerationMultiplier();
		return multiplier == 0 ? Math.max(1, maximumMultiplier() - 1) : Math.max(1, Math.min(multiplier, Math.max(2, maximumMultiplier())) - 1);
	}

	void sceneReady(RenderTarget scene, RenderTarget motion, VkCommandBuffer commands, Matrix4fc projection,
			Matrix4fc clipToPrevious, Quaternionfc orientation, float x, float y, float z, float jitterX, float jitterY,
			float near, float far, float fov, float frameMs, boolean reset) {
		if (!wantsMotion()) return;
		if (ReflexiumConfig.get().frameGeneration().nvidia() && refreshPolicy.bypass()) { resetNext = true; return; }
		if (ReflexiumConfig.get().frameGeneration().nvidia() && !com.bambooz.reflexium.reflex.ReflexController.get().isSleeping()) {
			ledger.reason(GenerationLedger.Shortfall.REFLEX_NOT_SLEEPING);
			invalidate(); return;
		}
		try {
			FrameGeneration requested = ReflexiumConfig.get().frameGeneration();
			int count = requested.nvidia() ? configuredGeneratedCount() : 1;
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
			UpscalingRenderer.barrier(commands);
			NgxBridge.Image color = UpscalingRenderer.ngxImage(scene.getColorTexture(), scene.getColorTextureView());
			NgxBridge.Image depth = UpscalingRenderer.ngxImage(motion.getDepthTexture(), motion.getDepthTextureView());
			NgxBridge.Image vectors = UpscalingRenderer.ngxImage(motion.getColorTexture(), motion.getColorTextureView());
			long evaluationId = mode == FrameGeneration.FSR ? ++fsrFrameId : frameId;
			for (int i = 0; i < count; i++) {
				NgxBridge.Image outputImg = UpscalingRenderer.ngxImage(generated[i].getColorTexture(), generated[i].getColorTextureView());
				long timing = FrameProfiler.start(FrameProfiler.FG_EVALUATE, commands);
				int result = mode == FrameGeneration.FSR ? fsr.evaluateFrameGeneration(commands.address(), color, depth, vectors,
						outputImg, constants, evaluationId, resetting)
						: ngx.evaluateFrameGeneration(commands.address(), color, depth, vectors, outputImg, constants, frameId, count, i + 1, resetting);
				FrameProfiler.end(FrameProfiler.FG_EVALUATE, timing, commands);
				check(result, "evaluation");
				UpscalingRenderer.barrier(commands);
			}
			UpscalingRenderer.barrier(commands);
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
			FsrSwapchain.schedule(ready == 0 ? null : UpscalingRenderer.ngxImage(generated[0].getColorTexture(), generated[0].getColorTextureView()), fsrFrameId);
		}
	}
	public GpuTextureView generatedView(int index) { return generated[index].getColorTextureView(); }
	public GpuTextureView generatedDepthView(int index) { return generated[index].getDepthTextureView(); }
	public com.mojang.renderpearl.api.textures.GpuTexture generatedDepthTexture(int index) { return generated[index].getDepthTexture(); }
	/** One encoder per replacement slot keeps RenderPearl's two-submit reuse wait per real frame, not per output. */
	public com.mojang.renderpearl.backend.api.CommandEncoderBackend presentEncoder(int index) {
		if (presentEncoders[index] == null) presentEncoders[index] = new com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder(device);
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
	public long generatedPresents() { return presented; }
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
		return "Frame generation: " + (reason.isEmpty() ? (mode.nvidia() && !resetNext ? built : mode).label().getString()
				+ (pendingWorldFrames > 30 ? " (waiting for consecutive world frames: " + ledger.reasonText() + ")" : mode.nvidia() ? " " + refreshPolicy.multiplier() + "x" : "")
				+ (meteringProblem.isEmpty() ? "" : " (" + meteringProblem + ")") : reason) + resets;
	}

	public void beginPresentBatch(int frames) {
		if (frames < 2 || frames > 8) throw new IllegalArgumentException("FG batch must contain 2..8 presents");
		presentBatch = frames; presentIndex = 0; batchRejected = false; batchesStarted++;
		long traceId = com.bambooz.reflexium.reflex.ReflexController.get().currentFrameId();
		if (com.bambooz.reflexium.vulkan.FrameTrace.on(traceId)) com.bambooz.reflexium.vulkan.FrameTrace.event(traceId, "metering batch begin presents=" + frames);
		com.bambooz.reflexium.vulkan.VulkanLifecycle.clearSubmissionAttribution();
	}
	public boolean batchRejected() { return batchRejected; }
	public boolean inPresentBatch() { return presentBatch > 1; }
	public void beginPresent(boolean generated) { generatedPresent = generated; }
	public boolean isGeneratedPresent() { return generatedPresent; }
	public void endPresentBatch() {
		if (presentBatch > 1 && !batchRejected && presentIndex == presentBatch) batchesCompleted++;
		if (presentBatch > 1) ledger.closeIfOpen(); // ended before its real present
		long traceId = com.bambooz.reflexium.reflex.ReflexController.get().currentFrameId();
		if (presentBatch > 1 && com.bambooz.reflexium.vulkan.FrameTrace.on(traceId)) com.bambooz.reflexium.vulkan.FrameTrace.event(traceId,
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
		boolean delivered = result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR;
		if (delivered) {
			if (generatedPresent) presented++;
			else {
				realPresented++;
				Minecraft mc = Minecraft.getInstance();
				if (mc != null) {
					double fgCost = FrameProfiler.gpuMeanMs(FrameProfiler.FG_EVALUATE) * generated.length;
					refreshPolicy.present(System.nanoTime(), mc.level != null && mc.gui.screen() == null && !mc.isPaused(),
							delivery.realRate(System.nanoTime()), com.bambooz.reflexium.reflex.ReflexController.get().frameGenerationRenderCostMs(fgCost), fgCost);
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
	public void onProxyPresentResult(int result) {
		// SDK present-count telemetry is exposed separately. Its callbacks are asynchronous and do
		// not identify generated scanout; keep the overlay's rate unmultiplied on this route.
		if (result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR) realPresented++;
		if (result != VK10.VK_SUCCESS) invalidate();
	}

	void release() {
		invalidate();
		if (ngx != null) ngx.releaseFrameGeneration();
		if (fsr != null) fsr.releaseFrameGeneration();
		for (TextureTarget target : generated) if (target != null) target.destroyBuffers();
		generated = new TextureTarget[0]; built = FrameGeneration.OFF;
	}
	private static void check(int result, String operation) {
		if (result != 0) throw new IllegalStateException("FG " + operation + " failed: 0x" + Integer.toHexString(result));
	}
}
