package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.upscale.dlss.DlssRuntimeInstaller;
import com.bambooz.reflexium.upscale.dlss.DlssStatus;
import com.bambooz.reflexium.upscale.dlss.DlssSupport;
import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.bambooz.reflexium.upscale.fsr.FsrBridge;
import com.bambooz.reflexium.vulkan.VulkanSession;

import java.nio.file.Files;

/**
 * The FSR runtime and Streamline's DLSS capability on Minecraft's device: loading, the first-use
 * DLSS runtime download, and why either is unavailable. Render thread only.
 */
final class UpscalerRuntimes {
	private VulkanSession session;
	private FsrBridge fsr;
	private String fsrUnavailable = "needs Windows x64 and Vulkan";
	private String dlssUnavailable = "needs the Vulkan renderer";
	private boolean dlssReady;
	private boolean awaitingRuntime;

	/** Loads FSR for a new device. Streamline's device state follows in {@link #initializeDlss()}. */
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
		dlssReady = false;
	}

	/** Reads Streamline's DLSS capability and starts the first-use runtime download when it is missing. */
	void initializeDlss() {
		awaitingRuntime = false;
		dlssReady = false;
		if (session == null) {
			dlssUnavailable = "needs the Vulkan renderer";
			return;
		}
		if (!session.isNvidia()) {
			dlssUnavailable = "not an NVIDIA GPU";
			return;
		}
		if (!StreamlineSupport.initialized()) {
			dlssUnavailable = "Streamline unavailable (" + StreamlineSupport.problem() + ")";
			return;
		}
		DlssStatus prerequisites = DlssSupport.status();
		if (prerequisites.prerequisitesMet() && !prerequisites.featureDlls().isEmpty()) {
			dlssReady = StreamlineSupport.dlssAvailable();
			if (!dlssReady) {
				dlssUnavailable = "Streamline DLSS unavailable" + (StreamlineSupport.problem().isEmpty() ? "" : " (" + StreamlineSupport.problem() + ")");
				if (DlssRuntimeInstaller.hasPendingWork()) {
					awaitingRuntime = true;
					dlssUnavailable = DlssRuntimeInstaller.state().message();
				}
			}
			return;
		}
		awaitingRuntime = DlssRuntimeInstaller.hasPendingWork()
				|| DlssRuntimeInstaller.state().phase() == DlssRuntimeInstaller.Phase.DOWNLOADING;
		dlssUnavailable = awaitingRuntime && DlssRuntimeInstaller.state().message().isEmpty()
				? "preparing the DLSS runtime"
				: DlssRuntimeInstaller.hasPendingWork() ? DlssRuntimeInstaller.state().message() : prerequisites.summary();
	}

	/** Once per frame: a first-use download that finished makes DLSS available after a restart. */
	void pollRuntimeDownload() {
		if (!awaitingRuntime || session == null) return;
		DlssRuntimeInstaller.Phase phase = DlssRuntimeInstaller.state().phase();
		if (DlssRuntimeInstaller.ensureInstalledAsync().isDone()) {
			awaitingRuntime = false;
			if (phase == DlssRuntimeInstaller.Phase.INSTALLED || phase == DlssRuntimeInstaller.Phase.READY
					|| Files.isRegularFile(DlssSupport.featureFolder().resolve(DlssRuntimeInstaller.FILE_NAME))) {
				DlssSupport.probe(session);
				try {
					// Streamline loads its NGX runtimes from its own folder at slInit; a file that
					// arrives after device setup cannot be picked up without a relaunch.
					StreamlineSupport.installRuntime();
				} catch (java.io.IOException e) {
					ReflexiumClient.LOGGER.warn("Could not copy the DLSS runtime into the Streamline folder", e);
				}
				dlssUnavailable = "DLSS runtime installed; restart the game to enable it";
				awaitingRuntime = false;
			}
		}
	}

	/** A settings change is the player's retry: restart a failed runtime download. */
	void retry() {
		if (!dlssReady && !awaitingRuntime && session != null && session.isNvidia() && DlssRuntimeInstaller.retryFailed()) {
			DlssSupport.probe(session);
			initializeDlss();
		}
	}

	/** Releases the per-configuration upscaler features. The runtimes stay loaded. */
	void releaseFeatures() {
		if (fsr != null) fsr.release();
		if (StreamlineSupport.initialized()) StreamlineSupport.freeDlss();
	}

	/** Device teardown. Streamline itself is shut down by the upscaling renderer. */
	void shutdown() {
		dlssReady = false;
		awaitingRuntime = false;
		fsr = null;
		session = null;
		dlssUnavailable = "needs the Vulkan renderer";
	}

	Upscaler resolve(Upscaler requested) {
		return requested.resolve(dlssReady, fsr != null);
	}

	boolean available(Upscaler value) {
		return switch (value) { case DLSS -> dlssReady; case FSR -> fsr != null; default -> false; };
	}

	boolean anyReady() { return dlssReady || fsr != null; }
	boolean dlssReady() { return dlssReady; }
	FsrBridge fsr() { return fsr; }

	/** Why {@code selected} cannot run. Anything but DLSS reports the FSR reason, as the status line always has. */
	String unavailableReason(Upscaler selected) {
		return selected == Upscaler.DLSS ? dlssUnavailable : fsrUnavailable;
	}

	/** Copies Streamline's own log lines into Reflexium's log. */
	void drainLog() {
		if (!StreamlineSupport.initialized()) {
			return;
		}
		String log = StreamlineSupport.takeLog();
		if (!log.isBlank()) {
			for (String line : log.split("\n")) {
				if (!line.isBlank()) {
					ReflexiumClient.LOGGER.info("[Streamline] {}", line.strip());
				}
			}
		}
	}
}
