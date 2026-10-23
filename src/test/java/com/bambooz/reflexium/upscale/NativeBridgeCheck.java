package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.upscale.dlss.NgxBridge;
import com.bambooz.reflexium.upscale.fsr.FsrBridge;
import com.bambooz.reflexium.upscale.fsr.FsrSwapchain;
import com.bambooz.reflexium.vulkan.ImageRef;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Packaging contracts that would otherwise fail only in game: typed native call sites, the bundled FSR
 * bridge's argument handling, and strict JSON resources. Run by Gradle check with the project directory as
 * working directory.
 */
public final class NativeBridgeCheck {
	public static void main(String[] args) throws Exception {
		// Signature-polymorphic calls can compile a conditional MemorySegment as Object.
		// Check the actual bytecode ABI, since invokeExact rejects that only during native dispatch.
		for (Class<?> bridge : new Class<?>[] {FsrBridge.class, NgxBridge.class, FsrSwapchain.class}) {
			var bytecode = new ClassNode();
			try (var in = bridge.getResourceAsStream(bridge.getSimpleName() + ".class")) {
				new ClassReader(in).accept(bytecode, 0);
			}
			int dispatches = 0;
			for (var method : bytecode.methods) for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call
						&& call.owner.equals("java/lang/invoke/MethodHandle") && call.name.equals("invokeExact")) {
					if (call.desc.contains("Ljava/lang/Object;")) throw new AssertionError("Untyped native ABI in " + bridge + ": " + call.desc);
					dispatches++;
				}
			}
			if (dispatches == 0) throw new AssertionError("No native ABI calls checked in " + bridge);
		}
		fsrAbi();
		try (var paths = Files.walk(Path.of("src"))) {
			for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
				try (var reader = new JsonReader(Files.newBufferedReader(path))) {
					reader.setStrictness(Strictness.STRICT);
					JsonParser.parseReader(reader);
					if (reader.peek() != JsonToken.END_DOCUMENT) throw new AssertionError("Trailing JSON: " + path);
				}
			}
		}
		System.out.println("Native bridge and resource checks passed: typed native ABI, FSR argument handling, strict JSON");
	}

	/** No Vulkan calls: native rejects evaluation without a context before reading resources. */
	private static void fsrAbi() {
		if (!System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64")) return;
		var folder = Path.of("src/client/resources/natives/windows-x86_64").toAbsolutePath();
		FsrBridge bridge = new FsrBridge(folder.resolve("amd_fidelityfx_vk.dll"), folder.resolve("reflexium_fsr.dll"));
		ImageRef dummy = new ImageRef(0, 0, 37, 1, 1, 1);
		for (ImageRef mask : new ImageRef[]{null, dummy}) {
			if (bridge.evaluate(0, dummy, dummy, dummy, dummy, mask, mask, 0, 0, true, 16.6f, 0.05f, 1024f, 1.2f, 0) != -1)
				throw new AssertionError("FSR evaluate without a context did not reject");
		}
	}
}
