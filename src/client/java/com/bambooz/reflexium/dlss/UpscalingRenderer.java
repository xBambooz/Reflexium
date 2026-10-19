package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.compat.ComplementaryHdr;
import com.bambooz.reflexium.compat.DistantHorizonsCompat;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.mixin.GameRendererAccessor;
import com.bambooz.reflexium.mixin.RenderTargetAccessor;
import com.bambooz.reflexium.mixin.VulkanCommandEncoderAccessor;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.fabricmc.loader.api.FabricLoader;
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
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;
import java.util.Locale;

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
	private static final boolean NGX_VERBOSE = Boolean.getBoolean("reflexium.dlss.verboseLog");

	private VulkanDevice device;
	private VulkanSession session;
	private NgxBridge bridge;
	private FsrBridge fsr;
	private String fsrUnavailable = "needs Windows x64 and Vulkan";
	private Upscaler builtUpscaler = Upscaler.OFF;
	private DlssModel builtModel;
	private int builtPreset;
	private float cameraNear = 0.05f, cameraFar = 1024f, cameraFov = 1.22f;
	private float motionFar = 1024f;
	private final Matrix4f depthTransform = new Matrix4f(), previousDepthTransform = new Matrix4f();
	private long invalidCameraMetadata;
	private boolean ngxReady;
	private boolean ngxInitialized;
	private boolean awaitingRuntime;
	private String unavailable = "needs the Vulkan renderer";
	private boolean failed;

	// Configuration currently built.
	private UpscaleQuality builtMode;
	private int displayWidth;
	private int displayHeight;
	private int renderWidth;
	private int renderHeight;
	/** Window size seen by the latest frame. This is what the world renders at whenever upscaling is not applied. */
	private int windowWidth;
	private int windowHeight;
	private boolean renderSizeApplied;

	private TextureTarget renderTarget;
	private TextureTarget hdrInput, hdrOutput;
	/** Display-size DLSS output that SharpenPass reads; the sharpened image lands in the display target. */
	private TextureTarget sharpenSource;
	private TextureTarget packMotionBase, packComposition;
	private PackSurfaceMotionPass packSurfacePass;
	private boolean packSurfaceApplied;
	private boolean builtHdr, hdrEvaluated;
	private long builtPackEpoch;
	private long irisEpoch;
	private float previousJitterNdcX, previousJitterNdcY;
	public void writePackJitter(Object out, java.lang.reflect.Method set) throws ReflectiveOperationException {
		set.invoke(out, jitterNdcX(), jitterNdcY(), previousJitterNdcX, previousJitterNdcY);
	}
	private int guiBlurCalls;
	public void noteGuiBlur() { guiBlurCalls++; }
	public boolean packHdrBuilt() { return builtHdr; }
	public boolean packHdrThisFrame() { return builtHdr && hdrEvaluated && swapped; }
	/** reconstructPackHdr's own precondition: composite5 will evaluate (or has evaluated) this frame. */
	public boolean packHdrBoundaryDue() { return builtHdr && swapped; }
	public int outputWidth() { return displayWidth; }
	public int outputHeight() { return displayHeight; }
	public int renderWidth() { return renderWidth; }
	public int renderHeight() { return renderHeight; }
	public int displayWidth() { return displayWidth; }
	public int displayHeight() { return displayHeight; }
	public float scaleLineWidth(float width) {
		if (swapped && renderWidth > 0 && displayWidth > 0 && renderWidth < displayWidth) {
			float scale = (float) renderWidth / (float) displayWidth;
			return Math.max(1.0f, width * scale);
		}
		return width;
	}
	public GpuTextureView packHdrColor() { return hdrOutput.getColorTextureView(); }
	public GpuTextureView packDisplayColor() { return renderTarget.getColorTextureView(); }
	private TextureTarget worldDepth;
	private GpuTextureView directWorldDepth;
	private TextureTarget motionTarget;
	private OutputImage reactive;
	private TextureTarget opaqueTarget;
	private final MipBiasSamplers materialSamplers = new MipBiasSamplers();
	private float mipBias;
	private MotionVectorPass motionPass;
	private final FoliageMotionPass foliageMotionPass = new FoliageMotionPass();
	private boolean foliageMotionApplied;
	private boolean irisOpaqueDepthApplied;
	public Matrix4fc foliageProjection(boolean previous) { return previous ? previousUnjittered : currentUnjittered; }
	public org.joml.Vector4f foliageMotionState(float previousTime) {
		Vec3 delta = currentCamera.subtract(previousCamera);
		return new org.joml.Vector4f((float) delta.x, (float) delta.y, (float) delta.z, previousTime);
	}
	private ObjectVelocityPass objectVelocityPass;
	private MotionDebugPass motionDebugPass;
	private PackHandMaskPass packHandMaskPass;
	private DepthResolvePass depthResolvePass;
	private SharpenPass sharpenPass;
	private CaptureEncodePass captureEncodePass;
	private TextureTarget packHandBefore;
	private TextureTarget packHandMask;
	private boolean packHandCaptured;
	private volatile String lastError = "";

	// Per-frame state.
	private boolean activeThisFrame;
	private boolean swapped;
	private long worldTiming;
	private boolean worldDepthCaptured;
	private boolean opaqueCaptured;
	private boolean reactiveGenerated;
	private boolean reactiveMaskFailed;
	private int jitterIndex;
	private int jitterPhases;
	private float jitterX;
	private float jitterY;
	private final Matrix4f currentJittered = new Matrix4f();
	private final Matrix4f currentProjection = new Matrix4f();
	private final Matrix4f physicalProjection = new Matrix4f();
	private float lastFrameMs = 16.6f;
	private final Matrix4f currentUnjittered = new Matrix4f();
	private final Matrix4f previousUnjittered = new Matrix4f();
	private Vec3 currentCamera = Vec3.ZERO;
	private Vec3 previousCamera = Vec3.ZERO;
	private final Quaternionf currentOrientation = new Quaternionf();
	private final Quaternionf previousOrientation = new Quaternionf();
	private int currentViewIdentity;
	private int previousViewIdentity;
	private Object previousLevel;
	private boolean havePrevious;
	private boolean resetNext = true;
	private ResetReason pendingReset = ResetReason.REBUILD;
	private ResetReason lastReset = ResetReason.REBUILD;
	private final long[] resetsByReason = new long[ResetReason.values().length];
	private long lastEvaluateNs;
	private long evaluatedFrames;
	private long historyResetFrames;
	public long historyResetFrames() { return historyResetFrames; }
	public int presetHint() { return builtPreset; }

	// First-person hand projection: the unjittered and jittered matrices it was drawn with this frame, and last frame's.
	private final Matrix4f handProjection = new Matrix4f();
	private final Matrix4f handProjectionJittered = new Matrix4f();
	private final Matrix4f handProjectionPrevious = new Matrix4f();
	private boolean haveHandProjection;
	private int handVerticesDrawn;

	// Post effects that sample the scene depth need a display-resolution copy once reconstruction precedes them.
	private List<PostChain> depthProbeChains = List.of();
	private boolean postEffectsNeedDepth;
	private boolean reconstructed;
	private boolean depthResolved;
	private boolean sceneCompleted;

	// Material bind telemetry. Counters are reset every frame and published when the frame ends.
	private final int[] frameBinds = new int[MaterialBinding.PATHS];
	private final int[] frameBiased = new int[MaterialBinding.PATHS];
	private final float[] frameBias = new float[MaterialBinding.PATHS];
	private final int[] lastBinds = new int[MaterialBinding.PATHS];
	private final int[] lastBiased = new int[MaterialBinding.PATHS];
	private final float[] lastBias = new float[MaterialBinding.PATHS];

	private volatile UpscaleCapture capture;

	// Global uniform state captured before the world pass.
	private GlobalSettingsUniform globals;
	private double glintAlpha;
	private long gameTime;
	private float worldPartialTicks;
	private int menuBlurRadius;
	private Vec3 cameraPos;
	private boolean useRgss;

	private UpscalingRenderer() {
		java.util.Arrays.fill(frameBias, Float.NaN);
		java.util.Arrays.fill(lastBias, Float.NaN);
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
		if (ReflexiumClient.isSupportedPlatform()) {
			try {
				fsr = new FsrBridge();
				fsrUnavailable = "";
				ReflexiumClient.LOGGER.info("FSR 3.1 Vulkan runtime loaded on {}", session.deviceName());
			} catch (Throwable e) {
				fsrUnavailable = "FSR runtime could not load (see log)";
				ReflexiumClient.LOGGER.warn(fsrUnavailable, e);
			}
		}
		ngxReady = false;
		failed = false;
		FrameGenerationRenderer.get().onDeviceCreated(device, session, fsr);
		initializeNgx();
	}

	/** Render-thread only: also runs once when a first-use download finishes. */
	private void initializeNgx() {
		awaitingRuntime = false;
		if (DlssRuntimeInstaller.hasPendingWork()) {
			awaitingRuntime = true;
			unavailable = DlssRuntimeInstaller.state().message();
			return;
		}
		DlssStatus prerequisites = DlssSupport.status();
		if (!prerequisites.prerequisitesMet()) {
			unavailable = prerequisites.summary();
			awaitingRuntime = DlssRuntimeInstaller.state().phase() == DlssRuntimeInstaller.Phase.DOWNLOADING
					|| DlssRuntimeInstaller.state().phase() == DlssRuntimeInstaller.Phase.INSTALLED;
			return;
		}
		if (prerequisites.featureDlls().stream().noneMatch(dll -> dll.equals("nvngx_dlss.dll"))) {
			unavailable = "nvngx_dlss.dll missing from " + DlssSupport.featureFolder();
			return;
		}
		bridge = NgxBridge.get().orElse(null);
		if (bridge == null) {
			unavailable = "native bridge failed to load";
			return;
		}
		try {
			long gipa = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
			long gdpa = VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr");
			String version = FabricLoader.getInstance().getModContainer(ReflexiumClient.MOD_ID)
					.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			int result = bridge.init(session.instance().address(), session.physicalDevice().address(), session.device().address(),
					gipa, gdpa, DlssSupport.featureFolder(), DlssSupport.featureFolder(), version, NGX_VERBOSE);
			drainLog();
			if (result != 0) {
				unavailable = "NGX init failed (0x" + Integer.toHexString(result) + ")";
				ReflexiumClient.LOGGER.warn("DLSS: {}", unavailable);
				return;
			}
			ngxInitialized = true;
			FrameGenerationRenderer.get().onNgxReady(bridge);
			int[] available = bridge.dlssAvailable();
			if (available[0] == 0) {
				unavailable = available[1] != 0
						? "driver too old, DLSS needs " + available[2] + "." + available[3]
						: "DLSS not available on this GPU (0x" + Integer.toHexString(available[4]) + ")";
				ReflexiumClient.LOGGER.warn("DLSS: {}", unavailable);
				return;
			}
			ngxReady = true;
			ReflexiumClient.LOGGER.info("DLSS Super Resolution ready (mode {})", ReflexiumConfig.get().upscaleQuality().displayName());
		} catch (RuntimeException e) {
			unavailable = "NGX init threw " + e.getClass().getSimpleName();
			ReflexiumClient.LOGGER.warn("DLSS: NGX init failed", e);
		}
	}

	private static RuntimeException keep(RuntimeException first, RuntimeException next) {
		if (first == null) return next;
		first.addSuppressed(next);
		return first;
	}

	/** Best effort: a lost device fails the GPU wait, which must not skip the remaining native teardown. */
	public void onDeviceClosing() {
		RuntimeException failure = null;
		if (device != null) {
			try { destroyResources(); } catch (RuntimeException e) { failure = e; }
		}
		try { FrameProfiler.close(); } catch (RuntimeException e) { failure = keep(failure, e); }
		try { FrameGenerationRenderer.get().onDeviceClosing(); } catch (RuntimeException e) { failure = keep(failure, e); }
		if (bridge != null && ngxInitialized) {
			try { bridge.shutdown(); } catch (RuntimeException e) { failure = keep(failure, e); }
		}
		if (failure != null) ReflexiumClient.LOGGER.warn("Upscaler teardown was incomplete (device lost?)", failure);
		ngxInitialized = false;
		ngxReady = false;
		awaitingRuntime = false;
		device = null;
		fsr = null;
		session = null;
		unavailable = "needs the Vulkan renderer";
	}

	// ---------------------------------------------------------------------------------------------
	// Frame hooks (render thread, called from GameRendererMixin)

	/** GameRenderer.render HEAD: decide whether this frame upscales and (re)build for the current size and mode. */
	public void beginFrame(GameRenderer gameRenderer) {
		DebugSettings.onFrame();
		FrameProfiler.beginFrame();
		FrameGenerationRenderer.get().beginFrame();
		if (awaitingRuntime && device != null) {
			DlssRuntimeInstaller.Phase phase = DlssRuntimeInstaller.state().phase();
			// The returned future is super resolution's alone; an optional FG download may still be running.
			if (DlssRuntimeInstaller.ensureInstalledAsync().isDone()) {
				awaitingRuntime = false;
				if (phase == DlssRuntimeInstaller.Phase.INSTALLED || phase == DlssRuntimeInstaller.Phase.READY
						|| java.nio.file.Files.isRegularFile(DlssSupport.featureFolder().resolve(DlssRuntimeInstaller.FILE_NAME))) {
					DlssSupport.probe(session);
					initializeNgx();
				} else {
					unavailable = DlssSupport.status().summary();
				}
			}
		}
		activeThisFrame = false;
		guiBlurCalls = 0;
		hdrEvaluated = false;
		packSurfaceApplied = false;
		foliageMotionApplied = false;
		irisOpaqueDepthApplied = false;
		ComplementaryHdr.beginFrame();
		DistantHorizonsCompat.beginFrame();
		worldDepthCaptured = false;
		directWorldDepth = null;
		opaqueCaptured = false;
		reactiveGenerated = false;
		packHandCaptured = false;
		haveHandProjection = false;
		reconstructed = false;
		depthResolved = false;
		sceneCompleted = false;
		WindowRenderState window = gameRenderer.gameRenderState().windowRenderState;
		windowWidth = window.width;
		windowHeight = window.height;
		UpscaleQuality mode = ReflexiumConfig.get().upscaleQuality();
		Upscaler selected = effectiveUpscaler();
		if (selected == Upscaler.OFF) mode = UpscaleQuality.NATIVE_AA;
		ShaderPacks.configureUpscaling(available(selected) && !failed && DistantHorizonsCompat.canUpscale());
		boolean wanted = (available(selected) || FrameGenerationRenderer.get().wantsMotion()) && !failed && gameRenderer.gameRenderState().shouldRenderLevel && !window.isMinimized
				&& window.width > 0 && window.height > 0 && ShaderPacks.canUpscale() && DistantHorizonsCompat.canUpscale();
		DistantHorizonsCompat.configureUpscaling(wanted);
		wanted &= DistantHorizonsCompat.canUpscale();
		if (!wanted) {
			ObjectMotion.get().beginFrame(false, false);
			if (renderSizeApplied) {
				restoreDisplaySize(gameRenderer, window);
			}
			return;
		}
		try {
			if (mode != builtMode || selected != builtUpscaler || ReflexiumConfig.get().dlssModel() != builtModel || requestedPreset(mode) != builtPreset
					|| window.width != displayWidth || window.height != displayHeight || renderTarget == null
					|| builtHdr != wantsPackHdr(selected) || builtHdr && builtPackEpoch != ComplementaryHdr.epoch()) {
				build(gameRenderer, mode, window.width, window.height);
			}
			if (!renderSizeApplied) {
				applyRenderSize(gameRenderer);
			}
			refreshMipBias();
			if (irisEpoch != com.bambooz.reflexium.compat.IrisCompat.epoch()) {
				// A reloaded or switched pack: camera, object and hand history all describe the previous pipeline.
				irisEpoch = com.bambooz.reflexium.compat.IrisCompat.epoch();
				requestReset(ResetReason.REBUILD);
				havePrevious = false;
			}
			activeThisFrame = true;
			advanceJitter();
		} catch (RuntimeException e) {
			fail("setup failed", e);
			restoreDisplaySize(gameRenderer, window);
		}
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
		currentViewIdentity = viewIdentity(gameRenderer);
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
		currentUnjittered.set(projection).mul(camera.viewRotationMatrix);
		currentProjection.set(projection);
		// View bob, damage tilt and nausea were multiplied into the drawn projection. The physical camera
		// planes and field of view come from the matrix Minecraft built before those effects.
		physicalProjection.set(camera.projectionMatrix);
		DlssMath.CameraMetadata physical = DlssMath.cameraMetadata(physicalProjection);
		if (physical.valid()) {
			cameraNear = physical.near();
			cameraFar = physical.far();
			cameraFov = physical.verticalFov();
		} else {
			invalidCameraMetadata++;
		}
		Matrix4f jittered = jitter(projection);
		currentJittered.set(jittered).mul(camera.viewRotationMatrix);
		currentCamera = camera.pos;
		currentOrientation.set(camera.orientation);
		return jittered;
	}

	/** Jitters the first-person hand projection the same way, so DLSS sees consistent sampling. */
	public Matrix4f jitterHudProjection(Matrix4f projection) {
		return swapped ? jitter(projection) : projection;
	}

	/** Records the projection the hand is drawn with: vanilla's hud projection and its jittered copy. */
	public void noteHandProjection(Matrix4fc unjittered, Matrix4fc jittered) {
		// A shader pack draws its own hand into the pack chain, and its projection was noted when it drew.
		if (!swapped || packHandCaptured) return;
		handProjection.set(unjittered);
		handProjectionJittered.set(jittered);
		haveHandProjection = true;
	}

	/** Vitrail multiplies its hand volume (jittered) and bob into one matrix: remove the left-multiplied jitter again. */
	public void noteHandProjectionDrawn(Matrix4fc drawnJittered) {
		if (!swapped) return;
		handProjectionJittered.set(drawnJittered);
		handProjection.translation(-jitterNdcX(), -jitterNdcY(), 0.0f).mul(drawnJittered);
		haveHandProjection = true;
	}

	public boolean isUpscalingThisFrame() {
		return swapped;
	}

	public boolean isUpscalingRequestedThisFrame() { return activeThisFrame; }
	public Matrix4f jitterShaderProjection(Matrix4fc projection) {
		return swapped ? jitter(new Matrix4f(projection)) : new Matrix4f(projection);
	}

	/**
	 * The sampler to bind for a color material while upscaling. Idempotent: a sampler that is already a
	 * corrected replacement comes back unchanged. {@code path} is a {@link MaterialBinding} index.
	 */
	public GpuSampler materialSampler(GpuSampler sampler, int path) {
		if (!swapped) return sampler;
		GpuSampler result = materialSamplers.get(sampler, mipBias);
		frameBinds[path]++;
		float applied = result instanceof AppliedLodBias bias ? bias.reflexium$appliedLodBias() : Float.NaN;
		frameBias[path] = applied;
		float expected = MipBiasSamplers.appliedBias(sampler) + mipBias;
		if (mipBias != 0 && (materialSamplers.owns(sampler) || Math.abs(applied - expected) < 1.0e-4f)) frameBiased[path]++;
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
			try {
			VkCommandBuffer commands = commandBuffer();
			long timing = FrameProfiler.start(FrameProfiler.REACTIVE, commands);
			barrier(commands);
			int result = fsr.generateReactive(commands.address(), ngxImage(opaqueTarget.getColorTexture(), opaqueTarget.getColorTextureView()),
					ngxImage(main.getColorTexture(), main.getColorTextureView()), reactive.asNgx());
			barrier(commands);
			if (result != 0) throw new IllegalStateException("FSR reactive mask returned " + result);
			FrameProfiler.end(FrameProfiler.REACTIVE, timing, commands);
			reactiveGenerated = true;
			} catch (RuntimeException e) {
				reactiveMaskFailed = true;
				lastError = "reactive mask disabled: " + e;
				ReflexiumClient.LOGGER.warn("FSR reactive mask failed; continuing with FSR shading-change detection", e);
			}
		}
		GpuTextureView opaqueDepth = com.bambooz.reflexium.compat.IrisCompat.opaqueDepth(renderWidth, renderHeight);
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

	/** Shader packs draw their hand inside renderLevel and suppress vanilla's render3dHud. */
	public void afterLevel(GameRenderer gameRenderer) {
		if (swapped && !worldDepthCaptured) {
			captureWorldDepth(gameRenderer);
			renderMotionVectors(gameRenderer, false);
		}
	}

	/** Decides whether this frame starts a new temporal history, and why. Null when history continues. */
	private ResetReason cutReason(Object level) {
		return DlssMath.cutReason(havePrevious, level == previousLevel, currentCamera.distanceToSqr(previousCamera),
				System.nanoTime() - lastEvaluateNs, currentViewIdentity == previousViewIdentity, previousOrientation, currentOrientation);
	}
	private void requestReset(ResetReason reason) {
		if (!resetNext) {
			resetNext = true;
			pendingReset = reason;
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
		float far = dh == null ? cameraFar : Math.max(cameraFar, dh.farPlane() * 2.0f);
		if (far != motionFar) {
			requestReset(ResetReason.REBUILD);
			havePrevious = false;
		}
		motionFar = far;
		depthTransform.set(DistantHorizonsCompat.depthTransform(cameraNear, cameraFar, motionFar));
		Object level = Minecraft.getInstance().level;
		ResetReason cut = cutReason(level);
		if (cut != null) {
			requestReset(cut);
			// Camera, object and hand history all restart together.
			previousUnjittered.set(currentUnjittered);
			previousDepthTransform.set(depthTransform);
			previousCamera = currentCamera;
			previousOrientation.set(currentOrientation);
			if (haveHandProjection) handProjectionPrevious.set(handProjection);
			ObjectMotion.get().discardHistory();
		}
		Vec3 delta = currentCamera.subtract(previousCamera);
		Matrix4f currentToPrevious = DlssMath.reprojection(previousUnjittered, currentJittered,
				(float) delta.x, (float) delta.y, (float) delta.z);
		try {
			if (!DistantHorizonsCompat.canUpscale()) throw new IllegalStateException("Distant Horizons input capture failed");
			VkCommandBuffer commands = commandBuffer();
			long timing = FrameProfiler.start(FrameProfiler.MOTION, commands);
			GpuTextureView sceneDepth = directWorldDepth != null ? directWorldDepth : worldDepth.getDepthTextureView();
			motionPass.render(motionTarget, sceneDepth, handDepth,
					currentToPrevious, jitterNdcX(), jitterNdcY(), dh, currentJittered, depthTransform,
					(float) delta.x, (float) delta.y, (float) delta.z, resetNext);
			FrameProfiler.end(FrameProfiler.MOTION, timing, commands);
			timing = FrameProfiler.start(FrameProfiler.OBJECTS, commands);
			// Match the merged depth, so a hidden object cannot overwrite a closer LOD's camera vector.
			objectVelocityPass.render(ObjectMotion.get(), motionTarget, motionTarget.getDepthTextureView(), handDepth,
					new Matrix4f(depthTransform).mul(currentJittered), currentUnjittered, previousUnjittered,
					(float) delta.x, (float) delta.y, (float) delta.z, resetNext);
			handVerticesDrawn = 0;
			if (haveHandProjection && DebugSettings.handMotion()) {
				handVerticesDrawn = objectVelocityPass.renderHand(ObjectMotion.get(), motionTarget, sceneDepth, handDepth,
						handProjectionJittered, handProjection, handProjectionPrevious, resetNext);
			}
			FrameProfiler.end(FrameProfiler.OBJECTS, timing, commands);
			GpuTextureView foliage = com.bambooz.reflexium.compat.BslFoliageMotion.surface();
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
		previousLevel = level;
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
				requestReset(ResetReason.LOADING);
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
		if (worldDepthCaptured && !failed && postEffectsNeedDepth(gameRenderer)) {
			resolveDisplayDepth(main);
		}
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
		if (!worldDepthCaptured) throw new IllegalStateException("HDR boundary has no aligned world depth");
		if (com.bambooz.reflexium.compat.ComplementaryMotionSource.ready() && !resetNext) {
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

	/** After GameRenderer.applyPostEffects: overlays, frame generation, and previous-frame promotion, once. */
	public void completeScene(GameRenderer gameRenderer) {
		if (!reconstructed || sceneCompleted) {
			return;
		}
		sceneCompleted = true;
		RenderTarget main = gameRenderer.mainRenderTarget();
		boolean fgReset = frameGenerationReset;
		if (DebugSettings.motionVectorView() && worldDepthCaptured && motionTarget != null) {
			drawMotionVectorView(main);
		}
		if (worldDepthCaptured && !failed && FrameGenerationRenderer.get().wantsMotion()) {
			Vec3 delta = currentCamera.subtract(previousCamera);
			// Clip transforms describe the drawn volume, including view effects. Lens scalars stay physical.
			FrameGenerationRenderer.get().sceneReady(main, motionTarget, commandBuffer(), new Matrix4f(depthTransform).mul(currentProjection),
					DlssMath.reprojection(new Matrix4f(previousDepthTransform).mul(previousUnjittered),
							new Matrix4f(depthTransform).mul(currentUnjittered), (float) delta.x, (float) delta.y, (float) delta.z),
					currentOrientation, (float) currentCamera.x, (float) currentCamera.y, (float) currentCamera.z,
					DebugSettings.jitterSignX(JITTER_SIGN) * jitterX, DebugSettings.jitterSignY(JITTER_SIGN) * jitterY,
					cameraNear, motionFar, cameraFov, lastFrameMs, fgReset);
		}
		com.bambooz.reflexium.compat.BslFoliageMotion.endFrame();
		previousUnjittered.set(currentUnjittered);
		previousDepthTransform.set(depthTransform);
		previousCamera = currentCamera;
		previousJitterNdcX = jitterNdcX(); previousJitterNdcY = jitterNdcY();
		previousOrientation.set(currentOrientation);
		previousViewIdentity = currentViewIdentity;
		if (haveHandProjection) handProjectionPrevious.set(handProjection);
		havePrevious = worldDepthCaptured;
	}

	/** True when this frame's evaluation (not a later one) ran with history reset, for frame generation. */
	private boolean frameGenerationReset;

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
			requestReset(ResetReason.MENU);
		}
		if (reconstructed && !sceneCompleted) {
			// Post effects threw after reconstruction. Do not carry half-promoted history forward.
			requestReset(ResetReason.REBUILD);
			havePrevious = false;
		}
		for (int i = 0; i < MaterialBinding.PATHS; i++) {
			lastBinds[i] = frameBinds[i];
			lastBiased[i] = frameBiased[i];
			lastBias[i] = frameBias[i];
			frameBinds[i] = 0;
			frameBiased[i] = 0;
			frameBias[i] = Float.NaN;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Status

	public String statusLine() {
		UpscaleQuality mode = ReflexiumConfig.get().upscaleQuality();
		Upscaler selected = effectiveUpscaler();
		if (ReflexiumConfig.get().upscaler() == Upscaler.OFF) return "Upscaling: off";
		if (!available(selected)) return "Upscaling: " + (selected == Upscaler.DLSS ? unavailable : fsrUnavailable);
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
		return String.format(Locale.ROOT, "%s: %s %dx%d -> %dx%d", name, mode.displayName(), renderWidth, renderHeight, displayWidth, displayHeight);
	}

	/** Debug harness snapshot (see DebugServer). Values describe the most recent frame. */
	public record DebugInfo(boolean active, String builtUpscaler, int jitterIndex, float jitterX, float jitterY,
			float jitterSignX, float jitterSignY, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
			String lastError, int capturedVertices, int drawnVertices, int entities, int blockEntities, float mipBias, boolean reactiveMask,
			long evaluatedFrames, boolean packHandMask) {
	}

	/**
	 * Resolution the world renders at right now. The upscaled render size only counts while upscaling is applied;
	 * with the upscaler off (or a frame that skipped upscaling) the render size equals the window.
	 */
	public int[] currentRenderSize() {
		boolean upscaling = renderSizeApplied && ReflexiumConfig.get().upscaler() != Upscaler.OFF;
		return upscaling ? new int[] {renderWidth, renderHeight} : new int[] {windowWidth, windowHeight};
	}

	public DebugInfo debugInfo() {
		int[] render = currentRenderSize();
		return new DebugInfo(activeThisFrame, builtUpscaler.serializedName(), jitterIndex, jitterX, jitterY,
				DebugSettings.jitterSignX(JITTER_SIGN), DebugSettings.jitterSignY(JITTER_SIGN),
				render[0], render[1], windowWidth, windowHeight, lastError,
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
		o.addProperty("primary_depth_source", irisOpaqueDepthApplied ? "iris_pre_translucent" : worldDepthCaptured ? "vanilla_world" : "not_captured");
		if (foliageMotionApplied) {
			o.addProperty("bsl_previous_time", com.bambooz.reflexium.compat.BslFoliageMotion.previousTime());
			o.addProperty("bsl_current_time", com.bambooz.reflexium.compat.BslFoliageMotion.currentTime());
		}
		o.addProperty("pack_surface_sources_verified", com.bambooz.reflexium.compat.ComplementaryMotionSource.ready());
		o.addProperty("blended_surface_strategy", "background vectors plus FSR contribution masks; DLSS blended layers remain unsupported");
		o.addProperty("gui_blur_calls_this_frame", guiBlurCalls);
		o.addProperty("menu_blur_radius_setting", menuBlurRadius);
		JsonArray postIds = new JsonArray();
		for (PostChain chain : ((GameRendererAccessor) Minecraft.getInstance().gameRenderer).reflexium$appliedPostEffects()) postIds.add(chain.id().toString());
		o.add("applied_post_effects", postIds);
		o.addProperty("mip_bias_requested", mipBias);
		o.addProperty("mip_bias_offset", DebugSettings.mipBiasOffset());
		o.addProperty("mip_bias_vendor_baseline", DlssMath.VENDOR_MIP_BASELINE);
		JsonObject binds = new JsonObject();
		for (int i = 0; i < MaterialBinding.PATHS; i++) {
			JsonObject bind = new JsonObject();
			bind.addProperty("binds", currentFrame ? frameBinds[i] : lastBinds[i]);
			bind.addProperty("with_requested_bias", currentFrame ? frameBiased[i] : lastBiased[i]);
			float applied = currentFrame ? frameBias[i] : lastBias[i];
			bind.addProperty("applied_bias", Float.isNaN(applied) ? null : applied);
			binds.add(MaterialBinding.PATH_NAMES[i], bind);
		}
		o.add("material_binds", binds);
		o.addProperty("material_replacements", materialSamplers.size());
		o.addProperty("jitter_phases", jitterPhases);
		o.addProperty("jitter_index", jitterIndex);
		boolean resetting = currentFrame && resetNext;
		o.addProperty("history_reset_frames", historyResetFrames + (resetting ? 1 : 0));
		o.addProperty("last_reset_reason", (resetting ? pendingReset : lastReset).label);
		JsonObject reasons = new JsonObject();
		for (ResetReason reason : ResetReason.values()) {
			reasons.addProperty(reason.name().toLowerCase(Locale.ROOT), resetsByReason[reason.ordinal()] + (resetting && pendingReset == reason ? 1 : 0));
		}
		o.add("resets_by_reason", reasons);
		JsonObject camera = new JsonObject();
		camera.addProperty("near", cameraNear);
		camera.addProperty("far", cameraFar);
		camera.addProperty("upscaler_far", motionFar);
		camera.addProperty("vertical_fov_rad", cameraFov);
		camera.addProperty("invalid_metadata_frames", invalidCameraMetadata);
		o.add("physical_camera", camera);
		o.addProperty("hand_vertices_drawn", handVerticesDrawn);
		o.addProperty("hand_slots", ObjectMotion.get().handSlots());
		o.addProperty("hand_motion", DebugSettings.handMotion());
		o.addProperty("post_effects_need_depth", postEffectsNeedDepth);
		o.addProperty("post_depth_resolved", depthResolved);
		o.addProperty("requested_preset", builtPreset);
		o.addProperty("requested_preset_is_hint", true);
		o.addProperty("pack_resolve_bypass_requested", DebugSettings.packResolveBypass());
		o.addProperty("pack_resolve_bypass_applied", com.bambooz.reflexium.compat.ComplementaryTemporalSource.applied());
		o.addProperty("pack_pre_filters_requested", DebugSettings.packPreFilters());
		o.addProperty("pack_source_edits", String.join(",", new java.util.TreeSet<>(com.bambooz.reflexium.compat.ComplementaryTemporalSource.appliedEdits())));
		return o;
	}

	public boolean isReady() {
		return ngxReady || fsr != null;
	}
	public boolean dlssReady() { return ngxReady; }
	public Upscaler effectiveUpscaler() { return ReflexiumConfig.get().upscaler().resolve(ngxReady, fsr != null); }
	public boolean available(Upscaler value) {
		return switch (value) { case DLSS -> ngxReady; case FSR -> fsr != null; default -> false; };
	}

	/** Clears a previous failure so a mode change retries. */
	public void onModeChanged() {
		failed = false;
		lastError = "";
		// A settings change is the player's retry: restart a failed runtime download or find a hand-placed DLL.
		if (!ngxReady && !awaitingRuntime && session != null && session.isNvidia() && DlssRuntimeInstaller.retryFailed()) {
			DlssSupport.probe(session);
			awaitingRuntime = true;
		}
		FrameGenerationRenderer.get().retry();
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
		if (packSurfaceApplied) meta.add("pack_deformation", com.bambooz.reflexium.compat.PackMotionState.evidence());
		meta.addProperty("motion_encoding", "RGB packed 12-bit XY, 1/32 render pixel, range -64..63.96875, clamped");
		meta.addProperty("depth_encoding", "RGB unorm24, reverse-Z device depth");
		meta.addProperty("evaluated_frame", evaluatedFrames + 1);
		meta.addProperty("upscaler", builtUpscaler.serializedName());
		meta.addProperty("quality", builtMode == null ? "" : builtMode.serializedName());
		meta.addProperty("dlss_model_requested", builtModel == null ? "" : builtModel.serializedName());
		meta.addProperty("preset_requested", builtPreset);
		meta.addProperty("frame_time_ms", frameTimeMs);
		meta.addProperty("history_reset", reset);
		meta.addProperty("reset_reason", reset ? pendingReset.label : "");
		JsonObject sizes = new JsonObject();
		sizes.addProperty("render_w", renderWidth);
		sizes.addProperty("render_h", renderHeight);
		sizes.addProperty("display_w", displayWidth);
		sizes.addProperty("display_h", displayHeight);
		meta.add("sizes", sizes);
		JsonObject jitter = new JsonObject();
		jitter.addProperty("index", jitterIndex);
		jitter.addProperty("phases", jitterPhases);
		jitter.addProperty("x_px", jitterX);
		jitter.addProperty("y_px", jitterY);
		jitter.addProperty("sign_x", DebugSettings.jitterSignX(JITTER_SIGN));
		jitter.addProperty("sign_y", DebugSettings.jitterSignY(JITTER_SIGN));
		jitter.addProperty("sent_x_px", signedJitterX);
		jitter.addProperty("sent_y_px", signedJitterY);
		jitter.addProperty("ndc_x", jitterNdcX());
		jitter.addProperty("ndc_y", jitterNdcY());
		meta.add("jitter", jitter);
		JsonObject matrices = new JsonObject();
		matrices.add("world_view_projection_jittered", floats(currentJittered));
		matrices.add("world_view_projection", floats(currentUnjittered));
		matrices.add("previous_world_view_projection", floats(previousUnjittered));
		matrices.add("current_to_previous", floats(currentToPrevious));
		matrices.add("physical_projection", floats(physicalProjection));
		matrices.add("drawn_projection", floats(currentProjection));
		matrices.add("upscaler_depth_transform", floats(depthTransform));
		matrices.addProperty("hand_projection_present", haveHandProjection);
		matrices.add("hand_projection_jittered", floats(handProjectionJittered));
		matrices.add("hand_projection", floats(handProjection));
		matrices.add("hand_projection_previous", floats(handProjectionPrevious));
		meta.add("matrices", matrices);
		JsonObject cameraJson = new JsonObject();
		cameraJson.addProperty("x", currentCamera.x);
		cameraJson.addProperty("y", currentCamera.y);
		cameraJson.addProperty("z", currentCamera.z);
		cameraJson.addProperty("previous_x", previousCamera.x);
		cameraJson.addProperty("previous_y", previousCamera.y);
		cameraJson.addProperty("previous_z", previousCamera.z);
		JsonArray q = new JsonArray();
		q.add(currentOrientation.x); q.add(currentOrientation.y); q.add(currentOrientation.z); q.add(currentOrientation.w);
		cameraJson.add("orientation_xyzw", q);
		meta.add("camera", cameraJson);
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

	private UpscaleCapture pendingOutput;
	private boolean pendingExtras;

	private static JsonArray floats(Matrix4fc matrix) {
		float[] values = matrix.get(new float[16]);
		JsonArray out = new JsonArray();
		for (float value : values) out.add(value);
		return out;
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
	private void encodeColorCapture(UpscaleCapture request, java.util.concurrent.CompletableFuture<com.mojang.blaze3d.platform.NativeImage> future, RenderTarget source) {
		if (captureEncodePass == null) captureEncodePass = new CaptureEncodePass();
		TextureTarget encoded = new TextureTarget("Reflexium linear HDR diagnostic", source.width, source.height, GpuFormat.RGBA8_UNORM, null);
		captureEncodePass.render(source, encoded, CaptureEncodePass.HDR);
		Screenshot.takeScreenshot(encoded, image -> { request.deliver(future, image); Minecraft.getInstance().execute(encoded::destroyBuffers); });
	}

	private void encodeCapture(UpscaleCapture request, java.util.concurrent.CompletableFuture<com.mojang.blaze3d.platform.NativeImage> future, int mode) {
		TextureTarget encoded = new TextureTarget("Reflexium capture encode", renderWidth, renderHeight, GpuFormat.RGBA8_UNORM, null);
		captureEncodePass.render(motionTarget, encoded, mode);
		// The readback callback runs after the copy finished, which is when the temporary target can go.
		Screenshot.takeScreenshot(encoded, image -> {
			request.deliver(future, image);
			Minecraft.getInstance().execute(encoded::destroyBuffers);
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Internals

	private void build(GameRenderer gameRenderer, UpscaleQuality mode, int width, int height) {
		Upscaler selected = effectiveUpscaler();
		int[] size = selected == Upscaler.OFF ? new int[] {width, height}
				: selected == Upscaler.FSR ? mode.fsrSize(width, height) : bridge.optimalSettings(width, height, mode);
		if (size == null) {
			throw new IllegalStateException("no DLSS render size for " + width + "x" + height + " " + mode.displayName());
		}
		waitForGpu();
		releaseFeatureResources();

		renderWidth = size[0];
		renderHeight = size[1];
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
				? fsr.create(session.device().address(), session.physicalDevice().address(),
						VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr"), renderWidth, renderHeight, displayWidth, displayHeight, builtHdr)
				: bridge.create(commandBuffer.address(), renderWidth, renderHeight, displayWidth, displayHeight, mode, FEATURE_FLAGS | (builtHdr ? 1 | (1 << 6) : 0),
						requestedPreset(mode));
		drainLog();
		if (result != 0) {
			throw new IllegalStateException(selected + " feature creation failed (0x" + Integer.toHexString(result) + ")");
		}
		barrier(commandBuffer);
		builtMode = mode;
		builtUpscaler = selected;
		builtModel = ReflexiumConfig.get().dlssModel();
		builtPreset = requestedPreset(mode);
		renderSizeApplied = false;
		requestReset(ResetReason.REBUILD);
		reactiveMaskFailed = false;
		havePrevious = false;
		jitterIndex = 0;
		lastEvaluateNs = 0;
		evaluatedFrames = 0;
		historyResetFrames = 0;
		java.util.Arrays.fill(resetsByReason, 0L);
		ReflexiumClient.LOGGER.info("{} {}: rendering {}x{}, output {}x{}; DLSS model {} (requested preset {}), texture LOD bias {}",
				selected, mode.displayName(), renderWidth, renderHeight, displayWidth, displayHeight, builtModel, builtPreset, mipBias);
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
				for (PostPass pass : ((com.bambooz.reflexium.mixin.PostChainAccessor) chain).reflexium$passes()) {
					for (PostPass.Input input : ((com.bambooz.reflexium.mixin.PostPassAccessor) pass).reflexium$inputs()) {
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
					depthTransform.m22(), depthTransform.m32(), jitterNdcX(), jitterNdcY());
			FrameProfiler.end(FrameProfiler.DISPLAY_DEPTH, timing, commands);
			depthResolved = true;
		} catch (RuntimeException e) {
			lastError = "post effect depth resolve failed: " + e;
			ReflexiumClient.LOGGER.error("Post effect depth resolve failed", e);
		}
	}

	private void evaluate(RenderTarget main) {
		VkCommandBuffer commandBuffer = commandBuffer();
		long timing = FrameProfiler.start(FrameProfiler.BARRIER_BEFORE, commandBuffer);
		barrier(commandBuffer);
		FrameProfiler.end(FrameProfiler.BARRIER_BEFORE, timing, commandBuffer);
		float signedJitterX = DebugSettings.jitterSignX(JITTER_SIGN) * jitterX;
		float signedJitterY = DebugSettings.jitterSignY(JITTER_SIGN) * jitterY;
		long now = System.nanoTime();
		float frameTimeMs = lastEvaluateNs == 0 ? 16.6f : (now - lastEvaluateNs) / 1_000_000f;
		lastFrameMs = frameTimeMs;
		lastEvaluateNs = now;
		boolean resetting = resetNext;
		frameGenerationReset = resetting;
		UpscaleCapture request = capture;
		if (request != null && builtUpscaler != Upscaler.OFF) {
			Vec3 delta = currentCamera.subtract(previousCamera);
			takeCapture(request, main, signedJitterX, signedJitterY,
					DlssMath.reprojection(previousUnjittered, currentJittered, (float) delta.x, (float) delta.y, (float) delta.z),
					frameTimeMs, resetting);
		}
		if (builtUpscaler == Upscaler.OFF) {
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(main.getColorTexture(), renderTarget.getColorTexture(),
					0, 0, 0, 0, 0, displayWidth, displayHeight);
			encoder.copyTextureToTexture(main.getDepthTexture(), renderTarget.getDepthTexture(),
					0, 0, 0, 0, 0, displayWidth, displayHeight);
			resetNext = false;
			return;
		}
		float sharpness = ReflexiumConfig.get().fsrSharpness() / 100f;
		boolean sharpen = builtUpscaler == Upscaler.DLSS && sharpenSource != null && sharpness > 0;
		RenderTarget outputTarget = builtHdr ? hdrOutput : sharpen ? sharpenSource : renderTarget;
		NgxBridge.Image outputImage = ngxImage(outputTarget.getColorTexture(), outputTarget.getColorTextureView());
		if (resetting) {
			historyResetFrames++;
			resetsByReason[pendingReset.ordinal()]++;
			lastReset = pendingReset;
		}
		if (!Float.isFinite(signedJitterX) || !Float.isFinite(signedJitterY) || !Float.isFinite(frameTimeMs)) {
			throw new IllegalStateException("non-finite upscaler input: jitter " + signedJitterX + "," + signedJitterY + " frame " + frameTimeMs);
		}
		timing = FrameProfiler.start(FrameProfiler.EVALUATE, commandBuffer);
		int result = builtUpscaler == Upscaler.FSR ? fsr.evaluate(commandBuffer.address(),
				ngxImage(main.getColorTexture(), main.getColorTextureView()),
				ngxImage(motionTarget.getDepthTexture(), motionTarget.getDepthTextureView()),
				ngxImage(motionTarget.getColorTexture(), motionTarget.getColorTextureView()), outputImage,
				packSurfaceApplied ? ngxImage(packComposition.getColorTexture(), packComposition.getColorTextureView()) : reactiveGenerated ? reactive.asNgx() : null,
				packSurfaceApplied ? ngxImage(packComposition.getColorTexture(), packComposition.getColorTextureView()) : null,
				signedJitterX, signedJitterY, resetNext, frameTimeMs, cameraNear, motionFar, cameraFov,
				sharpness) : bridge.evaluate(commandBuffer.address(),
				ngxImage(main.getColorTexture(), main.getColorTextureView()),
				ngxImage(motionTarget.getDepthTexture(), motionTarget.getDepthTextureView()),
				ngxImage(motionTarget.getColorTexture(), motionTarget.getColorTextureView()),
				outputImage,
				signedJitterX, signedJitterY, 1.0f, 1.0f, resetNext, frameTimeMs);
		FrameProfiler.end(FrameProfiler.EVALUATE, timing, commandBuffer);
		if (result != 0) {
			drainLog();
			throw new IllegalStateException(builtUpscaler + " evaluate returned 0x" + Integer.toHexString(result));
		}
		if (sharpen) {
			timing = FrameProfiler.start(FrameProfiler.SHARPEN, commandBuffer);
			barrier(commandBuffer);
			sharpenPass.render(sharpenSource.getColorTextureView(), renderTarget, sharpness);
			FrameProfiler.end(FrameProfiler.SHARPEN, timing, commandBuffer);
		}
		resetNext = false;
		if (++evaluatedFrames == 1) {
			ReflexiumClient.LOGGER.info("{} evaluated first world frame: {}x{} -> {}x{} (camera and object motion vectors)",
					builtUpscaler, renderWidth, renderHeight, displayWidth, displayHeight);
		}
		timing = FrameProfiler.start(FrameProfiler.BARRIER_AFTER, commandBuffer);
		barrier(commandBuffer);
		FrameProfiler.end(FrameProfiler.BARRIER_AFTER, timing, commandBuffer);
		finishCapture();
	}

	static NgxBridge.Image ngxImage(GpuTexture texture, GpuTextureView view) {
		int aspect = texture.getFormat().hasColorAspect() ? VK10.VK_IMAGE_ASPECT_COLOR_BIT : VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
		return new NgxBridge.Image(((VulkanGpuTexture) texture).vkImage(), ((VulkanGpuTextureView) view).vkImageView(),
				VulkanConst.toVk(texture.getFormat()), texture.getWidth(0), texture.getHeight(0), aspect);
	}

	/** The open frame command buffer for profiling scopes outside this class, or null before the device exists. */
	public VkCommandBuffer profilingCommands() {
		return device == null ? null : commandBuffer();
	}

	VkCommandBuffer commandBuffer() {
		return ((VulkanCommandEncoderAccessor) device.createCommandEncoder()).reflexium$commandBuffer();
	}

	static void barrier(VkCommandBuffer commandBuffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
		}
	}

	static void swap(RenderTarget a, RenderTarget b) {
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
		com.bambooz.reflexium.compat.IrisCompat.syncTargetVersions(a);
		com.bambooz.reflexium.compat.IrisCompat.syncTargetVersions(b);
	}

	private void updateGlobals(int width, int height) {
		if (usesTransientUniforms() && renderWidth == displayWidth && renderHeight == displayHeight) return;
		if (globals != null) {
			globals.update(width, height, glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
		}
	}

	/** The preset hint passed to NGX at feature creation. NGX does not report the model it actually selected. */
	private int requestedPreset(UpscaleQuality mode) {
		return DebugSettings.dlssPreset >= 0 ? DebugSettings.dlssPreset : ReflexiumConfig.get().dlssModel().preset(mode);
	}
	private boolean wantsPackHdr(Upscaler selected) {
		return selected != Upscaler.OFF && VitrailCompat.isDrawingPack() && ComplementaryHdr.ready() && ComplementaryHdr.storageSupported();
	}

	public boolean usesTransientUniforms() {
		// FSR showed no repeatable gain and a state-sensitive regression; retain its existing upload path.
		return DebugSettings.fastUniforms && builtUpscaler != Upscaler.FSR;
	}

	public boolean usesDirectDepth() {
		// The FSR candidate did not retain a measurable frame-time benefit in the paired retest.
		return DebugSettings.directDepth && builtUpscaler != Upscaler.FSR;
	}

	/** Halton(2,3) sub-pixel offsets in render pixels. DLSS uses at least 32 phases, FSR its own ratio rule. */
	private void advanceJitter() {
		jitterPhases = DlssMath.jitterPhases(renderWidth, displayWidth, builtUpscaler);
		jitterIndex = jitterIndex % jitterPhases + 1;
		if (builtUpscaler == Upscaler.OFF || !DebugSettings.jitterEnabled()) {
			jitterX = 0.0f;
			jitterY = 0.0f;
			return;
		}
		jitterX = DlssMath.halton(jitterIndex, 2) - 0.5f;
		jitterY = DlssMath.halton(jitterIndex, 3) - 0.5f;
	}

	private float jitterNdcX() {
		return 2.0f * jitterX / renderWidth;
	}

	private float jitterNdcY() {
		return 2.0f * jitterY / renderHeight;
	}

	private Matrix4f jitter(Matrix4f projection) {
		return new Matrix4f().translation(jitterNdcX(), jitterNdcY(), 0.0f).mul(projection);
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
		drainLog();
	}

	private void drainLog() {
		if (bridge == null) {
			return;
		}
		String log = bridge.takeLog();
		if (!log.isBlank()) {
			for (String line : log.split("\n")) {
				if (!line.isBlank()) {
					ReflexiumClient.LOGGER.info("[NGX] {}", line.strip());
				}
			}
		}
	}

	private void releaseFeatureResources() {
		ComplementaryHdr.release();
		if (hdrInput != null) { hdrInput.destroyBuffers(); hdrInput = null; }
		if (hdrOutput != null) { hdrOutput.destroyBuffers(); hdrOutput = null; }
		if (sharpenSource != null) { sharpenSource.destroyBuffers(); sharpenSource = null; }
		if (packMotionBase != null) { packMotionBase.destroyBuffers(); packMotionBase = null; }
		if (packComposition != null) { packComposition.destroyBuffers(); packComposition = null; }
		packSurfacePass = null;
		builtHdr = false;
		FrameGenerationRenderer.get().release();
		materialSamplers.close();
		if (packHandBefore != null) { packHandBefore.destroyBuffers(); packHandBefore = null; }
		if (packHandMask != null) { packHandMask.destroyBuffers(); packHandMask = null; }
		if (reactive != null) {
			reactive.destroy();
			reactive = null;
		}
		if (opaqueTarget != null) {
			opaqueTarget.destroyBuffers();
			opaqueTarget = null;
		}
		if (fsr != null) fsr.release();
		if (bridge != null && ngxReady) {
			bridge.release();
		}
		if (renderTarget != null) {
			renderTarget.destroyBuffers();
			renderTarget = null;
		}
		if (worldDepth != null) {
			worldDepth.destroyBuffers();
			worldDepth = null;
		}
		if (motionTarget != null) {
			motionTarget.destroyBuffers();
			motionTarget = null;
		}
	}

	void waitForGpu() {
		if (device != null) {
			device.createCommandEncoder().submit();
			int result = VK10.vkDeviceWaitIdle(session.device());
			if (result != VK10.VK_SUCCESS) throw new IllegalStateException("wait before releasing upscaler resources: " + result);
		}
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
