package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.compat.BslFoliageMotion;
import com.bambooz.reflexium.compat.ComplementaryHdr;
import com.bambooz.reflexium.compat.ComplementaryMotionSource;
import com.bambooz.reflexium.compat.ComplementaryTemporalSource;
import com.bambooz.reflexium.compat.DistantHorizonsCompat;
import com.bambooz.reflexium.compat.IrisCompat;
import com.bambooz.reflexium.compat.PackMotionState;
import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.mixin.GameRendererAccessor;
import com.bambooz.reflexium.mixin.PostChainAccessor;
import com.bambooz.reflexium.mixin.PostPassAccessor;
import com.bambooz.reflexium.mixin.RenderTargetAccessor;
import com.bambooz.reflexium.mixin.VulkanCommandEncoderAccessor;
import com.bambooz.reflexium.motion.FoliageMotionPass;
import com.bambooz.reflexium.motion.MotionDebugPass;
import com.bambooz.reflexium.motion.MotionVectorPass;
import com.bambooz.reflexium.motion.ObjectMotion;
import com.bambooz.reflexium.motion.ObjectVelocityPass;
import com.bambooz.reflexium.motion.PackHandMaskPass;
import com.bambooz.reflexium.motion.PackSurfaceMotionPass;
import com.bambooz.reflexium.motion.VanillaCloudMotion;
import com.bambooz.reflexium.upscale.dlss.DlssModel;
import com.bambooz.reflexium.vulkan.ImageRef;
import com.bambooz.reflexium.vulkan.VulkanInterop;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
	* Super Resolution for vanilla's Vulkan renderer.
	* <p>
	* Each frame with a world: the textures of a render-resolution target are swapped into Minecraft's
	* main target before the world pass (so every world, hand and post-effect pass renders at render
	* resolution with a jittered projection), motion vectors are built from depth after the hand, the
	* upscaler reconstructs the scene into the display-resolution textures before vanilla's post effects, and
	* the swap is undone so post effects and the GUI draw at display resolution. Outside that window the main
	* target always holds the display-resolution textures, so screenshots, the swapchain blit and
	* window-size checks see what they expect.
	*/
public final class UpscalingRenderer {
	private static final UpscalingRenderer INSTANCE = new UpscalingRenderer();

	/** NVSDK_NGX_DLSS_Feature_Flags: MVLowRes | DepthInverted (Minecraft renders reverse-Z). */
	private static final int FEATURE_FLAGS = (1 << 1) | (1 << 3);
	/** Sign applied to the projection jitter before it is handed to DLSS and FSR (tunable for testing). +1 on both axes: lowest still-frame change in the 2026-10-13 static test (docs/DEBUGGING.md). */
	private static final float JITTER_SIGN = Float.parseFloat(System.getProperty("reflexium.dlss.jitterSign", "1"));

	private VulkanDevice device;
	private VulkanSession session;
	private final UpscalerRuntimes runtimes = new UpscalerRuntimes();
	private final JitterSequence jitter = new JitterSequence();
	private final CameraHistory history = new CameraHistory();
	private final HistoryResets resets = new HistoryResets();
	private final MaterialBindStats bindStats = new MaterialBindStats();
	private final MipBiasSamplers materialSamplers = new MipBiasSamplers();
	private final UpscalerPerformanceGuard performanceGuard = new UpscalerPerformanceGuard();
	private boolean failed;
	private volatile String lastError = "";

	// Configuration currently built.
	private Upscaler builtUpscaler = Upscaler.OFF;
	private UpscaleQuality builtMode;
	private DlssModel builtModel;
	private int builtPreset;
	private boolean builtHdr;
	private long builtPackEpoch;
	private int displayWidth;
	private int displayHeight;
	private int renderWidth;
	private int renderHeight;
	/** Window size seen by the latest frame. This is what the world renders at whenever upscaling is not applied. */
	private int windowWidth;
	private int windowHeight;
	private boolean renderSizeApplied;
	private float mipBias;
	private long irisEpoch;

	// The configuration the performance guard's native/DLSS comparison belongs to.
	private long guardPackEpoch = -1;
	private int guardWidth, guardHeight;
	private UpscaleQuality guardQuality;
	private DlssModel guardModel;
	private Upscaler guardRequested;

	// Targets, created per configuration.
	private TextureTarget renderTarget;
	private TextureTarget worldDepth;
	private TextureTarget motionTarget;
	private TextureTarget hdrInput, hdrOutput;
	/** Display-size DLSS output that SharpenPass reads; the sharpened image lands in the display target. */
	private TextureTarget sharpenSource;
	private TextureTarget packMotionBase, packComposition;
	private TextureTarget packHandBefore;
	private TextureTarget packHandMask;
	private TextureTarget opaqueTarget;
	private OutputImage reactive;

	// Passes, created on first use.
	private MotionVectorPass motionPass;
	private ObjectVelocityPass objectVelocityPass;
	private final FoliageMotionPass foliageMotionPass = new FoliageMotionPass();
	private final VanillaCloudMotion vanillaCloudMotion = new VanillaCloudMotion();
	private PackSurfaceMotionPass packSurfacePass;
	private PackHandMaskPass packHandMaskPass;
	private MotionDebugPass motionDebugPass;
	private DepthResolvePass depthResolvePass;
	private SharpenPass sharpenPass;
	private CaptureEncodePass captureEncodePass;

	// Per-frame state.
	private boolean activeThisFrame;
	private boolean swapped;
	private long worldTiming;
	private boolean worldDepthCaptured;
	/** Iris's pre-translucent depth or vanilla's own depth, when it is used without a copy. */
	private GpuTextureView directWorldDepth;
	private boolean irisOpaqueDepthApplied;
	private boolean opaqueCaptured;
	private boolean reactiveGenerated;
	private boolean reactiveMaskFailed;
	private boolean packHandCaptured;
	private boolean hdrEvaluated;
	private boolean packSurfaceApplied;
	private boolean foliageMotionApplied;
	private boolean vanillaCloudMotionApplied;
	private int handVerticesDrawn;
	private int guiBlurCalls;
	private float lastFrameMs = 16.6f;
	private long lastEvaluateNs;
	private long evaluatedFrames;
	/** True when this frame's evaluation (not a later one) ran with history reset, for frame generation. */
	private boolean frameGenerationReset;

	// Post effects that sample the scene depth need a display-resolution copy once reconstruction precedes them.
	private List<PostChain> depthProbeChains = List.of();
	private boolean postEffectsNeedDepth;
	private boolean reconstructed;
	private boolean depthResolved;
	private boolean sceneCompleted;

	// Global uniform state captured before the world pass.
	private GlobalSettingsUniform globals;
	private double glintAlpha;
	private long gameTime;
	private float worldPartialTicks;
	private int menuBlurRadius;
	private Vec3 cameraPos;
	private boolean useRgss;

	// Debug capture: armed by the HTTP thread, taken at the next evaluation.
	private volatile UpscaleCapture capture;
	private UpscaleCapture pendingOutput;
	private boolean pendingExtras;

	private UpscalingRenderer() {
	}

	public static UpscalingRenderer get() {
		return INSTANCE;
	}

	// ---------------------------------------------------------------------------------------------
	// Device lifecycle (render thread)

	public void onDeviceCreated(VulkanDevice device, VulkanSession session) {
		this.device = device;
		this.session = session;
		FrameProfiler.init(session.device(), session.physicalDevice());
		runtimes.loadFsr(session);
		failed = false;
		FrameGenerationRenderer.get().onDeviceCreated(device, session, runtimes.fsr());
		runtimes.initializeNgx();
	}

	/** Best effort: a lost device fails the GPU wait, which must not skip the remaining native teardown. */
	public void onDeviceClosing() {
		RuntimeException failure = null;
		if (device != null) {
			try { destroyResources(); } catch (RuntimeException e) { failure = e; }
		}
		try { FrameProfiler.close(); } catch (RuntimeException e) { failure = keep(failure, e); }
		try { FrameGenerationRenderer.get().onDeviceClosing(); } catch (RuntimeException e) { failure = keep(failure, e); }
		try { runtimes.shutdown(); } catch (RuntimeException e) { failure = keep(failure, e); }
		if (failure != null) ReflexiumClient.LOGGER.warn("Upscaler teardown was incomplete (device lost?)", failure);
		device = null;
		session = null;
	}

	private static RuntimeException keep(RuntimeException first, RuntimeException next) {
		if (first == null) return next;
		first.addSuppressed(next);
		return first;
	}

	// ---------------------------------------------------------------------------------------------
	// Frame hooks (render thread, called from GameRendererMixin in this order)

	/** GameRenderer.render HEAD: decide whether this frame upscales and (re)build for the current size and mode. */
	public void beginFrame(GameRenderer gameRenderer) {
		DebugSettings.onFrame();
		FrameProfiler.beginFrame();
		FrameGenerationRenderer.get().beginFrame();
		if (device != null) runtimes.pollRuntimeDownload();
		resetFrameState();
		WindowRenderState window = gameRenderer.gameRenderState().windowRenderState;
		windowWidth = window.width;
		windowHeight = window.height;
		ReflexiumConfig config = ReflexiumConfig.get();
		updatePerformanceGuard(gameRenderer, window, config);
		UpscaleQuality mode = config.upscaleQuality();
		Upscaler selected = effectiveUpscaler();
		if (selected == Upscaler.OFF) mode = UpscaleQuality.NATIVE_AA;
		if (!Boolean.getBoolean("reflexium.disableDhTemporal")) ShaderPacks.configureUpscaling(available(selected) && !failed && DistantHorizonsCompat.canUpscale());
		boolean wanted = (available(selected) || FrameGenerationRenderer.get().wantsMotion()) && !failed && gameRenderer.gameRenderState().shouldRenderLevel && !window.isMinimized
				&& window.width > 0 && window.height > 0 && ShaderPacks.canUpscale() && DistantHorizonsCompat.canUpscale();
		DistantHorizonsCompat.configureUpscaling(wanted);
		wanted &= DistantHorizonsCompat.canUpscale();
		if (Boolean.getBoolean("reflexium.disableDhTargets")) wanted = false;
		if (!wanted) {
			ObjectMotion.get().beginFrame(false, false);
			if (renderSizeApplied) {
				restoreDisplaySize(gameRenderer, window);
			}
			return;
		}
		try {
			if (needsRebuild(mode, selected, window)) {
				build(mode, window.width, window.height);
			}
			if (!renderSizeApplied) {
				applyRenderSize(gameRenderer);
			}
			refreshMipBias();
			if (irisEpoch != IrisCompat.epoch()) {
				// A reloaded or switched pack: camera, object and hand history all describe the previous pipeline.
				irisEpoch = IrisCompat.epoch();
				resets.request(ResetReason.REBUILD);
				history.havePrevious = false;
			}
			activeThisFrame = true;
			jitter.advance(builtUpscaler, displayWidth, DebugSettings.jitterEnabled());
		} catch (RuntimeException e) {
			fail("setup failed", e);
			restoreDisplaySize(gameRenderer, window);
		}
	}

	private void resetFrameState() {
		activeThisFrame = false;
		guiBlurCalls = 0;
		hdrEvaluated = false;
		packSurfaceApplied = false;
		foliageMotionApplied = false;
		vanillaCloudMotionApplied = false;
		DeferredEffects.applied = false;
		DeferredEffects.generatedCopies = 0;
		irisOpaqueDepthApplied = false;
		ComplementaryHdr.beginFrame();
		DistantHorizonsCompat.beginFrame();
		worldDepthCaptured = false;
		directWorldDepth = null;
		opaqueCaptured = false;
		reactiveGenerated = false;
		packHandCaptured = false;
		history.haveHandProjection = false;
		reconstructed = false;
		depthResolved = false;
		sceneCompleted = false;
	}

	/** One native/DLSS comparison per configuration; any change of pack, size, quality, model or request starts a new one. */
	private void updatePerformanceGuard(GameRenderer gameRenderer, WindowRenderState window, ReflexiumConfig config) {
		Upscaler resolved = runtimes.resolve(config.upscaler(), ShaderPacks.isDrawingPack());
		long packEpoch = IrisCompat.configurationEpoch();
		if (guardPackEpoch != packEpoch || guardWidth != window.width || guardHeight != window.height
				|| guardQuality != config.upscaleQuality() || guardModel != config.dlssModel() || guardRequested != config.upscaler()) {
			performanceGuard.reset(); guardPackEpoch = packEpoch; guardWidth = window.width; guardHeight = window.height;
			guardQuality = config.upscaleQuality(); guardModel = config.dlssModel(); guardRequested = config.upscaler();
		}
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		boolean comparing = resolved == Upscaler.DLSS && !failed;
		if (comparing) performanceGuard.update(now, mc.level != null && mc.gui.screen() == null && !mc.isPaused()
				&& gameRenderer.gameRenderState().shouldRenderLevel && !window.isMinimized,
				FrameProfiler.worldProbeSamples(), FrameProfiler.worldProbeMs());
		FrameProfiler.worldProbe(comparing ? performanceGuard.sampleTag(now) : 0);
		FrameProfiler.automatic(comparing && performanceGuard.probing() || config.frameGeneration().nvidia());
	}

	private boolean needsRebuild(UpscaleQuality mode, Upscaler selected, WindowRenderState window) {
		return mode != builtMode || selected != builtUpscaler || ReflexiumConfig.get().dlssModel() != builtModel || requestedPreset(mode) != builtPreset
				|| window.width != displayWidth || window.height != displayHeight || renderTarget == null
				|| builtHdr != wantsPackHdr(selected) || builtHdr && builtPackEpoch != ComplementaryHdr.epoch();
	}

	/** GameRenderer.resize TAIL: vanilla just sized the world targets to the window, put them back to render size. */
	public void afterVanillaResize(GameRenderer gameRenderer) {
		if (renderSizeApplied) {
			renderSizeApplied = false;
			if (activeThisFrame) {
				applyRenderSize(gameRenderer);
			}
		}
	}

	/** Captures GlobalSettingsUniform.update so the world pass sees the render size in ScreenSize. */
	public void captureGlobals(GlobalSettingsUniform uniform, double glintAlpha, long gameTime, float worldPartialTicks,
			int menuBlurRadius, Vec3 cameraPos, boolean useRgss) {
		this.globals = uniform;
		this.glintAlpha = glintAlpha;
		this.gameTime = gameTime;
		this.worldPartialTicks = worldPartialTicks;
		this.menuBlurRadius = menuBlurRadius;
		this.cameraPos = cameraPos;
		this.useRgss = useRgss;
	}

	/** Before GameRenderer.renderLevel: swap the render-resolution textures into the main target. */
	public void beforeLevel(GameRenderer gameRenderer) {
		worldTiming = FrameProfiler.enabled() && device != null ? FrameProfiler.start(FrameProfiler.WORLD, commandBuffer()) : 0;
		if (!activeThisFrame) {
			return;
		}
		RenderTarget main = gameRenderer.mainRenderTarget();
		swap(main, renderTarget);
		swapped = true;
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(main.getColorTexture(),
				gameRenderer.gameRenderState().guiRenderState.clearColorOverride, main.getDepthTexture(), 0.0);
		updateGlobals(renderWidth, renderHeight);
		if (packHandMask != null && ShaderPacks.isDrawingPack()) RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(packHandMask.getDepthTexture(), 0.0);
		history.currentViewIdentity = viewIdentity(gameRenderer);
		ObjectMotion.get().beginFrame(DebugSettings.objectMotion(), DebugSettings.drawStaticObjects());
	}

	/** Camera mode and entity: a switch between them is a cut even with little translation. */
	private static int viewIdentity(GameRenderer gameRenderer) {
		Entity entity = Minecraft.getInstance().getCameraEntity();
		int mode = gameRenderer.gameRenderState().optionsRenderState.cameraType.ordinal();
		return 31 * (entity == null ? -1 : entity.getId()) + mode;
	}

	/** Jitters the level projection and records the matrices motion vectors need. */
	public Matrix4f jitterLevelProjection(Matrix4f projection, CameraRenderState camera) {
		if (!swapped) {
			return projection;
		}
		Matrix4f jittered = jitter.apply(projection);
		history.recordLevel(projection, jittered, camera);
		return jittered;
	}

	/** Jitters the first-person hand projection the same way, so DLSS sees consistent sampling. */
	public Matrix4f jitterHudProjection(Matrix4f projection) {
		return swapped ? jitter.apply(projection) : projection;
	}

	/** Shader pack gbuffer projections get the same jitter as the level. */
	public Matrix4f jitterShaderProjection(Matrix4fc projection) {
		return swapped ? jitter.apply(new Matrix4f(projection)) : new Matrix4f(projection);
	}

	/** Records the projection the hand is drawn with: vanilla's hud projection and its jittered copy. */
	public void noteHandProjection(Matrix4fc unjittered, Matrix4fc jittered) {
		// A shader pack draws its own hand into the pack chain, and its projection was noted when it drew.
		if (!swapped || packHandCaptured) return;
		history.handProjection.set(unjittered);
		history.handProjectionJittered.set(jittered);
		history.haveHandProjection = true;
	}

	/** Vitrail multiplies its hand volume (jittered) and bob into one matrix: remove the left-multiplied jitter again. */
	public void noteHandProjectionDrawn(Matrix4fc drawnJittered) {
		if (!swapped) return;
		history.handProjectionJittered.set(drawnJittered);
		history.handProjection.translation(-jitter.ndcX(), -jitter.ndcY(), 0.0f).mul(drawnJittered);
		history.haveHandProjection = true;
	}

	/**
	 * The sampler to bind for a color material while upscaling. Idempotent: a sampler that is already a
	 * corrected replacement comes back unchanged. {@code path} is a {@link MaterialBinding} index.
	 */
	public GpuSampler materialSampler(GpuSampler sampler, int path) {
		if (!swapped) return sampler;
		GpuSampler result = materialSamplers.get(sampler, mipBias);
		float applied = result instanceof AppliedLodBias bias ? bias.reflexium$appliedLodBias() : Float.NaN;
		float expected = MipBiasSamplers.appliedBias(sampler) + mipBias;
		bindStats.record(path, applied, mipBias != 0 && (materialSamplers.owns(sampler) || Math.abs(applied - expected) < 1.0e-4f));
		return result;
	}

	public static Float creatingSamplerBias() { return MipBiasSamplers.creatingBias(); }

	/** Vitrail draws the hand inside its level chain, before vanilla's HUD depth target exists. */
	public void capturePackHandBefore() {
		if (!swapped || packHandBefore == null) return;
		VkCommandBuffer commands = commandBuffer();
		long timing = FrameProfiler.start(FrameProfiler.PACK_HAND, commands);
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getDepthTexture(), packHandBefore.getDepthTexture(),
				0, 0, 0, 0, 0, renderWidth, renderHeight);
		FrameProfiler.end(FrameProfiler.PACK_HAND, timing, commands);
	}

	public void capturePackHandAfter() {
		if (!swapped || packHandBefore == null) return;
		VkCommandBuffer commands = commandBuffer();
		long timing = FrameProfiler.start(FrameProfiler.PACK_HAND, commands);
		packHandMaskPass.render(packHandMask, packHandBefore.getDepthTextureView(),
				Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView());
		packHandCaptured = true;
		FrameProfiler.end(FrameProfiler.PACK_HAND, timing, commands);
	}

	public boolean supportsReactiveMask() {
		return swapped && builtUpscaler == Upscaler.FSR && opaqueTarget != null && !ShaderPacks.isDrawingPack();
	}

	/** Called after opaque rendering ends, before any transparency writes to the scene. */
	public void captureOpaqueColor() {
		if (!supportsReactiveMask()) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		VkCommandBuffer commands = commandBuffer();
		long timing = FrameProfiler.start(FrameProfiler.OPAQUE_COPY, commands);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), opaqueTarget.getColorTexture(),
				0, 0, 0, 0, 0, renderWidth, renderHeight);
		FrameProfiler.end(FrameProfiler.OPAQUE_COPY, timing, commands);
		opaqueCaptured = true;
	}

	/** render3dHud HEAD: keep the world depth, which vanilla clears before drawing the hand. */
	public void captureWorldDepth(GameRenderer gameRenderer) {
		if (!swapped || worldDepthCaptured) {
			return;
		}
		RenderTarget main = gameRenderer.mainRenderTarget();
		if (opaqueCaptured && reactive != null && !reactiveMaskFailed) {
			generateReactiveMask(main);
		}
		GpuTextureView opaqueDepth = IrisCompat.opaqueDepth(renderWidth, renderHeight);
		if (opaqueDepth != null) {
			directWorldDepth = opaqueDepth;
			irisOpaqueDepthApplied = true;
		} else if (usesDirectDepth()) {
			// Vanilla uses its separate hand depth while active.
			directWorldDepth = main.getDepthTextureView();
		} else {
			VkCommandBuffer commands = commandBuffer();
			long timing = FrameProfiler.start(FrameProfiler.DEPTH, commands);
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getDepthTexture(), worldDepth.getDepthTexture(),
					0, 0, 0, 0, 0, renderWidth, renderHeight);
			FrameProfiler.end(FrameProfiler.DEPTH, timing, commands);
		}
		worldDepthCaptured = true;
	}

	/** FSR's reactive mask from the opaque and final scene colors. A failure only disables the mask. */
	private void generateReactiveMask(RenderTarget main) {
		try {
			VkCommandBuffer commands = commandBuffer();
			long timing = FrameProfiler.start(FrameProfiler.REACTIVE, commands);
			VulkanInterop.barrier(commands);
			int result = runtimes.fsr().generateReactive(commands.address(), VulkanInterop.image(opaqueTarget.getColorTexture(), opaqueTarget.getColorTextureView()),
					VulkanInterop.image(main.getColorTexture(), main.getColorTextureView()), reactive.asNgx());
			VulkanInterop.barrier(commands);
			if (result != 0) throw new IllegalStateException("FSR reactive mask returned " + result);
			FrameProfiler.end(FrameProfiler.REACTIVE, timing, commands);
			reactiveGenerated = true;
		} catch (RuntimeException e) {
			reactiveMaskFailed = true;
			lastError = "reactive mask disabled: " + e;
			ReflexiumClient.LOGGER.warn("FSR reactive mask failed; continuing with FSR shading-change detection", e);
		}
	}

	/** Shader packs draw their hand inside renderLevel and suppress vanilla's render3dHud. */
	public void afterLevel(GameRenderer gameRenderer) {
		if (swapped && !worldDepthCaptured) {
			captureWorldDepth(gameRenderer);
			renderMotionVectors(gameRenderer, false);
		}
	}

	/** render3dHud RETURN: motion vectors, with the hand (now in {@code handDepth}) given its own animation vectors. */
	public void renderMotionVectors(GameRenderer gameRenderer, boolean handInHud3dTarget) {
		if (!swapped || !worldDepthCaptured) {
			return;
		}
		RenderTarget handTarget = handInHud3dTarget
				? ((GameRendererAccessor) gameRenderer).reflexium$hud3DTarget()
				: gameRenderer.mainRenderTarget();
		// A pack that skips the hand still needs the cleared mask, never its world depth as hand coverage.
		GpuTextureView handDepth = packHandMask != null && ShaderPacks.isDrawingPack()
				? packHandMask.getDepthTextureView() : handTarget.getDepthTextureView();
		var dh = DistantHorizonsCompat.depthFrame(renderWidth, renderHeight);
		if (DistantHorizonsCompat.rejectedFrame()) {
			worldDepthCaptured = false;
			history.havePrevious = false;
			resets.request(ResetReason.LOADING);
			ObjectMotion.get().endFrame();
			return;
		}
		float far = dh == null ? history.cameraFar : Math.max(history.cameraFar, dh.farPlane() * 2.0f);
		if (far != history.motionFar) {
			resets.request(ResetReason.REBUILD);
			history.havePrevious = false;
		}
		history.motionFar = far;
		history.depthTransform.set(DistantHorizonsCompat.depthTransform(history.cameraNear, history.cameraFar, history.motionFar));
		Object level = Minecraft.getInstance().level;
		ResetReason cut = history.cutReason(level, System.nanoTime() - lastEvaluateNs);
		if (cut != null) {
			resets.request(cut);
			// Camera, object and hand history all restart together.
			history.restart();
			ObjectMotion.get().discardHistory();
		}
		Vec3 delta = history.delta();
		Matrix4f currentToPrevious = DlssMath.reprojection(history.previousUnjittered, history.currentJittered,
				(float) delta.x, (float) delta.y, (float) delta.z);
		boolean reset = resets.pending();
		try {
			if (!DistantHorizonsCompat.canUpscale()) throw new IllegalStateException("Distant Horizons input capture failed");
			VkCommandBuffer commands = commandBuffer();
			long timing = FrameProfiler.start(FrameProfiler.MOTION, commands);
			GpuTextureView sceneDepth = sceneDepth();
			GpuTextureView clouds = vanillaCloudMotion.capture(gameRenderer, renderWidth, renderHeight, jitter.apply(history.currentProjection), reset);
			vanillaCloudMotionApplied = clouds != null;
			Matrix4f cloudToPrevious = DlssMath.reprojection(history.previousUnjittered, history.currentJittered,
					(float) delta.x + vanillaCloudMotion.displacement(), (float) delta.y, (float) delta.z);
			motionPass.render(motionTarget, sceneDepth, handDepth,
					currentToPrevious, jitter.ndcX(), jitter.ndcY(), dh, history.currentJittered, history.depthTransform,
					(float) delta.x, (float) delta.y, (float) delta.z, reset, clouds, cloudToPrevious);
			FrameProfiler.end(FrameProfiler.MOTION, timing, commands);
			timing = FrameProfiler.start(FrameProfiler.OBJECTS, commands);
			// Match the merged depth, so a hidden object cannot overwrite a closer LOD's camera vector.
			objectVelocityPass.render(ObjectMotion.get(), motionTarget, motionTarget.getDepthTextureView(), handDepth,
					new Matrix4f(history.depthTransform).mul(history.currentJittered), history.currentUnjittered, history.previousUnjittered,
					(float) delta.x, (float) delta.y, (float) delta.z, reset);
			handVerticesDrawn = 0;
			if (history.haveHandProjection && DebugSettings.handMotion()) {
				handVerticesDrawn = objectVelocityPass.renderHand(ObjectMotion.get(), motionTarget, sceneDepth, handDepth,
						history.handProjectionJittered, history.handProjection, history.handProjectionPrevious, reset);
			}
			FrameProfiler.end(FrameProfiler.OBJECTS, timing, commands);
			GpuTextureView foliage = BslFoliageMotion.surface();
			if (foliage != null && DebugSettings.objectMotion()) {
				foliageMotionPass.render(motionTarget, foliage, sceneDepth, handDepth);
				foliageMotionApplied = true;
			}
		} catch (RuntimeException e) {
			worldDepthCaptured = false;
			fail("motion vector pass failed", e);
		} finally {
			ObjectMotion.get().endFrame();
		}
		history.previousLevel = level;
	}

	private GpuTextureView sceneDepth() {
		return directWorldDepth != null ? directWorldDepth : worldDepth.getDepthTextureView();
	}

	/** End of the pack's linear scene, before composite5's bloom, exposure, tone mapping and grading. */
	public void reconstructPackHdr(Object pass, Object targets) {
		if (!swapped || !builtHdr || hdrEvaluated) return;
		try { reconstructPackHdrInputs(pass, targets); }
		catch (RuntimeException e) { fail("HDR pack integration failed", e); throw e; }
	}

	private void reconstructPackHdrInputs(Object pass, Object targets) {
		GameRenderer renderer = Minecraft.getInstance().gameRenderer;
		afterLevel(renderer); // Geometry and hand are complete here, before the pack's final chain returns.
		if (DistantHorizonsCompat.rejectedFrame()) return;
		if (!worldDepthCaptured) throw new IllegalStateException("HDR boundary has no aligned world depth");
		if (ComplementaryMotionSource.ready() && !resets.pending()) {
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(motionTarget.getColorTexture(), packMotionBase.getColorTexture(), 0, 0, 0, 0, 0, renderWidth, renderHeight);
			encoder.copyTextureToTexture(motionTarget.getDepthTexture(), packMotionBase.getDepthTexture(), 0, 0, 0, 0, 0, renderWidth, renderHeight);
			packSurfacePass.render(packMotionBase, motionTarget, packComposition, ComplementaryHdr.geometrySurface(targets, 14),
					ComplementaryHdr.geometrySurface(targets, 15), packHandMask.getDepthTextureView());
			packSurfaceApplied = true;
		}
		GpuTextureView color = ComplementaryHdr.input(pass, targets);
		if (color.texture().getFormat() != GpuFormat.RGBA16_FLOAT || color.getWidth(0) != renderWidth || color.getHeight(0) != renderHeight) {
			throw new IllegalStateException("HDR boundary requires input-sized RGBA16F colortex0, got " + color.texture().getFormat());
		}
		VkCommandBuffer commands = commandBuffer();
		long timing = FrameProfiler.start(FrameProfiler.HDR_COPY, commands);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(color.texture(), hdrInput.getColorTexture(),
				0, 0, 0, 0, 0, renderWidth, renderHeight);
		FrameProfiler.end(FrameProfiler.HDR_COPY, timing, commands);
		evaluate(hdrInput);
		hdrEvaluated = true;
	}

	/**
	 * Before GameRenderer.applyPostEffects: reconstruct the scene into the display textures and undo the swap.
	 * Vendor guidance places super resolution before post processing, so vanilla post effects and the GUI
	 * run at display resolution on the reconstructed image.
	 */
	public void reconstructScene(GameRenderer gameRenderer) {
		if (!swapped) {
			if (worldTiming != 0) FrameProfiler.end(FrameProfiler.WORLD, worldTiming, commandBuffer());
			return;
		}
		RenderTarget main = gameRenderer.mainRenderTarget();
		try {
			if (hdrEvaluated) {
				// Pack final already wrote the display target, after the HDR evaluation and tone mapping.
			} else if (worldDepthCaptured && !builtHdr) {
				evaluate(main);
			} else {
				// Pack warmup can replace the level call while still presenting a loading frame.
				// Keep that frame visible and start fresh when actual scene rendering resumes.
				main.blitAndBlendToTexture(renderTarget.getColorTextureView(), renderTarget.getDepthTextureView());
				resets.request(ResetReason.LOADING);
			}
		} catch (RuntimeException e) {
			fail("evaluate failed", e);
			main.blitAndBlendToTexture(renderTarget.getColorTextureView(), renderTarget.getDepthTextureView());
		} finally {
			swap(main, renderTarget);
			swapped = false;
			updateGlobals(displayWidth, displayHeight);
		}
		FrameProfiler.end(FrameProfiler.WORLD, worldTiming, commandBuffer());
		reconstructed = true;
		if (worldDepthCaptured && !failed && (DeferredEffects.pending() || postEffectsNeedDepth(gameRenderer))) {
			resolveDisplayDepth(main);
		}
	}

	/** After GameRenderer.applyPostEffects: overlays, frame generation, and previous-frame promotion, once. */
	public void completeScene(GameRenderer gameRenderer) {
		if (!reconstructed || sceneCompleted) {
			return;
		}
		sceneCompleted = true;
		RenderTarget main = gameRenderer.mainRenderTarget();
		boolean fgReset = frameGenerationReset;
		if (DebugSettings.foliageDepthDebug && foliageMotionApplied) {
			foliageMotionPass.render(main, BslFoliageMotion.surface(), sceneDepth(), packHandMask.getDepthTextureView(), true);
		}
		if (DebugSettings.motionVectorView() && worldDepthCaptured && motionTarget != null) {
			drawMotionVectorView(main);
		}
		if (worldDepthCaptured && !failed && FrameGenerationRenderer.get().wantsMotion()) {
			Vec3 delta = history.delta();
			// Clip transforms describe the drawn volume, including view effects. Lens scalars stay physical.
			FrameGenerationRenderer.get().sceneReady(main, motionTarget, commandBuffer(), new Matrix4f(history.depthTransform).mul(history.currentProjection),
					DlssMath.reprojection(new Matrix4f(history.previousDepthTransform).mul(history.previousUnjittered),
							new Matrix4f(history.depthTransform).mul(history.currentUnjittered), (float) delta.x, (float) delta.y, (float) delta.z),
					history.currentOrientation, (float) history.currentCamera.x, (float) history.currentCamera.y, (float) history.currentCamera.z,
					DebugSettings.jitterSignX(JITTER_SIGN) * jitter.x(), DebugSettings.jitterSignY(JITTER_SIGN) * jitter.y(),
					history.cameraNear, history.motionFar, history.cameraFov, lastFrameMs, fgReset);
		}
		BslFoliageMotion.endFrame();
		history.promote(worldDepthCaptured);
		jitter.promote();
	}

	/** GameRenderer.render finally: restore the target even when rendering throws. */
	public void endFrame(GameRenderer gameRenderer) {
		ObjectMotion.get().endFrame();
		if (swapped) {
			swap(gameRenderer.mainRenderTarget(), renderTarget);
			swapped = false;
			updateGlobals(displayWidth, displayHeight);
		}
		if (activeThisFrame && !worldDepthCaptured) {
			// A frame without a world (menus): the next world frame starts a new history.
			resets.request(ResetReason.MENU);
		}
		if (reconstructed && !sceneCompleted) {
			// Post effects threw after reconstruction. Do not carry half-promoted history forward.
			resets.request(ResetReason.REBUILD);
			history.havePrevious = false;
		}
		bindStats.publish();
	}

	// ---------------------------------------------------------------------------------------------
	// Queries for mixins and shader pack integrations

	public boolean isUpscalingThisFrame() {
		return swapped;
	}

	public boolean isUpscalingRequestedThisFrame() { return activeThisFrame; }
	public void noteGuiBlur() { guiBlurCalls++; }
	public boolean packHdrThisFrame() { return builtHdr && hdrEvaluated && swapped; }
	/** reconstructPackHdr's own precondition: composite5 will evaluate (or has evaluated) this frame. */
	public boolean packHdrBoundaryDue() { return builtHdr && swapped; }
	public int outputWidth() { return displayWidth; }
	public int outputHeight() { return displayHeight; }
	public boolean displayDepthResolved() { return depthResolved; }
	public GpuTextureView packHdrColor() { return hdrOutput.getColorTextureView(); }
	public GpuTextureView packDisplayColor() { return renderTarget.getColorTextureView(); }
	public long historyResetFrames() { return resets.frames(); }
	public int presetHint() { return builtPreset; }

	/** Lines are drawn at render resolution while upscaling, so their width shrinks with it (never below one pixel). */
	public float scaleLineWidth(float width) {
		if (swapped && renderWidth > 0 && displayWidth > 0 && renderWidth < displayWidth) {
			float scale = (float) renderWidth / (float) displayWidth;
			return Math.max(1.0f, width * scale);
		}
		return width;
	}

	/** Shader pack previous/current jitter uniforms: {@code set(out, x, y, previousX, previousY)} in NDC. */
	public void writePackJitter(Object out, Method set) throws ReflectiveOperationException {
		set.invoke(out, jitter.ndcX(), jitter.ndcY(), jitter.previousNdcX(), jitter.previousNdcY());
	}

	public Matrix4fc foliageProjection(boolean previous) { return previous ? history.previousUnjittered : history.currentUnjittered; }

	public Vector4f foliageMotionState(float previousTime) {
		Vec3 delta = history.delta();
		return new Vector4f((float) delta.x, (float) delta.y, (float) delta.z, previousTime);
	}

	public boolean usesTransientUniforms() {
		// FSR showed no repeatable gain and a state-sensitive regression; retain its existing upload path.
		return DebugSettings.fastUniforms && builtUpscaler != Upscaler.FSR;
	}

	public boolean usesDirectDepth() {
		// The FSR candidate did not retain a measurable frame-time benefit in the paired retest.
		return DebugSettings.directDepth && builtUpscaler != Upscaler.FSR;
	}

	public boolean isReady() {
		return runtimes.anyReady();
	}

	public boolean dlssReady() { return runtimes.dlssReady(); }

	/** The configured upscaler after availability, shader pack state and the automatic native/DLSS comparison. */
	public Upscaler effectiveUpscaler() {
		Upscaler requested = ReflexiumConfig.get().upscaler();
		Upscaler resolved = runtimes.resolve(requested, ShaderPacks.isDrawingPack());
		return resolved == Upscaler.DLSS && (performanceGuard.probing() && performanceGuard.nativePhase()
				|| requested == Upscaler.AUTO && performanceGuard.nativeWins()) ? Upscaler.OFF : resolved;
	}

	public boolean available(Upscaler value) {
		return runtimes.available(value);
	}

	/** Clears a previous failure so a mode change retries. */
	public void onModeChanged() {
		failed = false;
		lastError = "";
		runtimes.retry();
		FrameGenerationRenderer.get().retry();
	}

	/** The command buffer RenderPearl is currently recording for this frame. */
	public VkCommandBuffer commandBuffer() {
		return ((VulkanCommandEncoderAccessor) device.createCommandEncoder()).reflexium$commandBuffer();
	}

	/** The open frame command buffer for profiling scopes outside this class, or null before the device exists. */
	public VkCommandBuffer profilingCommands() {
		return device == null ? null : commandBuffer();
	}

	/** Submits pending work and waits for the device to go idle, before resources the GPU may still read are replaced. */
	public void waitForGpu() {
		if (device != null) {
			device.createCommandEncoder().submit();
			int result = VK10.vkDeviceWaitIdle(session.device());
			if (result != VK10.VK_SUCCESS) throw new IllegalStateException("wait before releasing upscaler resources: " + result);
		}
	}

	/** Exchanges the textures and sizes of two targets, so passes drawing into {@code a} write into {@code b}'s storage. */
	public static void swap(RenderTarget a, RenderTarget b) {
		RenderTargetAccessor accA = (RenderTargetAccessor) a;
		RenderTargetAccessor accB = (RenderTargetAccessor) b;

		GpuTexture color = a.getColorTexture();
		GpuTextureView colorView = a.getColorTextureView();
		GpuTexture depth = a.getDepthTexture();
		GpuTextureView depthView = a.getDepthTextureView();
		int width = a.width;
		int height = a.height;

		accA.reflexium$setColorTexture(b.getColorTexture());
		accA.reflexium$setColorTextureView(b.getColorTextureView());
		accA.reflexium$setDepthTexture(b.getDepthTexture());
		accA.reflexium$setDepthTextureView(b.getDepthTextureView());
		a.width = b.width;
		a.height = b.height;

		accB.reflexium$setColorTexture(color);
		accB.reflexium$setColorTextureView(colorView);
		accB.reflexium$setDepthTexture(depth);
		accB.reflexium$setDepthTextureView(depthView);
		b.width = width;
		b.height = height;
		IrisCompat.syncTargetVersions(a);
		IrisCompat.syncTargetVersions(b);
	}

	// ---------------------------------------------------------------------------------------------
	// Status

	public String statusLine() {
		UpscaleQuality mode = ReflexiumConfig.get().upscaleQuality();
		Upscaler selected = effectiveUpscaler();
		if (ReflexiumConfig.get().upscaler() == Upscaler.OFF) return "Upscaling: off";
		if (DistantHorizonsCompat.waitingForFrame()) return "Upscaling: waiting for Distant Horizons";
		if (performanceGuard.nativeWins() && ReflexiumConfig.get().upscaler() == Upscaler.AUTO) return String.format(Locale.ROOT,
				"Upscaling: native (DLSS not faster here: %.2f vs %.2f ms)", performanceGuard.dlssMs(), performanceGuard.nativeMs());
		if (performanceGuard.probing() && runtimes.resolve(ReflexiumConfig.get().upscaler(), ShaderPacks.isDrawingPack()) == Upscaler.DLSS) return "Upscaling: " + (performanceGuard.nativePhase() ? "native" : "DLSS") + " (measuring native vs DLSS)";
		if (!available(selected)) return "Upscaling: " + runtimes.unavailableReason(selected);
		String name = selected == Upscaler.FSR ? "FSR 3.1" : "DLSS";
		if (failed) {
			return name + ": stopped after an error (see log); change upscaling settings to retry";
		}
		if (!ShaderPacks.canUpscale()) {
			return name + ": " + ShaderPacks.upscalingProblem();
		}
		if (!DistantHorizonsCompat.canUpscale()) return name + ": Distant Horizons integration unavailable (see log)";
		if (renderTarget == null) {
			return name + ": " + mode.displayName() + " (waiting for a world)";
		}
		return String.format(Locale.ROOT, "%s: %s %dx%d -> %dx%d", name, mode.displayName(), renderWidth, renderHeight, displayWidth, displayHeight)
				+ (!performanceGuard.probing() ? String.format(Locale.ROOT, performanceGuard.nativeWins() ? " (DLSS not faster here: %.2f vs %.2f ms)" : " (DLSS %.2f vs native %.2f ms)", performanceGuard.dlssMs(), performanceGuard.nativeMs()) : "");
	}

	/** Debug harness snapshot (see DebugServer). Values describe the most recent frame. */
	public record DebugInfo(boolean active, String builtUpscaler, int jitterIndex, float jitterX, float jitterY,
			float jitterSignX, float jitterSignY, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
			String lastError, int capturedVertices, int drawnVertices, int entities, int blockEntities, float mipBias, boolean reactiveMask,
			long evaluatedFrames, boolean packHandMask) {
	}

	public DebugInfo debugInfo() {
		// The upscaled render size only counts while upscaling is applied; otherwise the world renders at the window size.
		boolean upscaling = renderSizeApplied && ReflexiumConfig.get().upscaler() != Upscaler.OFF;
		return new DebugInfo(activeThisFrame, builtUpscaler.serializedName(), jitter.index(), jitter.x(), jitter.y(),
				DebugSettings.jitterSignX(JITTER_SIGN), DebugSettings.jitterSignY(JITTER_SIGN),
				upscaling ? renderWidth : windowWidth, upscaling ? renderHeight : windowHeight, windowWidth, windowHeight, lastError,
				ObjectMotion.get().capturedVertices(), ObjectMotion.get().drawnVertices(), ObjectMotion.get().trackedEntities(),
				ObjectMotion.get().trackedBlockEntities(), mipBias, reactiveGenerated, evaluatedFrames, packHandCaptured);
	}

	/**
	 * Observed upscaler inputs for the harness status and captures: what the last frame actually bound and
	 * drew, as distinct from the settings requested. Runs on the render thread.
	 */
	public JsonObject observedInputs() {
		return observedInputs(false);
	}

	private JsonObject observedInputs(boolean currentFrame) {
		JsonObject o = new JsonObject();
		o.addProperty("hdr_requested", ComplementaryHdr.ENABLED);
		o.addProperty("hdr_sources_verified", ComplementaryHdr.ready());
		o.addProperty("hdr_built", builtHdr);
		o.addProperty("hdr_evaluated_this_frame", hdrEvaluated);
		o.addProperty("pack_surface_motion", packSurfaceApplied);
		o.addProperty("bsl_foliage_motion", foliageMotionApplied);
		o.addProperty("bsl_foliage_surface_cleared", foliageMotionApplied && BslFoliageMotion.clearedThisFrame());
		o.addProperty("vanilla_cloud_motion", vanillaCloudMotionApplied);
		o.addProperty("post_upscale_particles", DeferredEffects.applied);
		o.addProperty("particle_generated_copies", DeferredEffects.generatedCopies);
		o.addProperty("vanilla_cloud_previous_displacement_x", vanillaCloudMotion.displacement());
		o.addProperty("primary_depth_source", irisOpaqueDepthApplied ? "iris_pre_translucent" : worldDepthCaptured ? "vanilla_world" : "not_captured");
		if (foliageMotionApplied) {
			o.addProperty("bsl_previous_time", BslFoliageMotion.previousTime());
			o.addProperty("bsl_current_time", BslFoliageMotion.currentTime());
		}
		o.addProperty("pack_surface_sources_verified", ComplementaryMotionSource.ready());
		o.addProperty("blended_surface_strategy", "background vectors plus FSR contribution masks; DLSS blended layers remain unsupported");
		o.addProperty("gui_blur_calls_this_frame", guiBlurCalls);
		o.addProperty("menu_blur_radius_setting", menuBlurRadius);
		JsonArray postIds = new JsonArray();
		for (PostChain chain : ((GameRendererAccessor) Minecraft.getInstance().gameRenderer).reflexium$appliedPostEffects()) postIds.add(chain.id().toString());
		o.add("applied_post_effects", postIds);
		o.addProperty("mip_bias_requested", mipBias);
		o.addProperty("mip_bias_offset", DebugSettings.mipBiasOffset());
		o.addProperty("mip_bias_vendor_baseline", DlssMath.VENDOR_MIP_BASELINE);
		o.add("material_binds", bindStats.toJson(currentFrame));
		o.addProperty("material_replacements", materialSamplers.size());
		o.addProperty("jitter_phases", jitter.phases());
		o.addProperty("jitter_index", jitter.index());
		resets.write(o, currentFrame);
		o.add("physical_camera", history.physicalJson());
		o.addProperty("hand_vertices_drawn", handVerticesDrawn);
		o.addProperty("hand_slots", ObjectMotion.get().handSlots());
		o.addProperty("hand_motion", DebugSettings.handMotion());
		o.addProperty("post_effects_need_depth", postEffectsNeedDepth);
		o.addProperty("post_depth_resolved", depthResolved);
		o.addProperty("requested_preset", builtPreset);
		o.addProperty("requested_preset_is_hint", true);
		o.addProperty("pack_resolve_bypass_requested", DebugSettings.packResolveBypass());
		o.addProperty("pack_resolve_bypass_applied", ComplementaryTemporalSource.applied());
		o.addProperty("pack_pre_filters_requested", DebugSettings.packPreFilters());
		o.addProperty("pack_source_edits", String.join(",", new TreeSet<>(ComplementaryTemporalSource.appliedEdits())));
		return o;
	}

	// ---------------------------------------------------------------------------------------------
	// Debug capture

	/**
	 * Arms one capture of the next evaluated frame (HTTP thread). Throws when one is already pending.
	 * The normal frame path only reads one volatile field and does no readback while this is null.
	 */
	public synchronized UpscaleCapture requestCapture(boolean extras) {
		UpscaleCapture pending = capture;
		if (pending != null && !pending.cancelled() && !pending.metadata.isDone()) {
			throw new IllegalStateException("a capture is already pending");
		}
		UpscaleCapture request = new UpscaleCapture(extras);
		capture = request;
		return request;
	}

	public synchronized void cancelCapture(UpscaleCapture request) {
		request.cancel();
		if (capture == request) capture = null;
	}

	/** Before the evaluation: metadata and the input image. The output follows in {@link #finishCapture()}. */
	private void takeCapture(UpscaleCapture request, RenderTarget main, float signedJitterX, float signedJitterY,
			Matrix4fc currentToPrevious, float frameTimeMs, boolean reset) {
		capture = null;
		if (request.cancelled()) return;
		JsonObject meta = new JsonObject();
		meta.addProperty("frame", DebugSettings.frames());
		meta.addProperty("capture_encoding_version", 1);
		meta.addProperty("color_encoding", builtHdr ? "RGB log2(1+linear)/16 unorm8, clamped 0..65535; decode exp2(16*channel/255)-1" : "RGBA8 display-referred");
		meta.addProperty("color_stage", builtHdr ? "scene linear before composite5 bloom/tonemap" : "finished scene");
		meta.addProperty("pre_exposure", 1.0f);
		meta.addProperty("vendor_auto_exposure", builtHdr);
		if (packSurfaceApplied) meta.add("pack_deformation", PackMotionState.evidence());
		meta.addProperty("motion_encoding", "RGB packed 12-bit XY, 1/32 render pixel, range -64..63.96875, clamped");
		meta.addProperty("depth_encoding", "RGB unorm24, reverse-Z device depth");
		meta.addProperty("evaluated_frame", evaluatedFrames + 1);
		meta.addProperty("upscaler", builtUpscaler.serializedName());
		meta.addProperty("quality", builtMode == null ? "" : builtMode.serializedName());
		meta.addProperty("dlss_model_requested", builtModel == null ? "" : builtModel.serializedName());
		meta.addProperty("preset_requested", builtPreset);
		meta.addProperty("frame_time_ms", frameTimeMs);
		meta.addProperty("history_reset", reset);
		meta.addProperty("reset_reason", reset ? resets.pendingReason().label : "");
		JsonObject sizes = new JsonObject();
		sizes.addProperty("render_w", renderWidth);
		sizes.addProperty("render_h", renderHeight);
		sizes.addProperty("display_w", displayWidth);
		sizes.addProperty("display_h", displayHeight);
		meta.add("sizes", sizes);
		meta.add("jitter", jitter.toJson(DebugSettings.jitterSignX(JITTER_SIGN), DebugSettings.jitterSignY(JITTER_SIGN), signedJitterX, signedJitterY));
		meta.add("matrices", history.matricesJson(currentToPrevious));
		meta.add("camera", history.positionJson());
		meta.add("observed", observedInputs(true));
		meta.addProperty("pack", ShaderPacks.status());
		meta.addProperty("pack_drawing", ShaderPacks.isDrawingPack());
		meta.addProperty("hand_mask_from_pack", packHandCaptured);
		meta.addProperty("scope", "requested values come from settings; observed values were measured on this frame");
		request.metadata.complete(meta);

		if (builtHdr) encodeColorCapture(request, request.input, main);
		else Screenshot.takeScreenshot(main, image -> request.deliver(request.input, image));
		this.pendingOutput = request;
		this.pendingExtras = request.extras;
	}

	/** After the evaluation: the output now exists in the display textures held by {@code renderTarget}. */
	private void finishCapture() {
		UpscaleCapture request = pendingOutput;
		pendingOutput = null;
		if (request == null) return;
		if (request.cancelled()) return;
		if (builtHdr) encodeColorCapture(request, request.output, hdrOutput);
		else Screenshot.takeScreenshot(renderTarget, image -> request.deliver(request.output, image));
		if (!pendingExtras || motionTarget == null) {
			request.motion.cancel(false);
			request.depth.cancel(false);
			return;
		}
		if (captureEncodePass == null) captureEncodePass = new CaptureEncodePass();
		encodeCapture(request, request.motion, CaptureEncodePass.MOTION);
		encodeCapture(request, request.depth, CaptureEncodePass.DEPTH);
	}

	private void encodeColorCapture(UpscaleCapture request, CompletableFuture<NativeImage> future, RenderTarget source) {
		if (captureEncodePass == null) captureEncodePass = new CaptureEncodePass();
		TextureTarget encoded = new TextureTarget("Reflexium linear HDR diagnostic", source.width, source.height, GpuFormat.RGBA8_UNORM, null);
		captureEncodePass.render(source, encoded, CaptureEncodePass.HDR);
		Screenshot.takeScreenshot(encoded, image -> { request.deliver(future, image); Minecraft.getInstance().execute(encoded::destroyBuffers); });
	}

	private void encodeCapture(UpscaleCapture request, CompletableFuture<NativeImage> future, int mode) {
		TextureTarget encoded = new TextureTarget("Reflexium capture encode", renderWidth, renderHeight, GpuFormat.RGBA8_UNORM, null);
		captureEncodePass.render(motionTarget, encoded, mode);
		// The readback callback runs after the copy finished, which is when the temporary target can go.
		Screenshot.takeScreenshot(encoded, image -> {
			request.deliver(future, image);
			Minecraft.getInstance().execute(encoded::destroyBuffers);
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Configuration

	private void build(UpscaleQuality mode, int width, int height) {
		Upscaler selected = effectiveUpscaler();
		int[] size = selected == Upscaler.OFF ? new int[] {width, height}
				: selected == Upscaler.FSR ? mode.fsrSize(width, height) : runtimes.ngx().optimalSettings(width, height, mode);
		if (size == null) {
			throw new IllegalStateException("no DLSS render size for " + width + "x" + height + " " + mode.displayName());
		}
		waitForGpu();
		releaseFeatureResources();

		renderWidth = size[0];
		renderHeight = size[1];
		jitter.resize(renderWidth, renderHeight);
		displayWidth = width;
		displayHeight = height;
		builtHdr = wantsPackHdr(selected);
		builtPackEpoch = ComplementaryHdr.epoch();
		if (builtHdr) {
			hdrInput = new TextureTarget("Reflexium linear HDR input", renderWidth, renderHeight, GpuFormat.RGBA16_FLOAT, GpuFormat.D32_FLOAT);
			hdrOutput = new TextureTarget("Reflexium linear HDR output", displayWidth, displayHeight, GpuFormat.RGBA16_FLOAT, GpuFormat.D32_FLOAT);
			packMotionBase = new TextureTarget("Reflexium pack motion base", renderWidth, renderHeight, MotionVectorPass.FORMAT, GpuFormat.D32_FLOAT);
			packComposition = new TextureTarget("Reflexium pack composition", renderWidth, renderHeight, GpuFormat.R8_UNORM, null);
			packSurfacePass = new PackSurfaceMotionPass();
		}
		renderTarget = new TextureTarget("Reflexium DLSS render", renderWidth, renderHeight, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		worldDepth = new TextureTarget("Reflexium DLSS world depth", renderWidth, renderHeight, null, GpuFormat.D32_FLOAT);
		motionTarget = new TextureTarget("Reflexium DLSS motion", renderWidth, renderHeight, MotionVectorPass.FORMAT, GpuFormat.D32_FLOAT);
		if (motionPass == null) {
			motionPass = new MotionVectorPass();
			objectVelocityPass = new ObjectVelocityPass();
		}
		VkCommandBuffer commandBuffer = commandBuffer();
		mipBias = computeMipBias();
		if (ShaderPacks.anyLoaded()) {
			packHandBefore = new TextureTarget("Reflexium pack before hand", renderWidth, renderHeight, null, GpuFormat.D32_FLOAT);
			packHandMask = new TextureTarget("Reflexium pack hand mask", renderWidth, renderHeight, null, GpuFormat.D32_FLOAT);
			if (packHandMaskPass == null) packHandMaskPass = new PackHandMaskPass();
		}
		if (selected == Upscaler.DLSS && !builtHdr) {
			sharpenSource = new TextureTarget("Reflexium DLSS sharpen source", displayWidth, displayHeight, GpuFormat.RGBA8_UNORM, null);
			if (sharpenPass == null) sharpenPass = new SharpenPass();
		}
		if (selected == Upscaler.FSR) {
			opaqueTarget = new TextureTarget("Reflexium opaque scene", renderWidth, renderHeight, GpuFormat.RGBA8_UNORM, null);
			reactive = new OutputImage(device, commandBuffer, renderWidth, renderHeight, VK10.VK_FORMAT_R8_UNORM);
		}
		int result = selected == Upscaler.OFF ? 0 : selected == Upscaler.FSR
				? runtimes.fsr().create(session.device().address(), session.physicalDevice().address(),
						VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr"), renderWidth, renderHeight, displayWidth, displayHeight, builtHdr)
				: runtimes.ngx().create(commandBuffer.address(), renderWidth, renderHeight, displayWidth, displayHeight, mode, FEATURE_FLAGS | (builtHdr ? 1 | (1 << 6) : 0),
						requestedPreset(mode));
		runtimes.drainLog();
		if (result != 0) {
			throw new IllegalStateException(selected + " feature creation failed (0x" + Integer.toHexString(result) + ")");
		}
		VulkanInterop.barrier(commandBuffer);
		builtMode = mode;
		builtUpscaler = selected;
		builtModel = ReflexiumConfig.get().dlssModel();
		builtPreset = requestedPreset(mode);
		renderSizeApplied = false;
		resets.request(ResetReason.REBUILD);
		reactiveMaskFailed = false;
		history.havePrevious = false;
		jitter.restart();
		lastEvaluateNs = 0;
		evaluatedFrames = 0;
		resets.clearCounters();
		ReflexiumClient.LOGGER.info("{} {}: rendering {}x{}, output {}x{}; DLSS model {} (requested preset {}), texture LOD bias {}",
				selected, mode.displayName(), renderWidth, renderHeight, displayWidth, displayHeight, builtModel, builtPreset, mipBias);
	}

	/** The preset hint passed to NGX at feature creation. NGX does not report the model it actually selected. */
	private int requestedPreset(UpscaleQuality mode) {
		return DebugSettings.dlssPreset >= 0 ? DebugSettings.dlssPreset : ReflexiumConfig.get().dlssModel().preset(mode);
	}

	private boolean wantsPackHdr(Upscaler selected) {
		return selected != Upscaler.OFF && VitrailCompat.isDrawingPack() && ComplementaryHdr.ready() && ComplementaryHdr.storageSupported();
	}

	/** Vendor baseline plus the dev offset. Never nonzero when the world is not rendered below display resolution. */
	private float computeMipBias() {
		float base = DlssMath.mipBias(renderWidth, displayWidth);
		return base == 0.0f ? 0.0f : base + DebugSettings.mipBiasOffset();
	}

	/**
	 * Applies a changed bias. Replacement samplers may still be referenced by earlier submitted frames, so the
	 * GPU is idle before they are closed and recreated lazily at the new bias.
	 */
	private void refreshMipBias() {
		float wanted = computeMipBias();
		if (wanted == mipBias) return;
		if (materialSamplers.size() > 0) {
			waitForGpu();
			materialSamplers.close();
		}
		mipBias = wanted;
	}

	private void applyRenderSize(GameRenderer gameRenderer) {
		GameRendererAccessor accessor = (GameRendererAccessor) gameRenderer;
		accessor.reflexium$resourcePool().clear();
		accessor.reflexium$hud3DTarget().resize(renderWidth, renderHeight);
		Minecraft.getInstance().levelRenderer.resize(renderWidth, renderHeight);
		renderSizeApplied = true;
	}

	private void restoreDisplaySize(GameRenderer gameRenderer, WindowRenderState window) {
		renderSizeApplied = false;
		if (window.width > 0 && window.height > 0) {
			gameRenderer.resize(window.width, window.height);
		}
		if (device != null) {
			waitForGpu();
			releaseFeatureResources();
		}
		builtMode = null;
	}

	private void updateGlobals(int width, int height) {
		if (usesTransientUniforms() && renderWidth == displayWidth && renderHeight == displayHeight) return;
		if (globals != null) {
			globals.update(width, height, glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Evaluation

	private void evaluate(RenderTarget main) {
		VkCommandBuffer commandBuffer = commandBuffer();
		long timing = FrameProfiler.start(FrameProfiler.BARRIER_BEFORE, commandBuffer);
		VulkanInterop.barrier(commandBuffer);
		FrameProfiler.end(FrameProfiler.BARRIER_BEFORE, timing, commandBuffer);
		float signedJitterX = DebugSettings.jitterSignX(JITTER_SIGN) * jitter.x();
		float signedJitterY = DebugSettings.jitterSignY(JITTER_SIGN) * jitter.y();
		long now = System.nanoTime();
		float frameTimeMs = lastEvaluateNs == 0 ? 16.6f : (now - lastEvaluateNs) / 1_000_000f;
		lastFrameMs = frameTimeMs;
		lastEvaluateNs = now;
		boolean resetting = resets.pending();
		frameGenerationReset = resetting;
		UpscaleCapture request = capture;
		if (request != null && builtUpscaler != Upscaler.OFF) {
			Vec3 delta = history.delta();
			takeCapture(request, main, signedJitterX, signedJitterY,
					DlssMath.reprojection(history.previousUnjittered, history.currentJittered, (float) delta.x, (float) delta.y, (float) delta.z),
					frameTimeMs, resetting);
		}
		if (builtUpscaler == Upscaler.OFF) {
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(main.getColorTexture(), renderTarget.getColorTexture(),
					0, 0, 0, 0, 0, displayWidth, displayHeight);
			encoder.copyTextureToTexture(main.getDepthTexture(), renderTarget.getDepthTexture(),
					0, 0, 0, 0, 0, displayWidth, displayHeight);
			resets.consume();
			return;
		}
		float sharpness = ReflexiumConfig.get().fsrSharpness() / 100f;
		boolean sharpen = builtUpscaler == Upscaler.DLSS && sharpenSource != null && sharpness > 0;
		RenderTarget outputTarget = builtHdr ? hdrOutput : sharpen ? sharpenSource : renderTarget;
		ImageRef outputImage = VulkanInterop.image(outputTarget.getColorTexture(), outputTarget.getColorTextureView());
		if (resetting) {
			resets.count();
		}
		if (!Float.isFinite(signedJitterX) || !Float.isFinite(signedJitterY) || !Float.isFinite(frameTimeMs)) {
			throw new IllegalStateException("non-finite upscaler input: jitter " + signedJitterX + "," + signedJitterY + " frame " + frameTimeMs);
		}
		ImageRef color = VulkanInterop.image(main.getColorTexture(), main.getColorTextureView());
		ImageRef depth = VulkanInterop.image(motionTarget.getDepthTexture(), motionTarget.getDepthTextureView());
		ImageRef motion = VulkanInterop.image(motionTarget.getColorTexture(), motionTarget.getColorTextureView());
		timing = FrameProfiler.start(FrameProfiler.EVALUATE, commandBuffer);
		int result;
		if (builtUpscaler == Upscaler.FSR) {
			ImageRef composition = packSurfaceApplied ? VulkanInterop.image(packComposition.getColorTexture(), packComposition.getColorTextureView()) : null;
			ImageRef reactiveMask = packSurfaceApplied ? composition : reactiveGenerated ? reactive.asNgx() : null;
			result = runtimes.fsr().evaluate(commandBuffer.address(), color, depth, motion, outputImage, reactiveMask, composition,
					signedJitterX, signedJitterY, resetting, frameTimeMs, history.cameraNear, history.motionFar, history.cameraFov, sharpness);
		} else {
			result = runtimes.ngx().evaluate(commandBuffer.address(), color, depth, motion, outputImage,
					signedJitterX, signedJitterY, 1.0f, 1.0f, resetting, frameTimeMs);
		}
		FrameProfiler.end(FrameProfiler.EVALUATE, timing, commandBuffer);
		if (result != 0) {
			runtimes.drainLog();
			throw new IllegalStateException(builtUpscaler + " evaluate returned 0x" + Integer.toHexString(result));
		}
		if (sharpen) {
			timing = FrameProfiler.start(FrameProfiler.SHARPEN, commandBuffer);
			VulkanInterop.barrier(commandBuffer);
			sharpenPass.render(sharpenSource.getColorTextureView(), renderTarget, sharpness);
			FrameProfiler.end(FrameProfiler.SHARPEN, timing, commandBuffer);
		}
		resets.consume();
		if (++evaluatedFrames == 1) {
			ReflexiumClient.LOGGER.info("{} evaluated first world frame: {}x{} -> {}x{} (camera and object motion vectors)",
					builtUpscaler, renderWidth, renderHeight, displayWidth, displayHeight);
		}
		timing = FrameProfiler.start(FrameProfiler.BARRIER_AFTER, commandBuffer);
		VulkanInterop.barrier(commandBuffer);
		FrameProfiler.end(FrameProfiler.BARRIER_AFTER, timing, commandBuffer);
		finishCapture();
	}

	/**
	 * True when an applied post chain samples the main target's depth. Depth is then resolved once at display
	 * resolution; with no such consumer no depth work is added.
	 */
	private boolean postEffectsNeedDepth(GameRenderer gameRenderer) {
		List<PostChain> applied = ((GameRendererAccessor) gameRenderer).reflexium$appliedPostEffects();
		if (applied.isEmpty()) {
			depthProbeChains = List.of();
			postEffectsNeedDepth = false;
			return false;
		}
		if (!applied.equals(depthProbeChains)) {
			depthProbeChains = List.copyOf(applied);
			boolean needs = false;
			for (PostChain chain : depthProbeChains) {
				for (PostPass pass : ((PostChainAccessor) chain).reflexium$passes()) {
					for (PostPass.Input input : ((PostPassAccessor) pass).reflexium$inputs()) {
						if (input instanceof PostPass.TargetInput target && target.depthBuffer()) needs = true;
					}
				}
			}
			postEffectsNeedDepth = needs;
		}
		return postEffectsNeedDepth;
	}

	/** Nearest-sample the world+hand depth the upscaler consumed into the display-size main depth for post effects. */
	private void resolveDisplayDepth(RenderTarget main) {
		try {
			if (depthResolvePass == null) depthResolvePass = new DepthResolvePass();
			VkCommandBuffer commands = commandBuffer();
			long timing = FrameProfiler.start(FrameProfiler.DISPLAY_DEPTH, commands);
			depthResolvePass.render(motionTarget.getDepthTextureView(), main.getDepthTextureView(), main.width, main.height,
					history.depthTransform.m22(), history.depthTransform.m32(), jitter.ndcX(), jitter.ndcY());
			FrameProfiler.end(FrameProfiler.DISPLAY_DEPTH, timing, commands);
			depthResolved = true;
		} catch (RuntimeException e) {
			lastError = "post effect depth resolve failed: " + e;
			ReflexiumClient.LOGGER.error("Post effect depth resolve failed", e);
		}
	}

	/** Debug only: overlay the motion vector texture on the upscaled world (main holds the display textures). */
	private void drawMotionVectorView(RenderTarget main) {
		try {
			if (motionDebugPass == null) {
				motionDebugPass = new MotionDebugPass();
			}
			motionDebugPass.render(motionTarget, main);
		} catch (RuntimeException e) {
			DebugSettings.setMotionVectorView(false);
			lastError = "motion vector view failed: " + e;
			ReflexiumClient.LOGGER.error("Motion vector debug view failed, disabling it", e);
		}
	}

	private void fail(String what, RuntimeException e) {
		DistantHorizonsCompat.configureUpscaling(false);
		if (pendingOutput != null) {
			cancelCapture(pendingOutput);
			pendingOutput = null;
		}
		failed = true;
		activeThisFrame = false;
		lastError = what + ": " + e;
		ReflexiumClient.LOGGER.error("DLSS {}, falling back to native resolution", what, e);
		runtimes.drainLog();
	}

	// ---------------------------------------------------------------------------------------------
	// Resources

	private void releaseFeatureResources() {
		DeferredEffects.close();
		vanillaCloudMotion.close();
		ComplementaryHdr.release();
		hdrInput = destroy(hdrInput);
		hdrOutput = destroy(hdrOutput);
		sharpenSource = destroy(sharpenSource);
		packMotionBase = destroy(packMotionBase);
		packComposition = destroy(packComposition);
		packSurfacePass = null;
		builtHdr = false;
		FrameGenerationRenderer.get().release();
		materialSamplers.close();
		packHandBefore = destroy(packHandBefore);
		packHandMask = destroy(packHandMask);
		if (reactive != null) {
			reactive.destroy();
			reactive = null;
		}
		opaqueTarget = destroy(opaqueTarget);
		runtimes.releaseFeatures();
		renderTarget = destroy(renderTarget);
		worldDepth = destroy(worldDepth);
		motionTarget = destroy(motionTarget);
	}

	private static TextureTarget destroy(TextureTarget target) {
		if (target != null) target.destroyBuffers();
		return null;
	}

	private void destroyResources() {
		DistantHorizonsCompat.configureUpscaling(false);
		try {
			waitForGpu();
		} catch (RuntimeException e) {
			// Destroying objects is still valid after VK_ERROR_DEVICE_LOST; continue and release them.
			ReflexiumClient.LOGGER.warn("GPU wait failed before releasing upscaler resources", e);
		}
		releaseFeatureResources();
		materialSamplers.close();
		if (motionPass != null) {
			motionPass.close();
			motionPass = null;
		}
		if (objectVelocityPass != null) {
			objectVelocityPass.close();
			objectVelocityPass = null;
		}
		captureEncodePass = null;
		if (depthResolvePass != null) {
			depthResolvePass.close();
			depthResolvePass = null;
		}
		if (sharpenPass != null) {
			sharpenPass.close();
			sharpenPass = null;
		}
		motionDebugPass = null;
		packHandMaskPass = null;
		renderSizeApplied = false;
		builtMode = null;
	}
}
