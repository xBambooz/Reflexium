package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.dlss.DlssRuntimeInstaller;
import com.bambooz.reflexium.upscale.dlss.DlssStatus;
import com.bambooz.reflexium.upscale.dlss.DlssSupport;
import com.bambooz.reflexium.upscale.dlss.NgxBridge;
import com.bambooz.reflexium.upscale.fsr.FsrBridge;
import com.bambooz.reflexium.vulkan.VulkanSession;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;

import java.nio.file.Files;

/**
 * The DLSS (NGX) and FSR runtimes on Minecraft's device: loading, NGX initialization, the first-use DLSS download,
 * and why either is unavailable. Render thread only.
 */
final class UpscalerRuntimes {
	private static final boolean NGX_VERBOSE = Boolean.getBoolean("reflexium.dlss.verboseLog");

	private VulkanSession session;
	private NgxBridge ngx;
	private FsrBridge fsr;
	private String fsrUnavailable = "needs Windows x64 and Vulkan";
	private String dlssUnavailable = "needs the Vulkan renderer";
	private boolean ngxReady;
	private boolean ngxInitialized;
	private boolean awaitingRuntime;

	/** Loads FSR for a new device. NGX follows in {@link #initializeNgx()}, after frame generation saw the FSR runtime. */
	void loadFsr(VulkanSession session) {
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
	}

	/** Also runs once when a first-use download finishes. */
	void initializeNgx() {
		awaitingRuntime = false;
		if (DlssRuntimeInstaller.hasPendingWork()) {
			awaitingRuntime = true;
			dlssUnavailable = DlssRuntimeInstaller.state().message();
			return;
		}
		DlssStatus prerequisites = DlssSupport.status();
		if (!prerequisites.prerequisitesMet()) {
			dlssUnavailable = prerequisites.summary();
			awaitingRuntime = DlssRuntimeInstaller.state().phase() == DlssRuntimeInstaller.Phase.DOWNLOADING
					|| DlssRuntimeInstaller.state().phase() == DlssRuntimeInstaller.Phase.INSTALLED;
			return;
		}
		if (prerequisites.featureDlls().stream().noneMatch(dll -> dll.equals("nvngx_dlss.dll"))) {
			dlssUnavailable = "nvngx_dlss.dll missing from " + DlssSupport.featureFolder();
			return;
		}
		ngx = NgxBridge.get().orElse(null);
		if (ngx == null) {
			dlssUnavailable = "native bridge failed to load";
			return;
		}
		try {
			long gipa = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
			long gdpa = VK10.vkGetInstanceProcAddr(session.instance(), "vkGetDeviceProcAddr");
			String version = FabricLoader.getInstance().getModContainer(ReflexiumClient.MOD_ID)
					.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			int result = ngx.init(session.instance().address(), session.physicalDevice().address(), session.device().address(),
					gipa, gdpa, DlssSupport.featureFolder(), DlssSupport.featureFolder(), version, NGX_VERBOSE);
			drainLog();
			if (result != 0) {
				dlssUnavailable = "NGX init failed (0x" + Integer.toHexString(result) + ")";
				ReflexiumClient.LOGGER.warn("DLSS: {}", dlssUnavailable);
				return;
			}
			ngxInitialized = true;
			FrameGenerationRenderer.get().onNgxReady(ngx);
			int[] available = ngx.dlssAvailable();
			if (available[0] == 0) {
				dlssUnavailable = available[1] != 0
						? "driver too old, DLSS needs " + available[2] + "." + available[3]
						: "DLSS not available on this GPU (0x" + Integer.toHexString(available[4]) + ")";
				ReflexiumClient.LOGGER.warn("DLSS: {}", dlssUnavailable);
				return;
			}
			ngxReady = true;
			ReflexiumClient.LOGGER.info("DLSS Super Resolution ready (mode {})", ReflexiumConfig.get().upscaleQuality().displayName());
		} catch (RuntimeException e) {
			dlssUnavailable = "NGX init threw " + e.getClass().getSimpleName();
			ReflexiumClient.LOGGER.warn("DLSS: NGX init failed", e);
		}
	}

	/** Once per frame: a first-use download that finished makes DLSS available in the same session. */
	void pollRuntimeDownload() {
		if (!awaitingRuntime || session == null) return;
		DlssRuntimeInstaller.Phase phase = DlssRuntimeInstaller.state().phase();
		// The returned future is super resolution's alone; an optional FG download may still be running.
		if (DlssRuntimeInstaller.ensureInstalledAsync().isDone()) {
			awaitingRuntime = false;
			if (phase == DlssRuntimeInstaller.Phase.INSTALLED || phase == DlssRuntimeInstaller.Phase.READY
					|| Files.isRegularFile(DlssSupport.featureFolder().resolve(DlssRuntimeInstaller.FILE_NAME))) {
				DlssSupport.probe(session);
				initializeNgx();
			} else {
				dlssUnavailable = DlssSupport.status().summary();
			}
		}
	}

	/** A settings change is the player's retry: restart a failed runtime download or find a hand-placed DLL. */
	void retry() {
		if (!ngxReady && !awaitingRuntime && session != null && session.isNvidia() && DlssRuntimeInstaller.retryFailed()) {
			DlssSupport.probe(session);
			awaitingRuntime = true;
		}
	}

	/** Releases the per-configuration upscaler features. The runtimes stay loaded. */
	void releaseFeatures() {
		if (fsr != null) fsr.release();
		if (ngx != null && ngxReady) {
			ngx.release();
		}
	}

	/** Device teardown. The NGX bridge itself stays loaded for the next device. */
	void shutdown() {
		try {
			if (ngx != null && ngxInitialized) ngx.shutdown();
		} finally {
			ngxInitialized = false;
			ngxReady = false;
			awaitingRuntime = false;
			fsr = null;
			session = null;
			dlssUnavailable = "needs the Vulkan renderer";
		}
	}

	Upscaler resolve(Upscaler requested, boolean drawingPack) {
		return requested.resolve(ngxReady, fsr != null, drawingPack);
	}

	boolean available(Upscaler value) {
		return switch (value) { case DLSS -> ngxReady; case FSR -> fsr != null; default -> false; };
	}

	boolean anyReady() { return ngxReady || fsr != null; }
	boolean dlssReady() { return ngxReady; }
	NgxBridge ngx() { return ngx; }
	FsrBridge fsr() { return fsr; }

	/** Why {@code selected} cannot run. Anything but DLSS reports the FSR reason, as the status line always has. */
	String unavailableReason(Upscaler selected) {
		return selected == Upscaler.DLSS ? dlssUnavailable : fsrUnavailable;
	}

	/** Copies NGX's own log lines into Reflexium's log. */
	void drainLog() {
		if (ngx == null) {
			return;
		}
		String log = ngx.takeLog();
		if (!log.isBlank()) {
			for (String line : log.split("\n")) {
				if (!line.isBlank()) {
					ReflexiumClient.LOGGER.info("[NGX] {}", line.strip());
				}
			}
		}
	}
}
