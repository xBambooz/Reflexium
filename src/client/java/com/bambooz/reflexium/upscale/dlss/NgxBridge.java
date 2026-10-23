package com.bambooz.reflexium.upscale.dlss;

import com.bambooz.reflexium.upscale.NativeLibraries;
import com.bambooz.reflexium.vulkan.ImageRef;
import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.upscale.UpscaleQuality;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Foreign Function bindings for reflexium_ngx.dll (native/reflexium_ngx.cpp), which links NVIDIA's
 * static NGX library. The DLL ships inside the jar and is extracted to {@code <game>/reflexium/natives}.
 * All calls happen on the render thread.
 */
public final class NgxBridge {
	private static NgxBridge instance;
	private static boolean loadAttempted;

	private final MethodHandle requiredExtensions;
	private final MethodHandle init;
	private final MethodHandle dlssAvailable;
	private final MethodHandle optimalSettings;
	private final MethodHandle create;
	private final MethodHandle evaluate;
	private final MethodHandle release;
	private final MethodHandle shutdown;
	private final MethodHandle takeLog;
	private final MethodHandle fgAvailable, fgCreate, fgEvaluate, fgRelease;

	/** Reused per-frame argument memory (render thread only). */
	private final MemorySegment images = Arena.global().allocate(ImageRef.SIZE * 4, 8);
	private final MemorySegment outInts = Arena.global().allocate(JAVA_INT.byteSize() * 4, 4);

	private NgxBridge(SymbolLookup lookup) {
		Linker linker = Linker.nativeLinker();
		requiredExtensions = handle(linker, lookup, "rx_required_extensions", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		init = handle(linker, lookup, "rx_init", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
		dlssAvailable = handle(linker, lookup, "rx_dlss_available", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		optimalSettings = handle(linker, lookup, "rx_optimal_settings", FunctionDescriptor.of(JAVA_INT,
				JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
		create = handle(linker, lookup, "rx_create", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		evaluate = handle(linker, lookup, "rx_evaluate", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_FLOAT));
		release = handle(linker, lookup, "rx_release", FunctionDescriptor.ofVoid());
		shutdown = handle(linker, lookup, "rx_shutdown", FunctionDescriptor.ofVoid());
		takeLog = handle(linker, lookup, "rx_take_log", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		fgAvailable = handle(linker, lookup, "rx_fg_available", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		fgCreate = handle(linker, lookup, "rx_fg_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		fgEvaluate = handle(linker, lookup, "rx_fg_evaluate", FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
		fgRelease = handle(linker, lookup, "rx_fg_release", FunctionDescriptor.ofVoid());
	}

	public record FrameGenerationSupport(boolean available, int maxGenerated, int initResult, boolean needsDriverUpdate) {}

	public FrameGenerationSupport frameGenerationSupport() {
		try {
			int available = (int) fgAvailable.invokeExact(outInts, outInts.asSlice(4), outInts.asSlice(8));
			return new FrameGenerationSupport(available != 0, outInts.get(JAVA_INT, 0), outInts.get(JAVA_INT, 4), outInts.get(JAVA_INT, 8) != 0);
		} catch (Throwable t) { throw rethrow(t); }
	}

	public int createFrameGeneration(long commands, int width, int height, int format) {
		try { return (int) fgCreate.invokeExact(MemorySegment.ofAddress(commands), width, height, format); }
		catch (Throwable t) { throw rethrow(t); }
	}

	public int evaluateFrameGeneration(long commands, ImageRef color, ImageRef depth, ImageRef motion, ImageRef output,
			MemorySegment constants, long frameId, int generated, int index, boolean reset) {
		try { return (int) fgEvaluate.invokeExact(MemorySegment.ofAddress(commands), color.write(images, 0),
				depth.write(images, 32), motion.write(images, 64), output.write(images, 96), constants,
				frameId, generated, index, reset ? 1 : 0); }
		catch (Throwable t) { throw rethrow(t); }
	}

	public void releaseFrameGeneration() {
		try { fgRelease.invokeExact(); }
		catch (Throwable t) { throw rethrow(t); }
	}

	private static MethodHandle handle(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		return linker.downcallHandle(lookup.find(name).orElseThrow(() -> new IllegalStateException("missing export " + name)), descriptor);
	}

	/** Loads the bridge once. Empty when not on Windows or when extraction/loading failed (logged). */
	public static synchronized Optional<NgxBridge> get() {
		if (!loadAttempted) {
			loadAttempted = true;
			if (ReflexiumClient.isSupportedPlatform()) {
				try {
					Path dll = NativeLibraries.extract("reflexium_ngx");
					instance = new NgxBridge(SymbolLookup.libraryLookup(dll, Arena.global()));
				} catch (Throwable t) {
					ReflexiumClient.LOGGER.warn("Could not load the NGX bridge, DLSS is unavailable", t);
				}
			}
		}
		return Optional.ofNullable(instance);
	}

	/** Device extensions NGX requires, from the static library's list. */
	public String[] requiredDeviceExtensions() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(4096);
			int result = (int) requiredExtensions.invokeExact(out, 4096);
			if (result != 0) {
				ReflexiumClient.LOGGER.warn("NGX extension query failed: 0x{}", Integer.toHexString(result));
				return new String[0];
			}
			String text = out.getString(0, StandardCharsets.UTF_8);
			int bar = text.indexOf('|');
			String device = bar < 0 ? "" : text.substring(bar + 1).trim();
			return device.isEmpty() ? new String[0] : device.split(" ");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public int init(long instance, long physicalDevice, long device, long gipa, long gdpa,
			Path dataPath, Path featurePath, String engineVersion, boolean verboseLog) {
		try (Arena arena = Arena.ofConfined()) {
			return (int) init.invokeExact(
					MemorySegment.ofAddress(instance), MemorySegment.ofAddress(physicalDevice), MemorySegment.ofAddress(device),
					MemorySegment.ofAddress(gipa), MemorySegment.ofAddress(gdpa),
					wide(arena, dataPath.toAbsolutePath().toString()), wide(arena, featurePath.toAbsolutePath().toString()),
					arena.allocateFrom(engineVersion, StandardCharsets.UTF_8), verboseLog ? 2 : 1);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** {@code [available, needsDriverUpdate, minDriverMajor, minDriverMinor, featureInitResult]}. */
	public int[] dlssAvailable() {
		try {
			MemorySegment needs = outInts;
			MemorySegment major = outInts.asSlice(4);
			MemorySegment minor = outInts.asSlice(8);
			MemorySegment initResult = outInts.asSlice(12);
			int available = (int) dlssAvailable.invokeExact(needs, major, minor, initResult);
			return new int[] {available, needs.get(JAVA_INT, 0), major.get(JAVA_INT, 0), minor.get(JAVA_INT, 0), initResult.get(JAVA_INT, 0)};
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Render resolution DLSS recommends for this display size and mode, or null on failure. */
	public int[] optimalSettings(int displayWidth, int displayHeight, UpscaleQuality mode) {
		try {
			MemorySegment w = outInts;
			MemorySegment h = outInts.asSlice(4);
			int result = (int) optimalSettings.invokeExact(displayWidth, displayHeight, mode.ngxValue(), w, h);
			int width = w.get(JAVA_INT, 0);
			int height = h.get(JAVA_INT, 0);
			return result == 0 && width > 0 && height > 0 ? new int[] {width, height} : null;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public int create(long commandBuffer, int renderWidth, int renderHeight, int displayWidth, int displayHeight, UpscaleQuality mode, int flags, int preset) {
		try {
			return (int) create.invokeExact(MemorySegment.ofAddress(commandBuffer), renderWidth, renderHeight,
					displayWidth, displayHeight, mode.ngxValue(), flags, preset);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public int evaluate(long commandBuffer, ImageRef color, ImageRef depth, ImageRef motion, ImageRef output,
			float jitterX, float jitterY, float mvScaleX, float mvScaleY, boolean reset, float frameTimeMs) {
		try {
			MemorySegment c = color.write(images, 0);
			MemorySegment d = depth.write(images, ImageRef.SIZE);
			MemorySegment m = motion.write(images, ImageRef.SIZE * 2);
			MemorySegment o = output.write(images, ImageRef.SIZE * 3);
			return (int) evaluate.invokeExact(MemorySegment.ofAddress(commandBuffer), c, d, m, o,
					jitterX, jitterY, mvScaleX, mvScaleY, reset ? 1 : 0, frameTimeMs);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public void release() {
		try {
			release.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public void shutdown() {
		try {
			shutdown.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** NGX log lines collected since the last call, or an empty string. */
	public String takeLog() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(65536);
			int n = (int) takeLog.invokeExact(out, 65536);
			return n <= 0 ? "" : out.getString(0, StandardCharsets.UTF_8);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static MemorySegment wide(Arena arena, String text) {
		return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

}
