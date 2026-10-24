package com.bambooz.reflexium.upscale.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.framegen.FrameGenerationConstants;
import com.bambooz.reflexium.upscale.UpscaleQuality;
import com.bambooz.reflexium.vulkan.ImageRef;
import com.bambooz.reflexium.vulkan.VulkanSession;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Streamline integration state. The SDK is NVIDIA Streamline 2.14.16, loaded from
 * {@code <game>/reflexium/streamline}. Manual Vulkan hooking: the game owns the instance, device and
 * swapchain, and Streamline receives them through {@code slSetVulkanInfo} plus the swapchain/present
 * proxies. On by default; {@code -Dreflexium.streamline=false} selects the raw NGX fallback.
 */
public final class StreamlineSupport {
	private static final boolean REQUESTED = !"false".equalsIgnoreCase(System.getProperty("reflexium.streamline"));
	private static final String[] RUNTIME_DLLS = {
			"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_g.dll",
			"sl.reflex.dll", "sl.pcl.dll", "NvLowLatencyVk.dll"
	};
	private static final MemorySegment CONSTANTS = Arena.global().allocate(FrameGenerationConstants.FLOATS * 4L, 4);

	private static StreamlineBridge bridge;
	private static boolean attempted;
	private static boolean initialized;
	private static Boolean override;
	private static boolean deviceReady;
	private static boolean dlssAvailable;
	private static boolean dlssgAvailable;
	private static boolean reflexAvailable;
	private static boolean pclAvailable;
	private static int dlssgMaxGenerated;
	private static boolean dlssgDynamicSupported;
	private static int dlssgStatus;
	private static int dlssgFramesPresented;
	private static int dlssgMinDimension;
	private static int appliedFgMode = -1;
	private static int appliedFgGenerated = -1;
	private static int optionsLogs;
	private static boolean appliedUiRecomposition;
	private static String problem = "";
	private static Set<String> instanceExtensions = Set.of();
	private static Set<String> deviceExtensions = Set.of();
	private static Set<String> features12 = Set.of();
	private static Set<String> features13 = Set.of();
	private static int frameIndex;
	private static long lastLog;

	private StreamlineSupport() {
	}

	/** True when Streamline is selected: the property default, or an explicit debug override. */
	public static boolean requested() { return override != null ? override : REQUESTED; }

	/** Overrides Streamline selection for the next launch only; instance/device extensions are fixed at creation. */
	public static void setEnabled(boolean value) { override = value; }

	public static boolean initialized() { return initialized; }
	public static boolean deviceReady() { return deviceReady; }
	public static boolean dlssAvailable() { return deviceReady && dlssAvailable; }
	public static boolean dlssgAvailable() { return deviceReady && dlssgAvailable; }
	public static boolean reflexAvailable() { return deviceReady && reflexAvailable; }
	public static boolean pclAvailable() { return deviceReady && pclAvailable; }
	public static int maximumGeneratedFrames() { return dlssgMaxGenerated; }
	public static boolean dynamicMfgSupported() { return dlssgDynamicSupported; }
	public static int frameGenerationStatus() { return dlssgStatus; }
	public static int presentedFrameCount() { return dlssgFramesPresented; }
	public static int minimumFrameGenerationDimension() { return dlssgMinDimension; }
	public static int appliedMode() { return appliedFgMode; }
	public static int appliedGenerated() { return appliedFgGenerated; }
	public static String problem() { return problem; }
	public static String status() {
		if (!requested()) return "Streamline: off";
		if (initialized) {
			if (!deviceReady) return "Streamline: " + problem;
			return "Streamline: DLSS " + (dlssAvailable ? "ready" : "unavailable")
					+ ", DLSS-G " + (dlssgAvailable ? "ready, max " + (dlssgMaxGenerated + 1) + "x" + (dlssgDynamicSupported ? ", dynamic" : "") : "unavailable")
					+ ", Reflex " + (reflexAvailable ? "ready" : "unavailable")
					+ (pclAvailable ? ", PCL ready" : "");
		}
		return "Streamline: " + (problem.isEmpty() ? "not initialized" : problem);
	}

	public static Set<String> instanceExtensions() { return instanceExtensions; }
	public static Set<String> deviceExtensions() { return deviceExtensions; }
	public static Set<String> features12() { return features12; }
	public static Set<String> features13() { return features13; }

	public static Path runtimeFolder() { return DlssSupport.featureFolder().resolve("streamline"); }

	/** Extracts the signed Streamline runtime from the jar and copies SHA-checked NGX runtimes beside it. */
	public static Path installRuntime() throws IOException {
		Path folder = runtimeFolder();
		Files.createDirectories(folder);
		for (String name : RUNTIME_DLLS) {
			byte[] bytes;
			try (InputStream in = StreamlineSupport.class.getResourceAsStream("/streamline/" + name)) {
				if (in == null) throw new IOException("Streamline runtime missing from the jar: " + name);
				bytes = in.readAllBytes();
			}
			Path target = folder.resolve(name);
			if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) continue;
			Path temp = Files.createTempFile(folder, name, ".tmp");
			Files.write(temp, bytes);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		for (String ngx : new String[] {DlssRuntimeInstaller.FILE_NAME, DlssRuntimeInstaller.FG_FILE_NAME}) {
			Path source = DlssSupport.featureFolder().resolve(ngx);
			Path target = folder.resolve(ngx);
			if (Files.isRegularFile(source) && !Files.isRegularFile(target)) {
				Files.copy(source, target);
			}
		}
		return folder;
	}

	/** Called before the game creates its Vulkan instance. */
	public static synchronized void beforeInstance() {
		if (!requested()) return;
		if (attempted) return;
		attempted = true;
		try {
			Path folder = installRuntime();
			bridge = StreamlineBridge.get().orElse(null);
			if (bridge == null) {
				problem = "bridge unavailable";
				return;
			}
			int result = bridge.init(folder, DlssSupport.featureFolder().resolve("streamline-logs"), "26.3");
			String log = bridge.takeLog();
			if (!log.isEmpty()) ReflexiumClient.LOGGER.info("[Streamline] {}", log.trim());
			if (result != 0) {
				problem = "init failed " + result;
				return;
			}
			initialized = true;
			instanceExtensions = split(bridge.instanceExtensions());
			deviceExtensions = split(bridge.deviceExtensions());
			features12 = split(bridge.features12());
			features13 = split(bridge.features13());
			ReflexiumClient.LOGGER.info("Streamline {} loaded; instance {}, device {}, features12 {}, features13 {}",
					ReflexiumClient.LOGGER.isDebugEnabled() ? bridge.dlssVersion() : "2.14.16",
					instanceExtensions, deviceExtensions, features12, features13);
		} catch (Throwable t) {
			problem = t.getMessage() == null ? t.toString() : t.getMessage();
			ReflexiumClient.LOGGER.warn("Streamline initialization failed; using the raw NGX path", t);
		}
	}

	/** Adds Streamline's instance extensions when the loader reports them. */
	public static Set<String> extendInstanceExtensions(Set<String> original, Set<String> supported) {
		if (!initialized) return original;
		Set<String> result = new LinkedHashSet<>(original);
		for (String name : instanceExtensions) {
			if (supported.contains(name)) result.add(name);
			else ReflexiumClient.LOGGER.info("Streamline wants instance extension {} but it is unavailable", name);
		}
		return result;
	}

	/** Called after the Vulkan device exists. */
	public static void onDeviceCreated(VulkanSession session, int graphicsFamily, int graphicsIndex) {
		if (!initialized || session == null || !session.isNvidia()) return;
		if (bridge == null) return;
		int result = bridge.setDevice(session.instance().address(), session.physicalDevice().address(),
				session.device().address(), graphicsFamily, graphicsIndex, graphicsFamily, graphicsIndex,
				graphicsFamily, graphicsIndex);
		deviceReady = result == 0;
		dlssAvailable = bridge.dlssAvailable();
		dlssgAvailable = bridge.dlssgAvailable();
		reflexAvailable = bridge.reflexAvailable();
		pclAvailable = bridge.pclAvailable();
		refreshFrameGenerationState();
		problem = deviceReady ? "" : "device setup failed " + result;
		String log = bridge.takeLog();
		if (!log.isEmpty()) ReflexiumClient.LOGGER.info("[Streamline] {}", log.trim());
		ReflexiumClient.LOGGER.info("Streamline device: {} ({}), version {}", status(), session.deviceName(), bridge.dlssVersion());
	}

	/** Refreshes the DLSS-G multiplier, support and runtime status. Render thread only. */
	public static int[] refreshFrameGenerationState() {
		if (!deviceReady || bridge == null || !dlssgAvailable) return null;
		int[] state = bridge.dlssgState();
		if (state == null) return null;
		dlssgMaxGenerated = Math.max(0, state[0]);
		dlssgDynamicSupported = state[1] != 0;
		dlssgStatus = state[2];
		dlssgFramesPresented = state[3];
		dlssgMinDimension = state[4];
		return state;
	}

	/**
	 * Highest multiplier Streamline DLSS-G can generate (generated frames + 1), or 1 when unavailable.
	 * Before the first DLSS-G query reports a count, assume the SDK ceiling instead of clamping fixed
	 * multipliers down; the state is refreshed on every present.
	 */
	public static int maximumMultiplier() {
		if (!dlssgAvailable) return 1;
		return dlssgMaxGenerated <= 0 ? 8 : Math.min(8, dlssgMaxGenerated + 1);
	}

	/**
	 * Enables or disables Streamline frame generation. Options only take effect at the next present and the
	 * SDK warns about redundant per-frame calls, so identical configurations are suppressed.
	 */
	public static int setFrameGeneration(int mode, int generatedFrames, boolean uiRecomposition) {
		if (!dlssgAvailable || bridge == null) return 1;
		int generated = Math.max(1, generatedFrames);
		if (mode == appliedFgMode && generated == appliedFgGenerated && uiRecomposition == appliedUiRecomposition) return 0;
		int result = bridge.dlssgSetOptions(mode, generated, uiRecomposition, true);
		if (result != 0) {
			problem = "DLSS-G options failed " + result;
			logProblem();
			return result;
		}
		if (optionsLogs < 12) {
			optionsLogs++;
			ReflexiumClient.LOGGER.info("DLSS-G options applied: mode {}, generated {}, ui {}, token {}", mode, generated,
					uiRecomposition, frameIndex);
		}
		appliedFgMode = mode;
		appliedFgGenerated = generated;
		appliedUiRecomposition = uiRecomposition;
		return 0;
	}

	/** Forces the next {@link #setFrameGeneration} call through, for a new swapchain or a device reset. */
	public static void invalidateFrameGenerationOptions() {
		appliedFgMode = -1;
		appliedFgGenerated = -1;
	}

	public static final int TAG_DEPTH = 1, TAG_MOTION = 2, TAG_HUDLESS = 4, TAG_UI_ALPHA = 8;

	public static int tagFrameGeneration(long commandBuffer, ImageRef depth, ImageRef motion, ImageRef hudless,
			ImageRef uiAlpha, int mask) {
		if (!dlssgAvailable || bridge == null) return 1;
		return bridge.dlssgTags(commandBuffer, depth, motion, hudless, uiAlpha, mask);
	}

	public static int tagFrameGenerationUi(long commandBuffer, ImageRef uiAlpha) {
		if (!dlssgAvailable || bridge == null) return 1;
		return bridge.dlssgTags(commandBuffer, null, null, null, uiAlpha, TAG_UI_ALPHA);
	}

	public static int clearFrameGenerationTags(long commandBuffer) {
		if (!dlssgAvailable || bridge == null) return 1;
		return bridge.dlssgClearTags(commandBuffer);
	}

	/** One Streamline token per real frame, used by SR tags/constants, Reflex sleep and PCL markers. */
	public static void beginFrame(boolean reset) {
		if (!deviceReady || bridge == null) return;
		frameIndex++;
		int result = bridge.beginFrame(frameIndex, reset);
		if (result != 0) {
			problem = "frame token failed " + result;
			deviceReady = false;
			logProblem();
		}
	}

	/** Writes standard camera constants for this frame and sends them to Streamline. */
	public static void setConstants(Matrix4fc projection, Matrix4fc clipToPrevious, Quaternionfc orientation,
			float x, float y, float z, float jitterX, float jitterY, float near, float far, float fov,
			float aspect, boolean reset, int renderWidth, int renderHeight, boolean flipY) {
		if (!deviceReady || bridge == null) return;
		FrameGenerationConstants.write(CONSTANTS, projection, clipToPrevious, orientation, x, y, z,
				jitterX, jitterY, near, far, fov, aspect, 0.0f, flipY);
		int result = bridge.setConstants(CONSTANTS, reset, renderWidth, renderHeight, flipY);
		if (result != 0) {
			// A single rejected frame must not disable SR and Reflex for the rest of the session.
			problem = "constants failed " + result;
			logProblem();
		}
	}

	public static int[] optimalSettings(int displayWidth, int displayHeight, UpscaleQuality mode, boolean hdr) {
		if (!deviceReady || bridge == null) return null;
		return bridge.dlssOptimal(slMode(mode), displayWidth, displayHeight, hdr);
	}

	public static int setDlssOptions(int displayWidth, int displayHeight, UpscaleQuality mode, boolean hdr, int preset) {
		if (!deviceReady || bridge == null) return 1;
		int result = bridge.dlssSetOptions(slMode(mode), displayWidth, displayHeight, hdr, preset);
		if (result != 0) {
			problem = "DLSS options failed " + result;
			deviceReady = false;
			logProblem();
		}
		return result;
	}

	public static int evaluateDlss(long commandBuffer, ImageRef color, ImageRef depth, ImageRef motion, ImageRef output) {
		if (!deviceReady || bridge == null) return 1;
		int result = bridge.evaluateDlss(commandBuffer, color, depth, motion, output);
		if (result != 0) logProblem();
		return result;
	}

	public static void freeDlss() {
		if (bridge != null && deviceReady) bridge.freeDlss();
	}

	public static int setReflexMode(int mode, int frameLimitUs) {
		if (!reflexAvailable || bridge == null) return 1;
		int result = bridge.reflexOptions(mode, frameLimitUs);
		if (result != 0) {
			problem = "Reflex options failed " + result;
			reflexAvailable = false;
			logProblem();
		}
		return result;
	}

	public static int reflexSleep() {
		if (!reflexAvailable || bridge == null) return 1;
		return bridge.reflexSleep();
	}

	public static int pclMarker(int marker) {
		if (!pclAvailable || bridge == null) return 1;
		return bridge.pclMarker(marker);
	}

	public static String takeLog() {
		if (bridge == null) return "";
		return bridge.takeLog();
	}

	public static StreamlineBridge.ReflexReport reflexReport() {
		if (!reflexAvailable || bridge == null) return null;
		return bridge.reflexReport();
	}

	/** lowLatencyAvailable, latencyReportAvailable; null when unavailable. */
	public static boolean[] reflexFlags() {
		if (!reflexAvailable || bridge == null) return null;
		return bridge.reflexFlags();
	}

	public static boolean swapchainReady() {
		return deviceReady && bridge != null && bridge.swapchainReady();
	}

	public static int createSwapchain(long device, long createInfo, long allocator, long out) {
		return bridge == null ? -1 : bridge.createSwapchain(device, createInfo, allocator, out);
	}
	public static void destroySwapchain(long device, long swapchain, long allocator) {
		if (bridge != null) bridge.destroySwapchain(device, swapchain, allocator);
	}
	public static int getSwapchainImages(long device, long swapchain, long count, long images) {
		return bridge == null ? -1 : bridge.getSwapchainImages(device, swapchain, count, images);
	}
	public static int acquireNextImage(long device, long swapchain, long timeout, long semaphore, long fence, long index) {
		return bridge == null ? -1 : bridge.acquireNextImage(device, swapchain, timeout, semaphore, fence, index);
	}
	public static int present(long queue, long presentInfo) {
		return bridge == null ? -1 : bridge.present(queue, presentInfo);
	}

	public static void shutdown() {
		if (bridge != null && initialized) bridge.shutdown();
		deviceReady = false;
		initialized = false;
		dlssgAvailable = false;
		dlssgMaxGenerated = 0;
		dlssgDynamicSupported = false;
		dlssgStatus = 0;
		dlssgFramesPresented = 0;
		invalidateFrameGenerationOptions();
	}

	private static int slMode(UpscaleQuality mode) {
		// Streamline DLSSMode: eOff=0, eMaxPerformance=1, eBalanced=2, eMaxQuality=3,
		// eUltraPerformance=4, eUltraQuality=5, eDLAA=6.
		return switch (mode) {
			case NATIVE_AA -> 6;
			case QUALITY -> 3;
			case BALANCED -> 2;
			case PERFORMANCE -> 1;
			case ULTRA_PERFORMANCE -> 4;
		};
	}

	private static Set<String> split(String text) {
		if (text == null || text.isBlank()) return Set.of();
		Set<String> result = new LinkedHashSet<>();
		for (String part : text.trim().split("\\s+")) if (!part.isBlank()) result.add(part);
		return Set.copyOf(result);
	}

	private static void logProblem() {
		long now = System.nanoTime();
		if (now - lastLog < 2_000_000_000L) return;
		lastLog = now;
		ReflexiumClient.LOGGER.warn("Streamline: {}", problem);
		if (bridge != null) {
			String log = bridge.takeLog();
			if (!log.isEmpty()) ReflexiumClient.LOGGER.warn("[Streamline] {}", log.trim());
		}
	}
}

