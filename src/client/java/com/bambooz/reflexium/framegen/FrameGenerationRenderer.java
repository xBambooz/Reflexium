package com.bambooz.reflexium.framegen;

import com.bambooz.reflexium.vulkan.VulkanInterop;
import com.bambooz.reflexium.vulkan.ImageRef;
import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.diagnostics.FailureNotice;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.upscale.dlss.DlssFeatureGate;
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
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Owns interpolation images and full-resolution GUI composition. No generated image advances simulation. */
public final class FrameGenerationRenderer {
	private static final FrameGenerationRenderer INSTANCE = new FrameGenerationRenderer();
	private static final Logger LOG = LoggerFactory.getLogger("Reflexium");
	/** -Dreflexium.fg.ui=false disables DLSS-G UI recomposition for A/B diagnosis. */
	private static final boolean UI_RECOMPOSITION = !"false".equalsIgnoreCase(System.getProperty("reflexium.fg.ui"));
	private final MemorySegment constants = Arena.global().allocate(FrameGenerationConstants.FLOATS * 4L, 4);
	private VulkanDevice device;
	private VulkanSession session;
	private FsrBridge fsr;
	private FrameGeneration built = FrameGeneration.OFF;
	private TextureTarget[] generated = new TextureTarget[0];
	private TextureTarget hudlessTarget, uiColorAlphaTarget, uiAlphaFlippedTarget;
	private TextureTarget srColorTarget, srOutputTarget;
	private boolean streamlineInputsPrepared;
	private TextureTarget fgDepthTarget, fgMotionTarget;
	private com.bambooz.reflexium.upscale.DepthResolvePass fgDepthFlip;
	private RenderTarget streamlineMotion;
	private boolean streamlineActive;
	private Boolean dlssgFeatureWanted;
	private boolean dlssgLoadFailed;
	
	
	private int lastStreamlinePresentResult;
	private long streamlineGeneratedPresents;
	private int streamlineNoGenerateLogs;
	private int streamlineNoGenerateFrames;
	private boolean streamlineUnfocused;
	private String streamlineProblem = "";
	private long streamlineSkipLogAt;
	private String streamlineSkipReason = "";
	private boolean streamlineSceneLogged;
	private final VulkanCommandEncoder[] presentEncoders = new VulkanCommandEncoder[7];
	private final GpuFence[] presentCompletions = new GpuFence[7];
	private boolean failed, resetNext = true;
	private boolean windowMinimized;
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
	private boolean batchRejected;
	private long batchesStarted, batchesCompleted;
	private long fsrFrameId;
	private final GenerationLedger ledger = new GenerationLedger();
	private final FrameGenerationRefreshPolicy refreshPolicy = new FrameGenerationRefreshPolicy();
	public FrameGenerationRefreshPolicy refreshPolicy() { return refreshPolicy; }

	public static FrameGenerationRenderer get() { return INSTANCE; }

	public void onDeviceCreated(VulkanDevice device, VulkanSession session, FsrBridge fsr) {
		this.device = device; this.session = session; this.fsr = fsr;
		presentBatch = 0; generatedPresent = false;
		streamlineActive = false;
		dlssgFeatureWanted = null;
		if (session != null && session.streamline()) {
			StreamlineSupport.invalidateFrameGenerationOptions();
			// The real ceiling is only known after the first present state query; do not rewrite the
			// saved multiplier while it is still the conservative 2x placeholder.
			int maximum = maximumMultiplier();
			if (maximum > 2) ReflexiumConfig.get().limitFrameGenerationMultiplier(maximum);
			ReflexiumClient.LOGGER.info("Streamline frame generation: {}", StreamlineSupport.status());
		}
		retry();
		if (fsr != null) FsrSwapchain.onDeviceCreated(device, session);
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
		device = null; session = null; fsr = null;
	}

	public int maximumMultiplier() {
		if (streamlinePath()) return StreamlineSupport.maximumMultiplier();
		return 1;
	}
	public boolean isDlssFgSupported() {
		return StreamlineSupport.dlssgFeatureLoaded() && gate().frameGenerationAllowed();
	}
	public boolean isDlssMfgSupported() {
		return StreamlineSupport.dlssgFeatureLoaded() && gate().multiFrameGenerationAllowed();
	}

	/** What slIsFeatureSupported and slDLSSGGetState allow on this adapter and runtime. */
	public DlssFeatureGate.Decision gate() {
		boolean nvidia = session != null && session.isNvidia();
		boolean streamline = nvidia && session.streamline() && StreamlineSupport.initialized() && StreamlineSupport.deviceReady();
		return DlssFeatureGate.evaluate(new DlssFeatureGate.Support(nvidia, streamline,
				StreamlineSupport.dlssgAvailable(), StreamlineSupport.maximumGeneratedFrames()));
	}

	/**
	 * DLSS-G guide 18.0: turn generation off before any window manipulation, including minimize.
	 * The restore resets history so the first visible frame is not interpolated from the iconified pause.
	 */
	public void onWindowState(boolean minimized) {
		if (minimized == windowMinimized) return;
		windowMinimized = minimized;
		if (minimized) {
			disableStreamlineFrameGeneration();
			resetNext = true;
			ledger.reason(GenerationLedger.Shortfall.WINDOW_PAUSED);
			ReflexiumClient.LOGGER.info("DLSS-G paused: window minimized");
		} else {
			resetNext = true;
			ReflexiumClient.LOGGER.info("DLSS-G resumed: window restored, frame history reset");
		}
	}
	public boolean windowMinimized() { return windowMinimized; }
	public boolean wantsMotion() { return ReflexiumConfig.get().frameGeneration() != FrameGeneration.OFF && !failed && unavailableReason().isEmpty(); }
	public boolean needsReflex() {
		FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
		return mode != FrameGeneration.OFF && mode.nvidia() && !failed;
	}
	/** Streamline owns interpolated presents for this frame; no app-side generated targets are used. */
	public boolean streamlineFrameGeneration() { return streamlinePath(); }
	/** Frame generation owns Streamline's per-frame constants in this configuration. */
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
			StreamlineSupport.invalidateFrameGenerationOptions();
			resetNext = true;
		}
	}

	/** Called before the swapchain is destroyed or recreated, not after. */
	public void beforeSurfaceChange() {
		if (session == null || !session.streamline()) return;
		disableStreamlineFrameGeneration();
		StreamlineSupport.invalidateFrameGenerationOptions();
		resetNext = true;
	}
	public void retry() {
		failed = false; problem = ""; streamlineProblem = ""; resetNext = true;
		streamlineUnfocused = false; streamlineNoGenerateFrames = 0;
		dlssgFeatureWanted = null; dlssgLoadFailed = false;
		delivery.reset(); refreshPolicy.reset();
	}

	/** A device loss ends frame generation for this device; changing settings cannot revive it. */
	public void failForDeviceLoss(String reason) {
		failed = true;
		problem = reason;
		streamlineProblem = reason;
		streamlineActive = false;
		ready = 0;
		delivery.reset();
	}
	public void invalidate() { ready = 0; resetNext = true; delivery.reset(); disableStreamlineFrameGeneration(); }

	public String unavailableReason() {
		FrameGeneration mode = ReflexiumConfig.get().frameGeneration();
		if (mode == FrameGeneration.OFF) return "";
		if (session == null) return "needs Windows x64 and Vulkan";
		if (mode == FrameGeneration.FSR && fsr == null) return "FSR runtime unavailable";
		if (mode == FrameGeneration.FSR && session.streamline()) return "FSR frame generation needs a launch without Streamline";
		if (mode == FrameGeneration.FSR && FsrSwapchain.active()) return "";
		if (Minecraft.getInstance().options.enableVsync().get()) return "disable VSync to enable Vulkan frame generation";
		if (session.streamline()) {
			// Streamline owns the presenter; raw VK_NV_present_metering and present IDs are not required.
			DlssFeatureGate.Decision gate = gate();
			if (!gate.frameGenerationAllowed()) return gate.reason();
			if (!StreamlineSupport.dlssgFeatureLoaded()) return "DLSS Frame Generation failed to load (see log)";
			if (!StreamlineSupport.reflexAvailable()) return "DLSS Frame Generation needs Streamline Reflex";
			int minimum = StreamlineSupport.minimumFrameGenerationDimension();
			if (minimum > 0 && width > 0 && height > 0 && (width < minimum || height < minimum)) {
				return "DLSS Frame Generation needs at least " + minimum + "x" + minimum + " pixels";
			}
			if (mode == FrameGeneration.DLSS_MULTI && !gate.multiFrameGenerationAllowed()) return gate.reason();
			int status = StreamlineSupport.frameGenerationStatus();
			if (status != 0) return "DLSS Frame Generation runtime status " + status;
			return "";
		}
		return mode == FrameGeneration.FSR && FsrSwapchain.active() ? "" : "NVIDIA frame generation needs Streamline";
	}

	/**
	 * DLSS-G guide 18.0. Loading or unloading the plugin is a swapchain-lifetime change, so it is done
	 * with the swapchain torn down: queue the state, ask the game to reconfigure its surface, and apply
	 * the state from the destroy hook before the replacement swapchain exists.
	 */
	private void syncDlssgFeatureState() {
		if (session == null || !session.streamline() || !StreamlineSupport.dlssgAvailable()) return;
		boolean want = ReflexiumConfig.get().frameGeneration().nvidia();
		if (!want) {
			// Leave the plugin loaded while frame generation is off. The unload path is the guide's
			// overhead optimization, but this SDK crashes in the next interposer swapchain creation
			// after an unload, so the safe fallback is an options-off feature.
			dlssgFeatureWanted = null;
			return;
		}
		if (StreamlineSupport.dlssgFeatureLoaded() && !com.bambooz.reflexium.debug.DebugSettings.failDlssgLoad()) {
			dlssgFeatureWanted = true;
			return;
		}
		if (Boolean.TRUE.equals(dlssgFeatureWanted)) return;
		dlssgFeatureWanted = true;
		StreamlineSupport.requestFrameGenerationFeature(true);
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) mc.invalidateSurfaceConfiguration();
		ReflexiumClient.LOGGER.info("DLSS-G feature load requested; recreating the swapchain");
	}

	public void beginFrame() {
		// Completion of SDK presentation consumers precedes overwriting the generated target.
		FsrSwapchain.waitForPresents();
		FailureNotice.flush();
		streamlineActive = false;
		syncDlssgFeatureState();
		frameId++;
		Minecraft mc = Minecraft.getInstance();
		boolean worldFrame = mc.level != null && mc.gui.screen() == null && !mc.isPaused() && !windowMinimized;
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

	/** FSR's application-managed interpolation: one generated image presented through its proxy swapchain. */
	public void sceneReady(RenderTarget scene, RenderTarget motion, VkCommandBuffer commands, Matrix4fc projection,
			Matrix4fc clipToPrevious, Quaternionfc orientation, float x, float y, float z, float jitterX, float jitterY,
			float near, float far, float fov, float frameMs, boolean reset) {
		if (!wantsMotion() || ReflexiumConfig.get().frameGeneration() != FrameGeneration.FSR) return;
		Minecraft mc = Minecraft.getInstance();
		// Real-scene frames only: generated UI is a still panel and tagging it as gameplay wastes GPU time.
		if (mc.gui.screen() != null || mc.isPaused()) {
			ledger.reason(GenerationLedger.Shortfall.MENU);
			invalidate();
			return;
		}
		try {
			int count = 1;
			if (built != FrameGeneration.FSR || generated.length != count || width != scene.width || height != scene.height) {
				UpscalingRenderer.get().waitForGpu();
				release();
				commands = UpscalingRenderer.get().commandBuffer();
				width = scene.width; height = scene.height;
				generated = new TextureTarget[count];
				for (int i = 0; i < count; i++) {
					generated[i] = new TextureTarget("Reflexium generated frame " + i, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
				}
				int result = fsr.createFrameGeneration(session.device().address(), session.physicalDevice().address(),
						VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr"), motion.width, motion.height, width, height);
				check(result, "creation");
				built = FrameGeneration.FSR;
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
			long evaluationId = ++fsrFrameId;
			ImageRef outputImg = VulkanInterop.image(generated[0].getColorTexture(), generated[0].getColorTextureView());
			long timing = FrameProfiler.start(FrameProfiler.FG_EVALUATE, commands);
			int result = fsr.evaluateFrameGeneration(commands.address(), color, depth, vectors,
					outputImg, constants, evaluationId, resetting);
			FrameProfiler.end(FrameProfiler.FG_EVALUATE, timing, commands);
			check(result, "evaluation");
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
		}
	}

	/**
	 * Streamline DLSS-G owns the generated presents. The application supplies display-resolution hudless
	 * color, depth, motion vectors and UI color/alpha, and one real present per frame.
	 */
	public void streamlineSceneReady(RenderTarget scene, RenderTarget motion, VkCommandBuffer commands,
			Matrix4fc projection, Matrix4fc clipToPrevious, Quaternionfc orientation, float x, float y, float z,
			float jitterX, float jitterY, float near, float far, float fov, float frameMs, boolean reset,
			boolean srOwnsConstants, int displayWidth, int displayHeight) {
		if (!streamlinePath()) { noteStreamlineSkip("no streamline path"); return; }
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null || mc.isPaused()) {
			ledger.reason(GenerationLedger.Shortfall.MENU);
			noteStreamlineSkip("menu screen=" + (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())
					+ " paused=" + mc.isPaused());
			// Guide 17.0: null every DLSS-G tag on menus and loading screens so the plugin never
			// consumes stale depth/motion from a paused world.
			if (streamlineActive) StreamlineSupport.clearFrameGenerationTags(commands.address());
			disableStreamlineFrameGeneration();
			return;
		}
		if (!ReflexController.get().isSleeping()) {
			ledger.reason(GenerationLedger.Shortfall.REFLEX_NOT_SLEEPING);
			noteStreamlineSkip("reflex not sleeping");
			disableStreamlineFrameGeneration();
			return;
		}
		FrameGeneration requested = ReflexiumConfig.get().frameGeneration();
		ReflexController.get().noteFrameGenerationRequested(requested.nvidia());
		int count = requested.nvidia() ? configuredGeneratedCount() : 0;
		if (count < 1) { resetNext = true; noteStreamlineSkip("configured count 0"); disableStreamlineFrameGeneration(); return; }
		if (requested.nvidia() && !ReflexController.get().frameGenerationPrimed()) {
			// DLSS-G matches its host frame against the Reflex marker for that frame. Reflex is already
			// configured and sleeping here; wait for two completed sleeps so the plugin has a marker
			// history before DLSS-G starts consuming it.
			noteStreamlineSkip("waiting for Reflex to run before DLSS-G");
			ledger.reason(GenerationLedger.Shortfall.NOT_EVALUATED);
			disableStreamlineFrameGeneration();
			return;
		}
		int maximum = maximumMultiplier();
		if (count + 1 > maximum) {
			// A saved multiplier can be above this runtime's real ceiling. Clamp to the queried state
			// instead of sending an options value the plugin rejects every frame.
			int clamped = Math.max(1, maximum - 1);
			if (clamped < 1) {
				streamlineProblem = "DLSS-G has no generated-frame capacity";
				noteStreamlineSkip(streamlineProblem);
				ledger.reason(GenerationLedger.Shortfall.UNAVAILABLE);
				disableStreamlineFrameGeneration();
				return;
			}
			if (count != clamped) {
				ReflexiumConfig.get().limitFrameGenerationMultiplier(maximum);
				streamlineProblem = "";
				LOG.info("DLSS-G multiplier {}x clamped to the runtime maximum {}x", count + 1, maximum);
			}
			count = clamped;
		}
		FrameGeneration mode = count == 1 ? FrameGeneration.DLSS : FrameGeneration.DLSS_MULTI;
		if (built != mode || hudlessTarget == null || uiColorAlphaTarget == null || uiAlphaFlippedTarget == null
				|| srColorTarget == null || srOutputTarget == null
				|| fgDepthTarget == null || fgMotionTarget == null || width != displayWidth || height != displayHeight
				|| fgMotionTarget.width != motion.width || fgMotionTarget.height != motion.height) {
			UpscalingRenderer.get().waitForGpu();
			releaseStreamlineTargets();
			width = displayWidth;
			height = displayHeight;
			hudlessTarget = new TextureTarget("Reflexium FG hudless", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			uiColorAlphaTarget = new TextureTarget("Reflexium FG UI", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			uiAlphaFlippedTarget = new TextureTarget("Reflexium FG UI flipped", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			fgDepthTarget = new TextureTarget("Reflexium FG depth flipped", motion.width, motion.height, null, GpuFormat.D32_FLOAT);
			fgMotionTarget = new TextureTarget("Reflexium FG motion flipped", motion.width, motion.height,
					motion.getColorTexture().getFormat(), GpuFormat.D32_FLOAT);
			srColorTarget = new TextureTarget("Reflexium SR color flipped", motion.width, motion.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			srOutputTarget = new TextureTarget("Reflexium SR output flipped", displayWidth, displayHeight, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			built = mode;
			resetNext = true;
		}
		streamlineMotion = motion;
		boolean resetting = reset || resetNext;
		rollResetWindow(System.nanoTime());
		if (resetting) resetsInWindow++;
		try {
			// One constants block per frame, supplied before DLSS-G options and before SR evaluates,
			// so both features and sl.reflex see the same frame data. The tagged images are mirrored
			// into the presented orientation, hence flipY.
			StreamlineSupport.setConstants(projection, clipToPrevious, orientation, x, y, z, jitterX, jitterY,
					near, far, fov, (float) displayWidth / displayHeight, resetting, motion.width, motion.height, true);
			VulkanLifecycle.SwapchainState swapchain = VulkanLifecycle.swapchainState();
			int optionResult = StreamlineSupport.setFrameGeneration(1, count, UI_RECOMPOSITION,
					swapchain == null ? 0 : swapchain.created(), motion.width, motion.height, displayWidth, displayHeight);
			if (optionResult != 0) {
				streamlineProblem = "DLSS-G options failed " + Integer.toHexString(optionResult);
				noteStreamlineSkip(streamlineProblem);
				ledger.reason(GenerationLedger.Shortfall.UNAVAILABLE);
				disableStreamlineFrameGeneration();
				return;
			}
			streamlineActive = true;
			streamlineInputsPrepared = false;
			streamlineProblem = "";
			if (!streamlineSceneLogged) {
				streamlineSceneLogged = true;
				LOG.info("DLSS-G scene marked active ({}x, {}x{})", count + 1, displayWidth, displayHeight);
			}
			streamlineSkipReason = "";
			evaluated++;
			resetNext = false;
			ready = 0;
			if (resetting) ledger.reason(GenerationLedger.Shortfall.HISTORY_RESET);
			else ledger.reason(GenerationLedger.Shortfall.UNPRESENTED);
		} catch (RuntimeException e) {
			failed = true; problem = e.getMessage(); ledger.reason(GenerationLedger.Shortfall.UNAVAILABLE);
			ReflexiumClient.LOGGER.error("Streamline frame generation stopped; continuing with rendered frames", e);
			FailureNotice.report("DLSS Frame Generation stopped after an error; using rendered frames.");
			disableStreamlineFrameGeneration();
			if (StreamlineSupport.initialized()) {
				String log = StreamlineSupport.problem();
				if (!log.isEmpty()) ReflexiumClient.LOGGER.info("[Streamline] {}", log);
			}
		}
	}

	/**
	 * Mirrors the render-size color, depth and motion vectors into DLSS-G's presented orientation.
	 * The same flipped depth and motion are the inputs for Streamline DLSS SR, so one constants block
	 * (flipY) serves both features.
	 */
	public void prepareStreamlineInputs(RenderTarget scene, RenderTarget motion, VkCommandBuffer commands) {
		if (!streamlineActive || srColorTarget == null || fgDepthTarget == null || fgMotionTarget == null
				|| motion == null || failed || streamlineInputsPrepared) return;
		long timing = FrameProfiler.start(FrameProfiler.FG_HUDLESS, commands);
		VulkanInterop.barrier(commands);
		if (scene.width == srColorTarget.width && scene.height == srColorTarget.height) {
			VulkanInterop.blitFlip(VulkanInterop.image(scene.getColorTexture(), scene.getColorTextureView()),
					VulkanInterop.image(srColorTarget.getColorTexture(), srColorTarget.getColorTextureView()), commands);
		}
		if (fgDepthFlip == null) fgDepthFlip = new com.bambooz.reflexium.upscale.DepthResolvePass();
		fgDepthFlip.render(motion.getDepthTextureView(), fgDepthTarget.getDepthTextureView(),
				motion.width, motion.height, 1.0f, 0.0f, 0.0f, 0.0f, true);
		VulkanInterop.barrier(commands);
		VulkanInterop.blitFlip(VulkanInterop.image(motion.getColorTexture(), motion.getColorTextureView()),
				VulkanInterop.image(fgMotionTarget.getColorTexture(), fgMotionTarget.getColorTextureView()), commands);
		VulkanInterop.barrier(commands);
		streamlineInputsPrepared = true;
		FrameProfiler.end(FrameProfiler.FG_HUDLESS, timing, commands);
	}

	/**
	 * Runs Streamline DLSS SR in the same presented orientation as DLSS-G, using the constants block
	 * that streamlineSceneReady already supplied, then mirrors the display-size result back into the
	 * game's target. Returns 0 on success.
	 */
	public int evaluateStreamlineDlss(VkCommandBuffer commands, RenderTarget output, Matrix4fc projection,
			Matrix4fc clipToPrevious, Quaternionfc orientation, float x, float y, float z, float jitterX, float jitterY,
			float near, float far, float fov, boolean reset) {
		if (!streamlineActive) return -2;
		if (srColorTarget == null) return -3;
		if (srOutputTarget == null) return -4;
		if (output == null) return -5;
		// streamlineSceneReady already set this frame's single shared constants block.
		int result = StreamlineSupport.evaluateDlss(commands.address(),
				VulkanInterop.image(srColorTarget.getColorTexture(), srColorTarget.getColorTextureView()),
				VulkanInterop.image(fgDepthTarget.getDepthTexture(), fgDepthTarget.getDepthTextureView()),
				VulkanInterop.image(fgMotionTarget.getColorTexture(), fgMotionTarget.getColorTextureView()),
				VulkanInterop.image(srOutputTarget.getColorTexture(), srOutputTarget.getColorTextureView()));
		if (result != 0) return result;
		VulkanInterop.barrier(commands);
		VulkanInterop.blitFlip(VulkanInterop.image(srOutputTarget.getColorTexture(), srOutputTarget.getColorTextureView()),
				VulkanInterop.image(output.getColorTexture(), output.getColorTextureView()), commands);
		VulkanInterop.barrier(commands);
		return 0;
	}

	/**
	 * Copies the finished scene (post effects and scene particles, before the GUI) into the hudless
	 * target and tags depth, motion vectors and hudless for this frame's present.
	 */
	public void streamlineHudless(RenderTarget scene, VkCommandBuffer commands) {
		if (!streamlineActive || hudlessTarget == null || fgDepthTarget == null || fgMotionTarget == null
				|| streamlineMotion == null || failed) return;
		if (!streamlineInputsPrepared) prepareStreamlineInputs(scene, streamlineMotion, commands);
		long timing = FrameProfiler.start(FrameProfiler.FG_HUDLESS, commands);
		VulkanInterop.barrier(commands);
		// Minecraft's final swapchain blit mirrors Y. DLSS-G composes straight into that backbuffer, so
		// hudless must be mirrored into the same orientation before it is tagged.
		VulkanInterop.blitFlip(VulkanInterop.image(scene.getColorTexture(), scene.getColorTextureView()),
				VulkanInterop.image(hudlessTarget.getColorTexture(), hudlessTarget.getColorTextureView()), commands);
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
		if (!UI_RECOMPOSITION) return;
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
		if (srColorTarget != null) { srColorTarget.destroyBuffers(); srColorTarget = null; }
		if (srOutputTarget != null) { srOutputTarget.destroyBuffers(); srOutputTarget = null; }
		if (hudlessTarget != null) { hudlessTarget.destroyBuffers(); hudlessTarget = null; }
		if (uiColorAlphaTarget != null) { uiColorAlphaTarget.destroyBuffers(); uiColorAlphaTarget = null; }
		if (uiAlphaFlippedTarget != null) { uiAlphaFlippedTarget.destroyBuffers(); uiAlphaFlippedTarget = null; }
		if (fgDepthTarget != null) { fgDepthTarget.destroyBuffers(); fgDepthTarget = null; }
		if (fgMotionTarget != null) { fgMotionTarget.destroyBuffers(); fgMotionTarget = null; }
		if (fgDepthFlip != null) { fgDepthFlip.close(); fgDepthFlip = null; }
		streamlineMotion = null;
	}

	private void noteStreamlineSkip(String reason) {
		long now = System.nanoTime();
		if (reason.equals(streamlineSkipReason) && now - streamlineSkipLogAt < 2_000_000_000L) return;
		streamlineSkipReason = reason;
		streamlineSkipLogAt = now;
		LOG.info("DLSS-G scene not marked active: {}", reason);
	}

	private void disableStreamlineFrameGeneration() {
		streamlineActive = false;
		streamlineSceneLogged = false;
		if (session != null && session.streamline() && StreamlineSupport.dlssgAvailable()) {
			StreamlineSupport.setFrameGeneration(0, 1, false, 0, 0, 0, 0, 0);
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
				: streamlineUnfocused ? " (DLSS-G inserted no generated frames; see the SDK log)" : "";
		if (windowMinimized) issue += " (paused: window minimized)";
		return "Frame generation: " + (reason.isEmpty() ? (mode.nvidia() && !resetNext ? built : mode).label().getString()
				+ (pendingWorldFrames > 30 ? " (waiting for consecutive world frames: " + ledger.reasonText() + ")" : mode.nvidia() ? " " + refreshPolicy.multiplier() + "x" : "")
				+ engine + issue : reason) + resets;
	}

	public void beginPresentBatch(int frames) {
		if (frames < 2 || frames > 8) throw new IllegalArgumentException("FG batch must contain 2..8 presents");
		presentBatch = frames; presentIndex = 0; batchRejected = false; batchesStarted++;
		long traceId = ReflexController.get().currentFrameId();
		if (FrameTrace.on(traceId)) FrameTrace.event(traceId, "FG batch begin presents=" + frames);
	}
	public boolean batchRejected() { return batchRejected; }
	public boolean inPresentBatch() { return presentBatch > 1; }
	public void beginPresent(boolean generated) { generatedPresent = generated; presentIndex++; }
	public boolean isGeneratedPresent() { return generatedPresent; }
	public void endPresentBatch() {
		if (presentBatch > 1 && !batchRejected && presentIndex == presentBatch) batchesCompleted++;
		if (presentBatch > 1) ledger.closeIfOpen(); // ended before its real present
		long traceId = ReflexController.get().currentFrameId();
		if (presentBatch > 1 && FrameTrace.on(traceId)) FrameTrace.event(traceId,
				"FG batch end presented=" + presentIndex + "/" + presentBatch + " rejected=" + batchRejected);
		presentBatch = 0; presentIndex = 0; generatedPresent = false; ready = 0;
	}
	/** One host present. Streamline handles the NVIDIA path internally; FSR presents its proxy batch here. */
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
		if (result != VK10.VK_SUCCESS) {
			batchRejected = true;
			invalidate();
		}
	}
	/** One host present; Streamline has already presented the real frame plus N interpolated frames. */
	private void onStreamlinePresentResult(int result) {
		if (FailureNotice.deviceLost(result)) {
			// Do not touch the SDK or the swapchain again: the engine already lost the device.
			streamlineProblem = "VK_ERROR_DEVICE_LOST";
			problem = streamlineProblem;
			failed = true;
			streamlineActive = false;
			ready = 0;
			StreamlineSupport.markDeviceLost();
			FailureNotice.report("DLSS Frame Generation stopped: VK_ERROR_DEVICE_LOST. Rendering natively.", true);
			ledger.reason(GenerationLedger.Shortfall.SURFACE_LOST);
			ledger.closeFrame();
			invalidate();
			return;
		}
		boolean delivered = result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR;
		lastStreamlinePresentResult = result;
		int[] state = streamlineActive ? StreamlineSupport.refreshFrameGenerationState() : null;
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

