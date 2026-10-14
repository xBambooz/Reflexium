package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.mixin.GameRendererAccessor;
import com.bambooz.reflexium.mixin.RenderTargetAccessor;
import com.bambooz.reflexium.mixin.VulkanCommandEncoderAccessor;
import com.bambooz.reflexium.vulkan.VulkanSession;
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
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Locale;

/**
 * DLSS Super Resolution for vanilla's Vulkan renderer.
 * <p>
 * Each frame with a world: the textures of a render-resolution target are swapped into Minecraft's
 * main target before the world pass (so every world, hand and post-effect pass renders at render
 * resolution with a jittered projection), motion vectors are built from depth after the hand, DLSS
 * upscales the result into the display-resolution textures, and the swap is undone before the GUI
 * draws. Outside that window the main target always holds the display-resolution textures, so
 * screenshots, the swapchain blit and window-size checks see what they expect.
 */
public final class UpscalingRenderer {
	private static final UpscalingRenderer INSTANCE = new UpscalingRenderer();

	/** NVSDK_NGX_DLSS_Feature_Flags: MVLowRes | DepthInverted (Minecraft renders reverse-Z). */
	private static final int FEATURE_FLAGS = (1 << 1) | (1 << 3);
	/** Camera moves further than this (blocks) between frames: treat as a cut and reset history. */
	private static final double RESET_DISTANCE = 8.0;
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
	private float cameraNear = 0.05f, cameraFar = 1024f, cameraFov = 1.22f;
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
	private boolean renderSizeApplied;

	private TextureTarget renderTarget;
	private TextureTarget worldDepth;
	private TextureTarget motionTarget;
	private OutputImage reactive;
	private TextureTarget opaqueTarget;
	private final MipBiasSamplers materialSamplers = new MipBiasSamplers();
	private float mipBias;
	private MotionVectorPass motionPass;
	private ObjectVelocityPass objectVelocityPass;
	private MotionDebugPass motionDebugPass;
	private PackHandMaskPass packHandMaskPass;
	private TextureTarget packHandBefore;
	private TextureTarget packHandMask;
	private boolean packHandCaptured;
	private volatile String lastError = "";

	// Per-frame state.
	private boolean activeThisFrame;
	private boolean swapped;
	private boolean worldDepthCaptured;
	private boolean opaqueCaptured;
	private boolean reactiveGenerated;
	private boolean reactiveMaskFailed;
	private int jitterIndex;
	private float jitterX;
	private float jitterY;
	private final Matrix4f currentJittered = new Matrix4f();
	private final Matrix4f currentProjection = new Matrix4f();
	private float lastFrameMs = 16.6f;
	private final Matrix4f currentUnjittered = new Matrix4f();
	private final Matrix4f previousUnjittered = new Matrix4f();
	private Vec3 currentCamera = Vec3.ZERO;
	private Vec3 previousCamera = Vec3.ZERO;
	private final Quaternionf currentOrientation = new Quaternionf();
	private final Quaternionf previousOrientation = new Quaternionf();
	private Object previousLevel;
	private boolean havePrevious;
	private boolean resetNext = true;
	private long lastEvaluateNs;
	private long evaluatedFrames;

	// Global uniform state captured before the world pass.
	private GlobalSettingsUniform globals;
	private double glintAlpha;
	private long gameTime;
	private float worldPartialTicks;
	private int menuBlurRadius;
	private Vec3 cameraPos;
	private boolean useRgss;

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

	public void onDeviceClosing() {
		if (device != null) {
			destroyResources();
		}
		if (bridge != null && ngxInitialized) {
			FrameGenerationRenderer.get().onDeviceClosing();
			bridge.shutdown();
		}
		else FrameGenerationRenderer.get().onDeviceClosing();
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
		FrameGenerationRenderer.get().beginFrame();
		if (awaitingRuntime && device != null) {
			DlssRuntimeInstaller.Phase phase = DlssRuntimeInstaller.state().phase();
			if (phase != DlssRuntimeInstaller.Phase.DOWNLOADING && DlssRuntimeInstaller.ensureInstalledAsync().isDone()) {
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
		worldDepthCaptured = false;
		opaqueCaptured = false;
		reactiveGenerated = false;
		packHandCaptured = false;
		WindowRenderState window = gameRenderer.gameRenderState().windowRenderState;
		UpscaleQuality mode = ReflexiumConfig.get().upscaleQuality();
		Upscaler selected = effectiveUpscaler();
		if (selected == Upscaler.OFF) mode = UpscaleQuality.NATIVE_AA;
		VitrailCompat.configureUpscaling(available(selected) && !failed);
		boolean wanted = (available(selected) || FrameGenerationRenderer.get().wantsMotion()) && !failed && gameRenderer.gameRenderState().shouldRenderLevel && !window.isMinimized
				&& window.width > 0 && window.height > 0 && VitrailCompat.canUpscale();
		if (!wanted) {
			ObjectMotion.get().beginFrame(false, false);
			if (renderSizeApplied) {
				restoreDisplaySize(gameRenderer, window);
			}
			return;
		}
		try {
			if (mode != builtMode || selected != builtUpscaler || ReflexiumConfig.get().dlssModel() != builtModel
					|| window.width != displayWidth || window.height != displayHeight || renderTarget == null) {
				build(gameRenderer, mode, window.width, window.height);
			}
			if (!renderSizeApplied) {
				applyRenderSize(gameRenderer);
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
		if (!activeThisFrame) {
			return;
		}
		RenderTarget main = gameRenderer.mainRenderTarget();
		swap(main, renderTarget);
		swapped = true;
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(main.getColorTexture(),
				gameRenderer.gameRenderState().guiRenderState.clearColorOverride, main.getDepthTexture(), 0.0);
		updateGlobals(renderWidth, renderHeight);
		if (packHandMask != null) RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(packHandMask.getDepthTexture(), 0.0);
		ObjectMotion.get().beginFrame(DebugSettings.objectMotion(), DebugSettings.drawStaticObjects());
	}

	/** Jitters the level projection and records the matrices motion vectors need. */
	public Matrix4f jitterLevelProjection(Matrix4f projection, CameraRenderState camera) {
		if (!swapped) {
			return projection;
		}
		currentUnjittered.set(projection).mul(camera.viewRotationMatrix);
		currentProjection.set(projection);
		cameraNear = projection.m32() / (projection.m22() + 1.0f);
		cameraFar = projection.m32() / projection.m22();
		cameraFov = 2.0f * (float) Math.atan(1.0f / Math.abs(projection.m11()));
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

	public boolean isUpscalingThisFrame() {
		return swapped;
	}

	public boolean isUpscalingRequestedThisFrame() { return activeThisFrame; }
	public Matrix4f jitterShaderProjection(Matrix4fc projection) {
		return swapped ? jitter(new Matrix4f(projection)) : new Matrix4f(projection);
	}

	public GpuSampler materialSampler(GpuSampler sampler) {
		return swapped ? materialSamplers.get(sampler, mipBias) : sampler;
	}
	public static float creatingSamplerBias() { return MipBiasSamplers.creatingBias(); }

	/** Vitrail draws the hand inside its level chain, before vanilla's HUD depth target exists. */
	public void capturePackHandBefore() {
		if (!swapped || packHandBefore == null) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getDepthTexture(), packHandBefore.getDepthTexture(),
				0, 0, 0, 0, 0, renderWidth, renderHeight);
	}

	public void capturePackHandAfter() {
		if (!swapped || packHandBefore == null) return;
		packHandMaskPass.render(packHandMask, packHandBefore.getDepthTextureView(),
				Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView());
		packHandCaptured = true;
	}

	public boolean supportsReactiveMask() {
		return swapped && builtUpscaler == Upscaler.FSR && opaqueTarget != null && !VitrailCompat.isDrawingPack();
	}

	/** Called after opaque rendering ends, before any transparency writes to the scene. */
	public void captureOpaqueColor() {
		if (!supportsReactiveMask()) return;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), opaqueTarget.getColorTexture(),
				0, 0, 0, 0, 0, renderWidth, renderHeight);
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
			barrier(commands);
			int result = fsr.generateReactive(commands.address(), ngxImage(opaqueTarget.getColorTexture(), opaqueTarget.getColorTextureView()),
					ngxImage(main.getColorTexture(), main.getColorTextureView()), reactive.asNgx());
			barrier(commands);
			if (result != 0) throw new IllegalStateException("FSR reactive mask returned " + result);
			reactiveGenerated = true;
			} catch (RuntimeException e) {
				reactiveMaskFailed = true;
				lastError = "reactive mask disabled: " + e;
				ReflexiumClient.LOGGER.warn("FSR reactive mask failed; continuing with FSR shading-change detection", e);
			}
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getDepthTexture(), worldDepth.getDepthTexture(),
				0, 0, 0, 0, 0, renderWidth, renderHeight);
		worldDepthCaptured = true;
	}

	/** Shader packs draw their hand inside renderLevel and suppress vanilla's render3dHud. */
	public void afterLevel(GameRenderer gameRenderer) {
		if (swapped && !worldDepthCaptured) {
			captureWorldDepth(gameRenderer);
			renderMotionVectors(gameRenderer, false);
		}
	}

	/** render3dHud RETURN: motion vectors, with the hand (now in {@code handDepth}) given zero motion. */
	public void renderMotionVectors(GameRenderer gameRenderer, boolean handInHud3dTarget) {
		if (!swapped || !worldDepthCaptured) {
			return;
		}
		RenderTarget handTarget = handInHud3dTarget
				? ((GameRendererAccessor) gameRenderer).reflexium$hud3DTarget()
				: gameRenderer.mainRenderTarget();
		GpuTextureView handDepth = packHandCaptured ? packHandMask.getDepthTextureView() : handTarget.getDepthTextureView();
		Object level = Minecraft.getInstance().level;
		if (!havePrevious || level != previousLevel || currentCamera.distanceToSqr(previousCamera) > RESET_DISTANCE * RESET_DISTANCE
				|| System.nanoTime() - lastEvaluateNs > 250_000_000L) {
			resetNext = true;
			previousUnjittered.set(currentUnjittered);
			previousCamera = currentCamera;
		}
		Vec3 delta = currentCamera.subtract(previousCamera);
		Matrix4f currentToPrevious = DlssMath.reprojection(previousUnjittered, currentJittered,
				(float) delta.x, (float) delta.y, (float) delta.z);
		try {
			motionPass.render(motionTarget, worldDepth.getDepthTextureView(), handDepth,
					currentToPrevious, jitterNdcX(), jitterNdcY());
			objectVelocityPass.render(ObjectMotion.get(), motionTarget, worldDepth.getDepthTextureView(), handDepth,
					currentJittered, currentUnjittered, previousUnjittered, (float) delta.x, (float) delta.y, (float) delta.z, resetNext);
		} catch (RuntimeException e) {
			worldDepthCaptured = false;
			fail("motion vector pass failed", e);
		} finally {
			ObjectMotion.get().endFrame();
		}
		previousLevel = level;
	}

	/** After GameRenderer.applyPostEffects: upscale into the display textures and undo the swap. */
	public void afterPostEffects(GameRenderer gameRenderer) {
		if (!swapped) {
			return;
		}
		RenderTarget main = gameRenderer.mainRenderTarget();
		boolean fgReset = resetNext;
		try {
			if (worldDepthCaptured) {
				evaluate(main);
				SharpBlockOutlineRenderer.get().render(renderTarget, motionTarget, currentProjection);
			} else {
				// Pack warmup can replace the level call while still presenting a loading frame.
				// Keep that frame visible and start fresh when actual scene rendering resumes.
				main.blitAndBlendToTexture(renderTarget.getColorTextureView(), renderTarget.getDepthTextureView());
				resetNext = true;
				SharpBlockOutlineRenderer.get().clear();
			}
		} catch (RuntimeException e) {
			fail("evaluate failed", e);
			main.blitAndBlendToTexture(renderTarget.getColorTextureView(), renderTarget.getDepthTextureView());
			SharpBlockOutlineRenderer.get().clear();
		} finally {
			swap(main, renderTarget);
			swapped = false;
			updateGlobals(displayWidth, displayHeight);
		}
		if (DebugSettings.motionVectorView() && worldDepthCaptured && motionTarget != null) {
			drawMotionVectorView(main);
		}
		if (worldDepthCaptured && !failed) {
			Vec3 delta = currentCamera.subtract(previousCamera);
			FrameGenerationRenderer.get().sceneReady(main, motionTarget, commandBuffer(), currentProjection,
					DlssMath.reprojection(previousUnjittered, currentUnjittered, (float) delta.x, (float) delta.y, (float) delta.z),
					currentOrientation, (float) currentCamera.x, (float) currentCamera.y, (float) currentCamera.z,
					DebugSettings.jitterSignX(JITTER_SIGN) * jitterX, DebugSettings.jitterSignY(JITTER_SIGN) * jitterY,
					cameraNear, cameraFar, cameraFov, lastFrameMs, fgReset);
		}
		previousUnjittered.set(currentUnjittered);
		previousCamera = currentCamera;
		previousOrientation.set(currentOrientation);
		havePrevious = worldDepthCaptured;
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
			resetNext = true;
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
		if (!VitrailCompat.canUpscale()) {
			return name + ": " + VitrailCompat.upscalingProblem();
		}
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

	public DebugInfo debugInfo() {
		return new DebugInfo(activeThisFrame, builtUpscaler.serializedName(), jitterIndex, jitterX, jitterY,
				DebugSettings.jitterSignX(JITTER_SIGN), DebugSettings.jitterSignY(JITTER_SIGN),
				renderWidth, renderHeight, displayWidth, displayHeight, lastError,
				ObjectMotion.get().capturedVertices(), ObjectMotion.get().drawnVertices(), ObjectMotion.get().trackedEntities(),
				ObjectMotion.get().trackedBlockEntities(), mipBias, reactiveGenerated, evaluatedFrames, packHandCaptured);
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
		FrameGenerationRenderer.get().retry();
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
		renderTarget = new TextureTarget("Reflexium DLSS render", renderWidth, renderHeight, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		worldDepth = new TextureTarget("Reflexium DLSS world depth", renderWidth, renderHeight, null, GpuFormat.D32_FLOAT);
		motionTarget = new TextureTarget("Reflexium DLSS motion", renderWidth, renderHeight, MotionVectorPass.FORMAT, GpuFormat.D32_FLOAT);
		if (motionPass == null) {
			motionPass = new MotionVectorPass();
			objectVelocityPass = new ObjectVelocityPass();
		}
		VkCommandBuffer commandBuffer = commandBuffer();
		mipBias = DlssMath.mipBias(renderWidth, displayWidth);
		if (VitrailCompat.isLoaded()) {
			packHandBefore = new TextureTarget("Reflexium pack before hand", renderWidth, renderHeight, null, GpuFormat.D32_FLOAT);
			packHandMask = new TextureTarget("Reflexium pack hand mask", renderWidth, renderHeight, null, GpuFormat.D32_FLOAT);
			if (packHandMaskPass == null) packHandMaskPass = new PackHandMaskPass();
		}
		if (selected == Upscaler.FSR) {
			opaqueTarget = new TextureTarget("Reflexium opaque scene", renderWidth, renderHeight, GpuFormat.RGBA8_UNORM, null);
			reactive = new OutputImage(device, commandBuffer, renderWidth, renderHeight, VK10.VK_FORMAT_R8_UNORM);
		}
		int result = selected == Upscaler.OFF ? 0 : selected == Upscaler.FSR
				? fsr.create(session.device().address(), session.physicalDevice().address(),
						VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr"), renderWidth, renderHeight, displayWidth, displayHeight)
				: bridge.create(commandBuffer.address(), renderWidth, renderHeight, displayWidth, displayHeight, mode, FEATURE_FLAGS,
						ReflexiumConfig.get().dlssModel().preset());
		drainLog();
		if (result != 0) {
			throw new IllegalStateException(selected + " feature creation failed (0x" + Integer.toHexString(result) + ")");
		}
		barrier(commandBuffer);
		builtMode = mode;
		builtUpscaler = selected;
		builtModel = ReflexiumConfig.get().dlssModel();
		renderSizeApplied = false;
		resetNext = true;
		reactiveMaskFailed = false;
		havePrevious = false;
		jitterIndex = 0;
		lastEvaluateNs = 0;
		evaluatedFrames = 0;
		ReflexiumClient.LOGGER.info("{} {}: rendering {}x{}, output {}x{}; DLSS model {}", selected, mode.displayName(), renderWidth, renderHeight, displayWidth, displayHeight, builtModel);
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

	private void evaluate(RenderTarget main) {
		VkCommandBuffer commandBuffer = commandBuffer();
		barrier(commandBuffer);
		float signedJitterX = DebugSettings.jitterSignX(JITTER_SIGN) * jitterX;
		float signedJitterY = DebugSettings.jitterSignY(JITTER_SIGN) * jitterY;
		long now = System.nanoTime();
		float frameTimeMs = lastEvaluateNs == 0 ? 16.6f : (now - lastEvaluateNs) / 1_000_000f;
		lastFrameMs = frameTimeMs;
		lastEvaluateNs = now;
		if (builtUpscaler == Upscaler.OFF) {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), renderTarget.getColorTexture(),
					0, 0, 0, 0, 0, displayWidth, displayHeight);
			resetNext = false;
			return;
		}
		NgxBridge.Image outputImage = ngxImage(renderTarget.getColorTexture(), renderTarget.getColorTextureView());
		int result = builtUpscaler == Upscaler.FSR ? fsr.evaluate(commandBuffer.address(),
				ngxImage(main.getColorTexture(), main.getColorTextureView()),
				ngxImage(motionTarget.getDepthTexture(), motionTarget.getDepthTextureView()),
				ngxImage(motionTarget.getColorTexture(), motionTarget.getColorTextureView()), outputImage,
				reactiveGenerated ? reactive.asNgx() : null,
				signedJitterX, signedJitterY, resetNext, frameTimeMs, cameraNear, cameraFar, cameraFov,
				ReflexiumConfig.get().fsrSharpness() / 100f) : bridge.evaluate(commandBuffer.address(),
				ngxImage(main.getColorTexture(), main.getColorTextureView()),
				ngxImage(motionTarget.getDepthTexture(), motionTarget.getDepthTextureView()),
				ngxImage(motionTarget.getColorTexture(), motionTarget.getColorTextureView()),
				outputImage,
				signedJitterX, signedJitterY, 1.0f, 1.0f, resetNext, frameTimeMs);
		if (result != 0) {
			drainLog();
			throw new IllegalStateException(builtUpscaler + " evaluate returned 0x" + Integer.toHexString(result));
		}
		resetNext = false;
		if (++evaluatedFrames == 1) {
			ReflexiumClient.LOGGER.info("{} evaluated first world frame: {}x{} -> {}x{} (camera and object motion vectors)",
					builtUpscaler, renderWidth, renderHeight, displayWidth, displayHeight);
		}
		barrier(commandBuffer);
	}

	static NgxBridge.Image ngxImage(GpuTexture texture, GpuTextureView view) {
		int aspect = texture.getFormat().hasColorAspect() ? VK10.VK_IMAGE_ASPECT_COLOR_BIT : VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
		return new NgxBridge.Image(((VulkanGpuTexture) texture).vkImage(), ((VulkanGpuTextureView) view).vkImageView(),
				VulkanConst.toVk(texture.getFormat()), texture.getWidth(0), texture.getHeight(0), aspect);
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
	}

	private void updateGlobals(int width, int height) {
		if (globals != null) {
			globals.update(width, height, glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
		}
	}

	/** Halton(2,3) sub-pixel offsets in render pixels, phase count scaled with the upscale ratio. */
	private void advanceJitter() {
		int phases = DlssMath.jitterPhases(renderWidth, displayWidth);
		jitterIndex = jitterIndex % phases + 1;
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
			try {
				((VulkanCommandEncoderAccessor) device.createCommandEncoder()).reflexium$commandBuffer();
				device.createCommandEncoder().submit();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void destroyResources() {
		waitForGpu();
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
		motionDebugPass = null;
		packHandMaskPass = null;
		renderSizeApplied = false;
		builtMode = null;
	}
}
