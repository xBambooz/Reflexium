package com.bambooz.reflexium.upscale.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.upscale.NativeLibraries;
import com.bambooz.reflexium.vulkan.ImageRef;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Foreign Function bindings for reflexium_sl.dll (native/reflexium_sl.cpp), the Streamline bridge. */
public final class StreamlineBridge {
	private static StreamlineBridge instance;
	private static boolean loadAttempted;

	private final MethodHandle init;
	private final MethodHandle instanceExtensions;
	private final MethodHandle deviceExtensions;
	private final MethodHandle features12;
	private final MethodHandle features13;
	private final MethodHandle queueRequirements;
	private final MethodHandle setDevice;
	private final MethodHandle dlssAvailable;
	private final MethodHandle dlssgAvailable;
	private final MethodHandle reflexAvailable;
	private final MethodHandle pclAvailable;
	private final MethodHandle dlssOptimal;
	private final MethodHandle dlssSetOptions;
	private final MethodHandle dlssgState;
	private final MethodHandle dlssgSetOptions;
	private final MethodHandle dlssgTags;
	private final MethodHandle dlssgClearTags;
	private final MethodHandle beginFrame;
	private final MethodHandle setConstants;
	private final MethodHandle dlssEvaluate;
	private final MethodHandle dlssFree;
	private final MethodHandle dlssVersion;
	private final MethodHandle reflexOptions;
	private final MethodHandle reflexSleep;
	private final MethodHandle pclMarker;
	private final MethodHandle reflexReport;
	private final MethodHandle reflexFlags;
	private final MethodHandle swapchainReady;
	private final MethodHandle createSwapchain;
	private final MethodHandle destroySwapchain;
	private final MethodHandle getSwapchainImages;
	private final MethodHandle acquireNextImage;
	private final MethodHandle present;
	private final MethodHandle deviceWaitIdle;
	private final MethodHandle shutdown;
	private final MethodHandle takeLog;

	private final MemorySegment images = Arena.global().allocate(ImageRef.SIZE * 4, 8);
	private final MemorySegment outInts = Arena.global().allocate(JAVA_INT.byteSize() * 8, 4);

	private StreamlineBridge(SymbolLookup lookup) {
		Linker linker = Linker.nativeLinker();
		init = handle(linker, lookup, "rx_sl_init", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		instanceExtensions = handle(linker, lookup, "rx_sl_instance_extensions", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		deviceExtensions = handle(linker, lookup, "rx_sl_device_extensions", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		features12 = handle(linker, lookup, "rx_sl_features12", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		features13 = handle(linker, lookup, "rx_sl_features13", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		queueRequirements = handle(linker, lookup, "rx_sl_queue_requirements", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		setDevice = handle(linker, lookup, "rx_sl_set_device", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		dlssAvailable = handle(linker, lookup, "rx_sl_dlss_available", FunctionDescriptor.of(JAVA_INT));
		dlssgAvailable = handle(linker, lookup, "rx_sl_dlssg_available", FunctionDescriptor.of(JAVA_INT));
		reflexAvailable = handle(linker, lookup, "rx_sl_reflex_available", FunctionDescriptor.of(JAVA_INT));
		pclAvailable = handle(linker, lookup, "rx_sl_pcl_available", FunctionDescriptor.of(JAVA_INT));
		dlssOptimal = handle(linker, lookup, "rx_sl_dlss_optimal", FunctionDescriptor.of(JAVA_INT,
				JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
		dlssSetOptions = handle(linker, lookup, "rx_sl_dlss_set_options", FunctionDescriptor.of(JAVA_INT,
				JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		dlssgState = handle(linker, lookup, "rx_sl_dlssg_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		dlssgSetOptions = handle(linker, lookup, "rx_sl_dlssg_set_options", FunctionDescriptor.of(JAVA_INT,
				JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		dlssgTags = handle(linker, lookup, "rx_sl_dlssg_tags", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
		dlssgClearTags = handle(linker, lookup, "rx_sl_dlssg_clear_tags", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		beginFrame = handle(linker, lookup, "rx_sl_begin_frame", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
		setConstants = handle(linker, lookup, "rx_sl_set_constants", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		dlssEvaluate = handle(linker, lookup, "rx_sl_dlss_evaluate", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		dlssFree = handle(linker, lookup, "rx_sl_free_dlss", FunctionDescriptor.ofVoid());
		dlssVersion = handle(linker, lookup, "rx_sl_dlss_version", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		reflexOptions = handle(linker, lookup, "rx_sl_reflex_options", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
		reflexSleep = handle(linker, lookup, "rx_sl_reflex_sleep", FunctionDescriptor.of(JAVA_INT));
		pclMarker = handle(linker, lookup, "rx_sl_pcl_marker", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
		reflexReport = handle(linker, lookup, "rx_sl_reflex_report", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		reflexFlags = handle(linker, lookup, "rx_sl_reflex_flags", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		swapchainReady = handle(linker, lookup, "rx_sl_swapchain_ready", FunctionDescriptor.of(JAVA_INT));
		createSwapchain = handle(linker, lookup, "rx_sl_create_swapchain", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		destroySwapchain = handle(linker, lookup, "rx_sl_destroy_swapchain", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
		getSwapchainImages = handle(linker, lookup, "rx_sl_get_swapchain_images", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
		acquireNextImage = handle(linker, lookup, "rx_sl_acquire_next_image", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
		present = handle(linker, lookup, "rx_sl_present", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		deviceWaitIdle = handle(linker, lookup, "rx_sl_device_wait_idle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		shutdown = handle(linker, lookup, "rx_sl_shutdown", FunctionDescriptor.ofVoid());
		takeLog = handle(linker, lookup, "rx_sl_take_log", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
	}

	/** Loads the bridge once. Empty when not on Windows or when extraction/loading failed (logged). */
	public static synchronized Optional<StreamlineBridge> get() {
		if (!loadAttempted) {
			loadAttempted = true;
			if (ReflexiumClient.isSupportedPlatform()) {
				try {
					Path dll = NativeLibraries.extract("reflexium_sl");
					instance = new StreamlineBridge(SymbolLookup.libraryLookup(dll, Arena.global()));
				} catch (Throwable t) {
					ReflexiumClient.LOGGER.warn("Could not load the Streamline bridge, Streamline is unavailable", t);
				}
			}
		}
		return Optional.ofNullable(instance);
	}

	public int init(Path pluginDir, Path logDir, String engineVersion) {
		try (Arena arena = Arena.ofConfined()) {
			return (int) init.invokeExact(wide(arena, pluginDir.toAbsolutePath().toString()),
					wide(arena, logDir.toAbsolutePath().toString()),
					arena.allocateFrom(engineVersion, StandardCharsets.UTF_8));
		} catch (Throwable t) { throw rethrow(t); }
	}

	private String extensionList(MethodHandle handle) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(8192);
			int result = (int) handle.invokeExact(out, 8192);
			return result <= 0 ? "" : out.getString(0, StandardCharsets.UTF_8);
		} catch (Throwable t) { throw rethrow(t); }
	}

	public String instanceExtensions() { return extensionList(instanceExtensions); }
	public String deviceExtensions() { return extensionList(deviceExtensions); }
	public String features12() { return extensionList(features12); }
	public String features13() { return extensionList(features13); }

	public int[] queueRequirements() {
		try {
			int result = (int) queueRequirements.invokeExact(outInts, outInts.asSlice(4), outInts.asSlice(8));
			if (result != 0) return new int[3];
			return new int[] {outInts.get(JAVA_INT, 0), outInts.get(JAVA_INT, 4), outInts.get(JAVA_INT, 8)};
		} catch (Throwable t) { throw rethrow(t); }
	}

	public int setDevice(long instance, long physicalDevice, long device, int graphicsFamily, int graphicsIndex,
			int computeFamily, int computeIndex, int opticalFlowFamily, int opticalFlowIndex) {
		try {
			return (int) setDevice.invokeExact(MemorySegment.ofAddress(instance), MemorySegment.ofAddress(physicalDevice),
					MemorySegment.ofAddress(device), graphicsFamily, graphicsIndex, computeFamily, computeIndex,
					opticalFlowFamily, opticalFlowIndex);
		} catch (Throwable t) { throw rethrow(t); }
	}

	public boolean dlssAvailable() { return invokeInt(dlssAvailable) != 0; }
	public boolean reflexAvailable() { return invokeInt(reflexAvailable) != 0; }
	public boolean pclAvailable() { return invokeInt(pclAvailable) != 0; }

	public int[] dlssOptimal(int mode, int outputWidth, int outputHeight, boolean hdr) {
		try {
			int result = (int) dlssOptimal.invokeExact(mode, outputWidth, outputHeight, hdr ? 1 : 0, outInts, outInts.asSlice(4));
			int width = outInts.get(JAVA_INT, 0);
			int height = outInts.get(JAVA_INT, 4);
			return result == 0 && width > 0 && height > 0 ? new int[] {width, height} : null;
		} catch (Throwable t) { throw rethrow(t); }
	}

	public int dlssSetOptions(int mode, int outputWidth, int outputHeight, boolean hdr, int preset) {
		return invokeInt(dlssSetOptions, mode, outputWidth, outputHeight, hdr ? 1 : 0, preset);
	}

	public boolean dlssgAvailable() { return invokeInt(dlssgAvailable) != 0; }

	/** maxGenerated, dynamicSupported, status, framesPresentedSinceLastCall, minimumWidthOrHeight. */
	public int[] dlssgState() {
		try {
			int result = (int) dlssgState.invokeExact(outInts, 8);
			if (result != 0) return null;
			return new int[] {outInts.get(JAVA_INT, 0), outInts.get(JAVA_INT, 4), outInts.get(JAVA_INT, 8),
					outInts.get(JAVA_INT, 12), outInts.get(JAVA_INT, 16)};
		} catch (Throwable t) { throw rethrow(t); }
	}

	public int dlssgSetOptions(int mode, int generatedFrames, boolean uiRecomposition, boolean retainResources) {
		return invokeInt(dlssgSetOptions, mode, generatedFrames, uiRecomposition ? 1 : 0, retainResources ? 1 : 0);
	}

	/** Tags DLSS-G inputs selected by mask (1 depth, 2 motion, 4 hudless, 8 UI alpha); an image may be null. */
	public int dlssgTags(long commandBuffer, ImageRef depth, ImageRef motion, ImageRef hudless, ImageRef uiAlpha, int mask) {
		MemorySegment depthSegment = depth == null ? MemorySegment.NULL : depth.write(images, 0);
		MemorySegment motionSegment = motion == null ? MemorySegment.NULL : motion.write(images, ImageRef.SIZE);
		MemorySegment hudlessSegment = hudless == null ? MemorySegment.NULL : hudless.write(images, ImageRef.SIZE * 2);
		MemorySegment uiSegment = uiAlpha == null ? MemorySegment.NULL : uiAlpha.write(images, ImageRef.SIZE * 3);
		try {
			return (int) dlssgTags.invokeExact(MemorySegment.ofAddress(commandBuffer),
					depthSegment, motionSegment, hudlessSegment, uiSegment, mask);
		} catch (Throwable t) { throw rethrow(t); }
	}

	public int dlssgClearTags(long commandBuffer) {
		try { return (int) dlssgClearTags.invokeExact(MemorySegment.ofAddress(commandBuffer)); }
		catch (Throwable t) { throw rethrow(t); }
	}

	public int beginFrame(int index, boolean reset) { return invokeInt(beginFrame, index, reset ? 1 : 0); }
	public int setConstants(MemorySegment constants, boolean reset, int renderWidth, int renderHeight, boolean flipY) {
		try { return (int) setConstants.invokeExact(constants, reset ? 1 : 0, renderWidth, renderHeight, flipY ? 1 : 0); }
		catch (Throwable t) { throw rethrow(t); }
	}

	public int evaluateDlss(long commandBuffer, ImageRef color, ImageRef depth, ImageRef motion, ImageRef output) {
		try {
			return (int) dlssEvaluate.invokeExact(MemorySegment.ofAddress(commandBuffer),
					color.write(images, 0), depth.write(images, ImageRef.SIZE),
					motion.write(images, ImageRef.SIZE * 2), output.write(images, ImageRef.SIZE * 3));
		} catch (Throwable t) { throw rethrow(t); }
	}

	public void freeDlss() { invokeVoid(dlssFree); }

	public String dlssVersion() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(128);
			int n = (int) dlssVersion.invokeExact(out, 128);
			return n <= 0 ? "" : out.getString(0, StandardCharsets.UTF_8);
		} catch (Throwable t) { throw rethrow(t); }
	}

	public int reflexOptions(int mode, int frameLimitUs) { return invokeInt(reflexOptions, mode, frameLimitUs); }
	public int reflexSleep() { return invokeInt(reflexSleep); }
	public int pclMarker(int marker) { return invokeInt(pclMarker, marker); }

	/** Present id plus 13 timestamps in microseconds, or null. */
	public ReflexReport reflexReport() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment id = arena.allocate(JAVA_LONG);
			MemorySegment times = arena.allocate(JAVA_LONG, 13);
			int result = (int) reflexReport.invokeExact(id, times);
			if (result == 2 || result == 3 || result > 100) return null;
			long[] values = new long[13];
			for (int i = 0; i < values.length; i++) values[i] = times.get(JAVA_LONG, i * 8L);
			return new ReflexReport(id.get(JAVA_LONG, 0), values);
		} catch (Throwable t) { throw rethrow(t); }
	}

	/** lowLatencyAvailable, latencyReportAvailable; null when Reflex state could not be read. */
	public boolean[] reflexFlags() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment low = arena.allocate(JAVA_INT);
			MemorySegment report = arena.allocate(JAVA_INT);
			int result = (int) reflexFlags.invokeExact(low, report);
			if (result != 0) return null;
			return new boolean[] {low.get(JAVA_INT, 0) != 0, report.get(JAVA_INT, 0) != 0};
		} catch (Throwable t) { throw rethrow(t); }
	}

	public record ReflexReport(long presentId, long[] times) { }

	public boolean swapchainReady() { return invokeInt(swapchainReady) != 0; }
	public int createSwapchain(long device, long createInfo, long allocator, long out) {
		return invokeInt(createSwapchain, MemorySegment.ofAddress(device), MemorySegment.ofAddress(createInfo),
				MemorySegment.ofAddress(allocator), MemorySegment.ofAddress(out));
	}
	public void destroySwapchain(long device, long swapchain, long allocator) {
		try { destroySwapchain.invokeExact(MemorySegment.ofAddress(device), swapchain, MemorySegment.ofAddress(allocator)); }
		catch (Throwable t) { throw rethrow(t); }
	}
	public int getSwapchainImages(long device, long swapchain, long count, long images) {
		return invokeInt(getSwapchainImages, MemorySegment.ofAddress(device), swapchain,
				MemorySegment.ofAddress(count), MemorySegment.ofAddress(images));
	}
	public int acquireNextImage(long device, long swapchain, long timeout, long semaphore, long fence, long index) {
		return invokeInt(acquireNextImage, MemorySegment.ofAddress(device), swapchain, timeout, semaphore, fence,
				MemorySegment.ofAddress(index));
	}
	public int present(long queue, long presentInfo) {
		return invokeInt(present, MemorySegment.ofAddress(queue), MemorySegment.ofAddress(presentInfo));
	}
	public int deviceWaitIdle(long device) { return invokeInt(deviceWaitIdle, MemorySegment.ofAddress(device)); }
	public void shutdown() { invokeVoid(shutdown); }

	public String takeLog() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(65536);
			int n = (int) takeLog.invokeExact(out, 65536);
			return n <= 0 ? "" : out.getString(0, StandardCharsets.UTF_8);
		} catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, int... args) {
		try {
			return switch (args.length) {
				case 0 -> (int) handle.invokeExact();
				case 1 -> (int) handle.invokeExact(args[0]);
				case 2 -> (int) handle.invokeExact(args[0], args[1]);
				case 4 -> (int) handle.invokeExact(args[0], args[1], args[2], args[3]);
				case 5 -> (int) handle.invokeExact(args[0], args[1], args[2], args[3], args[4]);
				default -> throw new IllegalArgumentException("unsupported arity " + args.length);
			};
		} catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, MemorySegment first, int a, int b, int c) {
		try { return (int) handle.invokeExact(first, a, b, c); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, MemorySegment a) {
		try { return (int) handle.invokeExact(a); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, MemorySegment a, MemorySegment b) {
		try { return (int) handle.invokeExact(a, b); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, MemorySegment a, MemorySegment b, MemorySegment c, MemorySegment d) {
		try { return (int) handle.invokeExact(a, b, c, d); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, MemorySegment a, long b, MemorySegment c, MemorySegment d) {
		try { return (int) handle.invokeExact(a, b, c, d); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, MemorySegment a, long b, long c, long d, long e, MemorySegment f) {
		try { return (int) handle.invokeExact(a, b, c, d, e, f); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, int a) {
		try { return (int) handle.invokeExact(a); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private int invokeInt(MethodHandle handle, long a) {
		try { return (int) handle.invokeExact(MemorySegment.ofAddress(a)); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private void invokeVoid(MethodHandle handle) {
		try { handle.invokeExact(); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private static MethodHandle handle(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		return linker.downcallHandle(lookup.find(name).orElseThrow(() -> new IllegalStateException("missing export " + name)), descriptor);
	}

	private static MemorySegment wide(Arena arena, String text) {
		return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}
}
