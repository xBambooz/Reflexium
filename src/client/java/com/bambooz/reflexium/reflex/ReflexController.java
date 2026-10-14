package com.bambooz.reflexium.reflex;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGetLatencyMarkerInfoNV;
import org.lwjgl.vulkan.VkLatencySleepInfoNV;
import org.lwjgl.vulkan.VkLatencySleepModeInfoNV;
import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSetLatencyMarkerInfoNV;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.NVLowLatency2.*;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK12.vkWaitSemaphores;

/**
 * NVIDIA Reflex through VK_NV_low_latency2. All methods except {@link #currentFrameId()} run on the
 * render thread.
 * <p>
 * Per frame (hooks in MinecraftMixin):
 * <pre>
 *   run():       latency sleep, SIMULATION_START,
 *                INPUT_SAMPLE                     (before SDL event polling, so input is sampled late)
 *   runTick():   SIMULATION_END                    (before renderFrame)
 *   renderFrame: RENDERSUBMIT_START                (before render-state extraction)
 *                RENDERSUBMIT_END, PRESENT_START   (before present)
 *                PRESENT_END                       (after present)
 * </pre>
 * The same frame id is attached to every queue submission (VkLatencySubmissionPresentIdNV) and to the
 * present itself (VkPresentIdKHR), which is how the driver ties GPU work to the markers.
 */
public final class ReflexController {
	private static final ReflexController INSTANCE = new ReflexController();
	private static final long SLEEP_TIMEOUT_NS = 100_000_000L;
	private static final long TIMING_REFRESH_NS = 250_000_000L;
	private static final long REFRESH_RATE_INTERVAL_NS = 1_000_000_000L;
	/** Minecraft treats 260 as "unlimited" for Max Framerate. */
	private static final int UNLIMITED_FPS = 260;
	private static final int NO_CAP = Integer.MAX_VALUE;
	/** -Dreflexium.logLatency=true writes the F3 Reflex lines to the log every few seconds. */
	private static final boolean LOG_LATENCY = Boolean.getBoolean("reflexium.logLatency");
	private static final long LATENCY_LOG_INTERVAL_NS = 5_000_000_000L;
	private long lastLatencyLog;

	private VkDevice device;
	private boolean presentIdEnabled;
	private long swapchain;
	private long sleepSemaphore;
	private long sleepValue;

	private volatile long frameId;
	private long lastPresentedId;
	private volatile boolean frameOpen;
	private boolean batchedPresent;

	private ReflexMode appliedMode;
	private ReflexMode loggedMode;
	private int appliedIntervalUs = -1;
	private int requestedIntervalUs;
	private boolean requestedCapIsVrr;
	private boolean sleepFailed;

	private long lastTimingRefresh;
	private long lastRefreshRead;
	private float refreshRateHz;
	private LatencyReport latency = LatencyReport.unavailable("no data yet");

	public static ReflexController get() {
		return INSTANCE;
	}

	// ---------------------------------------------------------------------------------------------
	// Lifecycle

	public void onDeviceCreated(VulkanSession session) {
		shutdown();
		if (!session.lowLatency2()) {
			return;
		}
		device = session.device();
		presentIdEnabled = session.presentId();
		sleepSemaphore = createTimelineSemaphore(device);
	}

	public void onSwapchainCreated(long newSwapchain) {
		if (device == null) {
			return;
		}
		swapchain = newSwapchain;
		appliedMode = null;
		appliedIntervalUs = -1;
		applySleepMode();
	}

	public void onSwapchainDestroyed() {
		swapchain = 0;
		frameOpen = false;
	}

	public void shutdown() {
		if (device != null && sleepSemaphore != 0) {
			vkDestroySemaphore(device, sleepSemaphore, null);
		}
		device = null;
		swapchain = 0;
		sleepSemaphore = 0;
		sleepValue = 0;
		presentIdEnabled = false;
		frameOpen = false;
		appliedMode = null;
		latency = LatencyReport.unavailable("no data yet");
	}

	// ---------------------------------------------------------------------------------------------
	// State

	/** VK_NV_low_latency2 is enabled on the device and a swapchain exists. */
	public boolean isAvailable() {
		return device != null && swapchain != 0;
	}

	public boolean isSleeping() {
		return isAvailable() && mode().enabled() && sleepSemaphore != 0 && !sleepFailed;
	}

	public ReflexMode mode() {
		ReflexMode requested = ReflexiumConfig.get().reflexMode();
		return requested == ReflexMode.OFF && com.bambooz.reflexium.dlss.FrameGenerationRenderer.get().needsReflex() ? ReflexMode.ON : requested;
	}

	public void setMode(ReflexMode mode) {
		ReflexiumConfig.get().setReflexMode(mode);
		sleepFailed = false;
		applySleepMode();
	}

	/** Frame id for queue submissions, safe to read from any thread. 0 means "not tagged". */
	public long currentFrameId() {
		return frameOpen ? frameId : 0;
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

	public void beginFrame() {
		if (!isAvailable()) {
			return;
		}
		frameId++;
		frameOpen = true;
		applySleepMode();
		if (isSleeping()) {
			long timing = com.bambooz.reflexium.debug.FrameProfiler.start(com.bambooz.reflexium.debug.FrameProfiler.REFLEX_SLEEP, null);
			sleep();
			com.bambooz.reflexium.debug.FrameProfiler.end(com.bambooz.reflexium.debug.FrameProfiler.REFLEX_SLEEP, timing, null);
		}
		marker(VK_LATENCY_MARKER_SIMULATION_START_NV);
		if (LOG_LATENCY && System.nanoTime() - lastLatencyLog > LATENCY_LOG_INTERVAL_NS) {
			lastLatencyLog = System.nanoTime();
			ReflexiumClient.LOGGER.info("{} | {}", statusLine(), latencyLine());
		}
		marker(VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
	}

	public void endSimulation() {
		marker(VK_LATENCY_MARKER_SIMULATION_END_NV);
	}

	public void beginRenderSubmit() {
		marker(VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
	}

	public void beforePresent() {
		marker(VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
		batchedPresent = com.bambooz.reflexium.dlss.FrameGenerationRenderer.get().generatedCount() > 0
				&& !com.bambooz.reflexium.dlss.FsrSwapchain.active();
		if (!batchedPresent) marker(VK_LATENCY_MARKER_PRESENT_START_NV);
	}

	public void afterPresent() {
		if (!batchedPresent) marker(VK_LATENCY_MARKER_PRESENT_END_NV);
		batchedPresent = false;
	}

	public void beforeBatchPresent(boolean generated) {
		marker(generated ? VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV : VK_LATENCY_MARKER_PRESENT_START_NV);
	}
	public void afterBatchPresent(boolean generated) {
		marker(generated ? VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV : VK_LATENCY_MARKER_PRESENT_END_NV);
	}

	/**
	 * Called every frame with vanilla's effective FPS cap (menu and AFK throttles included). Reflex paces
	 * frames to the lower of that cap and the automatic VRR cap, applied as the driver's minimum frame
	 * interval at the next sleep.
	 */
	public void updateFrameLimit(int fpsLimit, boolean vsync, Window window) {
		int userFps = fpsLimit > 0 && fpsLimit < UNLIMITED_FPS ? fpsLimit : NO_CAP;
		// VSync is a proxy for refresh headroom; this does not detect actual G-Sync engagement.
		int vrrFps = vsync && mode().enabled() ? vrrCap(window) : NO_CAP;
		int fps = Math.min(userFps, vrrFps);
		requestedIntervalUs = fps == NO_CAP ? 0 : 1_000_000 / fps;
		requestedCapIsVrr = vrrFps < userFps;
	}

	/** Refresh-headroom heuristic. Unknown refresh rate means no automatic cap. */
	private int vrrCap(Window window) {
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

	/** True when vanilla's post-present sleep should be skipped because the Reflex sleep paces frames. */
	public boolean ownsFrameLimit() {
		return requestedIntervalUs > 0 && isSleeping();
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
		if (!session.lowLatency2()) {
			return "Reflex: unavailable (" + (session.isNvidia() ? "driver lacks VK_NV_low_latency2" : "not an NVIDIA GPU") + ")";
		}
		if (com.bambooz.reflexium.dlss.FsrSwapchain.active()) return "Reflex: unavailable (FSR presentation scheduler)";
		StringBuilder line = new StringBuilder("Reflex: ").append(mode.displayName());
		if (sleepFailed) {
			line.append(" (sleep failed, markers only)");
		} else if (appliedIntervalUs > 0) {
			line.append(", cap ").append(Math.round(1_000_000.0 / appliedIntervalUs)).append(" FPS");
			if (requestedCapIsVrr) {
				line.append(" (VSync)");
			}
		}
		if (!presentIdEnabled) {
			line.append(", no present id");
		}
		return line.toString();
	}

	public String latencyLine() {
		long now = System.nanoTime();
		if (isAvailable() && now - lastTimingRefresh > TIMING_REFRESH_NS) {
			lastTimingRefresh = now;
			latency = readLatency();
		}
		return latency.format();
	}

	// ---------------------------------------------------------------------------------------------
	// Vulkan

	private void applySleepMode() {
		if (!isAvailable()) {
			return;
		}
		ReflexMode mode = mode();
		int interval = mode.enabled() ? requestedIntervalUs : 0;
		if (mode == appliedMode && interval == appliedIntervalUs) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencySleepModeInfoNV info = VkLatencySleepModeInfoNV.calloc(stack)
					.sType$Default()
					.lowLatencyMode(mode.enabled())
					.lowLatencyBoost(mode == ReflexMode.ON_BOOST)
					.minimumIntervalUs(interval);
			int result = vkSetLatencySleepModeNV(device, swapchain, info);
			if (result != VK_SUCCESS) {
				ReflexiumClient.LOGGER.warn("vkSetLatencySleepModeNV failed ({})", result);
			} else if (mode != loggedMode) {
				loggedMode = mode;
				ReflexiumClient.LOGGER.info("Reflex mode: {}", mode.displayName());
			}
		}
		appliedMode = mode;
		appliedIntervalUs = interval;
	}

	private void sleep() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long value = ++sleepValue;
			VkLatencySleepInfoNV info = VkLatencySleepInfoNV.calloc(stack)
					.sType$Default()
					.signalSemaphore(sleepSemaphore)
					.value(value);
			long timing = com.bambooz.reflexium.debug.FrameProfiler.start(com.bambooz.reflexium.debug.FrameProfiler.REFLEX_DRIVER, null);
			int result = vkLatencySleepNV(device, swapchain, info);
			com.bambooz.reflexium.debug.FrameProfiler.end(com.bambooz.reflexium.debug.FrameProfiler.REFLEX_DRIVER, timing, null);
			if (result != VK_SUCCESS) {
				failSleep("vkLatencySleepNV", result);
				return;
			}
			VkSemaphoreWaitInfo wait = VkSemaphoreWaitInfo.calloc(stack)
					.sType$Default()
					.semaphoreCount(1)
					.pSemaphores(stack.longs(sleepSemaphore))
					.pValues(stack.longs(value));
			timing = com.bambooz.reflexium.debug.FrameProfiler.start(com.bambooz.reflexium.debug.FrameProfiler.REFLEX_WAIT, null);
			result = vkWaitSemaphores(device, wait, SLEEP_TIMEOUT_NS);
			com.bambooz.reflexium.debug.FrameProfiler.end(com.bambooz.reflexium.debug.FrameProfiler.REFLEX_WAIT, timing, null);
			if (result != VK_SUCCESS && result != org.lwjgl.vulkan.VK12.VK_TIMEOUT) {
				failSleep("vkWaitSemaphores", result);
			}
		}
	}

	private void failSleep(String call, int result) {
		// A broken sleep must never stall the game; keep markers and drop to un-paced frames.
		sleepFailed = true;
		ReflexiumClient.LOGGER.warn("{} failed ({}); Reflex sleep disabled until the mode is changed", call, result);
	}

	private void marker(int marker) {
		if (!frameOpen || !isAvailable()) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkSetLatencyMarkerNV(device, swapchain, VkSetLatencyMarkerInfoNV.calloc(stack)
					.sType$Default()
					.presentID(frameId)
					.marker(marker));
		}
	}

	private LatencyReport readLatency() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencyTimingsFrameReportNV.Buffer reports = VkLatencyTimingsFrameReportNV.calloc(64, stack);
			for (int i = 0; i < reports.capacity(); i++) {
				reports.get(i).sType$Default();
			}
			VkGetLatencyMarkerInfoNV info = VkGetLatencyMarkerInfoNV.calloc(stack)
					.sType$Default()
					.timingCount(reports.capacity())
					.pTimings(reports);
			vkGetLatencyTimingsNV(device, swapchain, info);

			VkLatencyTimingsFrameReportNV latest = null;
			for (int i = 0; i < info.timingCount(); i++) {
				VkLatencyTimingsFrameReportNV report = reports.get(i);
				if (report.presentID() > 0 && report.presentEndTimeUs() > 0
						&& (latest == null || report.presentID() > latest.presentID())) {
					latest = report;
				}
			}
			if (latest == null) {
				return LatencyReport.unavailable("waiting for driver data");
			}
			long start = latest.inputSampleTimeUs() > 0 ? latest.inputSampleTimeUs() : latest.simStartTimeUs();
			if (start <= 0 || latest.presentEndTimeUs() <= start) {
				return LatencyReport.unavailable("incomplete driver data");
			}
			return new LatencyReport(true, "",
					(latest.presentEndTimeUs() - start) / 1000.0,
					span(latest.simStartTimeUs(), latest.simEndTimeUs()),
					span(latest.renderSubmitStartTimeUs(), latest.renderSubmitEndTimeUs()),
					span(latest.gpuRenderStartTimeUs(), latest.gpuRenderEndTimeUs()));
		} catch (RuntimeException e) {
			return LatencyReport.unavailable("timing query failed");
		}
	}

	private static double span(long start, long end) {
		return start > 0 && end > start ? (end - start) / 1000.0 : -1;
	}

	private static long createTimelineSemaphore(VkDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack)
					.sType$Default()
					.semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
					.initialValue(0);
			VkSemaphoreCreateInfo create = VkSemaphoreCreateInfo.calloc(stack)
					.sType$Default()
					.pNext(type.address());
			LongBuffer handle = stack.mallocLong(1);
			int result = vkCreateSemaphore(device, create, null, handle);
			if (result != VK_SUCCESS) {
				ReflexiumClient.LOGGER.warn("Could not create the Reflex sleep semaphore ({}); markers only", result);
				return 0;
			}
			return handle.get(0);
		}
	}

	record LatencyReport(boolean available, String reason, double totalMs, double simMs, double submitMs, double gpuMs) {
		static LatencyReport unavailable(String reason) {
			return new LatencyReport(false, reason, 0, -1, -1, -1);
		}

		String format() {
			if (!available) {
				return "Reflex latency: " + reason;
			}
			StringBuilder line = new StringBuilder(String.format(java.util.Locale.ROOT, "Reflex latency: %.1f ms", totalMs));
			append(line, "sim", simMs);
			append(line, "submit", submitMs);
			append(line, "GPU", gpuMs);
			return line.toString();
		}

		private static void append(StringBuilder line, String label, double ms) {
			if (ms >= 0) {
				line.append(String.format(java.util.Locale.ROOT, ", %s %.1f", label, ms));
			}
		}
	}
}
