package com.bambooz.reflexium.upscale.fsr;

import com.bambooz.reflexium.upscale.NativeLibraries;
import com.bambooz.reflexium.vulkan.ImageRef;
import com.bambooz.reflexium.upscale.dlss.NgxBridge;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/** FSR 3.1 Vulkan API. Resources and calls are owned by the render thread. */
public final class FsrBridge {
	private final MethodHandle create, evaluate, generateReactive, release;
	private final MethodHandle fgCreate, fgEvaluate, fgRelease;
	private final MemorySegment images = Arena.global().allocate(192, 8);

	public FsrBridge() throws IOException {
		this(NativeLibraries.extract("amd_fidelityfx_vk"), NativeLibraries.extract("reflexium_fsr"));
	}

	/** Also used by the offline ABI check, before Fabric has a game directory or a Vulkan device. */
	public FsrBridge(Path dependency, Path bridge) {
		// Resolve the signed AMD dependency before loading our import-library bridge.
		SymbolLookup.libraryLookup(dependency, Arena.global());
		SymbolLookup library = SymbolLookup.libraryLookup(bridge, Arena.global());
		Linker linker = Linker.nativeLinker();
		create = linker.downcallHandle(library.find("rx_fsr_create_v2").orElseThrow(), FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		evaluate = linker.downcallHandle(library.find("rx_fsr_evaluate_v2").orElseThrow(), FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT,
				JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
		generateReactive = linker.downcallHandle(library.find("rx_fsr_generate_reactive").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		release = linker.downcallHandle(library.find("rx_fsr_release").orElseThrow(), FunctionDescriptor.ofVoid());
		fgCreate = linker.downcallHandle(library.find("rx_fsr_fg_create").orElseThrow(), FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
		fgEvaluate = linker.downcallHandle(library.find("rx_fsr_fg_evaluate").orElseThrow(), FunctionDescriptor.of(JAVA_INT,
				ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
		fgRelease = linker.downcallHandle(library.find("rx_fsr_fg_release").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
	}

	public int createFrameGeneration(long device, long physical, long gdpa, int rw, int rh, int dw, int dh) {
		try { return (int) fgCreate.invokeExact(MemorySegment.ofAddress(device), MemorySegment.ofAddress(physical),
				MemorySegment.ofAddress(gdpa), rw, rh, dw, dh); }
		catch (Throwable t) { throw new IllegalStateException("FSR FG creation failed", t); }
	}

	public int evaluateFrameGeneration(long commands, ImageRef color, ImageRef depth, ImageRef motion,
			ImageRef output, MemorySegment constants, long frameId, boolean reset) {
		try { return (int) fgEvaluate.invokeExact(MemorySegment.ofAddress(commands), color.write(images, 0),
				depth.write(images, 32), motion.write(images, 64), output.write(images, 96), constants, frameId, reset ? 1 : 0); }
		catch (Throwable t) { throw new IllegalStateException("FSR FG dispatch failed", t); }
	}

	public void releaseFrameGeneration() {
		try { int result = (int) fgRelease.invokeExact(); if (result != 0) throw new IllegalStateException("FSR FG drain/release failed: " + result); }
		catch (Throwable t) { throw new IllegalStateException("FSR FG release failed", t); }
	}

	public int create(long device, long physical, long gdpa, int rw, int rh, int dw, int dh, boolean hdr) {
		try { return (int) create.invokeExact(MemorySegment.ofAddress(device), MemorySegment.ofAddress(physical),
				MemorySegment.ofAddress(gdpa), rw, rh, dw, dh, hdr ? 1 : 0); }
		catch (Throwable t) { throw new IllegalStateException("FSR context creation failed", t); }
	}

	public int evaluate(long commands, ImageRef color, ImageRef depth, ImageRef motion,
			ImageRef output, ImageRef reactive, ImageRef composition, float jitterX, float jitterY, boolean reset, float frameMs,
			float near, float far, float fov, float sharpness) {
		MemorySegment reactivePointer = reactive == null ? MemorySegment.NULL : reactive.write(images, 128);
		MemorySegment compositionPointer = composition == null ? MemorySegment.NULL : composition.write(images, 160);
		try { return (int) evaluate.invokeExact(MemorySegment.ofAddress(commands), color.write(images, 0),
				depth.write(images, 32), motion.write(images, 64), output.write(images, 96),
				reactivePointer, compositionPointer,
				jitterX, jitterY, reset ? 1 : 0, frameMs, near, far, fov, sharpness); }
		catch (Throwable t) { throw new IllegalStateException("FSR dispatch failed", t); }
	}

	public int generateReactive(long commands, ImageRef opaque, ImageRef color, ImageRef reactive) {
		try { return (int) generateReactive.invokeExact(MemorySegment.ofAddress(commands), opaque.write(images, 0),
				color.write(images, 32), reactive.write(images, 64)); }
		catch (Throwable t) { throw new IllegalStateException("FSR reactive mask generation failed", t); }
	}

	public void release() {
		try { release.invokeExact(); }
		catch (Throwable t) { throw new IllegalStateException("FSR release failed", t); }
	}
}
