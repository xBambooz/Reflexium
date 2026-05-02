package com.nvidiareflex.client;

import com.nvidiareflex.NvidiaReflexMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.vulkan.NVLowLatency2.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Manages Nvidia Reflex via the VK_NV_low_latency2 Vulkan extension.
 *
 * Frame flow (called from MinecraftMixin):
 *   onFrameStart()          → vkLatencySleepNV (driver-guided CPU sleep)
 *   onSimulationStart()     → SIMULATION_START marker
 *   onSimulationEnd()       → SIMULATION_END marker
 *   onRenderSubmitStart()   → RENDERSUBMIT_START marker
 *   onRenderSubmitEnd()     → RENDERSUBMIT_END marker
 *   onPresentStart()        → PRESENT_START marker
 *   onPresentEnd()          → PRESENT_END marker
 */
public class ReflexManager {

	private VkDevice device;
	private long swapchain;
	private long sleepSemaphore;        // Timeline semaphore used by vkLatencySleepNV
	private long sleepSemaphoreValue;   // Current timeline value
	private boolean initialized = false;
	private boolean extensionEnabled = false;
	private ReflexMode currentMode = ReflexMode.OFF;
	private volatile LatencySnapshot latencySnapshot = LatencySnapshot.unavailable("no timing data");

	private final AtomicLong frameId = new AtomicLong(0);

	private static final ReflexManager INSTANCE = new ReflexManager();

	public static ReflexManager getInstance() {
		return INSTANCE;
	}

	public void initialize(VkDevice device, long swapchain, boolean extensionEnabled) {
		this.device = device;
		this.swapchain = swapchain;
		this.extensionEnabled = extensionEnabled;
		this.sleepSemaphoreValue = 0;

		if (extensionEnabled) {
			createSleepSemaphore();
		}

		this.initialized = true;
		NvidiaReflexMod.LOGGER.info("ReflexManager initialized (extension: {})", extensionEnabled);

		ReflexMode savedMode = NvidiaReflexModClient.getConfig().getMode();
		if (savedMode != ReflexMode.OFF) {
			setMode(savedMode);
		}
	}

	private void createSleepSemaphore() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Create a timeline semaphore for vkLatencySleepNV signaling
			VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
					.sType$Default()
					.semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
					.initialValue(0);

			VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
					.sType$Default()
					.pNext(typeInfo.address());

			LongBuffer pSemaphore = stack.callocLong(1);
			int result = vkCreateSemaphore(device, createInfo, null, pSemaphore);
			if (result == VK_SUCCESS) {
				sleepSemaphore = pSemaphore.get(0);
				NvidiaReflexMod.LOGGER.info("Reflex sleep semaphore created");
			} else {
				NvidiaReflexMod.LOGGER.warn("Failed to create Reflex sleep semaphore: {}", result);
				sleepSemaphore = 0;
			}
		}
	}

	public void updateSwapchain(long newSwapchain) {
		this.swapchain = newSwapchain;
		if (currentMode != ReflexMode.OFF) {
			applyMode(currentMode);
		}
	}

	public void setMode(ReflexMode mode) {
		this.currentMode = mode;
		NvidiaReflexModClient.getConfig().setMode(mode);
		if (initialized && extensionEnabled) {
			applyMode(mode);
		}
	}

	public ReflexMode getCurrentMode() {
		return currentMode;
	}

	public boolean isAvailable() {
		return initialized && extensionEnabled;
	}

	public boolean isActive() {
		return initialized && extensionEnabled && currentMode != ReflexMode.OFF
				&& device != null && swapchain != 0;
	}

	public String getDebugStateLine() {
		if (!NvidiaReflexModClient.isModEnabled()) {
			return "Nvidia Reflex: disabled";
		}
		if (!initialized) {
			return "Nvidia Reflex: " + currentMode.getDisplayName() + " (not initialized)";
		}
		if (!extensionEnabled) {
			return "Nvidia Reflex: " + currentMode.getDisplayName() + " (VK_NV_low_latency2 unavailable)";
		}
		return "Nvidia Reflex: " + currentMode.getDisplayName() + (isActive() ? " (active)" : " (inactive)");
	}

	public String getDebugLatencyLine() {
		refreshLatencyTimings();
		return latencySnapshot.formatLine();
	}

	// -------------------------------------------------------------------------
	// Frame lifecycle — called from MinecraftMixin
	// -------------------------------------------------------------------------

	/**
	 * Called at the very start of runTick(), before simulation.
	 * Issues the driver-guided sleep to align CPU timing with GPU needs.
	 */
	public void onFrameStart() {
		if (!isActive()) return;

		long id = frameId.incrementAndGet();
		sleepSemaphoreValue = id;
		setMarker(VK_LATENCY_MARKER_INPUT_SAMPLE_NV);

		if (sleepSemaphore == 0) return;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencySleepInfoNV sleepInfo = VkLatencySleepInfoNV.calloc(stack)
					.sType$Default()
					.pNext(0)
					.signalSemaphore(sleepSemaphore)
					.value(sleepSemaphoreValue);

			int result = vkLatencySleepNV(device, swapchain, sleepInfo);
			if (result != VK_SUCCESS) {
				return;
			}

			// Wait on CPU for the driver to signal when simulation should start
			VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
					.sType$Default()
					.pNext(0)
					.flags(0)
					.semaphoreCount(1)
					.pSemaphores(stack.longs(sleepSemaphore))
					.pValues(stack.longs(sleepSemaphoreValue));

			// 100ms timeout — don't block forever if something goes wrong
			int waitResult = vkWaitSemaphores(device, waitInfo, 100_000_000L);
			if (waitResult != VK_SUCCESS && waitResult != VK_TIMEOUT) {
				NvidiaReflexMod.LOGGER.warn("vkWaitSemaphores failed: {}", waitResult);
			}
		} catch (Exception e) {
			NvidiaReflexMod.LOGGER.error("Reflex sleep failed", e);
		}
	}

	/** Called just before game tick/simulation begins. */
	public void onSimulationStart() {
		if (!isActive()) return;
		setMarker(VK_LATENCY_MARKER_SIMULATION_START_NV);
	}

	/** Called just after game tick/simulation ends. */
	public void onSimulationEnd() {
		if (!isActive()) return;
		setMarker(VK_LATENCY_MARKER_SIMULATION_END_NV);
	}

	/** Called just before the render command buffers are submitted. */
	public void onRenderSubmitStart() {
		if (!isActive()) return;
		setMarker(VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
	}

	/** Called just after render submission completes. */
	public void onRenderSubmitEnd() {
		if (!isActive()) return;
		setMarker(VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
	}

	/** Called just before vkQueuePresentKHR. */
	public void onPresentStart() {
		if (!isActive()) return;
		setMarker(VK_LATENCY_MARKER_PRESENT_START_NV);
	}

	/** Called just after vkQueuePresentKHR. */
	public void onPresentEnd() {
		if (!isActive()) return;
		setMarker(VK_LATENCY_MARKER_PRESENT_END_NV);
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	private void applyMode(ReflexMode mode) {
		if (device == null || swapchain == 0) return;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencySleepModeInfoNV sleepModeInfo = VkLatencySleepModeInfoNV.calloc(stack)
					.sType$Default()
					.pNext(0)
					.lowLatencyMode(mode != ReflexMode.OFF)
					.lowLatencyBoost(mode == ReflexMode.ON_BOOST)
					.minimumIntervalUs(0);

			int result = vkSetLatencySleepModeNV(device, swapchain, sleepModeInfo);
			if (result == VK_SUCCESS) {
				NvidiaReflexMod.LOGGER.info("Nvidia Reflex set to: {}", mode.getDisplayName());
			} else {
				NvidiaReflexMod.LOGGER.warn("vkSetLatencySleepModeNV failed: {}", result);
			}
		}
	}

	private void setMarker(int markerType) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSetLatencyMarkerInfoNV markerInfo = VkSetLatencyMarkerInfoNV.calloc(stack)
					.sType$Default()
					.pNext(0)
					.presentID(frameId.get())
					.marker(markerType);

			vkSetLatencyMarkerNV(device, swapchain, markerInfo);
		} catch (Exception e) {
			NvidiaReflexMod.LOGGER.error("Failed to set latency marker {}", markerType, e);
		}
	}

	private void refreshLatencyTimings() {
		if (!isActive()) {
			latencySnapshot = LatencySnapshot.unavailable("inactive");
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencyTimingsFrameReportNV.Buffer timings = VkLatencyTimingsFrameReportNV.calloc(64, stack);
			VkGetLatencyMarkerInfoNV markerInfo = VkGetLatencyMarkerInfoNV.calloc(stack)
					.sType$Default()
					.pNext(0)
					.timingCount(timings.capacity())
					.pTimings(timings);

			vkGetLatencyTimingsNV(device, swapchain, markerInfo);

			VkLatencyTimingsFrameReportNV latest = null;
			for (int i = 0; i < timings.capacity(); i++) {
				VkLatencyTimingsFrameReportNV timing = timings.get(i);
				if (timing.presentID() <= 0 || timing.presentEndTimeUs() <= 0) {
					continue;
				}
				if (latest == null || timing.presentID() > latest.presentID()) {
					latest = timing;
				}
			}

			if (latest == null) {
				latencySnapshot = LatencySnapshot.unavailable("waiting for driver data");
				return;
			}

			long startUs = latest.inputSampleTimeUs() > 0 ? latest.inputSampleTimeUs() : latest.simStartTimeUs();
			long endUs = latest.presentEndTimeUs();
			long renderStartUs = latest.renderSubmitStartTimeUs();
			long renderEndUs = latest.renderSubmitEndTimeUs();
			long gpuStartUs = latest.gpuRenderStartTimeUs();
			long gpuEndUs = latest.gpuRenderEndTimeUs();
			if (startUs <= 0 || endUs <= startUs) {
				latencySnapshot = LatencySnapshot.unavailable("incomplete driver data");
				return;
			}

			latencySnapshot = LatencySnapshot.available(
					latest.presentID(),
					(endUs - startUs) / 1000.0,
					durationMs(renderStartUs, renderEndUs),
					durationMs(gpuStartUs, gpuEndUs)
			);
		} catch (Exception e) {
			latencySnapshot = LatencySnapshot.unavailable("vkGetLatencyTimingsNV failed");
			NvidiaReflexMod.LOGGER.warn("Failed to read Reflex latency timings", e);
		}
	}

	private static double durationMs(long startUs, long endUs) {
		if (startUs <= 0 || endUs <= startUs) {
			return -1.0;
		}
		return (endUs - startUs) / 1000.0;
	}

	public void shutdown() {
		if (initialized && extensionEnabled && device != null) {
			applyMode(ReflexMode.OFF);
			if (sleepSemaphore != 0) {
				vkDestroySemaphore(device, sleepSemaphore, null);
				sleepSemaphore = 0;
			}
		}
		initialized = false;
		extensionEnabled = false;
		device = null;
		swapchain = 0;
		sleepSemaphoreValue = 0;
		latencySnapshot = LatencySnapshot.unavailable("not initialized");
	}

	private record LatencySnapshot(boolean available, long presentId, double totalMs, double renderSubmitMs, double gpuMs, String reason) {
		static LatencySnapshot available(long presentId, double totalMs, double renderSubmitMs, double gpuMs) {
			return new LatencySnapshot(true, presentId, totalMs, renderSubmitMs, gpuMs, "");
		}

		static LatencySnapshot unavailable(String reason) {
			return new LatencySnapshot(false, 0, 0.0, -1.0, -1.0, reason);
		}

		String formatLine() {
			if (!available) {
				return "Reflex latency: unavailable (" + reason + ")";
			}

			String line = String.format(Locale.ROOT, "Reflex latency: %.2f ms", totalMs);
			if (renderSubmitMs >= 0.0) {
				line += String.format(Locale.ROOT, ", submit %.2f ms", renderSubmitMs);
			}
			if (gpuMs >= 0.0) {
				line += String.format(Locale.ROOT, ", GPU %.2f ms", gpuMs);
			}
			return line + " (present " + presentId + ")";
		}
	}
}
