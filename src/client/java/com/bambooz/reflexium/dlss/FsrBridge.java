package com.bambooz.reflexium.dlss;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** FSR 3.1 Vulkan API. Resources and calls are owned by the render thread. */
final class FsrBridge {
	private final MethodHandle create, evaluate, generateReactive, release;
	private final MethodHandle fgCreate, fgEvaluate, fgRelease;
	private final MemorySegment images = Arena.global().allocate(192, 8);

	FsrBridge() throws java.io.IOException {
		this(NgxBridge.extract("amd_fidelityfx_vk"), NgxBridge.extract("reflexium_fsr"));
	}

	/** Also used by the offline ABI check, before Fabric has a game directory or a Vulkan device. */
	FsrBridge(java.nio.file.Path dependency, java.nio.file.Path bridge) {
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

	int createFrameGeneration(long device, long physical, long gdpa, int rw, int rh, int dw, int dh) {
		try { return (int) fgCreate.invokeExact(MemorySegment.ofAddress(device), MemorySegment.ofAddress(physical),
				MemorySegment.ofAddress(gdpa), rw, rh, dw, dh); }
		catch (Throwable t) { throw new IllegalStateException("FSR FG creation failed", t); }
	}

	int evaluateFrameGeneration(long commands, NgxBridge.Image color, NgxBridge.Image depth, NgxBridge.Image motion,
			NgxBridge.Image output, MemorySegment constants, long frameId, boolean reset) {
		try { return (int) fgEvaluate.invokeExact(MemorySegment.ofAddress(commands), color.write(images, 0),
				depth.write(images, 32), motion.write(images, 64), output.write(images, 96), constants, frameId, reset ? 1 : 0); }
		catch (Throwable t) { throw new IllegalStateException("FSR FG dispatch failed", t); }
	}

	void releaseFrameGeneration() {
		try { int result = (int) fgRelease.invokeExact(); if (result != 0) throw new IllegalStateException("FSR FG drain/release failed: " + result); }
		catch (Throwable t) { throw new IllegalStateException("FSR FG release failed", t); }
	}

	int create(long device, long physical, long gdpa, int rw, int rh, int dw, int dh, boolean hdr) {
		try { return (int) create.invokeExact(MemorySegment.ofAddress(device), MemorySegment.ofAddress(physical),
				MemorySegment.ofAddress(gdpa), rw, rh, dw, dh, hdr ? 1 : 0); }
		catch (Throwable t) { throw new IllegalStateException("FSR context creation failed", t); }
	}

	int evaluate(long commands, NgxBridge.Image color, NgxBridge.Image depth, NgxBridge.Image motion,
			NgxBridge.Image output, NgxBridge.Image reactive, NgxBridge.Image composition, float jitterX, float jitterY, boolean reset, float frameMs,
			float near, float far, float fov, float sharpness) {
		MemorySegment reactivePointer = reactive == null ? MemorySegment.NULL : reactive.write(images, 128);
		MemorySegment compositionPointer = composition == null ? MemorySegment.NULL : composition.write(images, 160);
		try { return (int) evaluate.invokeExact(MemorySegment.ofAddress(commands), color.write(images, 0),
				depth.write(images, 32), motion.write(images, 64), output.write(images, 96),
				reactivePointer, compositionPointer,
				jitterX, jitterY, reset ? 1 : 0, frameMs, near, far, fov, sharpness); }
		catch (Throwable t) { throw new IllegalStateException("FSR dispatch failed", t); }
	}

	int generateReactive(long commands, NgxBridge.Image opaque, NgxBridge.Image color, NgxBridge.Image reactive) {
		try { return (int) generateReactive.invokeExact(MemorySegment.ofAddress(commands), opaque.write(images, 0),
				color.write(images, 32), reactive.write(images, 64)); }
		catch (Throwable t) { throw new IllegalStateException("FSR reactive mask generation failed", t); }
	}

	void release() {
		try { release.invokeExact(); }
		catch (Throwable t) { throw new IllegalStateException("FSR release failed", t); }
	}
}
