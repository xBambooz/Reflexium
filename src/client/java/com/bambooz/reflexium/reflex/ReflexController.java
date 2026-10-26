package com.bambooz.reflexium.reflex;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.framegen.FrameGenerationRefreshPolicy;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.dlss.StreamlineBridge;
import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.bambooz.reflexium.upscale.fsr.FsrSwapchain;
import com.bambooz.reflexium.vulkan.FrameTrace;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;

import static org.lwjgl.vulkan.NVLowLatency2.*;

/**
 * NVIDIA Reflex through Streamline's {@code slReflexSetOptions}, {@code slReflexSleep} and PCL markers.
 * All methods except {@link #currentFrameId()} run on the render thread.
 * <p>
 * Per frame (hooks in MinecraftMixin):
 * <pre>
 *   run():       latency sleep,
 *                SIMULATION_START
 *   runTick():   SIMULATION_END                    (before renderFrame)
 *   renderFrame: RENDERSUBMIT_START                (before render-state extraction)
 *                RENDERSUBMIT_END, PRESENT_START   (before present)
 *                PRESENT_END                       (after present)
 * </pre>
 * One Streamline frame token is created per real frame and reused for the Reflex sleep, markers,
 * common constants and both DLSS features. The PCL Vulkan backend has no input-sample marker, so
 * that deprecated marker is not emitted. One sleep runs per frame in every mode, including Off,
 * because the driver also services the NVIDIA Control Panel frame limit from it. The only frame
 * limit Reflexium passes is Minecraft's own Max Framerate.
 */
public final class ReflexController {
	private static final ReflexController INSTANCE = new ReflexController();
	private static final long TIMING_REFRESH_NS = 250_000_000L;
	private static final long REFRESH_RATE_INTERVAL_NS = 1_000_000_000L;
	/** Minecraft treats 260 as "unlimited" for Max Framerate. */
	private static final int UNLIMITED_FPS = 260;
	private static final int NO_CAP = Integer.MAX_VALUE;
	/** -Dreflexium.logLatency=true writes the F3 Reflex lines to the log every few seconds. */
	private static final boolean LOG_LATENCY = Boolean.getBoolean("reflexium.logLatency");
	private static final long LATENCY_LOG_INTERVAL_NS = 5_000_000_000L;
	/** Completed Reflex sleeps that must precede the first DLSS-G frame of a new request. */
	private static final long FRAME_GENERATION_PRIME_SLEEPS = 2;
	private long lastLatencyLog;

	private boolean streamline;
	private boolean presentIdEnabled;
	private long swapchain;
	private boolean swapchainPresented;
	private long frameGenerationStartSleep = -1;

	private volatile long frameId;
	private long lastPresentedId;
	private volatile boolean frameOpen;
	private boolean simulationOpen;
	private final PacingState pacing = new PacingState();
	private ReflexMode loggedMode;
	private int requestedIntervalUs;
	private int baseFpsLimit = NO_CAP;
	private long timingFloorId, timingSeenId, timingSeenAt;
	/** Lifetime counters for measured-span validation; never reset, so callers compare deltas. */
	private long sleepsIssued, sleepsCompleted, unpacedFrames, suspendedFrames;

	private long lastTimingRefresh;
	private long lastRefreshRead;
	private float refreshRateHz;
	private boolean requestedCapIsFrameGeneration;
	private LatencyReport latency = LatencyReport.unavailable("no data yet");
	private int lastMarker = -1;
	private int lastMarkerRank;
	private long lastMarkerFrameId = -1;
	private long markerOrderRegressions;
	private int markerSuppressFrames;
	private int transitionFrames;

	public static ReflexController get() {
		return INSTANCE;
	}

	// ---------------------------------------------------------------------------------------------
	// Lifecycle

	public void onDeviceCreated(VulkanSession session) {
		shutdown();
		streamline = session.streamline() && StreamlineSupport.reflexAvailable();
		if (!streamline) {
			ReflexiumClient.LOGGER.info("Reflex: unavailable (Streamline Reflex is not active)");
			return;
		}
		// VK_NV_low_latency2 pairs Streamline's markers with presented frames through VkPresentIdKHR.
		presentIdEnabled = session.presentId();
		// Configure Reflex before the first swapchain: sl.reflex decides at vkCreateSwapchainKHR whether
		// the presentation path carries VK_NV_low_latency2 latency mode, and that decides whether the
		// driver publishes frame reports for the latency readout.
		applySleepMode();
		ReflexiumClient.LOGGER.info("Reflex: Streamline tokens and PCL markers");
	}

	public void onSwapchainCreated(long newSwapchain) {
		if (!streamline) {
			return;
		}
		swapchain = newSwapchain;
		swapchainPresented = false;
		pacing.reset();
		resetTiming();
		applySleepMode();
	}

	public void onSwapchainDestroyed() {
		swapchain = 0;
		swapchainPresented = false;
		frameOpen = false;
		simulationOpen = false;
		pacing.reset();
		resetTiming();
	}

	public void shutdown() {
		streamline = false;
		swapchain = 0;
		swapchainPresented = false;
		presentIdEnabled = false;
		frameOpen = false;
		simulationOpen = false;
		pacing.reset();
		requestedIntervalUs = 0;
		requestedCapIsFrameGeneration = false;
		lastRefreshRead = 0;
		refreshRateHz = 0;
		frameGenerationStartSleep = -1;
		resetTiming();
		latency = LatencyReport.unavailable("no data yet");
	}

	// ---------------------------------------------------------------------------------------------
	// State

	/** Streamline Reflex is available and the presentation scheduler allows low latency. */
	public boolean isSupported() {
		return streamline && StreamlineSupport.reflexAvailable() && !FsrSwapchain.active();
	}

	/** The driver integration is enabled and a swapchain exists. */
	public boolean isAvailable() {
		return streamline && StreamlineSupport.reflexAvailable() && swapchain != 0;
	}

	/** The driver is currently permitted to pace this frame. */
	public boolean isSleeping() {
		return swapchainPresented && transitionFrames == 0 && isAvailable() && pacing.canSleep();
	}

	public ReflexMode mode() {
		ReflexMode requested = ReflexiumConfig.get().reflexMode();
		return requested == ReflexMode.OFF && FrameGenerationRenderer.get().needsReflex() ? ReflexMode.ON : requested;
	}

	public void setMode(ReflexMode mode) {
		ReflexiumConfig.get().setReflexMode(mode);
		pacing.reset();
		resetTiming();
		applySleepMode();
	}

	/** Frame id for Streamline tokens, present ids and queue tracing. 0 means "not tagged". */
	public long currentFrameId() {
		return frameOpen ? frameId : 0;
	}
	public long lastFrameId() { return frameId; }
	public long sleepsIssued() { return sleepsIssued; }
	public long sleepsCompleted() { return sleepsCompleted; }
	public long unpacedFrames() { return unpacedFrames; }
	public long suspendedFrames() { return suspendedFrames; }
	public long sleepTimeouts() { return pacing.timeouts; }
	public long sleepRecoveries() { return pacing.recoveries; }
	public long pacingFailures() { return pacing.failures; }
	public long modeConfigurations() { return pacing.configurations; }
	public long markerOrderRegressions() { return markerOrderRegressions; }
	/** Applied driver mode ordinal, or -1 while unconfigured, failed or suspended. */
	public int appliedModeOrdinal() { return pacing.canSleep() && pacing.applied != null ? pacing.applied.ordinal() : -1; }

	/**
	 * DLSS-G must consume Reflex markers from a running Reflex instance. The caller waits for these
	 * completed sleeps after frame generation is requested and before DLSS-G options are enabled.
	 */
	public void noteFrameGenerationRequested(boolean requested) {
		if (!requested) {
			frameGenerationStartSleep = -1;
		} else if (frameGenerationStartSleep < 0) {
			frameGenerationStartSleep = sleepsCompleted;
		}
	}

	public boolean frameGenerationPrimed() {
		return frameGenerationStartSleep >= 0 && sleepsCompleted - frameGenerationStartSleep >= FRAME_GENERATION_PRIME_SLEEPS;
	}

	/** Id to attach to vkQueuePresentKHR, or 0 when this present must not be tagged. */
	public long takePresentId() {
		if (!presentIdEnabled || !frameOpen || frameId <= lastPresentedId) {
			return 0;
		}
		lastPresentedId = frameId;
		return frameId;
	}

	// ---------------------------------------------------------------------------------------------
	// Frame hooks

	/** Drops latency markers while presentation/upscaler resources are being rebuilt. */
	public void suspendMarkers(int frames) {
		if (frames > 0) markerSuppressFrames = Math.max(markerSuppressFrames, frames);
	}

	/** Temporarily disables Reflex sleep and markers while DLSS/DLSS-G resources are rebuilt. */
	public void beforeSurfaceChange() {
		suspendMarkers(8);
		transitionFrames = Math.max(transitionFrames, 30);
		if (!streamline) return;
		pacing.reset();
		applySleepMode();
	}

	public void beginFrame() {
		if (!isAvailable()) {
			return;
		}
		if (markerSuppressFrames > 0) markerSuppressFrames--;
		if (transitionFrames > 0) transitionFrames--;
		frameId++;
		// The Streamline token index is also the Reflex present id. Keeping one number for the
		// token, constants, PCL markers and VkPresentIdKHR is what DLSS-G compares at present.
		StreamlineSupport.beginFrame((int) frameId, false);
		frameOpen = true;
		lastMarker = -1;
		lastMarkerRank = 0;
		lastMarkerFrameId = frameId;
		applySleepMode();
		if (!swapchainPresented) {
			// A swapchain that has never presented has no Reflex attribution to sleep on. Resume
			// after the first successful present of the replacement swapchain.
			unpacedFrames++;
			return;
		}
		if (pacing.canSleep()) {
			long timing = FrameProfiler.start(FrameProfiler.REFLEX_SLEEP, null);
			sleepsIssued++;
			int result = StreamlineSupport.reflexSleep();
			FrameProfiler.end(FrameProfiler.REFLEX_SLEEP, timing, null);
			if (result == 0) {
				pacing.sleepResult(0);
				sleepsCompleted++;
			} else {
				pacing.sleepResult(-1);
				if (pacing.failed) {
					ReflexiumClient.LOGGER.warn("slReflexSleep failed ({}); Reflex sleep disabled until the mode is changed", result);
				}
			}
		} else {
			unpacedFrames++;
			if (pacing.suspended) suspendedFrames++;
		}
		if (LOG_LATENCY && System.nanoTime() - lastLatencyLog > LATENCY_LOG_INTERVAL_NS) {
			lastLatencyLog = System.nanoTime();
			ReflexiumClient.LOGGER.info("{} | {}", statusLine(), latencyLine());
		}
		marker(VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
		marker(VK_LATENCY_MARKER_SIMULATION_START_NV);
		simulationOpen = true;
	}

	public void endSimulation() {
		simulationOpen = false;
		marker(VK_LATENCY_MARKER_SIMULATION_END_NV);
	}

	public void beginRenderSubmit() {
		marker(VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
	}

	public void beforePresent() {
		marker(VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
		marker(VK_LATENCY_MARKER_PRESENT_START_NV);
	}

	public void afterPresent() {
		marker(VK_LATENCY_MARKER_PRESENT_END_NV);
		swapchainPresented = swapchain != 0;
		frameOpen = false;
	}

	public void beforeBatchPresent() {
		marker(VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV);
	}
	public void afterBatchPresent() {
		marker(VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV);
	}
	public void beginReplacementSubmit() { marker(VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_START_NV); }
	public void endReplacementSubmit() { marker(VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_END_NV); }
	public void triggerFlash() { if (simulationOpen) marker(VK_LATENCY_MARKER_TRIGGER_FLASH_NV); }

	/**
	 * Called every frame with vanilla's effective Max Framerate (menu and AFK throttles included).
	 * Reflex enforces that cap itself, so vanilla's post-present sleep can be skipped while Reflex owns
	 * it. No cap is inferred from VSync or the refresh rate. Frame generation still needs the display's
	 * output budget to choose a multiplier, and that budget is passed to the policy only.
	 */
	public void updateFrameLimit(int fpsLimit, Window window) {
		int fps = fpsLimit > 0 && fpsLimit < UNLIMITED_FPS ? fpsLimit : NO_CAP;
		baseFpsLimit = fps;
		int multiplier = FrameGenerationRenderer.get().refreshPolicy().multiplier();
		int userInterval = presentedIntervalUs(fps, multiplier);
		int fgInterval = presentedIntervalUs(mode().enabled() ? frameGenerationCap(window) : NO_CAP, 1);
		requestedIntervalUs = Math.max(userInterval, fgInterval);
		requestedCapIsFrameGeneration = fgInterval > userInterval;
	}

	/** Present interval in microseconds. minimumIntervalUs is defined between vkQueuePresentKHR calls. */
	static int presentedIntervalUs(int fps, int multiplier) {
		if (fps == NO_CAP) return 0;
		long presented = Math.min(NO_CAP, (long) fps * Math.max(1, multiplier));
		return (int) ((1_000_000L + presented - 1) / presented);
	}

	/**
	 * Presented-frame ceiling for frame generation. Only Auto targets the refresh rate: its multiplier is
	 * chosen from the measured real rate against that budget. A fixed multiplier is an explicit request to
	 * present real x N, so throttling real frames to refresh / N would reduce the real rate the user asked
	 * to keep; the driver still caps just under refresh itself when VSync or VRR asks for it.
	 */
	private int frameGenerationCap(Window window) {
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		boolean requested = fg.wantsMotion() && fg.requestedGeneratedCount() > 0;
		boolean auto = requested && FrameGenerationRefreshPolicy.targetsRefresh(ReflexiumConfig.get().frameGenerationMultiplier());
		fg.refreshPolicy().cap(auto ? refreshHeadroom(window) : NO_CAP);
		return auto && !fg.refreshPolicy().bypass() ? fg.refreshPolicy().cap() : NO_CAP;
	}

	/** Frame generation output budget: the refresh rate minus headroom for presentation jitter. */
	private int refreshHeadroom(Window window) {
		float hz = refreshRate(window);
		return hz <= 0 ? NO_CAP : Math.max(1, (int) Math.floor(hz - hz * hz / 3600f));
	}

	/**
	 * Refresh rate of the monitor the window is on, from vanilla's active video mode: the display's current
	 * mode in windowed and borderless modes, the exclusive mode when one is set. Read at most once per second,
	 * so a window move, resize or monitor switch takes effect within a second without a per-frame SDL query.
	 */
	private float refreshRate(Window window) {
		long now = System.nanoTime();
		if (now - lastRefreshRead > REFRESH_RATE_INTERVAL_NS) {
			lastRefreshRead = now;
			VideoMode video = window.getActiveVideoMode();
			refreshRateHz = video == null ? 0 : video.getRefreshRate();
		}
		return refreshRateHz;
	}

	/** True when vanilla's post-present sleep should be skipped because Reflex enforces the same cap. */
	public boolean ownsFrameLimit() {
		return isSleeping() && pacing.ownsCap(mode(), requestedIntervalUs);
	}

	// ---------------------------------------------------------------------------------------------
	// Debug

	public String statusLine() {
		ReflexMode mode = mode();
		if (!ReflexiumClient.isSupportedPlatform()) {
			return "Reflex: Windows only";
		}
		VulkanSession session = VulkanSession.current();
		if (session == null) {
			return "Reflex: " + mode.displayName() + " (needs the Vulkan renderer)";
		}
		if (!streamline || !StreamlineSupport.reflexAvailable()) {
			return "Reflex: unavailable (Streamline Reflex is not active)";
		}
		if (FsrSwapchain.active()) return "Reflex: unavailable (FSR presentation scheduler)";
		StringBuilder line = new StringBuilder("Reflex: ").append(pacing.configured ? pacing.applied.displayName() : "markers only")
				.append(" (requested ").append(ReflexiumConfig.get().reflexMode().displayName()).append(")");
		line.append(" (Streamline)");
		boolean[] flags = StreamlineSupport.reflexFlags();
		if (flags != null) line.append(", latency report ").append(flags[1] ? "ready" : "unavailable");
		if (pacing.failed) {
			line.append(" (pacing failed: ").append(pacing.lastResult).append(", native limiter)");
		} else if (pacing.suspended) {
			line.append(" (sleep signal pending, native limiter)");
		} else if (pacing.interval > 0) {
			line.append(", cap ").append(Math.floor(1_000_000.0 / pacing.interval)).append(" FPS");
			if (requestedCapIsFrameGeneration) line.append(" (frame generation budget)");
		}
		if (!presentIdEnabled) {
			line.append(", no present id");
		}
		return line.toString();
	}

	public String latencyLine() {
		if (!isAvailable()) return LatencyReport.unavailable("no native swapchain").format();
		return currentLatency().format();
	}

	// ---------------------------------------------------------------------------------------------
	// Streamline

	private void applySleepMode() {
		if (!streamline) {
			return;
		}
		ReflexMode mode = transitionFrames > 0 ? ReflexMode.OFF : mode();
		int multiplier = FrameGenerationRenderer.get().refreshPolicy().multiplier();
		int baseInterval = DebugSettings.reflexIntervalUs;
		int interval = mode.enabled() ? Math.max(requestedIntervalUs, baseInterval > 0 ? (baseInterval + multiplier - 1) / multiplier : 0) : 0;
		if (!pacing.shouldApply(mode, interval)) return;
		int result = StreamlineSupport.setReflexMode(streamlineMode(mode), interval);
		pacing.configuration(mode, interval, result);
		resetTiming();
		if (result != 0) {
			ReflexiumClient.LOGGER.warn("slReflexSetOptions failed ({})", result);
		} else if (mode != loggedMode) {
			loggedMode = mode;
			ReflexiumClient.LOGGER.info("Reflex mode: {} (Streamline)", mode.displayName());
		}
	}

	private void resetTiming() {
		timingFloorId = frameId;
		timingSeenId = timingSeenAt = lastTimingRefresh = 0;
		latency = LatencyReport.unavailable("waiting for current frame data");
	}

	private void marker(int marker) {
		if (!frameOpen || !swapchainPresented || !isAvailable() || markerSuppressFrames > 0) {
			return;
		}
		int rank = markerRank(marker);
		if (rank > 0) {
			if (lastMarkerRank > 0 && rank <= lastMarkerRank && lastMarkerFrameId == frameId) {
				markerOrderRegressions++;
				if (markerOrderRegressions <= 8) ReflexiumClient.LOGGER.warn("Reflex marker order regression on frame {}: {} emitted after {}",
						frameId, markerName(marker), markerName(lastMarker));
			}
			lastMarkerRank = rank;
			lastMarker = marker;
			lastMarkerFrameId = frameId;
		}
		int pcl = pclMarker(marker);
		if (pcl >= 0) {
			if (FrameTrace.enabled(frameId)) FrameTrace.event(frameId, "marker=" + markerName(marker));
			StreamlineSupport.pclMarker(pcl);
		}
	}

	private static int streamlineMode(ReflexMode mode) {
		return switch (mode) {
			case OFF -> 0;
			case ON -> 1;
			case ON_BOOST -> 2;
		};
	}

	/** VK_NV_low_latency2 marker to Streamline PCL marker. -1 means PCL has no equivalent. */
	private static int pclMarker(int marker) {
		return switch (marker) {
			// PCL eInputSample (6) is deprecated in 2.14.16 and the Vulkan LL2 backend rejects it
			// ("marker 6 is unknown"), so it is not emitted. eControllerInputSample (13) has no
			// Vulkan equivalent either.
			case VK_LATENCY_MARKER_INPUT_SAMPLE_NV -> -1;
			case VK_LATENCY_MARKER_SIMULATION_START_NV -> 0;
			case VK_LATENCY_MARKER_SIMULATION_END_NV -> 1;
			case VK_LATENCY_MARKER_RENDERSUBMIT_START_NV -> 2;
			case VK_LATENCY_MARKER_RENDERSUBMIT_END_NV -> 3;
			case VK_LATENCY_MARKER_PRESENT_START_NV -> 4;
			case VK_LATENCY_MARKER_PRESENT_END_NV -> 5;
			case VK_LATENCY_MARKER_TRIGGER_FLASH_NV -> 7;
			case VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_START_NV -> 9;
			case VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_END_NV -> 10;
			case VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV -> 11;
			case VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV -> 12;
			default -> -1;
		};
	}

	public static String markerName(int marker) {
		return switch (marker) {
			case VK_LATENCY_MARKER_SIMULATION_START_NV -> "SIMULATION_START";
			case VK_LATENCY_MARKER_SIMULATION_END_NV -> "SIMULATION_END";
			case VK_LATENCY_MARKER_RENDERSUBMIT_START_NV -> "RENDERSUBMIT_START";
			case VK_LATENCY_MARKER_RENDERSUBMIT_END_NV -> "RENDERSUBMIT_END";
			case VK_LATENCY_MARKER_PRESENT_START_NV -> "PRESENT_START";
			case VK_LATENCY_MARKER_PRESENT_END_NV -> "PRESENT_END";
			case VK_LATENCY_MARKER_INPUT_SAMPLE_NV -> "INPUT_SAMPLE";
			case VK_LATENCY_MARKER_TRIGGER_FLASH_NV -> "TRIGGER_FLASH";
			case VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_START_NV -> "OUT_OF_BAND_RENDERSUBMIT_START";
			case VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_END_NV -> "OUT_OF_BAND_RENDERSUBMIT_END";
			case VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV -> "OUT_OF_BAND_PRESENT_START";
			case VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV -> "OUT_OF_BAND_PRESENT_END";
			default -> "MARKER_" + marker;
		};
	}

	private static int markerRank(int marker) {
		return switch (marker) {
			case VK_LATENCY_MARKER_INPUT_SAMPLE_NV -> 1;
			case VK_LATENCY_MARKER_SIMULATION_START_NV -> 2;
			case VK_LATENCY_MARKER_SIMULATION_END_NV -> 3;
			case VK_LATENCY_MARKER_RENDERSUBMIT_START_NV -> 4;
			case VK_LATENCY_MARKER_RENDERSUBMIT_END_NV -> 5;
			case VK_LATENCY_MARKER_PRESENT_START_NV -> 6;
			case VK_LATENCY_MARKER_PRESENT_END_NV -> 7;
			default -> 0;
		};
	}

	/** Debug harness: complete driver reports, in microseconds relative to the first returned simulation start. */
	public JsonObject timingReports() {
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		JsonArray frames = new JsonArray();
		result.add("frames", frames);
		if (!isAvailable()) return result;
		StreamlineBridge.ReflexReport report = StreamlineSupport.reflexReport();
		if (report == null) return result;
		String[] names = {"input", "sim_start", "sim_end", "submit_start", "submit_end", "present_start",
				"present_end", "driver_start", "driver_end", "os_queue_start", "os_queue_end", "gpu_start", "gpu_end"};
		long origin = report.times()[1] > 0 ? report.times()[1] : report.times()[0];
		JsonObject f = new JsonObject();
		f.addProperty("id", report.presentId());
		for (int i = 0; i < names.length && i < report.times().length; i++) {
			f.addProperty(names[i], report.times()[i] - origin);
		}
		frames.add(f);
		return result;
	}

	/** GPU throughput bound, constrained by known real-frame limits, with unavailable data holding selection. */
	public double frameGenerationRenderCostMs(double fgCostMs) {
		if (!isAvailable() || !ReflexiumConfig.get().frameGeneration().nvidia()) return 0;
		LatencyReport latency = currentLatency();
		if (!latency.available()) return 0;
		double limitedMs = Math.max(baseFpsLimit == NO_CAP ? 0 : 1000.0 / baseFpsLimit,
				DebugSettings.reflexIntervalUs / 1000.0);
		return Math.max(latency.gpuMs(), limitedMs + fgCostMs);
	}

	/** The cached report, re-read from Streamline at most every {@link #TIMING_REFRESH_NS}. */
	private LatencyReport currentLatency() {
		long now = System.nanoTime();
		if (now - lastTimingRefresh > TIMING_REFRESH_NS) {
			lastTimingRefresh = now;
			latency = readLatency();
		}
		return latency;
	}

	private LatencyReport readLatency() {
		StreamlineBridge.ReflexReport report = StreamlineSupport.reflexReport();
		if (report == null) return LatencyReport.unavailable("waiting for Streamline data");
		long now = System.nanoTime();
		if (report.presentId() != timingSeenId) {
			timingSeenId = report.presentId();
			timingSeenAt = now;
		}
		if (timingSeenId <= timingFloorId || timingSeenId > frameId) {
			return LatencyReport.unavailable("waiting for driver data");
		}
		if (timingSeenAt != 0 && now - timingSeenAt > 1_000_000_000L) {
			return LatencyReport.unavailable("stale driver data");
		}
		return LatencyReport.fromStreamline(report.times());
	}
}
